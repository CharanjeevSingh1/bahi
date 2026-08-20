package dev.charanjeev.finflow.feature.transactions

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.finflow.core.testing.MainDispatcherRule
import dev.charanjeev.finflow.core.testing.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class TransactionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTransactionRepository()

    @Test
    fun `starts in loading state`() = runTest {
        val viewModel = TransactionsViewModel(repository)
        assertThat(viewModel.uiState.value).isEqualTo(TransactionsUiState.Loading)
    }

    @Test
    fun `emits empty when repository has no transactions`() = runTest {
        val viewModel = TransactionsViewModel(repository)

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Loading)
            repository.emit(emptyList())
            assertThat(awaitItem()).isEqualTo(TransactionsUiState.Empty)
        }
    }

    @Test
    fun `emits success with transactions in repository order`() = runTest {
        val viewModel = TransactionsViewModel(repository)
        val transactions = listOf(
            TestData.transaction(id = "a", description = "COFFEE"),
            TestData.transaction(id = "b", description = "RENT"),
        )

        viewModel.uiState.test {
            skipItems(1) // Loading
            repository.emit(transactions)

            val state = awaitItem()
            assertThat(state).isInstanceOf(TransactionsUiState.Success::class.java)
            assertThat((state as TransactionsUiState.Success).transactions)
                .containsExactlyElementsIn(transactions)
                .inOrder()
        }
    }
}
