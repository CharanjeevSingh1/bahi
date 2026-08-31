package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.CategoryBreakdown
import dev.charanjeev.bahi.core.model.SpendTrend
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow

/**
 * The only surface the insights screen is allowed to touch for aggregated
 * spend data, matching BudgetRepository's shape. Budgets-over-limit for a
 * month is deliberately not duplicated here -- `BudgetRepository.observeMonthlyBudgets`
 * already computes exactly that, and the insights screen consumes it
 * directly rather than this interface growing a second copy.
 */
interface InsightsRepository {

    /**
     * Whether the app has *any* live transaction, in any month. Distinct from
     * a month's [CategoryBreakdown] reading zero: that can mean either "you
     * spent nothing this month" or "you've never used the app", and this is
     * the flag a screen needs to tell those two apart before it picks which
     * one to say.
     */
    fun observeHasAnyHistory(): Flow<Boolean>

    /**
     * [month]'s expense spending, partitioned by category with the
     * uncategorised remainder alongside it -- see [CategoryBreakdown] for why
     * the two travel together.
     */
    fun observeCategoryBreakdown(month: YearMonth): Flow<CategoryBreakdown>

    /**
     * [month]'s expense total set against as many of the months before it as
     * actually have history, capped at [maxMonths] months in total (this one
     * included).
     *
     * Never returns a month the app has no data for -- see [SpendTrend] --
     * so a caller cannot tell "spent nothing" apart from "hadn't started
     * using the app yet" by looking at the numbers alone; [SpendTrend.hasComparison]
     * is what a screen must check before drawing anything comparative.
     */
    fun observeSpendTrend(month: YearMonth, maxMonths: Int = 6): Flow<SpendTrend>
}
