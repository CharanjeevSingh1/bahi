package dev.charanjeev.bahi.core.model

import kotlinx.datetime.LocalDate

/**
 * A mechanical, already-resolved filter for the transactions query: concrete
 * category ids and a concrete date window, never "this month" or "last
 * month" -- resolving what those mean today needs "now", which is a
 * presentation-layer concern. The data layer only ever sees dates.
 */
data class TransactionFilter(
    val categoryIds: Set<String> = emptySet(),
    val dateWindow: DateWindow? = null,
) {
    val isActive: Boolean get() = categoryIds.isNotEmpty() || dateWindow != null

    companion object {
        val NONE = TransactionFilter()
    }
}

/** Inclusive on both ends, matching TransactionDao's BETWEEN. */
data class DateWindow(val from: LocalDate, val to: LocalDate)
