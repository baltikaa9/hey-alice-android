package io.github.serifdrms.heyclaude.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.github.serifdrms.heyclaude.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class ShizukuStatus {
    /** The Shizuku app is not installed. */
    NOT_INSTALLED,

    /** Installed, but its service hasn't been started since the last reboot. */
    NOT_RUNNING,

    /** A Shizuku version too old to talk to. */
    UNSUPPORTED,

    /** Running, but this app hasn't been allowed yet. */
    NO_PERMISSION,

    READY,
}

/**
 * Talks to Shizuku and to our [AssistKeyService] running under it.
 *
 * Shizuku delivers its binder and service callbacks on the main thread, so [pressAssistKey]
 * and [prepare] block and must be called from a background thread.
 */
object ShizukuBridge {

    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val TAG = "ShizukuBridge"
    private const val PERMISSION_REQUEST = 4201
    private const val BIND_TIMEOUT_MS = 3_000L

    private lateinit var appContext: Context

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile private var service: IAssistKeyService? = null
    @Volatile private var pending: CountDownLatch? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, AssistKeyService::class.java.name))
            .daemon(false)
            .processNameSuffix("assist")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IAssistKeyService.Stub.asInterface(binder) else null
            pending?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            pending?.countDown()
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST) refresh()
        }
        refresh()
    }

    fun refresh() {
        _status.value = computeStatus()
    }

    private fun computeStatus(): ShizukuStatus {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return if (isManagerInstalled()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        return try {
            when {
                Shizuku.isPreV11() -> ShizukuStatus.UNSUPPORTED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuStatus.NO_PERMISSION
                else -> ShizukuStatus.READY
            }
        } catch (e: RuntimeException) {
            // The binder can die between the ping and the call.
            ShizukuStatus.NOT_RUNNING
        }
    }

    private fun isManagerInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Shows Shizuku's permission dialog. Returns false when the user picked "deny and don't ask
     * again", in which case the permission has to be granted inside the Shizuku app.
     */
    fun requestPermission(): Boolean = try {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            false
        } else {
            Shizuku.requestPermission(PERMISSION_REQUEST)
            true
        }
    } catch (e: RuntimeException) {
        refresh()
        false
    }

    /** Opens the Shizuku app, where the service is started after a reboot. */
    fun openManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Starts the helper process ahead of time so the first wake word isn't slowed down. */
    fun prepare() {
        if (_status.value == ShizukuStatus.READY) connect()
    }

    /** Presses the assistant key. Blocks for up to a few seconds; call off the main thread. */
    fun pressAssistKey(): Boolean {
        refresh()
        if (_status.value != ShizukuStatus.READY) return false
        val remote = connect() ?: return false
        return try {
            remote.pressAssistKey()
        } catch (e: RemoteException) {
            Log.w(TAG, "Helper process went away", e)
            service = null
            refresh()
            false
        }
    }

    @Synchronized
    private fun connect(): IAssistKeyService? {
        service?.let { if (it.asBinder().pingBinder()) return it }
        service = null

        val latch = CountDownLatch(1)
        pending = latch
        try {
            // Drop any stale registration first, or the callback would fire once per bind.
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, false) }
            Shizuku.bindUserService(serviceArgs, connection)
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return service
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the helper process", e)
            refresh()
            return null
        } finally {
            pending = null
        }
    }
}
