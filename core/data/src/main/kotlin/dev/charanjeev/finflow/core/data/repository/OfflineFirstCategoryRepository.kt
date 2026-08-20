package dev.charanjeev.finflow.core.data.repository

import dev.charanjeev.finflow.core.common.Dispatcher
import dev.charanjeev.finflow.core.common.FinFlowDispatcher
import dev.charanjeev.finflow.core.database.dao.CategoryDao
import dev.charanjeev.finflow.core.model.Category
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OfflineFirstCategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    @param:Dispatcher(FinFlowDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { entities -> entities.map(::toDomain) }

    override suspend fun upsert(category: Category) = withContext(ioDispatcher) {
        // deleteUserCategory's guard reads is_system_defined off the stored row, so
        // upsert must never let a caller flip that flag -- otherwise `upsert(food
        // .copy(isSystemDefined = false))` followed by `delete("food")` would launder
        // a system category into a deletable one. The existing row's flag always wins.
        val existing = categoryDao.getById(category.id)
        val entity = toEntity(category).let { entity ->
            if (existing != null) entity.copy(isSystemDefined = existing.isSystemDefined) else entity
        }
        categoryDao.upsertAll(listOf(entity))
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        categoryDao.deleteUserCategory(id)
    }

    override suspend fun seedSystemCategoriesIfNeeded() = withContext(ioDispatcher) {
        categoryDao.insertAllIgnoringConflicts(systemCategories.map(::toEntity))
        Unit
    }
}
