package dev.charanjeev.bahi.feature.settings

/**
 * Setup and pairing share one screen (docs/sync-design.md §8.4, D9, slice
 * 9c): both derive, wrap and persist a key identically -- see
 * [dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore]'s doc for why they
 * are one operation with different salt provenance rather than two. [Done] is
 * also what a device that is already set up sees the instant this screen
 * opens -- there is no "reset and start over" path here (§8.4: a lost
 * passphrase is unrecoverable by design, and silently letting a second
 * `setUp` call overwrite a working key would manufacture exactly that loss).
 */
sealed interface PassphraseUiState {

    data object Loading : PassphraseUiState

    data class Entry(
        val mode: PassphraseMode,
        val passphrase: String = "",
        val confirmPassphrase: String = "",
        val pairingCodeInput: String = "",
        val error: PassphraseEntryError? = null,
        val submitting: Boolean = false,
    ) : PassphraseUiState

    data class Done(val pairingCode: String) : PassphraseUiState
}

enum class PassphraseMode { SET_UP, PAIR }

enum class PassphraseEntryError { PASSPHRASE_TOO_SHORT, PASSPHRASE_MISMATCH, PAIRING_CODE_INVALID }

/** PBKDF2 is what actually protects a short passphrase (§8.4); this is a minimum against typos and one-character passphrases, not a strength meter. */
const val MIN_PASSPHRASE_LENGTH = 8

internal object PassphraseTestTags {
    const val LOADING = "passphrase:loading"
    const val ENTRY = "passphrase:entry"
    const val DONE = "passphrase:done"
    const val PASSPHRASE_FIELD = "passphrase:passphraseField"
    const val CONFIRM_PASSPHRASE_FIELD = "passphrase:confirmPassphraseField"
    const val PAIRING_CODE_FIELD = "passphrase:pairingCodeField"
    const val MODE_TOGGLE = "passphrase:modeToggle"
    const val SUBMIT = "passphrase:submit"
    const val ERROR = "passphrase:error"
    const val PAIRING_CODE_DISPLAY = "passphrase:pairingCodeDisplay"
}
