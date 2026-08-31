package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.dao.RowRevision
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
 *
 * The tombstone is modelled the way the table stores it -- the row stays,
 * with `deleted_at` set -- rather than by removing it from the map. A fake
 * that dropped the row would pass a test the real DAO fails, because
 * insertAllIgnoringConflicts still collides with a tombstoned id.
 *
 * The two cascade queries write tables this fake has no rows for, so they
 * record the category ids they were called with. That is enough for the
 * repository's tests, which care that the cascade was driven; that it lands
 * on the right rows is CategoryDaoTest's job, against real Room.
 */
class FakeCategoryDao(
    /** dirtyRows joins against this, same reasoning as FakeBudgetDao sharing FakeTransactionDao. */
    private val shadows: FakeSyncShadowDao = FakeSyncShadowDao(),
) : CategoryDao {

    private val backing = MutableStateFlow<Map<String, CategoryEntity>>(emptyMap())

    val budgetsCascadedFor = mutableListOf<String>()
    val rulesCascadedFor = mutableListOf<String>()

    /** Tombstones included, for asserting that a delete kept the row. */
    fun rowsIncludingDeleted(): List<CategoryEntity> = backing.value.values.toList()

    override fun observeAll(): Flow<List<CategoryEntity>> =
        backing.map { rows ->
            rows.values.filter { it.deletedAt == null }.sortedBy(CategoryEntity::name)
        }

    override suspend fun getById(id: String): CategoryEntity? =
        backing.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun upsertAll(categories: List<CategoryEntity>) {
        backing.value = backing.value + categories.associateBy(CategoryEntity::id)
    }

    /**
     * Reads through the tombstone, exactly as the real query does -- a fake
     * that filtered `deletedAt == null` here would hide the resurrection bug
     * this exists to fix and agree with whatever it was written to agree with.
     */
    override suspend fun revisionOf(id: String): RowRevision? =
        backing.value[id]?.let { RowRevision(it.localRevision, it.remoteRevision) }

    /** Same tombstone-inclusive read as [revisionOf], for the sync engine's apply step. */
    override suspend fun rowById(id: String): CategoryEntity? = backing.value[id]

    override suspend fun applyRemoteTombstone(
        id: String,
        deletedAt: Long,
        updatedAt: Long,
        localRevision: Long,
        remoteRevision: Long,
        pendingOperation: String?,
    ) {
        val existing = backing.value[id] ?: return
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = deletedAt,
                updatedAt = updatedAt,
                localRevision = localRevision,
                remoteRevision = remoteRevision,
                pendingOperation = pendingOperation,
            )
        )
    }

    override suspend fun tombstoneUserCategory(id: String, deletedAt: Long): Int {
        val existing = backing.value[id] ?: return 0
        if (existing.isSystemDefined || existing.deletedAt != null) return 0
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = deletedAt,
                pendingOperation = "DELETE",
                localRevision = existing.localRevision + 1,
            )
            )
        return 1
    }

    override suspend fun tombstoneBudgetsOf(categoryId: String, deletedAt: Long) {
        budgetsCascadedFor += categoryId
    }

    override suspend fun tombstoneRulesOf(categoryId: String, deletedAt: Long) {
        rulesCascadedFor += categoryId
    }

    override suspend fun insertAllIgnoringConflicts(categories: List<CategoryEntity>): List<Long> {
        val fresh = categories.filterNot { it.id in backing.value }
        backing.value = backing.value + fresh.associateBy(CategoryEntity::id)
        return fresh.map { 1L }
    }

    /**
     * Mirrors the real join: local_revision against the shadow's
     * remote_revision, 0 if absent. Query-level only, matching CategoryDao --
     * see the note there for why CategoryRepository doesn't expose this yet.
     */
    override suspend fun dirtyRows(limit: Int): List<CategoryEntity> =
        backing.value.values
            .filter { it.localRevision > shadows.remoteRevisionOf("categories", it.id) }
            .sortedBy { it.id }
            .take(limit)

    override suspend fun markSynced(id: String, remoteRevision: Long, expectedLocalRevision: Long): Int {
        val existing = backing.value[id] ?: return 0
        if (existing.localRevision != expectedLocalRevision) return 0
        backing.value = backing.value + (
            id to existing.copy(pendingOperation = null, remoteRevision = remoteRevision)
        )
        return 1
    }

    override suspend fun allIds(): List<String> = backing.value.keys.toList()

    override suspend fun tombstonesOlderThan(before: Long): List<String> =
        backing.value.values.filter { (it.deletedAt ?: return@filter false) < before }.map { it.id }

    override suspend fun hardDelete(id: String): Int {
        if (!backing.value.containsKey(id)) return 0
        backing.value = backing.value - id
        return 1
    }
}
