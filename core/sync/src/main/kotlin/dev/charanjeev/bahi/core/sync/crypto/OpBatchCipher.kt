package dev.charanjeev.bahi.core.sync.crypto

import dev.charanjeev.bahi.core.model.OpBatch
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/**
 * The wire format for one encrypted [OpBatch] (docs/sync-design.md §8.4, D9):
 * `[version:1 byte][nonce:12 bytes][ciphertext+tag]`. AES-256-GCM via
 * `javax.crypto` -- no new dependency, matching the design pass's claim.
 *
 * **Corrected while building 9c: no salt in the envelope.** The original
 * design-pass sentence -- "the payload envelope carries version, salt and
 * nonce" -- predates the AndroidKeyStore-cached-key refinement the "Decided"
 * entry under D9 adds. That refinement exists specifically so PBKDF2 (§8.4:
 * "deliberately slow to compute") runs once per device at setup, not once per
 * sync; a salt travelling on every envelope would only matter if every
 * encryption re-derived the key from the passphrase, which is exactly what
 * caching the derived key is for. The salt PBKDF2 actually uses lives once,
 * in [SyncEncryptionKeyStore]'s persisted key material, not here. What every
 * envelope still needs is a fresh nonce -- GCM's security argument requires
 * one per encryption under a given key, and this key is reused across many
 * batches.
 *
 * A version byte, not a version field inside the JSON, for the same reason
 * [OpBatch.version] is a top-level field rather than buried in [OpBatch.ops]:
 * a reader has to be able to tell an unreadable envelope apart *before*
 * attempting to decrypt it.
 */
object OpBatchCipher {

    const val ENVELOPE_VERSION: Byte = 1
    private const val NONCE_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(batch: OpBatch, key: SecretKey): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        val plaintext = Json.encodeToString(OpBatch.serializer(), batch).encodeToByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(ENVELOPE_VERSION) + nonce + ciphertext
    }

    /**
     * Throws [WrongPassphraseException] rather than returning a null or a
     * best-effort partial batch -- GCM's authentication tag is exactly the
     * mechanism that tells a wrong key apart from a right one, and §8.4
     * commits to failing loudly here rather than "producing garbage that
     * looks like a valid, corrupt op." A tampered or truncated envelope fails
     * the same way, for the same reason: GCM authenticates the whole
     * ciphertext, not just the key.
     */
    fun decrypt(envelope: ByteArray, key: SecretKey): OpBatch {
        require(envelope.size > 1 + NONCE_LENGTH_BYTES) {
            "Envelope too short to hold a version byte, a nonce and any ciphertext"
        }
        val version = envelope[0]
        require(version == ENVELOPE_VERSION) { "Unreadable envelope version $version" }
        val nonce = envelope.copyOfRange(1, 1 + NONCE_LENGTH_BYTES)
        val ciphertext = envelope.copyOfRange(1 + NONCE_LENGTH_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (cause: AEADBadTagException) {
            throw WrongPassphraseException(cause)
        }
        return Json.decodeFromString(OpBatch.serializer(), plaintext.decodeToString())
    }
}

/**
 * The passphrase used to derive [key][javax.crypto.SecretKey] does not match
 * what this envelope was encrypted with -- either a wrong passphrase was
 * typed, or the bytes are corrupt. Both are the same failure from the
 * caller's side: the plaintext cannot be trusted, whatever the cause.
 */
class WrongPassphraseException(cause: Throwable) : GeneralSecurityException(
    "The key does not match this ciphertext -- wrong passphrase, or the envelope is corrupt.",
    cause,
)
