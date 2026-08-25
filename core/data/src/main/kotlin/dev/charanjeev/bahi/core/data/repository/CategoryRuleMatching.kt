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
    val prepared = prepare(rules)
    if (prepared.isEmpty()) return emptyMap()

    return candidates.asSequence()
        // The third guard on categoryLockedByUser, after the caller's query
        // and applyRuleCategory's WHERE clause (§1.4). Cheap, and it means
        // this function is safe to call with any list at all rather than
        // only with one somebody else remembered to filter.
        .filterNot { it.categoryLockedByUser }
        .mapNotNull { transaction ->
            val rule = prepared.firstMatch(transaction) ?: return@mapNotNull null
            // A rule that "matches" a transaction already in that category
            // changes nothing. Excluding it here is what keeps the preview
            // count honest -- otherwise re-running the same rules reports
            // work it isn't going to do.
            if (rule.categoryId == transaction.categoryId) null else transaction.id to rule.categoryId
        }
        .toMap()
}

/**
 * How many of [candidates] [rules] would have recategorised but must never
 * touch, because the user set those categories by hand.
 *
 * This is what lets the preview say "12 transactions -- 3 locked ones will be
 * skipped" instead of leaving the user to wonder why the number is smaller
 * than they expected. Counting the locked ones is only honest if it uses the
 * same matching the write uses, which is why both go through [prepare] and
 * [firstMatch] rather than each having their own copy of the rules.
 *
 * **Returns a count, deliberately never a map of assignments.** There is no
 * way to turn this result into a write, which is the point: it would
 * otherwise be a second path into applyRuleCategory that hasn't got layer
 * 1's guard on it. It also requires [Transaction.categoryLockedByUser] to be
 * true, so handing it the unlocked candidate set counts nothing rather than
 * quietly double-counting the rows [applyRules] already claimed.
 */
internal fun countLockedMatches(
    rules: List<CategoryRule>,
    candidates: List<Transaction>,
): Int {
    val prepared = prepare(rules)
    if (prepared.isEmpty()) return 0

    return candidates.count { transaction ->
        if (!transaction.categoryLockedByUser) return@count false
        val rule = prepared.firstMatch(transaction) ?: return@count false
        // Same "already in that category" exclusion applyRules makes: a
        // locked transaction the rule would not have changed anyway isn't
        // something the user needs warning about.
        rule.categoryId != transaction.categoryId
    }
}

/** A rule with its needle already trimmed and case-folded, so matching doesn't redo it per transaction. */
private class PreparedRule(val rule: CategoryRule, val needle: String)

/**
 * Shared by [applyRules] and [countLockedMatches] so the two can't drift.
 * A preview computed with different matching from the write it previews is
 * worse than no preview at all -- it would be confidently wrong.
 */
private fun prepare(rules: List<CategoryRule>): List<PreparedRule> = rules
    // A blank needle is `contains("")`, which is true for every string --
    // one empty rule would silently recategorise the user's entire history.
    // Creation rejects it (CategoryRuleRepository.upsert) and the rules
    // editor can't submit one; this is the last place it can't get through,
    // since the cost of being wrong here is unbounded.
    .filter { it.merchantContains.isNotBlank() }
    .sortedWith(compareBy({ it.priority }, { it.id }))
    .map { rule -> PreparedRule(rule, rule.merchantContains.trim().uppercase()) }

/** First match wins outright; rules never combine (§1.5). */
private fun List<PreparedRule>.firstMatch(transaction: Transaction): CategoryRule? {
    // merchant when it exists, description otherwise. merchant is null for
    // every transaction the app can currently produce (§1.1) -- this is
    // forward-compatibility, not a live path.
    val haystack = (transaction.merchant ?: transaction.description).trim().uppercase()
    return firstOrNull { haystack.contains(it.needle) }?.rule
}
