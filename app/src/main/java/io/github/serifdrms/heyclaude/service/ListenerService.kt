package io.github.serifdrms.heyclaude.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.serifdrms.heyclaude.MainActivity
import io.github.serifdrms.heyclaude.R
import io.github.serifdrms.heyclaude.audio.MicCapture
import io.github.serifdrms.heyclaude.launch.AliceActivation
import io.github.serifdrms.heyclaude.settings.AppSettings
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge
import io.github.serifdrms.heyclaude.shizuku.ShizukuStatus
import io.github.serifdrms.heyclaude.wakeword.WakeWordEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Microphone foreground service started by the user from the visible app. */
class ListenerService : Service(), AliceActivation.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: AppSettings
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var engine: WakeWordEngine? = null
    private var mic: MicCapture? = null
    private var foreground = false
    private var stopping = false
    private var inCooldown = false
    private var notifiedStatus: ListenerStatus = ListenerStatus.Starting

    private val resumeWhenMicFree = Runnable { resumeListening() }
    private val resumeAfterCooldown = Runnable {
        inCooldown = false
        resumeListening()
    }

    private val recordingWatch = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = onMicUsageChanged()
    }
    private val modeWatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { onMicUsageChanged() }
    } else null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        settings.onThresholdChanged { engine?.detector?.threshold = it }
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioRecordingCallback(recordingWatch, main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeWatch?.let { audio.addOnModeChangedListener(mainExecutor, it) }
        }
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:Listening")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (foreground) return START_NOT_STICKY

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(ErrorReason.PERMISSION)
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(ListenerStatus.Starting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            foreground = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enter the foreground", e)
            fail(ErrorReason.FOREGROUND)
            return START_NOT_STICKY
        }

        scope.launch(Dispatchers.IO) {
            val loaded = try {
                WakeWordEngine.load(this@ListenerService, settings.threshold)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load wake word model", e)
                withContext(Dispatchers.Main.immediate) { fail(ErrorReason.MODELS, e.message) }
                return@launch
            }
            var adopted = false
            try {
                withContext(Dispatchers.Main.immediate) {
                    if (!stopping) {
                        attachRecorder(loaded)
                        adopted = true
                    }
                }
            } finally {
                // Cancellation can happen while the model is still loading or about to be delivered.
                if (!adopted) loaded.close()
            }
        }
        return START_NOT_STICKY
    }

    private fun attachRecorder(loaded: WakeWordEngine) {
        engine = loaded
        mic = MicCapture(this, { chunk ->
            val detector = loaded.detector
            val hit = detector.process(chunk)
            ListenerState.setLevel(detector.lastScore)
            if (hit != null) main.post {
                if (!stopping && mic?.isRunning == true && !inCooldown) {
                    Log.i(TAG, "Wake word detected")
                    AliceActivation.request()
                }
            }
        }, { error ->
            Log.e(TAG, "Microphone error", error)
            main.post { if (!stopping) fail(ErrorReason.MICROPHONE, error.message) }
        })

        AliceActivation.attach(this)
        scope.launch {
            ShizukuBridge.status.collect { status ->
                if (status == ShizukuStatus.READY) {
                    AliceActivation.prepare()
                    resumeListening()
                } else if (!inCooldown) {
                    pause(ListenerStatus.PausedShizuku)
                }
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        foreground = false
        AliceActivation.detach(this)
        scope.cancel()
        settings.onThresholdChanged(null)
        audio.unregisterAudioRecordingCallback(recordingWatch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modeWatch?.let(audio::removeOnModeChangedListener)
        }
        mic?.shutdown()
        main.removeCallbacksAndMessages(null)
        engine?.close()
        if (wakeLock.isHeld) wakeLock.release()
        if (ListenerState.status.value !is ListenerStatus.Error) ListenerState.setStatus(ListenerStatus.Stopped)
        super.onDestroy()
    }

    override fun releaseMicrophone() {
        inCooldown = true
        main.removeCallbacks(resumeAfterCooldown)
        pause(ListenerStatus.PausedCooldown)
    }

    override fun onActivationFinished(success: Boolean) {
        if (stopping) return
        val delay = if (success) settings.cooldownSeconds * 1_000L else MIC_FREE_DELAY_MS
        main.postDelayed(resumeAfterCooldown, delay)
    }

    private fun microphoneBusy(): Boolean {
        if (audio.mode != AudioManager.MODE_NORMAL) return true
        val own = mic?.sessionId ?: -1
        return audio.activeRecordingConfigurations.any { !it.isClientSilenced && it.clientAudioSessionId != own }
    }

    private fun onMicUsageChanged() {
        if (stopping || mic == null || inCooldown || ShizukuBridge.status.value != ShizukuStatus.READY) return
        main.removeCallbacks(resumeWhenMicFree)
        if (microphoneBusy()) {
            pause(ListenerStatus.PausedMicBusy)
        } else if (ListenerState.status.value == ListenerStatus.PausedMicBusy) {
            main.postDelayed(resumeWhenMicFree, MIC_FREE_DELAY_MS)
        }
    }

    private fun resumeListening() {
        if (stopping || inCooldown) return
        val capture = mic ?: return
        when {
            ShizukuBridge.status.value != ShizukuStatus.READY -> pause(ListenerStatus.PausedShizuku)
            capture.isRunning -> Unit
            microphoneBusy() -> pause(ListenerStatus.PausedMicBusy)
            else -> {
                engine?.detector?.reset()
                capture.start()
                if (capture.isRunning) updateStatus(ListenerStatus.Listening)
            }
        }
    }

    private fun pause(status: ListenerStatus) {
        mic?.stop()
        updateStatus(status)
    }

    private fun fail(reason: ErrorReason, detail: String? = null) {
        stopping = true
        ListenerState.setStatus(ListenerStatus.Error(reason, detail))
        stopSelf()
    }

    @SuppressLint("WakelockTimeout", "MissingPermission")
    private fun updateStatus(status: ListenerStatus) {
        ListenerState.setStatus(status)
        val keepAwake = status == ListenerStatus.Listening || status == ListenerStatus.PausedCooldown
        if (keepAwake && !wakeLock.isHeld) wakeLock.acquire()
        if (!keepAwake && wakeLock.isHeld) wakeLock.release()
        if (foreground && status != notifiedStatus) {
            notifiedStatus = status
            notifications.notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun buildNotification(status: ListenerStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val message = when (status) {
            ListenerStatus.PausedShizuku -> R.string.notification_shizuku
            ListenerStatus.PausedCooldown -> R.string.status_paused_cooldown
            ListenerStatus.PausedMicBusy -> R.string.status_paused_mic_busy
            ListenerStatus.Starting -> R.string.status_starting
            else -> R.string.notification_text
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(message))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ListenerService"
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "io.github.baltikaa9.heyalice.action.START"
        private const val ACTION_STOP = "io.github.baltikaa9.heyalice.action.STOP"
        private const val MIC_FREE_DELAY_MS = 4_000L

        /** Must be called while the app is on screen. */
        fun start(context: Context) {
            ListenerState.setStatus(ListenerStatus.Starting)
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, ListenerService::class.java).setAction(ACTION_START),
                )
            } catch (e: RuntimeException) {
                ListenerState.setStatus(ListenerStatus.Error(ErrorReason.FOREGROUND, e.message))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
