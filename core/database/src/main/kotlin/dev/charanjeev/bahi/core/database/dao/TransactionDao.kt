package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
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

    /** Used by the CSV importer to detect rows that already exist. */
    @Query("SELECT content_hash FROM transactions WHERE content_hash IN (:hashes)")
    suspend fun findExistingHashes(hashes: List<String>): List<String>

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
     */
    @Transaction
    suspend fun importBatch(transactions: List<TransactionEntity>): Int {
        val existing = findExistingHashes(transactions.map { it.contentHash }).toSet()
        val fresh = transactions.filterNot { it.contentHash in existing }
        insertAllIgnoringConflicts(fresh)
        return fresh.size
    }
}
