package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Transaction
import javax.inject.Inject

/**
 * Pairs rule matching with the one write path allowed to act on it.
 *
 * The pairing is the reason this exists rather than each caller doing the two
 * steps itself: docs/budgets-design.md §4.3 wants one matching implementation
 * and one write path, and there are three triggers (import time, the on-demand
 * recategorise action, and apply-this-rule after an edit) that would otherwise
 * each re-derive the pairing. `applyRules` stays internal to this module
 * because of this class -- nothing outside :core:data can match rules without
 * also going through [TransactionRepository.applyRuleCategories].
 *
 * What it deliberately does *not* do is choose the candidates. Every trigger
 * has a different idea of which transactions are in scope -- an import means
 * the rows that import actually inserted, the on-demand action means every
 * uncategorised row -- and folding that choice in here would hide the one
 * decision each caller genuinely has to make.
 */
class AutoCategoriser @Inject constructor(
    private val categoryRuleRepository: CategoryRuleRepository,
    private val transactionRepository: TransactionRepository,
) {

    /**
     * Returns how many transactions actually changed category, which can be
     * lower than the number matched: a locked or since-deleted row is refused
     * by the write itself (TransactionDao.applyRuleCategory). This number is
     * what a caller reports to the user -- never the size of [candidates],
     * and never the size of the match set.
     */
    suspend fun categorise(candidates: List<Transaction>): Int {
        if (candidates.isEmpty()) return 0
        val assignments = applyRules(categoryRuleRepository.rules(), candidates)
        return transactionRepository.applyRuleCategories(assignments)
    }
}
