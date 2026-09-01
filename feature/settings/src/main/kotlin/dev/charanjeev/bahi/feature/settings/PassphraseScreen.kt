package dev.charanjeev.bahi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PassphraseRoute(
    viewModel: PassphraseViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PassphraseScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onModeChanged = viewModel::onModeChanged,
        onPassphraseChanged = viewModel::onPassphraseChanged,
        onConfirmPassphraseChanged = viewModel::onConfirmPassphraseChanged,
        onPairingCodeInputChanged = viewModel::onPairingCodeInputChanged,
        onSubmit = viewModel::onSubmit,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassphraseScreen(
    uiState: PassphraseUiState,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onModeChanged: (PassphraseMode) -> Unit = {},
    onPassphraseChanged: (String) -> Unit = {},
    onConfirmPassphraseChanged: (String) -> Unit = {},
    onPairingCodeInputChanged: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passphrase_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (uiState) {
                is PassphraseUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .testTag(PassphraseTestTags.LOADING),
                )

                is PassphraseUiState.Entry -> EntryContent(
                    state = uiState,
                    onModeChanged = onModeChanged,
                    onPassphraseChanged = onPassphraseChanged,
                    onConfirmPassphraseChanged = onConfirmPassphraseChanged,
                    onPairingCodeInputChanged = onPairingCodeInputChanged,
                    onSubmit = onSubmit,
                )

                is PassphraseUiState.Done -> DoneContent(pairingCode = uiState.pairingCode)
            }
        }
    }
}

@Composable
private fun EntryContent(
    state: PassphraseUiState.Entry,
    onModeChanged: (PassphraseMode) -> Unit,
    onPassphraseChanged: (String) -> Unit,
    onConfirmPassphraseChanged: (String) -> Unit,
    onPairingCodeInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(PassphraseTestTags.ENTRY),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(if (state.mode == PassphraseMode.SET_UP) R.string.passphrase_setup_disclosure else R.string.passphrase_pair_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.passphrase_lost_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        TextButton(
            onClick = { onModeChanged(if (state.mode == PassphraseMode.SET_UP) PassphraseMode.PAIR else PassphraseMode.SET_UP) },
            modifier = Modifier.testTag(PassphraseTestTags.MODE_TOGGLE),
        ) {
            Text(
                stringResource(
                    if (state.mode == PassphraseMode.SET_UP) R.string.passphrase_switch_to_pair else R.string.passphrase_switch_to_setup,
                ),
            )
        }

        if (state.mode == PassphraseMode.PAIR) {
            OutlinedTextField(
                value = state.pairingCodeInput,
                onValueChange = onPairingCodeInputChanged,
                label = { Text(stringResource(R.string.passphrase_pairing_code_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PassphraseTestTags.PAIRING_CODE_FIELD),
            )
        }

        OutlinedTextField(
            value = state.passphrase,
            onValueChange = onPassphraseChanged,
            label = { Text(stringResource(R.string.passphrase_field_label)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PassphraseTestTags.PASSPHRASE_FIELD),
        )

        if (state.mode == PassphraseMode.SET_UP) {
            OutlinedTextField(
                value = state.confirmPassphrase,
                onValueChange = onConfirmPassphraseChanged,
                label = { Text(stringResource(R.string.passphrase_confirm_field_label)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PassphraseTestTags.CONFIRM_PASSPHRASE_FIELD),
            )
        }

        state.error?.let { error ->
            Text(
                text = errorMessage(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(PassphraseTestTags.ERROR),
            )
        }

        Button(
            onClick = onSubmit,
            enabled = !state.submitting,
            modifier = Modifier.testTag(PassphraseTestTags.SUBMIT),
        ) {
            Text(stringResource(if (state.mode == PassphraseMode.SET_UP) R.string.passphrase_submit_setup else R.string.passphrase_submit_pair))
        }
    }
}

@Composable
private fun DoneContent(pairingCode: String) {
    Column(
        modifier = Modifier.testTag(PassphraseTestTags.DONE),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.passphrase_done_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.passphrase_pairing_code_label), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                text = pairingCode,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(PassphraseTestTags.PAIRING_CODE_DISPLAY),
            )
        }
    }
}

@Composable
private fun errorMessage(error: PassphraseEntryError): String = when (error) {
    PassphraseEntryError.PASSPHRASE_TOO_SHORT -> stringResource(R.string.passphrase_error_too_short, MIN_PASSPHRASE_LENGTH)
    PassphraseEntryError.PASSPHRASE_MISMATCH -> stringResource(R.string.passphrase_error_mismatch)
    PassphraseEntryError.PAIRING_CODE_INVALID -> stringResource(R.string.passphrase_error_pairing_code_invalid)
}
