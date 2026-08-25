package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import dev.charanjeev.bahi.feature.budgets.navigation.RuleIdArg
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
class RuleEditorViewModel @Inject constructor(
    private val ruleRepository: CategoryRuleRepository,
    categoryRepository: CategoryRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val ruleId: String? = savedStateHandle[RuleIdArg]
    private val mode = if (ruleId == null) RuleEditorMode.ADD else RuleEditorMode.EDIT

    /** The rule as stored, so an edit preserves the priority this screen never shows. */
    private var original: CategoryRule? = null

    /** See RulesViewModel: held here so the confirm commits the same set it counted. */
    private var pendingPreview: RuleApplicationPreview? = null

    private val merchantContains = savedStateHandle.getStateFlow(KEY_MERCHANT, "")
    private val categoryId = savedStateHandle.getStateFlow<String?>(KEY_CATEGORY_ID, null)
    private val merchantTouched = savedStateHandle.getStateFlow(KEY_MERCHANT_TOUCHED, false)
    private val categoryTouched = savedStateHandle.getStateFlow(KEY_CATEGORY_TOUCHED, false)
    private val hasSeeded = savedStateHandle.getStateFlow(KEY_SEEDED, false)

    private val categories = MutableStateFlow(persistentListOf<Category>())
    private val isSaving = MutableStateFlow(false)
    private val dialog = MutableStateFlow<RuleApplyDialog?>(null)
    private val loadError = MutableStateFlow<String?>(null)
    private val loaded = MutableStateFlow(mode == RuleEditorMode.ADD)

    private val eventChannel = Channel<RuleEditorEvent>(Channel.BUFFERED)
    val events: Flow<RuleEditorEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            categoryRepository.observeCategories().collect { categories.value = it.toPersistentList() }
        }
        if (mode == RuleEditorMode.EDIT) {
            viewModelScope.launch {
                val rule = ruleRepository.observeRules().first().firstOrNull { it.id == ruleId }
                if (rule == null) {
                    loadError.value = "This rule no longer exists."
                    return@launch
                }
                original = rule
                // A process-death restore already seeded these -- don't
                // overwrite an in-progress edit with the stored values.
                if (!hasSeeded.value) {
                    savedStateHandle[KEY_MERCHANT] = rule.merchantContains
                    savedStateHandle[KEY_CATEGORY_ID] = rule.categoryId
                    savedStateHandle[KEY_SEEDED] = true
                }
                loaded.value = true
            }
        }
    }

    private val fields = combine(
        merchantContains, categoryId, merchantTouched, categoryTouched, ::Fields,
    )

    private val status = combine(categories, isSaving, dialog, loaded, loadError, ::Status)

    val uiState: StateFlow<RuleEditorUiState> = combine(fields, status) { field, state ->
        when {
            state.loadError != null -> RuleEditorUiState.Error(state.loadError)
            !state.loaded -> RuleEditorUiState.Loading
            else -> RuleEditorUiState.Editing(
                mode = mode,
                merchantContains = field.merchantContains,
                categoryId = field.categoryId,
                categories = state.categories,
                merchantTouched = field.merchantTouched,
                categoryTouched = field.categoryTouched,
                isSaving = state.isSaving,
                dialog = state.dialog,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RuleEditorUiState.Loading,
    )

    fun onMerchantContainsChange(value: String) {
        // Clamped as it's typed rather than validated afterwards, matching
        // the transaction form's description field: an over-long paste should
        // never land in the field in the first place.
        savedStateHandle[KEY_MERCHANT] = value.take(MERCHANT_CONTAINS_MAX_LENGTH)
        savedStateHandle[KEY_MERCHANT_TOUCHED] = true
    }

    fun onCategorySelected(id: String) {
        savedStateHandle[KEY_CATEGORY_ID] = id
        savedStateHandle[KEY_CATEGORY_TOUCHED] = true
    }

    /**
     * Refuses an invalid rule independently of the save button's enabled
     * state. That is not belt-and-braces for its own sake: `canSave` is a
     * property of a state object, and a screen that stopped consulting it --
     * a redesign, a new entry point, a test driving the ViewModel directly --
     * would otherwise be one mistake away from persisting a rule that matches
     * every transaction the user has.
     */
    fun onSave() {
        if (isSaving.value) return
        // Validated against the field flows, not against uiState. uiState is
        // a WhileSubscribed StateFlow and reads Loading whenever nothing is
        // collecting it, so a guard phrased against it would quietly be
        // conditional on the screen being attached -- which is not what a
        // guard against saving a rule that matches every transaction should
        // depend on. These two flows are always live.
        val merchant = merchantContains.value.trim()
        val category = categoryId.value
        if (merchant.isBlank() || category == null) return

        isSaving.value = true
        viewModelScope.launch {
            val rule = CategoryRule(
                id = original?.id ?: UUID.randomUUID().toString(),
                categoryId = category,
                merchantContains = merchant,
                // A new rule goes last: it can't silently outrank rules the
                // user already ordered. Editing keeps whatever position the
                // rule already had, which this screen never shows.
                priority = original?.priority ?: nextPriority(),
            )
            ruleRepository.upsert(rule)

            // The rule is saved either way. Applying it to transactions that
            // already exist is a separate, consented step -- never a side
            // effect of saving (§1.6).
            val preview = ruleRepository.previewApplyToExisting(rule)
            pendingPreview = preview
            dialog.value = if (preview.isEmpty) {
                RuleApplyDialog.NothingToDo(preview.lockedSkippedCount)
            } else {
                RuleApplyDialog.Confirm(preview.matchedCount, preview.lockedSkippedCount)
            }
            isSaving.value = false
        }
    }

    fun onApplyConfirmed() {
        val preview = pendingPreview ?: return
        viewModelScope.launch {
            val changed = ruleRepository.apply(preview)
            pendingPreview = null
            dialog.value = RuleApplyDialog.Done(changedCount = changed, previewedCount = preview.matchedCount)
        }
    }

    /** "Not now": the rule stays saved, existing transactions are left alone. */
    fun onApplyDeclined() {
        pendingPreview = null
        dialog.value = null
        navigateBack()
    }

    fun onDoneDismissed() {
        dialog.value = null
        navigateBack()
    }

    fun onDelete() {
        val id = original?.id ?: return
        viewModelScope.launch {
            ruleRepository.delete(id)
            navigateBack()
        }
    }

    fun onCancel() = navigateBack()

    private fun navigateBack() {
        viewModelScope.launch { eventChannel.send(RuleEditorEvent.NavigateBack) }
    }

    private suspend fun nextPriority(): Int = (ruleRepository.rules().maxOfOrNull { it.priority } ?: -1) + 1

    private data class Fields(
        val merchantContains: String,
        val categoryId: String?,
        val merchantTouched: Boolean,
        val categoryTouched: Boolean,
    )

    private data class Status(
        val categories: ImmutableList<Category>,
        val isSaving: Boolean,
        val dialog: RuleApplyDialog?,
        val loaded: Boolean,
        val loadError: String?,
    )

    private companion object {
        const val KEY_MERCHANT = "merchantContains"
        const val KEY_CATEGORY_ID = "categoryId"
        const val KEY_MERCHANT_TOUCHED = "merchantTouched"
        const val KEY_CATEGORY_TOUCHED = "categoryTouched"
        const val KEY_SEEDED = "hasSeeded"
    }
}

/** One-shot navigation signal -- not UI state, so it can't re-fire on rotation. */
sealed interface RuleEditorEvent {
    data object NavigateBack : RuleEditorEvent
}
