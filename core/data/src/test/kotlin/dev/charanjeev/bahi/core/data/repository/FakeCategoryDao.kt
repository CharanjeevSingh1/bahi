package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake rather than a mock, matching CategoryDao's real Room
 * conflict semantics: upsertAll always overwrites, insertAllIgnoringConflicts
 * skips ids already present. That distinction is what the seeding behaviour
 * under test depends on, so the fake has to get it right rather than just
 * record that a method was called.
 */
class FakeCategoryDao : CategoryDao {

    private val backing = MutableStateFlow<Map<String, CategoryEntity>>(emptyMap())

    override fun observeAll(): Flow<List<CategoryEntity>> =
        backing.map { it.values.sortedBy(CategoryEntity::name) }

    override suspend fun getById(id: String): CategoryEntity? = backing.value[id]

    override suspend fun upsertAll(categories: List<CategoryEntity>) {
        backing.value = backing.value + categories.associateBy(CategoryEntity::id)
    }

    override suspend fun deleteUserCategory(id: String) {
        val existing = backing.value[id] ?: return
        if (!existing.isSystemDefined) {
            backing.value = backing.value - id
        }
    }

    override suspend fun insertAllIgnoringConflicts(categories: List<CategoryEntity>): List<Long> {
        val fresh = categories.filterNot { it.id in backing.value }
        backing.value = backing.value + fresh.associateBy(CategoryEntity::id)
        return fresh.map { 1L }
    }
}
