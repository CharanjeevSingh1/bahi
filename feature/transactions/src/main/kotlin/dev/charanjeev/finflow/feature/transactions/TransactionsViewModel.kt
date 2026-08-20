package dev.charanjeev.finflow.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.finflow.core.data.repository.TransactionRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    repository: TransactionRepository,
) : ViewModel() {

    val uiState: StateFlow<TransactionsUiState> = repository.observeTransactions()
        .map { transactions ->
            if (transactions.isEmpty()) {
                TransactionsUiState.Empty
            } else {
                TransactionsUiState.Success(transactions.toImmutableList())
            }
        }
        .catch { emit(TransactionsUiState.Error(it.message ?: "Something went wrong")) }
        .stateIn(
            scope = viewModelScope,
            // 5s keeps the flow alive across configuration changes without
            // leaking work when the screen actually goes away.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransactionsUiState.Loading,
        )
}
