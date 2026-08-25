package dev.charanjeev.bahi.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The month a [Budget] covers, as "2026-08".
 *
 * A plain String would let `"August 2026"` or `"2026-8"` be constructed and
 * then silently match no transaction at all -- a budget that reads zero
 * forever rather than an error. Parsing in one place makes that
 * unrepresentable, the same reason [Money] exists rather than a raw Long.
 *
 * Stored as TEXT so it sorts lexicographically, matching `transactions.date`;
 * zero-padding the month is what makes that ordering correct.
 */
@JvmInline
value class YearMonth private constructor(val value: String) : Comparable<YearMonth> {

    val year: Int get() = value.substring(0, 4).toInt()
    val month: Int get() = value.substring(5, 7).toInt()

    /**
     * The concrete [LocalDate] bounds of this month, inclusive on both ends to
     * match `TransactionDao`'s BETWEEN.
     *
     * Derived by calendar arithmetic rather than a table of month lengths, so
     * February 2028 is 29 days without leap years being special-cased. This is
     * the only place a month becomes a date range: the data layer is never
     * handed "this month" (see TransactionFilter's doc and
     * docs/budgets-design.md §2.3), and nothing here converts through an
     * Instant or a time zone, so the answer can't drift with where the device
     * is.
     */
    fun dateRange(): DateWindow {
        val first = LocalDate(year, month, 1)
        return DateWindow(
            from = first,
            to = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY),
        )
    }

    /**
     * The month [count] months after this one; negative goes back.
     *
     * Calendar arithmetic on the first of the month, for the same reason
     * [dateRange] derives its own bounds: month lengths and year rollover
     * come from the date library rather than from arithmetic here that would
     * have to special-case December.
     */
    fun plusMonths(count: Int): YearMonth = from(LocalDate(year, month, 1).plus(count, DateTimeUnit.MONTH))

    override fun compareTo(other: YearMonth): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("""(\d{4})-(\d{2})""")

        /** Throws on anything that isn't a real "YYYY-MM"; see [parseOrNull] to handle it. */
        fun parse(raw: String): YearMonth =
            parseOrNull(raw) ?: throw IllegalArgumentException("Not a YYYY-MM month: '$raw'")

        fun parseOrNull(raw: String): YearMonth? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val month = match.groupValues[2].toInt()
            return if (month in 1..12) YearMonth(match.value) else null
        }

        fun of(year: Int, month: Int): YearMonth {
            require(month in 1..12) { "Month out of range: $month" }
            require(year in 1..9999) { "Year out of range: $year" }
            return YearMonth("%04d-%02d".format(year, month))
        }

        fun from(date: LocalDate): YearMonth = of(date.year, date.monthNumber)
    }
}
