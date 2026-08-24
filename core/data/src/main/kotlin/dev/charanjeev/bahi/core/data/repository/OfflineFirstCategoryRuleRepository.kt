package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.model.CategoryRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject

class OfflineFirstCategoryRuleRepository @Inject constructor(
    private val categoryRuleDao: CategoryRuleDao,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CategoryRuleRepository {

    override fun observeRules(): Flow<List<CategoryRule>> =
        categoryRuleDao.observeAll().map { entities -> entities.map(::toDomain) }

    override suspend fun rules(): List<CategoryRule> = withContext(ioDispatcher) {
        categoryRuleDao.getAll().map(::toDomain)
    }

    override suspend fun upsert(rule: CategoryRule) = withContext(ioDispatcher) {
        val now = clock.now().toEpochMilliseconds()
        // Editing a rule must not restate when it was created, so an existing
        // row's createdAt is carried over rather than overwritten with now.
        val existing = categoryRuleDao.getById(rule.id)
        val entity = toEntity(rule, createdAt = existing?.createdAt ?: now, updatedAt = now).copy(
            pendingOperation = "UPSERT",
            localRevision = (existing?.localRevision ?: 0) + 1,
            remoteRevision = existing?.remoteRevision,
        )
        categoryRuleDao.upsert(entity)
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        categoryRuleDao.softDelete(id, clock.now().toEpochMilliseconds())
    }
}
