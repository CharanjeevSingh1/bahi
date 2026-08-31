package dev.charanjeev.bahi.core.sync

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/** Runs [SyncTransportContractTest] against the fake -- everywhere but M4b. */
class InMemoryTransportTest : SyncTransportContractTest() {
    override fun createTransport(): SyncTransport = InMemoryTransport()
}

/**
 * [InMemoryTransport.compact] itself: not part of [SyncTransport]'s contract
 * (see that method's doc), so its guarantees live here rather than in
 * [SyncTransportContractTest].
 */
class InMemoryTransportCompactionTest {

    @Test
    fun `compact folds every pushed batch into the horizon`() = runTest {
        val transport = InMemoryTransport()
        transport.push(opBatch("device-a", seq = 1))
        transport.push(opBatch("device-a", seq = 2))
        transport.push(opBatch("device-b", seq = 1))

        transport.compact()

        assertThat(transport.snapshot().horizon).containsExactly("device-a", 2L, "device-b", 1L)
    }

    @Test
    fun `compact keeps only the newest op per row, tombstones excluded`() = runTest {
        val transport = InMemoryTransport()
        transport.push(opBatch("device-a", seq = 1, ops = listOf(op(rowId = "t1", remoteRevision = 1, amountMinor = -100))))
        transport.push(opBatch("device-a", seq = 2, ops = listOf(op(rowId = "t1", remoteRevision = 2, amountMinor = -200))))
        transport.push(opBatch("device-a", seq = 3, ops = listOf(op(rowId = "t2", remoteRevision = 1, payload = null))))

        transport.compact()
        val snapshot = transport.snapshot()

        assertThat(snapshot.rows.map { it.rowId }).containsExactly("t1")
        assertThat(snapshot.rows.single().payload).isEqualTo(payload(-200))
    }

    @Test
    fun `pull after compaction no longer returns the compacted batches`() = runTest {
        val transport = InMemoryTransport()
        transport.push(opBatch("device-a", seq = 1))
        transport.push(opBatch("device-a", seq = 2))

        transport.compact()

        // A device that had never pulled anything from device-a would
        // otherwise expect to see both batches; compaction is exactly the
        // condition under which it must not (§7) -- it has to reconcile
        // against the snapshot instead.
        assertThat(transport.pull(emptyMap())).isEmpty()
    }

    @Test
    fun `a batch pushed after compaction is still pulled normally`() = runTest {
        val transport = InMemoryTransport()
        transport.push(opBatch("device-a", seq = 1))
        transport.compact()

        transport.push(opBatch("device-a", seq = 2))

        assertThat(transport.pull(mapOf("device-a" to 1)).map { it.seq }).containsExactly(2L)
    }

    private fun opBatch(deviceId: String, seq: Long, ops: List<SyncOp> = listOf(op(rowId = "row-$deviceId-$seq", remoteRevision = seq))) =
        OpBatch(deviceId = deviceId, seq = seq, ops = ops)

    private fun op(rowId: String, remoteRevision: Long, amountMinor: Long = -100, payload: kotlinx.serialization.json.JsonObject? = payload(amountMinor)) = SyncOp(
        table = SyncTable.TRANSACTIONS.tableName,
        rowId = rowId,
        remoteRevision = remoteRevision,
        deviceId = "author",
        updatedAt = 1_000,
        payload = payload,
    )

    private fun payload(amountMinor: Long) = buildJsonObject { put("amount_minor", JsonPrimitive(amountMinor)) }
}
