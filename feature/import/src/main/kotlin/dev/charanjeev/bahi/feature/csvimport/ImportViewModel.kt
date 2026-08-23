package dev.charanjeev.bahi.feature.csvimport

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.importer.ColumnMapping
import dev.charanjeev.bahi.core.importer.CsvImporter
import dev.charanjeev.bahi.core.importer.MappingField
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Matches the app's existing single-account assumption
 * (TransactionFormViewModel.DEFAULT_ACCOUNT_ID) -- docs/csv-import-design.md
 * §8 treats this as an existing simplification, not a gap import needs to
 * solve, since there's no Account entity or picker anywhere in the app yet.
 */
private const val DEFAULT_ACCOUNT_ID = "acct-1"

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val csvImporter: CsvImporter,
    private val csvFileReader: CsvFileReader,
    private val transactionRepository: TransactionRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Restores a completed Result across process death (SavedStateHandle
    // survives it; a plain in-memory default wouldn't). Reading/Preview/
    // Importing don't get the same treatment -- csv is never persisted (see
    // its own doc), so there's nothing to rebuild a Preview from after
    // death, and re-picking the file is the accepted recovery path for that
    // (docs/csv-import-design.md §6/§7). Only Result -- a handful of plain
    // Ints and a String -- is both cheap to persist and load-bearing:
    // without it, onUndoImport's batchId would silently go stale.
    private val _uiState = MutableStateFlow<ImportUiState>(restoredResult() ?: ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val events = Channel<ImportEvent>()
    val eventFlow: Flow<ImportEvent> = events.receiveAsFlow()

    // Held here, not re-fetched: §3's correction flow re-derives the preview
    // from the already-parsed rows in memory, and §7 deliberately doesn't
    // persist the picked Uri's permission, so there's nothing to re-read
    // from even if this class wanted to.
    private var csv: String? = null

    fun onFilePicked(uriString: String) {
        _uiState.value = ImportUiState.Reading
        viewModelScope.launch {
            when (val result = csvFileReader.read(uriString)) {
                is CsvFileReadResult.Success -> {
                    csv = result.csv
                    _uiState.value = ImportUiState.Preview(result.fileName, csvImporter.preview(result.csv))
                }

                CsvFileReadResult.TooLarge -> _uiState.value = ImportUiState.Failed(FailureReason.TOO_LARGE)
                CsvFileReadResult.NotText -> _uiState.value = ImportUiState.Failed(FailureReason.NOT_TEXT)
                CsvFileReadResult.EncodingUnclear -> _uiState.value = ImportUiState.Failed(FailureReason.ENCODING)
                CsvFileReadResult.ReadFailed -> _uiState.value = ImportUiState.Failed(FailureReason.GENERIC)
            }
        }
    }

    fun onPickAnotherFile() {
        csv = null
        _uiState.value = ImportUiState.Idle
    }

    /** Opens the sheet matching whichever uncertain field the user tapped. */
    fun onCorrectionRequested(field: MappingField) {
        val state = _uiState.value as? ImportUiState.Preview ?: return
        val mapping = state.preview.mapping ?: return
        viewModelScope.launch {
            val target = when (field) {
                MappingField.DATE_FORMAT -> dateFormatTarget(state, mapping)
                MappingField.AMOUNT_COLUMNS -> amountColumnsTarget(state, mapping) ?: CorrectionTarget.RawGrid
                MappingField.DESCRIPTION_COLUMN, MappingField.DATE_COLUMN -> CorrectionTarget.RawGrid
                MappingField.AMOUNT_SIGN -> null // no dedicated sheet; a wrong guess here doesn't block import
            }
            if (target != null) _uiState.value = state.copy(activeCorrection = target)
        }
    }

    fun onFixColumnsByHandRequested() {
        val state = _uiState.value as? ImportUiState.Preview ?: return
        _uiState.value = state.copy(activeCorrection = CorrectionTarget.RawGrid)
    }

    fun onCorrectionDismissed() {
        val state = _uiState.value as? ImportUiState.Preview ?: return
        _uiState.value = state.copy(activeCorrection = null)
    }

    fun onDateFormatSelected(dateFormat: String) = applyCorrectedMapping { it.copy(dateFormat = dateFormat) }

    fun onAmountColumnsSwapSelected(debitColumn: Int, creditColumn: Int) = applyCorrectedMapping {
        it.copy(debitColumn = debitColumn, creditColumn = creditColumn)
    }

    fun onRawGridMappingApplied(mapping: ColumnMapping) = applyCorrectedMapping { mapping }

    fun onImportConfirmed() {
        val state = _uiState.value as? ImportUiState.Preview ?: return
        val mapping = state.preview.mapping ?: return
        val currentCsv = csv ?: return
        _uiState.value = ImportUiState.Importing
        viewModelScope.launch {
            val result = csvImporter.import(currentCsv, mapping, DEFAULT_ACCOUNT_ID)
            setResult(ImportUiState.Result.from(result))
        }
    }

    fun onDone() {
        events.trySend(ImportEvent.Finished)
    }

    /**
     * Guarded by [ImportUiState.Result.undoneCount] rather than just
     * disabling the button in the UI layer -- a second tap racing the
     * first's coroutine (e.g. a fast double-tap before recomposition) would
     * otherwise call undoImport twice. The second call would be a harmless
     * no-op against already-tombstoned rows (returning 0), but overwriting a
     * real removed count with that 0 is a bug this guard avoids having to
     * reason about.
     *
     * The removed count comes back from the repository, not from
     * [ImportUiState.Result.newCount] -- a row hand-edited since import no
     * longer carries this batch id (TransactionDao.update's doc) and
     * survives undo, so the two can genuinely differ. Reporting newCount
     * here would claim more was removed than actually was.
     */
    fun onUndoImport() {
        val state = _uiState.value as? ImportUiState.Result ?: return
        if (state.undoneCount != null) return
        viewModelScope.launch {
            val removedCount = transactionRepository.undoImport(state.batchId)
            setResult(state.copy(undoneCount = removedCount))
        }
    }

    private fun setResult(result: ImportUiState.Result) {
        savedStateHandle[KEY_RESULT_BATCH_ID] = result.batchId
        savedStateHandle[KEY_RESULT_NEW_COUNT] = result.newCount
        savedStateHandle[KEY_RESULT_DUPLICATES_SKIPPED] = result.duplicatesSkipped
        savedStateHandle[KEY_RESULT_FAILED_ROW_COUNT] = result.failedRowCount
        savedStateHandle[KEY_RESULT_UNDONE_COUNT] = result.undoneCount
        _uiState.value = result
    }

    private fun restoredResult(): ImportUiState.Result? {
        val batchId = savedStateHandle.get<String>(KEY_RESULT_BATCH_ID) ?: return null
        return ImportUiState.Result(
            batchId = batchId,
            newCount = savedStateHandle[KEY_RESULT_NEW_COUNT] ?: 0,
            duplicatesSkipped = savedStateHandle[KEY_RESULT_DUPLICATES_SKIPPED] ?: 0,
            failedRowCount = savedStateHandle[KEY_RESULT_FAILED_ROW_COUNT] ?: 0,
            undoneCount = savedStateHandle[KEY_RESULT_UNDONE_COUNT],
        )
    }

    private fun applyCorrectedMapping(transform: (ColumnMapping) -> ColumnMapping) {
        val state = _uiState.value as? ImportUiState.Preview ?: return
        val currentMapping = state.preview.mapping ?: return
        val currentCsv = csv ?: return
        viewModelScope.launch {
            val preview = csvImporter.preview(currentCsv, transform(currentMapping))
            _uiState.value = state.copy(preview = preview, activeCorrection = null)
        }
    }

    /**
     * Two candidate formats derived from the ambiguous column's own values
     * rather than assumed -- the separator might be "-" or "." instead of
     * "/". Trying each candidate through preview(csv, mapping) and counting
     * null dates is what turns "3 April or 4 March?" from a hardcoded
     * example into the actual outcome for this file, and is what makes a
     * genuinely contradictory column (§2) show a nonzero failure count on
     * both options instead of presenting either as a clean answer.
     */
    private suspend fun dateFormatTarget(state: ImportUiState.Preview, mapping: ColumnMapping): CorrectionTarget.DateFormat {
        val currentCsv = requireNotNull(csv)
        val separator = separatorIn(state, mapping.dateColumn)
        val dayFirst = "dd${separator}MM${separator}yyyy"
        val monthFirst = "MM${separator}dd${separator}yyyy"
        val dayFirstFailures = csvImporter.preview(currentCsv, mapping.copy(dateFormat = dayFirst))
            .sampleRows.count { it.date == null }
        val monthFirstFailures = csvImporter.preview(currentCsv, mapping.copy(dateFormat = monthFirst))
            .sampleRows.count { it.date == null }
        return CorrectionTarget.DateFormat(
            optionA = DateFormatOption(dayFirst, dayFirstFailures),
            optionB = DateFormatOption(monthFirst, monthFirstFailures),
        )
    }

    /** Might be "-" or "." instead of "/" -- read off a real sample rather than assumed. */
    private fun separatorIn(state: ImportUiState.Preview, dateColumn: Int): Char {
        val sampleCell = state.preview.sampleRows.firstNotNullOfOrNull {
            it.rawCells.getOrNull(dateColumn)?.trim()?.takeIf(String::isNotEmpty)
        }
        return sampleCell?.firstOrNull { !it.isDigit() } ?: '/'
    }

    private fun amountColumnsTarget(state: ImportUiState.Preview, mapping: ColumnMapping): CorrectionTarget.AmountColumns? {
        val debitColumn = mapping.debitColumn ?: return null
        val creditColumn = mapping.creditColumn ?: return null
        val debitSample = state.preview.sampleRows.firstNotNullOfOrNull {
            it.rawCells.getOrNull(debitColumn)?.takeIf(String::isNotBlank)
        } ?: "-"
        val creditSample = state.preview.sampleRows.firstNotNullOfOrNull {
            it.rawCells.getOrNull(creditColumn)?.takeIf(String::isNotBlank)
        } ?: "-"
        return CorrectionTarget.AmountColumns(debitColumn, debitSample, creditColumn, creditSample)
    }

    private companion object {
        const val KEY_RESULT_BATCH_ID = "importResultBatchId"
        const val KEY_RESULT_NEW_COUNT = "importResultNewCount"
        const val KEY_RESULT_DUPLICATES_SKIPPED = "importResultDuplicatesSkipped"
        const val KEY_RESULT_FAILED_ROW_COUNT = "importResultFailedRowCount"
        const val KEY_RESULT_UNDONE_COUNT = "importResultUndoneCount"
    }
}

sealed interface ImportEvent {
    data object Finished : ImportEvent
}
