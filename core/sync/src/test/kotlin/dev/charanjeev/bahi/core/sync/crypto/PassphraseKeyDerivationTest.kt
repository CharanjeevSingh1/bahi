package dev.charanjeev.bahi.core.sync.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PassphraseKeyDerivationTest {

    @Test
    fun `same passphrase and salt derive the same key`() {
        val salt = PassphraseKeyDerivation.newSalt()

        val first = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), salt)
        val second = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), salt)

        assertThat(first.encoded).isEqualTo(second.encoded)
    }

    @Test
    fun `different passphrases derive different keys from the same salt`() {
        val salt = PassphraseKeyDerivation.newSalt()

        val a = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), salt)
        val b = PassphraseKeyDerivation.derive("correct horse battery staplee".toCharArray(), salt)

        assertThat(a.encoded).isNotEqualTo(b.encoded)
    }

    @Test
    fun `same passphrase derives different keys from different salts`() {
        val passphrase = "correct horse battery staple"

        val a = PassphraseKeyDerivation.derive(passphrase.toCharArray(), PassphraseKeyDerivation.newSalt())
        val b = PassphraseKeyDerivation.derive(passphrase.toCharArray(), PassphraseKeyDerivation.newSalt())

        assertThat(a.encoded).isNotEqualTo(b.encoded)
    }

    @Test
    fun `newSalt is the documented length and not the same value twice`() {
        val first = PassphraseKeyDerivation.newSalt()
        val second = PassphraseKeyDerivation.newSalt()

        assertThat(first).hasLength(PassphraseKeyDerivation.SALT_LENGTH_BYTES)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `derive clears the passphrase it was handed`() {
        val passphrase = "correct horse battery staple".toCharArray()

        PassphraseKeyDerivation.derive(passphrase, PassphraseKeyDerivation.newSalt())

        assertThat(passphrase).isEqualTo(CharArray(passphrase.size) { 32.toChar() })
    }

    @Test
    fun `rejects a salt of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            PassphraseKeyDerivation.derive("passphrase".toCharArray(), ByteArray(4))
        }
    }
}
