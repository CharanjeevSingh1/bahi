package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.data.repository.ImportBatchResult
import dev.charanjeev.bahi.core.data.repository.SyncApplier
import dev.charanjeev.bahi.core.data.repository.TombstoneReaper
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Shared across [SyncEngineTest] and `SyncRunnerTest`: both drive a real
 * [SyncEngine] end to end and need the same four dirty-row-reading
 * repositories, applier and reaper underneath it. Extracted here rather than
 * duplicated once a second test needed the exact same shape (docs/sync-design.md
 * §13 slice 9g) -- `internal`, not `private`, so both test files can see it
 * without either owning it.
 */
internal class FakeSyncApplier : SyncApplier {
    val calls = mutableListOf<Pair<List<SyncOp>, String>>()
    val recordedPushes = mutableListOf<PushedShadow>()
    val reconcileCalls = mutableListOf<Pair<RemoteSnapshot, String>>()

    override suspend fun apply(ops: List<SyncOp>, localDeviceId: String) {
        calls += ops to localDeviceId
    }

    override suspend fun recordPushed(table: SyncTable, rowId: String, remoteRevision: Long, payload: JsonObject?) {
        recordedPushes += PushedShadow(table, rowId, remoteRevision, payload)
    }

    override suspend fun reconcile(snapshot: RemoteSnapshot, localDeviceId: String) {
        reconcileCalls += snapshot to localDeviceId
    }
}

internal data class PushedShadow(val table: SyncTable, val rowId: String, val remoteRevision: Long, val payload: JsonObject?)

internal class FakeTombstoneReaper : TombstoneReaper {
    var reapCount = 0
        private set

    override suspend fun reap() {
        reapCount += 1
    }
}

/** Records exactly the two calls [SyncEngine] makes: reading what is dirty, and acknowledging what was pushed. */
internal class FakeTransactionRepository(
    private val dirty: List<DirtyRow> = emptyList(),
    private val markSyncedResult: Boolean = true,
) : TransactionRepository {
    val markSyncedCalls = mutableListOf<Triple<String, Long, Long>>()
    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> = MutableStateFlow(emptyList())
    override fun observeTransaction(id: String): Flow<Transaction?> = MutableStateFlow(null)
    override suspend fun upsert(transaction: Transaction): Unit = error("not used")
    override suspend fun update(transaction: Transaction): Unit = error("not used")
    override suspend fun delete(id: String): Unit = error("not used")
    override suspend fun undoDelete(id: String): Unit = error("not used")
    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult = error("not used")
    override suspend fun undoImport(batchId: String): Int = error("not used")
    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int = error("not used")
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = dirty
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean {
        markSyncedCalls += Triple(rowId, remoteRevision, expectedLocalRevision)
        return markSyncedResult
    }
}

internal class FakeCategoryRepository(private val dirty: List<DirtyRow> = emptyList()) : CategoryRepository {
    val markSyncedCalls = mutableListOf<Triple<String, Long, Long>>()
    override fun observeCategories(): Flow<List<Category>> = MutableStateFlow(emptyList())
    override suspend fun upsert(category: Category): Unit = error("not used")
    override suspend fun delete(id: String): Unit = error("not used")
    override suspend fun seedSystemCategoriesIfNeeded(): Unit = error("not used")
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = dirty
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean {
        markSyncedCalls += Triple(rowId, remoteRevision, expectedLocalRevision)
        return true
    }
}

internal class FakeBudgetRepository(private val dirty: List<DirtyRow> = emptyList()) : BudgetRepository {
    val markSyncedCalls = mutableListOf<Triple<String, Long, Long>>()
    override fun observeBudgets(month: YearMonth): Flow<List<Budget>> = MutableStateFlow(emptyList())
    override fun observeMonthlyBudgets(month: YearMonth): Flow<MonthlyBudgets> =
        MutableStateFlow(MonthlyBudgets(month, emptyList(), Money(0)))
    override suspend fun upsert(budget: Budget): Unit = error("not used")
    override suspend fun delete(id: String): Unit = error("not used")
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = dirty
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean {
        markSyncedCalls += Triple(rowId, remoteRevision, expectedLocalRevision)
        return true
    }
}

internal class FakeCategoryRuleRepository(private val dirty: List<DirtyRow> = emptyList()) : CategoryRuleRepository {
    val markSyncedCalls = mutableListOf<Triple<String, Long, Long>>()
    override fun observeRules(): Flow<List<CategoryRule>> = MutableStateFlow(emptyList())
    override suspend fun rules(): List<CategoryRule> = emptyList()
    override suspend fun upsert(rule: CategoryRule): Unit = error("not used")
    override suspend fun delete(id: String): Unit = error("not used")
    override suspend fun reorder(orderedIds: List<String>): Unit = error("not used")
    override suspend fun previewApplyToExisting(rule: CategoryRule): RuleApplicationPreview = error("not used")
    override suspend fun previewRecategoriseUncategorised(): RuleApplicationPreview = error("not used")
    override suspend fun apply(preview: RuleApplicationPreview): Int = error("not used")
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = dirty
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean {
        markSyncedCalls += Triple(rowId, remoteRevision, expectedLocalRevision)
        return true
    }
}
