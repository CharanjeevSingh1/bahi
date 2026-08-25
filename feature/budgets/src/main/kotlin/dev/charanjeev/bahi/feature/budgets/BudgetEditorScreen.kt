package dev.charanjeev.bahi.feature.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BudgetEditorRoute(
    viewModel: BudgetEditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                BudgetEditorEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    BudgetEditorScreen(
        uiState = uiState,
        onLimitTextChange = viewModel::onLimitTextChange,
        onCategorySelected = viewModel::onCategorySelected,
        onSave = viewModel::onSave,
        onCancel = viewModel::onCancel,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetEditorScreen(
    uiState: BudgetEditorUiState,
    modifier: Modifier = Modifier,
    onLimitTextChange: (String) -> Unit = {},
    onCategorySelected: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val editing = uiState as? BudgetEditorUiState.Editing
                    Text(
                        stringResource(
                            if (editing?.mode == BudgetEditorMode.EDIT) {
                                R.string.budget_editor_edit_title
                            } else {
                                R.string.budget_editor_add_title
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.budgets_back_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState) {
                BudgetEditorUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testTag(BudgetEditorTestTags.LOADING),
                )

                is BudgetEditorUiState.Error -> Text(
                    text = uiState.message,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                is BudgetEditorUiState.Editing -> BudgetEditorForm(
                    state = uiState,
                    onLimitTextChange = onLimitTextChange,
                    onCategorySelected = onCategorySelected,
                    onSave = onSave,
                )
            }
        }
    }
}

@Composable
private fun BudgetEditorForm(
    state: BudgetEditorUiState.Editing,
    onLimitTextChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.budget_editor_month, state.month.displayName()),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))

        CategoryField(state = state, onCategorySelected = onCategorySelected)

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.limitText,
            onValueChange = onLimitTextChange,
            label = { Text(stringResource(R.string.budget_editor_limit_label)) },
            isError = state.showLimitError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text(
                    text = when {
                        !state.showLimitError -> stringResource(R.string.budget_editor_limit_hint)
                        state.limitError == LimitError.NOT_POSITIVE ->
                            stringResource(R.string.budget_editor_limit_error_not_positive)
                        else -> stringResource(R.string.budget_editor_limit_error_invalid)
                    },
                    modifier = Modifier.testTag(BudgetEditorTestTags.LIMIT_ERROR),
                )
            },
            modifier = Modifier.fillMaxWidth().testTag(BudgetEditorTestTags.LIMIT_FIELD),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag(BudgetEditorTestTags.SAVE),
        ) {
            Text(stringResource(R.string.budget_editor_save))
        }
    }
}

@Composable
private fun CategoryField(
    state: BudgetEditorUiState.Editing,
    onCategorySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = state.selectedCategory?.name ?: stringResource(R.string.budget_editor_category_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.budget_editor_category_label)) },
            isError = state.showCategoryError,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag(BudgetEditorTestTags.CATEGORY_FIELD),
        )
        // A readOnly text field still swallows taps, so the click target is an
        // overlay -- same approach the rule editor and transaction form use.
        Box(modifier = Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onCategorySelected(category.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(BudgetEditorTestTags.categoryOption(category.id)),
                )
            }
        }
    }
}
