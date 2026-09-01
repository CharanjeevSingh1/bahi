package dev.charanjeev.bahi.feature.settings

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.SyncConflictRepository
import dev.charanjeev.bahi.core.sync.SyncConfiguration
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val driveAuthorization: DriveAuthorization,
    syncConfiguration: SyncConfiguration,
) : ViewModel() {

    private val restoreMessage = MutableStateFlow<RestoreMessage?>(null)

    // A PendingIntent isn't state -- launching it a second time on
    // recomposition or process restart would be wrong -- so it travels as a
    // one-shot event, the same reasoning restoreMessage's own ack path
    // (onRestoreMessageShown) exists to avoid re-showing a snackbar.
    private val consentRequests = Channel<PendingIntent>(Channel.CONFLATED)
    val consentRequestEvents = consentRequests.receiveAsFlow()

    // Read once: whether sync.properties existed at build time can't change for
    // the life of this process, so there is nothing to observe here -- see
    // SettingsUiState.syncConfigured's doc for why it still has to reach every
    // state, Loading included.
    private val syncConfigured = syncConfiguration.isConfigured

    val uiState: StateFlow<SettingsUiState> = combine(
        conflictRepository.observeConflicts(),
        restoreMessage,
        driveAuthorization.connectionState,
    ) { conflicts, message, driveConnection ->
        if (conflicts.isEmpty()) {
            SettingsUiState.Empty(syncConfigured = syncConfigured, restoreMessage = message, driveConnection = driveConnection)
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
                syncConfigured = syncConfigured,
                driveConnection = driveConnection,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState.Loading(syncConfigured = syncConfigured),
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

    /** Tapping "Connect" or "Reconnect" on the Drive row. */
    fun onConnectDriveRequested() {
        viewModelScope.launch {
            when (val request = driveAuthorization.beginAuthorization()) {
                // driveAuthorization.connectionState already reflects a
                // Resolved outcome (DriveAuthorization's own contract) --
                // nothing further to do here.
                is ConsentRequest.Resolved -> Unit
                is ConsentRequest.NeedsConsent -> consentRequests.trySend(request.pendingIntent)
            }
        }
    }

    /** The Activity Result callback for a launched consent [PendingIntent], forwarded here. */
    fun onAuthorizationResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch { driveAuthorization.completeAuthorization(resultCode, data) }
    }
}
