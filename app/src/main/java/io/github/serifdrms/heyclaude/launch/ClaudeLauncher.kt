package io.github.serifdrms.heyclaude.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.serifdrms.heyclaude.shizuku.ShizukuBridge

enum class LaunchMethod {
    /**
     * Presses the assistant key through Shizuku. Android then starts the default assistant the
     * same way the side key does, which for Claude opens straight into listening.
     */
    ASSIST_KEY,

    /**
     * Sends ACTION_ASSIST to Claude. Works without Shizuku, but Claude opens its text chat.
     */
    ASSIST_INTENT,
}

enum class LaunchResult { OPENED_VOICE, OPENED_TEXT, FAILED }

object ClaudeLauncher {

    const val PACKAGE = "com.anthropic.claude"
    private const val TAG = "ClaudeLauncher"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Opens Claude with [method], falling back to the text chat if Shizuku isn't available.
     * May block for a moment on the Shizuku path, so call it off the main thread.
     */
    fun launch(context: Context, method: LaunchMethod): LaunchResult {
        if (method == LaunchMethod.ASSIST_KEY && ShizukuBridge.pressAssistKey()) {
            return LaunchResult.OPENED_VOICE
        }
        return if (openTextChat(context)) LaunchResult.OPENED_TEXT else LaunchResult.FAILED
    }

    private fun openTextChat(context: Context): Boolean {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_ASSIST).setPackage(PACKAGE)
            .takeIf { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
            ?: pm.getLaunchIntentForPackage(PACKAGE)
            ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Claude could not be opened", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch refused", e)
            false
        }
    }
}
