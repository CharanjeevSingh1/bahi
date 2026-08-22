package dev.charanjeev.bahi.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    // Lives here rather than in the row's own composable state: the row that
    // triggers a delete leaves composition as soon as the repository's flow
    // drops it, which would take any row-local "pending" flag down with it
    // before the undo snackbar even has a chance to be tapped.
    private val pendingDelete = MutableStateFlow<TransactionListItem?>(null)

    // Replays so a late subscriber (or a retry) re-runs the combine below
    // instead of needing its own separate trigger plumbing.
    private val retrySignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    val uiState: StateFlow<TransactionsUiState> = retrySignal
        .flatMapLatest {
            // .catch scoped to this one attempt, not the outer retrySignal chain:
            // if it wrapped the whole thing, catching an error would complete the
            // flow stateIn is collecting and onRetry() would have nothing left to
            // restart.
            combine(
                repository.observeTransactions(),
                categoryRepository.observeCategories(),
                pendingDelete,
            ) { transactions, categories, pending ->
                if (transactions.isEmpty()) {
                    TransactionsUiState.Empty
                } else {
                    val categoriesById = categories.associateBy { it.id }
                    val items = transactions.map { transaction ->
                        TransactionListItem(transaction, categoriesById[transaction.categoryId])
                    }
                    val today = clock.todayIn(TimeZone.currentSystemDefault())
                    TransactionsUiState.Success(
                        groups = groupByDate(items, today),
                        // This calendar month, not all-time: a single salary
                        // transaction would otherwise dominate a net summed
                        // across everything ever loaded.
                        netTotal = netTotalForMonth(transactions, today),
                        periodMonth = today,
                        currencyCode = transactions.first().currencyCode,
                        pendingDelete = pending,
                    )
                }
            }.catch { emit(TransactionsUiState.Error(it.message ?: "Something went wrong")) }
        }
        .stateIn(
            scope = viewModelScope,
            // 5s keeps the flow alive across configuration changes without
            // leaking work when the screen actually goes away.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TransactionsUiState.Loading,
        )

    fun onDeleteTransaction(item: TransactionListItem) {
        pendingDelete.value = item
        viewModelScope.launch { repository.delete(item.transaction.id) }
    }

    fun onUndoDelete() {
        val item = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch { repository.undoDelete(item.transaction.id) }
    }

    /** Called once the snackbar resolves without an undo, so it can't reappear on the next recomposition. */
    fun onDeleteSnackbarDismissed() {
        pendingDelete.value = null
    }

    fun onRetry() {
        retrySignal.tryEmit(Unit)
    }
}
