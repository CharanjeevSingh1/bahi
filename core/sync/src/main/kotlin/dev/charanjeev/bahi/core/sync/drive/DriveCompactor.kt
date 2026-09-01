package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SnapshotRow
import dev.charanjeev.bahi.core.model.TOMBSTONE_HORIZON_DAYS
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.crypto.OpBatchCipher
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import okhttp3.Call

/** §8.3's "only compact when the op-file count crosses a threshold" -- chosen so a handful of ordinary edits never trigger a round, but a season of offline changes or a CSV import does. */
private const val OP_FILE_COMPACTION_THRESHOLD = 50

/** §8.3's "never delete an op file younger than a fixed grace period" -- comfortably longer than [ELECTION_WAIT_MILLIS] and Drive's undocumented listing-propagation delay, short enough not to matter to when compaction actually helps. */
private val COMPACTION_GRACE_PERIOD: Duration = Duration.ofHours(1)

/** D13: "waits a short window (30 seconds -- comfortably longer than any plausible listing-propagation delay)." A constructor default, not a fixed constant, because a test cannot afford a real 30-second wait per case -- see [DriveCompactor]'s own constructor doc. */
private const val ELECTION_WAIT_MILLIS = 30_000L

/**
 * D13's single-elected-compactor (docs/sync-design.md §8.3, D13, slice 9f):
 * exactly one device ever folds the op log into a [RemoteSnapshot] and
 * deletes what it folded, closing the multi-compactor race §8.3's M4b design
 * pass worked through rather than shipping as a live risk.
 *
 * **Not wired to any caller yet.** Like [DriveTransport] before slice 9g
 * binds it into [dev.charanjeev.bahi.core.sync.di.SyncModule], this class is
 * complete and tested against `InMemoryFakeDrive` but nothing in the app
 * constructs one: the periodic trigger that would call [electIfNeeded] and
 * [compact] repeatedly is slice 9g's job, and [deviceId] has nowhere real to
 * come from until whatever wires that trigger also solves device identity --
 * the same gap [dev.charanjeev.bahi.core.sync.SyncEngine]'s own `deviceId`
 * doc names as a slice-8/M4b concern still unresolved. The Settings takeover
 * affordance D13 also calls for is, for the same reason, [isStale] and
 * [takeOver] existing and tested here rather than a wired button in
 * `:feature:settings` -- see this class's `docs/sync-design.md` §13 slice 9f
 * entry for the full reasoning.
 */
class DriveCompactor(
    driveAuthorization: DriveAuthorization,
    private val keyStore: SyncEncryptionKeyStore,
    callFactory: Call.Factory,
    private val deviceId: String,
    private val electionWaitMillis: Long = ELECTION_WAIT_MILLIS,
    private val clock: () -> Instant = Instant::now,
) {

    private val api = DriveApi(callFactory, DriveAccessToken(driveAuthorization)::invoke)

    /**
     * D13's claim-then-verify: if no `owner.json` exists, write one naming
     * this device, wait [electionWaitMillis], then re-read and keep only the
     * lexicographically-lowest [DriveFile.appProperties] `deviceId` among
     * whatever now exists (§5.5's tiebreak shape, reused for the same
     * "deterministic so it converges" reason), deleting every losing claim.
     * If an owner already exists, this only answers whether it names this
     * device -- no wait, no write -- which is what makes election "once per
     * install, not every cycle": after the first successful call anywhere,
     * every device's every subsequent call takes this branch.
     */
    suspend fun electIfNeeded(): Boolean {
        val existing = api.list(KIND, KIND_OWNER)
        return if (existing.isEmpty()) claimAndResolve() else reconcileAndWinner(existing) == deviceId
    }

    /**
     * D13's manual escape hatch: "no device has compacted in 90+ days, make
     * this the compactor." Deletes whatever owner file(s) currently exist --
     * the elected device being gone is exactly the case where nothing else
     * will ever remove them -- then runs the same claim-then-verify
     * [electIfNeeded] uses, so two users hitting "take over" around the same
     * time resolve the same deterministic way a first-install race does.
     * Callers are expected to have already checked [isStale]; this does not
     * check it itself, so a caller (a test, or a future forced-takeover UI
     * path) can also use it without waiting on real elapsed time.
     */
    suspend fun takeOver(): Boolean {
        api.list(KIND, KIND_OWNER).forEach { api.delete(it.id) }
        return claimAndResolve()
    }

    /**
     * D13's staleness gate for [takeOver], sized against
     * [TOMBSTONE_HORIZON_DAYS] -- "the same horizon constant §7 already
     * names."
     *
     * The precise question this answers is "has a live compactor been
     * folding the op log," not "when was the owner file written" -- an
     * elected device that is still running but has simply never crossed
     * [OP_FILE_COMPACTION_THRESHOLD] looks identical, on `claimedAt` alone,
     * to one that vanished the day after election, and flagging the former
     * as stale would be wrong. So this prefers the newest snapshot's age
     * once one exists, and only falls back to the owner claim's age -- combined
     * with an actual op-file backlog past the threshold, not age alone -- when
     * no snapshot has ever been written, so a quiet account that has
     * genuinely never had enough to compact is never flagged.
     */
    suspend fun isStale(): Boolean {
        val owners = api.list(KIND, KIND_OWNER)
        if (owners.isEmpty()) return false
        val threshold = Duration.ofDays(TOMBSTONE_HORIZON_DAYS.toLong())

        val newestSnapshotAge = api.list(KIND, KIND_SNAPSHOT)
            .mapNotNull { it.createdTime?.let(::parseInstantOrNull) }
            .maxOrNull()
            ?.let { Duration.between(it, clock()) }
        if (newestSnapshotAge != null) return newestSnapshotAge > threshold

        val oldestActiveClaimAge = owners
            .mapNotNull { it.appProperties["claimedAt"]?.toLongOrNull() }
            .maxOrNull()
            ?.let { Duration.between(Instant.ofEpochMilli(it), clock()) }
            ?: return false
        return oldestActiveClaimAge > threshold && api.list(KIND, KIND_OPS).size >= OP_FILE_COMPACTION_THRESHOLD
    }

    /**
     * Folds every op file older than [COMPACTION_GRACE_PERIOD] into a new
     * snapshot merged with whatever the previous one already held, writes it,
     * then deletes exactly the op files just folded in -- write fully before
     * deleting anything (§8.3's mitigation list). A no-op if this device
     * isn't the elected owner, if fewer than [OP_FILE_COMPACTION_THRESHOLD]
     * op files are old enough to fold, or if the election is lost between the
     * write and the delete -- that last case is only reachable during D13's
     * narrow first-election race, and leaves one harmless extra snapshot
     * rather than a wrongly-elected device deleting files it was never
     * entitled to.
     */
    suspend fun compact() {
        if (!isElectedOwner()) return

        val opFiles = api.list(KIND, KIND_OPS)
        val eligible = opFiles.filter { file ->
            val createdAt = file.createdTime?.let(::parseInstantOrNull) ?: return@filter false
            Duration.between(createdAt, clock()) >= COMPACTION_GRACE_PERIOD
        }
        if (eligible.size < OP_FILE_COMPACTION_THRESHOLD) return

        val key = keyStore.requireSyncKey()
        val previous = api.latestSnapshot(key)
        val rows = previous?.rows?.associateByTo(LinkedHashMap()) { it.table to it.rowId } ?: LinkedHashMap()
        val horizon = previous?.horizon?.toMutableMap() ?: mutableMapOf()

        for (file in eligible) {
            val batch = OpBatchCipher.decrypt(api.get(file.id), key)
            horizon[batch.deviceId] = maxOf(horizon[batch.deviceId] ?: 0L, batch.seq)
            for (op in batch.ops) {
                val rowKey = op.table to op.rowId
                val current = rows[rowKey]
                if (current != null && current.remoteRevision >= op.remoteRevision) continue
                val payload = op.payload
                if (payload == null) rows.remove(rowKey) else rows[rowKey] = SnapshotRow(op.table, op.rowId, op.remoteRevision, op.updatedAt, payload)
            }
        }

        val n = (api.list(KIND, KIND_SNAPSHOT).mapNotNull { it.appProperties["n"]?.toLongOrNull() }.maxOrNull() ?: 0L) + 1
        api.create(
            name = "snapshot-$n.json",
            appProperties = mapOf(KIND to KIND_SNAPSHOT, "n" to n.toString()),
            content = OpBatchCipher.encryptSnapshot(RemoteSnapshot(horizon = horizon, rows = rows.values.toList()), key),
        )

        if (!isElectedOwner()) return
        eligible.forEach { api.delete(it.id) }
    }

    private suspend fun isElectedOwner(): Boolean = winner(api.list(KIND, KIND_OWNER)) == deviceId

    private suspend fun claimAndResolve(): Boolean {
        api.create(
            name = "owner.json",
            appProperties = mapOf(KIND to KIND_OWNER, "deviceId" to deviceId, "claimedAt" to clock().toEpochMilli().toString()),
            content = ByteArray(0),
        )
        delay(electionWaitMillis)
        return reconcileAndWinner(api.list(KIND, KIND_OWNER)) == deviceId
    }

    /** Deletes every owner file that isn't the winner, and returns it -- only ever called from an election path; [compact]'s own ownership checks are read-only via [isElectedOwner]. */
    private suspend fun reconcileAndWinner(files: List<DriveFile>): String? {
        val winningId = winner(files) ?: return null
        files.filter { it.appProperties["deviceId"] != winningId }.forEach { api.delete(it.id) }
        return winningId
    }

    private fun winner(files: List<DriveFile>): String? = files.mapNotNull { it.appProperties["deviceId"] }.minOrNull()
}

private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
