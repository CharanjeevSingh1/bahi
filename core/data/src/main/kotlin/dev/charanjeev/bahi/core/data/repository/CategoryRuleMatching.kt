package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.Transaction

/**
 * Decides which of [candidates] each of [rules] would recategorise, returning
 * transaction id -> category id for the ones that would actually change.
 *
 * A pure function over domain models: no Room, no Android, no clock, no
 * writes. Matching and writing are deliberately separate -- the same result
 * drives the "this will recategorise 14 transactions" preview
 * (docs/budgets-design.md §1.6) and the write that follows, so the number the
 * user is shown cannot disagree with what actually happens.
 */
internal fun applyRules(
    rules: List<CategoryRule>,
    candidates: List<Transaction>,
): Map<String, String> {
    // A blank needle is `contains("")`, which is true for every string --
    // one empty rule would silently recategorise the user's entire history.
    // Creation should reject it; this is the second place it can't get
    // through, since the cost of being wrong here is unbounded.
    val ordered = rules
        .filter { it.merchantContains.isNotBlank() }
        .sortedWith(compareBy({ it.priority }, { it.id }))
        .map { rule -> rule to rule.merchantContains.trim().uppercase() }

    if (ordered.isEmpty()) return emptyMap()

    return candidates.asSequence()
        // The third guard on categoryLockedByUser, after the caller's query
        // and applyRuleCategory's WHERE clause (§1.4). Cheap, and it means
        // this function is safe to call with any list at all rather than
        // only with one somebody else remembered to filter.
        .filterNot { it.categoryLockedByUser }
        .mapNotNull { transaction ->
            // merchant when it exists, description otherwise. merchant is
            // null for every transaction the app can currently produce
            // (§1.1) -- this is forward-compatibility, not a live path.
            val haystack = (transaction.merchant ?: transaction.description).trim().uppercase()
            // First match wins outright; rules never combine (§1.5).
            val rule = ordered.firstOrNull { (_, needle) -> haystack.contains(needle) }?.first
                ?: return@mapNotNull null
            // A rule that "matches" a transaction already in that category
            // changes nothing. Excluding it here is what keeps the preview
            // count honest -- otherwise re-running the same rules reports
            // work it isn't going to do.
            if (rule.categoryId == transaction.categoryId) null else transaction.id to rule.categoryId
        }
        .toMap()
}
