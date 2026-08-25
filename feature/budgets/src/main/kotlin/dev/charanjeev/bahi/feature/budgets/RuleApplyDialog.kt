package dev.charanjeev.bahi.feature.budgets

/**
 * The consent gate in front of every bulk recategorisation, shared by the
 * rules list and the rule editor because both flows owe the user the same
 * thing (docs/budgets-design.md §1.6).
 *
 * Applying rules to transactions that already exist rewrites data the user
 * has already looked at, and rules are substring matches they typed by hand
 * -- a rule broader than intended is an easy mistake and an unpleasant one to
 * find afterwards. So the shape is: count first, consent second, write third,
 * and report what actually happened rather than what was promised.
 */
sealed interface RuleApplyDialog {

    /**
     * Shown before anything is written. [matchedCount] is how many
     * transactions will change; [lockedSkippedCount] is how many the rules
     * matched but will not touch because the user set those categories by
     * hand.
     *
     * The skipped figure is shown rather than quietly subtracted: without it,
     * "12 transactions" reads as wrong to someone looking at 15 that match,
     * and the lock -- the feature's central promise -- stays invisible right
     * where it is doing its job.
     */
    data class Confirm(
        val matchedCount: Int,
        val lockedSkippedCount: Int,
    ) : RuleApplyDialog

    /**
     * Nothing matched. A separate case from [Confirm] with a zero count,
     * because there is nothing to consent to -- offering an "Apply" button
     * that would do nothing invites the user to wonder what it did.
     */
    data class NothingToDo(val lockedSkippedCount: Int) : RuleApplyDialog

    /**
     * What actually happened. [changedCount] can be lower than
     * [previewedCount]: the write refuses a row that was locked or deleted
     * between the preview and the confirm, and the honest number is the one
     * the user gets told.
     */
    data class Done(
        val changedCount: Int,
        val previewedCount: Int,
    ) : RuleApplyDialog {
        /** Worth calling out separately -- silently reporting a smaller number invites "did it work?". */
        val fellShort: Boolean get() = changedCount < previewedCount
    }
}
