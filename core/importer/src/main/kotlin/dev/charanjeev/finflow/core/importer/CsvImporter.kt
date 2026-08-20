package dev.charanjeev.finflow.core.importer

import dev.charanjeev.finflow.core.model.Transaction

/**
 * M2 lives here. The interface is committed now so the module boundary is real
 * from day one and the milestone has somewhere obvious to land.
 *
 * The hard part is [ColumnMapping] inference: bank CSVs disagree about column
 * order, date format, whether debits are negative or in a separate column, and
 * whether the file has a preamble before the header row.
 */
interface CsvImporter {
    suspend fun preview(csv: String): ImportPreview
    suspend fun import(csv: String, mapping: ColumnMapping, accountId: String): ImportResult
}

data class ColumnMapping(
    val dateColumn: Int,
    val descriptionColumn: Int,
    val amountColumn: Int?,
    val debitColumn: Int?,
    val creditColumn: Int?,
    val dateFormat: String,
    val headerRowIndex: Int,
) {
    init {
        require(amountColumn != null || (debitColumn != null && creditColumn != null)) {
            "Need either a single amount column or a debit/credit pair"
        }
    }
}

data class ImportPreview(
    val inferredMapping: ColumnMapping?,
    val confidence: Float,
    val sampleRows: List<List<String>>,
    val warnings: List<String>,
)

data class ImportResult(
    val imported: List<Transaction>,
    val duplicatesSkipped: Int,
    val failedRows: List<FailedRow>,
)

data class FailedRow(val lineNumber: Int, val raw: String, val reason: String)
