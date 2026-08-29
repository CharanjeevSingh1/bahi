package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncConflictDao {

    /**
     * The one write path, and the place the table's bound is enforced: at most
     * one *unacknowledged* conflict per (table, row, field).
     *
     * Without it, two devices that keep disagreeing about the same field
     * accumulate a row per sync, which is the unbounded list
     * docs/sync-design.md §5.6 warns about and §7 refuses for tombstones. The
     * cost is real and worth naming: the superseded row's `discarded_value` is
     * gone, and it was the only copy. What it was is a value from a merge that
     * has since been merged over again, so restoring it would put back
     * something two edits stale -- but it is a loss, not a free win.
     *
     * Acknowledged conflicts are never superseded. Once the user has seen it
     * the row is history, and history is what this table is for.
     *
     * SQLite could express this as `UNIQUE(table_name, row_id, field) WHERE
     * acknowledged_at IS NULL`, but `@Entity(indices)` cannot declare a
     * partial index -- the same limit BudgetEntity documents for its own
     * one-per-category-per-month rule. So the invariant goes in the write path
     * where a caller cannot forget it, which is where budgets-design §1.4 puts
     * its guards for the same reason.
     */
    @Transaction
    suspend fun record(conflict: SyncConflictEntity) {
        supersedeUnacknowledged(conflict.tableName, conflict.rowId, conflict.field)
        insert(conflict)
    }

    @Insert
    suspend fun insert(conflict: SyncConflictEntity)

    @Query(
        """
        DELETE FROM sync_conflicts
        WHERE table_name = :table AND row_id = :rowId AND field = :field
          AND acknowledged_at IS NULL
        """,
    )
    suspend fun supersedeUnacknowledged(table: String, rowId: String, field: String): Int

    @Query("SELECT * FROM sync_conflicts WHERE acknowledged_at IS NULL ORDER BY resolved_at DESC")
    fun observeUnacknowledged(): Flow<List<SyncConflictEntity>>

    /** The count behind the Settings row ("3 conflicts resolved -- review"), §5.6. */
    @Query("SELECT COUNT(*) FROM sync_conflicts WHERE acknowledged_at IS NULL")
    fun observeUnacknowledgedCount(): Flow<Int>

    @Query("SELECT * FROM sync_conflicts WHERE table_name = :table AND row_id = :rowId ORDER BY resolved_at DESC")
    fun observeForRow(table: String, rowId: String): Flow<List<SyncConflictEntity>>

    /**
     * Guarded the same way `TransactionDao.markSynced` is, and for a smaller
     * but similar reason: `acknowledged_at` is the clock the horizon sweep
     * reads, so acknowledging an already-acknowledged conflict would push its
     * expiry forward every time the screen was opened.
     */
    @Query("UPDATE sync_conflicts SET acknowledged_at = :at WHERE id = :id AND acknowledged_at IS NULL")
    suspend fun acknowledge(id: String, at: Long): Int

    /**
     * Called when a tombstone crosses the horizon and its row is hard-deleted
     * (§7). A conflict outliving the row it is about would render as an entry
     * pointing at nothing -- and there is no foreign key to do this
     * automatically, because the parent table is named by a column.
     */
    @Query("DELETE FROM sync_conflicts WHERE table_name = :table AND row_id = :rowId")
    suspend fun forgetRow(table: String, rowId: String): Int

    /**
     * The other half of the horizon sweep (§5.6, §7). Acknowledged only:
     * dropping an unacknowledged conflict would discard the losing value
     * before anyone had the chance to look at it, which is the one thing this
     * table exists to prevent.
     */
    @Query("DELETE FROM sync_conflicts WHERE acknowledged_at IS NOT NULL AND acknowledged_at < :before")
    suspend fun deleteAcknowledgedBefore(before: Long): Int
}
