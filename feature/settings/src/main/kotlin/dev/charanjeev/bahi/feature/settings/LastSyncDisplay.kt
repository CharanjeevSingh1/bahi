package dev.charanjeev.bahi.feature.settings

import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What the Settings sync row shows once a build is configured
 * (docs/sync-design.md §8.7): the "quiet fact... going to a visible warning"
 * signal §8.7 asks for specifically because the unacknowledged-conflict
 * count (already on this screen) can be zero because nothing has conflicted,
 * not because sync is healthy. Computed once per [SettingsUiState]
 * recomposition, not on a live-ticking timer -- the same "not urgent enough
 * for a notification" call §8.7 makes about this signal applies to a
 * self-refreshing clock too; the next unrelated recomposition (a conflict
 * resolving, a Drive connection change) is what moves this forward.
 */
sealed interface LastSyncDisplay {
    data object Never : LastSyncDisplay
    data object JustNow : LastSyncDisplay
    data class MinutesAgo(val minutes: Int) : LastSyncDisplay
    data class HoursAgo(val hours: Int) : LastSyncDisplay

    /**
     * [isStale] is only ever true here, never on [MinutesAgo]/[HoursAgo]:
     * [STALE_THRESHOLD] is three days, comfortably past where either of
     * those buckets stops applying.
     */
    data class DaysAgo(val days: Int, val isStale: Boolean) : LastSyncDisplay
}

/**
 * §8.7's own example ("Last synced 6 days ago — check your connection")
 * implies a threshold well past a single missed periodic tick
 * ([dev.charanjeev.bahi.core.sync.work.DefaultSyncScheduler]'s cadence is
 * every 4 hours): flagging staleness after one day without a matching
 * network/battery window would be a false alarm on a perfectly healthy
 * install (a day of travel, a day in low-battery mode). Three days absorbs
 * over a dozen missed periodic ticks -- not one bad day -- while still
 * surfacing a genuinely broken sync well before the "6 days" §8.7 itself
 * treats as obviously too long to have stayed quiet.
 */
private val STALE_THRESHOLD = 3.days

fun lastSyncDisplay(now: Instant, lastSuccessfulSyncAt: Instant?): LastSyncDisplay {
    if (lastSuccessfulSyncAt == null) return LastSyncDisplay.Never
    val elapsed = now - lastSuccessfulSyncAt
    return when {
        elapsed < 1.minutes -> LastSyncDisplay.JustNow
        elapsed < 1.hours -> LastSyncDisplay.MinutesAgo(elapsed.inWholeMinutes.toInt())
        elapsed < 24.hours -> LastSyncDisplay.HoursAgo(elapsed.inWholeHours.toInt())
        else -> LastSyncDisplay.DaysAgo(elapsed.inWholeDays.toInt(), isStale = elapsed >= STALE_THRESHOLD)
    }
}
