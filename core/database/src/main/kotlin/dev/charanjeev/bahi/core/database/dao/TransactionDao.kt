package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    /**
     * categoryCount and hasDateWindow turn an absent filter into a no-op
     * condition instead of Room needing a separate query per combination --
     * an empty :categoryIds would otherwise make `IN ()` reject every row.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND (:categoryCount = 0 OR category_id IN (:categoryIds))
          AND (:hasDateWindow = 0 OR date BETWEEN :from AND :to)
        ORDER BY date DESC, created_at DESC
        """,
    )
    fun observeFiltered(
        categoryIds: List<String>,
        categoryCount: Int,
        hasDateWindow: Int,
        from: String,
        to: String,
    ): Flow<List<TransactionEntity>>

    /**
     * A count per hash, not just presence: two genuinely identical
     * transactions -- same coffee shop, same amount, twice -- hash the
     * same, and presence alone can't tell "this exact one was already
     * imported" from "one of these was, the other wasn't." See
     * [importBatch] for how the count is used.
     */
    @Query(
        """
        SELECT content_hash, COUNT(*) AS existing_count FROM transactions
        WHERE content_hash IN (:hashes)
        GROUP BY content_hash
        """,
    )
    suspend fun countExistingHashes(
        hashes: List<String>,
    ): Map<@MapColumn(columnName = "content_hash") String, @MapColumn(columnName = "existing_count") Int>

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    /**
     * Distinct from [upsert]: a user edit always bumps local_revision and
     * marks the row pending sync, the same way softDelete/undoSoftDelete do
     * it in one atomic UPDATE rather than a read-then-write. Plain upsert
     * stays as-is for creation, seeding and CSV import, which don't have an
     * existing revision to bump.
     */
    @Query(
        """
        UPDATE transactions
        SET amount_minor = :amountMinor, currency_code = :currencyCode, date = :date,
            description = :description, merchant = :merchant, category_id = :categoryId,
            account_id = :accountId, notes = :notes, category_locked_by_user = :categoryLockedByUser,
            content_hash = :contentHash, updated_at = :updatedAt,
            pending_operation = 'UPSERT', local_revision = local_revision + 1
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: String,
        amountMinor: Long,
        currencyCode: String,
        date: String,
        description: String,
        merchant: String?,
        categoryId: String?,
        accountId: String,
        notes: String?,
        categoryLockedByUser: Boolean,
        contentHash: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(transactions: List<TransactionEntity>): List<Long>

    /** Soft delete: sync needs the tombstone so other devices learn about it. */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Reverses [softDelete]. Sets pending_operation back to UPSERT rather than
     * clearing it -- NULL means "in sync with remote", which is false the
     * moment the DELETE this undoes has already been pushed: the remote would
     * keep the deletion and the row would vanish again on the next sync.
     * UPSERT re-asserts the row so sync pushes it back.
     */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = NULL, pending_operation = 'UPSERT', local_revision = local_revision + 1
        WHERE id = :id
        """,
    )
    suspend fun undoSoftDelete(id: String)

    @Query("SELECT * FROM transactions WHERE pending_operation IS NOT NULL LIMIT :limit")
    suspend fun pendingChanges(limit: Int = 200): List<TransactionEntity>

    @Query("UPDATE transactions SET pending_operation = NULL, remote_revision = :remoteRevision WHERE id = :id")
    suspend fun markSynced(id: String, remoteRevision: Long)

    /**
     * Runs de-duplication and insert inside one transaction so a large import
     * can't half-apply if the process dies midway.
     *
     * De-duplication is count-aware, not presence-aware (docs/csv-import-
     * design.md §4): each incoming row consumes one unit of "already exists"
     * quota for its hash before any row with that hash is treated as fresh,
     * so two identical-tuple rows re-imported alongside a genuinely new
     * third one are recognised as two duplicates and one addition, not
     * three duplicates or three additions. This is only correct if same-
     * tuple rows keep a stable relative order across re-exports of an
     * overlapping statement period -- every bank export encountered so far
     * does (chronological, ties broken by an internal sequence number), but
     * nothing enforces it. If a re-export ever reordered same-tuple rows
     * relative to an earlier import, this can drop the wrong one and
     * re-insert a duplicate of the wrong one instead -- the total row count
     * would still look right, which is what would make it easy to miss.
     */
    @Transaction
    suspend fun importBatch(transactions: List<TransactionEntity>): Int {
        val remainingExisting = countExistingHashes(transactions.map { it.contentHash }).toMutableMap()
        val fresh = transactions.filter { transaction ->
            val remaining = remainingExisting.getOrDefault(transaction.contentHash, 0)
            if (remaining > 0) {
                remainingExisting[transaction.contentHash] = remaining - 1
                false
            } else {
                true
            }
        }
        insertAllIgnoringConflicts(fresh)
        return fresh.size
    }
}
