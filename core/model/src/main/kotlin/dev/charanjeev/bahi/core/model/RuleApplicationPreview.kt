package dev.charanjeev.bahi.core.model

/**
 * What a rule run would do, worked out before anything is written.
 *
 * This type exists because "apply these rules to my existing transactions"
 * is a bulk mutation of data the user has already looked at, and the wrong
 * shape for that is a button that does it and reports afterwards. Rules are
 * substring matches the user typed; a rule broader than they intended is an
 * easy mistake to make and an unpleasant one to discover after the fact
 * (docs/budgets-design.md §1.6). So the count comes first, then the consent,
 * then the write.
 *
 * [assignments] is carried rather than recomputed at commit time, so the
 * number shown to the user and the set of rows actually written are the same
 * object. A preview that recomputed on confirm could show one number and
 * apply another.
 */
data class RuleApplicationPreview(
    /** Transaction id -> the category a rule matched, exactly as the engine produced it. */
    val assignments: Map<String, String>,
    /**
     * How many transactions the rules matched but will not touch, because
     * the user set those categories by hand. Surfaced rather than silently
     * subtracted: "12 will change" reads as wrong to someone looking at 15
     * matching transactions unless the app says where the other 3 went.
     */
    val lockedSkippedCount: Int,
) {
    /** How many transactions will actually change. Never includes locked ones. */
    val matchedCount: Int get() = assignments.size

    /** Nothing to do -- worth its own name, since it's the case the UI must not offer a confirm button for. */
    val isEmpty: Boolean get() = assignments.isEmpty()

    companion object {
        val NOTHING = RuleApplicationPreview(assignments = emptyMap(), lockedSkippedCount = 0)
    }
}
