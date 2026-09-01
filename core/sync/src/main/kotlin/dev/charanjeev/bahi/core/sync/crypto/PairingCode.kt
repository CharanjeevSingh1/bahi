package dev.charanjeev.bahi.core.sync.crypto

import java.util.Base64

/**
 * The base64 form of a PBKDF2 salt, shown to the user after setup and typed
 * (or pasted) into a second device pairing into the same sync group
 * (docs/sync-design.md §8.4's "new-device flow"). Safe to display and copy in
 * the clear -- a salt is not secret, only the passphrase is, and §8.4's
 * no-in-app-shortcut argument is specifically about the passphrase crossing a
 * channel this app controls, not about the salt.
 *
 * A manual copy of this string is the only way a second device can learn the
 * first device's salt today: `DriveTransport` (slice 9e) does not exist yet
 * to publish it automatically, so this is the honest, fully-functional
 * version of pairing that does not depend on a transport -- see
 * [dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore]'s doc.
 */
object PairingCode {

    fun encode(salt: ByteArray): String = Base64.getEncoder().encodeToString(salt)

    /** Null for anything that is not exactly [PassphraseKeyDerivation.SALT_LENGTH_BYTES] of valid base64. */
    fun decode(code: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(code) }
            .getOrNull()
            ?.takeIf { it.size == PassphraseKeyDerivation.SALT_LENGTH_BYTES }
}
