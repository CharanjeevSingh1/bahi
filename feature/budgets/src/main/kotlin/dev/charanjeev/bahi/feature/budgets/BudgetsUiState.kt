package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.model.BudgetProgress
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.collections.immutable.ImmutableList

/**
 * A sealed interface rather than a bag of nullable fields, matching
 * TransactionsUiState.
 *
 * Note what [Empty] does and doesn't mean: no *budgets* for this month, not
 * "no spending". A month can have no budgets and plenty of uncategorised
 * spending, so Empty still carries the month and the uncategorised figure --
 * dropping them would make the emptiest screen the one that tells the user
 * least about where their money went.
 */
sealed interface BudgetsUiState {

    /** Every state carries the month, so the month switcher works while loading rather than disappearing. */
    val month: YearMonth

    data class Loading(override val month: YearMonth) : BudgetsUiState

    data class Empty(
        override val month: YearMonth,
        val uncategorisedSpend: Money,
    ) : BudgetsUiState {
        val hasUncategorisedSpend: Boolean get() = uncategorisedSpend > Money.ZERO
    }

    data class Success(
        override val month: YearMonth,
        val budgets: ImmutableList<BudgetRow>,
        val uncategorisedSpend: Money,
        val currencyCode: String,
        val pendingDelete: PendingBudgetDelete? = null,
    ) : BudgetsUiState {

        val hasUncategorisedSpend: Boolean get() = uncategorisedSpend > Money.ZERO

        /**
         * Nothing has been counted against any budget on screen. Carefully
         * *not* called "no spending this month": spending in a category that
         * has no budget is invisible here too, and claiming the month was
         * empty when it wasn't would be exactly the confidently-wrong kind of
         * statement this project keeps refusing elsewhere. An "overall" total
         * that would let the screen say more needs the sentinel-category work
         * §2.1/D3 deferred, so the copy stays narrow instead.
         */
        val nothingCountedYet: Boolean get() = budgets.all { it.progress.spent == Money.ZERO }

        val anyOverBudget: Boolean get() = budgets.any { it.progress.isOverBudget }
    }
}

/**
 * [category] is nullable for the same reason RuleListItem's is: budgets and
 * categories arrive on separate flows, so there is a frame where one has
 * updated and the other hasn't.
 */
data class BudgetRow(
    val progress: BudgetProgress,
    val category: Category?,
)

data class PendingBudgetDelete(
    val budgetId: String,
    val categoryName: String,
)

internal object BudgetsTestTags {
    const val LOADING = "budgets:loading"
    const val EMPTY = "budgets:empty"
    const val LIST = "budgets:list"
    const val ADD_FAB = "budgets:add"
    const val MONTH_LABEL = "budgets:month"
    const val PREVIOUS_MONTH = "budgets:previousMonth"
    const val NEXT_MONTH = "budgets:nextMonth"
    const val UNCATEGORISED_CARD = "budgets:uncategorised"
    const val NOTHING_COUNTED_NOTE = "budgets:nothingCounted"
    const val RULES_ACTION = "budgets:rules"
    const val DELETE_DIALOG = "budgets:deleteDialog"

    fun row(budgetId: String) = "budgets:row:$budgetId"
    fun bar(budgetId: String) = "budgets:bar:$budgetId"
    fun overBudget(budgetId: String) = "budgets:over:$budgetId"
    fun nearLimit(budgetId: String) = "budgets:near:$budgetId"
}
