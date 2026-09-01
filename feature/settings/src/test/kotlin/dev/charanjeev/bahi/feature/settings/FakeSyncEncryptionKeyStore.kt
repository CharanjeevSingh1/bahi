package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.crypto.PairingCode
import dev.charanjeev.bahi.core.sync.crypto.PassphraseKeyDerivation
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Not a real key store: the "key" it caches is just the pairing code string
 * itself, which is enough to test [PassphraseViewModel]'s orchestration
 * without a real `AndroidKeyStore` -- [cachedKey] is never asserted on by any
 * test that uses this fake, only [pairingCode] and [isSetUp].
 */
class FakeSyncEncryptionKeyStore(initialPairingCode: String? = null) : SyncEncryptionKeyStore {

    private val pairingCodeState = MutableStateFlow(initialPairingCode)

    var lastSetUpPassphrase: String? = null
        private set
    var lastPairPassphrase: String? = null
        private set
    var lastPairSalt: ByteArray? = null
        private set

    override val isSetUp = pairingCodeState.map { it != null }

    override suspend fun setUp(passphrase: CharArray): String {
        lastSetUpPassphrase = String(passphrase)
        val code = PairingCode.encode(PassphraseKeyDerivation.newSalt())
        pairingCodeState.value = code
        return code
    }

    override suspend fun pair(passphrase: CharArray, salt: ByteArray): String {
        lastPairPassphrase = String(passphrase)
        lastPairSalt = salt
        val code = PairingCode.encode(salt)
        pairingCodeState.value = code
        return code
    }

    override suspend fun cachedKey(): SecretKey? = null

    override suspend fun pairingCode(): String? = pairingCodeState.value
}
