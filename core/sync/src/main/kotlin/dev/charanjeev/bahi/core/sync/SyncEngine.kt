package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.data.repository.SyncApplier
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable

/**
 * Pull -> classify -> merge -> apply -> push, in that order, for one sync
 * cycle (docs/sync-design.md §4.2, §6.2, slice 5).
 *
 * Classify, merge and apply are deliberately not this class's job: see
 * [SyncApplier]'s doc for why that whole step has to run inside `:core:data`'s
 * Room transaction rather than here. What is left for the engine is fetching
 * what changed on each side and threading the per-device pull cursor and push
 * sequence through repeated calls -- the two pieces that have to survive
 * across many [sync] calls on the same instance.
 *
 * [deviceId] identifies this device to every op it produces
 * ([SyncOp.deviceId]) and is neither persisted nor generated here -- that is
 * a slice-8/M4b concern (docs/sync-design.md §8.5's `sync.properties`).
 *
 * The pull cursor and push sequence live only in memory, not in
 * `UserPreferencesDataSource.lastSyncCursor`: that column has to become a
 * per-device map before it can hold this (§8.3), and that change belongs to
 * M4b's transport, not to an engine still only ever exercised against
 * [InMemoryTransport]. A fresh [SyncEngine] per process is exactly what
 * §10.1's two-device harness constructs.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val applier: SyncApplier,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRuleRepository: CategoryRuleRepository,
    private val deviceId: String,
) {

    private val cursor = mutableMapOf<String, Long>()
    private var nextPushSeq = 1L

    /** One full cycle: pull whatever is new and apply it, then push whatever is still dirty. */
    suspend fun sync() {
        pull()
        push()
    }

    private suspend fun pull() {
        val batches = transport.pull(cursor)
        if (batches.isNotEmpty()) {
            // A batch from a future format version is skipped rather than
            // misinterpreted (§4.2) -- but only its ops, not the cursor
            // advance below, or it would be re-fetched on every pull forever.
            val ops = batches.filter { it.isReadable }.flatMap { it.ops }
            applier.apply(ops, deviceId)
            for (batch in batches) {
                cursor[batch.deviceId] = maxOf(cursor[batch.deviceId] ?: 0L, batch.seq)
            }
        }
    }

    private suspend fun push() {
        val transactionRows = transactionRepository.dirtyRows()
        val categoryRows = categoryRepository.dirtyRows()
        val budgetRows = budgetRepository.dirtyRows()
        val ruleRows = categoryRuleRepository.dirtyRows()

        val ops = transactionRows.map { toOp(SyncTable.TRANSACTIONS, it) } +
            categoryRows.map { toOp(SyncTable.CATEGORIES, it) } +
            budgetRows.map { toOp(SyncTable.BUDGETS, it) } +
            ruleRows.map { toOp(SyncTable.CATEGORY_RULES, it) }
        if (ops.isEmpty()) return

        // Parent tables first, matching SyncTable's own declared order and
        // the reason it is declared that way (§4.2): the far side applies
        // this same batch in one transaction too, and a child row must never
        // land ahead of the parent it references.
        val batch = OpBatch(deviceId = deviceId, seq = nextPushSeq, ops = ops)
        transport.push(batch)
        nextPushSeq += 1
        // This device's own pushes are batches it never needs to pull back.
        cursor[deviceId] = batch.seq

        // Acknowledging is guarded per row (docs/sync-design.md §4.3): a row
        // that changed again between the read above and this call stays
        // dirty for the next cycle rather than losing that edit.
        for (row in transactionRows) transactionRepository.markSynced(row.rowId, row.localRevision, row.localRevision)
        for (row in categoryRows) categoryRepository.markSynced(row.rowId, row.localRevision, row.localRevision)
        for (row in budgetRows) budgetRepository.markSynced(row.rowId, row.localRevision, row.localRevision)
        for (row in ruleRows) categoryRuleRepository.markSynced(row.rowId, row.localRevision, row.localRevision)
    }

    private fun toOp(table: SyncTable, row: DirtyRow) = SyncOp(
        table = table.tableName,
        rowId = row.rowId,
        // This device assigns its own row's revision when it pushes -- there
        // is no server to hand one out (D2, §8.3's dumb storage). Reusing
        // local_revision is what closes the loop with markSynced, which
        // records that same number as the row's remote_revision once
        // acknowledged -- see SyncApplier's revision-rebase doc for why the
        // *receiving* device cannot simply trust it as a shared scale.
        remoteRevision = row.localRevision,
        deviceId = deviceId,
        updatedAt = row.updatedAt,
        payload = row.payload,
    )
}
