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

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
        ORDER BY date DESC, created_at DESC
        """,
    )
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL AND date BETWEEN :from AND :to
        ORDER BY date DESC
        """,
    )
    fun observeBetween(from: String, to: String): Flow<List<TransactionEntity>>

    /** Used by the CSV importer to detect rows that already exist. */
    @Query("SELECT content_hash FROM transactions WHERE content_hash IN (:hashes)")
    suspend fun findExistingHashes(hashes: List<String>): List<String>

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

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
