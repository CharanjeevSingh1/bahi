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

    /**
     * Distinct from a transient [Failed] (docs/sync-design.md §8.6, slice 9d):
     * the user revoked `drive.appdata` access from their Google Account, or
     * never granted it. Retrying on a backoff schedule can never succeed on
     * its own the way it can for [Failed] -- only the user re-running consent
     * can. Additive, and still decorative in the sense slice 8 already named
     * for this whole type: `SyncEngine` has no caller until 9g, so nothing
     * produces this value yet either. `:feature:settings`' Drive connection
     * row reads `DriveAuthorization.connectionState` directly rather than
     * this, for the same reason slice 8 preferred `observeUnacknowledgedCount`
     * over `SyncStatus` there: it is live from the moment a device first tries
     * to authorize, not only once a sync cycle exists to update it.
     */
    data object NeedsReauthorization : SyncStatus
}
