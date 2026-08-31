package dev.charanjeev.bahi.core.ui

import dev.charanjeev.bahi.core.model.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "August 2026". Shared across every screen that names a month -- budgets and
 * insights both switch months, and the two must not name the same one
 * differently.
 *
 * Goes through java.time only for the localised month name; the value itself
 * stays a [YearMonth] and never becomes an instant, so nothing here can drift
 * with the device's time zone (docs/budgets-design.md §2.3).
 */
fun YearMonth.displayName(): String =
    java.time.YearMonth.of(year, month)
        .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))

/** "Aug" -- short form for a chart's per-bar label, where "August 2026" would overrun. */
fun YearMonth.shortDisplayName(): String =
    java.time.YearMonth.of(year, month)
        .format(DateTimeFormatter.ofPattern("LLL", Locale.getDefault()))
