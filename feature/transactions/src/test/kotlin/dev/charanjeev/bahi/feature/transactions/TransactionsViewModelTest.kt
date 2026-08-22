package dev.charanjeev.bahi.feature.transactions

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
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

    private fun viewModel() = TransactionsViewModel(transactionRepository, categoryRepository, clock)

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
}
