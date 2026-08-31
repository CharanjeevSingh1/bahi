package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.model.CategoryBreakdown
import dev.charanjeev.bahi.core.model.CategorySpend
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.MonthlyTotal
import dev.charanjeev.bahi.core.model.SpendTrend
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class OfflineFirstInsightsRepository @Inject constructor(
    private val transactionDao: TransactionDao,
) : InsightsRepository {

    override fun observeHasAnyHistory(): Flow<Boolean> =
        transactionDao.observeEarliestTransactionDate().map { it != null }

    override fun observeCategoryBreakdown(month: YearMonth): Flow<CategoryBreakdown> {
        val window = month.dateRange()
        val from = window.from.toString()
        val to = window.to.toString()
        // Two queries composed here for the same reason
        // OfflineFirstBudgetRepository combines budget spend with
        // uncategorised spend (docs/budgets-design.md §2.2): uncategorised
        // money has no category to group by, so it cannot be a row of the
        // other query. The same combine transient that doc measures applies
        // here too -- this screen only ever renders the settled result.
        return combine(
            transactionDao.observeCategorySpend(from, to),
            transactionDao.observeUncategorisedSpend(from, to),
        ) { rows, uncategorisedMinor ->
            CategoryBreakdown(
                month = month,
                categorySpend = rows.map { CategorySpend(it.categoryId, Money(it.spentMinor)) },
                uncategorisedSpend = Money(uncategorisedMinor),
            )
        }.distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSpendTrend(month: YearMonth, maxMonths: Int): Flow<SpendTrend> {
        require(maxMonths >= 1) { "maxMonths must be at least 1: $maxMonths" }
        // flatMapLatest, not combine: the earliest transaction date decides
        // which window observeMonthlySpend is even asked for, so a change to
        // it (the oldest transaction being deleted, say) has to restart that
        // query rather than race it -- the same reasoning BudgetsViewModel
        // uses for a month switch.
        return transactionDao.observeEarliestTransactionDate().flatMapLatest { earliest ->
            val earliestMonth = earliest?.let { YearMonth.parse(it.substring(0, 7)) }
            val start = when {
                // No history at all, or the only history is after [month]
                // (browsing to a month before the app's first transaction):
                // either way there is nothing before [month] this trend can
                // honestly zero-fill, so the window is just [month] itself.
                earliestMonth == null || earliestMonth > month -> month
                else -> maxOf(earliestMonth, month.plusMonths(-(maxMonths - 1)))
            }
            val months = monthsBetween(start, month)
            val from = start.dateRange().from.toString()
            val to = month.dateRange().to.toString()
            transactionDao.observeMonthlySpend(from, to).map { rows ->
                val spentByMonth = rows.associate { YearMonth.parse(it.yearMonth) to Money(it.spentMinor) }
                // A month in [months] absent from spentByMonth is a genuine
                // zero, not a gap -- every month in this list is within the
                // app's real history by construction of [start] above.
                SpendTrend(months.map { m -> MonthlyTotal(m, spentByMonth[m] ?: Money.ZERO) })
            }
        }.distinctUntilChanged()
    }

    private fun monthsBetween(start: YearMonth, end: YearMonth): List<YearMonth> {
        val months = mutableListOf<YearMonth>()
        var cursor = start
        while (cursor <= end) {
            months += cursor
            cursor = cursor.plusMonths(1)
        }
        return months
    }
}
