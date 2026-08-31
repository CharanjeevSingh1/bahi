package dev.charanjeev.bahi.feature.insights

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryBreakdown
import dev.charanjeev.bahi.core.model.CategorySpend
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.MonthlyTotal
import dev.charanjeev.bahi.core.model.SpendTrend
import dev.charanjeev.bahi.core.model.SystemCategoryIds
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.testing.FixedClock
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val food = Category(id = "food", name = "Food", colorArgb = 0xFFEF5350.toInt(), iconKey = "restaurant")
    private val transport = Category(id = "transport", name = "Transport", colorArgb = 0xFF42A5F5.toInt(), iconKey = "train")
    private val uncategorisedCategory = Category(
        id = SystemCategoryIds.UNCATEGORISED,
        name = "Uncategorised",
        colorArgb = 0xFFBDBDBD.toInt(),
        iconKey = "help_outline",
        isSystemDefined = true,
    )

    private val insightsRepository = FakeInsightsRepository()
    private val budgetRepository = FakeBudgetRepository()
    private val categoryRepository = FakeCategoryRepository(listOf(food, transport, uncategorisedCategory))
    private val clock = FixedClock(LocalDate(2026, 8, 14))

    private val august = YearMonth.of(2026, 8)

    private fun viewModel() =
        InsightsViewModel(insightsRepository, budgetRepository, categoryRepository, clock, SavedStateHandle())

    private fun budget(categoryId: String, month: YearMonth = august, limit: Money = Money(800_000)) =
        Budget(id = "budget-$categoryId-$month", categoryId = categoryId, month = month, limit = limit, currencyCode = "INR")

    @Test
    fun `opens on the month containing today`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertThat(awaitItem().month).isEqualTo(august)
        }
    }

    @Test
    fun `reports no history when the app has never recorded a transaction`() = runTest {
        insightsRepository.setHasAnyHistory(false)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem()).isInstanceOf(InsightsUiState.NoHistory::class.java)
        }
    }

    @Test
    fun `a month with history but no spend is Success, not NoHistory`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setBreakdown(august, CategoryBreakdown(august, emptyList(), Money.ZERO))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.hasAnySpend).isFalse()
        }
    }

    @Test
    fun `category slices are sorted by spend, largest first`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setBreakdown(
            august,
            CategoryBreakdown(
                august,
                categorySpend = listOf(CategorySpend("food", Money(45_000)), CategorySpend("transport", Money(100_000))),
                uncategorisedSpend = Money.ZERO,
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.categorySlices.map { it.category?.id }).containsExactly("transport", "food").inOrder()
        }
    }

    @Test
    fun `uncategorised spend appears as its own slice using the real system category`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setBreakdown(
            august,
            CategoryBreakdown(august, categorySpend = emptyList(), uncategorisedSpend = Money(620_000)),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            val slice = state.categorySlices.single()
            assertThat(slice.category?.id).isEqualTo(SystemCategoryIds.UNCATEGORISED)
            assertThat(slice.spent).isEqualTo(Money(620_000))
            assertThat(state.totalSpend).isEqualTo(Money(620_000))
        }
    }

    @Test
    fun `zero uncategorised spend does not add an empty slice`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setBreakdown(
            august,
            CategoryBreakdown(august, categorySpend = listOf(CategorySpend("food", Money(45_000))), uncategorisedSpend = Money.ZERO),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.categorySlices.map { it.category?.id }).containsExactly("food")
        }
    }

    // --- trend pass-through ---

    @Test
    fun `hasComparison mirrors the repository's trend`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setTrend(
            august,
            SpendTrend(listOf(MonthlyTotal(YearMonth.of(2026, 7), Money(10_000)), MonthlyTotal(august, Money(20_000)))),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.hasComparison).isTrue()
            assertThat(state.trend.map { it.spent }).containsExactly(Money(10_000), Money(20_000)).inOrder()
        }
    }

    @Test
    fun `a single-month trend reports no comparison`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        insightsRepository.setTrend(august, SpendTrend(listOf(MonthlyTotal(august, Money.ZERO))))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as InsightsUiState.Success).hasComparison).isFalse()
        }
    }

    // --- over-budget: three states that must not be conflated ---

    @Test
    fun `no budgets at all is distinct from budgets with none over`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.hasAnyBudgets).isFalse()
            assertThat(state.overBudget).isEmpty()
        }
    }

    @Test
    fun `budgets set but none exceeded reports an empty over-budget list with hasAnyBudgets true`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        budgetRepository.seed(budget("food"), spent = Money(300_000))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.hasAnyBudgets).isTrue()
            assertThat(state.overBudget).isEmpty()
        }
    }

    @Test
    fun `only budgets over their limit are listed, with their category`() = runTest {
        insightsRepository.setHasAnyHistory(true)
        budgetRepository.seed(budget("food", limit = Money(800_000)), spent = Money(900_000))
        budgetRepository.seed(budget("transport", limit = Money(500_000)), spent = Money(100_000))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as InsightsUiState.Success
            assertThat(state.overBudget).hasSize(1)
            val row = state.overBudget.single()
            assertThat(row.category?.id).isEqualTo("food")
            assertThat(row.progress.remaining.absolute).isEqualTo(Money(100_000))
        }
    }

    // --- month navigation ---

    @Test
    fun `moving to the previous month re-queries that month`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onPreviousMonth()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem().month).isEqualTo(YearMonth.of(2026, 7))
        }
    }

    @Test
    fun `month navigation rolls over the year`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        repeat(5) { viewModel.onNextMonth() }
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem().month).isEqualTo(YearMonth.of(2027, 1))
        }
    }
}
