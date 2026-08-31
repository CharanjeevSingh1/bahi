package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CategoryBreakdownTest {

    private val august = YearMonth.of(2026, 8)

    private fun breakdown(categorySpend: List<CategorySpend>, uncategorised: Money) =
        CategoryBreakdown(month = august, categorySpend = categorySpend, uncategorisedSpend = uncategorised)

    @Test
    fun `totalSpend adds categorised and uncategorised spend`() {
        val breakdown = breakdown(
            categorySpend = listOf(CategorySpend("food", Money(300_000)), CategorySpend("transport", Money(100_000))),
            uncategorised = Money(50_000),
        )

        assertThat(breakdown.totalSpend).isEqualTo(Money(450_000))
    }

    @Test
    fun `hasAnySpend is false when nothing was spent at all`() {
        assertThat(breakdown(emptyList(), Money.ZERO).hasAnySpend).isFalse()
    }

    @Test
    fun `hasAnySpend is true from uncategorised spend alone`() {
        assertThat(breakdown(emptyList(), Money(1)).hasAnySpend).isTrue()
    }

    // --- the two states that must not be conflated, same instinct as MonthlyBudgets ---

    @Test
    fun `an empty month and an all-uncategorised month have identical category spend but differ in total`() {
        val empty = breakdown(emptyList(), Money.ZERO)
        val allUncategorised = breakdown(emptyList(), Money(620_000))

        assertThat(empty.categorySpend).isEmpty()
        assertThat(allUncategorised.categorySpend).isEmpty()
        assertThat(empty).isNotEqualTo(allUncategorised)
        assertThat(empty.hasAnySpend).isFalse()
        assertThat(allUncategorised.hasAnySpend).isTrue()
    }
}
