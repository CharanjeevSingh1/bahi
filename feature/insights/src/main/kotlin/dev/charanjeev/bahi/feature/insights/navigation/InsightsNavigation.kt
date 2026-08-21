package dev.charanjeev.bahi.feature.insights.navigation

import androidx.compose.material3.Text
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val InsightsRoute = "insights"

/** Placeholder destination. Real screen lands in the milestone that owns it. */
fun NavGraphBuilder.insightsScreen() {
    composable(route = InsightsRoute) {
        Text("Insights")
    }
}
