package io.github.serifdrms.heyclaude

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.serifdrms.heyclaude.ui.HomeScreen
import io.github.serifdrms.heyclaude.ui.theme.HeyClaudeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeyClaudeTheme {
                HomeScreen()
            }
        }
    }
}
