package dev.charanjeev.bahi.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RulesRoute(
    viewModel: RulesViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onAddRule: () -> Unit = {},
    onEditRule: (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RulesScreen(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onAddRule = onAddRule,
        onEditRule = onEditRule,
        onMoveUp = viewModel::onMoveUp,
        onMoveDown = viewModel::onMoveDown,
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteConfirmed = viewModel::onDeleteConfirmed,
        onDeleteCancelled = viewModel::onDeleteCancelled,
        onRecategoriseRequested = viewModel::onRecategoriseRequested,
        onApplyConfirmed = viewModel::onApplyConfirmed,
        onDialogDismissed = viewModel::onDialogDismissed,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RulesScreen(
    uiState: RulesUiState,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onAddRule: () -> Unit = {},
    onEditRule: (String) -> Unit = {},
    onMoveUp: (String) -> Unit = {},
    onMoveDown: (String) -> Unit = {},
    onDeleteRequested: (String) -> Unit = {},
    onDeleteConfirmed: () -> Unit = {},
    onDeleteCancelled: () -> Unit = {},
    onRecategoriseRequested: () -> Unit = {},
    onApplyConfirmed: () -> Unit = {},
    onDialogDismissed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.rules_back_content_description),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule, modifier = Modifier.testTag(RulesTestTags.ADD_FAB)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.rules_add_content_description),
                )
            }
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState) {
                RulesUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testTag(RulesTestTags.LOADING),
                )

                RulesUiState.Empty -> EmptyRules(
                    modifier = Modifier.align(Alignment.Center).testTag(RulesTestTags.EMPTY),
                )

                is RulesUiState.Success -> RulesList(
                    state = uiState,
                    onEditRule = onEditRule,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDeleteRequested = onDeleteRequested,
                    onRecategoriseRequested = onRecategoriseRequested,
                )
            }
        }
    }

    val success = uiState as? RulesUiState.Success
    RuleApplyDialogs(
        dialog = success?.dialog,
        onConfirm = onApplyConfirmed,
        onDecline = onDialogDismissed,
        onDismiss = onDialogDismissed,
        confirmTestTag = RulesTestTags.CONFIRM_APPLY,
        dialogTestTag = RulesTestTags.CONFIRM_DIALOG,
        nothingToDoTestTag = RulesTestTags.NOTHING_TO_DO_DIALOG,
        doneTestTag = RulesTestTags.DONE_DIALOG,
    )

    val pendingDelete = success?.pendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDeleteCancelled,
            modifier = Modifier.testTag(RulesTestTags.DELETE_DIALOG),
            title = { Text(stringResource(R.string.rules_delete_title)) },
            // Says what deleting does *not* do. Someone who has been warned
            // that rules rewrite categories in bulk has every reason to
            // wonder whether removing one rewrites them back.
            text = { Text(stringResource(R.string.rules_delete_body)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirmed) {
                    Text(stringResource(R.string.rules_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDeleteCancelled) { Text(stringResource(R.string.rules_cancel)) }
            },
        )
    }
}

@Composable
private fun RulesList(
    state: RulesUiState.Success,
    onEditRule: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onDeleteRequested: (String) -> Unit,
    onRecategoriseRequested: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.rules_order_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f).testTag(RulesTestTags.LIST)) {
            itemsIndexed(state.rules, key = { _, item -> item.rule.id }) { index, item ->
                RuleRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.rules.lastIndex,
                    onClick = { onEditRule(item.rule.id) },
                    onMoveUp = { onMoveUp(item.rule.id) },
                    onMoveDown = { onMoveDown(item.rule.id) },
                    onDelete = { onDeleteRequested(item.rule.id) },
                )
                HorizontalDivider()
            }
        }
        OutlinedButton(
            onClick = onRecategoriseRequested,
            // Blocked while a preview or an apply is running, rather than
            // queueing a second run behind the first.
            enabled = !state.isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(RulesTestTags.RECATEGORISE_ACTION),
        ) {
            Text(stringResource(R.string.rules_recategorise))
        }
    }
}

@Composable
private fun RuleRow(
    item: RuleListItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RulesTestTags.row(item.rule.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.rules_summary, item.rule.merchantContains),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = item.category?.name ?: stringResource(R.string.rules_unknown_category),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier = Modifier.testTag(RulesTestTags.moveUp(item.rule.id)),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.rules_move_up_content_description),
            )
        }
        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier.testTag(RulesTestTags.moveDown(item.rule.id)),
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.rules_move_down_content_description),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag(RulesTestTags.delete(item.rule.id))) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.rules_delete_content_description),
            )
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.rule_editor_edit_title)) }
    }
}

@Composable
private fun EmptyRules(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.rules_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.rules_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
