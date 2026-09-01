package dev.charanjeev.bahi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.charanjeev.bahi.core.designsystem.theme.BahiTheme
import dev.charanjeev.bahi.core.sync.work.SyncScheduler
import dev.charanjeev.bahi.ui.BahiApp
import javax.inject.Inject

/**
 * Single-activity architecture: this is the only Activity in the app.
 * Every screen is a Compose destination inside [BahiApp]'s nav host.
 *
 * That also makes this Activity's own lifecycle the app's foreground signal
 * -- no separate `ProcessLifecycleOwner` observer is needed for §8.7's
 * "app moves to foreground" sync trigger the way a multi-activity app would
 * need one; [onStart] firing *is* the app coming to the foreground here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BahiTheme {
                BahiApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        syncScheduler.requestExpeditedSync()
    }
}
