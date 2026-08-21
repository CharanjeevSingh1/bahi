package dev.charanjeev.bahi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.charanjeev.bahi.core.designsystem.theme.BahiTheme
import dev.charanjeev.bahi.navigation.BahiNavHost

/**
 * Single-activity architecture: this is the only Activity in the app.
 * Every screen is a Compose destination inside [BahiNavHost].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BahiTheme {
                BahiNavHost()
            }
        }
    }
}
