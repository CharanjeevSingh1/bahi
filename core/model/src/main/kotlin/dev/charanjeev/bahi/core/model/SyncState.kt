package dev.charanjeev.bahi.core.model

import kotlinx.datetime.Instant

/**
 * Per-row sync bookkeeping. Kept out of [Transaction] so the domain model isn't
 * polluted by transport concerns -- the repository maps between the two.
 */
data class SyncMetadata(
    val rowId: String,
    val localRevision: Long,
    val remoteRevision: Long?,
    val lastSyncedAt: Instant?,
    val pendingOperation: PendingOperation?,
)

enum class PendingOperation { UPSERT, DELETE }

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Failed(val reason: String, val retryable: Boolean) : SyncStatus
}
