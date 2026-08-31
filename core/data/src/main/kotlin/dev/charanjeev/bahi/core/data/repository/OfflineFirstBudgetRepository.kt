package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.model.budgetIdFor
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
        // the other's old one. Moving a transaction between a budget and the
        // uncategorised line emits one intermediate frame where the two sides
        // disagree about which write they reflect.
        //
        // Nothing orders those two re-queries, so that frame lands either way:
        // if the budget query wins, the transaction is counted twice; if the
        // uncategorised query wins, it is counted in neither. An earlier
        // version of this comment claimed the transient could only ever
        // overstate, on a nine-run sample. It cannot -- both shapes were
        // observed in both directions over 22 instrumented runs, and
        // BudgetTotalsTransientTest now asserts only what is
        // order-independent: each side moves exactly once, never through a
        // value no query would return, and the pair settles correctly.
        //
        // What makes it invisible is duration, not direction. Measured against
        // real Room over those 22 runs, the longest intermediate frame was
        // 2.66ms against a 16.7ms 60Hz frame, and collectAsStateWithLifecycle
        // conflates a superseded value before composition reads it. So it
        // exists in the flow and does not reach the screen.
        //
        // That holds only while these values are rendered and nothing else.
        // Anything that acts on the pair -- a budget alert above all -- needs
        // the single-query redesign first; see docs/budgets-design.md 2.2.
        //
        // Eliminating it rather than tolerating it would mean one query, which
        // the paragraph above rules out. distinctUntilChanged keeps the
        // settled result from re-emitting for unrelated writes; it cannot
        // collapse the intermediate, since that is a genuinely different value.
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
        // doc). The id *is* that key now (docs/sync-design.md §3.2): deriving
        // it rather than reusing whatever the caller minted is what keeps this
        // an update instead of a second row, and -- unlike the lookup below,
        // which only ever sees writes that come through here -- it also holds
        // for a row arriving from another device, which is the case the
        // lookup alone could never fix.
        val keyed = budget.copy(id = budgetIdFor(budget.categoryId, budget.month))
        // Still a lookup, because createdAt and the revision bookkeeping have
        // to come off the stored row; the id no longer depends on it.
        val existing = budgetDao.findActive(budget.categoryId, budget.month.toString())
        // Read through the tombstone for the revision only: see RowRevision.
        // Natural-key ids made this reachable -- recreating a deleted budget
        // now revives that very row (§3.2), and `findActive` cannot see it, so
        // the revision came back as "new" for a row that has a history.
        // createdAt stays on findActive: recreating a budget is the user
        // creating a budget, whatever the row underneath has been through.
        val revision = budgetDao.revisionOf(keyed.id)
        val entity = toEntity(
            keyed,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        ).copy(
            pendingOperation = "UPSERT",
            localRevision = (revision?.localRevision ?: 0) + 1,
            remoteRevision = revision?.remoteRevision,
        )
        budgetDao.upsert(entity)
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        budgetDao.softDelete(id, clock.now().toEpochMilliseconds())
    }

    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = withContext(ioDispatcher) {
        budgetDao.dirtyRows(limit).map { entity ->
            DirtyRow(
                rowId = entity.id,
                localRevision = entity.localRevision,
                updatedAt = entity.updatedAt,
                payload = if (entity.deletedAt != null) null else toFieldMap(entity),
            )
        }
    }

    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean =
        withContext(ioDispatcher) {
            budgetDao.markSynced(rowId, remoteRevision, expectedLocalRevision) > 0
        }
}
