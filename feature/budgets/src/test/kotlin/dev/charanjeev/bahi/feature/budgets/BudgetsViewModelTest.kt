package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
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
class BudgetsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val food = Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant")
    private val transport = Category(id = "transport", name = "Transport", colorArgb = 0, iconKey = "train")

    private val budgetRepository = FakeBudgetRepository()
    private val categoryRepository = FakeCategoryRepository(listOf(food, transport))
    private val clock = FixedClock(LocalDate(2026, 8, 14))

    private val august = YearMonth.of(2026, 8)

    private fun viewModel() =
        BudgetsViewModel(budgetRepository, categoryRepository, clock, SavedStateHandle())

    private fun budget(
        id: String = "budget-1",
        categoryId: String = "food",
        month: YearMonth = august,
        limit: Money = Money(800_000),
    ) = Budget(id, categoryId, month, limit, "INR")

    @Test
    fun `opens on the month containing today`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertThat(awaitItem().month).isEqualTo(august)
        }
    }

    @Test
    fun `emits empty when the month has no budgets`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem()).isInstanceOf(BudgetsUiState.Empty::class.java)
        }
    }

    @Test
    fun `lists budgets with their spend and category`() = runTest {
        budgetRepository.seed(budget(id = "b-food", categoryId = "food"), spent = Money(430_000))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetsUiState.Success
            assertThat(state.budgets.single().progress.spent).isEqualTo(Money(430_000))
            assertThat(state.budgets.single().category?.name).isEqualTo("Food")
        }
    }

    // --- the two months that must not render identically ---

    @Test
    fun `a month with no transactions reports nothing counted and no uncategorised spending`() = runTest {
        budgetRepository.seed(budget(), spent = Money.ZERO)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetsUiState.Success
            assertThat(state.nothingCountedYet).isTrue()
            assertThat(state.hasUncategorisedSpend).isFalse()
        }
    }

    @Test
    fun `a month of entirely uncategorised spending has identical budget rows but is a different state`() =
        runTest {
            budgetRepository.seed(budget(), spent = Money.ZERO)
            budgetRepository.setUncategorisedSpend(Money(620_000))
            val viewModel = viewModel()

            viewModel.uiState.test {
                skipItems(1) // Loading
                val state = awaitItem() as BudgetsUiState.Success
                // Identical to the empty month above on every budget row --
                // correctly, since uncategorised money can't be attributed to
                // a category. The uncategorised figure is the whole
                // difference, and it is what the screen renders differently.
                assertThat(state.budgets.map { it.progress.spent }).containsExactly(Money.ZERO)
                assertThat(state.nothingCountedYet).isTrue()
                assertThat(state.hasUncategorisedSpend).isTrue()
                assertThat(state.uncategorisedSpend).isEqualTo(Money(620_000))
            }
        }

    @Test
    fun `uncategorised spending is reported even when the month has no budgets at all`() = runTest {
        // Otherwise the emptiest screen is the one that tells the user least
        // about where their money actually went.
        budgetRepository.setUncategorisedSpend(Money(620_000))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetsUiState.Empty
            assertThat(state.hasUncategorisedSpend).isTrue()
            assertThat(state.uncategorisedSpend).isEqualTo(Money(620_000))
        }
    }

    @Test
    fun `nothingCountedYet is false as soon as any budget has spend`() = runTest {
        budgetRepository.seed(budget(id = "a", categoryId = "food"), spent = Money(430_000))
        budgetRepository.seed(budget(id = "b", categoryId = "transport"), spent = Money.ZERO)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as BudgetsUiState.Success).nothingCountedYet).isFalse()
        }
    }

    // --- month navigation ---

    @Test
    fun `moving to the previous month re-queries that month`() = runTest {
        budgetRepository.seed(budget(id = "july", month = YearMonth.of(2026, 7)))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onPreviousMonth()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem()
            assertThat(state.month).isEqualTo(YearMonth.of(2026, 7))
            assertThat((state as BudgetsUiState.Success).budgets.single().progress.budget.id).isEqualTo("july")
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

    @Test
    fun `currentMonth reports the month on screen, not today`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onNextMonth()

        // The add screen opens on the month being looked at -- resolving it a
        // second time from the clock is how the two screens disagree.
        assertThat(viewModel.currentMonth()).isEqualTo(YearMonth.of(2026, 9))
    }

    // --- delete ---

    @Test
    fun `deleting asks before it happens`() = runTest {
        budgetRepository.seed(budget(id = "b-food"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDeleteRequested("b-food", "Food")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetsUiState.Success
            assertThat(state.pendingDelete?.categoryName).isEqualTo("Food")
        }
        assertThat(budgetRepository.deleted).isEmpty()
    }

    @Test
    fun `confirming a delete removes the budget`() = runTest {
        budgetRepository.seed(budget(id = "b-food"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDeleteRequested("b-food", "Food")

        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertThat(budgetRepository.deleted).containsExactly("b-food")
    }

    @Test
    fun `cancelling a delete leaves the budget alone`() = runTest {
        budgetRepository.seed(budget(id = "b-food"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDeleteRequested("b-food", "Food")

        viewModel.onDeleteCancelled()
        advanceUntilIdle()

        assertThat(budgetRepository.deleted).isEmpty()
    }
}
