package io.github.serifdrms.heyclaude.launch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.serifdrms.heyclaude.R

/** Entry point for hands-free voice commands routed by Android's Bluetooth stack. */
class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_VOICE_COMMAND) {
            finish()
            return
        }

        // Keep the activity alive until a cold Shizuku bind finishes.
        setShowWhenLocked(true)
        AliceActivation.request { success ->
            if (!success) {
                Toast.makeText(applicationContext, R.string.launch_failed, Toast.LENGTH_LONG).show()
            }
            if (!isDestroyed) finish()
        }
    }
}
