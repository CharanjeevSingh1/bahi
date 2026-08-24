package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake, matching FakeTransactionDao. Every query's
 * `deleted_at IS NULL` is mirrored by hand here -- with no SQLite behind the
 * fake, forgetting one is how a test would pass while the real query
 * resurrects a tombstoned row.
 */
class FakeBudgetDao : BudgetDao {

    private val backing = MutableStateFlow<Map<String, BudgetEntity>>(emptyMap())

    /** Tombstones included, unlike every query below -- tests assert on them. */
    fun allRows(): List<BudgetEntity> = backing.value.values.toList()

    override fun observeForMonth(yearMonth: String): Flow<List<BudgetEntity>> =
        backing.map { entities ->
            entities.values
                .filter { it.deletedAt == null && it.yearMonth == yearMonth }
                .sortedBy { it.categoryId }
        }

    override suspend fun getById(id: String): BudgetEntity? =
        backing.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun findActive(categoryId: String, yearMonth: String): BudgetEntity? =
        backing.value.values.firstOrNull {
            it.categoryId == categoryId && it.yearMonth == yearMonth && it.deletedAt == null
        }

    override suspend fun upsert(budget: BudgetEntity) {
        backing.value = backing.value + (budget.id to budget)
    }

    override suspend fun softDelete(id: String, deletedAt: Long) {
        val existing = backing.value[id]?.takeIf { it.deletedAt == null } ?: return
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = deletedAt,
                pendingOperation = "DELETE",
                localRevision = existing.localRevision + 1,
            )
        )
    }
}
