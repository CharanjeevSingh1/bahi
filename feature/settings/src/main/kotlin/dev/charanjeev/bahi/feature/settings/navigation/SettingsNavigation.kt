package dev.charanjeev.bahi.feature.settings.navigation

import androidx.compose.material3.Text
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val SettingsRoute = "settings"

/** Placeholder destination. Real screen lands in the milestone that owns it. */
fun NavGraphBuilder.settingsScreen() {
    composable(route = SettingsRoute) {
        Text("Settings")
    }
}
