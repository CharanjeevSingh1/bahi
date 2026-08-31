package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryRuleDao {

    /**
     * Ordered the way rules are evaluated (docs/budgets-design.md §1.5), so
     * the matching engine can take the first hit rather than re-sorting, and
     * the management screen shows them in the order they actually apply. `id`
     * breaks a priority tie so the order is total rather than merely
     * consistent-per-query.
     */
    @Query(
        """
        SELECT * FROM category_rules
        WHERE deleted_at IS NULL
        ORDER BY priority ASC, id ASC
        """,
    )
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    /** [observeAll]'s one-shot twin, in the same evaluation order -- a rule run is a one-off, not a subscription. */
    @Query(
        """
        SELECT * FROM category_rules
        WHERE deleted_at IS NULL
        ORDER BY priority ASC, id ASC
        """,
    )
    suspend fun getAll(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): CategoryRuleEntity?

    @Upsert
    suspend fun upsert(rule: CategoryRuleEntity)

    /** See [RowRevision]: no `deleted_at` condition, deliberately. */
    @Query("SELECT local_revision, remote_revision FROM category_rules WHERE id = :id")
    suspend fun revisionOf(id: String): RowRevision?

    /** See [TransactionDao.rowById]: the local side of a merge, tombstone included. */
    @Query("SELECT * FROM category_rules WHERE id = :id")
    suspend fun rowById(id: String): CategoryRuleEntity?

    /** See [TransactionDao.applyRemoteTombstone]. */
    @Query(
        """
        UPDATE category_rules
        SET deleted_at = :deletedAt, updated_at = :updatedAt, local_revision = :localRevision,
            remote_revision = :remoteRevision, pending_operation = :pendingOperation
        WHERE id = :id
        """,
    )
    suspend fun applyRemoteTombstone(
        id: String,
        deletedAt: Long,
        updatedAt: Long,
        localRevision: Long,
        remoteRevision: Long,
        pendingOperation: String?,
    )

    /**
     * Priority is what decides which rule wins a conflict (§1.5), so
     * reordering is a real edit: it bumps the revision and marks the row
     * pending sync exactly like any other change to the rule.
     *
     * Only the one column, rather than a full-row upsert, so a reorder can't
     * accidentally restate a rule's merchant string or category from a stale
     * copy the screen was holding.
     */
    @Query(
        """
        UPDATE category_rules
        SET priority = :priority, updated_at = :updatedAt,
            pending_operation = 'UPSERT', local_revision = local_revision + 1
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun updatePriority(id: String, priority: Int, updatedAt: Long)

    /**
     * One transaction for the whole reorder, matching applyRuleCategories'
     * reasoning: priorities that half-applied would leave two rules claiming
     * the same slot, and which one then wins is decided by the id tie-break
     * rather than by anything the user chose.
     *
     * Assigning position-in-list as the priority makes the stored values
     * dense and 0-based every time, so repeated reorders can't drift into
     * ever-larger numbers or collide.
     */
    @Transaction
    suspend fun reorder(orderedIds: List<String>, updatedAt: Long) {
        orderedIds.forEachIndexed { index, id -> updatePriority(id, index, updatedAt) }
    }

    /** Soft delete: sync needs the tombstone, same as every other table (rule 7). */
    @Query(
        """
        UPDATE category_rules
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)

    /** See [TransactionDao.dirtyRows]: derived from the shadow, not `pending_operation`. */
    @Query(
        """
        SELECT r.* FROM category_rules r
        LEFT JOIN sync_shadow s ON s.table_name = 'category_rules' AND s.row_id = r.id
        WHERE r.local_revision > COALESCE(s.remote_revision, 0)
        ORDER BY r.id ASC
        LIMIT :limit
        """,
    )
    suspend fun dirtyRows(limit: Int = 200): List<CategoryRuleEntity>

    /** See [TransactionDao.markSynced]: guarded so a push acknowledgement can't clear a newer edit. */
    @Query(
        """
        UPDATE category_rules
        SET pending_operation = NULL, remote_revision = :remoteRevision
        WHERE id = :id AND local_revision = :expectedLocalRevision
        """,
    )
    suspend fun markSynced(id: String, remoteRevision: Long, expectedLocalRevision: Long): Int

    /** See [TransactionDao.allIds]. */
    @Query("SELECT id FROM category_rules")
    suspend fun allIds(): List<String>

    /** See [TransactionDao.tombstonesOlderThan]. */
    @Query("SELECT id FROM category_rules WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun tombstonesOlderThan(before: Long): List<String>

    /** See [TransactionDao.hardDelete]. */
    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun hardDelete(id: String): Int
}
