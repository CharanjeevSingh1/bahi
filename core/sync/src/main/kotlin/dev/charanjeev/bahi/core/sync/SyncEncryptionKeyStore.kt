package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import dev.charanjeev.bahi.core.datastore.SyncEncryptionKeyMaterial
import dev.charanjeev.bahi.core.sync.crypto.KeyWrapper
import dev.charanjeev.bahi.core.sync.crypto.PairingCode
import dev.charanjeev.bahi.core.sync.crypto.PassphraseKeyDerivation
import dev.charanjeev.bahi.core.sync.crypto.WrappedKey
import java.util.Base64
import javax.crypto.SecretKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The passphrase-derived sync key, cached per device (docs/sync-design.md
 * §8.4, D9). The seam `:feature:settings`' passphrase screen talks to --
 * fakeable in a ViewModel test the same way [SyncConfiguration] is, since the
 * real implementation's [KeyWrapper] needs a real `AndroidKeyStore`.
 *
 * [setUp] and [pair] are the same operation with different salt provenance:
 * [setUp] mints a fresh salt (first device in a sync group), [pair] is
 * handed one (every device after, typed or pasted in from the first --
 * §8.4's "new-device flow"). Both derive, wrap and persist identically, which
 * is why they share [persist] rather than being two independent code paths
 * that could quietly drift apart.
 *
 * **What this does not do, named rather than silently missing.** Neither
 * method can tell a "wrong" passphrase from a right one -- there is nothing
 * to compare against until real ciphertext exists to fail against (§8.4: "a
 * lost passphrase is unrecoverable"; [dev.charanjeev.bahi.core.sync.crypto.
 * OpBatchCipher.decrypt] is where that failure actually surfaces, against a
 * real op, not here). A second device *can* now learn the first device's
 * salt automatically -- `DriveTransport.publishSalt`/`readPublishedSalt`
 * (slice 9e) publish and read it unencrypted, separate from the encrypted op
 * log -- but nothing calls that from a screen yet. [pairingCode] is still
 * the whole of the "new-device flow" a user actually sees: a string relayed
 * by hand, the same way they relay the passphrase itself. Wiring
 * `PassphraseScreen` to prefer the automatic path is a UX decision left for
 * whoever picks that up next.
 */
interface SyncEncryptionKeyStore {

    val isSetUp: Flow<Boolean>

    /** Generates a fresh salt, derives and wraps the key, persists both. Returns the pairing code for a second device. */
    suspend fun setUp(passphrase: CharArray): String

    /** Same as [setUp], but derives against a salt learned from another device rather than minting one. */
    suspend fun pair(passphrase: CharArray, salt: ByteArray): String

    /** Null until [setUp] or [pair] has run on this device. */
    suspend fun cachedKey(): SecretKey?

    /** The salt this device's key was derived from, base64-encoded for display -- what [pair] on a second device needs. Null until set up. */
    suspend fun pairingCode(): String?
}

class DefaultSyncEncryptionKeyStore @Inject constructor(
    private val preferences: UserPreferencesDataSource,
    private val keyWrapper: KeyWrapper,
) : SyncEncryptionKeyStore {

    override val isSetUp: Flow<Boolean> = preferences.syncEncryptionKeyMaterial.map { it != null }

    override suspend fun setUp(passphrase: CharArray): String = persist(passphrase, PassphraseKeyDerivation.newSalt())

    override suspend fun pair(passphrase: CharArray, salt: ByteArray): String = persist(passphrase, salt)

    override suspend fun cachedKey(): SecretKey? {
        val material = preferences.syncEncryptionKeyMaterial.first() ?: return null
        return keyWrapper.unwrap(
            WrappedKey(
                ciphertext = Base64.getDecoder().decode(material.wrappedKeyBase64),
                iv = Base64.getDecoder().decode(material.wrappedKeyIvBase64),
            ),
        )
    }

    override suspend fun pairingCode(): String? = preferences.syncEncryptionKeyMaterial.first()?.saltBase64

    private suspend fun persist(passphrase: CharArray, salt: ByteArray): String {
        val key = PassphraseKeyDerivation.derive(passphrase, salt)
        val wrapped = keyWrapper.wrap(key)
        preferences.setSyncEncryptionKeyMaterial(
            SyncEncryptionKeyMaterial(
                saltBase64 = PairingCode.encode(salt),
                wrappedKeyBase64 = Base64.getEncoder().encodeToString(wrapped.ciphertext),
                wrappedKeyIvBase64 = Base64.getEncoder().encodeToString(wrapped.iv),
            ),
        )
        return PairingCode.encode(salt)
    }
}
