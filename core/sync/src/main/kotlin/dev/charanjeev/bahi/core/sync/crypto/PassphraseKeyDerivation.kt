package dev.charanjeev.bahi.core.sync.crypto

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a user passphrase into an AES-256 key (docs/sync-design.md §8.4, D9).
 * Pure `javax.crypto` -- no new dependency, matching the design pass's claim.
 *
 * [ITERATIONS] is OWASP's current PBKDF2-HMAC-SHA256 floor rather than a
 * number picked for this app specifically: this is the slow-by-design step
 * the design doc's "deliberately slow to compute" line is about, so there is
 * no reason to under-shoot a published, still-current recommendation. It is
 * why the derived key is cached behind [KeyWrapper] rather than re-derived on
 * every sync -- see [SyncEncryptionKeyStore]'s doc.
 */
object PassphraseKeyDerivation {

    const val ITERATIONS = 210_000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * [passphrase] is cleared in place before returning, whatever the outcome
     * -- the one thing this function owns for exactly as long as it is on the
     * stack. The caller's own copy (from a UI text field, typically a
     * `String`) is a separate matter [SyncEncryptionKeyStore]'s doc names
     * rather than pretends to solve here.
     */
    fun derive(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        require(salt.size == SALT_LENGTH_BYTES) { "Salt must be $SALT_LENGTH_BYTES bytes, was ${salt.size}" }
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
            passphrase.fill(32.toChar())
        }
    }
}
