package dev.charanjeev.bahi.core.database.dao

import androidx.room.ColumnInfo

/**
 * One existing row that shares a content hash with an incoming import row
 * (docs/sync-design.md §6.1, slice 9b). [id] and [deletedAt] are what
 * [TransactionDao.countExistingHashes]'s old bare count threw away and
 * [TransactionDao.importBatch] needs back: a live row and a tombstoned row
 * both consume the same de-duplication quota unit, but they mean opposite
 * things to a user re-importing a statement, and only [deletedAt] tells them
 * apart.
 */
data class ExistingHashRow(
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/**
 * [TransactionDao.importBatch]'s full answer: which rows it actually wrote,
 * and the two reasons a row wasn't among them. [duplicatesSkipped] +
 * [previouslyDeletedSkipped] + `insertedIds.size` always equals the size of
 * the batch handed in -- every incoming row lands in exactly one bucket.
 */
data class ImportBatchOutcome(
    val insertedIds: List<String>,
    val duplicatesSkipped: Int,
    val previouslyDeletedSkipped: Int,
)
