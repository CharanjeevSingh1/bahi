package dev.charanjeev.bahi.feature.transactions

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.datetime.LocalDate

/**
 * A structured header rather than a pre-formatted string: "Today" and
 * "Yesterday" need locale-specific copy, which belongs in the Composable, not
 * in logic that has to stay unit-testable without an Android context.
 */
sealed interface DateHeader {
    data object Today : DateHeader
    data object Yesterday : DateHeader
    data class Dated(val date: LocalDate) : DateHeader
}

data class TransactionGroup(
    val header: DateHeader,
    val items: ImmutableList<TransactionListItem>,
)

/**
 * Groups by calendar date, newest group first. [today] is passed in rather
 * than read from LocalDate.now() so the Today/Yesterday boundary -- and month
 * rollovers -- are deterministic in tests.
 */
fun groupByDate(items: List<TransactionListItem>, today: LocalDate): ImmutableList<TransactionGroup> {
    val yesterday = LocalDate.fromEpochDays(today.toEpochDays() - 1)
    return items
        .groupBy { it.transaction.date }
        .toList()
        .sortedByDescending { (date, _) -> date }
        .map { (date, groupItems) ->
            val header = when (date) {
                today -> DateHeader.Today
                yesterday -> DateHeader.Yesterday
                else -> DateHeader.Dated(date)
            }
            TransactionGroup(header = header, items = groupItems.toImmutableList())
        }
        .toImmutableList()
}
