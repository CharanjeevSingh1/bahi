package dev.charanjeev.bahi.feature.transactions

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.DateWindow
import kotlinx.datetime.LocalDate
import org.junit.Test

class TransactionFilterStateTest {

    private val today = LocalDate(2026, 3, 14)

    private val categories = listOf("food", "groceries", "shopping", "transfers", "transport")
        .map { id -> Category(id = id, name = id.replaceFirstChar { it.uppercase() }, colorArgb = 0xFF000000.toInt(), iconKey = id) }

    @Test
    fun `no date range option resolves to no date window`() {
        val state = TransactionFilterState(categoryIds = setOf("food"))

        assertThat(state.resolveDateWindow(today)).isNull()
    }

    @Test
    fun `this month resolves to the calendar month today falls in`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.THIS_MONTH)

        assertThat(state.resolveDateWindow(today))
            .isEqualTo(DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31)))
    }

    @Test
    fun `last month resolves to the previous calendar month`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.LAST_MONTH)

        assertThat(state.resolveDateWindow(today))
            .isEqualTo(DateWindow(LocalDate(2026, 2, 1), LocalDate(2026, 2, 28)))
    }

    @Test
    fun `last month wraps across a year boundary`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.LAST_MONTH)

        assertThat(state.resolveDateWindow(LocalDate(2026, 1, 15)))
            .isEqualTo(DateWindow(LocalDate(2025, 12, 1), LocalDate(2025, 12, 31)))
    }

    @Test
    fun `this month wraps across a year boundary in December`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.THIS_MONTH)

        assertThat(state.resolveDateWindow(LocalDate(2026, 12, 25)))
            .isEqualTo(DateWindow(LocalDate(2026, 12, 1), LocalDate(2026, 12, 31)))
    }

    @Test
    fun `custom resolves to exactly the chosen bounds`() {
        val state = TransactionFilterState(
            dateRangeOption = DateRangeOption.CUSTOM,
            customFrom = LocalDate(2026, 1, 10),
            customTo = LocalDate(2026, 2, 20),
        )

        assertThat(state.resolveDateWindow(today))
            .isEqualTo(DateWindow(LocalDate(2026, 1, 10), LocalDate(2026, 2, 20)))
    }

    @Test
    fun `custom with no bounds chosen yet resolves to no date window`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.CUSTOM)

        assertThat(state.resolveDateWindow(today)).isNull()
    }

    @Test
    fun `toRepositoryFilter carries category ids and the resolved window through`() {
        val state = TransactionFilterState(categoryIds = setOf("food", "rent"), dateRangeOption = DateRangeOption.THIS_MONTH)

        val filter = state.toRepositoryFilter(today)

        assertThat(filter.categoryIds).containsExactly("food", "rent")
        assertThat(filter.dateWindow).isEqualTo(DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31)))
    }

    @Test
    fun `no filter at all is not active`() {
        assertThat(TransactionFilterState().isActive).isFalse()
    }

    @Test
    fun `a category alone is active`() {
        assertThat(TransactionFilterState(categoryIds = setOf("food")).isActive).isTrue()
    }

    @Test
    fun `a date range alone is active`() {
        assertThat(TransactionFilterState(dateRangeOption = DateRangeOption.THIS_MONTH).isActive).isTrue()
    }

    // --- Net total label ---

    @Test
    fun `default (no filter) labels the total with the current month`() {
        assertThat(TransactionFilterState().toNetPeriod(today)).isEqualTo(NetPeriod.Month(today))
    }

    @Test
    fun `this month labels the total with the current month`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.THIS_MONTH)

        assertThat(state.toNetPeriod(today)).isEqualTo(NetPeriod.Month(today))
    }

    @Test
    fun `last month labels the total with last month`() {
        val state = TransactionFilterState(dateRangeOption = DateRangeOption.LAST_MONTH)

        assertThat(state.toNetPeriod(today)).isEqualTo(NetPeriod.Month(LocalDate(2026, 2, 1)))
    }

    @Test
    fun `custom range labels the total with the chosen range`() {
        val state = TransactionFilterState(
            dateRangeOption = DateRangeOption.CUSTOM,
            customFrom = LocalDate(2026, 1, 10),
            customTo = LocalDate(2026, 2, 20),
        )

        assertThat(state.toNetPeriod(today)).isEqualTo(NetPeriod.Range(LocalDate(2026, 1, 10), LocalDate(2026, 2, 20)))
    }

    @Test
    fun `category filter with no date range labels the total as filtered, not a month`() {
        val state = TransactionFilterState(categoryIds = setOf("food"))

        assertThat(state.toNetPeriod(today)).isEqualTo(NetPeriod.Filtered)
    }

    // --- Category chip content ---

    @Test
    fun `no categories selected shows the placeholder`() {
        val state = TransactionFilterState()

        assertThat(state.categoryChipContent(categories)).isEqualTo(CategoryChipContent.Placeholder)
    }

    @Test
    fun `one category selected shows its name`() {
        val state = TransactionFilterState(categoryIds = setOf("food"))

        assertThat(state.categoryChipContent(categories)).isEqualTo(CategoryChipContent.Names(listOf("Food")))
    }

    @Test
    fun `two categories selected shows both names`() {
        val state = TransactionFilterState(categoryIds = setOf("food", "groceries"))

        assertThat(state.categoryChipContent(categories))
            .isEqualTo(CategoryChipContent.Names(listOf("Food", "Groceries")))
    }

    @Test
    fun `three categories selected shows a count instead of names`() {
        val state = TransactionFilterState(categoryIds = setOf("food", "groceries", "shopping"))

        assertThat(state.categoryChipContent(categories)).isEqualTo(CategoryChipContent.Count(3))
    }

    @Test
    fun `five categories selected shows a count instead of names`() {
        val state = TransactionFilterState(categoryIds = categories.map { it.id }.toSet())

        assertThat(state.categoryChipContent(categories)).isEqualTo(CategoryChipContent.Count(5))
    }
}
