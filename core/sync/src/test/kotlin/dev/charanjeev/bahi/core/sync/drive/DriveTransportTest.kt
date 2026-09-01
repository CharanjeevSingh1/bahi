package dev.charanjeev.bahi.core.sync.drive

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.SyncTransportContractTest
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Test

/**
 * Runs the full [SyncTransportContractTest] suite against [DriveTransport]
 * wired to [InMemoryFakeDrive] -- proves this class's own push/pull/snapshot
 * logic (cursoring, encryption, appProperties tagging) satisfies the same
 * guarantees `InMemoryTransportTest` checks, entirely offline. What this
 * cannot check -- eventual consistency, quota, real token lifecycle -- is
 * exactly what `DriveTransportContractTest` (`core/sync/src/driveTest`,
 * docs/sync-design.md §10.5) exists to check separately, against a real
 * account.
 */
class DriveTransportTest : SyncTransportContractTest() {

    override fun createTransport(): SyncTransport = transport()

    private fun transport(
        drive: InMemoryFakeDrive = InMemoryFakeDrive(),
        keyStore: FakeSyncEncryptionKeyStore = FakeSyncEncryptionKeyStore(),
        auth: FakeDriveAuthorization = FakeDriveAuthorization(),
    ) = DriveTransport(auth, keyStore, FakeCallFactory(drive::handle), Dispatchers.Unconfined)

    private fun batch(deviceId: String = "device-a", seq: Long = 1) = OpBatch(
        deviceId = deviceId,
        seq = seq,
        ops = listOf(SyncOp(table = "transactions", rowId = "row-1", remoteRevision = seq, deviceId = deviceId, updatedAt = 0L, payload = null)),
    )

    @Test
    fun `push refuses when no encryption key is set up`() = runTest {
        val failure = runCatching { transport(keyStore = FakeSyncEncryptionKeyStore(null)).push(batch()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DriveTransportException::class.java)
    }

    @Test
    fun `pull refuses when no encryption key is set up`() = runTest {
        val failure = runCatching { transport(keyStore = FakeSyncEncryptionKeyStore(null)).pull(emptyMap()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(DriveTransportException::class.java)
    }

    @Test
    fun `push uploads ciphertext, not the plaintext batch`() = runTest {
        val callFactory = FakeCallFactory(InMemoryFakeDrive()::handle)
        val theBatch = batch()

        DriveTransport(FakeDriveAuthorization(), FakeSyncEncryptionKeyStore(), callFactory, Dispatchers.Unconfined).push(theBatch)

        val uploadedBody = Buffer().also { callFactory.requests.first { r -> r.method == "POST" }.body!!.writeTo(it) }.readUtf8()
        assertThat(uploadedBody).doesNotContain(theBatch.ops.first().rowId)
    }

    @Test
    fun `two devices' pushed batches decrypt correctly when a third pulls both`() = runTest {
        val drive = InMemoryFakeDrive()
        val sharedKeyStore = FakeSyncEncryptionKeyStore()
        transport(drive = drive, keyStore = sharedKeyStore).push(batch(deviceId = "device-a", seq = 1))
        transport(drive = drive, keyStore = sharedKeyStore).push(batch(deviceId = "device-b", seq = 1))

        val pulled = transport(drive = drive, keyStore = sharedKeyStore).pull(emptyMap())

        assertThat(pulled.map { it.deviceId }).containsExactly("device-a", "device-b")
    }

    @Test
    fun `a NeedsReauthorization outcome surfaces as a non-retryable failure`() = runTest {
        val failure = runCatching {
            transport(auth = FakeDriveAuthorization(AuthorizationOutcome.NeedsReauthorization)).pull(emptyMap())
        }.exceptionOrNull() as? DriveTransportException

        assertThat(failure?.retryable).isFalse()
    }

    @Test
    fun `a transient Failed outcome carries its retryable flag through`() = runTest {
        val failure = runCatching {
            transport(auth = FakeDriveAuthorization(AuthorizationOutcome.Failed("network blip", retryable = true))).pull(emptyMap())
        }.exceptionOrNull() as? DriveTransportException

        assertThat(failure?.retryable).isTrue()
    }

    @Test
    fun `snapshot is always empty -- no writer of one exists until compaction lands`() = runTest {
        assertThat(transport().snapshot().horizon).isEmpty()
    }

    @Test
    fun `readPublishedSalt is null before any device has published one`() = runTest {
        assertThat(transport().readPublishedSalt()).isNull()
    }

    @Test
    fun `publishSalt then readPublishedSalt round-trips the exact bytes, unencrypted`() = runTest {
        val drive = InMemoryFakeDrive()
        val salt = byteArrayOf(1, 2, 3, 4)

        transport(drive = drive).publishSalt(salt)

        assertThat(transport(drive = drive).readPublishedSalt()).isEqualTo(salt)
    }
}
