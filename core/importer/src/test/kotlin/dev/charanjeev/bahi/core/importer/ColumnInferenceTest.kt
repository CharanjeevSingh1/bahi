package dev.charanjeev.bahi.core.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exercises inferColumnMapping against the fixture files in
 * src/test/resources/csv/, covering fixture cases 1, 2, 3, 4, 8, 17 from
 * docs/csv-import-design.md §9, plus the balance-detection robustness and
 * uncertain-amount-structure cases called out separately from that list --
 * date-format disambiguation (cases 5, 6, 7) is deliberately out of scope
 * here; that's slice 4.
 */
class ColumnInferenceTest {

    @Test
    fun `case 1 -- single amount column with header and ISO dates confidently resolves except sign`() {
        val result = infer("clean-single-amount-header-iso.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.headerRowIndex).isEqualTo(0)
        assertThat(mapping.firstDataRowIndex).isEqualTo(1)
        assertThat(mapping.dateColumn).isEqualTo(0)
        assertThat(mapping.dateFormat).isEqualTo("yyyy-MM-dd")
        assertThat(mapping.descriptionColumn).isEqualTo(1)
        assertThat(mapping.amountColumn).isEqualTo(2)
        // Not corroborated by a balance column, so this is a best guess --
        // AMOUNT_SIGN must be flagged uncertain even though the guess
        // happens to be right for this fixture.
        assertThat(result.uncertainFields).containsExactly(MappingField.AMOUNT_SIGN)
    }

    @Test
    fun `case 2 -- debit-credit pair with header text resolves with zero uncertain fields`() {
        val result = infer("debit-credit-with-header.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.debitColumn).isEqualTo(2)
        assertThat(mapping.creditColumn).isEqualTo(3)
        assertThat(mapping.amountColumn).isNull()
        assertThat(mapping.descriptionColumn).isEqualTo(1)
        assertThat(result.uncertainFields).isEmpty()
    }

    @Test
    fun `case 3 -- no header row starts data at row 0`() {
        val result = infer("no-header-row.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.headerRowIndex).isNull()
        assertThat(mapping.firstDataRowIndex).isEqualTo(0)
    }

    @Test
    fun `case 4 -- preamble rows before the header are skipped, not treated as data`() {
        val result = infer("preamble-before-header.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.headerRowIndex).isEqualTo(3)
        assertThat(mapping.firstDataRowIndex).isEqualTo(4)
    }

    @Test
    fun `case 8 -- a clean running balance is excluded from amountColumn and lands in unmappedColumns`() {
        val result = infer("trailing-balance-column.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.amountColumn).isEqualTo(2)
        assertThat(result.unmappedColumns).containsExactly(3)
        // The balance relation corroborates the sign too: this is the one
        // single-amount-column case that should NOT need AMOUNT_SIGN
        // confirmed, unlike case 1.
        assertThat(mapping.amountSign).isEqualTo(AmountSign.NEGATIVE_IS_DEBIT)
        assertThat(result.uncertainFields).isEmpty()
    }

    @Test
    fun `balance detection tolerates one non-matching pair out of several`() {
        // "Mystery Adjustment" deliberately breaks the exact balance delta
        // for one row, simulating a dropped row or an untracked fee -- 4 of
        // 5 checkable pairs still match, above the match-rate floor.
        val result = infer("balance-with-gap.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.amountColumn).isEqualTo(2)
        assertThat(result.unmappedColumns).containsExactly(3)
        assertThat(result.uncertainFields).isEmpty()
    }

    @Test
    fun `balance detection uses only the pairs where the balance cell is present`() {
        // Balance is blank for the first two rows -- those pairs aren't
        // checkable at all (skipped, not counted as a miss), but the four
        // pairs after that are enough evidence on their own.
        val result = infer("balance-starts-mid-file.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.amountColumn).isEqualTo(2)
        assertThat(result.unmappedColumns).containsExactly(3)
        assertThat(result.uncertainFields).isEmpty()
    }

    @Test
    fun `two money-shaped columns with no balance relation and no debit-credit exclusivity are left uncertain`() {
        val result = infer("two-amount-columns-no-clear-role.csv")

        assertThat(result.uncertainFields).containsExactly(MappingField.AMOUNT_COLUMNS, MappingField.AMOUNT_SIGN)
        // Still a best-guess mapping, not null -- there IS a plausible
        // amount column, just not a confirmed one.
        assertThat(result.mapping).isNotNull()
    }

    @Test
    fun `debit-credit pair without a header confirms the pair but not which side is which`() {
        val result = infer("debit-credit-without-header.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.debitColumn).isNotNull()
        assertThat(mapping.creditColumn).isNotNull()
        assertThat(result.uncertainFields).containsExactly(MappingField.AMOUNT_COLUMNS)
    }

    @Test
    fun `case 17 -- European and Indian amount formats in the same column don't confuse amount detection`() {
        val result = infer("european-and-indian-amount-formats.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.amountColumn).isEqualTo(2)
        assertThat(mapping.descriptionColumn).isEqualTo(1)
    }

    @Test
    fun `a numeric ambiguous date column is still confidently identified, but its format is not`() {
        // Column identification only needs "does this look date-shaped at
        // all" (§2) -- 03/04/2026 satisfies that regardless of which
        // ordering is right, so DATE_COLUMN itself stays confident here.
        // Which format applies is explicitly slice 4's job; this pins down
        // that the placeholder dateFormat this returns in the meantime is
        // never presented without DATE_FORMAT also being flagged uncertain.
        val result = infer("numeric-ambiguous-dates.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateColumn).isEqualTo(0)
        assertThat(result.uncertainFields).containsExactly(MappingField.DATE_FORMAT, MappingField.AMOUNT_SIGN)
    }

    @Test
    fun `a file with nothing date- or money-shaped is total inference failure`() {
        val result = infer("unparseable-garbage.csv")

        assertThat(result.mapping).isNull()
    }

    @Test
    fun `two columns can't hold date, description and amount, so mapping is null`() {
        val result = infer("too-few-columns.csv")

        assertThat(result.mapping).isNull()
    }

    private fun infer(fixtureName: String): InferredMapping =
        inferColumnMapping(tokenizeCsv(loadCsvFixture(fixtureName)))
}

private fun loadCsvFixture(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/csv/$name")) { "Missing fixture: $name" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
