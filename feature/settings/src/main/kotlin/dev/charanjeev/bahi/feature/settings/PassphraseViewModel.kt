package dev.charanjeev.bahi.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.crypto.PairingCode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The passphrase-entry screen for setup and for pairing a new device
 * (docs/sync-design.md §8.4, D9, slice 9c). [PassphraseUiState.Entry] holds
 * the raw passphrase as a `String` while the user types, which the platform
 * cannot securely zero the way a `CharArray` can -- a known, accepted gap
 * named here rather than left implicit: converting to `CharArray` happens
 * only at [onSubmit], the point this ViewModel hands it to
 * [SyncEncryptionKeyStore], which clears that copy immediately after deriving
 * the key ([dev.charanjeev.bahi.core.sync.crypto.PassphraseKeyDerivation.derive]'s
 * doc). A custom `CharArray`-backed text field would close this gap and is
 * disproportionate machinery for a portfolio app's threat model (§8.4: the
 * two actors this design defends against are Google-under-compulsion and
 * account takeover, neither of which is reading this process's live heap).
 *
 * [ready] exists so [PassphraseUiState.Loading] is shown for the one
 * unavoidably async read -- whether this device is already set up -- rather
 * than flashing the entry form first and switching to [PassphraseUiState.Done]
 * a moment later on an already-configured device. Same shape as
 * `SettingsUiState.Loading` carrying `syncConfigured`, except that value
 * cannot be read synchronously the way `SyncConfiguration.isConfigured` can
 * (docs/sync-design.md §8.5): it lives in `DataStore`, which has no
 * synchronous read.
 */
@HiltViewModel
class PassphraseViewModel @Inject constructor(
    private val keyStore: SyncEncryptionKeyStore,
) : ViewModel() {

    private val entry = MutableStateFlow(PassphraseUiState.Entry(mode = PassphraseMode.SET_UP))
    private val done = MutableStateFlow<String?>(null)
    private val ready = MutableStateFlow(false)

    val uiState: StateFlow<PassphraseUiState> = combine(ready, entry, done) { isReady, entryState, doneCode ->
        when {
            !isReady -> PassphraseUiState.Loading
            doneCode != null -> PassphraseUiState.Done(doneCode)
            else -> entryState
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PassphraseUiState.Loading,
    )

    init {
        viewModelScope.launch {
            if (keyStore.isSetUp.first()) {
                done.value = keyStore.pairingCode()
            }
            ready.value = true
        }
    }

    fun onModeChanged(mode: PassphraseMode) {
        updateEntry { it.copy(mode = mode, error = null) }
    }

    fun onPassphraseChanged(value: String) {
        updateEntry { it.copy(passphrase = value, error = null) }
    }

    fun onConfirmPassphraseChanged(value: String) {
        updateEntry { it.copy(confirmPassphrase = value, error = null) }
    }

    fun onPairingCodeInputChanged(value: String) {
        updateEntry { it.copy(pairingCodeInput = value, error = null) }
    }

    fun onSubmit() {
        val current = entry.value
        val validationError = validate(current)
        if (validationError != null) {
            entry.value = current.copy(error = validationError)
            return
        }

        entry.value = current.copy(submitting = true, error = null)
        viewModelScope.launch {
            val pairingCode = when (current.mode) {
                PassphraseMode.SET_UP -> keyStore.setUp(current.passphrase.toCharArray())
                PassphraseMode.PAIR -> keyStore.pair(current.passphrase.toCharArray(), PairingCode.decode(current.pairingCodeInput)!!)
            }
            done.value = pairingCode
        }
    }

    private fun validate(state: PassphraseUiState.Entry): PassphraseEntryError? = when {
        state.passphrase.length < MIN_PASSPHRASE_LENGTH -> PassphraseEntryError.PASSPHRASE_TOO_SHORT
        state.mode == PassphraseMode.SET_UP && state.passphrase != state.confirmPassphrase -> PassphraseEntryError.PASSPHRASE_MISMATCH
        state.mode == PassphraseMode.PAIR && PairingCode.decode(state.pairingCodeInput) == null -> PassphraseEntryError.PAIRING_CODE_INVALID
        else -> null
    }

    private inline fun updateEntry(transform: (PassphraseUiState.Entry) -> PassphraseUiState.Entry) {
        entry.value = transform(entry.value)
    }
}
