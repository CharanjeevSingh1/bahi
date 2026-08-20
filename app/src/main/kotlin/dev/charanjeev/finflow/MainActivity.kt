package dev.charanjeev.finflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.charanjeev.finflow.core.designsystem.theme.FinFlowTheme
import dev.charanjeev.finflow.navigation.FinFlowNavHost

/**
 * Single-activity architecture: this is the only Activity in the app.
 * Every screen is a Compose destination inside [FinFlowNavHost].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FinFlowTheme {
                FinFlowNavHost()
            }
        }
    }
}
