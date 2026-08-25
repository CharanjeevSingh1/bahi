package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.SystemCategoryIds
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.feature.budgets.navigation.BudgetIdArg
import dev.charanjeev.bahi.feature.budgets.navigation.MonthArg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BudgetEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val august = YearMonth.of(2026, 8)

    private val categories = listOf(
        Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant"),
        Category(id = SystemCategoryIds.INCOME, name = "Income", colorArgb = 0, iconKey = "payments"),
        Category(id = SystemCategoryIds.TRANSFERS, name = "Transfers", colorArgb = 0, iconKey = "swap_horiz"),
    )

    private val budgetRepository = FakeBudgetRepository()
    private val categoryRepository = FakeCategoryRepository(categories)

    private fun viewModel(budgetId: String? = null) = BudgetEditorViewModel(
        budgetRepository,
        categoryRepository,
        SavedStateHandle(
            buildMap {
                put(MonthArg, august.toString())
                if (budgetId != null) put(BudgetIdArg, budgetId)
            },
        ),
    )

    @Test
    fun `the picker leaves out categories a budget can never accumulate spend against`() = runTest {
        // Income and Transfers only ever carry positive amounts, and the
        // totals query only sees negative ones -- a budget against either
        // would read zero forever (§2.2).
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetEditorUiState.Editing
            assertThat(state.categories.map { it.id }).containsExactly("food")
        }
    }

    @Test
    fun `an empty limit cannot be saved`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetEditorUiState.Editing
            assertThat(state.canSave).isFalse()
            assertThat(state.limitError).isEqualTo(LimitError.EMPTY)
        }
    }

    @Test
    fun `a budget cannot be saved without a category`() = runTest {
        val viewModel = viewModel()
        viewModel.onLimitTextChange("8000")

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as BudgetEditorUiState.Editing).canSave).isFalse()
        }
    }

    @Test
    fun `a zero limit is refused, because it can only ever be over budget`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("0")

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetEditorUiState.Editing
            assertThat(state.limitError).isEqualTo(LimitError.NOT_POSITIVE)
            assertThat(state.canSave).isFalse()
        }
    }

    @Test
    fun `onSave refuses a zero limit even when called directly`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("0")

        viewModel.onSave()
        advanceUntilIdle()

        budgetRepository.observeBudgets(august).test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `the smallest positive limit is still allowed`() = runTest {
        // The rule is "above zero", not "above some round number" -- a ₹0.01
        // budget is odd but it has real states, unlike a zero one.
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("0.01")

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetEditorUiState.Editing
            assertThat(state.limitError).isNull()
            assertThat(state.limit).isEqualTo(Money(1))
        }
    }

    @Test
    fun `a grouped amount parses the way a bank statement writes it`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("8,000.50")

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as BudgetEditorUiState.Editing).limit).isEqualTo(Money(800_050))
        }
    }

    @Test
    fun `saving stores the parsed limit for the month it was opened on`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("8000")

        viewModel.onSave()
        advanceUntilIdle()

        val saved = budgetRepository.observeBudgets(august).let { flow ->
            var result: Budget? = null
            flow.test { result = awaitItem().singleOrNull() }
            result
        }
        assertThat(saved?.limit).isEqualTo(Money(800_000))
        assertThat(saved?.month).isEqualTo(august)
    }

    @Test
    fun `onSave refuses an invalid limit even when called directly`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("")

        viewModel.onSave()
        advanceUntilIdle()

        budgetRepository.observeBudgets(august).test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `a second budget for the same category and month replaces the first`() = runTest {
        // The editor generates a fresh id on every save and relies on the
        // repository keying on (category, month) instead -- so "add" on a
        // category that already has a budget edits it rather than duplicating.
        budgetRepository.seed(Budget("existing", "food", august, Money(800_000), "INR"))
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onLimitTextChange("9500")

        viewModel.onSave()
        advanceUntilIdle()

        budgetRepository.observeBudgets(august).test {
            val budgets = awaitItem()
            assertThat(budgets).hasSize(1)
            assertThat(budgets.single().id).isEqualTo("existing")
            assertThat(budgets.single().limit).isEqualTo(Money(950_000))
        }
    }

    @Test
    fun `editing seeds the form from the stored budget`() = runTest {
        budgetRepository.seed(Budget("b-1", "food", august, Money(800_000), "INR"))
        val viewModel = viewModel(budgetId = "b-1")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as BudgetEditorUiState.Editing
            assertThat(state.mode).isEqualTo(BudgetEditorMode.EDIT)
            assertThat(state.limitText).isEqualTo("8000")
            assertThat(state.categoryId).isEqualTo("food")
        }
    }

    @Test
    fun `editing seeds a limit with paise without losing them`() = runTest {
        budgetRepository.seed(Budget("b-1", "food", august, Money(800_050), "INR"))
        val viewModel = viewModel(budgetId = "b-1")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as BudgetEditorUiState.Editing).limitText).isEqualTo("8000.50")
        }
    }

    @Test
    fun `editing a budget that no longer exists surfaces an error rather than an empty form`() = runTest {
        val viewModel = viewModel(budgetId = "gone")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem()).isInstanceOf(BudgetEditorUiState.Error::class.java)
        }
    }
}
