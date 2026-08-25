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
 *
 * [csv] is the fully decoded file contents, not a stream or a size-bounded
 * wrapper -- deliberately. The size cap that guards against reading an
 * unexpectedly huge file into memory (docs/csv-import-design.md §7) has to be
 * enforced before these bytes are ever decoded into a String, at the point
 * the file is opened off its content Uri; by the time [csv] exists here, that
 * memory has already been spent or the caller has already refused to spend
 * it. Re-checking a size limit on [csv] itself wouldn't prevent anything, so
 * this interface doesn't carry one -- staying decoupled from Android/SAF is
 * worth more than a redundant assertion. Enforcing the cap is the caller's
 * responsibility.
 */
interface CsvImporter {
    suspend fun preview(csv: String): ImportPreview

    /**
     * Re-renders the preview under a mapping the caller supplies directly,
     * skipping inference entirely -- this is what makes §3's correction flow
     * ("re-derive the preview from the already-parsed rows in memory, no
     * re-read of the file") actually possible: the UI already holds [csv] in
     * memory from the first [preview] call, and a correction (a fixed date
     * format, a confirmed debit/credit assignment) only needs the rows
     * re-mapped, not the file re-fetched from its content Uri or re-inferred
     * from scratch. The returned [ImportPreview.uncertainFields] is always
     * empty -- [mapping] is being trusted as given, not re-evaluated -- and
     * [ImportPreview.mapping] echoes [mapping] back unchanged.
     *
     * Also what lets a correction UI show, not just assert, the difference
     * between two kinds of date-format uncertainty (§2): calling this once
     * per candidate format and comparing how many [PreviewRow.date]s come
     * back null tells the caller whether a format is silently ambiguous
     * (every value valid under both orderings, so trying the other
     * candidate shows zero failures either way) or genuinely contradictory
     * (some rows only parse one way), which [ColumnMapping.dateFormat]'s
     * shared placeholder value can't distinguish on its own.
     */
    suspend fun preview(csv: String, mapping: ColumnMapping): ImportPreview

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
        require((debitColumn == null) == (creditColumn == null)) {
            "debitColumn and creditColumn must both be set or both be null"
        }
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
 *
 * [AMOUNT_COLUMNS] and [AMOUNT_SIGN] are deliberately separate: AMOUNT_COLUMNS
 * covers not knowing *which columns* play which amount-related role -- is
 * there a balance column at all, and for a debit/credit pair, which one is
 * which -- while AMOUNT_SIGN covers not knowing how a single, already-
 * identified amount column encodes its polarity. A file can have one
 * uncertain without the other.
 */
enum class MappingField { DATE_COLUMN, DATE_FORMAT, DESCRIPTION_COLUMN, AMOUNT_COLUMNS, AMOUNT_SIGN }

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
    /** The header row's cells, when [ColumnMapping.headerRowIndex] is non-null -- lets the UI name an unmapped column by its own header text instead of a bare index. */
    val headerCells: List<String>?,
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

/**
 * [imported] is every row that successfully mapped to a [Transaction], not
 * only the ones that ended up newly written to the database -- some of
 * these may have been recognised as duplicates and skipped by
 * [dev.charanjeev.bahi.core.data.repository.TransactionRepository.importAll],
 * which is what [duplicatesSkipped] counts. `imported.size - duplicatesSkipped`
 * is the number actually new.
 *
 * Which specific rows those are *is* now knowable --
 * [dev.charanjeev.bahi.core.data.repository.ImportBatchResult.insertedIds]
 * names them, and auto-categorisation depends on that, since running rules
 * over a de-duplicated row would report work against a row this import never
 * created. [ImportResult] still doesn't surface the distinction because
 * nothing displaying it needs to: the screen shows counts and offers undo.
 *
 * [batchId] is what makes undo well-defined: every row `importAll` actually
 * wrote carries it. [autoCategorisedCount] is how many of those rules then
 * gave a category to -- always counted from what the write actually changed,
 * never from how many rules matched, since a locked row is refused by
 * `applyRuleCategory` regardless of what matched it.
 */
data class ImportResult(
    val imported: List<Transaction>,
    val duplicatesSkipped: Int,
    val failedRows: List<FailedRow>,
    val batchId: String,
    val autoCategorisedCount: Int = 0,
)

data class FailedRow(val lineNumber: Int, val raw: String, val reason: String)
