package dev.charanjeev.bahi.feature.insights

import dev.charanjeev.bahi.core.data.repository.InsightsRepository
import dev.charanjeev.bahi.core.model.CategoryBreakdown
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.SpendTrend
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A hand-written fake. The real aggregation -- grouping, sign filtering, the
 * uncategorised partition, the zero-fill window -- is
 * OfflineFirstInsightsRepositoryTest's job against a real DAO; this fake
 * only has to let the ViewModel's own logic (joining categories, sorting
 * slices, deriving the over-budget list) be tested in isolation from it.
 */
class FakeInsightsRepository : InsightsRepository {

    private val hasAnyHistory = MutableStateFlow(false)
    private val breakdowns = MutableStateFlow<Map<YearMonth, CategoryBreakdown>>(emptyMap())
    private val trends = MutableStateFlow<Map<YearMonth, SpendTrend>>(emptyMap())

    fun setHasAnyHistory(value: Boolean) {
        hasAnyHistory.value = value
    }

    fun setBreakdown(month: YearMonth, breakdown: CategoryBreakdown) {
        breakdowns.value = breakdowns.value + (month to breakdown)
    }

    fun setTrend(month: YearMonth, trend: SpendTrend) {
        trends.value = trends.value + (month to trend)
    }

    override fun observeHasAnyHistory(): Flow<Boolean> = hasAnyHistory

    override fun observeCategoryBreakdown(month: YearMonth): Flow<CategoryBreakdown> =
        MutableStateFlow(breakdowns.value[month] ?: CategoryBreakdown(month, emptyList(), Money.ZERO))

    override fun observeSpendTrend(month: YearMonth, maxMonths: Int): Flow<SpendTrend> =
        MutableStateFlow(trends.value[month] ?: SpendTrend(emptyList()))
}
