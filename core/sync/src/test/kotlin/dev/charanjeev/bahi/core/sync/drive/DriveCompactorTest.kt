package dev.charanjeev.bahi.core.sync.drive

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.TOMBSTONE_HORIZON_DAYS
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * D13's election and compaction (docs/sync-design.md §8.3, D13, slice 9f),
 * against [InMemoryFakeDrive] -- entirely offline, entirely in CI, the same
 * shape [DriveTransportTest] already proves the transport itself against.
 * [electionWaitMillis] is always 0 here: a real election waits 30 real
 * seconds (D13), which no unit test can afford to actually do, so every
 * [compactor] below overrides it -- the wait itself is not what these tests
 * are checking, the *outcome* of the claim-then-verify dance is.
 */
class DriveCompactorTest {

    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = { now }

    private fun compactor(drive: InMemoryFakeDrive, deviceId: String, keyStore: FakeSyncEncryptionKeyStore = sharedKeyStore) =
        DriveCompactor(FakeDriveAuthorization(), keyStore, FakeCallFactory(drive::handle), deviceId, electionWaitMillis = 0L, clock = clock)

    private fun rawApi(drive: InMemoryFakeDrive) = DriveApi(FakeCallFactory(drive::handle)) { "fake-token" }

    private val sharedKeyStore = FakeSyncEncryptionKeyStore()

    // --- election -----------------------------------------------------

    @Test
    fun `electIfNeeded claims ownership when no owner exists`() = runTest {
        val drive = InMemoryFakeDrive(clock)

        assertThat(compactor(drive, "device-a").electIfNeeded()).isTrue()
    }

    @Test
    fun `electIfNeeded defers to an existing owner naming another device`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        compactor(drive, "device-a").electIfNeeded()

        assertThat(compactor(drive, "device-b").electIfNeeded()).isFalse()
    }

    @Test
    fun `electIfNeeded is stable across repeated calls once a device is elected`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()

        assertThat(a.electIfNeeded()).isTrue()
        assertThat(rawApi(drive).list(KIND, KIND_OWNER)).hasSize(1)
    }

    @Test
    fun `a simultaneous double-claim resolves to the lexicographically lower deviceId and deletes the loser`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        // Simulates two devices both finding no owner file at once and both
        // claiming, before either has re-read to notice the other -- the
        // race D13 accepts can still happen once, at first election.
        val api = rawApi(drive)
        api.create(name = "owner.json", appProperties = mapOf(KIND to KIND_OWNER, "deviceId" to "z-device", "claimedAt" to "1000"), content = ByteArray(0))
        api.create(name = "owner.json", appProperties = mapOf(KIND to KIND_OWNER, "deviceId" to "a-device", "claimedAt" to "2000"), content = ByteArray(0))

        // A third device, or either racer, computes the same winner independently.
        assertThat(compactor(drive, "m-device").electIfNeeded()).isFalse()

        val remaining = api.list(KIND, KIND_OWNER)
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().appProperties["deviceId"]).isEqualTo("a-device")
    }

    // --- manual takeover ------------------------------------------------

    @Test
    fun `takeOver replaces the current owner regardless of who it names`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        val b = compactor(drive, "device-b")
        a.electIfNeeded()

        assertThat(b.takeOver()).isTrue()
        assertThat(a.electIfNeeded()).isFalse()
    }

    @Test
    fun `isStale is false when no device has ever claimed ownership`() = runTest {
        val drive = InMemoryFakeDrive(clock)

        assertThat(compactor(drive, "device-a").isStale()).isFalse()
    }

    @Test
    fun `isStale is false for a freshly elected owner with no compaction backlog`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        compactor(drive, "device-a").electIfNeeded()

        assertThat(compactor(drive, "device-b").isStale()).isFalse()
    }

    @Test
    fun `isStale is false for an old claim with no snapshot when the op backlog never crossed the threshold`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        push(drive, "device-a", 1)

        now = now.plus(Duration.ofDays(TOMBSTONE_HORIZON_DAYS + 1L))

        assertThat(compactor(drive, "device-b").isStale()).isFalse()
    }

    @Test
    fun `isStale is true for an old claim with no snapshot once the op backlog crosses the threshold`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        compactor(drive, "device-a").electIfNeeded()
        repeat(51) { i -> push(drive, "device-a", i + 1L) }

        now = now.plus(Duration.ofDays(TOMBSTONE_HORIZON_DAYS + 1L))

        assertThat(compactor(drive, "device-b").isStale()).isTrue()
    }

    @Test
    fun `isStale tracks the newest snapshots age once one exists, not the owner claim`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(51) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))
        a.compact()

        assertThat(compactor(drive, "device-b").isStale()).isFalse()

        now = now.plus(Duration.ofDays(TOMBSTONE_HORIZON_DAYS + 1L))

        assertThat(compactor(drive, "device-b").isStale()).isTrue()
    }

    // --- compaction -----------------------------------------------------

    @Test
    fun `compact does nothing when this device is not the elected owner`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        compactor(drive, "device-a").electIfNeeded()
        repeat(60) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))

        compactor(drive, "device-b").compact()

        assertThat(rawApi(drive).list(KIND, KIND_SNAPSHOT)).isEmpty()
        assertThat(rawApi(drive).list(KIND, KIND_OPS)).hasSize(60)
    }

    @Test
    fun `compact does nothing below the op-file threshold, even once aged past the grace period`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(10) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))

        a.compact()

        assertThat(rawApi(drive).list(KIND, KIND_SNAPSHOT)).isEmpty()
        assertThat(rawApi(drive).list(KIND, KIND_OPS)).hasSize(10)
    }

    @Test
    fun `compact does nothing above the threshold when every op file is still within the grace period`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(60) { i -> push(drive, "device-a", i + 1L) }

        a.compact()

        assertThat(rawApi(drive).list(KIND, KIND_SNAPSHOT)).isEmpty()
        assertThat(rawApi(drive).list(KIND, KIND_OPS)).hasSize(60)
    }

    @Test
    fun `compact folds op files older than the grace period into one snapshot and deletes exactly those files`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(60) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))

        a.compact()

        val snapshots = rawApi(drive).list(KIND, KIND_SNAPSHOT)
        assertThat(snapshots).hasSize(1)
        assertThat(rawApi(drive).list(KIND, KIND_OPS)).isEmpty()

        val snapshot = rawApi(drive).latestSnapshot(sharedKeyStore.cachedKey()!!)
        assertThat(snapshot!!.horizon).containsExactly("device-a", 60L)
        assertThat(snapshot.rows.map { it.rowId }).containsExactlyElementsIn((1..60).map { "row-device-a-$it" })
    }

    @Test
    fun `compact leaves op files still inside the grace period untouched`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(60) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))
        repeat(5) { i -> push(drive, "device-a", 60L + i + 1) }

        a.compact()

        assertThat(rawApi(drive).list(KIND, KIND_OPS)).hasSize(5)
    }

    @Test
    fun `a second compaction round merges onto the first rather than starting over`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        repeat(60) { i -> push(drive, "device-a", i + 1L) }
        now = now.plus(Duration.ofHours(2))
        a.compact()

        repeat(60) { i -> push(drive, "device-a", 60L + i + 1) }
        now = now.plus(Duration.ofHours(2))
        a.compact()

        val snapshots = rawApi(drive).list(KIND, KIND_SNAPSHOT)
        assertThat(snapshots).hasSize(2)
        val key = sharedKeyStore.cachedKey()!!
        val latest = rawApi(drive).latestSnapshot(key)!!
        assertThat(latest.horizon).containsExactly("device-a", 120L)
        // Rows from the first round survive into the second, alongside the new ones.
        assertThat(latest.rows.map { it.rowId }).containsExactlyElementsIn((1..120).map { "row-device-a-$it" })
    }

    @Test
    fun `a tombstone folded during compaction removes the row from the snapshot`() = runTest {
        val drive = InMemoryFakeDrive(clock)
        val a = compactor(drive, "device-a")
        a.electIfNeeded()
        val transport = DriveTransport(FakeDriveAuthorization(), sharedKeyStore, FakeCallFactory(drive::handle), Dispatchers.Unconfined)
        transport.push(
            OpBatch(
                deviceId = "device-a",
                seq = 1,
                ops = listOf(SyncOp(table = "transactions", rowId = "row-1", remoteRevision = 1, deviceId = "device-a", updatedAt = 0L, payload = buildJsonObject { put("amount_minor", JsonPrimitive(-100)) })),
            ),
        )
        transport.push(
            OpBatch(deviceId = "device-a", seq = 2, ops = listOf(SyncOp(table = "transactions", rowId = "row-1", remoteRevision = 2, deviceId = "device-a", updatedAt = 0L, payload = null))),
        )
        repeat(58) { i -> push(drive, "device-a", i + 3L) }
        now = now.plus(Duration.ofHours(2))

        a.compact()

        val snapshot = rawApi(drive).latestSnapshot(sharedKeyStore.cachedKey()!!)!!
        assertThat(snapshot.rows.map { it.rowId }).doesNotContain("row-1")
    }

    private suspend fun push(drive: InMemoryFakeDrive, deviceId: String, seq: Long) {
        val transport = DriveTransport(FakeDriveAuthorization(), sharedKeyStore, FakeCallFactory(drive::handle), Dispatchers.Unconfined)
        transport.push(
            OpBatch(
                deviceId = deviceId,
                seq = seq,
                ops = listOf(
                    SyncOp(
                        table = "transactions",
                        rowId = "row-$deviceId-$seq",
                        remoteRevision = seq,
                        deviceId = deviceId,
                        updatedAt = 0L,
                        payload = buildJsonObject { put("amount_minor", JsonPrimitive(-seq)) },
                    ),
                ),
            ),
        )
    }
}
