package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.data.repository.ImportBatchResult
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
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

    // In-memory filtering is fine for a fake standing in for a real query --
    // what matters is that TransactionsViewModel never does this itself.
    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> = backing.map { transactions ->
        failure?.let { throw it }
        val window = filter.dateWindow
        transactions.filter { transaction ->
            (filter.categoryIds.isEmpty() || transaction.categoryId in filter.categoryIds) &&
                (window == null || transaction.date in window.from..window.to)
        }
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

    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult = ImportBatchResult(
        batchId = "unused",
        insertedIds = transactions.map { it.id },
        duplicatesSkipped = 0,
        previouslyDeletedSkipped = 0,
    )

    override suspend fun undoImport(batchId: String): Int = 0

    /** Nothing in :feature:transactions triggers auto-categorisation; recorded so a test could. */
    val appliedRuleCategories = mutableListOf<Map<String, String>>()

    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int {
        appliedRuleCategories += assignments
        return assignments.size
    }

    /** Not exercised: nothing under test here reaches the sync engine's push step. */
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = emptyList()
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean = false
}
