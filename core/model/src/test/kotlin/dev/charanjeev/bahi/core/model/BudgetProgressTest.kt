package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BudgetProgressTest {

    private val august = YearMonth.of(2026, 8)

    private fun budget(limit: Money = Money(800_000)) = Budget(
        id = "budget-1",
        categoryId = "food",
        month = august,
        limit = limit,
        currencyCode = "INR",
    )

    private fun progress(limit: Money = Money(800_000), spent: Money) =
        BudgetProgress(budget = budget(limit), spent = spent)

    @Test
    fun `remaining is what is left of the limit`() {
        assertThat(progress(spent = Money(300_000)).remaining).isEqualTo(Money(500_000))
    }

    @Test
    fun `remaining goes negative once the limit is passed`() {
        // The over-budget figure the UI shows is this value's magnitude, so it
        // has to keep going rather than clamp at zero.
        assertThat(progress(spent = Money(834_000)).remaining).isEqualTo(Money(-34_000))
    }

    @Test
    fun `spending exactly the limit is on budget, not over it`() {
        val exactly = progress(spent = Money(800_000))
        assertThat(exactly.isOverBudget).isFalse()
        assertThat(exactly.remaining).isEqualTo(Money.ZERO)
    }

    @Test
    fun `one minor unit past the limit is over budget`() {
        assertThat(progress(spent = Money(800_001)).isOverBudget).isTrue()
    }

    @Test
    fun `fraction is not clamped, so an over-budget bar can overflow`() {
        assertThat(progress(spent = Money(1_200_000)).fractionOfLimit).isEqualTo(1.5f)
    }

    @Test
    fun `a zero limit with spending against it reads as full, not as infinity`() {
        // A user can create a zero budget, and `spent / 0` is Infinity --
        // which a progress bar renders as full or as empty depending on the
        // toolkit. Deciding it here means it can't be decided twice.
        val fraction = progress(limit = Money.ZERO, spent = Money(50_000)).fractionOfLimit
        assertThat(fraction).isEqualTo(1f)
        assertThat(fraction.isFinite()).isTrue()
    }

    @Test
    fun `a zero limit with nothing spent reads as empty, not as NaN`() {
        val fraction = progress(limit = Money.ZERO, spent = Money.ZERO).fractionOfLimit
        assertThat(fraction).isEqualTo(0f)
        assertThat(fraction.isNaN()).isFalse()
    }

    @Test
    fun `a zero limit is over budget as soon as anything is spent`() {
        assertThat(progress(limit = Money.ZERO, spent = Money(1)).isOverBudget).isTrue()
    }

    // --- MonthlyBudgets: the two months that must not look the same ---

    private fun monthlyBudgets(uncategorised: Money) = MonthlyBudgets(
        month = august,
        // Identical in both cases, which is the point: uncategorised money
        // isn't attributable to a category, so no budget row can reflect it.
        budgets = listOf(progress(spent = Money.ZERO)),
        uncategorisedSpend = uncategorised,
    )

    @Test
    fun `a month with nothing in it and a month of only uncategorised spending are different values`() {
        val empty = monthlyBudgets(uncategorised = Money.ZERO)
        val allUncategorised = monthlyBudgets(uncategorised = Money(620_000))

        // Every budget reads zero in both -- so if the screen were handed only
        // the budget list, these two months would be indistinguishable.
        assertThat(empty.budgets.map { it.spent }).containsExactly(Money.ZERO)
        assertThat(allUncategorised.budgets.map { it.spent }).containsExactly(Money.ZERO)

        assertThat(empty).isNotEqualTo(allUncategorised)
        assertThat(empty.hasUncategorisedSpend).isFalse()
        assertThat(allUncategorised.hasUncategorisedSpend).isTrue()
    }
}
