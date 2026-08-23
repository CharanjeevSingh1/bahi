package dev.charanjeev.bahi.core.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixture cases 3, 9, 14, 15, 16 from docs/csv-import-design.md §9 -- the
 * quoting/dialect properties this tokenizer, not the inference layer, is
 * responsible for.
 */
class CsvTokenizerTest {

    @Test
    fun `tokenizes a file with no header row`() {
        val csv = "2026-01-01,Coffee,-500\n2026-01-02,Salary,50000"

        val rows = tokenizeCsv(csv)

        assertThat(rows).containsExactly(
            CsvRow(0, listOf("2026-01-01", "Coffee", "-500")),
            CsvRow(1, listOf("2026-01-02", "Salary", "50000")),
        ).inOrder()
    }

    @Test
    fun `unescapes a doubled quote inside a quoted field containing the delimiter`() {
        val csv = "Date,Description,Amount\n" +
            "2026-01-01,\"Coffee, \"\"Downtown\"\" Ltd\",-500"

        val rows = tokenizeCsv(csv)

        assertThat(rows[1].cells).containsExactly(
            "2026-01-01", "Coffee, \"Downtown\" Ltd", "-500",
        ).inOrder()
    }

    @Test
    fun `handles CRLF line endings without leaking carriage returns into cells`() {
        val csv = "Date,Amount\r\n2026-01-01,-500\r\n2026-01-02,-600\r\n"

        val rows = tokenizeCsv(csv)

        assertThat(rows).containsExactly(
            CsvRow(0, listOf("Date", "Amount")),
            CsvRow(1, listOf("2026-01-01", "-500")),
            CsvRow(2, listOf("2026-01-02", "-600")),
        ).inOrder()
    }

    @Test
    fun `represents a trailing blank line at EOF as one empty-cell row, same as a mid-file blank line`() {
        // A single-empty-string cell (listOf("")) and no cells at all
        // (emptyList()) both render as "[]" via toString(), which made the
        // wrong version of this assertion look plausible at a glance -- the
        // actual, verified behaviour is a one-cell row, matching the
        // mid-file blank-line case below rather than differing from it.
        val csv = "Date,Amount\r\n2026-01-01,-500\r\n\r\n"

        val rows = tokenizeCsv(csv)

        assertThat(rows).containsExactly(
            CsvRow(0, listOf("Date", "Amount")),
            CsvRow(1, listOf("2026-01-01", "-500")),
            CsvRow(2, listOf("")),
        ).inOrder()
    }

    @Test
    fun `keeps a blank line in the middle of the file as its own row`() {
        val csv = "Account: Checking\n\nDate,Amount\n2026-01-01,-500"

        val rows = tokenizeCsv(csv)

        assertThat(rows).containsExactly(
            CsvRow(0, listOf("Account: Checking")),
            CsvRow(1, listOf("")),
            CsvRow(2, listOf("Date", "Amount")),
            CsvRow(3, listOf("2026-01-01", "-500")),
        ).inOrder()
    }

    @Test
    fun `treats a literal newline inside a quoted field as part of one record, not two`() {
        val csv = "Date,Description,Amount\n2026-01-01,\"Coffee\nDowntown\",-500"

        val rows = tokenizeCsv(csv)

        assertThat(rows).hasSize(2)
        assertThat(rows[1].cells).containsExactly(
            "2026-01-01", "Coffee\nDowntown", "-500",
        ).inOrder()
    }

    @Test
    fun `strips a leading UTF-8 BOM from the first cell`() {
        val csv = "﻿Date,Amount\n2026-01-01,-500"

        val rows = tokenizeCsv(csv)

        assertThat(rows[0].cells[0]).isEqualTo("Date")
    }
}
