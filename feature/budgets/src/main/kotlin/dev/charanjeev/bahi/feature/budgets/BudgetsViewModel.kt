package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.model.YearMonth
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@HiltViewModel
class BudgetsViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
    clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Which month is on screen. Resolved through the injected clock and the
     * device zone *here*, in the presentation layer, and never below it --
     * the data layer only ever sees the concrete date window YearMonth
     * produces (docs/budgets-design.md §2.3, and TransactionFilter's doc,
     * which set the precedent).
     */
    private val month = savedStateHandle.getStateFlow(
        KEY_MONTH,
        YearMonth.from(clock.todayIn(TimeZone.currentSystemDefault())).toString(),
    )

    private val pendingDelete = MutableStateFlow<PendingBudgetDelete?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthlyBudgets = month.flatMapLatest { raw ->
        // flatMapLatest, not combine: changing month means a different query,
        // and the previous month's flow must be cancelled rather than left
        // racing the new one to emit last.
        budgetRepository.observeMonthlyBudgets(YearMonth.parse(raw))
    }

    val uiState: StateFlow<BudgetsUiState> = combine(
        monthlyBudgets,
        categoryRepository.observeCategories(),
        pendingDelete,
    ) { monthly, categories, delete ->
        if (monthly.budgets.isEmpty()) {
            // Still carries the uncategorised figure: a month with no budgets
            // but real uncategorised spending has something worth saying.
            BudgetsUiState.Empty(month = monthly.month, uncategorisedSpend = monthly.uncategorisedSpend)
        } else {
            val byId = categories.associateBy { it.id }
            BudgetsUiState.Success(
                month = monthly.month,
                budgets = monthly.budgets
                    .map { BudgetRow(it, byId[it.budget.categoryId]) }
                    .toPersistentList(),
                uncategorisedSpend = monthly.uncategorisedSpend,
                currencyCode = monthly.budgets.first().budget.currencyCode,
                pendingDelete = delete,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetsUiState.Loading(YearMonth.parse(month.value)),
    )

    fun onPreviousMonth() = shiftMonth(-1)

    fun onNextMonth() = shiftMonth(1)

    private fun shiftMonth(offset: Int) {
        savedStateHandle[KEY_MONTH] = YearMonth.parse(month.value).plusMonths(offset).toString()
    }

    fun onDeleteRequested(budgetId: String, categoryName: String) {
        pendingDelete.value = PendingBudgetDelete(budgetId, categoryName)
    }

    fun onDeleteConfirmed() {
        val target = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch { budgetRepository.delete(target.budgetId) }
    }

    fun onDeleteCancelled() {
        pendingDelete.value = null
    }

    /** The month the add/edit screen should open on -- the one being looked at, not "today". */
    fun currentMonth(): YearMonth = YearMonth.parse(month.value)

    private companion object {
        const val KEY_MONTH = "month"
    }
}
