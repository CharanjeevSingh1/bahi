package dev.charanjeev.bahi.core.model

/**
 * An auto-categorisation rule: a transaction whose description contains
 * [merchantContains] is filed under [categoryId].
 *
 * Substring, not regex: a user-authored regex is easy to write too broadly and
 * expensive to run over a whole import, and the failure mode -- transactions
 * quietly filed under the wrong category -- looks like success
 * (docs/budgets-design.md §1.1).
 *
 * Matching itself lives in :core:data, not here: it needs a Transaction and a
 * whole rule set, so it isn't a property of a single rule.
 */
data class CategoryRule(
    val id: String,
    val categoryId: String,
    val merchantContains: String,
    /** Ascending. The lowest matching value wins outright; rules never combine (§1.5). */
    val priority: Int,
)
