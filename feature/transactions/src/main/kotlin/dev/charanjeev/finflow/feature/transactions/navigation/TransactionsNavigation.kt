package dev.charanjeev.finflow.feature.transactions.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.charanjeev.finflow.feature.transactions.TransactionsRoute as TransactionsScreen

const val TransactionsRoute = "transactions"

/**
 * Each feature owns its own navigation entry point. The app module wires them
 * together without ever importing a feature's internals.
 *
 * M1 upgrade: swap the string route for a @Serializable type-safe route once
 * the serialization plugin is on the feature convention.
 */
fun NavGraphBuilder.transactionsScreen() {
    composable(route = TransactionsRoute) {
        TransactionsScreen()
    }
}
