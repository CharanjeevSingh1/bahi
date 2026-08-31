package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SnapshotRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `SyncTransport` behind the two-device test harness (§10.1), the
 * scripted scenarios and the property test -- everywhere except M4b's Drive
 * backend. A mutable list of [OpBatch] behind a mutex, modelling exactly what
 * §8.3's op log offers and nothing more: append-only storage, shared across
 * every device that holds a reference to the same instance, with no ordering
 * promise between devices.
 *
 * The mutex exists because a two-device test drives two `SyncEngine`s
 * concurrently over one instance of this class; a real op log needs no
 * equivalent, since each device only ever appends to its own directory of
 * files.
 */
class InMemoryTransport : SyncTransport {

    private val mutex = Mutex()
    private val batches = mutableListOf<OpBatch>()
    private var frozenSnapshot = RemoteSnapshot(horizon = emptyMap(), rows = emptyList())

    override suspend fun push(batch: OpBatch) {
        mutex.withLock { batches += batch }
    }

    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = mutex.withLock {
        batches.filter { it.seq > (after[it.deviceId] ?: 0L) }
    }

    override suspend fun snapshot(): RemoteSnapshot = mutex.withLock { frozenSnapshot }

    /**
     * Simulates one round of §8.3's compaction: folds every batch pushed so
     * far into a [RemoteSnapshot] -- the merged current state, tombstones
     * excluded, "newest op per row wins" exactly as
     * `RoomSyncApplier.apply`'s own batch fold does -- then deletes every
     * batch whose device has been fully folded in, matching "deletes op
     * files strictly older than the snapshot's watermark" (§8.3).
     *
     * Deliberately not part of [SyncTransport]: a real backend compacts on
     * its own schedule (a periodic job, M4b/slice 9), so nothing in
     * [SyncEngine] ever calls this -- it exists so a test can put a device
     * behind the horizon on purpose and exercise the reconciliation path
     * (§7) against something other than a hand-built [RemoteSnapshot].
     */
    suspend fun compact() = mutex.withLock {
        val newestPerRow = batches.flatMap { it.ops }
            .groupBy { it.table to it.rowId }
            .mapValues { (_, group) -> group.maxBy { it.remoteRevision } }
        val horizon = batches.groupBy { it.deviceId }.mapValues { (_, forDevice) -> forDevice.maxOf { it.seq } }

        frozenSnapshot = RemoteSnapshot(
            horizon = horizon,
            rows = newestPerRow.values.mapNotNull { op ->
                op.payload?.let { payload -> SnapshotRow(op.table, op.rowId, op.remoteRevision, op.updatedAt, payload) }
            },
        )
        // Every batch just folded into the snapshot has seq <= its device's
        // own entry in horizon by construction (horizon is that device's max
        // seq among exactly these batches) -- so this clears everything the
        // fold above just accounted for, and nothing else.
        batches.removeAll { (horizon[it.deviceId] ?: 0L) >= it.seq }
    }
}
