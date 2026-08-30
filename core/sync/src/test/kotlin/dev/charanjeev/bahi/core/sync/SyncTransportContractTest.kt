package dev.charanjeev.bahi.core.sync

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.SyncOp
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What every `SyncTransport` must guarantee, independent of backend
 * (docs/sync-design.md §10.4). Subclassed by [InMemoryTransportTest] here and,
 * manually, against the Drive transport in M4b -- the real backend gets this
 * specification even though CI cannot execute it.
 *
 * Abstract rather than a fake-only test so the same guarantees are checked
 * wherever a new `SyncTransport` shows up, the same reason
 * `FieldPolicyCoverageTest` exists for the resolver's policy map.
 */
abstract class SyncTransportContractTest {

    abstract fun createTransport(): SyncTransport

    @Test
    fun `pull returns nothing from a transport nothing has been pushed to`() = runTest {
        assertThat(createTransport().pull(emptyMap())).isEmpty()
    }

    @Test
    fun `pull returns a pushed batch when the caller has no cursor for it`() = runTest {
        val transport = createTransport()
        val batch = opBatch(deviceId = "device-a", seq = 1)

        transport.push(batch)

        assertThat(transport.pull(emptyMap())).containsExactly(batch)
    }

    @Test
    fun `pull excludes a batch at or before its devices cursor`() = runTest {
        val transport = createTransport()
        transport.push(opBatch(deviceId = "device-a", seq = 1))
        transport.push(opBatch(deviceId = "device-a", seq = 2))

        assertThat(transport.pull(mapOf("device-a" to 1)).map { it.seq }).containsExactly(2L)
    }

    @Test
    fun `pull includes a batch exactly one past its devices cursor`() = runTest {
        val transport = createTransport()
        transport.push(opBatch(deviceId = "device-a", seq = 1))

        assertThat(transport.pull(mapOf("device-a" to 0))).hasSize(1)
    }

    /**
     * A cursor is per device (§8.3): a caller that has never seen device B
     * still gets every batch B pushed, whatever cursor it holds for A.
     */
    @Test
    fun `pull is unaffected by a cursor for a different device`() = runTest {
        val transport = createTransport()
        val batch = opBatch(deviceId = "device-a", seq = 1)
        transport.push(batch)

        assertThat(transport.pull(mapOf("device-b" to 99))).containsExactly(batch)
    }

    @Test
    fun `pull returns batches from every device that has pushed`() = runTest {
        val transport = createTransport()
        val fromA = opBatch(deviceId = "device-a", seq = 1)
        val fromB = opBatch(deviceId = "device-b", seq = 1)
        transport.push(fromA)
        transport.push(fromB)

        assertThat(transport.pull(emptyMap())).containsExactly(fromA, fromB)
    }

    /**
     * Files are named `<deviceId>/<seq>.json` and never rewritten (§8.3), so
     * one device's own batches come back in the order it produced them.
     */
    @Test
    fun `pull preserves one devices batches in the order they were pushed`() = runTest {
        val transport = createTransport()
        val first = opBatch(deviceId = "device-a", seq = 1)
        val second = opBatch(deviceId = "device-a", seq = 2)
        val third = opBatch(deviceId = "device-a", seq = 3)
        transport.push(first)
        transport.push(second)
        transport.push(third)

        assertThat(transport.pull(emptyMap()).map { it.seq }).containsExactly(1L, 2L, 3L).inOrder()
    }

    private fun opBatch(deviceId: String, seq: Long) = OpBatch(
        deviceId = deviceId,
        seq = seq,
        ops = listOf(
            SyncOp(
                table = "transactions",
                rowId = "row-$deviceId-$seq",
                remoteRevision = seq,
                deviceId = deviceId,
                updatedAt = 0L,
                payload = null,
            ),
        ),
    )
}
