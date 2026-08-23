package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import kotlinx.coroutines.flow.Flow

/**
 * The only surface features are allowed to touch. Room, DataStore and the sync
 * backend all live behind this interface, which is what lets a ViewModel test
 * run against a fake in milliseconds.
 */
interface TransactionRepository {

    /** [filter] is applied as a query, not by the caller filtering the returned list. */
    fun observeTransactions(filter: TransactionFilter = TransactionFilter.NONE): Flow<List<Transaction>>

    fun observeTransaction(id: String): Flow<Transaction?>

    suspend fun upsert(transaction: Transaction)

    /**
     * For a user edit, not creation: bumps local_revision and marks the row
     * pending sync, distinct from [upsert] which is used for creation,
     * seeding and CSV import and doesn't touch either.
     */
    suspend fun update(transaction: Transaction)

    suspend fun delete(id: String)

    /** Reverses a soft delete: clears the tombstone and the pending DELETE. */
    suspend fun undoDelete(id: String)

    /**
     * Every row this inserts shares a fresh, generated batch id, returned so
     * the caller can offer [undoImport] against exactly this import and no
     * other. [ImportBatchResult.insertedCount] is how many of [transactions]
     * were actually written after de-duplication -- the rest were recognised
     * as duplicates and skipped.
     */
    suspend fun importAll(transactions: List<Transaction>): ImportBatchResult

    /**
     * Soft-deletes every row still carrying [batchId] -- not a hard delete,
     * so sync sees the tombstone the same as any other deletion. A row the
     * user has since hand-edited no longer carries [batchId] (see
     * TransactionDao.update's doc) and is left alone.
     *
     * Returns how many rows were actually removed, which can be fewer than
     * the batch's original insert count -- exactly the hand-edited case
     * above. The caller (the import Result screen) needs the real number,
     * not the batch's original size, or it reports having undone more than
     * it did.
     */
    suspend fun undoImport(batchId: String): Int
}

/** [insertedCount] excludes rows the DAO's de-duplication recognised as duplicates and didn't write. */
data class ImportBatchResult(val batchId: String, val insertedCount: Int)
