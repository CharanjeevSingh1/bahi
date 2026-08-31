package dev.charanjeev.bahi.core.model

import kotlinx.datetime.Instant

/**
 * One field, on one row, where a policy had to pick a winner (docs/sync-design.md
 * §5.6) -- the domain view of `sync_conflicts`. [chosenValue] and [discardedValue]
 * are decoded out of the stored JSON into something a screen can render without
 * knowing the field's real Kotlin type; see [ConflictValue].
 */
data class SyncConflict(
    val id: String,
    val table: SyncTable,
    val rowId: String,
    val field: String,
    val resolvedAt: Instant,
    val chosenValue: ConflictValue,
    val discardedValue: ConflictValue,
    /** Which merge rule fired, so the list can say why and not just what. */
    val reason: String,
    /** Null until the user has seen it. */
    val acknowledgedAt: Instant?,
)

/**
 * A field value decoded from `sync_conflicts`' JSON columns, generic enough
 * to render across every table's field set without this module knowing what
 * `category_id` or `amount_minor` mean -- labelling the field name and, where
 * it names a row in another table, resolving it to something readable, is a
 * presentation concern left to :feature:settings.
 */
sealed interface ConflictValue {
    data object None : ConflictValue
    data class Text(val value: String) : ConflictValue
    data class Number(val value: Long) : ConflictValue
    data class Flag(val value: Boolean) : ConflictValue
}
