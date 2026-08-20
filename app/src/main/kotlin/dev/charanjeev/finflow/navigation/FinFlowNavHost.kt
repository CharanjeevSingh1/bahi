package dev.charanjeev.finflow.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import dev.charanjeev.finflow.feature.transactions.navigation.TransactionsRoute
import dev.charanjeev.finflow.feature.transactions.navigation.transactionsScreen

/**
 * The app module is the only place that knows about every feature. Features
 * expose NavGraphBuilder extensions and never reference each other directly,
 * which is what keeps them independently buildable.
 */
@Composable
fun FinFlowNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = TransactionsRoute,
    ) {
        transactionsScreen()
        // budgetsScreen()
        // insightsScreen()
        // settingsScreen()
    }
}
