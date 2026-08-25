package dev.charanjeev.bahi.feature.transactions.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.charanjeev.bahi.feature.transactions.TransactionFormRoute as TransactionFormScreen
import dev.charanjeev.bahi.feature.transactions.TransactionsRoute as TransactionsScreen

const val TransactionsRoute = "transactions"
const val NewTransactionRoute = "transactions/new"
private const val EditTransactionRoute = "transactions/edit"
const val TransactionIdArg = "transactionId"

fun editTransactionRoute(transactionId: String) = "$EditTransactionRoute/$transactionId"

/**
 * Each feature owns its own navigation entry point. The app module wires them
 * together without ever importing a feature's internals -- add/edit navigate
 * within this one graph, using the NavHostController the app module already
 * owns, so the app module still never sees TransactionFormScreen or its
 * ViewModel directly.
 *
 * M1 upgrade: swap the string routes for @Serializable type-safe routes once
 * the serialization plugin is on the feature convention.
 */
fun NavGraphBuilder.transactionsScreen(
    navController: NavHostController,
    onImportClick: () -> Unit = {},
) {
    composable(route = TransactionsRoute) {
        TransactionsScreen(
            onAddTransaction = { navController.navigate(NewTransactionRoute) },
            onTransactionClick = { id -> navController.navigate(editTransactionRoute(id)) },
            onImportClick = onImportClick,
        )
    }
    composable(route = NewTransactionRoute) {
        TransactionFormScreen(onNavigateBack = { navController.popBackStack() })
    }
    composable(
        route = "$EditTransactionRoute/{$TransactionIdArg}",
        arguments = listOf(navArgument(TransactionIdArg) { type = NavType.StringType }),
    ) {
        TransactionFormScreen(onNavigateBack = { navController.popBackStack() })
    }
}
