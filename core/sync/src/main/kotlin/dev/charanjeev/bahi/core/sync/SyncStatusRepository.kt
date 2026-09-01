package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import dev.charanjeev.bahi.core.model.SyncStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant

/**
 * What `SettingsViewModel`'s own doc has said since slice 8: [SyncStatus] is
 * decorative until something actually calls `SyncEngine.sync`. [SyncRunner]
 * (slice 9g) is that caller, and this is where it reports what happened so
 * `:feature:settings` has something real to read instead of a value that
 * would hold `Idle` forever.
 *
 * [status] is in-memory only, not persisted -- "a sync is running right now"
 * is only ever true for the lifetime of the process that is running one, and
 * restarting to `Idle` on process death is the correct answer, not a gap.
 * [lastSuccessfulSyncAt] is the opposite: it is exactly the fact that has to
 * survive process death, since §8.7's staleness warning ("last synced 6 days
 * ago") is meaningless if it resets to null every time the app is killed.
 */
interface SyncStatusRepository {
    val status: Flow<SyncStatus>
    val lastSuccessfulSyncAt: Flow<Instant?>

    fun reportRunning()
    suspend fun reportSuccess(at: Instant)
    fun reportFailed(reason: String, retryable: Boolean)
    fun reportNeedsReauthorization()
}

@Singleton
class DefaultSyncStatusRepository @Inject constructor(
    private val preferences: UserPreferencesDataSource,
) : SyncStatusRepository {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    override val status: Flow<SyncStatus> = _status.asStateFlow()

    override val lastSuccessfulSyncAt: Flow<Instant?> = preferences.lastSuccessfulSyncAt

    override fun reportRunning() {
        _status.value = SyncStatus.Running
    }

    override suspend fun reportSuccess(at: Instant) {
        preferences.setLastSuccessfulSyncAt(at)
        _status.value = SyncStatus.Idle
    }

    override fun reportFailed(reason: String, retryable: Boolean) {
        _status.value = SyncStatus.Failed(reason, retryable)
    }

    override fun reportNeedsReauthorization() {
        _status.value = SyncStatus.NeedsReauthorization
    }
}
