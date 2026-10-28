package io.github.serifdrms.heyclaude.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.serifdrms.heyclaude.launch.LaunchMethod
import io.github.serifdrms.heyclaude.wakeword.WakeWordDetector

class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, WakeWordDetector.DEFAULT_THRESHOLD)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)) }

    var launchMethod: LaunchMethod
        get() = prefs.getString(KEY_LAUNCH_METHOD, null)
            ?.let { name -> LaunchMethod.entries.firstOrNull { it.name == name } }
            ?: LaunchMethod.ASSIST_KEY
        set(value) = prefs.edit { putString(KEY_LAUNCH_METHOD, value.name) }

    /** How long to stay quiet after a detection, so Claude gets the microphone to itself. */
    var cooldownSeconds: Int
        get() = prefs.getInt(KEY_COOLDOWN, DEFAULT_COOLDOWN)
        set(value) = prefs.edit { putInt(KEY_COOLDOWN, value.coerceIn(MIN_COOLDOWN, MAX_COOLDOWN)) }

    private var thresholdListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Lets the running service pick up slider changes without a restart. */
    fun onThresholdChanged(block: ((Float) -> Unit)?) {
        thresholdListener?.let(prefs::unregisterOnSharedPreferenceChangeListener)
        thresholdListener = block?.let { callback ->
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_THRESHOLD) callback(threshold)
            }.also(prefs::registerOnSharedPreferenceChangeListener)
        }
    }

    companion object {
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_LAUNCH_METHOD = "launch_method"
        private const val KEY_COOLDOWN = "cooldown_seconds"

        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.9f
        const val DEFAULT_COOLDOWN = 10
        const val MIN_COOLDOWN = 3
        const val MAX_COOLDOWN = 60
    }
}
