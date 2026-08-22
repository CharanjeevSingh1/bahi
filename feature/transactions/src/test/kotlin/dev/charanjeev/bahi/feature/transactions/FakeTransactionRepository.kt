package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake rather than a mock. Mocks verify that a call happened;
 * a fake lets the test assert on observable behaviour, which is what actually
 * breaks in production.
 */
class FakeTransactionRepository : TransactionRepository {

    private val backing = MutableSharedFlow<List<Transaction>>(replay = 1)

    // Mirrors the tombstone: a soft-deleted row's data survives so undoDelete
    // has something to restore, matching TransactionDao.undoSoftDelete.
    private val softDeleted = mutableMapOf<String, Transaction>()

    private var failure: Throwable? = null

    val upserted = mutableListOf<Transaction>()
    val updated = mutableListOf<Transaction>()
    val deletedIds = mutableListOf<String>()

    suspend fun emit(transactions: List<Transaction>) = backing.emit(transactions)

    /** Every collection of [observeTransactions] throws until [clearFailure] is called. */
    fun failWith(throwable: Throwable) {
        failure = throwable
    }

    fun clearFailure() {
        failure = null
    }

    override fun observeTransactions(): Flow<List<Transaction>> = backing.map { transactions ->
        failure?.let { throw it }
        transactions
    }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        backing.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun upsert(transaction: Transaction) {
        upserted += transaction
        val current = backing.replayCache.firstOrNull() ?: emptyList()
        backing.emit(current.filterNot { it.id == transaction.id } + transaction)
    }

    override suspend fun update(transaction: Transaction) {
        updated += transaction
        val current = backing.replayCache.firstOrNull() ?: emptyList()
        backing.emit(current.map { if (it.id == transaction.id) transaction else it })
    }

    override suspend fun delete(id: String) {
        deletedIds += id
        val current = backing.replayCache.firstOrNull() ?: return
        val target = current.firstOrNull { it.id == id } ?: return
        softDeleted[id] = target
        backing.emit(current.filterNot { it.id == id })
    }

    override suspend fun undoDelete(id: String) {
        val restored = softDeleted.remove(id) ?: return
        val current = backing.replayCache.firstOrNull() ?: emptyList()
        backing.emit(current + restored)
    }

    override suspend fun importAll(transactions: List<Transaction>): Int = transactions.size
}
