package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.model.SyncStatus
import dev.charanjeev.bahi.core.sync.SyncStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

/** Only [lastSuccessfulSyncAt] is real state -- SettingsViewModel reads nothing else off this seam yet (its own doc says why). */
class FakeSyncStatusRepository(lastSuccessfulSyncAt: Instant? = null) : SyncStatusRepository {
    private val _lastSuccessfulSyncAt = MutableStateFlow(lastSuccessfulSyncAt)
    override val status: Flow<SyncStatus> = MutableStateFlow(SyncStatus.Idle)
    override val lastSuccessfulSyncAt: Flow<Instant?> = _lastSuccessfulSyncAt

    fun emit(instant: Instant?) {
        _lastSuccessfulSyncAt.value = instant
    }

    override fun reportRunning(): Unit = error("not used by SettingsViewModel")
    override suspend fun reportSuccess(at: Instant): Unit = error("not used by SettingsViewModel")
    override fun reportFailed(reason: String, retryable: Boolean): Unit = error("not used by SettingsViewModel")
    override fun reportNeedsReauthorization(): Unit = error("not used by SettingsViewModel")
}
