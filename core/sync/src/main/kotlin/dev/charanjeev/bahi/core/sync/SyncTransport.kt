package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot

/**
 * What the engine needs from a backend, and nothing about what the backend
 * actually is (docs/sync-design.md §8.3, §9). [InMemoryTransport] is what the
 * app actually runs against; `DriveTransport` (`core/sync/drive/`, slice 9e)
 * is a second, real implementation, proven against the same contract
 * (`SyncTransportContractTest`) but not yet the one Hilt binds -- see
 * [DisabledSyncTransport]'s doc for why that's deliberate. The engine (slice
 * 5c) is built and tested entirely against this interface so that swap is
 * additive.
 *
 * Modelled on the op-log shape §8.3 settled on for Drive: each device appends
 * immutable batches at an ever-increasing per-device [OpBatch.seq], and a pull
 * asks for everything after a per-device cursor rather than a single global
 * one, because "everything after cursor X" is not expressible as one number
 * across independently-appending writers. There is deliberately no ordering
 * guarantee *between* devices' batches in the result -- only real Drive
 * listing semantics could promise one, and the engine does not need it: §5's
 * merge is commutative per row.
 */
interface SyncTransport {

    /** Appends one immutable batch. Never modifies or replaces an existing one. */
    suspend fun push(batch: OpBatch)

    /**
     * Every batch not yet seen, i.e. every batch from each device whose `seq`
     * exceeds that device's entry in [after]. A device absent from [after] has
     * pushed nothing this caller has seen, so all of its batches come back --
     * the same case a fresh install or a restored backup hits on its very
     * first pull.
     */
    suspend fun pull(after: Map<String, Long>): List<OpBatch>

    /**
     * The current merged state, and the per-device seq it already accounts
     * for (docs/sync-design.md §7, §8.3). A transport that has never
     * compacted anything answers with an empty [RemoteSnapshot.horizon] --
     * there is nothing an incremental [pull] could miss, so nothing here is
     * ever behind it. Triggering compaction itself is not part of this
     * interface: on Drive it is a periodic maintenance job (M4b, slice 9)
     * writing a new snapshot file, not something the engine asks for -- this
     * method only ever reads whatever the most recent one says.
     */
    suspend fun snapshot(): RemoteSnapshot
}
