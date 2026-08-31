package dev.charanjeev.bahi.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The unit of exchange between two devices: one row, at one revision
 * (docs/sync-design.md §4.2).
 *
 * [payload] is a field map rather than a serialised entity for two reasons.
 * Entities never leave `:core:data` (CLAUDE.md rule 3), so there is nothing
 * here to serialise even if it were wanted; and a map lets the resolver work
 * one field at a time, which is the whole premise of §5.2. It also means a
 * field written by a newer version of the app survives a round trip through an
 * older one instead of being silently dropped.
 *
 * The keys are database column names, not domain property names. The resolver
 * compares a payload against a `sync_shadow` payload built from the same
 * columns, so the two have to agree, and the column name is the one of the two
 * that a migration is forced to keep honest.
 *
 * A null [payload] is a tombstone. There is no `deleted` flag, because a
 * deleted row has no fields worth carrying and a flag alongside a populated
 * payload would admit a state -- "deleted, but here are its values" -- that
 * nothing should ever have to interpret.
 *
 * [updatedAt] is the row's own `updated_at`, not a sync timestamp, and it is
 * never used to order two devices' edits against each other (§5.5). It is
 * carried because it is a field of the row that the user can see.
 */
@Serializable
data class SyncOp(
    val table: String,
    val rowId: String,
    val remoteRevision: Long,
    val deviceId: String,
    val updatedAt: Long,
    val payload: JsonObject? = null,
) {
    val isTombstone: Boolean get() = payload == null
}

/**
 * A run of ops from one device, in the order that device produced them.
 *
 * [version] is the same escape hatch as the `h1:` prefix on a content-derived
 * id (§3.1): the op log on the remote is append-only, so batches written by
 * this version will still be there to be read by a version that has changed
 * the format, and a reader that cannot tell which rules to apply has no way to
 * refuse safely. [isReadable] is the branch -- a batch from the future is
 * skipped rather than misinterpreted.
 */
@Serializable
data class OpBatch(
    val deviceId: String,
    val seq: Long,
    val ops: List<SyncOp>,
    val version: Int = OP_FORMAT_VERSION,
) {
    val isReadable: Boolean get() = version <= OP_FORMAT_VERSION
}

const val OP_FORMAT_VERSION: Int = 1

/**
 * The tables that sync (§1.1, D1). Declared parent-first: a budget or a rule
 * whose category has not arrived yet would fail its foreign key, so this is
 * also the order the engine applies a batch in.
 *
 * [of] returns null rather than throwing for the same reason [OpBatch.version]
 * exists. An op naming a table this version does not have is not corrupt, it is
 * from a version that has one more table, and the only safe thing an older
 * device can do with it is leave it alone. Failing the batch instead would mean
 * one new table stops sync working for everything else.
 */
enum class SyncTable(val tableName: String) {
    CATEGORIES("categories"),
    TRANSACTIONS("transactions"),
    BUDGETS("budgets"),
    CATEGORY_RULES("category_rules"),
    ;

    companion object {
        fun of(tableName: String): SyncTable? = entries.firstOrNull { it.tableName == tableName }
    }
}

/**
 * The tombstone horizon (docs/sync-design.md §7, D8): a local tombstone
 * older than this is hard-deleted, and a device whose per-peer pull cursor
 * has fallen behind a compacted remote's [RemoteSnapshot.horizon] can no
 * longer trust an incremental pull and must reconcile against the snapshot
 * instead. One constant for both, on purpose -- the value that makes the
 * first safe is exactly the value that makes the second necessary: a
 * tombstone is only safe to forget once every plausibly-offline device has
 * had a chance to see it, and that is the same "longer than any device is
 * plausibly offline" bound §7 sizes the horizon against.
 */
const val TOMBSTONE_HORIZON_DAYS: Int = 90

/**
 * What a compacted remote answers instead of raw ops once it has forgotten
 * history older than [horizon] (docs/sync-design.md §7, §8.3). Modelled after
 * the shape §8.3 settled on for Drive -- `snapshot/<n>.json` holding every
 * device's merged current state -- rather than after the ops that produced
 * it, because that history is exactly what compaction has thrown away.
 *
 * [rows] carries only *live* rows: a snapshot is "what currently exists", not
 * a log, so a row deleted and then compacted away simply has no entry, and a
 * device that still has that row locally learns this by its absence (§7's
 * "for any local row absent from the snapshot, decide...").
 */
@Serializable
data class RemoteSnapshot(
    val horizon: Map<String, Long>,
    val rows: List<SnapshotRow>,
)

/**
 * One live row as of a [RemoteSnapshot]. Same shape as [SyncOp] minus
 * [SyncOp.isTombstone] (a snapshot never carries a tombstone row, see
 * [RemoteSnapshot]) and minus a single authoring [SyncOp.deviceId] --
 * compaction merges every device's contributions into one state, so there is
 * no one device to attribute a snapshot row to. A device applying it treats
 * it exactly like an ordinary pulled op (docs/sync-design.md §7: "the
 * reconciliation path is needed anyway for a new device").
 */
@Serializable
data class SnapshotRow(
    val table: String,
    val rowId: String,
    val remoteRevision: Long,
    val updatedAt: Long,
    val payload: JsonObject,
)
