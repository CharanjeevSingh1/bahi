package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.database.dao.RowRevision
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A hand-written fake, matching FakeBudgetDao. */
class FakeCategoryRuleDao : CategoryRuleDao {

    private val backing = MutableStateFlow<Map<String, CategoryRuleEntity>>(emptyMap())

    /** Tombstones included, unlike every query below -- tests assert on them. */
    fun allRows(): List<CategoryRuleEntity> = backing.value.values.toList()

    override fun observeAll(): Flow<List<CategoryRuleEntity>> =
        backing.map { entities ->
            entities.values
                .filter { it.deletedAt == null }
                .sortedWith(compareBy({ it.priority }, { it.id }))
        }

    override suspend fun getAll(): List<CategoryRuleEntity> =
        backing.value.values
            .filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.priority }, { it.id }))

    override suspend fun getById(id: String): CategoryRuleEntity? =
        backing.value[id]?.takeIf { it.deletedAt == null }

    /**
     * Reads through the tombstone, exactly as the real query does -- a fake
     * that filtered `deletedAt == null` here would hide the resurrection bug
     * this exists to fix and agree with whatever it was written to agree with.
     */
    override suspend fun revisionOf(id: String): RowRevision? =
        backing.value[id]?.let { RowRevision(it.localRevision, it.remoteRevision) }

    override suspend fun upsert(rule: CategoryRuleEntity) {
        backing.value = backing.value + (rule.id to rule)
    }

    override suspend fun updatePriority(id: String, priority: Int, updatedAt: Long) {
        val existing = backing.value[id]?.takeIf { it.deletedAt == null } ?: return
        backing.value = backing.value + (
            id to existing.copy(
                priority = priority,
                updatedAt = updatedAt,
                pendingOperation = "UPSERT",
                localRevision = existing.localRevision + 1,
            )
        )
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
