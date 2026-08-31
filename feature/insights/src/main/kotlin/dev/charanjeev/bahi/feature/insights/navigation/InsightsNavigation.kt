package dev.charanjeev.bahi.feature.insights.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.charanjeev.bahi.feature.insights.InsightsRoute as InsightsScreen

const val InsightsRoute = "insights"

/**
 * Matches TransactionsNavigation's pattern: each feature owns its own entry
 * point. The screen is a placeholder until M5, but the entry point is real --
 * it is a tab now, and the app module wires [onSetUpRules] to the rules
 * screen without either feature knowing about the other.
 */
fun NavGraphBuilder.insightsScreen(onSetUpRules: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    composable(route = InsightsRoute) {
        InsightsScreen(onSetUpRules = onSetUpRules, onOpenSettings = onOpenSettings)
    }
}
