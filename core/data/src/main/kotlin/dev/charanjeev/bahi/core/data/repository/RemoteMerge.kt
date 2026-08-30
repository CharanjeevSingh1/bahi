package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One side of a merge, as the applier sees it: a row's payload (`null` =
 * tombstone) plus the two facts a tiebreak needs. Structurally the same
 * shape as `:core:sync`'s `MergeSide` -- deliberately a separate type rather
 * than that one reused, because [RemoteMerge] is the seam that keeps this
 * module from depending on `:core:sync` (see its doc).
 */
data class MergeSideInput(val payload: JsonObject?, val updatedAt: Long, val deviceId: String)

/** One field a merge had to pick a side for. See `:core:sync`'s `FieldConflict`. */
data class ResolvedField(val field: String, val chosenValue: JsonElement, val discardedValue: JsonElement, val reason: String)

/** The merged payload for one row, and every field-level tie the merge broke. */
data class MergeOutcome(val payload: JsonObject?, val conflicts: List<ResolvedField> = emptyList())

/**
 * The one decision `SyncApplier` needs from `:core:sync`'s conflict resolver,
 * narrowed to a pure function and defined on this side of the boundary.
 *
 * `SyncApplier` has to read a row's current state and the shadow, decide the
 * merge, and write the result back all inside one Room transaction
 * (docs/sync-design.md §6.2) -- reading and writing separately would leave a
 * window for a concurrent local edit to land between them and be silently
 * overwritten by a stale merge. That means the merge *decision* has to be
 * reachable from inside `:core:data`'s transaction, but rule 3's boundary and
 * the module graph both run the other way: `:core:sync` depends on
 * `:core:data`, never the reverse, so `:core:data` cannot import
 * `:core:sync`'s `ConflictResolver` directly.
 *
 * [RemoteMerge] is the narrow interface this module owns instead. It is
 * implemented in `:core:sync` (`ConflictResolverRemoteMerge`, a thin adapter
 * over `DefaultConflictResolver`) and bound here by Hilt, so the actual merge
 * *policy* -- every field's resolution rule, the notes algorithm, the
 * category lock tiebreak -- still lives in exactly one place, `:core:sync`,
 * as docs/sync-design.md §9's module placement calls for. Only the function
 * shape crosses the boundary, not the policy.
 */
fun interface RemoteMerge {
    fun merge(table: SyncTable, local: MergeSideInput, remote: MergeSideInput, base: JsonObject?): MergeOutcome
}
