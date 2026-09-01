package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.data.repository.AutoCategoriser
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** Matches the app's existing single-currency assumption (TransactionFormViewModel.DEFAULT_CURRENCY_CODE). */
private const val DEFAULT_CURRENCY_CODE = "INR"

/**
 * Wires the tokenizer and inference engine into the CsvImporter contract,
 * and is the one place that turns an [InferredMapping]/[ColumnMapping] into
 * actual [Transaction]s. [mapRow] is shared between [preview] and [import]
 * deliberately: if they used separately-written parsing logic, they could
 * disagree about which rows are parseable, which would defeat the point of
 * showing a preview before committing.
 */
class DefaultCsvImporter @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val autoCategoriser: AutoCategoriser,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CsvImporter {

    override suspend fun preview(csv: String): ImportPreview = withContext(ioDispatcher) {
        val rows = tokenizeCsv(csv)
        val inference = inferColumnMapping(rows)
        val mapping = inference.mapping

        val sampleRows = if (mapping == null) {
            // Total inference failure: the raw-grid fallback (§3) needs every
            // row's cells, not a small sample, since there's no mapping yet
            // to know which rows even matter.
            rows.map { PreviewRow(rawCells = it.cells, date = null, description = null, amount = null) }
        } else {
            previewRowsFor(rows, mapping, inference.uncertainFields)
        }

        ImportPreview(
            mapping = mapping,
            uncertainFields = inference.uncertainFields,
            sampleRows = sampleRows,
            unmappedColumns = inference.unmappedColumns,
            headerCells = inference.headerCells,
            warnings = emptyList(),
        )
    }

    override suspend fun preview(csv: String, mapping: ColumnMapping): ImportPreview = withContext(ioDispatcher) {
        val rows = tokenizeCsv(csv)
        ImportPreview(
            mapping = mapping,
            uncertainFields = emptySet(),
            sampleRows = previewRowsFor(rows, mapping, uncertainFields = emptySet()),
            unmappedColumns = unmappedColumnsFor(rows, mapping),
            headerCells = mapping.headerRowIndex?.let { rows.getOrNull(it)?.cells },
            warnings = emptyList(),
        )
    }

    private fun previewRowsFor(
        rows: List<CsvRow>,
        mapping: ColumnMapping,
        uncertainFields: Set<MappingField>,
    ): List<PreviewRow> {
        val dateFormatter = dateFormatterFor(mapping, uncertainFields)
        return rows.drop(mapping.firstDataRowIndex).map { row ->
            when (val mapped = mapRow(row, mapping, dateFormatter)) {
                is RowMapping.Mapped -> PreviewRow(row.cells, mapped.date, mapped.description, mapped.amount)
                is RowMapping.Failed -> PreviewRow(row.cells, null, null, null)
            }
        }
    }

    private fun unmappedColumnsFor(rows: List<CsvRow>, mapping: ColumnMapping): List<Int> {
        val columnCount = rows.maxOfOrNull { it.cells.size } ?: 0
        val used = setOfNotNull(
            mapping.dateColumn,
            mapping.descriptionColumn,
            mapping.amountColumn,
            mapping.debitColumn,
            mapping.creditColumn,
            mapping.signColumn,
        )
        return (0 until columnCount).filterNot { it in used }
    }

    override suspend fun import(csv: String, mapping: ColumnMapping, accountId: String): ImportResult =
        withContext(ioDispatcher) {
            val dateFormatter = dateFormatterFor(mapping, uncertainFields = emptySet())
            val now = clock.now()

            val mapped = mutableListOf<Transaction>()
            val failed = mutableListOf<FailedRow>()

            for (row in tokenizeCsv(csv).drop(mapping.firstDataRowIndex)) {
                when (val result = mapRow(row, mapping, dateFormatter)) {
                    is RowMapping.Mapped -> mapped += Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = result.amount,
                        currencyCode = DEFAULT_CURRENCY_CODE,
                        date = result.date,
                        description = result.description,
                        merchant = null,
                        categoryId = null,
                        accountId = accountId,
                        source = TransactionSource.CSV_IMPORT,
                        notes = null,
                        categoryLockedByUser = false,
                        createdAt = now,
                        updatedAt = now,
                    )

                    is RowMapping.Failed -> failed += FailedRow(
                        // 0-based CsvRow.index -> 1-based, matching how a
                        // reviewer would count rows opening the file directly.
                        lineNumber = row.index + 1,
                        raw = row.cells.joinToString(", "),
                        reason = result.reason,
                    )
                }
            }

            // The DAO's de-duplication is count-aware (docs/csv-import-design.md
            // §4) and now status-aware too (docs/sync-design.md §6.1, slice 9b):
            // it returns which of `mapped` it actually inserted and why each
            // skip was skipped, which is the only trustworthy source for both
            // counts below. Re-deriving "is this a duplicate" here -- e.g. by
            // checking existing hashes independently -- would reintroduce
            // presence-based counting at a second layer, exactly the bug §4
            // fixed at the DAO layer.
            val batchResult = transactionRepository.importAll(mapped)

            // Order matters: rules run after de-duplication, over the rows
            // that were actually written, never over everything parsed. A row
            // de-duplicated away has no database row of its own -- the copy
            // already in the table is a different row with a different id --
            // so categorising `mapped` wholesale would report recategorising
            // transactions this import never created. The existing copy is
            // deliberately left alone too: it may carry a category the user
            // chose, and a re-import is not a reason to revisit that.
            val insertedIds = batchResult.insertedIds.toSet()
            val autoCategorised = autoCategoriser.categorise(mapped.filter { it.id in insertedIds })

            ImportResult(
                imported = mapped,
                duplicatesSkipped = batchResult.duplicatesSkipped,
                previouslyDeletedSkipped = batchResult.previouslyDeletedSkipped,
                failedRows = failed,
                batchId = batchResult.batchId,
                autoCategorisedCount = autoCategorised,
            )
        }

    /**
     * Null when the format is a placeholder rather than a real pattern --
     * always true when DATE_FORMAT is uncertain (§1's ColumnMapping doc), and
     * checked by that flag rather than by comparing against the placeholder
     * string, since the flag is the authoritative signal and the placeholder
     * is just an implementation detail of what value fills the field
     * meanwhile. A null formatter fails every row's date parse the same way
     * a genuinely invalid pattern would -- there's no separate case to
     * handle, since [import] should only ever be called with a mapping whose
     * uncertain fields the user has already resolved (§3's correction flow).
     */
    private fun dateFormatterFor(mapping: ColumnMapping, uncertainFields: Set<MappingField>): DateTimeFormatter? {
        if (MappingField.DATE_FORMAT in uncertainFields) return null
        return runCatching { DateTimeFormatter.ofPattern(mapping.dateFormat, Locale.ENGLISH) }.getOrNull()
    }
}

private sealed interface RowMapping {
    data class Mapped(val date: LocalDate, val description: String, val amount: Money) : RowMapping
    data class Failed(val reason: String) : RowMapping
}

private fun mapRow(row: CsvRow, mapping: ColumnMapping, dateFormatter: DateTimeFormatter?): RowMapping {
    val requiredColumns = listOfNotNull(
        mapping.dateColumn,
        mapping.descriptionColumn,
        mapping.amountColumn,
        mapping.debitColumn,
        mapping.creditColumn,
        mapping.signColumn,
    ).max() + 1
    if (row.cells.size < requiredColumns) {
        return RowMapping.Failed("expected at least $requiredColumns columns, found ${row.cells.size}")
    }

    if (dateFormatter == null) {
        return RowMapping.Failed("date format \"${mapping.dateFormat}\" is not confirmed")
    }
    val date = runCatching {
        java.time.LocalDate.parse(row.cells[mapping.dateColumn].trim(), dateFormatter).toKotlinLocalDate()
    }.getOrElse { return RowMapping.Failed("unparseable date \"${row.cells[mapping.dateColumn]}\"") }

    val amount = resolveAmount(row, mapping)
        ?: return RowMapping.Failed("unparseable or ambiguous amount")

    return RowMapping.Mapped(date, row.cells[mapping.descriptionColumn].trim(), amount)
}

/**
 * Mirrors the structure resolveAmountStructure (ColumnInference.kt) already
 * decided at inference time; this just applies it row by row. SIGN_COLUMN
 * isn't produced by inference yet (§8's documented gap), but a mapping built
 * by the manual raw-grid fallback (§3) can specify one directly, so it's
 * handled here regardless.
 */
private fun resolveAmount(row: CsvRow, mapping: ColumnMapping): Money? {
    if (mapping.amountColumn != null) {
        val raw = Money.parse(row.cells[mapping.amountColumn]) ?: return null
        return when (mapping.amountSign) {
            AmountSign.NEGATIVE_IS_DEBIT -> raw
            AmountSign.POSITIVE_IS_DEBIT -> -raw
            AmountSign.SIGN_COLUMN -> {
                val marker = row.cells[mapping.signColumn!!].trim().lowercase()
                when {
                    marker.startsWith("d") || marker.contains("dr") -> -raw.absolute
                    marker.startsWith("c") || marker.contains("cr") -> raw.absolute
                    else -> null
                }
            }

            null -> null
        }
    }

    val debit = Money.parse(row.cells[mapping.debitColumn!!])
    val credit = Money.parse(row.cells[mapping.creditColumn!!])
    return when {
        debit != null && credit == null -> -debit.absolute
        credit != null && debit == null -> credit.absolute
        else -> null // both populated or neither -- ambiguous, not a transaction to guess at
    }
}
