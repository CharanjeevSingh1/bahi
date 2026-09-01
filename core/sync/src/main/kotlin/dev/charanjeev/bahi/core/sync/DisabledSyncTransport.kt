package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import javax.inject.Inject

/**
 * The only [SyncTransport] this app's Hilt graph ever constructs so far.
 * `DriveTransport` exists now (docs/sync-design.md §13, slice 9e) but is
 * deliberately not bound here yet -- nothing calls `SyncEngine.sync`
 * anywhere in the app until slice 9g, so a conditional binding today would
 * be reachable by nothing; making [dev.charanjeev.bahi.core.sync.di.
 * SyncModule]'s binding conditional on [SyncConfiguration.isConfigured] is
 * left for 9g, wired and manually verified together with the code that
 * first actually calls it. Until then this stays bound unconditionally.
 *
 * Every method throws rather than no-ops. Nothing in the app calls
 * `SyncEngine.sync` yet (§13's note on slice 8), so a real call reaching this
 * class would mean a caller was wired ahead of a transport able to answer
 * it -- a bug to surface loudly during development, not one to hide behind
 * silently-empty results that would look like "sync ran and found nothing."
 */
class DisabledSyncTransport @Inject constructor() : SyncTransport {

    override suspend fun push(batch: OpBatch) {
        error("SyncEngine has no transport configured -- see docs/sync-setup.md")
    }

    override suspend fun pull(after: Map<String, Long>): List<OpBatch> {
        error("SyncEngine has no transport configured -- see docs/sync-setup.md")
    }

    override suspend fun snapshot(): RemoteSnapshot {
        error("SyncEngine has no transport configured -- see docs/sync-setup.md")
    }
}
