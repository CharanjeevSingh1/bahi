package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.model.Category
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject

class OfflineFirstCategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { entities -> entities.map(::toDomain) }

    override suspend fun upsert(category: Category) = withContext(ioDispatcher) {
        // tombstoneUserCategory's guard reads is_system_defined off the stored row,
        // so upsert must never let a caller flip that flag -- otherwise `upsert(food
        // .copy(isSystemDefined = false))` followed by `delete("food")` would launder
        // a system category into a deletable one. The existing row's flag always wins.
        val existing = categoryDao.getById(category.id)
        // Two different questions, and they need two different reads. Whether
        // this is a system category is about the row the *user* can see, so it
        // comes off `getById` and a tombstoned row correctly answers "no row".
        // The revision is bookkeeping about the row itself and has to be read
        // through the tombstone, or reviving a deleted category restarts its
        // count at 1 and drops the remote's acknowledgement. See RowRevision.
        val revision = categoryDao.revisionOf(category.id)
        val entity = toEntity(category).copy(
            isSystemDefined = existing?.isSystemDefined ?: category.isSystemDefined,
            pendingOperation = "UPSERT",
            localRevision = (revision?.localRevision ?: 0) + 1,
            remoteRevision = revision?.remoteRevision,
        )
        categoryDao.upsertAll(listOf(entity))
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        categoryDao.softDeleteUserCategory(id, clock.now().toEpochMilliseconds())
    }

    override suspend fun seedSystemCategoriesIfNeeded() = withContext(ioDispatcher) {
        categoryDao.insertAllIgnoringConflicts(systemCategories.map(::toEntity))
        Unit
    }
}
