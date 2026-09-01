package dev.charanjeev.bahi.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class LastSyncDisplayTest {

    private val now = Instant.parse("2026-01-10T12:00:00Z")

    @Test
    fun `never synced when there is no last-successful timestamp`() {
        assertThat(lastSyncDisplay(now, null)).isEqualTo(LastSyncDisplay.Never)
    }

    @Test
    fun `just now for anything under a minute`() {
        assertThat(lastSyncDisplay(now, now.minus(30.seconds))).isEqualTo(LastSyncDisplay.JustNow)
    }

    @Test
    fun `minutes ago between one minute and one hour`() {
        assertThat(lastSyncDisplay(now, now.minus(4.minutes))).isEqualTo(LastSyncDisplay.MinutesAgo(4))
    }

    @Test
    fun `hours ago between one hour and one day`() {
        assertThat(lastSyncDisplay(now, now.minus(5.hours))).isEqualTo(LastSyncDisplay.HoursAgo(5))
    }

    @Test
    fun `days ago is not stale under the three-day threshold`() {
        assertThat(lastSyncDisplay(now, now.minus(2.days))).isEqualTo(LastSyncDisplay.DaysAgo(2, isStale = false))
    }

    @Test
    fun `exactly three days counts as stale`() {
        assertThat(lastSyncDisplay(now, now.minus(3.days))).isEqualTo(LastSyncDisplay.DaysAgo(3, isStale = true))
    }

    @Test
    fun `six days ago is stale, matching the design doc's own example`() {
        assertThat(lastSyncDisplay(now, now.minus(6.days))).isEqualTo(LastSyncDisplay.DaysAgo(6, isStale = true))
    }
}
