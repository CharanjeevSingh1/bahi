package dev.charanjeev.bahi.feature.csvimport

import dev.charanjeev.bahi.core.data.repository.ImportBatchResult
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ImportViewModel only ever calls [undoImport] -- importing itself goes
 * through CsvImporter, not this repository directly -- so that's the only
 * method this fake tracks calls for.
 */
class FakeTransactionRepository : TransactionRepository {

    val undoneBatchIds = mutableListOf<String>()

    /** Scripted per test -- defaults to "removed everything", overridden to test the hand-edited-row case. */
    var undoImportReturnValue: Int? = null

    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observeTransaction(id: String): Flow<Transaction?> = flowOf(null)
    override suspend fun upsert(transaction: Transaction) = Unit
    override suspend fun update(transaction: Transaction) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun undoDelete(id: String) = Unit
    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult =
        ImportBatchResult(batchId = "unused", insertedIds = transactions.map { it.id })

    override suspend fun undoImport(batchId: String): Int {
        undoneBatchIds += batchId
        return undoImportReturnValue ?: 0
    }

    /** Recorded, not applied -- the import screen never calls this itself. */
    val appliedRuleCategories = mutableListOf<Map<String, String>>()

    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int {
        appliedRuleCategories += assignments
        return assignments.size
    }
}
