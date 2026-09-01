package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.data.repository.DirtyRow
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
 *
 * Batch membership, by contrast, *is* modelled honestly ([batchOf] below):
 * whether a rule-categorised row stays undoable is behaviour this fake has
 * to reproduce rather than script, or the end-to-end test asserting it would
 * only be testing itself. The rule it mirrors is the real DAO's: `update`
 * evicts a row from its batch, `applyRuleCategories` does not.
 */
class FakeTransactionRepository : TransactionRepository {

    var importAllReturnValue: Int = 0

    /**
     * Defaults to 0, which is what keeps every test predating slice 9b
     * passing unchanged: with this at 0, [importAll] attributes every
     * un-inserted row to `duplicatesSkipped`, exactly the "everything not
     * inserted is a duplicate" arithmetic the importer itself used to do
     * before it started trusting the repository's own two counts
     * (docs/sync-design.md §6.1). A test exercising the split sets this to
     * carve some of those un-inserted rows out as tombstone collisions
     * instead.
     */
    var previouslyDeletedSkippedReturnValue: Int = 0
    var importAllBatchId: String = "fake-batch-id"
    var lastImportedBatch: List<Transaction> = emptyList()
        private set
    val undoneBatchIds = mutableListOf<String>()

    /** transaction id -> the import batch it still belongs to. */
    private val batchOf = mutableMapOf<String, String>()

    /** Rows this fake believes exist, so a locked one can be refused like the real write does. */
    private val rows = mutableMapOf<String, Transaction>()

    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> = flowOf(emptyList())
    override fun observeTransaction(id: String): Flow<Transaction?> = flowOf(null)
    override suspend fun upsert(transaction: Transaction) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun undoDelete(id: String) = Unit

    /** Mirrors TransactionDao.update: a hand-edit takes the row out of its batch. */
    override suspend fun update(transaction: Transaction) {
        rows[transaction.id] = transaction
        batchOf.remove(transaction.id)
    }

    /**
     * Which rows are "inserted" is the first [importAllReturnValue] of them.
     * Which specific ones is arbitrary and deliberately so -- the count stays
     * scripted rather than derived, per the class doc; naming ids just lets a
     * caller act on exactly the inserted set.
     */
    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult {
        lastImportedBatch = transactions
        val inserted = transactions.take(importAllReturnValue)
        inserted.forEach { transaction ->
            rows[transaction.id] = transaction
            batchOf[transaction.id] = importAllBatchId
        }
        val duplicatesSkipped = transactions.size - inserted.size - previouslyDeletedSkippedReturnValue
        return ImportBatchResult(
            batchId = importAllBatchId,
            insertedIds = inserted.map { it.id },
            duplicatesSkipped = duplicatesSkipped,
            previouslyDeletedSkipped = previouslyDeletedSkippedReturnValue,
        )
    }

    var undoImportReturnValue: Int? = null

    override suspend fun undoImport(batchId: String): Int {
        undoneBatchIds += batchId
        undoImportReturnValue?.let { return it }
        val removed = batchOf.filterValues { it == batchId }.keys
        removed.forEach { batchOf.remove(it); rows.remove(it) }
        return removed.size
    }

    val appliedRuleCategories = mutableListOf<Map<String, String>>()

    /**
     * Mirrors TransactionDao.applyRuleCategory on the two points that matter
     * here: a locked row is refused (and so not counted), and a categorised
     * row keeps its batch membership.
     */
    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int {
        appliedRuleCategories += assignments
        return assignments.count { (id, categoryId) ->
            val existing = rows[id]
            if (existing == null || existing.categoryLockedByUser) {
                false
            } else {
                rows[id] = existing.copy(categoryId = categoryId)
                true
            }
        }
    }

    fun categoryOf(id: String): String? = rows[id]?.categoryId

    /** Not exercised: nothing under test here reaches the sync engine's push step. */
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = emptyList()
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean = false
}
