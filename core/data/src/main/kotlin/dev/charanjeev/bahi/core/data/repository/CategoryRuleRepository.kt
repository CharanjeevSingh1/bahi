package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import kotlinx.coroutines.flow.Flow

/**
 * The only surface features are allowed to touch for auto-categorisation
 * rules, matching TransactionRepository's shape.
 */
interface CategoryRuleRepository {

    /**
     * In evaluation order: ascending priority, ties broken by id
     * (docs/budgets-design.md §1.5). Callers can rely on that ordering rather
     * than re-sorting -- it's the order the rules actually apply in.
     */
    fun observeRules(): Flow<List<CategoryRule>>

    /** [observeRules]'s one-shot twin, same order. A rule run reads once; it doesn't subscribe. */
    suspend fun rules(): List<CategoryRule>

    /**
     * Keyed on [CategoryRule.id], unlike BudgetRepository.upsert: rules have
     * no natural key, and two rules may legitimately share a
     * [CategoryRule.merchantContains] string pointing at different
     * categories -- §1.5 resolves that by priority rather than forbidding it.
     */
    /**
     * Rejects a blank [CategoryRule.merchantContains] outright, and this is
     * the guard §1.1 calls for at creation rather than a redundant one.
     * `contains("")` is true of every string, so a blank rule doesn't match
     * nothing -- it matches the user's entire transaction history and files
     * all of it under one category.
     *
     * It throws rather than saving-and-ignoring or silently no-oping,
     * because by the time a blank rule reaches here every layer above has
     * already failed: the editor disables its save action while the field is
     * blank, and RuleEditorViewModel.onSave refuses independently of the
     * button's state. Reaching this line means a programming error, and
     * crashing on one is strictly better than persisting a rule that will
     * recategorise everything the next time any trigger fires.
     */
    suspend fun upsert(rule: CategoryRule)

    /** Soft delete: sync needs the tombstone. */
    suspend fun delete(id: String)

    /**
     * Rewrites every rule's priority to its position in [orderedIds], which
     * is what the user dragged (or nudged) it to. Priority decides which of
     * two matching rules wins (§1.5), so this is the whole of "reorder".
     *
     * Takes the complete order rather than a "move this one up": the caller
     * already knows the list it is showing, and a relative move has to be
     * resolved against a list that may have changed underneath it.
     */
    suspend fun reorder(orderedIds: List<String>)

    /**
     * What applying [rule] to transactions that already exist *would* do --
     * computed, shown, and only then committed via [apply] (§1.6).
     *
     * Scoped to unlocked transactions in *any* category, not just
     * uncategorised ones: moving an already-categorised transaction is
     * precisely why someone edits a rule, so a preview that only ever
     * offered to fill in blanks would never show the change they were
     * trying to make.
     */
    suspend fun previewApplyToExisting(rule: CategoryRule): RuleApplicationPreview

    /**
     * §1.3's second trigger: every active rule over every transaction that
     * has no category yet. The only way a rule created *after* an import
     * ever reaches transactions that already exist.
     *
     * Never touches a transaction that already has a category, unlike
     * [previewApplyToExisting] -- this action is "fill in the blanks", and a
     * user running it should not have to worry that it will also rearrange
     * categories they already set.
     */
    suspend fun previewRecategoriseUncategorised(): RuleApplicationPreview

    /**
     * Commits exactly the assignments [preview] carries, and returns how many
     * rows actually changed.
     *
     * That count can be lower than [RuleApplicationPreview.matchedCount]: the
     * write goes through applyRuleCategory, whose WHERE clause refuses a row
     * that has been locked or deleted since the preview was taken. Callers
     * report this number, not the one they previewed -- the same honesty rule
     * softDeleteBatch established.
     */
    suspend fun apply(preview: RuleApplicationPreview): Int
}
