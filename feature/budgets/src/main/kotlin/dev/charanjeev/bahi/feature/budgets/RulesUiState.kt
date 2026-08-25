package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import kotlinx.collections.immutable.ImmutableList

/**
 * A sealed interface rather than a data class with nullable fields, matching
 * TransactionsUiState: Loading and Empty are genuinely different answers to
 * "what do I draw", and a `rules: List?` that is null in one case and empty
 * in the other makes them the caller's problem to tell apart.
 */
sealed interface RulesUiState {

    data object Loading : RulesUiState

    /**
     * No rules yet. Deliberately carries no dialog state: with nothing to
     * run, "recategorise uncategorised transactions" has no meaning, so this
     * state doesn't offer the action at all rather than offering one that
     * always reports zero.
     */
    data object Empty : RulesUiState

    data class Success(
        /** In evaluation order -- the order they actually apply in, which is what the user reorders. */
        val rules: ImmutableList<RuleListItem>,
        val dialog: RuleApplyDialog? = null,
        val pendingDelete: PendingDelete? = null,
        /** A preview or an apply is in flight; the screen blocks re-entry rather than queueing a second run. */
        val isWorking: Boolean = false,
    ) : RulesUiState
}

/**
 * [category] is nullable because a rule can outlive the category it points
 * at in the UI's eyes -- the row is CASCADE-deleted in the database, but the
 * rules flow and the categories flow arrive separately, so there is a frame
 * where one has updated and the other hasn't. Rendering "Unknown category"
 * for that frame is better than crashing on a lookup.
 */
data class RuleListItem(
    val rule: CategoryRule,
    val category: Category?,
)

/** Carries the merchant string so the confirmation can name what is being deleted, not just ask. */
data class PendingDelete(
    val ruleId: String,
    val merchantContains: String,
)

internal object RulesTestTags {
    const val LOADING = "rules:loading"
    const val EMPTY = "rules:empty"
    const val LIST = "rules:list"
    const val ADD_FAB = "rules:add"
    const val RECATEGORISE_ACTION = "rules:recategorise"
    const val CONFIRM_DIALOG = "rules:confirmDialog"
    const val CONFIRM_APPLY = "rules:confirmApply"
    const val NOTHING_TO_DO_DIALOG = "rules:nothingToDoDialog"
    const val DONE_DIALOG = "rules:doneDialog"
    const val DELETE_DIALOG = "rules:deleteDialog"

    fun row(ruleId: String) = "rules:row:$ruleId"
    fun moveUp(ruleId: String) = "rules:moveUp:$ruleId"
    fun moveDown(ruleId: String) = "rules:moveDown:$ruleId"
    fun delete(ruleId: String) = "rules:delete:$ruleId"
}
