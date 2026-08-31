package dev.charanjeev.bahi.core.model

/**
 * One category's share of a month's spending, as a positive amount.
 *
 * [categoryId] rather than a [Category] -- category data lives on its own
 * flow (`CategoryRepository`), and pairing the two is the caller's job, the
 * same split `BudgetRow` makes for a budget and its category.
 */
data class CategorySpend(val categoryId: String, val spent: Money)

/**
 * A month's expense spending, partitioned two ways: by category, and the
 * uncategorised remainder. Together the two halves are exhaustive over every
 * expense transaction in [month] -- [categorySpend] only ever contains a row
 * for a category with live spend, and [uncategorisedSpend] uses the same
 * "`category_id IS NULL` or points at a tombstoned category" condition
 * `TransactionDao.observeUncategorisedSpend` already established for the
 * budgets screen (docs/budgets-design.md §2.2), so no expense transaction
 * falls between the two.
 *
 * Income and transfers are absent by construction, not by category id: the
 * underlying query filters by sign (`amount_minor < 0`), the same principle
 * §2.2 uses for a budget's spend, so a positive transaction never reaches
 * either half regardless of what it is filed under.
 */
data class CategoryBreakdown(
    val month: YearMonth,
    val categorySpend: List<CategorySpend>,
    val uncategorisedSpend: Money,
) {
    val totalSpend: Money get() = categorySpend.fold(uncategorisedSpend) { acc, spend -> acc + spend.spent }

    val hasAnySpend: Boolean get() = totalSpend > Money.ZERO
}
