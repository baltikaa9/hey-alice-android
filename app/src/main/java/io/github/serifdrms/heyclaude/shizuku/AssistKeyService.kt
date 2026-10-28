package io.github.serifdrms.heyclaude.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with shell (adb) privileges.
 *
 * It deliberately exposes exactly one operation. Nothing in it takes a command or arguments
 * from the caller, so the privileged process can't be used for anything but this key press.
 */
class AssistKeyService() : IAssistKeyService.Stub() {

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun pressAssistKey(): Boolean = try {
        val process = ProcessBuilder("/system/bin/input", "keyevent", "KEYCODE_ASSIST")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        finished && process.exitValue() == 0
    } catch (e: Exception) {
        Log.e(TAG, "Assist key failed", e)
        false
    }

    private companion object {
        const val TAG = "AssistKeyService"
    }
}
