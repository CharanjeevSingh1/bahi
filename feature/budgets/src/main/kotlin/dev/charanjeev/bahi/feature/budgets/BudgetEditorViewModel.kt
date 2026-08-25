package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.SystemCategoryIds
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.feature.budgets.navigation.BudgetIdArg
import dev.charanjeev.bahi.feature.budgets.navigation.MonthArg
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BudgetEditorViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val budgetId: String? = savedStateHandle[BudgetIdArg]
    private val mode = if (budgetId == null) BudgetEditorMode.ADD else BudgetEditorMode.EDIT
    private val month: YearMonth = YearMonth.parse(savedStateHandle.get<String>(MonthArg)!!)

    private var original: Budget? = null

    private val limitText = savedStateHandle.getStateFlow(KEY_LIMIT, "")
    private val categoryId = savedStateHandle.getStateFlow<String?>(KEY_CATEGORY_ID, null)
    private val limitTouched = savedStateHandle.getStateFlow(KEY_LIMIT_TOUCHED, false)
    private val categoryTouched = savedStateHandle.getStateFlow(KEY_CATEGORY_TOUCHED, false)
    private val hasSeeded = savedStateHandle.getStateFlow(KEY_SEEDED, false)

    private val categories = MutableStateFlow(persistentListOf<Category>())
    private val isSaving = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)
    private val loaded = MutableStateFlow(mode == BudgetEditorMode.ADD)

    private val eventChannel = Channel<BudgetEditorEvent>(Channel.BUFFERED)
    val events: Flow<BudgetEditorEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            categoryRepository.observeCategories().collect { all ->
                // Income and Transfers can never accumulate spend, because the
                // totals query only sees negative amounts (§2.2). Offering
                // them would let a user build a budget guaranteed to read ₹0.
                categories.value = all
                    .filterNot { it.id in SystemCategoryIds.NEVER_BUDGETABLE }
                    .toPersistentList()
            }
        }
        if (mode == BudgetEditorMode.EDIT) {
            viewModelScope.launch {
                val budget = budgetRepository.observeBudgets(month).first().firstOrNull { it.id == budgetId }
                if (budget == null) {
                    loadError.value = "This budget no longer exists."
                    return@launch
                }
                original = budget
                if (!hasSeeded.value) {
                    savedStateHandle[KEY_LIMIT] = formatLimitForEditing(budget)
                    savedStateHandle[KEY_CATEGORY_ID] = budget.categoryId
                    savedStateHandle[KEY_SEEDED] = true
                }
                loaded.value = true
            }
        }
    }

    private val fields = combine(limitText, categoryId, limitTouched, categoryTouched, ::Fields)

    private val status = combine(categories, isSaving, loaded, loadError, ::Status)

    val uiState: StateFlow<BudgetEditorUiState> = combine(fields, status) { field, state ->
        when {
            state.loadError != null -> BudgetEditorUiState.Error(state.loadError)
            !state.loaded -> BudgetEditorUiState.Loading
            else -> BudgetEditorUiState.Editing(
                mode = mode,
                month = month,
                limitText = field.limitText,
                categoryId = field.categoryId,
                categories = state.categories,
                limitTouched = field.limitTouched,
                categoryTouched = field.categoryTouched,
                isSaving = state.isSaving,
                currencyCode = original?.currencyCode ?: DEFAULT_CURRENCY_CODE,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BudgetEditorUiState.Loading,
    )

    fun onLimitTextChange(value: String) {
        // Only characters a currency amount can contain, so an invalid one
        // never lands in the field -- the same instinct as the transaction
        // form's sanitizeAmountInput, kept local because that helper lives in
        // another feature module and features never depend on each other.
        savedStateHandle[KEY_LIMIT] = value.filter { it.isDigit() || it == '.' || it == ',' }.take(LIMIT_MAX_LENGTH)
        savedStateHandle[KEY_LIMIT_TOUCHED] = true
    }

    fun onCategorySelected(id: String) {
        savedStateHandle[KEY_CATEGORY_ID] = id
        savedStateHandle[KEY_CATEGORY_TOUCHED] = true
    }

    /**
     * Reads the field flows rather than uiState, for the reason
     * RuleEditorViewModel.onSave spells out: uiState is WhileSubscribed and
     * reads Loading with nothing collecting it, so a guard against it would
     * be conditional on the screen being attached.
     */
    fun onSave() {
        if (isSaving.value) return
        val limit = Money.parse(limitText.value.trim()) ?: return
        if (limit.isNegative) return
        val category = categoryId.value ?: return

        isSaving.value = true
        viewModelScope.launch {
            budgetRepository.upsert(
                Budget(
                    // Ignored by the repository when a budget already exists
                    // for this category and month -- it keys on that pair, not
                    // on the id (BudgetRepository.upsert's doc). Generating one
                    // here is correct precisely because it may be discarded.
                    id = original?.id ?: UUID.randomUUID().toString(),
                    categoryId = category,
                    month = month,
                    limit = limit,
                    currencyCode = original?.currencyCode ?: DEFAULT_CURRENCY_CODE,
                ),
            )
            isSaving.value = false
            eventChannel.send(BudgetEditorEvent.NavigateBack)
        }
    }

    fun onCancel() {
        viewModelScope.launch { eventChannel.send(BudgetEditorEvent.NavigateBack) }
    }

    private fun formatLimitForEditing(budget: Budget): String {
        val minor = budget.limit.minorUnits
        return if (minor % 100L == 0L) (minor / 100L).toString() else "%d.%02d".format(minor / 100L, minor % 100L)
    }

    private data class Fields(
        val limitText: String,
        val categoryId: String?,
        val limitTouched: Boolean,
        val categoryTouched: Boolean,
    )

    private data class Status(
        val categories: ImmutableList<Category>,
        val isSaving: Boolean,
        val loaded: Boolean,
        val loadError: String?,
    )

    private companion object {
        const val KEY_LIMIT = "limitText"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_LIMIT_TOUCHED = "limitTouched"
        const val KEY_CATEGORY_TOUCHED = "categoryTouched"
        const val KEY_SEEDED = "hasSeeded"

        const val LIMIT_MAX_LENGTH = 15
        const val DEFAULT_CURRENCY_CODE = "INR"

    }
}

/** One-shot navigation signal -- not UI state, so it can't re-fire on rotation. */
sealed interface BudgetEditorEvent {
    data object NavigateBack : BudgetEditorEvent
}
