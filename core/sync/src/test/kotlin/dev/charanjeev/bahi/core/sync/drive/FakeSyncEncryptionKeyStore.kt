package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.flowOf

/**
 * A real AES-256 [SecretKey] a test can actually encrypt and decrypt
 * against -- unlike `:feature:settings`' `FakeSyncEncryptionKeyStore`, whose
 * [cachedKey] is always null because nothing that uses it ever asserts on the
 * key itself, only on [pairingCode]/[isSetUp]. [DriveTransportTest] needs the
 * opposite: a working key to round-trip [dev.charanjeev.bahi.core.sync.
 * crypto.OpBatchCipher] through, and no UI-facing state at all.
 */
class FakeSyncEncryptionKeyStore(private var key: SecretKey? = generateKey()) : SyncEncryptionKeyStore {

    override val isSetUp = flowOf(key != null)

    fun setKey(key: SecretKey?) {
        this.key = key
    }

    override suspend fun setUp(passphrase: CharArray): String = error("not exercised by DriveTransport")
    override suspend fun pair(passphrase: CharArray, salt: ByteArray): String = error("not exercised by DriveTransport")
    override suspend fun cachedKey(): SecretKey? = key
    override suspend fun pairingCode(): String? = error("not exercised by DriveTransport")

    companion object {
        fun generateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
