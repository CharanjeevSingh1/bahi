package dev.charanjeev.bahi.core.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixture cases 5, 6 and 7 from docs/csv-import-design.md §9, plus a
 * contradictory-column case beyond that list: day-first and month-first
 * disagree about which rows are even valid dates, so neither format parses
 * the whole column. Column *identification* (which index is the date
 * column) is slice 3's job and isn't re-tested here -- these fixtures only
 * exercise resolveNumericDateFormat, the day-first/month-first rule itself.
 */
class DateFormatDisambiguationTest {

    @Test
    fun `case 5 -- no value disambiguates, so the format stays uncertain rather than guessed`() {
        // Same fixture ColumnInferenceTest already uses to prove DATE_FORMAT
        // lands in uncertainFields; re-checked here for the specific
        // placeholder value, which is this test file's actual subject.
        val result = infer("numeric-ambiguous-dates.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateFormat).isEqualTo("AMBIGUOUS")
        assertThat(result.uncertainFields).contains(MappingField.DATE_FORMAT)
    }

    @Test
    fun `case 6 -- a day-only-valid value resolves day-first with no prompt`() {
        // 15/03/2026 and 20/01/2026 both have a first component over 12,
        // which month-first can never accept -- day-first is the only
        // format that parses the whole column.
        val result = infer("date-format-day-first-unambiguous.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateFormat).isEqualTo("dd/MM/yyyy")
        assertThat(result.uncertainFields).doesNotContain(MappingField.DATE_FORMAT)
    }

    @Test
    fun `case 6 -- a month-only-valid value resolves month-first with no prompt`() {
        // The mirror image: 03/15/2026 and 01/20/2026 both have a second
        // component over 12, ruling out day-first.
        val result = infer("date-format-month-first-unambiguous.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateFormat).isEqualTo("MM/dd/yyyy")
        assertThat(result.uncertainFields).doesNotContain(MappingField.DATE_FORMAT)
    }

    @Test
    fun `case 7 -- month-name dates resolve confidently, no numeric ambiguity to begin with`() {
        val result = infer("date-format-month-name.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateFormat).isEqualTo("dd-MMM-yyyy")
        assertThat(result.uncertainFields).doesNotContain(MappingField.DATE_FORMAT)
    }

    @Test
    fun `day-first and month-first disagree about which rows are valid, so neither is picked`() {
        // 13/04/2026 is only valid day-first (04 can't be a day > 12... it
        // isn't -- it's month 13 that's invalid under day-first read as
        // month); 04/13/2026 is only valid month-first. No single format
        // parses both rows, which is a different failure than case 5's
        // "every value is silent" -- both land in the same uncertain
        // outcome on purpose, per §2: no guess, however plausible.
        val result = infer("date-format-contradictory.csv")

        val mapping = requireNotNull(result.mapping)
        assertThat(mapping.dateFormat).isEqualTo("AMBIGUOUS")
        assertThat(result.uncertainFields).contains(MappingField.DATE_FORMAT)
    }

    private fun infer(fixtureName: String): InferredMapping =
        inferColumnMapping(tokenizeCsv(loadCsvFixture(fixtureName)))
}
