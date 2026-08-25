package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import org.junit.Test

class BudgetMappersTest {

    @Test
    fun `budget round-trips through the entity unchanged`() {
        val budget = Budget(
            id = "budget-1",
            categoryId = "food",
            month = YearMonth.of(2026, 8),
            limit = Money(800_000),
            currencyCode = "INR",
        )

        assertThat(toDomain(toEntity(budget, createdAt = 1, updatedAt = 2))).isEqualTo(budget)
    }

    @Test
    fun `budget month is stored zero-padded so the column sorts lexicographically`() {
        val budget = Budget(
            id = "budget-1",
            categoryId = "food",
            month = YearMonth.of(2026, 9),
            limit = Money(1),
            currencyCode = "INR",
        )

        assertThat(toEntity(budget, createdAt = 0, updatedAt = 0).yearMonth).isEqualTo("2026-09")
    }

    @Test
    fun `rule round-trips through the entity unchanged`() {
        val rule = CategoryRule(
            id = "rule-1",
            categoryId = "food",
            merchantContains = "SWIGGY",
            priority = 3,
        )

        assertThat(toDomain(toEntity(rule, createdAt = 1, updatedAt = 2))).isEqualTo(rule)
    }
}
