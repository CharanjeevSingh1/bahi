package dev.charanjeev.bahi.core.model

/** One month's total expense spend, for the trend chart. Positive, same sign convention as [CategorySpend]. */
data class MonthlyTotal(val month: YearMonth, val spent: Money)

/**
 * The months a spending trend can honestly be drawn over, oldest first,
 * always ending on the month being viewed.
 *
 * Every entry is a real zero, never a stand-in for "no data yet": the window
 * this is built over starts at the earliest month with any live transaction
 * (see `OfflineFirstInsightsRepository.observeSpendTrend`), so a month inside
 * it with no expense rows genuinely spent nothing rather than not existing.
 * A month before the app has any history is never included, which is what
 * [hasComparison] is checking for -- exactly one entry means the month being
 * viewed has no history before it to compare against, and the screen has to
 * say that rather than draw a one-bar "trend".
 */
data class SpendTrend(val months: List<MonthlyTotal>) {
    val hasComparison: Boolean get() = months.size > 1
}
