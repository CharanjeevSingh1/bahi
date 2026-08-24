package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject

class OfflineFirstBudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BudgetRepository {

    override fun observeBudgets(month: YearMonth): Flow<List<Budget>> =
        budgetDao.observeForMonth(month.toString()).map { entities -> entities.map(::toDomain) }

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
