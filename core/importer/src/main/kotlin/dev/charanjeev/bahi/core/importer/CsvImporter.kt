package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.datetime.LocalDate

/**
 * M2 lives here. The interface is committed now so the module boundary is real
 * from day one and the milestone has somewhere obvious to land.
 *
 * The hard part is [ColumnMapping] inference: bank CSVs disagree about column
 * order, date format, whether debits are negative or in a separate column, and
 * whether the file has a preamble before the header row. See
 * docs/csv-import-design.md for the inference approach, the preview/correction
 * flow, and why this shape looks the way it does.
 */
interface CsvImporter {
    suspend fun preview(csv: String): ImportPreview
    suspend fun import(csv: String, mapping: ColumnMapping, accountId: String): ImportResult
}

/**
 * [headerRowIndex] is nullable because some exports have no header row at all;
 * [firstDataRowIndex] is tracked separately from it because a header, when one
 * exists, isn't necessarily the row directly above the data -- some exports
 * have a preamble (account name, statement period, a blank line) above it.
 */
data class ColumnMapping(
    val headerRowIndex: Int?,
    val firstDataRowIndex: Int,
    val dateColumn: Int,
    val dateFormat: String,
    val descriptionColumn: Int,
    val amountColumn: Int?,
    val amountSign: AmountSign?,
    val signColumn: Int?,
    val debitColumn: Int?,
    val creditColumn: Int?,
) {
    init {
        val hasSingleAmount = amountColumn != null
        val hasDebitCredit = debitColumn != null && creditColumn != null
        require(hasSingleAmount != hasDebitCredit) {
            "Need exactly one of a single amount column or a debit/credit pair"
        }
        if (hasSingleAmount) {
            requireNotNull(amountSign) { "amountSign is required when amountColumn is set" }
            require((amountSign == AmountSign.SIGN_COLUMN) == (signColumn != null)) {
                "signColumn is required exactly when amountSign is SIGN_COLUMN, and only then"
            }
        } else {
            require(amountSign == null && signColumn == null) {
                "amountSign/signColumn only apply to a single amount column, not a debit/credit pair"
            }
        }
    }
}

/**
 * How a single [ColumnMapping.amountColumn] encodes which side of the ledger a
 * row is on. Doesn't apply to a debit/credit pair, which encodes it by which
 * of the two columns is populated instead.
 */
enum class AmountSign {
    NEGATIVE_IS_DEBIT,
    POSITIVE_IS_DEBIT,

    /** The sign convention itself is in [ColumnMapping.signColumn], e.g. a "Dr"/"Cr" column. */
    SIGN_COLUMN,
}

/**
 * A role inference couldn't confidently resolve. Named per-field, not as a
 * single confidence score, so the correction UI can ask about only the part
 * that's actually uncertain instead of re-confirming everything.
 */
enum class MappingField { DATE_COLUMN, DATE_FORMAT, DESCRIPTION_COLUMN, AMOUNT_SIGN }

/**
 * [mapping] is null only on total inference failure -- no data rows found, or
 * no date/amount column identifiable at all -- which is the floor the raw
 * column-picker UI falls back to. Short of that, [mapping] is always the best
 * guess, and [uncertainFields] says which parts of it need confirming rather
 * than blocking the whole preview on a picker.
 */
data class ImportPreview(
    val mapping: ColumnMapping?,
    val uncertainFields: Set<MappingField>,
    val sampleRows: List<PreviewRow>,
    /** Columns inference recognised but deliberately didn't map, e.g. a detected running balance. */
    val unmappedColumns: List<Int>,
    val warnings: List<String>,
)

/**
 * A sample row shown to the user as a mapped transaction, not raw cells --
 * the user reviews what will be imported, not which column index is which.
 * [rawCells] is kept alongside so the same row can drive the raw-grid
 * fallback when [ImportPreview.mapping] is null or the user asks to see it.
 * A null [date]/[description]/[amount] means this particular sample row
 * didn't parse under the current mapping, not that the field is absent.
 */
data class PreviewRow(
    val rawCells: List<String>,
    val date: LocalDate?,
    val description: String?,
    val amount: Money?,
)

data class ImportResult(
    val imported: List<Transaction>,
    val duplicatesSkipped: Int,
    val failedRows: List<FailedRow>,
)

data class FailedRow(val lineNumber: Int, val raw: String, val reason: String)
