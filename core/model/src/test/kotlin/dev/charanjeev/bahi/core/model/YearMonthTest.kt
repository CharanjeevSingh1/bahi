package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.Test

class YearMonthTest {

    @Test
    fun `parses a well-formed month`() {
        val month = YearMonth.parse("2026-08")

        assertThat(month.year).isEqualTo(2026)
        assertThat(month.month).isEqualTo(8)
        assertThat(month.toString()).isEqualTo("2026-08")
    }

    @Test
    fun `rejects the shapes a raw String would have silently accepted`() {
        // Each of these would have produced a budget that matches no
        // transaction and reads zero forever, rather than an error.
        assertThat(YearMonth.parseOrNull("2026-8")).isNull()
        assertThat(YearMonth.parseOrNull("August 2026")).isNull()
        assertThat(YearMonth.parseOrNull("2026-13")).isNull()
        assertThat(YearMonth.parseOrNull("2026-00")).isNull()
        assertThat(YearMonth.parseOrNull("2026-08-01")).isNull()
        assertThat(YearMonth.parseOrNull("")).isNull()
    }

    @Test
    fun `of zero-pads the month so months sort lexicographically`() {
        assertThat(YearMonth.of(2026, 9).toString()).isEqualTo("2026-09")
        assertThat(YearMonth.of(2026, 9) < YearMonth.of(2026, 10)).isTrue()
        assertThat(YearMonth.of(2026, 12) < YearMonth.of(2027, 1)).isTrue()
    }

    @Test
    fun `date range covers the whole month, inclusive on both ends`() {
        val range = YearMonth.of(2026, 8).dateRange()

        assertThat(range.from).isEqualTo(LocalDate(2026, 8, 1))
        assertThat(range.to).isEqualTo(LocalDate(2026, 8, 31))
    }

    @Test
    fun `date range handles a 30-day month`() {
        val range = YearMonth.of(2026, 9).dateRange()

        assertThat(range.to).isEqualTo(LocalDate(2026, 9, 30))
    }

    @Test
    fun `date range handles February in a leap year and a non-leap year`() {
        assertThat(YearMonth.of(2028, 2).dateRange().to).isEqualTo(LocalDate(2028, 2, 29))
        assertThat(YearMonth.of(2026, 2).dateRange().to).isEqualTo(LocalDate(2026, 2, 28))
    }

    @Test
    fun `date range for December stays inside the year`() {
        val range = YearMonth.of(2026, 12).dateRange()

        assertThat(range.to).isEqualTo(LocalDate(2026, 12, 31))
    }

    @Test
    fun `from takes the month a date falls in`() {
        assertThat(YearMonth.from(LocalDate(2026, 8, 31))).isEqualTo(YearMonth.of(2026, 8))
    }
}
