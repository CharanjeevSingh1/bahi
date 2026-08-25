package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.model.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "August 2026". Shared by the budgets list and the editor so the two can't
 * name the same month differently -- the editor previously showed the raw
 * "2026-08", which is the stored form and not something to put in front of
 * anyone.
 *
 * Goes through java.time only for the localised month name; the value itself
 * stays a [YearMonth] and never becomes an instant, so nothing here can drift
 * with the device's time zone (docs/budgets-design.md §2.3).
 */
internal fun YearMonth.displayName(): String =
    java.time.YearMonth.of(year, month)
        .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
