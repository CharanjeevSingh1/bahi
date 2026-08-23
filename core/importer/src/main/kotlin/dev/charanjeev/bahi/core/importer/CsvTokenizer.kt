package dev.charanjeev.bahi.core.importer

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.StringReader

/**
 * One row exactly as parsed from the source file. [index] is the row's
 * 0-based position among parsed records, not a guaranteed physical line
 * number -- a quoted field spanning multiple lines is still one record, so
 * the two can diverge. That's an inherent CSV property, not something this
 * tokenizer tries to paper over.
 */
internal data class CsvRow(val index: Int, val cells: List<String>)

/**
 * Blank lines are kept as a single-empty-cell row rather than dropped, so
 * [CsvRow.index] still lines up with what a preamble or header row looks
 * like to inference (docs/csv-import-design.md §2) instead of silently
 * shifting every row after a blank line up by one.
 */
private val FORMAT: CSVFormat = CSVFormat.Builder.create(CSVFormat.RFC4180)
    .setIgnoreEmptyLines(false)
    .build()

/**
 * Turns raw file contents into rows of cells, handling RFC 4180 quoting --
 * including a comma or a literal newline inside a quoted field -- via
 * Commons CSV (§8). The one thing the library doesn't do is strip a leading
 * UTF-8 BOM, which Excel writes on save-as-CSV and would otherwise corrupt
 * the first cell of the first row.
 */
internal fun tokenizeCsv(csv: String): List<CsvRow> {
    val withoutBom = csv.removePrefix("\uFEFF")
    return CSVParser.parse(StringReader(withoutBom), FORMAT).use { parser ->
        parser.records.mapIndexed { index, record -> CsvRow(index, record.toList()) }
    }
}
