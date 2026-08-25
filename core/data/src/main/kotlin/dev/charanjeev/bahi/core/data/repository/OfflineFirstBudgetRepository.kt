package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject

class OfflineFirstBudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    // The uncategorised-spend query reads `transactions`, so it lives on
    // that table's DAO. Composing the two queries is this layer's job, not
    // a reason to give one DAO a query over the other's table.
    private val transactionDao: TransactionDao,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BudgetRepository {

    override fun observeBudgets(month: YearMonth): Flow<List<Budget>> =
        budgetDao.observeForMonth(month.toString()).map { entities -> entities.map(::toDomain) }

    override fun observeMonthlyBudgets(month: YearMonth): Flow<MonthlyBudgets> {
        // The only place a month becomes concrete dates, and it happens before
        // either query runs. Neither DAO is ever handed "this month" -- see
        // YearMonth.dateRange and docs/budgets-design.md §2.3 for why that
        // rule is what keeps a budget's boundaries from drifting with wherever
        // the device happens to be.
        val window = month.dateRange()
        val from = window.from.toString()
        val to = window.to.toString()
        // Two queries rather than one, per §2.2: uncategorised spending has no
        // category to join a budget on, so it can't be a column of the other
        // query.
        //
        // combine has a transient, and it is real rather than theoretical:
        // both flows are invalidated by the same write to `transactions` but
        // re-query independently, so combine can pair one's new value with
        // the other's old one. Categorising a transaction emits one
        // intermediate frame counting it in both its new budget and the
        // uncategorised line.
        //
        // Measured, not assumed -- BudgetTotalsTransientTest records every
        // emission against real Room. The intermediate frame appears in most
        // runs and lasts 0.24-0.68ms, which is 20-70x shorter than a single
        // 60Hz display frame, and collectAsStateWithLifecycle conflates it
        // away before composition ever reads it. So it exists in the flow and
        // cannot reach the screen.
        //
        // It is also self-correcting and can only ever overstate, never lose
        // money -- the test asserts no frame drops the transaction from both
        // sides at once, which is the failure that would actually matter.
        // Eliminating it would mean folding both into one query, which the
        // paragraph above rules out. distinctUntilChanged keeps the settled
        // result from re-emitting for unrelated writes; it can't collapse the
        // intermediate, since that is a genuinely different value.
        return combine(
            budgetDao.observeBudgetsWithSpend(month.toString(), from, to),
            transactionDao.observeUncategorisedSpend(from, to),
        ) { budgets, uncategorisedSpendMinor ->
            MonthlyBudgets(
                month = month,
                budgets = budgets.map(::toDomain),
                uncategorisedSpend = Money(uncategorisedSpendMinor),
            )
        }.distinctUntilChanged()
    }

    override suspend fun upsert(budget: Budget) = withContext(ioDispatcher) {
        val now = clock.now().toEpochMilliseconds()
        // The one-per-category-per-month invariant, enforced here because a
        // UNIQUE index can't express it over soft deletes (BudgetRepository's
        // doc). Reusing the existing row's id -- rather than inserting the
        // caller's -- is what keeps this an update instead of a second row.
        val existing = budgetDao.findActive(budget.categoryId, budget.month.toString())
        val entity = if (existing != null) {
            toEntity(budget.copy(id = existing.id), createdAt = existing.createdAt, updatedAt = now).copy(
                pendingOperation = "UPSERT",
                localRevision = existing.localRevision + 1,
                remoteRevision = existing.remoteRevision,
            )
        } else {
            toEntity(budget, createdAt = now, updatedAt = now).copy(pendingOperation = "UPSERT")
        }
        budgetDao.upsert(entity)
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        budgetDao.softDelete(id, clock.now().toEpochMilliseconds())
    }
}
