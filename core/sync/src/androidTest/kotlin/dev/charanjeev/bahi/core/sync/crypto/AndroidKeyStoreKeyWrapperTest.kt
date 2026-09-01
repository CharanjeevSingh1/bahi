package dev.charanjeev.bahi.core.sync.crypto

import com.google.common.truth.Truth.assertThat
import java.security.GeneralSecurityException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The one piece of slice 9c that needs a real `AndroidKeyStore`
 * (docs/sync-design.md §8.4, D9) -- [AndroidKeyStoreKeyWrapper]'s own doc
 * says why this cannot run under `testDebugUnitTest`. [SyncEncryptionKeyStore]
 * covers everything built on top of a [KeyWrapper] with [FakeKeyWrapper]
 * standing in for this class; this test is the only thing that actually
 * exercises the real implementation.
 */
class AndroidKeyStoreKeyWrapperTest {

    private val wrapper = AndroidKeyStoreKeyWrapper()

    @Test
    fun wrapThenUnwrap_returnsTheSameKeyBytes() {
        val key = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), PassphraseKeyDerivation.newSalt())

        val unwrapped = wrapper.unwrap(wrapper.wrap(key))

        assertThat(unwrapped.encoded).isEqualTo(key.encoded)
    }

    @Test
    fun wrap_producesADifferentIvEachTime() {
        val key = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), PassphraseKeyDerivation.newSalt())

        val first = wrapper.wrap(key)
        val second = wrapper.wrap(key)

        assertThat(first.iv).isNotEqualTo(second.iv)
    }

    @Test
    fun unwrap_ofATamperedCiphertext_failsRatherThanReturningGarbage() {
        val key = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), PassphraseKeyDerivation.newSalt())
        val wrapped = wrapper.wrap(key)
        val tampered = wrapped.copy(ciphertext = wrapped.ciphertext.also { it[it.lastIndex] = (it.last() + 1).toByte() })

        assertThrows(GeneralSecurityException::class.java) { wrapper.unwrap(tampered) }
    }

    @Test
    fun wrappingKey_survivesACallerConstructingATheSecondInstance() {
        // The wrapping key lives in AndroidKeyStore, not in this object's own
        // state, so a second AndroidKeyStoreKeyWrapper -- the same shape as a
        // fresh process after the app is killed and restarted -- can still
        // unwrap what the first instance wrapped.
        val key = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), PassphraseKeyDerivation.newSalt())
        val wrapped = wrapper.wrap(key)

        val secondInstance = AndroidKeyStoreKeyWrapper()

        assertThat(secondInstance.unwrap(wrapped).encoded).isEqualTo(key.encoded)
    }
}
