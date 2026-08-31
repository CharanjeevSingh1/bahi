package dev.charanjeev.bahi.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.charanjeev.bahi.feature.settings.SettingsRoute as SettingsScreen

const val SettingsRoute = "settings"

/**
 * A top-level destination, not nested in any tab's graph -- TopLevelDestination's
 * doc explains why Settings isn't a tab, and every top-level screen's own
 * top bar is what reaches this, not the bottom bar.
 */
fun NavGraphBuilder.settingsScreen(navController: NavHostController) {
    composable(route = SettingsRoute) {
        SettingsScreen(onNavigateBack = { navController.popBackStack() })
    }
}
