package dev.charanjeev.bahi.feature.settings

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.SyncConflictRepository
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.sync.SyncConfiguration
import dev.charanjeev.bahi.core.sync.SyncStatusRepository
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.work.SyncScheduler
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
import kotlinx.datetime.Clock

/**
 * The Settings screen's real content (docs/sync-design.md §5.6 slice 8,
 * §8.7 slice 9g): what a merge policy decided and discarded with a restore
 * path, and -- now that [dev.charanjeev.bahi.core.sync.SyncRunner] is
 * `SyncEngine`'s first real caller -- when sync last actually succeeded.
 *
 * There is still no live "sync is running right now" state on this screen:
 * [dev.charanjeev.bahi.core.model.SyncStatus.Running] exists and
 * [SyncStatusRepository] carries it, but §8.7 only ever asked for a
 * last-successful-sync signal here, not a spinner, and this screen sticks to
 * that rather than inventing a second surface nothing designed for.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val conflictRepository: SyncConflictRepository,
    private val driveAuthorization: DriveAuthorization,
    syncConfiguration: SyncConfiguration,
    private val syncStatusRepository: SyncStatusRepository,
    private val syncScheduler: SyncScheduler,
    private val categoryRepository: CategoryRepository,
    private val clock: Clock,
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

    init {
        // §8.7: opening Settings is one of the two moments (with app
        // foreground) worth nudging sync early rather than waiting up to 4
        // hours for the next periodic tick. A no-op on an unconfigured build
        // or if one is already in flight -- SyncScheduler owns both guards.
        syncScheduler.requestExpeditedSync()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        conflictRepository.observeConflicts(),
        restoreMessage,
        driveAuthorization.connectionState,
        syncStatusRepository.lastSuccessfulSyncAt,
        categoryRepository.observeCategories(),
    ) { conflicts, message, driveConnection, lastSuccessfulSyncAt, categories ->
        val lastSyncDisplay = lastSyncDisplay(clock.now(), lastSuccessfulSyncAt)
        if (conflicts.isEmpty()) {
            SettingsUiState.Empty(
                syncConfigured = syncConfigured,
                restoreMessage = message,
                driveConnection = driveConnection,
                lastSyncDisplay = lastSyncDisplay,
            )
        } else {
            val categoriesById = categories.associateBy { it.id }
            SettingsUiState.Success(
                conflicts = conflicts.map {
                    ConflictListItem(
                        id = it.id,
                        table = it.table,
                        field = it.field,
                        chosenValue = resolveIfCategoryReference(it.table, it.field, it.chosenValue, categoriesById),
                        discardedValue = resolveIfCategoryReference(it.table, it.field, it.discardedValue, categoriesById),
                        reason = it.reason,
                        resolvedAt = it.resolvedAt,
                    )
                }.toPersistentList(),
                restoreMessage = message,
                syncConfigured = syncConfigured,
                driveConnection = driveConnection,
                lastSyncDisplay = lastSyncDisplay,
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

/**
 * `category_id` names a category on every table it appears on
 * (transactions, budgets, category_rules); `parent_id` does the same but
 * only on categories itself -- no other field in `FieldPolicies.kt` points
 * at another table's row the way these two do (docs/sync-design.md §11,
 * §8.8). `import_batch_id` and `account_id` are id-*shaped* too, but
 * neither names a row in a table this screen could join against -- there is
 * no import-batch table (§1.1) and no accounts table at all yet -- so they
 * stay exactly as the resolver recorded them.
 */
private fun isCategoryReference(table: SyncTable, field: String): Boolean =
    field == "category_id" || (table == SyncTable.CATEGORIES && field == "parent_id")

/**
 * [categoriesById] is built from [CategoryRepository.observeCategories],
 * which already filters `deleted_at IS NULL` -- so a soft-deleted or
 * reaped category is simply absent from it, and the `?:` below is that
 * absence read as "gone" rather than a tombstone check of its own. Falling
 * back to the id itself, not a placeholder like "Unknown category", is
 * deliberate even though the rule and budget screens use a placeholder for
 * the same situation: a conflict row is a record of what was chosen and
 * discarded, and the id is still the one piece of evidence a user could go
 * looking for if a placeholder swallowed it.
 */
private fun resolveIfCategoryReference(
    table: SyncTable,
    field: String,
    value: ConflictValue,
    categoriesById: Map<String, Category>,
): ConflictValue {
    if (!isCategoryReference(table, field)) return value
    return when (value) {
        is ConflictValue.Text -> ConflictValue.Text(categoriesById[value.value]?.name ?: value.value)
        ConflictValue.None, is ConflictValue.Number, is ConflictValue.Flag -> value
    }
}
