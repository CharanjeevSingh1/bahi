package dev.charanjeev.bahi.feature.transactions

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.FixedClock
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class TransactionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val transactionRepository = FakeTransactionRepository()
    private val categoryRepository = FakeCategoryRepository()
    private val clock = FixedClock(LocalDate(2026, 3, 14))

    private fun viewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        TransactionsViewModel(transactionRepository, categoryRepository, clock, savedStateHandle)

    @Test
    fun `starts in loading state`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.uiState.value).isEqualTo(TransactionsUiState.Loading)
    }

    @Test
    fun `emits empty when repository has no transactions`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Loading)
            transactionRepository.emit(emptyList())
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)
        }
    }

    @Test
    fun `emits success with transactions grouped and totalled`() = runTest {
        val viewModel = viewModel()
        val transactions = listOf(
            TestData.transaction(id = "a", description = "COFFEE", date = LocalDate(2026, 3, 14)),
            TestData.transaction(id = "b", description = "RENT", date = LocalDate(2026, 3, 14)),
        )

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(transactions)

            val state = awaitItem()
            assertThat(state).isInstanceOf(TransactionsUiState.Success::class.java)
            val success = state as TransactionsUiState.Success
            assertThat(success.groups).hasSize(1)
            assertThat(success.groups.single().header).isEqualTo(DateHeader.Today)
            assertThat(success.groups.single().items.map { it.transaction })
                .containsExactlyElementsIn(transactions)
                .inOrder()
            assertThat(success.netTotal).isEqualTo(transactions[0].amount + transactions[1].amount)
        }
    }

    @Test
    fun `resolves a transaction's category from the category repository`() = runTest {
        val viewModel = viewModel()
        val category = Category(id = "food", name = "Food", colorArgb = 0xFFEF5350.toInt(), iconKey = "restaurant")
        categoryRepository.emit(listOf(category))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(TestData.transaction(categoryId = "food")))

            val success = awaitItem() as TransactionsUiState.Success
            assertThat(success.groups.single().items.single().category).isEqualTo(category)
        }
    }

    @Test
    fun `delete then undo restores the transaction`() = runTest {
        val viewModel = viewModel()
        val transaction = TestData.transaction(id = "a", date = LocalDate(2026, 3, 14))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(transaction))
            val success = awaitItem() as TransactionsUiState.Success
            val item = success.groups.single().items.single()

            viewModel.onDeleteTransaction(item)
            val pending = awaitItem() as TransactionsUiState.Success
            assertThat(pending.pendingDelete).isEqualTo(item)

            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)

            viewModel.onUndoDelete()
            val restored = awaitItem() as TransactionsUiState.Success
            assertThat(restored.groups.single().items.single().transaction).isEqualTo(transaction)
            assertThat(restored.pendingDelete).isNull()
        }
    }

    @Test
    fun `dismissing the undo snackbar clears the pending delete without restoring`() = runTest {
        val viewModel = viewModel()
        val transaction = TestData.transaction(id = "a", date = LocalDate(2026, 3, 14))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(transaction))
            val item = (awaitItem() as TransactionsUiState.Success).groups.single().items.single()

            viewModel.onDeleteTransaction(item)
            skipItems(1) // Success with pendingDelete set
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)

            viewModel.onDeleteSnackbarDismissed()
            expectNoEvents()
        }
    }

    @Test
    fun `emits error when the repository fails, and retry recovers`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.failWith(RuntimeException("disk on fire"))
            transactionRepository.emit(emptyList())
            val error = awaitItem()
            assertThat(error).isInstanceOf(TransactionsUiState.Error::class.java)
            assertThat((error as TransactionsUiState.Error).message).isEqualTo("disk on fire")

            // The underlying condition is resolved before the user taps retry --
            // a real retry re-runs the same query, which only helps if whatever
            // caused it to fail is actually gone.
            transactionRepository.clearFailure()
            viewModel.onRetry()
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)
        }
    }

    // --- Filtering ---

    @Test
    fun `category and date filters compose -- both must match`() = runTest {
        val viewModel = viewModel()
        val inFoodAndMarch = TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 10))
        val inFoodButFebruary = TestData.transaction(id = "b", categoryId = "food", date = LocalDate(2026, 2, 10))
        val inMarchButRent = TestData.transaction(id = "c", categoryId = "rent", date = LocalDate(2026, 3, 10))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(inFoodAndMarch, inFoodButFebruary, inMarchButRent))
            skipItems(1) // Success, unfiltered

            viewModel.onCategoryFilterToggled("food")
            skipItems(1) // Success, category-only
            viewModel.onDateRangeOptionSelected(DateRangeOption.THIS_MONTH)

            val filtered = awaitItem() as TransactionsUiState.Success
            assertThat(filtered.groups.flatMap { it.items }.map { it.transaction.id }).containsExactly("a")
        }
    }

    @Test
    fun `the net total sums exactly the filtered set, even when it spans months`() = runTest {
        val viewModel = viewModel()
        val march = TestData.transaction(id = "a", amount = Money(-1000), categoryId = "food", date = LocalDate(2026, 3, 1))
        val january = TestData.transaction(id = "b", amount = Money(-2000), categoryId = "food", date = LocalDate(2026, 1, 1))
        val differentCategory = TestData.transaction(id = "c", amount = Money(-9999), categoryId = "rent", date = LocalDate(2026, 2, 1))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(march, january, differentCategory))
            skipItems(1) // Success, unfiltered

            // Category-only: no date bound, so both "food" rows count regardless of month.
            viewModel.onCategoryFilterToggled("food")

            val success = awaitItem() as TransactionsUiState.Success
            assertThat(success.netTotal).isEqualTo(Money(-3000))
            assertThat(success.netPeriod).isEqualTo(NetPeriod.Filtered)
        }
    }

    @Test
    fun `the default unfiltered total still only covers the current calendar month`() = runTest {
        val viewModel = viewModel()
        val thisMonth = TestData.transaction(id = "a", amount = Money(-1000), date = LocalDate(2026, 3, 1))
        val lastMonth = TestData.transaction(id = "b", amount = Money(-500_000), date = LocalDate(2026, 2, 1))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(thisMonth, lastMonth))

            val success = awaitItem() as TransactionsUiState.Success
            assertThat(success.netTotal).isEqualTo(Money(-1000))
            assertThat(success.netPeriod).isEqualTo(NetPeriod.Month(LocalDate(2026, 3, 14)))
        }
    }

    @Test
    fun `clearing filters restores the unfiltered list and default total`() = runTest {
        val viewModel = viewModel()
        val inFood = TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 1))
        val inRent = TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 3, 1))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(inFood, inRent))
            skipItems(1) // Success, unfiltered

            viewModel.onCategoryFilterToggled("food")
            skipItems(1) // Success, filtered

            viewModel.onClearFilters()
            val cleared = awaitItem() as TransactionsUiState.Success
            assertThat(cleared.groups.flatMap { it.items }.map { it.transaction.id }).containsExactly("a", "b")
            assertThat(cleared.filter.isActive).isFalse()
        }
    }

    @Test
    fun `a filter matching nothing shows the filtered-empty state, distinct from no transactions at all`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(TestData.transaction(id = "a", categoryId = "food")))
            skipItems(1) // Success

            viewModel.onCategoryFilterToggled("nonexistent")

            val state = awaitItem()
            assertThat(state).isInstanceOf(TransactionsUiState.EmptyFiltered::class.java)
            val filtered = state as TransactionsUiState.EmptyFiltered
            assertThat(filtered.filter.categoryIds).containsExactly("nonexistent")
            // Zero, not absent -- a missing total would read as a rendering
            // bug rather than "nothing matched this filter."
            assertThat(filtered.netTotal).isEqualTo(Money.ZERO)
            assertThat(filtered.netPeriod).isEqualTo(NetPeriod.Filtered)
        }
    }

    @Test
    fun `no transactions at all shows the true empty state, not filtered-empty`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Loading)
            transactionRepository.emit(emptyList())
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)
        }
    }

    @Test
    fun `a custom date range filters to exactly that window`() = runTest {
        val viewModel = viewModel()
        val inside = TestData.transaction(id = "a", date = LocalDate(2026, 1, 15))
        val outside = TestData.transaction(id = "b", date = LocalDate(2026, 2, 1))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(inside, outside))
            skipItems(1) // Success, unfiltered

            viewModel.onCustomDateRangeSelected(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31))

            val success = awaitItem() as TransactionsUiState.Success
            assertThat(success.groups.flatMap { it.items }.map { it.transaction.id }).containsExactly("a")
            assertThat(success.netPeriod).isEqualTo(NetPeriod.Range(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31)))
        }
    }

    // --- SavedStateHandle survives process death ---

    @Test
    fun `restoring from a saved state handle re-applies an active filter`() = runTest {
        val restoredHandle = SavedStateHandle(
            mapOf(
                "categoryIds" to setOf("food"),
                "dateRangeOption" to DateRangeOption.CUSTOM.name,
                "customFromEpochDays" to LocalDate(2026, 1, 1).toEpochDays().toLong(),
                "customToEpochDays" to LocalDate(2026, 1, 31).toEpochDays().toLong(),
            ),
        )
        val viewModel = viewModel(restoredHandle)
        val inWindow = TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 1, 15))
        val outsideCategory = TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 1, 15))

        viewModel.uiState.test {
            skipItems(1) // Loading
            transactionRepository.emit(listOf(inWindow, outsideCategory))

            val success = awaitItem() as TransactionsUiState.Success
            assertThat(success.groups.flatMap { it.items }.map { it.transaction.id }).containsExactly("a")
            assertThat(success.filter.categoryIds).containsExactly("food")
            assertThat(success.filter.dateRangeOption).isEqualTo(DateRangeOption.CUSTOM)
        }
    }
}
