package dev.charanjeev.bahi.core.testing

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A [Clock] a test can advance between writes. [FixedClock] answers "what
 * time is it" once and for all; this one is for scenarios where the relative
 * order of two edits is the thing under test -- a tiebreak, a causality
 * check -- and both need their own distinct, controlled instant.
 */
class MutableClock(startEpochMillis: Long = 0L) : Clock {

    private var epochMillis = startEpochMillis

    override fun now(): Instant = Instant.fromEpochMilliseconds(epochMillis)

    fun advanceBy(millis: Long) {
        epochMillis += millis
    }

    fun set(epochMillis: Long) {
        this.epochMillis = epochMillis
    }
}
