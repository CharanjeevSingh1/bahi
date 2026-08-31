package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.SyncShadowDao
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity

/**
 * A hand-written fake, shared across a test's other fake DAOs the same way
 * FakeBudgetDao already shares FakeTransactionDao for its join --
 * `dirtyRows` on each table's fake is exactly that kind of join, against
 * this table instead of `transactions`.
 */
class FakeSyncShadowDao : SyncShadowDao {

    private val backing = mutableMapOf<Pair<String, String>, SyncShadowEntity>()

    /**
     * What each fake DAO's `dirtyRows` compares `local_revision` against --
     * 0 for a row with no shadow, matching the real query's
     * `COALESCE(s.remote_revision, 0)`.
     */
    fun remoteRevisionOf(table: String, rowId: String): Long = backing[table to rowId]?.remoteRevision ?: 0

    override suspend fun baseOf(table: String, rowId: String): SyncShadowEntity? = backing[table to rowId]

    override suspend fun basesOf(table: String, rowIds: List<String>): List<SyncShadowEntity> =
        rowIds.mapNotNull { backing[table to it] }

    override suspend fun record(shadow: SyncShadowEntity) {
        backing[shadow.tableName to shadow.rowId] = shadow
    }

    override suspend fun recordAll(shadows: List<SyncShadowEntity>) {
        shadows.forEach { backing[it.tableName to it.rowId] = it }
    }

    override suspend fun forget(table: String, rowId: String): Int =
        if (backing.remove(table to rowId) != null) 1 else 0

    override suspend fun count(): Int = backing.size
}
