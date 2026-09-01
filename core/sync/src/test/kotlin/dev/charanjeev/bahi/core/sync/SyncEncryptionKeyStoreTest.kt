package dev.charanjeev.bahi.core.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import dev.charanjeev.bahi.core.sync.crypto.FakeKeyWrapper
import dev.charanjeev.bahi.core.sync.crypto.PairingCode
import dev.charanjeev.bahi.core.sync.crypto.PassphraseKeyDerivation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DefaultSyncEncryptionKeyStore]'s persistence orchestration, against a real
 * file-backed `DataStore` (no Android runtime needed for that -- see
 * [FakeKeyWrapper]'s doc for the one piece this test suite cannot exercise).
 */
class SyncEncryptionKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyStore: SyncEncryptionKeyStore

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("test.preferences_pb") },
        )
        keyStore = DefaultSyncEncryptionKeyStore(UserPreferencesDataSource(dataStore), FakeKeyWrapper())
    }

    @Test
    fun `isSetUp is false before anything has been set up`() = runTest {
        assertThat(keyStore.isSetUp.first()).isFalse()
    }

    @Test
    fun `setUp makes isSetUp true and caches a key`() = runTest {
        keyStore.setUp("correct horse battery staple".toCharArray())

        assertThat(keyStore.isSetUp.first()).isTrue()
        assertThat(keyStore.cachedKey()).isNotNull()
    }

    @Test
    fun `cachedKey is null before setUp or pair has run`() = runTest {
        assertThat(keyStore.cachedKey()).isNull()
    }

    @Test
    fun `setUp then cachedKey unwraps back to the same key derive would produce from the returned pairing code`() = runTest {
        val pairingCode = keyStore.setUp("correct horse battery staple".toCharArray())
        val salt = PairingCode.decode(pairingCode)!!

        val expected = PassphraseKeyDerivation.derive("correct horse battery staple".toCharArray(), salt)
        val cached = keyStore.cachedKey()!!

        assertThat(cached.encoded).isEqualTo(expected.encoded)
    }

    @Test
    fun `pair derives against the salt it is given rather than minting a new one`() = runTest {
        val salt = PassphraseKeyDerivation.newSalt()

        val pairingCode = keyStore.pair("correct horse battery staple".toCharArray(), salt)

        assertThat(PairingCode.decode(pairingCode)).isEqualTo(salt)
    }

    @Test
    fun `pairingCode returns null before setUp or pair has run`() = runTest {
        assertThat(keyStore.pairingCode()).isNull()
    }

    @Test
    fun `pairingCode after setUp matches what setUp returned`() = runTest {
        val returned = keyStore.setUp("correct horse battery staple".toCharArray())

        assertThat(keyStore.pairingCode()).isEqualTo(returned)
    }

    @Test
    fun `setUp twice replaces the key rather than layering a second one`() = runTest {
        keyStore.setUp("first passphrase entirely".toCharArray())
        val secondPairingCode = keyStore.setUp("second passphrase entirely".toCharArray())

        assertThat(keyStore.pairingCode()).isEqualTo(secondPairingCode)
        val expected = PassphraseKeyDerivation.derive("second passphrase entirely".toCharArray(), PairingCode.decode(secondPairingCode)!!)
        assertThat(keyStore.cachedKey()!!.encoded).isEqualTo(expected.encoded)
    }
}
