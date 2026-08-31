package dev.charanjeev.bahi.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.SyncConflictRepository
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Settings screen's only real content so far (docs/sync-design.md §5.6,
 * slice 8): what a merge policy decided and discarded, and the restore path
 * that makes those decisions reversible.
 *
 * There is no "sync is running" state here, deliberately -- `SyncEngine`
 * (`:core:sync`) has no caller anywhere in the app (M4a stops before M4b's
 * transport), so a status row backed by [dev.charanjeev.bahi.core.model.SyncStatus]
 * would always read Idle and would be decorative rather than informative.
 * This screen shows the one thing that has real data behind it right now:
 * conflicts a merge already resolved.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val conflictRepository: SyncConflictRepository,
) : ViewModel() {

    private val restoreMessage = MutableStateFlow<RestoreMessage?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        conflictRepository.observeConflicts(),
        restoreMessage,
    ) { conflicts, message ->
        if (conflicts.isEmpty()) {
            SettingsUiState.Empty(restoreMessage = message)
        } else {
            SettingsUiState.Success(
                conflicts = conflicts.map {
                    ConflictListItem(
                        id = it.id,
                        table = it.table,
                        field = it.field,
                        chosenValue = it.chosenValue,
                        discardedValue = it.discardedValue,
                        reason = it.reason,
                        resolvedAt = it.resolvedAt,
                    )
                }.toPersistentList(),
                restoreMessage = message,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState.Loading,
    )

    fun onRestoreRequested(conflictId: String) {
        viewModelScope.launch {
            val outcome = conflictRepository.restore(conflictId)
            restoreMessage.value = RestoreMessage(conflictId, outcome)
        }
    }

    fun onDismissRequested(conflictId: String) {
        viewModelScope.launch { conflictRepository.acknowledge(conflictId) }
    }

    fun onRestoreMessageShown() {
        restoreMessage.value = null
    }
}
