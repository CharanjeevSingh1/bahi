package dev.charanjeev.bahi.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.sync.crypto.PairingCode
import dev.charanjeev.bahi.core.sync.crypto.PassphraseKeyDerivation
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassphraseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `starts loading then resolves to setup entry when nothing is set up`() = runTest {
        val viewModel = PassphraseViewModel(FakeSyncEncryptionKeyStore())

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PassphraseUiState.Loading)
            val entry = awaitItem() as PassphraseUiState.Entry
            assertThat(entry.mode).isEqualTo(PassphraseMode.SET_UP)
        }
    }

    @Test
    fun `starts loading then resolves straight to done when already set up`() = runTest {
        val keyStore = FakeSyncEncryptionKeyStore(initialPairingCode = "existing-code")
        val viewModel = PassphraseViewModel(keyStore)

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PassphraseUiState.Loading)
            assertThat(awaitItem()).isEqualTo(PassphraseUiState.Done("existing-code"))
        }
    }

    @Test
    fun `onModeChanged switches between setup and pair`() = runTest {
        val viewModel = PassphraseViewModel(FakeSyncEncryptionKeyStore())

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Entry(SET_UP)

            viewModel.onModeChanged(PassphraseMode.PAIR)

            assertThat((awaitItem() as PassphraseUiState.Entry).mode).isEqualTo(PassphraseMode.PAIR)
        }
    }

    @Test
    fun `submitting a too-short passphrase reports the error without calling the key store`() = runTest {
        val keyStore = FakeSyncEncryptionKeyStore()
        val viewModel = PassphraseViewModel(keyStore)

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onPassphraseChanged("short")
            skipItems(1)
            viewModel.onConfirmPassphraseChanged("short")
            skipItems(1)
            viewModel.onSubmit()

            val afterSubmit = awaitItem() as PassphraseUiState.Entry
            assertThat(afterSubmit.error).isEqualTo(PassphraseEntryError.PASSPHRASE_TOO_SHORT)
        }
        assertThat(keyStore.lastSetUpPassphrase).isNull()
    }

    @Test
    fun `setup with mismatched confirmation reports the error`() = runTest {
        val viewModel = PassphraseViewModel(FakeSyncEncryptionKeyStore())

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onPassphraseChanged("correct horse battery")
            skipItems(1)
            viewModel.onConfirmPassphraseChanged("different horse battery")
            skipItems(1)
            viewModel.onSubmit()

            val afterSubmit = awaitItem() as PassphraseUiState.Entry
            assertThat(afterSubmit.error).isEqualTo(PassphraseEntryError.PASSPHRASE_MISMATCH)
        }
    }

    @Test
    fun `a valid setup calls the key store and lands on done`() = runTest {
        val keyStore = FakeSyncEncryptionKeyStore()
        val viewModel = PassphraseViewModel(keyStore)

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onPassphraseChanged("correct horse battery")
            skipItems(1)
            viewModel.onConfirmPassphraseChanged("correct horse battery")
            skipItems(1)
            viewModel.onSubmit()

            skipItems(1) // submitting = true
            val done = awaitItem() as PassphraseUiState.Done
            assertThat(done.pairingCode).isNotEmpty()
        }
        assertThat(keyStore.lastSetUpPassphrase).isEqualTo("correct horse battery")
    }

    @Test
    fun `pairing with an invalid code reports the error without calling the key store`() = runTest {
        val keyStore = FakeSyncEncryptionKeyStore()
        val viewModel = PassphraseViewModel(keyStore)

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onModeChanged(PassphraseMode.PAIR)
            skipItems(1)
            viewModel.onPassphraseChanged("correct horse battery")
            skipItems(1)
            viewModel.onPairingCodeInputChanged("not a real pairing code")
            skipItems(1)
            viewModel.onSubmit()

            val afterSubmit = awaitItem() as PassphraseUiState.Entry
            assertThat(afterSubmit.error).isEqualTo(PassphraseEntryError.PAIRING_CODE_INVALID)
        }
        assertThat(keyStore.lastPairPassphrase).isNull()
    }

    @Test
    fun `a valid pair calls the key store with the decoded salt and lands on done`() = runTest {
        val keyStore = FakeSyncEncryptionKeyStore()
        val viewModel = PassphraseViewModel(keyStore)
        val salt = PassphraseKeyDerivation.newSalt()
        val pairingCode = PairingCode.encode(salt)

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onModeChanged(PassphraseMode.PAIR)
            skipItems(1)
            viewModel.onPassphraseChanged("correct horse battery")
            skipItems(1)
            viewModel.onPairingCodeInputChanged(pairingCode)
            skipItems(1)
            viewModel.onSubmit()

            skipItems(1) // submitting = true
            val done = awaitItem() as PassphraseUiState.Done
            assertThat(done.pairingCode).isEqualTo(pairingCode)
        }
        assertThat(keyStore.lastPairPassphrase).isEqualTo("correct horse battery")
        assertThat(keyStore.lastPairSalt).isEqualTo(salt)
    }

    @Test
    fun `changing a field after an error clears the error`() = runTest {
        val viewModel = PassphraseViewModel(FakeSyncEncryptionKeyStore())

        viewModel.uiState.test {
            skipItems(2) // Loading, Entry(SET_UP)

            viewModel.onSubmit() // empty passphrase -> too short
            val withError = awaitItem() as PassphraseUiState.Entry
            assertThat(withError.error).isNotNull()

            viewModel.onPassphraseChanged("c")

            val cleared = awaitItem() as PassphraseUiState.Entry
            assertThat(cleared.error).isNull()
        }
    }
}
