package dev.charanjeev.finflow.core.data.repository

import dev.charanjeev.finflow.core.common.Dispatcher
import dev.charanjeev.finflow.core.common.FinFlowDispatcher
import dev.charanjeev.finflow.core.database.dao.TransactionDao
import dev.charanjeev.finflow.core.model.Transaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    @param:Dispatcher(FinFlowDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : TransactionRepository {

    override fun observeTransactions(): Flow<List<Transaction>> =
        transactionDao.observeAll().map { entities -> entities.map(::toDomain) }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.let(::toDomain) }

    override suspend fun upsert(transaction: Transaction) = withContext(ioDispatcher) {
        transactionDao.upsert(toEntity(transaction))
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        transactionDao.softDelete(id, System.currentTimeMillis())
    }

    override suspend fun importAll(transactions: List<Transaction>): Int =
        withContext(ioDispatcher) {
            transactionDao.importBatch(transactions.map(::toEntity))
        }
}
