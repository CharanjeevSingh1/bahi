package dev.charanjeev.bahi.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.navigation
import dev.charanjeev.bahi.feature.budgets.navigation.BudgetsRoute
import dev.charanjeev.bahi.feature.budgets.navigation.RulesRoute
import dev.charanjeev.bahi.feature.budgets.navigation.budgetsScreen
import dev.charanjeev.bahi.feature.csvimport.navigation.ImportRoute
import dev.charanjeev.bahi.feature.csvimport.navigation.importScreen
import dev.charanjeev.bahi.feature.insights.navigation.InsightsRoute
import dev.charanjeev.bahi.feature.insights.navigation.insightsScreen
import dev.charanjeev.bahi.feature.transactions.navigation.TransactionsRoute
import dev.charanjeev.bahi.feature.transactions.navigation.transactionsScreen

/**
 * The app module is the only place that knows about every feature. Features
 * expose NavGraphBuilder extensions and never reference each other directly,
 * which is what keeps them independently buildable.
 *
 * Each tab is a *nested graph* rather than a single destination. That nesting
 * is the whole mechanism behind per-tab back stacks: [navigateToTopLevelDestination]
 * saves and restores state keyed on the graph, so what comes back is the tab's
 * entire stack -- a half-filled add-transaction form included -- and not just
 * the screen at its root.
 */
@Composable
fun BahiNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.TRANSACTIONS.graphRoute,
        modifier = modifier,
    ) {
        navigation(
            route = TopLevelDestination.TRANSACTIONS.graphRoute,
            startDestination = TransactionsRoute,
        ) {
            transactionsScreen(
                navController,
                onImportClick = { navController.navigate(ImportRoute) },
            )
            // Import is an action on transactions, not a destination of its
            // own: you import *into* the list you are looking at. So it sits
            // inside this tab's graph and returns with it, rather than being
            // a fourth tab or a screen the other tabs can strand you on.
            importScreen(navController)
        }

        navigation(
            route = TopLevelDestination.BUDGETS.graphRoute,
            startDestination = BudgetsRoute,
        ) {
            budgetsScreen(navController)
        }

        navigation(
            route = TopLevelDestination.INSIGHTS.graphRoute,
            startDestination = InsightsRoute,
        ) {
            // Rules is a budgets destination, so this switches to that tab
            // first and pushes onto *its* stack. Pushing it straight onto the
            // insights stack would work, but the bar would then highlight
            // Budgets -- the tab the rules screen lives in -- while the user
            // was standing in Insights, and a bar that lies about where you
            // are is worse than one extra tab change.
            insightsScreen(
                onSetUpRules = {
                    navController.navigateToTopLevelDestination(
                        destination = TopLevelDestination.BUDGETS,
                        isCurrent = false,
                    )
                    navController.navigate(RulesRoute)
                },
            )
        }

        // No settings destination: see TopLevelDestination for why the tab
        // isn't there, and :feature:settings for the stub that is waiting.
    }
}

/**
 * Switches tabs, keeping one back stack per tab.
 *
 * `popUpTo(graph.id)` -- the *host* graph, not its start destination -- is what
 * makes this reliable. It clears the outgoing tab's stack right down to its
 * graph entry, so the deepest thing saved is always that graph, which is the
 * id `restoreState` then looks the stack up under when the tab is tapped
 * again. Popping to the host's start *destination* instead leaves the first
 * tab's entries pinned at the bottom of the stack forever and keys the saved
 * state off the wrong destination.
 *
 * `launchSingleTop` is the belt to that braces: [isCurrent] is read from the
 * last composition, so two taps landing before it recomputes would otherwise
 * push the same graph twice.
 */
fun NavHostController.navigateToTopLevelDestination(
    destination: TopLevelDestination,
    isCurrent: Boolean,
) {
    if (isCurrent) {
        // Material's rule for re-tapping the active tab: back to its root.
        // It matters more here than usual because the bar stays visible on
        // pushed screens -- without this the tab the user is standing in is
        // the one button on the bar that does nothing. Returns false when
        // already at the root, which is the correct no-op.
        popBackStack(destination.startRoute, inclusive = false)
        return
    }

    navigate(destination.graphRoute) {
        popUpTo(graph.id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
