package dev.charanjeev.bahi.feature.csvimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.importer.ColumnMapping
import dev.charanjeev.bahi.core.importer.MappingField
import dev.charanjeev.bahi.core.importer.PreviewRow

@Composable
fun ImportRoute(
    viewModel: ImportViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onFilePicked(it.toString()) }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                ImportEvent.Finished -> onNavigateBack()
            }
        }
    }

    ImportScreen(
        uiState = uiState,
        onPickFile = {
            pickFileLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*"))
        },
        onPickAnotherFile = viewModel::onPickAnotherFile,
        onCorrectionRequested = viewModel::onCorrectionRequested,
        onFixColumnsByHandRequested = viewModel::onFixColumnsByHandRequested,
        onCorrectionDismissed = viewModel::onCorrectionDismissed,
        onDateFormatSelected = viewModel::onDateFormatSelected,
        onAmountColumnsSwapSelected = viewModel::onAmountColumnsSwapSelected,
        onRawGridMappingApplied = viewModel::onRawGridMappingApplied,
        onImportConfirmed = viewModel::onImportConfirmed,
        onDone = viewModel::onDone,
        onBack = onNavigateBack,
    )
}

/** Stateless and previewable, matching TransactionFormScreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportScreen(
    uiState: ImportUiState,
    modifier: Modifier = Modifier,
    onPickFile: () -> Unit = {},
    onPickAnotherFile: () -> Unit = {},
    onCorrectionRequested: (MappingField) -> Unit = {},
    onFixColumnsByHandRequested: () -> Unit = {},
    onCorrectionDismissed: () -> Unit = {},
    onDateFormatSelected: (String) -> Unit = {},
    onAmountColumnsSwapSelected: (Int, Int) -> Unit = { _, _ -> },
    onRawGridMappingApplied: (ColumnMapping) -> Unit = {},
    onImportConfirmed: () -> Unit = {},
    onDone: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.import_back_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (uiState) {
                ImportUiState.Idle -> IdleContent(onPickFile)
                ImportUiState.Reading -> LoadingContent(stringResource(R.string.import_reading), ImportTestTags.READING)
                is ImportUiState.Preview -> PreviewContent(
                    state = uiState,
                    onCorrectionRequested = onCorrectionRequested,
                    onFixColumnsByHandRequested = onFixColumnsByHandRequested,
                    onCorrectionDismissed = onCorrectionDismissed,
                    onDateFormatSelected = onDateFormatSelected,
                    onAmountColumnsSwapSelected = onAmountColumnsSwapSelected,
                    onRawGridMappingApplied = onRawGridMappingApplied,
                    onImportConfirmed = onImportConfirmed,
                )
                ImportUiState.Importing -> LoadingContent(stringResource(R.string.import_importing), ImportTestTags.IMPORTING)
                is ImportUiState.Result -> ResultContent(uiState, onDone)
                is ImportUiState.Failed -> FailedContent(uiState, onPickAnotherFile)
            }
        }
    }
}

@Composable
private fun IdleContent(onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ImportTestTags.IDLE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.import_pick_file_body))
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onPickFile, modifier = Modifier.testTag(ImportTestTags.PICK_FILE_BUTTON)) {
            Text(stringResource(R.string.import_pick_file_button))
        }
    }
}

@Composable
private fun LoadingContent(label: String, testTag: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(testTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(label)
    }
}

@Composable
private fun FailedContent(state: ImportUiState.Failed, onPickAnotherFile: () -> Unit) {
    val message = stringResource(
        when (state.reason) {
            FailureReason.TOO_LARGE -> R.string.import_error_too_large
            FailureReason.NOT_TEXT -> R.string.import_error_not_text
            FailureReason.ENCODING -> R.string.import_error_encoding
            FailureReason.GENERIC -> R.string.import_error_generic
        },
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ImportTestTags.FAILED),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        Spacer(Modifier.padding(8.dp))
        OutlinedButton(onClick = onPickAnotherFile) {
            Text(stringResource(R.string.import_error_retry))
        }
    }
}

@Composable
private fun ResultContent(state: ImportUiState.Result, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(ImportTestTags.RESULT),
    ) {
        Text(stringResource(R.string.import_result_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(4.dp))
        Text(stringResource(R.string.import_result_new, state.newCount))
        if (state.duplicatesSkipped > 0) {
            Text(stringResource(R.string.import_result_duplicates, state.duplicatesSkipped))
        }
        if (state.failedRows.isNotEmpty()) {
            Text(stringResource(R.string.import_result_failed, state.failedRows.size))
        }
        Spacer(Modifier.padding(12.dp))
        Button(onClick = onDone, modifier = Modifier.testTag(ImportTestTags.DONE_BUTTON)) {
            Text(stringResource(R.string.import_result_done))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PreviewContent(
    state: ImportUiState.Preview,
    onCorrectionRequested: (MappingField) -> Unit,
    onFixColumnsByHandRequested: () -> Unit,
    onCorrectionDismissed: () -> Unit,
    onDateFormatSelected: (String) -> Unit,
    onAmountColumnsSwapSelected: (Int, Int) -> Unit,
    onRawGridMappingApplied: (ColumnMapping) -> Unit,
    onImportConfirmed: () -> Unit,
) {
    val preview = state.preview
    Column(modifier = Modifier.fillMaxSize()) {
        if (preview.mapping == null) {
            // Total inference failure: the raw grid IS the screen, not a sheet
            // over it -- there's no confident mapping underneath to preview.
            RawGridEditor(
                rows = preview.sampleRows.map(PreviewRow::rawCells),
                onApply = onRawGridMappingApplied,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ImportTestTags.RAW_GRID),
            )
            return@Column
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.import_preview_count, preview.sampleRows.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(ImportTestTags.PREVIEW_COUNT),
            )

            if (preview.uncertainFields.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.import_preview_needs_review),
                    modifier = Modifier.testTag(ImportTestTags.UNCERTAIN_BANNER),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    preview.uncertainFields.forEach { field ->
                        AssistChip(
                            onClick = { onCorrectionRequested(field) },
                            label = { Text(labelFor(field)) },
                            modifier = Modifier.testTag(ImportTestTags.uncertainChip(field)),
                        )
                    }
                }
            }

            if (preview.unmappedColumns.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.import_unmapped_columns_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag(ImportTestTags.UNMAPPED_COLUMNS),
                )
                preview.unmappedColumns.forEach { column ->
                    Text(stringResource(R.string.import_unmapped_columns_body, column))
                }
            }

            TextButton(
                onClick = onFixColumnsByHandRequested,
                modifier = Modifier.testTag(ImportTestTags.FIX_MANUALLY_BUTTON),
            ) {
                Text(stringResource(R.string.import_preview_fix_manually))
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).testTag(ImportTestTags.SAMPLE_LIST)) {
            items(preview.sampleRows) { row ->
                ListItem(
                    headlineContent = { Text(row.description ?: row.rawCells.joinToString(" · ")) },
                    supportingContent = row.date?.let { { Text(it.toString()) } },
                    trailingContent = row.amount?.let { { Text(it.minorUnits.toString()) } },
                )
            }
        }

        Button(
            onClick = onImportConfirmed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(ImportTestTags.CONFIRM_BUTTON),
        ) {
            Text(stringResource(R.string.import_preview_confirm_button))
        }
    }

    when (val correction = state.activeCorrection) {
        is CorrectionTarget.DateFormat -> ModalBottomSheet(onDismissRequest = onCorrectionDismissed) {
            DateFormatCorrectionSheet(correction, onDateFormatSelected)
        }

        is CorrectionTarget.AmountColumns -> ModalBottomSheet(onDismissRequest = onCorrectionDismissed) {
            AmountColumnsCorrectionSheet(correction, onAmountColumnsSwapSelected)
        }

        CorrectionTarget.RawGrid -> ModalBottomSheet(onDismissRequest = onCorrectionDismissed) {
            RawGridEditor(
                rows = preview.sampleRows.map(PreviewRow::rawCells),
                onApply = onRawGridMappingApplied,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ImportTestTags.RAW_GRID),
            )
        }

        null -> Unit
    }
}

@Composable
private fun labelFor(field: MappingField): String = stringResource(
    when (field) {
        MappingField.DATE_FORMAT -> R.string.import_uncertain_date_format
        MappingField.AMOUNT_COLUMNS -> R.string.import_uncertain_amount_columns
        MappingField.DESCRIPTION_COLUMN -> R.string.import_uncertain_description_column
        MappingField.DATE_COLUMN -> R.string.import_uncertain_date_column
        MappingField.AMOUNT_SIGN -> R.string.import_uncertain_amount_columns
    },
)

@Composable
private fun DateFormatCorrectionSheet(
    target: CorrectionTarget.DateFormat,
    onDateFormatSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.import_date_format_sheet_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.import_date_format_sheet_body))
        Spacer(Modifier.padding(8.dp))
        DateFormatOptionRow(target.optionA, onDateFormatSelected, ImportTestTags.DATE_FORMAT_OPTION_A)
        DateFormatOptionRow(target.optionB, onDateFormatSelected, ImportTestTags.DATE_FORMAT_OPTION_B)
    }
}

@Composable
private fun DateFormatOptionRow(option: DateFormatOption, onSelected: (String) -> Unit, testTag: String) {
    val text = if (option.failingRowCount == 0) {
        stringResource(R.string.import_date_format_option_clean, option.dateFormat)
    } else {
        stringResource(R.string.import_date_format_option_with_failures, option.dateFormat, option.failingRowCount)
    }
    OutlinedButton(
        onClick = { onSelected(option.dateFormat) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(testTag),
    ) {
        Text(text)
    }
}

@Composable
private fun AmountColumnsCorrectionSheet(
    target: CorrectionTarget.AmountColumns,
    onSwapSelected: (Int, Int) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.import_amount_sheet_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(8.dp))
        OutlinedButton(
            onClick = { onSwapSelected(target.debitColumn, target.creditColumn) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ImportTestTags.AMOUNT_OPTION_A),
        ) {
            Text(stringResource(R.string.import_amount_sheet_option_a, target.debitColumn + 1, target.debitSample))
        }
        OutlinedButton(
            onClick = { onSwapSelected(target.creditColumn, target.debitColumn) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(ImportTestTags.AMOUNT_OPTION_B),
        ) {
            Text(stringResource(R.string.import_amount_sheet_option_b, target.creditColumn + 1, target.creditSample))
        }
    }
}

internal object ImportTestTags {
    const val IDLE = "import:idle"
    const val PICK_FILE_BUTTON = "import:pick_file"
    const val READING = "import:reading"
    const val IMPORTING = "import:importing"
    const val FAILED = "import:failed"
    const val RESULT = "import:result"
    const val DONE_BUTTON = "import:done"
    const val PREVIEW_COUNT = "import:preview_count"
    const val UNCERTAIN_BANNER = "import:uncertain_banner"
    const val UNMAPPED_COLUMNS = "import:unmapped_columns"
    const val FIX_MANUALLY_BUTTON = "import:fix_manually"
    const val SAMPLE_LIST = "import:sample_list"
    const val CONFIRM_BUTTON = "import:confirm"
    const val RAW_GRID = "import:raw_grid"
    const val RAW_GRID_APPLY = "import:raw_grid:apply"
    const val DATE_FORMAT_OPTION_A = "import:date_format:a"
    const val DATE_FORMAT_OPTION_B = "import:date_format:b"
    const val AMOUNT_OPTION_A = "import:amount:a"
    const val AMOUNT_OPTION_B = "import:amount:b"
    fun uncertainChip(field: MappingField) = "import:uncertain_chip:${field.name}"
}
