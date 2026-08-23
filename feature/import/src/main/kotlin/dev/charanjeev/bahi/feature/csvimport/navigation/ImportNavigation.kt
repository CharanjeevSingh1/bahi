package dev.charanjeev.bahi.feature.csvimport.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import dev.charanjeev.bahi.feature.csvimport.ImportRoute as ImportScreen

const val ImportRoute = "import"

/** Matches TransactionsNavigation's pattern: each feature owns its own entry point. */
fun NavGraphBuilder.importScreen(navController: NavHostController) {
    composable(route = ImportRoute) {
        ImportScreen(onNavigateBack = { navController.popBackStack() })
    }
}
