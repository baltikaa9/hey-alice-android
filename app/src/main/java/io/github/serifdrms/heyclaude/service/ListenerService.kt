package io.github.serifdrms.heyclaude.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
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
import io.github.serifdrms.heyclaude.launch.ClaudeLauncher
import io.github.serifdrms.heyclaude.launch.LaunchResult
import io.github.serifdrms.heyclaude.settings.AppSettings
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge
import io.github.serifdrms.heyclaude.wakeword.ModelIntegrityException
import io.github.serifdrms.heyclaude.wakeword.WakeWordEngine
import java.util.concurrent.Executors

/**
 * Foreground service that listens for the wake word while the phone is unlocked.
 *
 * Android only lets a microphone foreground service start while the app is on screen, so it
 * is started from the Start button and then keeps running. The microphone itself is opened
 * and closed as the screen is unlocked and locked.
 */
class ListenerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings
    private var engine: WakeWordEngine? = null
    private var mic: MicCapture? = null
    private var receiverRegistered = false
    private var inCooldown = false

    // Shizuku calls block briefly, so they run here instead of on the main thread.
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var audio: AudioManager

    // Claude keeps the microphone open while its voice mode is up. Listening at the same time
    // can leave Claude hearing silence, so we stay off until nobody else is recording.
    // Android only tells us that some recording exists, not whose it is or what it hears.
    private val recordingWatch = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            onMicUsageChanged()
        }
    }
    private val resumeWhenMicFree = Runnable { resumeIfUnlocked() }

    private val resumeAfterCooldown = Runnable {
        inCooldown = false
        resumeIfUnlocked()
    }

    // USER_PRESENT can arrive a moment before the keyguard reports itself unlocked, so a
    // single check right after the broadcast isn't enough. Poll briefly while the screen is on.
    private var unlockChecks = 0
    private val unlockWatch = object : Runnable {
        override fun run() {
            if (isUnlocked()) {
                resumeIfUnlocked()
                return
            }
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            if (screenOn && ++unlockChecks < UNLOCK_CHECK_LIMIT) {
                main.postDelayed(this, UNLOCK_CHECK_INTERVAL_MS)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    main.removeCallbacks(unlockWatch)
                    pause(ListenerStatus.PausedLocked)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> watchForUnlock()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        settings.onThresholdChanged { engine?.detector?.threshold = it }
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioRecordingCallback(recordingWatch, main)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECHECK) {
            // The UI is on screen, so the phone is certainly unlocked.
            if (engine != null) watchForUnlock() else stopSelf()
            return START_NOT_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ListenerState.setStatus(ListenerStatus.Error(ErrorReason.PERMISSION))
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Thrown when the start didn't come from the foreground (e.g. a restart by the system).
            Log.w(TAG, "Could not enter the foreground", e)
            ListenerState.setStatus(ListenerStatus.Stopped)
            stopSelf()
            return START_NOT_STICKY
        }

        if (engine == null && !loadEngine()) {
            stopSelf()
            return START_NOT_STICKY
        }
        engine?.detector?.threshold = settings.threshold
        background.execute { ShizukuBridge.prepare() }

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        if (isUnlocked()) resumeIfUnlocked() else pause(ListenerStatus.PausedLocked)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        settings.onThresholdChanged(null)
        audio.unregisterAudioRecordingCallback(recordingWatch)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        receiverRegistered = false
        mic?.shutdown()
        mic = null
        background.shutdown()
        engine?.close()
        engine = null
        // Keep a startup error on screen; otherwise show that listening has stopped.
        if (ListenerState.status.value !is ListenerStatus.Error) {
            ListenerState.setStatus(ListenerStatus.Stopped)
        }
        super.onDestroy()
    }

    private fun loadEngine(): Boolean = try {
        val loaded = WakeWordEngine.load(this, settings.threshold)
        engine = loaded
        ListenerState.setPhrases(loaded.phrases)
        mic = MicCapture(this, ::onChunk, ::onMicError)
        true
    } catch (e: ModelIntegrityException) {
        Log.e(TAG, "Model check failed", e)
        ListenerState.setStatus(ListenerStatus.Error(ErrorReason.MODELS, e.message))
        false
    } catch (e: Exception) {
        Log.e(TAG, "Could not load models", e)
        ListenerState.setStatus(ListenerStatus.Error(ErrorReason.MODELS, e.message))
        false
    }

    /** Runs on the capture thread. */
    private fun onChunk(chunk: ShortArray) {
        val detector = engine?.detector ?: return
        val hit = detector.process(chunk)
        ListenerState.setLevel(detector.lastScore)
        if (hit != null) main.post { onWakeWord() }
    }

    private fun onWakeWord() {
        if (inCooldown) return
        Log.i(TAG, "Wake word detected")
        // Release the mic first so Claude can open it right away.
        pause(ListenerStatus.PausedCooldown)
        inCooldown = true
        main.postDelayed(resumeAfterCooldown, settings.cooldownSeconds * 1000L)
        val method = settings.launchMethod
        background.execute {
            if (ClaudeLauncher.launch(this, method) == LaunchResult.FAILED) {
                Log.w(TAG, "Claude could not be opened")
            }
        }
    }

    private fun onMicError(error: Throwable) {
        Log.e(TAG, "Microphone error", error)
        main.post { ListenerState.setStatus(ListenerStatus.Error(ErrorReason.MICROPHONE, error.message)) }
    }

    private fun pause(status: ListenerStatus) {
        mic?.stop()
        if (status == ListenerStatus.PausedLocked) {
            // Locking the screen cancels any cooldown; unlocking starts fresh.
            main.removeCallbacks(resumeAfterCooldown)
            inCooldown = false
        }
        ListenerState.setStatus(status)
    }

    private fun watchForUnlock() {
        unlockChecks = 0
        main.removeCallbacks(unlockWatch)
        main.post(unlockWatch)
    }

    private fun othersRecording(): Boolean {
        val own = mic?.sessionId ?: -1
        return audio.activeRecordingConfigurations.any { it.clientAudioSessionId != own }
    }

    private fun onMicUsageChanged() {
        val capture = mic ?: return
        if (othersRecording()) {
            main.removeCallbacks(resumeWhenMicFree)
            if (capture.isRunning) pause(ListenerStatus.PausedMicBusy)
        } else if (ListenerState.status.value == ListenerStatus.PausedMicBusy) {
            // Wait a little: voice apps often release the mic briefly between turns.
            main.removeCallbacks(resumeWhenMicFree)
            main.postDelayed(resumeWhenMicFree, MIC_FREE_DELAY_MS)
        }
    }

    private fun resumeIfUnlocked() {
        if (inCooldown || !isUnlocked()) return
        val capture = mic ?: return
        if (capture.isRunning) return
        if (othersRecording()) {
            ListenerState.setStatus(ListenerStatus.PausedMicBusy)
            return
        }
        engine?.detector?.apply {
            threshold = settings.threshold
            reset()
        }
        capture.start()
        if (capture.isRunning) ListenerState.setStatus(ListenerStatus.Listening)
    }

    private fun isUnlocked(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
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
        private const val ACTION_STOP = "io.github.serifdrms.heyclaude.action.STOP"
        private const val ACTION_RECHECK = "io.github.serifdrms.heyclaude.action.RECHECK"
        private const val UNLOCK_CHECK_INTERVAL_MS = 500L
        private const val UNLOCK_CHECK_LIMIT = 120 // one minute of polling at most
        private const val MIC_FREE_DELAY_MS = 4_000L

        /** Must be called while the app is on screen. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java))
        }

        /** Asks a running service to look at the lock state again. Call while the UI is visible. */
        fun recheck(context: Context) {
            context.startService(Intent(context, ListenerService::class.java).setAction(ACTION_RECHECK))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
