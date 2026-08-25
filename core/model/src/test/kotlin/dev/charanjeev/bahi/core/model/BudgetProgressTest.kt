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

    // --- the three states a row can be in ---

    @Test
    fun `comfortably under the limit is UNDER`() {
        assertThat(progress(spent = Money(300_000)).status).isEqualTo(BudgetStatus.UNDER)
    }

    @Test
    fun `approaching the limit is NEAR_LIMIT, not UNDER`() {
        // 90% of 8,000. Rendering this the same as 3,700 left is the warning
        // the user only gets after it stops being useful.
        assertThat(progress(spent = Money(720_000)).status).isEqualTo(BudgetStatus.NEAR_LIMIT)
    }

    @Test
    fun `just below the warning band is still UNDER`() {
        assertThat(progress(spent = Money(719_999)).status).isEqualTo(BudgetStatus.UNDER)
    }

    @Test
    fun `spending exactly the limit is NEAR_LIMIT, not UNDER and not OVER`() {
        // The case that prompted this: zero left rendered identically to
        // plenty left, in the state where the signal matters most.
        val exactly = progress(spent = Money(800_000))
        assertThat(exactly.status).isEqualTo(BudgetStatus.NEAR_LIMIT)
        assertThat(exactly.isExactlyAtLimit).isTrue()
    }

    @Test
    fun `past the limit is OVER`() {
        val over = progress(spent = Money(834_000))
        assertThat(over.status).isEqualTo(BudgetStatus.OVER)
        // Over is not also "at the limit" -- the copy for the two differs.
        assertThat(over.isExactlyAtLimit).isFalse()
    }

    @Test
    fun `isOverBudget and status cannot disagree`() {
        // isOverBudget derives from status rather than recomparing, so there
        // is one decision about where the line is, not two.
        listOf(Money.ZERO, Money(300_000), Money(800_000), Money(800_001), Money(2_000_000)).forEach { spent ->
            val row = progress(spent = spent)
            assertThat(row.isOverBudget).isEqualTo(row.status == BudgetStatus.OVER)
        }
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

    @Test
    fun `a zero limit with nothing spent is UNDER rather than swept into the warning band`() {
        // fractionOfLimit reports 0f here, and the NEAR_LIMIT test is guarded
        // on the limit as well as the fraction so this can't read as 0f >= 0.9f
        // by some future rearrangement of the comparison.
        assertThat(progress(limit = Money.ZERO, spent = Money.ZERO).status).isEqualTo(BudgetStatus.UNDER)
        assertThat(progress(limit = Money.ZERO, spent = Money.ZERO).isExactlyAtLimit).isFalse()
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
