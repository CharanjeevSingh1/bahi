package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import javax.inject.Inject

/**
 * What every build without `sync.properties` gets (docs/sync-design.md §13,
 * slice 9a, D12) -- and, since slice 9g,
 * [dev.charanjeev.bahi.core.sync.di.SyncTransportModule] now really does
 * choose between this and [dev.charanjeev.bahi.core.sync.drive.DriveTransport]
 * at runtime on [SyncConfiguration.isConfigured], the conditional binding
 * this class's doc used to describe as deferred.
 *
 * Every method throws rather than no-ops. [SyncRunner] is `SyncEngine`'s only
 * caller (slice 9g), and it never runs against this transport --
 * [dev.charanjeev.bahi.core.sync.di.SyncTransportModule] only ever hands it
 * [DriveTransport] when [SyncConfiguration.isConfigured] is true, and
 * [dev.charanjeev.bahi.core.sync.work.DefaultSyncScheduler] never schedules
 * anything otherwise -- so a real call reaching this class would mean both of
 * those guards were bypassed, a bug worth surfacing loudly during
 * development rather than hiding behind a silently-empty result that would
 * look like "sync ran and found nothing."
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
