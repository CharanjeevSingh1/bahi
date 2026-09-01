package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.data.repository.SyncApplier
import dev.charanjeev.bahi.core.data.repository.TombstoneReaper
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
 * ([SyncOp.deviceId]) and is neither persisted nor generated here --
 * [DeviceIdentity] owns that, and [SyncRunner] (slice 9g) is the only caller
 * that resolves one and passes it in.
 *
 * **The pull cursor is seeded and read back, not persisted, by this class.**
 * [initialCursor] seeds it (empty for an install that has never synced) and
 * [cursorSnapshot] reads it back after a [sync] call; round-tripping it
 * through `UserPreferencesDataSource.syncCursor` (§8.3's per-device map)
 * across process death is [SyncRunner]'s job, not this one's -- this class
 * still only knows "in memory since construction," the same as before, it
 * just no longer assumes construction and process start are the same event.
 * A fresh [SyncEngine] seeded with an empty cursor is exactly what §10.1's
 * two-device harness constructs, and exactly what a fresh install's first
 * sync looks like either way.
 *
 * **The push sequence has its own seam, seeded and persisted differently
 * from the pull cursor.** [initialPushSeq] seeds [nextPushSeq] the same
 * shape [initialCursor] seeds [cursor] -- [SyncRunner] reads it back from
 * `UserPreferencesDataSource.pushSeq` -- but persistence itself cannot wait
 * for [cursorSnapshot]'s "read back once the whole cycle finishes" pattern
 * the way the pull cursor does. Re-pulling a batch this device already
 * applied is a no-op (§10.4's idempotence watermark, keyed on each row's own
 * revision, not on which batch carried it); reusing a push sequence number
 * is not -- a peer whose cursor for this device already covers that number
 * would filter the new batch out by `seq > cursor[deviceId]` before a single
 * op inside it is ever inspected, silently, with nothing to reconcile
 * against later. [persistPushSeq] is called with the reserved number before
 * [push] ever calls [SyncTransport.push], so a crash between the two just
 * burns that number -- harmless, since nothing here or in
 * `SyncTransportContractTest` requires push sequences to be contiguous, only
 * monotonic and unique per device -- rather than risking the far worse
 * alternative of persisting after a push that already reached the transport
 * and racing a peer that pulls in between.
 *
 * **Seeding still has to survive an install that predates this counter.**
 * [SyncRunner] does not seed [initialPushSeq] from `pushSeq` alone: an
 * install already running the cursor-persistence fix above has been writing
 * `cursor[deviceId] = batch.seq` into its own persisted cursor on every push
 * for as long as it has been pushing, before `pushSeq` existed to record the
 * same fact directly. Trusting `pushSeq` alone would read null on that
 * install's first run under this fix and hand out 1 again, reusing every
 * number it already sent under the old, unpersisted counter -- the exact bug
 * this seam exists to close, reintroduced by its own migration gap.
 * [SyncRunner] rebases the seed as `max(pushSeq, cursor[deviceId]) + 1`,
 * closing it the same way slice 5c's Lamport rebase closed the equivalent
 * gap for `local_revision`: a value already recorded elsewhere for a
 * different reason turns out to be exactly the floor a fresh counter needs
 * to respect. A genuinely new device id -- the ordinary shape of a reinstall,
 * [DeviceIdentity]'s doc -- has no entry in any peer's cursor yet, so seeding
 * it at 1 is correct, not the same hazard: the rebase only ever raises the
 * floor for an id a peer could already be holding a watermark for.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val applier: SyncApplier,
    private val reaper: TombstoneReaper,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRuleRepository: CategoryRuleRepository,
    private val deviceId: String,
    initialCursor: Map<String, Long> = emptyMap(),
    initialPushSeq: Long = 0L,
    private val persistPushSeq: suspend (Long) -> Unit = {},
) {

    private val cursor = initialCursor.toMutableMap()
    private var nextPushSeq = initialPushSeq + 1

    /** Read after [sync] so [SyncRunner] can persist it -- see this class's own doc for why persisting it is not this class's job. */
    val cursorSnapshot: Map<String, Long> get() = cursor.toMap()

    /**
     * One full cycle: pull whatever is new and apply it, then push whatever
     * is still dirty, then sweep tombstones and acknowledged conflicts past
     * the horizon (docs/sync-design.md §7, D8).
     *
     * The reap runs here, once per cycle, rather than from a periodic
     * worker: M4a has no WorkManager wiring yet (slice 9, M4b's job), so
     * this is what makes the horizon a code path every scripted scenario and
     * every property-test seed actually exercises, instead of one waiting on
     * scheduling infrastructure that does not exist yet.
     */
    suspend fun sync() {
        pull()
        push()
        reaper.reap()
    }

    private suspend fun pull() {
        reconcileIfBehindHorizon()

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

    /**
     * §7: a device whose cursor for some peer is older than that peer's
     * entry in the remote's [dev.charanjeev.bahi.core.model.RemoteSnapshot.horizon]
     * cannot trust an incremental pull -- history it has not seen for that
     * peer has already been deleted -- and reconciles against the snapshot
     * instead. Reconciling brings this device level with the *whole*
     * horizon, not just the peer that happened to trigger it, because a
     * compacted snapshot is one merged file covering every device (§8.3);
     * there is no cheaper way to ask "just the part I'm missing".
     *
     * Calling [SyncTransport.snapshot] on every cycle is the simple choice
     * for [InMemoryTransport], where it is free. A real backend (M4b) that
     * pays a network round trip for this on every sync, whether or not it is
     * ever behind, would likely want a cheaper way to learn the horizon
     * before fetching the rows -- not a problem this engine has evidence for
     * yet.
     */
    private suspend fun reconcileIfBehindHorizon() {
        val snapshot = transport.snapshot()
        val behindHorizon = snapshot.horizon.any { (peerId, seq) -> (cursor[peerId] ?: 0L) < seq }
        if (!behindHorizon) return

        applier.reconcile(snapshot, deviceId)
        for ((peerId, seq) in snapshot.horizon) {
            cursor[peerId] = maxOf(cursor[peerId] ?: 0L, seq)
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

        // Reserved and persisted before the batch ever reaches the transport
        // -- see this class's own doc for why that order, not the reverse,
        // is what keeps a crash from ever reusing this number.
        val seq = nextPushSeq
        nextPushSeq += 1
        persistPushSeq(seq)

        // Parent tables first, matching SyncTable's own declared order and
        // the reason it is declared that way (§4.2): the far side applies
        // this same batch in one transaction too, and a child row must never
        // land ahead of the parent it references.
        val batch = OpBatch(deviceId = deviceId, seq = seq, ops = ops)
        transport.push(batch)
        // This device's own pushes are batches it never needs to pull back.
        cursor[deviceId] = batch.seq

        // Acknowledging is guarded per row (docs/sync-design.md §4.3): a row
        // that changed again between the read above and this call stays
        // dirty for the next cycle rather than losing that edit.
        //
        // recordPushed is the other half, found while building slice 6's
        // two-device harness: markSynced only clears this row's own
        // pending_operation/remote_revision columns, but dirtyRows judges
        // "dirty" against sync_shadow.remote_revision, which nothing wrote
        // for a row this device pushed. Skipping it when markSynced returns
        // false is the same guard for the same reason -- a row that moved
        // under the push has not actually settled at this revision, so
        // recording it as the agreed base would be recording a lie.
        for (row in transactionRows) {
            if (transactionRepository.markSynced(row.rowId, row.localRevision, row.localRevision)) {
                applier.recordPushed(SyncTable.TRANSACTIONS, row.rowId, row.localRevision, row.payload)
            }
        }
        for (row in categoryRows) {
            if (categoryRepository.markSynced(row.rowId, row.localRevision, row.localRevision)) {
                applier.recordPushed(SyncTable.CATEGORIES, row.rowId, row.localRevision, row.payload)
            }
        }
        for (row in budgetRows) {
            if (budgetRepository.markSynced(row.rowId, row.localRevision, row.localRevision)) {
                applier.recordPushed(SyncTable.BUDGETS, row.rowId, row.localRevision, row.payload)
            }
        }
        for (row in ruleRows) {
            if (categoryRuleRepository.markSynced(row.rowId, row.localRevision, row.localRevision)) {
                applier.recordPushed(SyncTable.CATEGORY_RULES, row.rowId, row.localRevision, row.payload)
            }
        }
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
