package dev.charanjeev.bahi.core.sync.crypto

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SnapshotRow
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The "pure byte-transform" half of slice 9c (docs/sync-design.md §13): no
 * `SyncTransport` involved, ciphertext round-trips through [OpBatchCipher]
 * alone. The `snapshot()`-prefixed tests cover the widening slice 9f added --
 * see [OpBatchCipher]'s class doc.
 */
class OpBatchCipherTest {

    private val key = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), PassphraseKeyDerivation.newSalt())

    @Test
    fun `a batch round-trips through encrypt then decrypt unchanged`() {
        val batch = batch()

        val envelope = OpBatchCipher.encrypt(batch, key)
        val decrypted = OpBatchCipher.decrypt(envelope, key)

        assertThat(decrypted).isEqualTo(batch)
    }

    @Test
    fun `the envelope starts with the version byte and is not the plaintext`() {
        val envelope = OpBatchCipher.encrypt(batch(), key)

        assertThat(envelope[0]).isEqualTo(OpBatchCipher.ENVELOPE_VERSION)
        // "row-1" is a rowId in the plaintext payload -- if this shows up as
        // raw bytes in the envelope, nothing was actually encrypted.
        assertThat(String(envelope, Charsets.ISO_8859_1)).doesNotContain("row-1")
    }

    @Test
    fun `two encryptions of the same batch produce different envelopes`() {
        val batch = batch()

        val first = OpBatchCipher.encrypt(batch, key)
        val second = OpBatchCipher.encrypt(batch, key)

        // Same key, same plaintext, but a fresh nonce each time -- GCM's
        // security argument requires this, and it is also the property that
        // makes byte-for-byte comparison in this test suite meaningless, so
        // every other assertion here compares decrypted OpBatches instead.
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `decrypting with the wrong key fails loudly rather than returning garbage`() {
        val envelope = OpBatchCipher.encrypt(batch(), key)
        val wrongKey = PassphraseKeyDerivation.derive("a different passphrase entirely".toCharArray(), PassphraseKeyDerivation.newSalt())

        assertThrows(WrongPassphraseException::class.java) {
            OpBatchCipher.decrypt(envelope, wrongKey)
        }
    }

    @Test
    fun `a tampered envelope fails the same way as a wrong key`() {
        val envelope = OpBatchCipher.encrypt(batch(), key)
        envelope[envelope.lastIndex] = (envelope[envelope.lastIndex] + 1).toByte()

        assertThrows(WrongPassphraseException::class.java) {
            OpBatchCipher.decrypt(envelope, key)
        }
    }

    @Test
    fun `a future envelope version is rejected rather than misread`() {
        val envelope = OpBatchCipher.encrypt(batch(), key)
        envelope[0] = (OpBatchCipher.ENVELOPE_VERSION + 1).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            OpBatchCipher.decrypt(envelope, key)
        }
    }

    @Test
    fun `a snapshot round-trips through encryptSnapshot then decryptSnapshot unchanged`() {
        val snapshot = snapshot()

        val envelope = OpBatchCipher.encryptSnapshot(snapshot, key)
        val decrypted = OpBatchCipher.decryptSnapshot(envelope, key)

        assertThat(decrypted).isEqualTo(snapshot)
    }

    @Test
    fun `a snapshot envelope is not the plaintext either`() {
        val envelope = OpBatchCipher.encryptSnapshot(snapshot(), key)

        assertThat(envelope[0]).isEqualTo(OpBatchCipher.ENVELOPE_VERSION)
        assertThat(String(envelope, Charsets.ISO_8859_1)).doesNotContain("row-1")
    }

    @Test
    fun `decrypting a snapshot envelope with the wrong key fails loudly rather than returning garbage`() {
        val envelope = OpBatchCipher.encryptSnapshot(snapshot(), key)
        val wrongKey = PassphraseKeyDerivation.derive("a different passphrase entirely".toCharArray(), PassphraseKeyDerivation.newSalt())

        assertThrows(WrongPassphraseException::class.java) {
            OpBatchCipher.decryptSnapshot(envelope, wrongKey)
        }
    }

    private fun snapshot() = RemoteSnapshot(
        horizon = mapOf("device-a" to 5L),
        rows = listOf(
            SnapshotRow(
                table = SyncTable.TRANSACTIONS.tableName,
                rowId = "row-1",
                remoteRevision = 5,
                updatedAt = 1_000,
                payload = buildJsonObject { put("amount_minor", JsonPrimitive(-500)) },
            ),
        ),
    )

    private fun batch() = OpBatch(
        deviceId = "device-a",
        seq = 1,
        ops = listOf(
            SyncOp(
                table = SyncTable.TRANSACTIONS.tableName,
                rowId = "row-1",
                remoteRevision = 1,
                deviceId = "device-a",
                updatedAt = 1_000,
                payload = buildJsonObject { put("amount_minor", JsonPrimitive(-500)) },
            ),
        ),
    )
}
