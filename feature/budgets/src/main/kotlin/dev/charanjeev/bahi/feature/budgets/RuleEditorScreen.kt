package dev.charanjeev.bahi.feature.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RuleEditorRoute(
    viewModel: RuleEditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RuleEditorEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    RuleEditorScreen(
        uiState = uiState,
        onMerchantContainsChange = viewModel::onMerchantContainsChange,
        onCategorySelected = viewModel::onCategorySelected,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onCancel = viewModel::onCancel,
        onApplyConfirmed = viewModel::onApplyConfirmed,
        onApplyDeclined = viewModel::onApplyDeclined,
        onDoneDismissed = viewModel::onDoneDismissed,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorScreen(
    uiState: RuleEditorUiState,
    modifier: Modifier = Modifier,
    onMerchantContainsChange: (String) -> Unit = {},
    onCategorySelected: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onDelete: () -> Unit = {},
    onCancel: () -> Unit = {},
    onApplyConfirmed: () -> Unit = {},
    onApplyDeclined: () -> Unit = {},
    onDoneDismissed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val editing = uiState as? RuleEditorUiState.Editing
                    Text(
                        stringResource(
                            if (editing?.mode == RuleEditorMode.EDIT) {
                                R.string.rule_editor_edit_title
                            } else {
                                R.string.rule_editor_add_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.rules_back_content_description),
                        )
                    }
                },
                actions = {
                    if ((uiState as? RuleEditorUiState.Editing)?.mode == RuleEditorMode.EDIT) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(
                                    R.string.rule_editor_delete_content_description,
                                ),
                            )
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState) {
                RuleEditorUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testTag(RuleEditorTestTags.LOADING),
                )

                is RuleEditorUiState.Error -> Text(
                    text = uiState.message,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                is RuleEditorUiState.Editing -> RuleEditorForm(
                    state = uiState,
                    onMerchantContainsChange = onMerchantContainsChange,
                    onCategorySelected = onCategorySelected,
                    onSave = onSave,
                )
            }
        }
    }

    RuleApplyDialogs(
        dialog = (uiState as? RuleEditorUiState.Editing)?.dialog,
        onConfirm = onApplyConfirmed,
        onDecline = onApplyDeclined,
        onDismiss = onDoneDismissed,
        confirmTestTag = RuleEditorTestTags.CONFIRM_APPLY,
        dialogTestTag = RuleEditorTestTags.CONFIRM_DIALOG,
        nothingToDoTestTag = RuleEditorTestTags.NOTHING_TO_DO_DIALOG,
        doneTestTag = RuleEditorTestTags.DONE_DIALOG,
        declineTestTag = RuleEditorTestTags.CONFIRM_SKIP,
    )
}

@Composable
private fun RuleEditorForm(
    state: RuleEditorUiState.Editing,
    onMerchantContainsChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.merchantContains,
            onValueChange = onMerchantContainsChange,
            label = { Text(stringResource(R.string.rule_editor_merchant_label)) },
            isError = state.showMerchantError,
            singleLine = true,
            supportingText = {
                Text(
                    text = if (state.showMerchantError) {
                        stringResource(R.string.rule_editor_merchant_error_blank)
                    } else {
                        stringResource(R.string.rule_editor_merchant_hint)
                    },
                    modifier = Modifier.testTag(RuleEditorTestTags.MERCHANT_ERROR),
                )
            },
            modifier = Modifier.fillMaxWidth().testTag(RuleEditorTestTags.MERCHANT_FIELD),
        )

        Spacer(Modifier.height(16.dp))

        CategoryField(state = state, onCategorySelected = onCategorySelected)

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            // The outermost guard on the blank rule: with nothing typed there
            // is no way to submit at all, so the layers underneath never have
            // to be what stops a real user (see Editing.canSave).
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag(RuleEditorTestTags.SAVE),
        ) {
            Text(stringResource(R.string.rule_editor_save))
        }
    }
}

@Composable
private fun CategoryField(
    state: RuleEditorUiState.Editing,
    onCategorySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = state.selectedCategory?.name ?: stringResource(R.string.rule_editor_category_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.rule_editor_category_label)) },
            isError = state.showCategoryError,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            supportingText = if (state.showCategoryError) {
                { Text(stringResource(R.string.rule_editor_category_error)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag(RuleEditorTestTags.CATEGORY_FIELD),
        )
        // A readOnly text field still swallows taps, so the click target is
        // an overlay -- same approach the transaction form's date field uses.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(RuleEditorTestTags.CATEGORY_MENU),
        ) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onCategorySelected(category.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(RuleEditorTestTags.categoryOption(category.id)),
                )
            }
        }
    }
}
