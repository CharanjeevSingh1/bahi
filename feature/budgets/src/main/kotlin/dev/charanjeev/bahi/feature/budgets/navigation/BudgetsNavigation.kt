package dev.charanjeev.bahi.feature.budgets.navigation

import androidx.compose.material3.Text
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val BudgetsRoute = "budgets"

/** Placeholder destination. Real screen lands in the milestone that owns it. */
fun NavGraphBuilder.budgetsScreen() {
    composable(route = BudgetsRoute) {
        Text("Budgets")
    }
}
