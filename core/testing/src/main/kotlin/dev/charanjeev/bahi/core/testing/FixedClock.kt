package dev.charanjeev.bahi.core.testing

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * A Clock that never advances, so "today"/"yesterday" boundaries and month
 * rollovers are deterministic instead of depending on when the test runs.
 */
class FixedClock(private val instant: Instant) : Clock {

    // Anchors in the caller's own zone by default, not UTC: production code
    // reads "today" via TimeZone.currentSystemDefault(), and a UTC anchor
    // would drift onto the wrong calendar date on a host west or east of UTC.
    constructor(date: LocalDate, timeZone: TimeZone = TimeZone.currentSystemDefault()) :
        this(date.atStartOfDayIn(timeZone))

    override fun now(): Instant = instant
}
