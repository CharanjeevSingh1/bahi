package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.data.repository.ImportBatchResult
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [importAllReturnValue] is set directly by each test rather than derived
 * from [lastImportedBatch]'s content -- the point of this fake, for
 * DefaultCsvImporterTest's dedup tests, is that its return value has no
 * relationship to the batch's actual content, which is exactly what proves
 * duplicatesSkipped comes from the repository's answer and not from the
 * importer re-deriving one of its own.
 */
class FakeTransactionRepository : TransactionRepository {

    var importAllReturnValue: Int = 0
    var importAllBatchId: String = "fake-batch-id"
    var lastImportedBatch: List<Transaction> = emptyList()
        private set
    val undoneBatchIds = mutableListOf<String>()

    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observeTransaction(id: String): Flow<Transaction?> = flowOf(null)
    override suspend fun upsert(transaction: Transaction) = Unit
    override suspend fun update(transaction: Transaction) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun undoDelete(id: String) = Unit

    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult {
        lastImportedBatch = transactions
        return ImportBatchResult(importAllBatchId, importAllReturnValue)
    }

    var undoImportReturnValue: Int = 0

    override suspend fun undoImport(batchId: String): Int {
        undoneBatchIds += batchId
        return undoImportReturnValue
    }

    /**
     * Recorded rather than applied: nothing in :core:importer calls this yet
     * (that's the slice that runs rules at import time), and when it does,
     * what the test needs to assert is which assignments were handed over.
     */
    val appliedRuleCategories = mutableListOf<Map<String, String>>()
    var applyRuleCategoriesReturnValue: Int = 0

    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int {
        appliedRuleCategories += assignments
        return applyRuleCategoriesReturnValue
    }
}
