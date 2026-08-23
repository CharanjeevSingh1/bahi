package dev.charanjeev.bahi.feature.csvimport

import dev.charanjeev.bahi.core.importer.FailedRow
import dev.charanjeev.bahi.core.importer.ImportPreview
import dev.charanjeev.bahi.core.importer.ImportResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

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
     */
    data class Result(
        val newCount: Int,
        val duplicatesSkipped: Int,
        val failedRows: ImmutableList<FailedRow>,
    ) : ImportUiState {
        companion object {
            fun from(result: ImportResult) = Result(
                newCount = result.imported.size - result.duplicatesSkipped,
                duplicatesSkipped = result.duplicatesSkipped,
                failedRows = result.failedRows.toImmutableList(),
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
