package dev.charanjeev.bahi.feature.transactions

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Test

class TransactionGroupingTest {

    private fun item(id: String, date: LocalDate) =
        TransactionListItem(transaction = TestData.transaction(id = id, date = date), category = null)

    @Test
    fun `labels a transaction dated today as Today`() {
        val today = LocalDate(2026, 3, 14)
        val groups = groupByDate(listOf(item("a", today)), today)

        assertThat(groups.single().header).isEqualTo(DateHeader.Today)
    }

    @Test
    fun `labels a transaction dated one day before today as Yesterday`() {
        val today = LocalDate(2026, 3, 14)
        val groups = groupByDate(listOf(item("a", LocalDate(2026, 3, 13))), today)

        assertThat(groups.single().header).isEqualTo(DateHeader.Yesterday)
    }

    @Test
    fun `labels a transaction two days before today with its date, not Yesterday`() {
        val today = LocalDate(2026, 3, 14)
        val date = LocalDate(2026, 3, 12)
        val groups = groupByDate(listOf(item("a", date)), today)

        assertThat(groups.single().header).isEqualTo(DateHeader.Dated(date))
    }

    @Test
    fun `yesterday rolls over a month boundary`() {
        val today = LocalDate(2026, 3, 1)
        val groups = groupByDate(listOf(item("a", LocalDate(2026, 2, 28))), today)

        assertThat(groups.single().header).isEqualTo(DateHeader.Yesterday)
    }

    @Test
    fun `yesterday rolls over a year boundary`() {
        val today = LocalDate(2026, 1, 1)
        val groups = groupByDate(listOf(item("a", LocalDate(2025, 12, 31))), today)

        assertThat(groups.single().header).isEqualTo(DateHeader.Yesterday)
    }

    @Test
    fun `orders groups newest date first`() {
        val today = LocalDate(2026, 3, 14)
        val items = listOf(
            item("old", LocalDate(2026, 3, 1)),
            item("today", today),
            item("yesterday", LocalDate(2026, 3, 13)),
        )

        val groups = groupByDate(items, today)

        assertThat(groups.map { it.header }).containsExactly(
            DateHeader.Today,
            DateHeader.Yesterday,
            DateHeader.Dated(LocalDate(2026, 3, 1)),
        ).inOrder()
    }

    @Test
    fun `keeps transactions on the same date in the order given`() {
        val today = LocalDate(2026, 3, 14)
        val first = item("first", today)
        val second = item("second", today)

        val groups = groupByDate(listOf(first, second), today)

        assertThat(groups.single().items).containsExactly(first, second).inOrder()
    }
}
