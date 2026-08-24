package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
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

    override suspend fun upsert(rule: CategoryRuleEntity) {
        backing.value = backing.value + (rule.id to rule)
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
