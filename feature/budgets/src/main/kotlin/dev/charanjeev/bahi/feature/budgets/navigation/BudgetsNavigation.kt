package dev.charanjeev.bahi.feature.budgets.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.feature.budgets.BudgetEditorRoute as BudgetEditorScreen
import dev.charanjeev.bahi.feature.budgets.BudgetsRoute as BudgetsScreen
import dev.charanjeev.bahi.feature.budgets.RuleEditorRoute as RuleEditorScreen
import dev.charanjeev.bahi.feature.budgets.RulesRoute as RulesScreen

const val BudgetsRoute = "budgets"
private const val NewBudgetRoute = "budgets/new"
private const val EditBudgetRoute = "budgets/edit"
const val BudgetIdArg = "budgetId"

/**
 * The month is a route argument rather than something the editor re-derives
 * from "today": a user looking at September and tapping add means September,
 * and resolving the month a second time is exactly how the two screens end up
 * disagreeing about which one they are editing.
 */
const val MonthArg = "month"

const val RulesRoute = "budgets/rules"
const val NewRuleRoute = "budgets/rules/new"
private const val EditRuleRoute = "budgets/rules/edit"
const val RuleIdArg = "ruleId"

fun newBudgetRoute(month: YearMonth) = "$NewBudgetRoute/$month"

fun editBudgetRoute(budgetId: String, month: YearMonth) = "$EditBudgetRoute/$budgetId/$month"

fun editRuleRoute(ruleId: String) = "$EditRuleRoute/$ruleId"

/**
 * Budgets and rules navigate within this one graph, using the NavHostController
 * the app module already owns, so the app module never sees a screen or a
 * ViewModel directly -- same shape as transactionsScreen.
 */
fun NavGraphBuilder.budgetsScreen(navController: NavHostController) {
    composable(route = BudgetsRoute) {
        BudgetsScreen(
            onAddBudget = { month -> navController.navigate(newBudgetRoute(month)) },
            onEditBudget = { id, month -> navController.navigate(editBudgetRoute(id, month)) },
            onOpenRules = { navController.navigate(RulesRoute) },
        )
    }
    composable(
        route = "$NewBudgetRoute/{$MonthArg}",
        arguments = listOf(navArgument(MonthArg) { type = NavType.StringType }),
    ) {
        BudgetEditorScreen(onNavigateBack = { navController.popBackStack() })
    }
    composable(
        route = "$EditBudgetRoute/{$BudgetIdArg}/{$MonthArg}",
        arguments = listOf(
            navArgument(BudgetIdArg) { type = NavType.StringType },
            navArgument(MonthArg) { type = NavType.StringType },
        ),
    ) {
        BudgetEditorScreen(onNavigateBack = { navController.popBackStack() })
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
