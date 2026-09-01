package dev.charanjeev.bahi.feature.settings.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.charanjeev.bahi.feature.settings.PassphraseRoute as PassphraseScreen

const val PassphraseRoute = "settings/passphrase"

/** Reached only from Settings' encryption row -- see [dev.charanjeev.bahi.feature.settings.SettingsScreen]'s doc on why that row is gated on `syncConfigured`. */
fun NavGraphBuilder.passphraseScreen(navController: NavHostController) {
    composable(route = PassphraseRoute) {
        PassphraseScreen(onNavigateBack = { navController.popBackStack() })
    }
}
