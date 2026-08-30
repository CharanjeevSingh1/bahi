package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.OpBatch
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

    override suspend fun push(batch: OpBatch) {
        mutex.withLock { batches += batch }
    }

    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = mutex.withLock {
        batches.filter { it.seq > (after[it.deviceId] ?: 0L) }
    }
}
