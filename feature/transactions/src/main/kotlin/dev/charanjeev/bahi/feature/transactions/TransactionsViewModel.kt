package dev.charanjeev.bahi.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Money
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import javax.inject.Inject

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // Lives here rather than in the row's own composable state: the row that
    // triggers a delete leaves composition as soon as the repository's flow
    // drops it, which would take any row-local "pending" flag down with it
    // before the undo snackbar even has a chance to be tapped.
    private val pendingDelete = MutableStateFlow<TransactionListItem?>(null)

    // Replays so a late subscriber (or a retry) re-runs the combine below
    // instead of needing its own separate trigger plumbing.
    private val retrySignal = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    private val categoryIds = savedStateHandle.getStateFlow(KEY_CATEGORY_IDS, emptySet<String>())
    private val dateRangeOptionName = savedStateHandle.getStateFlow<String?>(KEY_DATE_RANGE_OPTION, null)
    private val customFromEpochDays = savedStateHandle.getStateFlow<Long?>(KEY_CUSTOM_FROM, null)
    private val customToEpochDays = savedStateHandle.getStateFlow<Long?>(KEY_CUSTOM_TO, null)

    private val filterState: Flow<TransactionFilterState> = combine(
        categoryIds, dateRangeOptionName, customFromEpochDays, customToEpochDays,
    ) { catIds, optionName, fromDays, toDays ->
        TransactionFilterState(
            categoryIds = catIds,
            dateRangeOption = optionName?.let(DateRangeOption::valueOf),
            customFrom = fromDays?.let { LocalDate.fromEpochDays(it.toInt()) },
            customTo = toDays?.let { LocalDate.fromEpochDays(it.toInt()) },
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TransactionsUiState> = combine(retrySignal, filterState) { _, filter -> filter }
        .flatMapLatest { filter ->
            val today = clock.todayIn(TimeZone.currentSystemDefault())
            // .catch scoped to this one attempt, not the outer chain: if it
            // wrapped the whole thing, catching an error would complete the
            // flow stateIn is collecting and onRetry()/a filter change would
            // have nothing left to restart.
            combine(
                repository.observeTransactions(filter.toRepositoryFilter(today)),
                categoryRepository.observeCategories(),
                pendingDelete,
            ) { transactions, categories, pending ->
                val categoriesList = categories.toPersistentList()
                if (transactions.isEmpty()) {
                    if (filter.isActive) {
                        TransactionsUiState.EmptyFiltered(
                            filter = filter,
                            availableCategories = categoriesList,
                            netPeriod = filter.toNetPeriod(today),
                        )
                    } else {
                        TransactionsUiState.Empty
                    }
                } else {
                    val categoriesById = categories.associateBy { it.id }
                    val items = transactions.map { transaction ->
                        TransactionListItem(transaction, categoriesById[transaction.categoryId])
                    }
                    // A query already bounded by the active filter is exactly
                    // what should be totalled -- summing it directly is what
                    // "the total reflects the filter" means. Only the
                    // unfiltered default (an unbounded query) still needs the
                    // explicit current-month restriction, so a single salary
                    // transaction from six months ago doesn't dominate it.
                    val netTotal = if (filter.isActive) {
                        transactions.fold(Money.ZERO) { total, transaction -> total + transaction.amount }
                    } else {
                        netTotalForMonth(transactions, today)
                    }
                    TransactionsUiState.Success(
                        groups = groupByDate(items, today),
                        netTotal = netTotal,
                        netPeriod = filter.toNetPeriod(today),
                        currencyCode = transactions.first().currencyCode,
                        pendingDelete = pending,
                        filter = filter,
                        availableCategories = categoriesList,
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

    fun onCategoryFilterToggled(categoryId: String) {
        val current = categoryIds.value
        savedStateHandle[KEY_CATEGORY_IDS] = if (categoryId in current) current - categoryId else current + categoryId
    }

    fun onDateRangeOptionSelected(option: DateRangeOption?) {
        savedStateHandle[KEY_DATE_RANGE_OPTION] = option?.name
        if (option != DateRangeOption.CUSTOM) {
            savedStateHandle[KEY_CUSTOM_FROM] = null
            savedStateHandle[KEY_CUSTOM_TO] = null
        }
    }

    fun onCustomDateRangeSelected(from: LocalDate, to: LocalDate) {
        savedStateHandle[KEY_DATE_RANGE_OPTION] = DateRangeOption.CUSTOM.name
        savedStateHandle[KEY_CUSTOM_FROM] = from.toEpochDays().toLong()
        savedStateHandle[KEY_CUSTOM_TO] = to.toEpochDays().toLong()
    }

    fun onClearFilters() {
        savedStateHandle[KEY_CATEGORY_IDS] = emptySet<String>()
        savedStateHandle[KEY_DATE_RANGE_OPTION] = null
        savedStateHandle[KEY_CUSTOM_FROM] = null
        savedStateHandle[KEY_CUSTOM_TO] = null
    }

    private companion object {
        const val KEY_CATEGORY_IDS = "categoryIds"
        const val KEY_DATE_RANGE_OPTION = "dateRangeOption"
        const val KEY_CUSTOM_FROM = "customFromEpochDays"
        const val KEY_CUSTOM_TO = "customToEpochDays"
    }
}
