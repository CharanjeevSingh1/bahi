package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RulesViewModel @Inject constructor(
    private val ruleRepository: CategoryRuleRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    /**
     * The preview the user is being shown, held here rather than in the UI
     * state. Two reasons: the state stays free of a Map, which Compose treats
     * as unstable, and -- the one that matters -- committing means handing
     * *this* object back to the repository rather than matching again, so the
     * count the user consented to and the rows written are the same set.
     */
    private var pendingPreview: RuleApplicationPreview? = null

    /**
     * The rules, mirrored off the repository flow. uiState can't stand in for
     * this: it is WhileSubscribed, so it reads Loading whenever the screen
     * isn't attached, and reorder resolving a move against Loading would
     * silently do nothing.
     */
    private val rules = MutableStateFlow<List<CategoryRule>>(emptyList())

    private val dialog = MutableStateFlow<RuleApplyDialog?>(null)
    private val pendingDelete = MutableStateFlow<PendingDelete?>(null)
    private val isWorking = MutableStateFlow(false)

    private val transientState = combine(dialog, pendingDelete, isWorking, ::TransientState)

    init {
        viewModelScope.launch { ruleRepository.observeRules().collect { rules.value = it } }
    }

    val uiState: StateFlow<RulesUiState> = combine(
        ruleRepository.observeRules(),
        categoryRepository.observeCategories(),
        transientState,
    ) { rules, categories, transient ->
        if (rules.isEmpty()) {
            RulesUiState.Empty
        } else {
            val byId = categories.associateBy { it.id }
            RulesUiState.Success(
                // Already in evaluation order from the repository; re-sorting
                // here would be a second opinion about which rule wins.
                rules = rules.map { RuleListItem(it, byId[it.categoryId]) }.toPersistentList(),
                dialog = transient.dialog,
                pendingDelete = transient.pendingDelete,
                isWorking = transient.isWorking,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RulesUiState.Loading,
    )

    /**
     * Reorder is expressed as a whole new order rather than a relative move,
     * because that is what the repository stores -- position becomes
     * priority. Building the list here from what the screen is showing keeps
     * the two in step; asking the repository to "move rule X up" would make
     * it resolve the move against a list it can't see.
     */
    fun onMoveUp(ruleId: String) = move(ruleId, offset = -1)

    fun onMoveDown(ruleId: String) = move(ruleId, offset = 1)

    private fun move(ruleId: String, offset: Int) {
        val ids = rules.value.map { it.id }
        val from = ids.indexOf(ruleId).takeIf { it >= 0 } ?: return
        val to = from + offset
        // Out of range means the button shouldn't have been enabled; ignoring
        // it here rather than clamping keeps a stale tap from silently
        // reordering something the user didn't aim at.
        if (to !in ids.indices) return
        val reordered = ids.toMutableList().apply { add(to, removeAt(from)) }
        viewModelScope.launch { ruleRepository.reorder(reordered) }
    }

    fun onDeleteRequested(ruleId: String) {
        val rule = rules.value.firstOrNull { it.id == ruleId } ?: return
        pendingDelete.value = PendingDelete(ruleId, rule.merchantContains)
    }

    fun onDeleteConfirmed() {
        val target = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch { ruleRepository.delete(target.ruleId) }
    }

    fun onDeleteCancelled() {
        pendingDelete.value = null
    }

    /**
     * §1.3's on-demand trigger. Previews first, always -- there is no path
     * from this action straight to a write, which is the whole reason it is a
     * button the user presses rather than something that happens to them.
     */
    fun onRecategoriseRequested() {
        if (isWorking.value) return
        isWorking.value = true
        viewModelScope.launch {
            val preview = ruleRepository.previewRecategoriseUncategorised()
            pendingPreview = preview
            dialog.value = if (preview.isEmpty) {
                RuleApplyDialog.NothingToDo(preview.lockedSkippedCount)
            } else {
                RuleApplyDialog.Confirm(preview.matchedCount, preview.lockedSkippedCount)
            }
            isWorking.value = false
        }
    }

    fun onApplyConfirmed() {
        val preview = pendingPreview ?: return
        if (isWorking.value) return
        isWorking.value = true
        viewModelScope.launch {
            val changed = ruleRepository.apply(preview)
            pendingPreview = null
            // The number that actually changed, not the number promised --
            // a row locked while the dialog was open is refused by the write.
            dialog.value = RuleApplyDialog.Done(changedCount = changed, previewedCount = preview.matchedCount)
            isWorking.value = false
        }
    }

    fun onDialogDismissed() {
        pendingPreview = null
        dialog.value = null
    }

    private data class TransientState(
        val dialog: RuleApplyDialog?,
        val pendingDelete: PendingDelete?,
        val isWorking: Boolean,
    )
}
