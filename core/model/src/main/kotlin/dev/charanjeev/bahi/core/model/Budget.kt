package dev.charanjeev.bahi.core.model

/**
 * A spending limit for one category in one calendar month.
 *
 * [categoryId] is non-null on purpose: an "overall" budget would need a real
 * sentinel category id, never a null one, because SQL treats every NULL as
 * distinct and would silently stop enforcing one-budget-per-month for exactly
 * that case (docs/budgets-design.md §2.1).
 *
 * There is no `spent` here -- what a budget has been spent against is a
 * property of the transactions, not of the budget, and is computed by query.
 * See `BudgetProgress` when that lands.
 */
data class Budget(
    val id: String,
    val categoryId: String,
    val month: YearMonth,
    val limit: Money,
    val currencyCode: String,
)
