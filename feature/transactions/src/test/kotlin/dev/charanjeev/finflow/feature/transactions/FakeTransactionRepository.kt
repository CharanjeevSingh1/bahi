package dev.charanjeev.finflow.feature.transactions

import dev.charanjeev.finflow.core.data.repository.TransactionRepository
import dev.charanjeev.finflow.core.model.Transaction
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

    suspend fun emit(transactions: List<Transaction>) = backing.emit(transactions)

    override fun observeTransactions(): Flow<List<Transaction>> = backing

    override fun observeTransaction(id: String): Flow<Transaction?> =
        backing.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun upsert(transaction: Transaction) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun importAll(transactions: List<Transaction>): Int = transactions.size
}
