package dev.charanjeev.bahi.core.sync.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `a salt round-trips through encode then decode unchanged`() {
        val salt = PassphraseKeyDerivation.newSalt()

        assertThat(PairingCode.decode(PairingCode.encode(salt))).isEqualTo(salt)
    }

    @Test
    fun `decode rejects text that is not valid base64`() {
        assertThat(PairingCode.decode("not base64 at all!!")).isNull()
    }

    @Test
    fun `decode rejects valid base64 of the wrong length`() {
        val tooShort = PairingCode.encode(ByteArray(4))

        assertThat(PairingCode.decode(tooShort)).isNull()
    }

    @Test
    fun `decode rejects an empty string`() {
        assertThat(PairingCode.decode("")).isNull()
    }
}
