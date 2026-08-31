package dev.charanjeev.bahi.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.ui.graphics.vector.ImageVector
import dev.charanjeev.bahi.R
import dev.charanjeev.bahi.feature.budgets.navigation.BudgetsRoute
import dev.charanjeev.bahi.feature.insights.navigation.InsightsRoute
import dev.charanjeev.bahi.feature.transactions.navigation.TransactionsRoute

/**
 * The tabs, and the only place that knows which routes are tabs.
 *
 * Settings is deliberately absent. A screen you visit rarely -- reviewing a
 * sync conflict, eventually a theme choice -- does not belong next to the
 * three you use daily, so it is a top-bar action on each of them
 * (`onOpenSettings`, wired in `BahiNavHost`) rather than a fourth tab that
 * would cost a quarter of the bar for something opened a few times a month.
 *
 * [graphRoute] and [startRoute] are different things and both are needed: the
 * bar navigates to the *graph*, because that is what carries a saved back
 * stack, and re-tapping the active tab pops back to the *screen* at its root.
 */
enum class TopLevelDestination(
    val graphRoute: String,
    val startRoute: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    @get:StringRes val labelRes: Int,
) {
    TRANSACTIONS(
        graphRoute = "graph/transactions",
        startRoute = TransactionsRoute,
        selectedIcon = Icons.AutoMirrored.Filled.ReceiptLong,
        unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
        labelRes = R.string.nav_transactions,
    ),
    BUDGETS(
        graphRoute = "graph/budgets",
        startRoute = BudgetsRoute,
        selectedIcon = Icons.Filled.PieChart,
        unselectedIcon = Icons.Outlined.PieChart,
        labelRes = R.string.nav_budgets,
    ),
    INSIGHTS(
        graphRoute = "graph/insights",
        startRoute = InsightsRoute,
        selectedIcon = Icons.Filled.Insights,
        unselectedIcon = Icons.Outlined.Insights,
        labelRes = R.string.nav_insights,
    ),
    ;

    companion object {
        /**
         * The tab a graph belongs to. Matched on [graphRoute], not on the
         * screen currently showing, so a pushed screen still keeps its tab
         * highlighted -- the caller walks the destination's parent hierarchy
         * and the first graph that answers here is the tab the user is in.
         */
        fun forGraphRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.graphRoute == route }
    }
}
