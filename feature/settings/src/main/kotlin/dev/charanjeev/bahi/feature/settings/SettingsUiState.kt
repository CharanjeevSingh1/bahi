package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.Instant

/**
 * A sealed interface rather than a data class with nullable fields, matching
 * RulesUiState: Loading and Empty are genuinely different answers to "what do
 * I draw", and a `conflicts: List?` that is null in one case and empty in the
 * other makes them the caller's problem to tell apart.
 */
sealed interface SettingsUiState {

    /**
     * Every state carries the last restore/dismiss outcome, matching
     * BudgetsUiState's `month`: a restore that empties the list is still a
     * restore the user needs to be told about, and putting the message only
     * on [Success] would lose it the instant the last conflict clears.
     */
    val restoreMessage: RestoreMessage?

    /**
     * Whether this build has `sync.properties` at all (docs/sync-design.md
     * §8.5, D12, slice 9a). Carried on every state, including [Loading]: it
     * comes from `SyncConfiguration`, read once and synchronously when the
     * ViewModel is constructed, not from a Flow -- there is no "still finding
     * out" state for it, so baking a fixed default into [Loading] the way
     * `restoreMessage` defaults to null would make a correctly-configured
     * build show the wrong row for a frame before self-correcting, which is
     * exactly the class of momentary-wrong-then-quiet bug the rest of this
     * app refuses to ship (docs/budgets-design.md §2.1's `YearMonth`).
     */
    val syncConfigured: Boolean

    data class Loading(override val syncConfigured: Boolean) : SettingsUiState {
        override val restoreMessage: RestoreMessage? = null
    }

    /** No unacknowledged conflicts. Not the same as "sync has never run" -- there is currently no way to tell those apart; see SettingsViewModel's doc. */
    data class Empty(
        override val syncConfigured: Boolean = true,
        override val restoreMessage: RestoreMessage? = null,
    ) : SettingsUiState

    data class Success(
        val conflicts: ImmutableList<ConflictListItem>,
        override val restoreMessage: RestoreMessage? = null,
        override val syncConfigured: Boolean = true,
    ) : SettingsUiState
}

/**
 * One row of the conflict list. [field] and the two values stay in their raw
 * form -- a column name and a decoded [ConflictValue] -- rather than being
 * formatted here: every other screen's copy lives in strings.xml and is
 * resolved by the Composable through `stringResource`, and a field label is
 * copy same as any other.
 */
data class ConflictListItem(
    val id: String,
    val table: SyncTable,
    val field: String,
    val chosenValue: ConflictValue,
    val discardedValue: ConflictValue,
    val reason: String,
    val resolvedAt: Instant,
)

/** What the last restore/dismiss attempt did, so the screen can say so once and then forget it. */
data class RestoreMessage(val conflictId: String, val outcome: RestoreOutcome)

internal object SettingsTestTags {
    const val LOADING = "settings:loading"
    const val EMPTY = "settings:empty"
    const val LIST = "settings:list"
    const val CONFLICTS_COUNT = "settings:conflictsCount"
    const val NOT_CONFIGURED = "settings:notConfigured"

    fun row(id: String) = "settings:row:$id"
    fun restore(id: String) = "settings:restore:$id"
    fun dismiss(id: String) = "settings:dismiss:$id"
}
