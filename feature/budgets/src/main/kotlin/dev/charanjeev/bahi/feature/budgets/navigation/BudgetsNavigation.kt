package dev.charanjeev.bahi.feature.budgets.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.charanjeev.bahi.feature.budgets.RuleEditorRoute as RuleEditorScreen
import dev.charanjeev.bahi.feature.budgets.RulesRoute as RulesScreen

const val BudgetsRoute = "budgets"
const val RulesRoute = "budgets/rules"
const val NewRuleRoute = "budgets/rules/new"
private const val EditRuleRoute = "budgets/rules/edit"
const val RuleIdArg = "ruleId"

fun editRuleRoute(ruleId: String) = "$EditRuleRoute/$ruleId"

/**
 * Rules navigate within this one graph, using the NavHostController the app
 * module already owns, so the app module never sees RulesScreen or its
 * ViewModel directly -- same shape as transactionsScreen.
 */
fun NavGraphBuilder.budgetsScreen(navController: NavHostController) {
    composable(route = BudgetsRoute) {
        // Still a placeholder: the budgets screen itself is the next slice.
        // It carries a way into rules so the rules flow is reachable in the
        // meantime rather than existing only as a route nothing links to.
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Budgets")
            TextButton(onClick = { navController.navigate(RulesRoute) }) {
                Text("Auto-categorisation rules")
            }
        }
    }
    composable(route = RulesRoute) {
        RulesScreen(
            onNavigateBack = { navController.popBackStack() },
            onAddRule = { navController.navigate(NewRuleRoute) },
            onEditRule = { id -> navController.navigate(editRuleRoute(id)) },
        )
    }
    composable(route = NewRuleRoute) {
        RuleEditorScreen(onNavigateBack = { navController.popBackStack() })
    }
    composable(
        route = "$EditRuleRoute/{$RuleIdArg}",
        arguments = listOf(navArgument(RuleIdArg) { type = NavType.StringType }),
    ) {
        RuleEditorScreen(onNavigateBack = { navController.popBackStack() })
    }
}
