package dev.charanjeev.bahi.feature.insights

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.BudgetProgress
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A hand-written fake, matching feature/budgets' FakeBudgetRepository. Spend is set per budget by the test. */
class FakeBudgetRepository : BudgetRepository {

    private val budgets = MutableStateFlow<Map<String, Budget>>(emptyMap())
    private val spendByBudgetId = MutableStateFlow<Map<String, Money>>(emptyMap())
    private val uncategorised = MutableStateFlow(Money.ZERO)

    fun seed(budget: Budget, spent: Money = Money.ZERO) {
        budgets.value = budgets.value + (budget.id to budget)
        spendByBudgetId.value = spendByBudgetId.value + (budget.id to spent)
    }

    override fun observeBudgets(month: YearMonth): Flow<List<Budget>> =
        budgets.map { all -> all.values.filter { it.month == month }.sortedBy { it.categoryId } }

    override fun observeMonthlyBudgets(month: YearMonth): Flow<MonthlyBudgets> =
        budgets.map { all ->
            MonthlyBudgets(
                month = month,
                budgets = all.values
                    .filter { it.month == month }
                    .sortedBy { it.categoryId }
                    .map { BudgetProgress(it, spendByBudgetId.value[it.id] ?: Money.ZERO) },
                uncategorisedSpend = uncategorised.value,
            )
        }

    override suspend fun upsert(budget: Budget) {
        budgets.value = budgets.value + (budget.id to budget)
    }

    override suspend fun delete(id: String) {
        budgets.value = budgets.value - id
        spendByBudgetId.value = spendByBudgetId.value - id
    }

    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = emptyList()
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean = false
}
