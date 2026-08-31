package dev.charanjeev.bahi.feature.insights

import dev.charanjeev.bahi.core.model.BudgetProgress
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.MonthlyTotal
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.collections.immutable.ImmutableList

/**
 * A sealed interface rather than a bag of nullable fields, matching
 * BudgetsUiState.
 *
 * [NoHistory] is not "nothing this month" -- that is a [Success] with every
 * figure at zero, still worth charting as a real month. [NoHistory] means the
 * app has never recorded a live transaction at all, in any month, which is
 * the one state a chart cannot honestly be drawn for.
 */
sealed interface InsightsUiState {

    /** Every state carries the month, so the month switcher works while loading rather than disappearing. */
    val month: YearMonth

    data class Loading(override val month: YearMonth) : InsightsUiState

    data class NoHistory(override val month: YearMonth) : InsightsUiState

    data class Success(
        override val month: YearMonth,
        /** Sorted by spend, largest first. Includes an "Uncategorised" slice whenever that figure is non-zero. */
        val categorySlices: ImmutableList<CategorySlice>,
        val totalSpend: Money,
        /** Oldest first, always ending on [month]. Only meaningful to draw when [hasComparison] is true. */
        val trend: ImmutableList<MonthlyTotal>,
        val hasComparison: Boolean,
        val overBudget: ImmutableList<OverBudgetRow>,
        /** Distinguishes "no budgets set this month" from "budgets set, none exceeded" -- [overBudget] alone can't. */
        val hasAnyBudgets: Boolean,
        val currencyCode: String,
    ) : InsightsUiState {
        val hasAnySpend: Boolean get() = totalSpend > Money.ZERO
    }
}

/**
 * One bar of the category breakdown, including the uncategorised bucket --
 * represented by the real "Uncategorised" system category (its own name and
 * colour), not a null placeholder, so the chart draws it the same way it
 * draws any other slice.
 *
 * [category] is still nullable: category-spend rows and the category list
 * arrive on separate flows (like [dev.charanjeev.bahi.core.model.CategorySpend]
 * and `CategoryRepository`), so there is a frame where one has updated and
 * the other hasn't -- the same reason `BudgetRow.category` is nullable.
 */
data class CategorySlice(
    val category: Category?,
    val spent: Money,
)

/** [category] is nullable for the same reason [CategorySlice.category] is. */
data class OverBudgetRow(
    val progress: BudgetProgress,
    val category: Category?,
)

internal object InsightsTestTags {
    const val LOADING = "insights:loading"
    const val NO_HISTORY = "insights:noHistory"
    const val CONTENT = "insights:content"
    const val MONTH_LABEL = "insights:month"
    const val PREVIOUS_MONTH = "insights:previousMonth"
    const val NEXT_MONTH = "insights:nextMonth"
    const val NO_SPEND_NOTE = "insights:noSpend"
    const val TREND_CHART = "insights:trend"
    const val TREND_NO_COMPARISON = "insights:trendNoComparison"
    const val OVER_BUDGET_LIST = "insights:overBudget"
    const val OVER_BUDGET_NONE = "insights:overBudgetNone"
    const val OVER_BUDGET_NO_BUDGETS = "insights:overBudgetNoBudgets"
    const val UNCATEGORISED_PROMPT = "insights:uncategorisedPrompt"
    const val SETTINGS_ACTION = "insights:settings"

    fun categorySlice(categoryId: String) = "insights:slice:$categoryId"
}
