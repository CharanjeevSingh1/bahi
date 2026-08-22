package dev.charanjeev.bahi.feature.transactions

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Test

class TransactionTotalsTest {

    @Test
    fun `sums income and expenses within the given month`() {
        val month = LocalDate(2026, 8, 15)
        val transactions = listOf(
            TestData.transaction(id = "a", amount = Money(-45_00), date = LocalDate(2026, 8, 1)),
            TestData.transaction(id = "b", amount = Money(150_000_00), date = LocalDate(2026, 8, 20)),
        )

        assertThat(netTotalForMonth(transactions, month)).isEqualTo(Money(-45_00) + Money(150_000_00))
    }

    @Test
    fun `excludes transactions from a different month even in the same year`() {
        val month = LocalDate(2026, 8, 15)
        val transactions = listOf(
            TestData.transaction(id = "a", amount = Money(-45_00), date = LocalDate(2026, 7, 31)),
            TestData.transaction(id = "b", amount = Money(-99_00), date = LocalDate(2026, 9, 1)),
        )

        assertThat(netTotalForMonth(transactions, month)).isEqualTo(Money.ZERO)
    }

    @Test
    fun `excludes a transaction from the same month number in a different year`() {
        val month = LocalDate(2026, 1, 15)
        val transactions = listOf(
            TestData.transaction(id = "a", amount = Money(-45_00), date = LocalDate(2025, 1, 15)),
        )

        assertThat(netTotalForMonth(transactions, month)).isEqualTo(Money.ZERO)
    }

    @Test
    fun `includes the last day of the month but not the first day of the next`() {
        val month = LocalDate(2026, 2, 1)
        val transactions = listOf(
            TestData.transaction(id = "a", amount = Money(-100_00), date = LocalDate(2026, 2, 28)),
            TestData.transaction(id = "b", amount = Money(-200_00), date = LocalDate(2026, 3, 1)),
        )

        assertThat(netTotalForMonth(transactions, month)).isEqualTo(Money(-100_00))
    }

    @Test
    fun `is zero with no transactions`() {
        assertThat(netTotalForMonth(emptyList(), LocalDate(2026, 8, 15))).isEqualTo(Money.ZERO)
    }
}
