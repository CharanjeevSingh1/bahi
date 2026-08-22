package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.DateWindow
import dev.charanjeev.bahi.core.model.TransactionFilter
import kotlinx.datetime.LocalDate

/** Which of the three date-range choices is picked; null means no date filter at all. */
enum class DateRangeOption { THIS_MONTH, LAST_MONTH, CUSTOM }

/**
 * The screen's own filter selection -- "this month" and "last month" are
 * relative to whenever "today" turns out to be, unlike [TransactionFilter]'s
 * concrete [DateWindow], so this is what survives in SavedStateHandle and
 * [toRepositoryFilter] resolves it against "today" fresh on every read.
 */
data class TransactionFilterState(
    val categoryIds: Set<String> = emptySet(),
    val dateRangeOption: DateRangeOption? = null,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
) {
    val isActive: Boolean get() = categoryIds.isNotEmpty() || dateRangeOption != null
}

/** The concrete window [dateRangeOption] means today, or null if no date filter is set. */
fun TransactionFilterState.resolveDateWindow(today: LocalDate): DateWindow? = when (dateRangeOption) {
    null -> null
    DateRangeOption.THIS_MONTH -> DateWindow(firstDayOfMonth(today), lastDayOfMonth(today))
    DateRangeOption.LAST_MONTH -> {
        val lastMonth = firstDayOfPreviousMonth(today)
        DateWindow(lastMonth, lastDayOfMonth(lastMonth))
    }
    DateRangeOption.CUSTOM -> if (customFrom != null && customTo != null) DateWindow(customFrom, customTo) else null
}

fun TransactionFilterState.toRepositoryFilter(today: LocalDate): TransactionFilter =
    TransactionFilter(categoryIds = categoryIds, dateWindow = resolveDateWindow(today))

/**
 * What the net total's label should say. A plain LocalDate can't tell "the
 * default view" apart from an explicit "this month" filter, but the label
 * reads the same either way, so [Month] covers both; [Filtered] is a
 * category-only filter with no date bound, where no single month applies.
 */
sealed interface NetPeriod {
    data class Month(val month: LocalDate) : NetPeriod
    data class Range(val from: LocalDate, val to: LocalDate) : NetPeriod
    data object Filtered : NetPeriod
}

fun TransactionFilterState.toNetPeriod(today: LocalDate): NetPeriod = when (dateRangeOption) {
    null -> if (categoryIds.isEmpty()) NetPeriod.Month(today) else NetPeriod.Filtered
    DateRangeOption.THIS_MONTH -> NetPeriod.Month(today)
    DateRangeOption.LAST_MONTH -> NetPeriod.Month(firstDayOfPreviousMonth(today))
    DateRangeOption.CUSTOM -> if (customFrom != null && customTo != null) {
        NetPeriod.Range(customFrom, customTo)
    } else {
        NetPeriod.Filtered
    }
}

internal fun firstDayOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.monthNumber, 1)

internal fun lastDayOfMonth(date: LocalDate): LocalDate {
    val firstOfNextMonth = if (date.monthNumber == 12) {
        LocalDate(date.year + 1, 1, 1)
    } else {
        LocalDate(date.year, date.monthNumber + 1, 1)
    }
    return LocalDate.fromEpochDays(firstOfNextMonth.toEpochDays() - 1)
}

internal fun firstDayOfPreviousMonth(date: LocalDate): LocalDate =
    if (date.monthNumber == 1) LocalDate(date.year - 1, 12, 1) else LocalDate(date.year, date.monthNumber - 1, 1)
