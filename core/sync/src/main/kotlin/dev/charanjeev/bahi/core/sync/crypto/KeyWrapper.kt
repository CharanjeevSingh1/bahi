package dev.charanjeev.bahi.core.sync.crypto

import javax.crypto.SecretKey

/**
 * Wraps the PBKDF2-derived sync key so it can sit in `DataStore` without
 * storing the key itself in the clear (docs/sync-design.md §8.4, D9: "the
 * derived key is cached... wrapped with a hardware-backed `AndroidKeyStore`
 * key"). A seam, not a concrete class, for the same reason [dev.charanjeev.
 * bahi.core.sync.SyncConfiguration] is one: [AndroidKeyStoreKeyWrapper] needs
 * a real `AndroidKeyStore`, which nothing in `testDebugUnitTest` has, so
 * [SyncEncryptionKeyStore]'s own logic is tested against a fake here and the
 * real implementation is verified on-device instead (see its own doc).
 */
interface KeyWrapper {
    fun wrap(key: SecretKey): WrappedKey
    fun unwrap(wrapped: WrappedKey): SecretKey
}

data class WrappedKey(val ciphertext: ByteArray, val iv: ByteArray) {
    // Generated because this is a data class holding ByteArrays: the default
    // equals/hashCode would compare array identity, not contents, which is
    // wrong for a value used in round-trip assertions.
    override fun equals(other: Any?): Boolean =
        this === other || (other is WrappedKey && ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv))

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()
}
