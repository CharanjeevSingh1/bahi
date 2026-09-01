package dev.charanjeev.bahi.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncTable

@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onRestoreRequested = viewModel::onRestoreRequested,
        onDismissRequested = viewModel::onDismissRequested,
        onRestoreMessageShown = viewModel::onRestoreMessageShown,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    uiState: SettingsUiState,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onRestoreRequested: (String) -> Unit = {},
    onDismissRequested: (String) -> Unit = {},
    onRestoreMessageShown: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.restoreMessage
    val restoreSucceeded = stringResource(R.string.settings_restore_succeeded)
    val restoreRowGone = stringResource(R.string.settings_restore_row_gone)
    val restoreValueChanged = stringResource(R.string.settings_restore_value_changed)
    val restoreNotFound = stringResource(R.string.settings_restore_not_found)
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val text = when (message.outcome) {
            RestoreOutcome.RESTORED -> restoreSucceeded
            RestoreOutcome.ROW_GONE -> restoreRowGone
            RestoreOutcome.VALUE_CHANGED_SINCE -> restoreValueChanged
            RestoreOutcome.NOT_FOUND -> restoreNotFound
        }
        snackbarHostState.showSnackbar(text)
        onRestoreMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            Text(
                text = stringResource(R.string.settings_sync_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (!uiState.syncConfigured) {
                NotConfiguredRow(modifier = Modifier.testTag(SettingsTestTags.NOT_CONFIGURED))
            }
            when (uiState) {
                is SettingsUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .testTag(SettingsTestTags.LOADING),
                )

                is SettingsUiState.Empty -> EmptyConflicts(modifier = Modifier.testTag(SettingsTestTags.EMPTY))

                is SettingsUiState.Success -> ConflictList(
                    uiState = uiState,
                    onRestoreRequested = onRestoreRequested,
                    onDismissRequested = onDismissRequested,
                )
            }
        }
    }
}

@Composable
private fun ConflictList(
    uiState: SettingsUiState.Success,
    onRestoreRequested: (String) -> Unit,
    onDismissRequested: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = pluralStringResource(R.plurals.settings_conflicts_count, uiState.conflicts.size, uiState.conflicts.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(SettingsTestTags.CONFLICTS_COUNT),
        )
        LazyColumn(modifier = Modifier.testTag(SettingsTestTags.LIST)) {
            items(uiState.conflicts, key = { it.id }) { item ->
                ConflictRow(
                    item = item,
                    onRestore = { onRestoreRequested(item.id) },
                    onDismiss = { onDismissRequested(item.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ConflictRow(
    item: ConflictListItem,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(SettingsTestTags.row(item.id)),
    ) {
        Text(
            text = "${tableLabel(item.table)} • ${fieldLabel(item.field)}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_conflict_reason, item.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${stringResource(R.string.settings_conflict_chosen)}: ${item.chosenValue.display()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "${stringResource(R.string.settings_conflict_discarded)}: ${item.discardedValue.display()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTestTags.dismiss(item.id))) {
                Text(stringResource(R.string.settings_conflict_dismiss))
            }
            TextButton(onClick = onRestore, modifier = Modifier.testTag(SettingsTestTags.restore(item.id))) {
                Text(stringResource(R.string.settings_conflict_restore))
            }
        }
    }
}

/**
 * Shown above whatever the conflicts section renders, in every state
 * including [SettingsUiState.Loading] -- D12's "visible, disabled, with an
 * explanation" answer (docs/sync-design.md §8.5) to what a reviewer without a
 * configured build should see: not a hidden feature, not a fake demo, a real
 * row saying plainly that this build has no transport wired up.
 */
@Composable
private fun NotConfiguredRow(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_sync_not_configured_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_sync_not_configured_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun EmptyConflicts(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.settings_conflicts_none),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_conflicts_none_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun tableLabel(table: SyncTable): String = when (table) {
    SyncTable.TRANSACTIONS -> stringResource(R.string.settings_table_transactions)
    SyncTable.CATEGORIES -> stringResource(R.string.settings_table_categories)
    SyncTable.BUDGETS -> stringResource(R.string.settings_table_budgets)
    SyncTable.CATEGORY_RULES -> stringResource(R.string.settings_table_category_rules)
}

@Composable
private fun fieldLabel(field: String): String = when (field) {
    "amount_minor" -> stringResource(R.string.settings_field_amount_minor)
    "currency_code" -> stringResource(R.string.settings_field_currency_code)
    "date" -> stringResource(R.string.settings_field_date)
    "description" -> stringResource(R.string.settings_field_description)
    "merchant" -> stringResource(R.string.settings_field_merchant)
    "category_id" -> stringResource(R.string.settings_field_category_id)
    "account_id" -> stringResource(R.string.settings_field_account_id)
    "source" -> stringResource(R.string.settings_field_source)
    "notes" -> stringResource(R.string.settings_field_notes)
    "category_locked_by_user" -> stringResource(R.string.settings_field_category_locked_by_user)
    "import_batch_id" -> stringResource(R.string.settings_field_import_batch_id)
    "name" -> stringResource(R.string.settings_field_name)
    "parent_id" -> stringResource(R.string.settings_field_parent_id)
    "color_argb" -> stringResource(R.string.settings_field_color_argb)
    "icon_key" -> stringResource(R.string.settings_field_icon_key)
    "is_system_defined" -> stringResource(R.string.settings_field_is_system_defined)
    "year_month" -> stringResource(R.string.settings_field_year_month)
    "limit_minor" -> stringResource(R.string.settings_field_limit_minor)
    "merchant_contains" -> stringResource(R.string.settings_field_merchant_contains)
    "priority" -> stringResource(R.string.settings_field_priority)
    // Falls back to the raw column name rather than crashing: a field this
    // screen's map doesn't know about yet is a copy gap, not a reason to
    // hide the row it belongs to.
    else -> field
}

/**
 * A raw, generic rendering: [ConflictValue.Number] shows minor units and an
 * argb int as plain integers rather than formatted currency or a swatch.
 * Real per-field formatting (Money, a colour chip) is a follow-up, noted
 * rather than attempted here so this stays honest about what it shows.
 */
@Composable
private fun ConflictValue.display(): String = when (this) {
    ConflictValue.None -> stringResource(R.string.settings_value_none)
    is ConflictValue.Text -> value
    is ConflictValue.Number -> value.toString()
    is ConflictValue.Flag -> stringResource(if (value) R.string.settings_value_yes else R.string.settings_value_no)
}
