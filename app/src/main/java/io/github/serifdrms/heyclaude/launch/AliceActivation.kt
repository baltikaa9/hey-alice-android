package io.github.serifdrms.heyclaude.launch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge
import io.github.serifdrms.heyclaude.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** Shared by the microphone service, Bluetooth activity and setup screen. */
object AliceActivation {
    const val ALICE_PACKAGE = "com.yandex.aliceapp"
    private const val REPEAT_INTERVAL_MS = 2_000L
    private const val BINDER_DELIVERY_TIMEOUT_MS = 1_200L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var injecting = false
    private var nextAllowed = 0L
    private var listener: Listener? = null
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()

    interface Listener {
        fun releaseMicrophone()
        fun onActivationFinished(success: Boolean)
    }

    @MainThread
    fun attach(listener: Listener) {
        this.listener = listener
        if (injecting) listener.releaseMicrophone()
    }

    @MainThread
    fun detach(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun prepare() {
        worker.execute { ShizukuBridge.prepare() }
    }

    @MainThread
    fun request(onFinished: (Boolean) -> Unit = {}) {
        if (injecting || SystemClock.elapsedRealtime() < nextAllowed) {
            onFinished(true)
            return
        }

        injecting = true
        listener?.releaseMicrophone()
        worker.execute {
            val success = runCatching {
                ShizukuBridge.refresh()
                if (ShizukuBridge.status.value == ShizukuStatus.NOT_RUNNING) {
                    // A Bluetooth cold start can arrive before the provider receives the binder.
                    runBlocking {
                        withTimeoutOrNull(BINDER_DELIVERY_TIMEOUT_MS) {
                            ShizukuBridge.status.first { it != ShizukuStatus.NOT_RUNNING }
                        }
                    }
                }
                ShizukuBridge.pressAssistKey()
            }.getOrDefault(false)
            main.post {
                injecting = false
                nextAllowed = SystemClock.elapsedRealtime() + REPEAT_INTERVAL_MS
                _failed.value = !success
                listener?.onActivationFinished(success)
                onFinished(success)
            }
        }
    }
}
