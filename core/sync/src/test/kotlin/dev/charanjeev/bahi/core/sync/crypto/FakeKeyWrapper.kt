package dev.charanjeev.bahi.core.sync.crypto

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Stands in for [AndroidKeyStoreKeyWrapper], which needs a real
 * `AndroidKeyStore` that no unit test has (see its own doc). Not a mock: it
 * really does wrap and unwrap, just with the raw key bytes copied through
 * rather than encrypted -- enough to test [dev.charanjeev.bahi.core.sync.
 * SyncEncryptionKeyStore]'s persistence orchestration, which does not care
 * how the wrapping itself works, only that wrap-then-unwrap returns the same
 * key.
 */
class FakeKeyWrapper : KeyWrapper {
    override fun wrap(key: SecretKey): WrappedKey = WrappedKey(ciphertext = key.encoded, iv = ByteArray(0))
    override fun unwrap(wrapped: WrappedKey): SecretKey = SecretKeySpec(wrapped.ciphertext, "AES")
}
