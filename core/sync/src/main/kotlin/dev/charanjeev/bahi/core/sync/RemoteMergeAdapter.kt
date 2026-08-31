package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.data.repository.MergeOutcome
import dev.charanjeev.bahi.core.data.repository.MergeSideInput
import dev.charanjeev.bahi.core.data.repository.RemoteMerge
import dev.charanjeev.bahi.core.data.repository.ResolvedField
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * The thin side of the seam `:core:data`'s [RemoteMerge] doc describes: this
 * class exists only to translate between that interface's shapes and
 * [ConflictResolver]'s. Every actual merge decision -- field policies, the
 * notes algorithm, the category lock tiebreak -- stays in [ConflictResolver]
 * and [fieldPoliciesFor], exactly where docs/sync-design.md §9 puts them.
 */
class ConflictResolverRemoteMerge @Inject constructor(
    private val resolver: ConflictResolver,
) : RemoteMerge {

    override fun merge(table: SyncTable, local: MergeSideInput, remote: MergeSideInput, base: JsonObject?): MergeOutcome {
        val result = resolver.resolve(
            table = table,
            local = MergeSide(local.payload, local.updatedAt, local.deviceId),
            remote = MergeSide(remote.payload, remote.updatedAt, remote.deviceId),
            base = base,
        )
        return MergeOutcome(
            payload = result.payload,
            conflicts = result.conflicts.map { ResolvedField(it.field, it.chosenValue, it.discardedValue, it.reason) },
        )
    }
}
