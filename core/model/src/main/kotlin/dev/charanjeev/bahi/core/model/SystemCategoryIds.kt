package dev.charanjeev.bahi.core.model

/**
 * The ids of system categories that layers above `:core:data` have to reason
 * about by name.
 *
 * The seeded rows themselves live in `:core:data` (`systemCategories`) and
 * stay there -- this is not a second copy of that list, only the handful of
 * ids other modules genuinely need. The budgets screen needs two of them
 * (§2.2), and a feature module cannot see an `internal` in `:core:data`, so
 * the alternative was string literals in a feature: if an id ever changed,
 * the picker would quietly stop excluding the right categories and nothing
 * would fail.
 *
 * **Never change a value here.** These ids are the entire idempotency
 * mechanism for seeding -- changing one orphans the existing row and reseeds
 * a duplicate under the new id.
 */
object SystemCategoryIds {

    /** A budget against income would read ₹0 spent forever: the totals query only sees negative amounts. */
    const val INCOME = "income"

    /** Same as [INCOME] -- a transfer is not spending, so it can never accumulate against a budget. */
    const val TRANSFERS = "transfers"

    /**
     * Exists for the category picker, not for transaction data: nothing in
     * the app writes this id onto a transaction, which is why the
     * uncategorised-spend query matches `category_id IS NULL` instead
     * (§2.2).
     */
    const val UNCATEGORISED = "uncategorised"

    /** Categories a budget can never accumulate spend against, so the budget picker leaves them out. */
    val NEVER_BUDGETABLE: Set<String> = setOf(INCOME, TRANSFERS)
}
