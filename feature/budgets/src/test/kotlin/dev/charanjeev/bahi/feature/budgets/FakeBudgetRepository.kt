package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.BudgetProgress
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake. Spend is set per budget by the test rather than
 * derived from transactions: the real figure comes out of SQLite (§4.2) and
 * is held to account in BudgetDaoTest, so re-deriving it here would only
 * assert that this file agrees with itself.
 *
 * The upsert-by-natural-key behaviour *is* mirrored, because the editor
 * depends on it -- it generates an id for every save and relies on the
 * repository discarding that id when a budget already exists for the
 * category and month.
 */
class FakeBudgetRepository : BudgetRepository {

    private val budgets = MutableStateFlow<Map<String, Budget>>(emptyMap())
    private val spendByBudgetId = MutableStateFlow<Map<String, Money>>(emptyMap())
    private val uncategorised = MutableStateFlow(Money.ZERO)

    val deleted = mutableListOf<String>()

    fun seed(budget: Budget, spent: Money = Money.ZERO) {
        budgets.value = budgets.value + (budget.id to budget)
        spendByBudgetId.value = spendByBudgetId.value + (budget.id to spent)
    }

    fun setUncategorisedSpend(amount: Money) {
        uncategorised.value = amount
    }

    fun budget(id: String): Budget? = budgets.value[id]

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
        // Keyed on (category, month), not on id -- the caller's id is
        // discarded when a budget already exists for that pair.
        val existing = budgets.value.values
            .firstOrNull { it.categoryId == budget.categoryId && it.month == budget.month }
        val id = existing?.id ?: budget.id
        budgets.value = budgets.value + (id to budget.copy(id = id))
    }

    override suspend fun delete(id: String) {
        deleted += id
        budgets.value = budgets.value - id
        spendByBudgetId.value = spendByBudgetId.value - id
    }
}
