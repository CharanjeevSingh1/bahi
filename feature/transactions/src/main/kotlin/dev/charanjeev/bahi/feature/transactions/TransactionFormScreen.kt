package dev.charanjeev.bahi.feature.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.Category
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime

@Composable
fun TransactionFormRoute(
    viewModel: TransactionFormViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                TransactionFormEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    TransactionFormScreen(
        uiState = uiState,
        onAmountTextChange = viewModel::onAmountTextChange,
        onAmountFieldFocusLost = viewModel::onAmountFieldFocusLost,
        onTypeChange = viewModel::onTypeChange,
        onDateChange = viewModel::onDateChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onCategorySelected = viewModel::onCategorySelected,
        onNotesChange = viewModel::onNotesChange,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onBackRequested = viewModel::onBackRequested,
        onDiscardConfirmed = viewModel::onDiscardConfirmed,
        onDiscardCancelled = viewModel::onDiscardCancelled,
    )
}

/**
 * Stateless and previewable, matching TransactionsScreen. The Route above
 * owns the ViewModel so this one can be driven directly from Compose tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionFormScreen(
    uiState: TransactionFormUiState,
    modifier: Modifier = Modifier,
    onAmountTextChange: (String) -> Unit = {},
    onAmountFieldFocusLost: () -> Unit = {},
    onTypeChange: (TransactionType) -> Unit = {},
    onDateChange: (LocalDate) -> Unit = {},
    onDescriptionChange: (String) -> Unit = {},
    onCategorySelected: (String) -> Unit = {},
    onNotesChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onDelete: () -> Unit = {},
    onBackRequested: () -> Unit = {},
    onDiscardConfirmed: () -> Unit = {},
    onDiscardCancelled: () -> Unit = {},
) {
    BackHandler(onBack = onBackRequested)

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TransactionFormTopBar(
                editingState = uiState as? TransactionFormUiState.Editing,
                onBackRequested = onBackRequested,
                onDeleteRequested = { showDeleteConfirmation = true },
                onSave = onSave,
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState) {
                TransactionFormUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TransactionFormTestTags.LOADING),
                )

                is TransactionFormUiState.Error -> Text(
                    text = uiState.message,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .testTag(TransactionFormTestTags.ERROR),
                )

                is TransactionFormUiState.Editing -> TransactionFormContent(
                    uiState = uiState,
                    onAmountTextChange = onAmountTextChange,
                    onAmountFieldFocusLost = onAmountFieldFocusLost,
                    onTypeChange = onTypeChange,
                    onDateChange = onDateChange,
                    onDescriptionChange = onDescriptionChange,
                    onCategorySelected = onCategorySelected,
                    onNotesChange = onNotesChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TransactionFormTestTags.FORM),
                )
            }
        }
    }

    if (uiState is TransactionFormUiState.Editing && uiState.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onDiscardCancelled,
            title = { Text(stringResource(R.string.transactions_form_discard_dialog_title)) },
            text = { Text(stringResource(R.string.transactions_form_discard_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = onDiscardConfirmed,
                    modifier = Modifier.testTag(TransactionFormTestTags.DISCARD_CONFIRM),
                ) { Text(stringResource(R.string.transactions_form_discard_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDiscardCancelled) {
                    Text(stringResource(R.string.transactions_form_discard_dialog_cancel))
                }
            },
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.transactions_form_delete_dialog_title)) },
            text = { Text(stringResource(R.string.transactions_form_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                    modifier = Modifier.testTag(TransactionFormTestTags.DELETE_CONFIRM),
                ) { Text(stringResource(R.string.transactions_form_delete_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.transactions_form_delete_dialog_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFormTopBar(
    editingState: TransactionFormUiState.Editing?,
    onBackRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(
                    if (editingState?.mode == FormMode.EDIT) {
                        R.string.transactions_form_title_edit
                    } else {
                        R.string.transactions_form_title_add
                    },
                ),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackRequested) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.transactions_form_back_content_description),
                )
            }
        },
        actions = {
            if (editingState?.mode == FormMode.EDIT) {
                IconButton(
                    onClick = onDeleteRequested,
                    modifier = Modifier.testTag(TransactionFormTestTags.DELETE_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.transactions_form_delete_content_description),
                    )
                }
            }
            // Save lives here rather than as a full-width button at the foot
            // of the form -- a short form doesn't need a heavy commit action,
            // and this keeps it next to Back instead of a scroll away.
            if (editingState != null) {
                TextButton(
                    onClick = onSave,
                    enabled = !editingState.isSaving,
                    modifier = Modifier.testTag(TransactionFormTestTags.SAVE_BUTTON),
                ) {
                    Text(stringResource(R.string.transactions_form_save))
                }
            }
        },
    )
}

@Composable
private fun TransactionFormContent(
    uiState: TransactionFormUiState.Editing,
    onAmountTextChange: (String) -> Unit,
    onAmountFieldFocusLost: () -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TransactionType.entries.forEachIndexed { index, type ->
                SegmentedButton(
                    selected = uiState.type == type,
                    onClick = { onTypeChange(type) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TransactionType.entries.size),
                    modifier = Modifier.testTag(TransactionFormTestTags.typeTag(type)),
                ) {
                    Text(
                        stringResource(
                            if (type == TransactionType.EXPENSE) {
                                R.string.transactions_form_type_expense
                            } else {
                                R.string.transactions_form_type_income
                            },
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // The currency's own symbol, not the ISO code -- INR-only today, but
        // reading it off Currency instead of hardcoding "₹" is what makes
        // this correct if a second currency ever shows up here.
        val currencySymbol = remember(uiState.currencyCode) { Currency.getInstance(uiState.currencyCode).symbol }
        OutlinedTextField(
            value = uiState.amountText,
            onValueChange = onAmountTextChange,
            label = { Text(stringResource(R.string.transactions_form_amount_label)) },
            prefix = { Text(currencySymbol) },
            singleLine = true,
            isError = uiState.showAmountError,
            supportingText = {
                if (uiState.showAmountError) {
                    Text(
                        stringResource(
                            if (uiState.amountError == AmountError.EMPTY) {
                                R.string.transactions_form_amount_error_empty
                            } else {
                                R.string.transactions_form_amount_error_invalid
                            },
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) onAmountFieldFocusLost() }
                .testTag(TransactionFormTestTags.AMOUNT_FIELD),
        )

        Spacer(Modifier.height(12.dp))

        DateField(date = uiState.date, onDateChange = onDateChange)

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.transactions_form_description_label)) },
            singleLine = true,
            isError = uiState.showDescriptionError,
            supportingText = {
                if (uiState.showDescriptionError) {
                    Text(
                        stringResource(
                            if (uiState.descriptionError == DescriptionError.TOO_LONG) {
                                R.string.transactions_form_description_error_too_long
                            } else {
                                R.string.transactions_form_description_error
                            },
                        ),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TransactionFormTestTags.DESCRIPTION_FIELD),
        )

        Spacer(Modifier.height(12.dp))

        CategoryField(
            categories = uiState.categories,
            selectedCategoryId = uiState.categoryId,
            onCategorySelected = onCategorySelected,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.transactions_form_notes_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TransactionFormTestTags.NOTES_FIELD),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(date: LocalDate, onDateChange: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val formatted = date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = formatted,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.transactions_form_date_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TransactionFormTestTags.DATE_FIELD),
        )
        // A readOnly TextField still consumes clicks for cursor placement, so
        // opening the picker needs its own transparent layer on top rather
        // than a plain clickable modifier on the field itself.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { showPicker = true },
        )
    }

    if (showPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val selected = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
                            onDateChange(selected)
                        }
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.transactions_form_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.transactions_form_discard_dialog_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    categories: ImmutableList<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = categories.firstOrNull { it.id == selectedCategoryId }?.name
        ?: stringResource(R.string.transactions_form_category_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.transactions_form_category_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .testTag(TransactionFormTestTags.CATEGORY_FIELD),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.name) },
                    onClick = {
                        onCategorySelected(category.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(TransactionFormTestTags.categoryOptionTag(category.id)),
                )
            }
        }
    }
}

internal object TransactionFormTestTags {
    const val LOADING = "transaction_form:loading"
    const val ERROR = "transaction_form:error"
    const val FORM = "transaction_form:form"
    const val AMOUNT_FIELD = "transaction_form:amount"
    const val DATE_FIELD = "transaction_form:date"
    const val DESCRIPTION_FIELD = "transaction_form:description"
    const val CATEGORY_FIELD = "transaction_form:category"
    const val NOTES_FIELD = "transaction_form:notes"
    const val SAVE_BUTTON = "transaction_form:save"
    const val DELETE_BUTTON = "transaction_form:delete"
    const val DELETE_CONFIRM = "transaction_form:delete:confirm"
    const val DISCARD_CONFIRM = "transaction_form:discard:confirm"
    fun typeTag(type: TransactionType) = "transaction_form:type:${type.name}"
    fun categoryOptionTag(categoryId: String) = "transaction_form:category_option:$categoryId"
}
