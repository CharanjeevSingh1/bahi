package dev.charanjeev.bahi.core.sync

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.model.OP_FORMAT_VERSION
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * The orchestration slice 5c adds -- pull, push, cursor and push-sequence
 * bookkeeping -- against a real [InMemoryTransport] and a recording
 * [FakeSyncApplier]. The merge decision itself is out of scope here: see
 * `ConflictResolverTest` (the policy) and `SyncApplierTest`, `:core:data`
 * (the transactional write it drives).
 */
class SyncEngineTest {

    private fun payload(value: Long = 1) = buildJsonObject { put("v", JsonPrimitive(value)) }

    @Test
    fun `push does nothing when no repository has a dirty row`() = runTest {
        val transport = InMemoryTransport()
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        assertThat(transport.pull(emptyMap())).isEmpty()
    }

    @Test
    fun `push batches dirty rows from every table into one op batch`() = runTest {
        val transport = InMemoryTransport()
        val transactions = FakeTransactionRepository(dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = payload())))
        val categories = FakeCategoryRepository(dirty = listOf(DirtyRow("c1", localRevision = 2, updatedAt = 200, payload = payload())))
        val budgets = FakeBudgetRepository(dirty = listOf(DirtyRow("b1", localRevision = 3, updatedAt = 300, payload = payload())))
        val rules = FakeCategoryRuleRepository(dirty = listOf(DirtyRow("r1", localRevision = 4, updatedAt = 400, payload = payload())))
        val engine = SyncEngine(transport, FakeSyncApplier(), FakeTombstoneReaper(), transactions, categories, budgets, rules, deviceId = "device-a")

        engine.sync()

        val pushed = transport.pull(emptyMap()).single()
        assertThat(pushed.deviceId).isEqualTo("device-a")
        assertThat(pushed.ops.map { it.table to it.rowId }).containsExactly(
            "transactions" to "t1",
            "categories" to "c1",
            "budgets" to "b1",
            "category_rules" to "r1",
        )
        // Each op's remoteRevision is this device's own local_revision for
        // that row -- there is no server to assign one (docs/sync-design.md
        // §8.3, D2).
        assertThat(pushed.ops.first { it.rowId == "t1" }.remoteRevision).isEqualTo(1)
    }

    @Test
    fun `a successful push acknowledges exactly the rows it sent`() = runTest {
        val transport = InMemoryTransport()
        val transactions = FakeTransactionRepository(
            dirty = listOf(DirtyRow("t1", localRevision = 5, updatedAt = 100, payload = payload())),
        )
        val engine = SyncEngine(
            transport, FakeSyncApplier(), FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        assertThat(transactions.markSyncedCalls).containsExactly(Triple("t1", 5L, 5L))
    }

    /**
     * The bug found while building slice 6's two-device harness: `dirtyRows`
     * judges a row dirty against `sync_shadow.remote_revision`, not the
     * `remote_revision` *column* `markSynced` updates on the row itself.
     * Acknowledging a push without also writing the shadow leaves that row
     * permanently dirty -- every device that has ever pushed anything would
     * re-push it forever, which is exactly what the two-device harness's
     * simplest possible scenario (one edit, one sync) hit before this test
     * and SyncEngine.push's fix existed.
     */
    @Test
    fun `a successful push writes the shadow so the row is not pushed forever`() = runTest {
        val transport = InMemoryTransport()
        val applier = FakeSyncApplier()
        val transactions = FakeTransactionRepository(
            dirty = listOf(DirtyRow("t1", localRevision = 5, updatedAt = 100, payload = payload(value = 42))),
        )
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        assertThat(applier.recordedPushes).containsExactly(
            PushedShadow(SyncTable.TRANSACTIONS, "t1", remoteRevision = 5L, payload = payload(value = 42)),
        )
    }

    @Test
    fun `a push that fails its guard does not write a shadow for the stale revision`() = runTest {
        val transport = InMemoryTransport()
        val applier = FakeSyncApplier()
        val transactions = FakeTransactionRepository(
            dirty = listOf(DirtyRow("t1", localRevision = 5, updatedAt = 100, payload = payload())),
            markSyncedResult = false, // the row moved under the push (§4.3's guard)
        )
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        // Recording it anyway would claim this device and the remote agree
        // on a revision the row has already moved past.
        assertThat(applier.recordedPushes).isEmpty()
    }

    @Test
    fun `this device's own push is never pulled back on the next cycle`() = runTest {
        val transport = InMemoryTransport()
        val transactions = FakeTransactionRepository(
            dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = payload())),
        )
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()
        applier.calls.clear()
        engine.sync() // the fake's dirtyRows never empties, so this pushes t1 again -- what
        // this test checks is the *pull* half: it must not hand the applier its own batch back.

        assertThat(applier.calls).isEmpty()
    }

    @Test
    fun `pull hands every readable op from every pulled batch to the applier in one call`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1))))
        transport.push(OpBatch("device-b", seq = 2, ops = listOf(op(rowId = "y", remoteRevision = 2))))
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        assertThat(applier.calls).hasSize(1)
        val (ops, localDeviceId) = applier.calls.single()
        assertThat(ops.map { it.rowId }).containsExactly("x", "y")
        assertThat(localDeviceId).isEqualTo("device-a")
    }

    @Test
    fun `a batch from a future format version is skipped but still advances the cursor`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1)), version = OP_FORMAT_VERSION + 1))
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()
        // No new batches from device-b, so a second sync must not re-hand
        // the unreadable one to the applier -- the cursor already moved past it.
        engine.sync()

        assertThat(applier.calls.flatMap { it.first }).isEmpty()
    }

    /** §7, D8: reaping runs once per cycle rather than waiting on a periodic worker slice 9 has not built yet. */
    @Test
    fun `sync reaps tombstones and acknowledged conflicts past the horizon every cycle`() = runTest {
        val reaper = FakeTombstoneReaper()
        val engine = SyncEngine(
            InMemoryTransport(), FakeSyncApplier(), reaper,
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()
        engine.sync()

        assertThat(reaper.reapCount).isEqualTo(2)
    }

    /**
     * §7: once a peer's history has been compacted past what this device has
     * pulled, an incremental [SyncTransport.pull] would silently miss
     * whatever was dropped -- reconciling against the snapshot instead is
     * the only safe option, exactly what a fresh device does on its first
     * sync (§7's "the reconciliation path is needed anyway for a new
     * device").
     */
    @Test
    fun `a device behind the compacted horizon reconciles instead of pulling directly`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1))))
        transport.push(OpBatch("device-b", seq = 2, ops = listOf(op(rowId = "y", remoteRevision = 2))))
        transport.compact() // device-a has pulled neither batch -- its cursor for device-b is now behind the horizon.
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )

        engine.sync()

        assertThat(applier.reconcileCalls).hasSize(1)
        assertThat(applier.reconcileCalls.single().first.rows.map { it.rowId }).containsExactly("x", "y")
        // The compacted batches are gone from the transport (InMemoryTransportCompactionTest
        // covers that directly) -- apply() must not have been handed an empty pull as if it were meaningful.
        assertThat(applier.calls).isEmpty()
    }

    /** The other half: once reconciled, this device must not reconcile the same horizon again on every future cycle. */
    @Test
    fun `reconciling advances the cursor so the next sync pulls normally instead of reconciling again`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1))))
        transport.compact()
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
        )
        engine.sync() // reconciles, cursor for device-b advances to the horizon (seq 1)

        transport.push(OpBatch("device-b", seq = 2, ops = listOf(op(rowId = "z", remoteRevision = 1))))
        engine.sync()

        assertThat(applier.reconcileCalls).hasSize(1)
        assertThat(applier.calls.flatMap { it.first }.map { it.rowId }).containsExactly("z")
    }

    /**
     * `SyncRunner` (slice 9g's cursor-persistence fix) seeds a fresh engine
     * from `UserPreferencesDataSource.syncCursor` instead of an empty map --
     * this is the engine-level half of that: a cursor handed in through the
     * constructor has to behave exactly like one built up in memory over
     * prior `sync()` calls, or persisting it would be pointless.
     */
    @Test
    fun `a cursor seeded through the constructor is honoured on the first pull`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1))))
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
            initialCursor = mapOf("device-b" to 1L), // as if a prior, now-dead process already pulled seq 1
        )

        engine.sync()

        assertThat(applier.calls).isEmpty()
        assertThat(engine.cursorSnapshot).containsEntry("device-b", 1L)
    }

    /**
     * §7: a persisted cursor is exactly as capable of being behind the
     * horizon as one built up in memory -- the engine has no way to tell the
     * two apart, so this is a property of `reconcileIfBehindHorizon`, not
     * something a caller needs to special-case for a cursor it read back
     * from `UserPreferencesDataSource` rather than accumulated itself.
     */
    @Test
    fun `a cursor seeded through the constructor still reconciles if it is behind the horizon`() = runTest {
        val transport = InMemoryTransport()
        transport.push(OpBatch("device-b", seq = 1, ops = listOf(op(rowId = "x", remoteRevision = 1))))
        transport.push(OpBatch("device-b", seq = 2, ops = listOf(op(rowId = "y", remoteRevision = 2))))
        transport.compact()
        val applier = FakeSyncApplier()
        val engine = SyncEngine(
            transport, applier, FakeTombstoneReaper(),
            FakeTransactionRepository(), FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
            initialCursor = mapOf("device-b" to 1L), // persisted before device-b's batch 2 was folded into the snapshot
        )

        engine.sync()

        assertThat(applier.reconcileCalls).hasSize(1)
        assertThat(applier.reconcileCalls.single().first.rows.map { it.rowId }).containsExactly("x", "y")
    }

    /**
     * `SyncRunner` (slice 9g's push-sequence fix) seeds a fresh engine from
     * `UserPreferencesDataSource.pushSeq` -- rebased against the cursor, see
     * `SyncRunnerTest` -- instead of always starting at 1. This is the
     * engine-level half: a seed handed in through the constructor has to
     * produce the next number, not the first one, or persisting it would be
     * pointless.
     */
    @Test
    fun `a push sequence seeded through the constructor numbers the next batch, not the first one`() = runTest {
        val transport = InMemoryTransport()
        val transactions = FakeTransactionRepository(dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = payload())))
        val engine = SyncEngine(
            transport, FakeSyncApplier(), FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
            initialPushSeq = 4L, // as if a prior, now-dead process already pushed seq 1-4
        )

        engine.sync()

        assertThat(transport.pull(emptyMap()).single().seq).isEqualTo(5L)
    }

    /**
     * The property `persistPushSeq` exists for (`SyncEngine`'s own doc):
     * reusing a push sequence number is silent data loss for whichever peer
     * already pulled past it, unlike re-pulling a batch this device already
     * applied, which the revision watermark just no-ops. Persisting the
     * reserved number has to happen before the risky network call, not
     * after, or the crash window it closes reopens -- this proves the
     * ordering directly rather than trusting that a passing happy-path test
     * implies it.
     */
    @Test
    fun `the reserved push sequence is persisted before the transport call, even when that call then fails`() = runTest {
        val persisted = mutableListOf<Long>()
        val transactions = FakeTransactionRepository(dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = payload())))
        val engine = SyncEngine(
            AlwaysFailsToPushTransport(), FakeSyncApplier(), FakeTombstoneReaper(),
            transactions, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
            deviceId = "device-a",
            persistPushSeq = { persisted += it },
        )

        runCatching { engine.sync() }

        assertThat(persisted).containsExactly(1L)
    }

    private fun op(rowId: String, remoteRevision: Long) = SyncOp(
        table = SyncTable.TRANSACTIONS.tableName,
        rowId = rowId,
        remoteRevision = remoteRevision,
        deviceId = "device-b",
        updatedAt = 1_000,
        payload = payload(),
    )
}

/** A transport that always fails the risky call [SyncEngine.persistPushSeq] exists to run ahead of. */
private class AlwaysFailsToPushTransport : SyncTransport {
    override suspend fun push(batch: OpBatch): Unit = error("push always fails")
    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = emptyList()
    override suspend fun snapshot(): RemoteSnapshot = RemoteSnapshot(horizon = emptyMap(), rows = emptyList())
}
