package dev.charanjeev.bahi.core.model

/**
 * A [Budget] and what has actually been spent against it in the month it
 * covers.
 *
 * [spent] is positive. Expenses are stored negative ([Transaction.isExpense]
 * is `amount.isNegative`) and the query negates the sum, so "spent ₹4,300 of
 * ₹8,000" reads the way it is displayed instead of every call site having to
 * remember to flip the sign first.
 *
 * There is no way to build one of these without a spend figure, and that is
 * the point: spend is computed by query (docs/budgets-design.md §4.2), so
 * pairing the two here means a screen can't end up rendering a budget having
 * quietly folded over a list of transactions to get the number.
 */
data class BudgetProgress(
    val budget: Budget,
    val spent: Money,
) {
    /** Goes negative once [spent] passes the limit; §2.5's "₹340 over budget" is its [Money.absolute]. */
    val remaining: Money get() = budget.limit - spent

    /**
     * Three states, not two.
     *
     * Splitting [BudgetStatus.NEAR_LIMIT] out of "under" is the difference
     * between a budget that is working and one that is about to stop being
     * useful. Rendering ₹0 left the same as ₹3,700 left hides the moment the
     * user most needs to see -- by the time the display changes at all, the
     * limit has already been passed and the warning is a post-mortem.
     *
     * The band starts at [NEAR_LIMIT_FRACTION] rather than only at exactly
     * 100%: ₹0 left and ₹50 left are the same practical situation, and a
     * signal that fires only on an exact equality would almost never fire at
     * all, since spend rarely lands on a round limit.
     */
    val status: BudgetStatus
        get() = when {
            spent > budget.limit -> BudgetStatus.OVER
            // Guarded on the limit, not the fraction: fractionOfLimit reports
            // 0f for a zero limit with nothing spent, which must stay UNDER
            // rather than being swept into the warning band.
            budget.limit > Money.ZERO && fractionOfLimit >= NEAR_LIMIT_FRACTION -> BudgetStatus.NEAR_LIMIT
            else -> BudgetStatus.UNDER
        }

    /** Strictly past the limit -- spending exactly the limit is on budget, not over it. */
    val isOverBudget: Boolean get() = status == BudgetStatus.OVER

    /** Exactly on the line. Worth its own name: "₹0.00 left" reads better as "limit reached". */
    val isExactlyAtLimit: Boolean get() = budget.limit > Money.ZERO && spent == budget.limit

    /**
     * How full the progress bar is. Deliberately not clamped at 1f, so an
     * over-budget bar can overflow rather than sit pinned at full (§2.5).
     *
     * A `Float` here does not contradict rule 5: that rule is about currency,
     * and this is a ratio of two amounts, not an amount. The division lives
     * here rather than in the composable so the zero-limit case is handled
     * once -- `spent / 0` is Infinity or NaN, which a progress bar renders as
     * a full bar or an empty one depending on which toolkit gets it.
     *
     * The budget editor no longer lets a zero limit be created (it can only
     * ever be over budget, which makes every state it has degenerate), so
     * this guard is defence rather than a live path -- the same standing as
     * applyRules' blank-needle filter. It still matters: M4 sync will write
     * budgets this device's editor never validated, and a repository call is
     * not obliged to go through a screen.
     */
    val fractionOfLimit: Float
        get() = when {
            budget.limit.minorUnits <= 0L -> if (spent > Money.ZERO) 1f else 0f
            else -> spent.minorUnits.toFloat() / budget.limit.minorUnits.toFloat()
        }

    companion object {
        /**
         * 90%. A round number chosen for being early enough to act on and
         * late enough not to cry wolf -- there is no research behind it, and
         * it is one constant to change if it turns out to nag.
         */
        const val NEAR_LIMIT_FRACTION = 0.9f
    }
}

/**
 * What a budget row should be telling the user, decided once here rather than
 * by each screen comparing numbers and reaching its own conclusion.
 */
enum class BudgetStatus {
    /** Comfortably within the limit. */
    UNDER,

    /** At or approaching the limit -- the state a warning colour exists for. */
    NEAR_LIMIT,

    /** Past the limit. */
    OVER,
}

/**
 * Everything the budgets screen needs for one month, in one value.
 *
 * The two halves travel together deliberately. A month with no transactions
 * at all and a month whose spending is *entirely uncategorised* both leave
 * every budget reading ₹0 spent -- and correctly so, since uncategorised
 * money isn't attributable to any category (§2.2). But they are not the same
 * month: one is empty, the other has real money in it that no budget can
 * see. The whole difference is carried by [uncategorisedSpend], so handing a
 * screen the budget list alone would make the two states genuinely
 * indistinguishable at the point of rendering them.
 *
 * Bundling also means both numbers arrive in the same emission. Observed as
 * two separate flows they'd be combined anyway, and could briefly disagree
 * about which write they reflect.
 */
data class MonthlyBudgets(
    val month: YearMonth,
    val budgets: List<BudgetProgress>,
    /**
     * Expense spending in [month] filed under no category at all, as a
     * positive amount. Structurally can't count against any budget -- a
     * budget requires a real category id -- so it is shown on its own line
     * rather than folded in or dropped (§2.2).
     */
    val uncategorisedSpend: Money,
) {
    /** The flag that tells an empty month apart from an uncategorised one; see the class doc. */
    val hasUncategorisedSpend: Boolean get() = uncategorisedSpend > Money.ZERO
}
