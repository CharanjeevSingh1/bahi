package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.MonthlyBudgets
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.Flow

/**
 * The only surface features are allowed to touch for budget data, matching
 * TransactionRepository's shape.
 */
interface BudgetRepository {

    fun observeBudgets(month: YearMonth): Flow<List<Budget>>

    /**
     * [month]'s budgets with their spend, plus that month's uncategorised
     * spending -- everything the budgets screen renders, in one value.
     *
     * Spend is aggregated by the query, not by this layer folding over
     * transactions (docs/budgets-design.md §4.2). That is what makes the
     * result live: a rule, an import or a hand edit changing a transaction's
     * category re-emits here on its own, with nothing to invalidate and no
     * recompute step at any of the call sites that can change a
     * `category_id` (§3).
     *
     * The uncategorised figure travels with the budgets rather than beside
     * them because it is the only thing separating two months that otherwise
     * render identically: one with no transactions at all, and one whose
     * spending is entirely uncategorised. Both leave every budget at ₹0 spent
     * -- see [MonthlyBudgets].
     */
    fun observeMonthlyBudgets(month: YearMonth): Flow<MonthlyBudgets>

    /**
     * Creates or replaces the budget for [budget]'s category and month --
     * there is only ever one, and it is keyed on that pair rather than on
     * [Budget.id].
     *
     * That means **[Budget.id] is not what identifies the row on the way in**:
     * if a budget already exists for the category and month, this updates it
     * and keeps its existing id, ignoring whatever id the caller passed. A
     * caller editing a budget therefore doesn't need to have loaded it first,
     * and a caller creating one can't accidentally end up with two.
     *
     * The invariant lives here, not in a UNIQUE index, because a soft-deleted
     * budget still occupies the natural key and would make re-creating a
     * deleted budget fail a constraint the user can't see the cause of --
     * docs/budgets-design.md §4.1.
     */
    suspend fun upsert(budget: Budget)

    /** Soft delete: sync needs the tombstone. */
    suspend fun delete(id: String)

    /** See TransactionRepository.dirtyRows. */
    suspend fun dirtyRows(limit: Int = 200): List<DirtyRow>

    /** See TransactionRepository.markSynced. */
    suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean
}
