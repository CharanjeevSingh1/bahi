package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.CategoryRule
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

    /**
     * Keyed on [CategoryRule.id], unlike BudgetRepository.upsert: rules have
     * no natural key, and two rules may legitimately share a
     * [CategoryRule.merchantContains] string pointing at different
     * categories -- §1.5 resolves that by priority rather than forbidding it.
     */
    suspend fun upsert(rule: CategoryRule)

    /** Soft delete: sync needs the tombstone. */
    suspend fun delete(id: String)
}
