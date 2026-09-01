package dev.charanjeev.bahi.core.sync.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * The real [KeyWrapper]: a hardware-backed AES key that never leaves
 * `AndroidKeyStore`, wrapping the PBKDF2-derived sync key the same way a
 * password manager wraps a vault key with a device key (docs/sync-design.md
 * §8.4, D9). Generated once, on first use, and reused after -- `AndroidKeyStore`
 * persists it across process death by construction, which is the whole point
 * of using it instead of, say, an in-memory key: the wrapped blob in
 * `DataStore` is useless without this device's copy of [KEY_ALIAS].
 *
 * Only verified on-device ([AndroidKeyStoreKeyWrapperTest], `androidTest`) --
 * `AndroidKeyStore` has no JVM implementation, and Robolectric's shadow of it
 * does not perform real cryptographic operations, so a `testDebugUnitTest`
 * claiming to cover this class would be testing the shadow, not the
 * behaviour. [SyncEncryptionKeyStore] is tested against a fake [KeyWrapper]
 * instead; this class is what that fake stands in for.
 */
class AndroidKeyStoreKeyWrapper @Inject constructor() : KeyWrapper {

    override fun wrap(key: SecretKey): WrappedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.WRAP_MODE, wrappingKey())
        return WrappedKey(ciphertext = cipher.wrap(key), iv = cipher.iv)
    }

    override fun unwrap(wrapped: WrappedKey): SecretKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.UNWRAP_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapped.iv))
        return cipher.unwrap(wrapped.ciphertext, "AES", Cipher.SECRET_KEY) as SecretKey
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "bahi_sync_key_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
