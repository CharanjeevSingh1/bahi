package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Offline-first: reads always come from Room, never from the network. Sync
 * writes into the same tables, so the UI updates through the normal Flow with
 * no special-casing for "we just synced".
 *
 * TODO(M4): conflict resolution hooks land here once :core:sync is built.
 */
class OfflineFirstTransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    // @param: pins the qualifier to the constructor parameter, which is what Hilt
    // reads. Kotlin 2.2 warns that the default target is changing in a future
    // release; being explicit keeps injection working either way.
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : TransactionRepository {

    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> =
        transactionDao.observeFiltered(
            categoryIds = filter.categoryIds.toList(),
            categoryCount = filter.categoryIds.size,
            hasDateWindow = if (filter.dateWindow != null) 1 else 0,
            from = filter.dateWindow?.from?.toString().orEmpty(),
            to = filter.dateWindow?.to?.toString().orEmpty(),
        ).map { entities -> entities.map(::toDomain) }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.let(::toDomain) }

    override suspend fun upsert(transaction: Transaction) = withContext(ioDispatcher) {
        transactionDao.upsert(toEntity(transaction))
    }

    override suspend fun update(transaction: Transaction) = withContext(ioDispatcher) {
        val entity = toEntity(transaction)
        transactionDao.update(
            id = entity.id,
            amountMinor = entity.amountMinor,
            currencyCode = entity.currencyCode,
            date = entity.date,
            description = entity.description,
            merchant = entity.merchant,
            categoryId = entity.categoryId,
            accountId = entity.accountId,
            notes = entity.notes,
            categoryLockedByUser = entity.categoryLockedByUser,
            contentHash = entity.contentHash,
            updatedAt = entity.updatedAt,
        )
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        transactionDao.softDelete(id, System.currentTimeMillis())
    }

    override suspend fun undoDelete(id: String) = withContext(ioDispatcher) {
        transactionDao.undoSoftDelete(id)
    }

    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult =
        withContext(ioDispatcher) {
            val batchId = UUID.randomUUID().toString()
            val insertedCount = transactionDao.importBatch(transactions.map { toEntity(it, importBatchId = batchId) })
            ImportBatchResult(batchId, insertedCount)
        }

    override suspend fun undoImport(batchId: String): Int = withContext(ioDispatcher) {
        transactionDao.softDeleteBatch(batchId, System.currentTimeMillis())
    }
}
