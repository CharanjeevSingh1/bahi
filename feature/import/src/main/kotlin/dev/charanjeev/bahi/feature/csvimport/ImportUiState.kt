package dev.charanjeev.bahi.feature.csvimport

import dev.charanjeev.bahi.core.importer.ImportPreview
import dev.charanjeev.bahi.core.importer.ImportResult

/**
 * A sealed state, not a data class with nullable fields, for the same reason
 * TransactionsUiState is one: Idle/Reading/Preview/Importing/Result/Failed
 * need to be distinguishable, not inferred from which fields happen to be
 * null.
 */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Reading : ImportUiState

    data class Preview(
        val fileName: String,
        val preview: ImportPreview,
        /** Non-null while a correction sheet is open; which one is [CorrectionTarget]. */
        val activeCorrection: CorrectionTarget? = null,
    ) : ImportUiState

    data object Importing : ImportUiState

    /**
     * [newCount] is deliberately not `result.imported.size` -- that count
     * includes rows the repository recognised as duplicates and didn't
     * actually write (see [ImportResult.imported]'s own doc). Rendering it
     * directly would overcount a "18 imported" summary by however many of
     * those 18 already existed.
     *
     * [failedRowCount] rather than the full row list: this screen has only
     * ever rendered the count (the per-row detail is Preview's job, before
     * import). A bare Int is also what makes this state cheaply survivable
     * across process death -- see [batchId]'s doc.
     *
     * [batchId] is only ever meaningful on this screen: it's not persisted
     * anywhere else the app can look it back up later, so undo is offered
     * here and nowhere else (see docs/csv-import-design.md §11.1 -- there's
     * no import-batch table to list past imports from). Every field here is
     * a plain String/Int, deliberately -- ImportViewModel mirrors this whole
     * state into SavedStateHandle, and only primitive types survive process
     * death that way without a Parcelable detour nothing else needs.
     *
     * [undoneCount] is null until undo has actually run, and holds the real
     * number of rows removed once it has -- not [newCount] again, since a
     * hand-edited row can make the two diverge (TransactionDao.update's
     * doc). Null vs. non-null is also what gates the button from firing
     * twice.
     */
    data class Result(
        val batchId: String,
        val newCount: Int,
        val duplicatesSkipped: Int,
        val failedRowCount: Int,
        val undoneCount: Int? = null,
    ) : ImportUiState {
        companion object {
            fun from(result: ImportResult) = Result(
                batchId = result.batchId,
                newCount = result.imported.size - result.duplicatesSkipped,
                duplicatesSkipped = result.duplicatesSkipped,
                failedRowCount = result.failedRows.size,
            )
        }
    }

    data class Failed(val reason: FailureReason) : ImportUiState
}

enum class FailureReason { TOO_LARGE, NOT_TEXT, ENCODING, GENERIC }

/**
 * Which uncertain field the open correction sheet is resolving, carrying
 * whatever that sheet needs to render -- computed once, up front, via
 * CsvImporter.preview(csv, mapping) per candidate, rather than the sheet
 * composable doing its own suspend work while open.
 */
sealed interface CorrectionTarget {
    /**
     * [failingRowCount] on both options together is what makes a silently
     * ambiguous date column (§2) look different from a genuinely
     * contradictory one from the sheet alone: both zero means either choice
     * is safe, both nonzero means neither format parses the whole column
     * and the user is picking the lesser problem, not a clean answer.
     */
    data class DateFormat(val optionA: DateFormatOption, val optionB: DateFormatOption) : CorrectionTarget

    data class AmountColumns(
        val debitColumn: Int,
        val debitSample: String,
        val creditColumn: Int,
        val creditSample: String,
    ) : CorrectionTarget

    data object RawGrid : CorrectionTarget
}

data class DateFormatOption(val dateFormat: String, val failingRowCount: Int)
