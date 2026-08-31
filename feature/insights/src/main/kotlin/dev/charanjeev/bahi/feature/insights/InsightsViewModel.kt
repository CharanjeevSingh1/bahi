package dev.charanjeev.bahi.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.InsightsRepository
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.SystemCategoryIds
import dev.charanjeev.bahi.core.model.YearMonth
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightsRepository: InsightsRepository,
    private val budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
    clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Which month is on screen, resolved through the injected clock and the
     * device zone here and never below it -- matches BudgetsViewModel's
     * reasoning (docs/budgets-design.md §2.3).
     */
    private val month = savedStateHandle.getStateFlow(
        KEY_MONTH,
        YearMonth.from(clock.todayIn(TimeZone.currentSystemDefault())).toString(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val breakdown = month.flatMapLatest { insightsRepository.observeCategoryBreakdown(YearMonth.parse(it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val trend = month.flatMapLatest { insightsRepository.observeSpendTrend(YearMonth.parse(it)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val monthlyBudgets = month.flatMapLatest { budgetRepository.observeMonthlyBudgets(YearMonth.parse(it)) }

    val uiState: StateFlow<InsightsUiState> = combine(
        insightsRepository.observeHasAnyHistory(),
        breakdown,
        trend,
        monthlyBudgets,
        categoryRepository.observeCategories(),
    ) { hasHistory, categoryBreakdown, spendTrend, monthly, categories ->
        if (!hasHistory) {
            // Not "nothing this month" -- the app has never recorded a
            // transaction, in any month, so there is nothing to chart yet.
            InsightsUiState.NoHistory(categoryBreakdown.month)
        } else {
            val categoriesById = categories.associateBy { it.id }
            val slices = buildList {
                categoryBreakdown.categorySpend.forEach { spend ->
                    add(CategorySlice(categoriesById[spend.categoryId], spend.spent))
                }
                // The real "Uncategorised" system category, not a null
                // placeholder -- so the chart draws its name and colour the
                // same way it draws any other slice. Omitted entirely when
                // zero, same as every category that has no spend this month.
                if (categoryBreakdown.uncategorisedSpend > Money.ZERO) {
                    add(CategorySlice(categoriesById[SystemCategoryIds.UNCATEGORISED], categoryBreakdown.uncategorisedSpend))
                }
            }.sortedByDescending { it.spent.minorUnits }

            InsightsUiState.Success(
                month = categoryBreakdown.month,
                categorySlices = slices.toPersistentList(),
                totalSpend = categoryBreakdown.totalSpend,
                trend = spendTrend.months.toPersistentList(),
                hasComparison = spendTrend.hasComparison,
                overBudget = monthly.budgets
                    .filter { it.isOverBudget }
                    .map { OverBudgetRow(it, categoriesById[it.budget.categoryId]) }
                    .toPersistentList(),
                hasAnyBudgets = monthly.budgets.isNotEmpty(),
                // The app has one currency today; see BudgetEditorViewModel's
                // constant of the same name for the same assumption.
                currencyCode = monthly.budgets.firstOrNull()?.budget?.currencyCode ?: DEFAULT_CURRENCY_CODE,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InsightsUiState.Loading(YearMonth.parse(month.value)),
    )

    fun onPreviousMonth() = shiftMonth(-1)

    fun onNextMonth() = shiftMonth(1)

    private fun shiftMonth(offset: Int) {
        savedStateHandle[KEY_MONTH] = YearMonth.parse(month.value).plusMonths(offset).toString()
    }

    private companion object {
        const val KEY_MONTH = "month"
        const val DEFAULT_CURRENCY_CODE = "INR"
    }
}
