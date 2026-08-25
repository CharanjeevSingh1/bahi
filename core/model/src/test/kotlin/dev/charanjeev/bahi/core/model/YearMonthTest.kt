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

    // dateRange() is the input to every budget total, so a boundary that is
    // off by one day silently mis-attributes a real transaction to the
    // neighbouring month rather than failing. These cover it exhaustively
    // enough that a rewrite of the arithmetic can't quietly regress.

    @Test
    fun `date range covers the whole month, inclusive on both ends`() {
        val range = YearMonth.of(2026, 8).dateRange()

        assertThat(range.from).isEqualTo(LocalDate(2026, 8, 1))
        assertThat(range.to).isEqualTo(LocalDate(2026, 8, 31))
    }

    @Test
    fun `every month ends on its own last day`() {
        val lastDays = (1..12).map { month -> YearMonth.of(2026, month).dateRange().to.dayOfMonth }

        // 2026 is not a leap year, so February is 28.
        assertThat(lastDays).containsExactly(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31).inOrder()
    }

    @Test
    fun `every month starts on the first`() {
        val firstDays = (1..12).map { month -> YearMonth.of(2026, month).dateRange().from.dayOfMonth }

        assertThat(firstDays).containsExactly(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
    }

    @Test
    fun `February is 29 days in a leap year and 28 otherwise`() {
        assertThat(YearMonth.of(2028, 2).dateRange().to).isEqualTo(LocalDate(2028, 2, 29))
        assertThat(YearMonth.of(2026, 2).dateRange().to).isEqualTo(LocalDate(2026, 2, 28))
        // 2000 is a leap year and 1900 is not -- the century rule, which a
        // hand-rolled `year % 4` check gets wrong and calendar arithmetic
        // gets right for free.
        assertThat(YearMonth.of(2000, 2).dateRange().to).isEqualTo(LocalDate(2000, 2, 29))
        assertThat(YearMonth.of(1900, 2).dateRange().to).isEqualTo(LocalDate(1900, 2, 28))
    }

    @Test
    fun `December rolls into the next year without escaping its own month`() {
        val range = YearMonth.of(2026, 12).dateRange()

        assertThat(range.from).isEqualTo(LocalDate(2026, 12, 1))
        assertThat(range.to).isEqualTo(LocalDate(2026, 12, 31))
    }

    @Test
    fun `the first and last day of a month fall inside the range`() {
        val range = YearMonth.of(2026, 8).dateRange()

        // The two dates a wrong boundary would exclude -- and the case the
        // task worried about: a transaction dated the 31st belongs to August.
        assertThat(LocalDate(2026, 8, 1) >= range.from).isTrue()
        assertThat(LocalDate(2026, 8, 31) <= range.to).isTrue()
    }

    @Test
    fun `the neighbouring months' adjacent days fall outside the range`() {
        val range = YearMonth.of(2026, 8).dateRange()

        assertThat(LocalDate(2026, 7, 31) < range.from).isTrue()
        assertThat(LocalDate(2026, 9, 1) > range.to).isTrue()
    }

    @Test
    fun `consecutive months' ranges are adjacent with no gap or overlap`() {
        // If they overlapped, a transaction would count toward two budgets;
        // if they gapped, toward none.
        val august = YearMonth.of(2026, 8).dateRange()
        val september = YearMonth.of(2026, 9).dateRange()

        assertThat(august.to.toEpochDays() + 1).isEqualTo(september.from.toEpochDays())
    }

    @Test
    fun `from takes the month a date falls in`() {
        assertThat(YearMonth.from(LocalDate(2026, 8, 31))).isEqualTo(YearMonth.of(2026, 8))
    }

    // --- plusMonths: the budgets screen's month navigation ---

    @Test
    fun `plusMonths moves within a year`() {
        assertThat(YearMonth.of(2026, 8).plusMonths(1)).isEqualTo(YearMonth.of(2026, 9))
        assertThat(YearMonth.of(2026, 8).plusMonths(-1)).isEqualTo(YearMonth.of(2026, 7))
    }

    @Test
    fun `plusMonths rolls over the year in both directions`() {
        // The case arithmetic on the month number alone gets wrong, and gets
        // wrong silently -- month 13 would format as "2026-13" and match no
        // transaction ever.
        assertThat(YearMonth.of(2026, 12).plusMonths(1)).isEqualTo(YearMonth.of(2027, 1))
        assertThat(YearMonth.of(2026, 1).plusMonths(-1)).isEqualTo(YearMonth.of(2025, 12))
    }

    @Test
    fun `plusMonths spans more than a year`() {
        assertThat(YearMonth.of(2026, 8).plusMonths(14)).isEqualTo(YearMonth.of(2027, 10))
        assertThat(YearMonth.of(2026, 8).plusMonths(-20)).isEqualTo(YearMonth.of(2024, 12))
    }

    @Test
    fun `plusMonths keeps the range usable for a budget`() {
        // A navigated-to month has to produce a real date window, since that
        // is what every total on the screen is scoped by.
        val february = YearMonth.of(2027, 12).plusMonths(2)

        assertThat(february).isEqualTo(YearMonth.of(2028, 2))
        assertThat(february.dateRange().to).isEqualTo(LocalDate(2028, 2, 29))
    }
}
