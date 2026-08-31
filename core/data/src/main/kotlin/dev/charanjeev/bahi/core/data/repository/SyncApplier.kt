package dev.charanjeev.bahi.core.data.repository

import androidx.room.withTransaction
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SnapshotRow
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import javax.inject.Inject

/**
 * Applies a pulled batch of ops -- classify, merge, write, for every row
 * across every table, in one Room transaction (docs/sync-design.md §6.2).
 *
 * The caller ([dev.charanjeev.bahi.core.sync]'s engine) does no classifying
 * of its own: it hands over the raw ops it pulled, already filtered to
 * readable ([dev.charanjeev.bahi.core.model.OpBatch.isReadable]) versions,
 * and nothing else. Reading current row state, deciding fast-forward versus
 * merge, and writing the result all have to happen together, or a user edit
 * landing between the read and the write would be silently overwritten by a
 * stale merge -- which is why this is one method rather than "read" and
 * "write" split across the module boundary.
 */
interface SyncApplier {
    suspend fun apply(ops: List<SyncOp>, localDeviceId: String)

    /**
     * The second of the two moments docs/sync-design.md §4.1 names for a
     * shadow write -- and the one slice 5c's engine built but never called.
     * [apply] covers the first (a *pulled* op becomes the new base); this
     * covers "this device's own edit is what the far side now has", which
     * only the engine's push step knows happened, and only once the push is
     * actually acknowledged.
     *
     * Found while building the two-device harness (slice 6): without this,
     * `dirtyRows` -- which compares `local_revision` against
     * `sync_shadow.remote_revision`, not the `remote_revision` *column* that
     * [TransactionRepository.markSynced] and its three siblings update on the
     * row itself -- never sees a device's own acknowledged push as synced.
     * The row's `remote_revision` column advances, `pending_operation`
     * clears, and `local_revision` still exceeds the shadow's stale (or
     * absent) `remote_revision`, so the row is pushed again next cycle,
     * forever, on every device that has ever pushed anything.
     *
     * [payload] is `null` for an acknowledged deletion, matching every other
     * shadow write's convention for a tombstoned base.
     */
    suspend fun recordPushed(table: SyncTable, rowId: String, remoteRevision: Long, payload: JsonObject?)

    /**
     * The full-reconciliation path (docs/sync-design.md §7, D8): what a
     * device whose pull cursor has fallen behind a compacted remote's
     * horizon does instead of an incremental [apply]. Every row [snapshot]
     * still knows about is applied exactly like an ordinary pulled op --
     * reusing [apply]'s own merge machinery is what §7 means by "the
     * reconciliation path is needed anyway for a new device", since a fresh
     * install's first sync is the same shape. What is genuinely new here is
     * the rows this device has that [snapshot] does not: absence is only
     * informative when compared against everything this device holds, not
     * just what is currently dirty, which is why this takes a whole table
     * scan rather than a batch of ops.
     */
    suspend fun reconcile(snapshot: RemoteSnapshot, localDeviceId: String)
}

/**
 * The Room-backed implementation, and the only one -- there is nothing to
 * fake here the way DAOs are faked for JVM repository tests, since the whole
 * point under test is that SQLite really does serialise the writers. See
 * `SyncApplierTest` (androidTest, real Room).
 */
class RoomSyncApplier @Inject constructor(
    private val database: BahiDatabase,
    private val merge: RemoteMerge,
    private val clock: Clock,
) : SyncApplier {

    override suspend fun apply(ops: List<SyncOp>, localDeviceId: String) {
        if (ops.isEmpty()) return
        database.withTransaction {
            // Parent tables first (SyncTable's own declared order): a
            // transaction or a budget referencing a category this same batch
            // is creating must never apply before its parent does.
            for (table in SyncTable.entries) {
                val forTable = ops.filter { it.table == table.tableName }
                if (forTable.isEmpty()) continue
                // More than one op for the same row can arrive in one pull if
                // this device missed several of the author's push cycles.
                // remoteRevision is monotonic per row (§4.2), so the newest
                // one supersedes the others -- they are history, not separate
                // merges to fold in one after another.
                val newestPerRow = forTable.groupBy { it.rowId }
                    .mapValues { (_, group) -> group.maxBy { it.remoteRevision } }
                for ((rowId, op) in newestPerRow) {
                    applyOne(table, rowId, op, localDeviceId)
                }
            }
        }
    }

    private suspend fun applyOne(table: SyncTable, rowId: String, op: SyncOp, localDeviceId: String) {
        val shadow = database.syncShadowDao().baseOf(table.tableName, rowId)
        // Idempotence (§10.4): an op this device has already incorporated, or
        // something newer than it, carries no new information. Re-delivery is
        // the ordinary shape of at-least-once storage, not a corruption --
        // see the class doc.
        if (op.remoteRevision <= (shadow?.remoteRevision ?: 0L)) return

        // A null shadow.payload and a missing shadow row both mean "no field
        // values to compare against" (§4.1) -- collapsed here rather than
        // three lines down, so every call site after this sees one JsonObject?
        val base = shadow?.payload?.let { Json.parseToJsonElement(it).jsonObject }

        when (table) {
            SyncTable.TRANSACTIONS -> applyTransaction(rowId, op, base, localDeviceId)
            SyncTable.CATEGORIES -> applyCategory(rowId, op, base, localDeviceId)
            SyncTable.BUDGETS -> applyBudget(rowId, op, base, localDeviceId)
            SyncTable.CATEGORY_RULES -> applyCategoryRule(rowId, op, base, localDeviceId)
        }
    }

    private suspend fun applyTransaction(rowId: String, op: SyncOp, base: JsonObject?, localDeviceId: String) {
        val existing = database.transactionDao().rowById(rowId)
        val local = MergeSideInput(
            payload = existing?.takeIf { it.deletedAt == null }?.let { toFieldMap(it) },
            updatedAt = existing?.updatedAt ?: 0L,
            deviceId = localDeviceId,
        )
        val outcome = merge.merge(SyncTable.TRANSACTIONS, local, remoteSide(op), base)
        val decision = decide(local, existing?.localRevision ?: 0L, op, outcome)

        if (decision.payload == null) {
            if (existing != null) {
                database.transactionDao().applyRemoteTombstone(
                    id = rowId,
                    // Not decision.updatedAt: that value is derived from the
                    // op's own updated_at, which for a delete-sourced op is
                    // the row's *last edit* time (softDelete never touches
                    // updated_at) -- using it here would backdate the
                    // tombstone. No cross-device deletion instant is ever
                    // transmitted (SyncOp carries none), so "now" is read the
                    // same way createdAt is for a brand-new row: when this
                    // device learned about it, not a fact about the row.
                    deletedAt = clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                )
            }
        } else {
            database.transactionDao().upsert(
                transactionFromFieldMap(
                    id = rowId,
                    payload = decision.payload,
                    createdAt = existing?.createdAt ?: clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                ),
            )
        }
        recordShadowAndConflicts(SyncTable.TRANSACTIONS, rowId, op, decision)
    }

    private suspend fun applyCategory(rowId: String, op: SyncOp, base: JsonObject?, localDeviceId: String) {
        val existing = database.categoryDao().rowById(rowId)
        val local = MergeSideInput(
            payload = existing?.takeIf { it.deletedAt == null }?.let { toFieldMap(it) },
            updatedAt = existing?.updatedAt ?: 0L,
            deviceId = localDeviceId,
        )
        val outcome = merge.merge(SyncTable.CATEGORIES, local, remoteSide(op), base)
        val decision = decide(local, existing?.localRevision ?: 0L, op, outcome)

        if (decision.payload == null) {
            if (existing != null) {
                database.categoryDao().applyRemoteTombstone(
                    id = rowId,
                    // Not decision.updatedAt: that value is derived from the
                    // op's own updated_at, which for a delete-sourced op is
                    // the row's *last edit* time (softDelete never touches
                    // updated_at) -- using it here would backdate the
                    // tombstone. No cross-device deletion instant is ever
                    // transmitted (SyncOp carries none), so "now" is read the
                    // same way createdAt is for a brand-new row: when this
                    // device learned about it, not a fact about the row.
                    deletedAt = clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                )
            }
        } else {
            database.categoryDao().upsertAll(
                listOf(
                    categoryFromFieldMap(
                        id = rowId,
                        payload = decision.payload,
                        updatedAt = decision.updatedAt,
                        localRevision = decision.localRevision,
                        remoteRevision = op.remoteRevision,
                        pendingOperation = decision.pendingOperation,
                    ),
                ),
            )
        }
        recordShadowAndConflicts(SyncTable.CATEGORIES, rowId, op, decision)
    }

    private suspend fun applyBudget(rowId: String, op: SyncOp, base: JsonObject?, localDeviceId: String) {
        val existing = database.budgetDao().rowById(rowId)
        val local = MergeSideInput(
            payload = existing?.takeIf { it.deletedAt == null }?.let { toFieldMap(it) },
            updatedAt = existing?.updatedAt ?: 0L,
            deviceId = localDeviceId,
        )
        val outcome = merge.merge(SyncTable.BUDGETS, local, remoteSide(op), base)
        val decision = decide(local, existing?.localRevision ?: 0L, op, outcome)

        if (decision.payload == null) {
            if (existing != null) {
                database.budgetDao().applyRemoteTombstone(
                    id = rowId,
                    // Not decision.updatedAt: that value is derived from the
                    // op's own updated_at, which for a delete-sourced op is
                    // the row's *last edit* time (softDelete never touches
                    // updated_at) -- using it here would backdate the
                    // tombstone. No cross-device deletion instant is ever
                    // transmitted (SyncOp carries none), so "now" is read the
                    // same way createdAt is for a brand-new row: when this
                    // device learned about it, not a fact about the row.
                    deletedAt = clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                )
            }
        } else {
            database.budgetDao().upsert(
                budgetFromFieldMap(
                    id = rowId,
                    payload = decision.payload,
                    createdAt = existing?.createdAt ?: clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                ),
            )
        }
        recordShadowAndConflicts(SyncTable.BUDGETS, rowId, op, decision)
    }

    private suspend fun applyCategoryRule(rowId: String, op: SyncOp, base: JsonObject?, localDeviceId: String) {
        val existing = database.categoryRuleDao().rowById(rowId)
        val local = MergeSideInput(
            payload = existing?.takeIf { it.deletedAt == null }?.let { toFieldMap(it) },
            updatedAt = existing?.updatedAt ?: 0L,
            deviceId = localDeviceId,
        )
        val outcome = merge.merge(SyncTable.CATEGORY_RULES, local, remoteSide(op), base)
        val decision = decide(local, existing?.localRevision ?: 0L, op, outcome)

        if (decision.payload == null) {
            if (existing != null) {
                database.categoryRuleDao().applyRemoteTombstone(
                    id = rowId,
                    // Not decision.updatedAt: that value is derived from the
                    // op's own updated_at, which for a delete-sourced op is
                    // the row's *last edit* time (softDelete never touches
                    // updated_at) -- using it here would backdate the
                    // tombstone. No cross-device deletion instant is ever
                    // transmitted (SyncOp carries none), so "now" is read the
                    // same way createdAt is for a brand-new row: when this
                    // device learned about it, not a fact about the row.
                    deletedAt = clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                )
            }
        } else {
            database.categoryRuleDao().upsert(
                categoryRuleFromFieldMap(
                    id = rowId,
                    payload = decision.payload,
                    createdAt = existing?.createdAt ?: clock.now().toEpochMilliseconds(),
                    updatedAt = decision.updatedAt,
                    localRevision = decision.localRevision,
                    remoteRevision = op.remoteRevision,
                    pendingOperation = decision.pendingOperation,
                ),
            )
        }
        recordShadowAndConflicts(SyncTable.CATEGORY_RULES, rowId, op, decision)
    }

    override suspend fun reconcile(snapshot: RemoteSnapshot, localDeviceId: String) {
        database.withTransaction {
            val rowsByTable = snapshot.rows.groupBy { it.table }
            // Parent tables first, same reasoning as apply(): a budget or
            // rule the snapshot is (re)creating must not land ahead of its
            // category.
            for (table in SyncTable.entries) {
                for (row in rowsByTable[table.tableName].orEmpty()) {
                    applyOne(table, row.rowId, row.toSyncOp(), localDeviceId)
                }
            }
            // Children before CATEGORIES (SyncTable.entries reversed): a
            // category hard-deleted before its budgets/rules were checked
            // would cascade-delete them ahead of this pass ever seeing
            // them, and their shadow/conflict rows would be left orphaned.
            for (table in SyncTable.entries.reversed()) {
                reconcileRowsMissingFromSnapshot(table, rowsByTable[table.tableName].orEmpty(), localDeviceId)
            }
        }
    }

    /**
     * The half of §7's algorithm [apply] has no equivalent of: a row this
     * device holds that [snapshot] no longer mentions at all. Absence means
     * one of two things, and [SyncShadowEntity] plus `local_revision` is
     * what tells them apart -- the same causal test §5.2's fast-forward
     * table uses, one horizon later.
     */
    private suspend fun reconcileRowsMissingFromSnapshot(table: SyncTable, presentRows: List<SnapshotRow>, localDeviceId: String) {
        val present = presentRows.mapTo(mutableSetOf()) { it.rowId }
        for (id in allIdsFor(table)) {
            if (id in present) continue
            val shadow = database.syncShadowDao().baseOf(table.tableName, id)
            // The row moved (or vanished) under this same transaction's own
            // earlier pass -- nothing left to reconcile.
            val localRevision = localRevisionFor(table, id) ?: continue

            if (shadow == null || localRevision > shadow.remoteRevision) {
                // No base at all -- remote has never seen this row, a
                // genuine local creation -- or a local edit newer than what
                // was last agreed, i.e. this device kept editing a row
                // remote deleted (and has since forgotten entirely). Either
                // way this is edit-over-delete (§5.3) one horizon later: the
                // row survives, dirty, to be pushed as if new. A stale base
                // no longer describes anything remote can still produce, so
                // it is forgotten rather than compared against again.
                if (shadow != null) database.syncShadowDao().forget(table.tableName, id)
            } else {
                // This device has nothing beyond what was already agreed,
                // and remote has forgotten the row entirely: it was deleted,
                // and the deletion is now out of retention. Hard delete, not
                // a tombstone -- there is nothing left anywhere to
                // reconcile a tombstone against.
                hardDeleteFor(table, id)
                database.syncShadowDao().forget(table.tableName, id)
                database.syncConflictDao().forgetRow(table.tableName, id)
            }
        }
    }

    private suspend fun allIdsFor(table: SyncTable): List<String> = when (table) {
        SyncTable.TRANSACTIONS -> database.transactionDao().allIds()
        SyncTable.CATEGORIES -> database.categoryDao().allIds()
        SyncTable.BUDGETS -> database.budgetDao().allIds()
        SyncTable.CATEGORY_RULES -> database.categoryRuleDao().allIds()
    }

    private suspend fun localRevisionFor(table: SyncTable, id: String): Long? = when (table) {
        SyncTable.TRANSACTIONS -> database.transactionDao().revisionOf(id)?.localRevision
        SyncTable.CATEGORIES -> database.categoryDao().revisionOf(id)?.localRevision
        SyncTable.BUDGETS -> database.budgetDao().revisionOf(id)?.localRevision
        SyncTable.CATEGORY_RULES -> database.categoryRuleDao().revisionOf(id)?.localRevision
    }

    private suspend fun hardDeleteFor(table: SyncTable, id: String) {
        when (table) {
            SyncTable.TRANSACTIONS -> database.transactionDao().hardDelete(id)
            SyncTable.CATEGORIES -> database.categoryDao().hardDelete(id)
            SyncTable.BUDGETS -> database.budgetDao().hardDelete(id)
            SyncTable.CATEGORY_RULES -> database.categoryRuleDao().hardDelete(id)
        }
    }

    private fun SnapshotRow.toSyncOp() = SyncOp(
        table = table,
        rowId = rowId,
        remoteRevision = remoteRevision,
        // A snapshot row is compaction's merged current state, not one
        // device's edit -- there is no single author to name (see
        // [SnapshotRow]'s doc). This is only ever consulted as a last-resort
        // deterministic tiebreak when [updatedAt] ties exactly (§5.5), so any
        // fixed value is safe; it is never compared for equality against a
        // real device id.
        deviceId = SNAPSHOT_DEVICE_ID,
        updatedAt = updatedAt,
        payload = payload,
    )

    override suspend fun recordPushed(table: SyncTable, rowId: String, remoteRevision: Long, payload: JsonObject?) {
        database.syncShadowDao().record(
            SyncShadowEntity(
                tableName = table.tableName,
                rowId = rowId,
                remoteRevision = remoteRevision,
                payload = payload?.toString(),
            ),
        )
    }

    private fun remoteSide(op: SyncOp) = MergeSideInput(op.payload, op.updatedAt, op.deviceId)

    private suspend fun recordShadowAndConflicts(table: SyncTable, rowId: String, op: SyncOp, decision: RemoteApplyDecision) {
        database.syncShadowDao().record(
            SyncShadowEntity(
                tableName = table.tableName,
                rowId = rowId,
                remoteRevision = op.remoteRevision,
                payload = decision.payload?.toString(),
            ),
        )
        for (conflict in decision.conflicts) {
            database.syncConflictDao().record(
                SyncConflictEntity(
                    id = UUID.randomUUID().toString(),
                    tableName = table.tableName,
                    rowId = rowId,
                    field = conflict.field,
                    resolvedAt = clock.now().toEpochMilliseconds(),
                    chosenValue = conflict.chosenValue.toString(),
                    discardedValue = conflict.discardedValue.toString(),
                    reason = conflict.reason,
                ),
            )
        }
    }
}

/**
 * What one row's bookkeeping columns become after a merge, computed once and
 * shared by the write branch (tombstone or upsert) and the shadow write --
 * they must never disagree about what "this row's new state" was.
 */
private data class RemoteApplyDecision(
    val payload: JsonObject?,
    val updatedAt: Long,
    val localRevision: Long,
    val pendingOperation: String?,
    val conflicts: List<ResolvedField>,
)

/**
 * The revision rebase this design needed and did not have (found while
 * building slice 5c; see docs/sync-design.md §4.3's list of bugs found the
 * same way). `local_revision` is a *per-device* counter -- device A's row
 * edited fifty times and device B's edited three both start at 1 and climb by
 * one per edit, with no shared scale between them. `dirtyRows`
 * (docs/sync-design.md §4.3, §5a) compares `local_revision` against
 * `sync_shadow.remote_revision` for the *same device*, which is safe -- but
 * applying a remote op overwrites that shadow with a number from the
 * *other* device's scale. A plain `local_revision + 1` after that write
 * would compare B's small counter against A's large one on every future
 * dirty check, and B's own genuinely new edits could permanently look
 * "already synced" the moment A's revision count happens to be higher.
 *
 * The fix is the same move a Lamport clock makes on receipt of a message:
 * rebase to `max(what I had, what I just learned)`, then add one only if
 * this device is contributing something the remote does not already have.
 * That keeps `local_revision` always at least as large as every revision
 * number this device has ever seen from anyone, so the comparison
 * `dirtyRows` relies on stays valid regardless of how far two devices'
 * counters had drifted apart before they met.
 */
private fun decide(local: MergeSideInput, existingLocalRevision: Long, op: SyncOp, outcome: MergeOutcome): RemoteApplyDecision {
    // If the merge outcome is exactly what the op already carries, remote
    // already has (or will have, via this same op) the true state -- a pure
    // fast-forward, nothing owed. Any other outcome means a field this
    // device won, or a delete the op's own payload does not reflect, is not
    // yet known on the other side and has to be pushed back.
    val localContributed = outcome.payload != op.payload
    return RemoteApplyDecision(
        payload = outcome.payload,
        updatedAt = maxOf(local.updatedAt, op.updatedAt),
        localRevision = maxOf(existingLocalRevision, op.remoteRevision) + if (localContributed) 1 else 0,
        pendingOperation = if (!localContributed) null else if (outcome.payload == null) "DELETE" else "UPSERT",
        conflicts = outcome.conflicts,
    )
}

/** See [RoomSyncApplier.toSyncOp]. */
private const val SNAPSHOT_DEVICE_ID = "sync-snapshot"
