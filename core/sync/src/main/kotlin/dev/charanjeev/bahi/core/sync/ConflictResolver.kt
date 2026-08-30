package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.FieldResolution
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * One side of a merge: a row's payload (`null` = tombstone) plus the two
 * facts a tiebreak needs and nothing else (docs/sync-design.md §5.5).
 * [updatedAt] and [deviceId] are never used to decide *whether* two sides
 * conflict, only -- once they genuinely do and no more specific rule applies
 * -- which one a deterministic tiebreak keeps.
 */
data class MergeSide(
    val payload: JsonObject?,
    val updatedAt: Long,
    val deviceId: String,
)

/**
 * One field where both sides had changed it away from the same base and a
 * policy had to pick (§5.2's fourth row). The information `sync_conflicts`
 * needs (§5.6), minus the bookkeeping (`id`, `resolvedAt`, `tableName`/
 * `rowId`) that only makes sense once the engine knows which row it just
 * merged -- adding those is the engine's job, not this one's.
 */
data class FieldConflict(
    val field: String,
    val chosenValue: JsonElement,
    val discardedValue: JsonElement,
    val reason: String,
)

/**
 * The merged payload for one row, and every field-level tie the merge had to
 * break. [payload] is `null` when the row is a tombstone in the result --
 * both sides agree it is deleted, or one side deleted it and the other's edit
 * was a rule's own guess that does not resurrect it (§5.3).
 */
data class MergeResult(
    val payload: JsonObject?,
    val conflicts: List<FieldConflict>,
)

/**
 * Merges two devices' changes to one row, given the last state they agreed on
 * (docs/sync-design.md §5).
 *
 * No field here is unconditionally "remote wins". The M0 sketch this
 * interface started as said `amount, date, description -> remote wins (they
 * came from the bank)`, but no bank ever talks to this app -- every one of
 * those fields is produced by a device, either typed by the user or
 * interpreted out of a CSV by that device's importer, and two devices can
 * interpret the same file into two different real dates
 * (docs/csv-import-design.md §2). That line was false when it was written and
 * stood for three milestones with nothing to test it (§12); the actual policy
 * for those fields is [FieldResolution.USER_PROMPT] (FieldPolicies.kt), the
 * same tiebreak-and-record policy most columns get.
 *
 * Only ever called for a row **both sides changed** away from [base] -- the
 * row-level fast-forward classification in §5.2's middle two rows (only one
 * side changed) is the engine's job (slice 5) and never reaches here at all.
 * There is no `applyRemote(entity)` anywhere in this codebase: the only way a
 * remote value reaches Room is as the return value of [resolve], which is
 * §5.4's first enforcement of `categoryLockedByUser` that isn't a SQL guard.
 *
 * [base] is `null` for two different facts this class does not need to tell
 * apart: no shared history at all, and shared history that says the row was
 * deleted (§4.1). Neither carries a field value to compare against, so a
 * per-field merge treats them identically -- every field counts as changed on
 * both sides.
 *
 * Every field a synced table carries has an entry in [fieldPoliciesFor] --
 * `FieldPolicyCoverageTest` fails the build otherwise -- so this never has to
 * decide what an unmapped column means.
 */
interface ConflictResolver {
    fun resolve(table: SyncTable, local: MergeSide, remote: MergeSide, base: JsonObject?): MergeResult
}

class DefaultConflictResolver : ConflictResolver {

    override fun resolve(table: SyncTable, local: MergeSide, remote: MergeSide, base: JsonObject?): MergeResult {
        val localPayload = local.payload
        val remotePayload = remote.payload
        return when {
            localPayload == null && remotePayload == null -> MergeResult(payload = null, conflicts = emptyList())
            localPayload == null -> resolveDeletionVsEdit(editedPayload = remotePayload!!, base = base)
            remotePayload == null -> resolveDeletionVsEdit(editedPayload = localPayload, base = base)
            else -> mergeFields(table, local, localPayload, remote, remotePayload, base)
        }
    }

    /**
     * §5.3: a concurrent edit beats a concurrent delete -- a lost edit cannot
     * be recovered and a re-delete costs one tap -- except when the "edit" is
     * a rule's own guess (it changed [CATEGORY_ID] and nothing else, which is
     * what [dev.charanjeev.bahi.core.database.dao.TransactionDao.applyRuleCategory]'s
     * single-column `UPDATE` produces). A rule's guess is not a user's intent
     * and does not resurrect a row the user deleted by hand. Neither branch
     * is recorded: this is a row-level decision, not one of the per-field
     * ties §5.6 exists for.
     */
    private fun resolveDeletionVsEdit(editedPayload: JsonObject, base: JsonObject?): MergeResult {
        val changedFields = editedPayload.keys.filterTo(mutableSetOf()) { field ->
            base == null || editedPayload.getValue(field) != base[field]
        }
        val isRuleGuessOnly = changedFields == setOf(CATEGORY_ID)
        return if (isRuleGuessOnly) {
            MergeResult(payload = null, conflicts = emptyList())
        } else {
            MergeResult(payload = editedPayload, conflicts = emptyList())
        }
    }

    private fun mergeFields(
        table: SyncTable,
        local: MergeSide,
        localPayload: JsonObject,
        remote: MergeSide,
        remotePayload: JsonObject,
        base: JsonObject?,
    ): MergeResult {
        val policies = fieldPoliciesFor(table)
        val conflicts = mutableListOf<FieldConflict>()

        val categoryResolution = if (table == SyncTable.TRANSACTIONS) {
            resolveCategoryPair(localPayload, remotePayload, base, local, remote, conflicts)
        } else {
            emptyMap()
        }

        val merged = buildJsonObject {
            for (field in policies.keys) {
                val resolved = categoryResolution[field] ?: resolveField(
                    field = field,
                    policy = policies.getValue(field),
                    localValue = localPayload.getValue(field),
                    remoteValue = remotePayload.getValue(field),
                    base = base,
                    local = local,
                    remote = remote,
                    conflicts = conflicts,
                )
                put(field, resolved)
            }
        }
        return MergeResult(payload = merged, conflicts = conflicts)
    }

    /**
     * `category_id` and `category_locked_by_user` resolved as one decision
     * (§5.4), because the lock has to travel with the category it locked: an
     * independent per-field tiebreak on each could pick different sides and
     * leave the row with one side's category and the other's lock flag.
     */
    private fun resolveCategoryPair(
        localPayload: JsonObject,
        remotePayload: JsonObject,
        base: JsonObject?,
        local: MergeSide,
        remote: MergeSide,
        conflicts: MutableList<FieldConflict>,
    ): Map<String, JsonElement> {
        val localCategoryId = localPayload.getValue(CATEGORY_ID)
        val remoteCategoryId = remotePayload.getValue(CATEGORY_ID)
        val localLocked = (localPayload.getValue(CATEGORY_LOCKED) as JsonPrimitive).boolean
        val remoteLocked = (remotePayload.getValue(CATEGORY_LOCKED) as JsonPrimitive).boolean

        if (localCategoryId == remoteCategoryId && localLocked == remoteLocked) {
            return mapOf(CATEGORY_ID to localCategoryId, CATEGORY_LOCKED to JsonPrimitive(localLocked))
        }

        val baseCategoryId = base?.get(CATEGORY_ID)
        val baseLocked = (base?.get(CATEGORY_LOCKED) as? JsonPrimitive)?.boolean
        val localChanged = base == null || localCategoryId != baseCategoryId || localLocked != baseLocked
        val remoteChanged = base == null || remoteCategoryId != baseCategoryId || remoteLocked != baseLocked

        return when {
            // Only one side touched the pair -- e.g. B's rule engine ran
            // before B had synced A's hand-picked lock (§5.4's motivating
            // case). Fast-forward, same as any other untouched field.
            !localChanged -> mapOf(CATEGORY_ID to remoteCategoryId, CATEGORY_LOCKED to JsonPrimitive(remoteLocked))
            !remoteChanged -> mapOf(CATEGORY_ID to localCategoryId, CATEGORY_LOCKED to JsonPrimitive(localLocked))
            // A hand choice beats a rule's guess, unconditionally -- never
            // consult updated_at here, or a newer guess could beat an older
            // hand choice.
            localLocked && !remoteLocked -> mapOf(CATEGORY_ID to localCategoryId, CATEGORY_LOCKED to JsonPrimitive(true))
            remoteLocked && !localLocked -> mapOf(CATEGORY_ID to remoteCategoryId, CATEGORY_LOCKED to JsonPrimitive(true))
            // Both locked and different, or both unlocked and different: a
            // genuine tie, same tiebreak as everything else, and recorded.
            else -> {
                val reason = if (localLocked) {
                    "both devices locked a different category by hand"
                } else {
                    "two rule guesses disagreed on the category"
                }
                val chosenCategoryId = tiebreak(CATEGORY_ID, localCategoryId, remoteCategoryId, local, remote, conflicts, reason)
                mapOf(CATEGORY_ID to chosenCategoryId, CATEGORY_LOCKED to JsonPrimitive(localLocked))
            }
        }
    }

    private fun resolveField(
        field: String,
        policy: FieldResolution,
        localValue: JsonElement,
        remoteValue: JsonElement,
        base: JsonObject?,
        local: MergeSide,
        remote: MergeSide,
        conflicts: MutableList<FieldConflict>,
    ): JsonElement {
        if (localValue == remoteValue) return localValue

        val baseValue = base?.get(field)
        val localChanged = base == null || localValue != baseValue
        val remoteChanged = base == null || remoteValue != baseValue

        return when {
            !localChanged -> remoteValue
            !remoteChanged -> localValue
            policy == FieldResolution.MERGE -> resolveNotes(field, localValue, remoteValue, local, remote, conflicts)
            policy == FieldResolution.LOCAL_WINS -> localValue
            policy == FieldResolution.REMOTE_WINS -> remoteValue
            else -> tiebreak(field, localValue, remoteValue, local, remote, conflicts, reason = "both devices changed this field; kept the newer value")
        }
    }

    /**
     * §5.5's three-step rule. Step 1 (one side equals the base) never reaches
     * here -- [resolveField] already fast-forwards that case before consulting
     * a policy at all.
     */
    private fun resolveNotes(
        field: String,
        localValue: JsonElement,
        remoteValue: JsonElement,
        local: MergeSide,
        remote: MergeSide,
        conflicts: MutableList<FieldConflict>,
    ): JsonElement {
        val localText = (localValue as? JsonPrimitive)?.contentOrNull
        val remoteText = (remoteValue as? JsonPrimitive)?.contentOrNull

        return when {
            // §5.5 is written for two divergent texts; a cleared note on one
            // side isn't text to diff against, so it falls back to the same
            // tiebreak-and-record everything else gets rather than a rule
            // the design doc never states an answer for.
            localText == null || remoteText == null ->
                tiebreak(field, localValue, remoteValue, local, remote, conflicts, reason = "one device cleared the note while the other edited it; kept the newer version")
            remoteText.contains(localText) -> remoteValue
            localText.contains(remoteText) -> localValue
            else -> {
                val localIsNewer = local.updatedAt >= remote.updatedAt
                val newer = if (localIsNewer) localText else remoteText
                val older = if (localIsNewer) remoteText else localText
                val discarded = if (localIsNewer) remoteValue else localValue
                val merged = JsonPrimitive("$newer\n$NOTES_CONFLICT_MARKER\n$older")
                conflicts += FieldConflict(field, merged, discarded, reason = "both devices edited the note differently; kept both")
                merged
            }
        }
    }

    private fun tiebreak(
        field: String,
        localValue: JsonElement,
        remoteValue: JsonElement,
        local: MergeSide,
        remote: MergeSide,
        conflicts: MutableList<FieldConflict>,
        reason: String,
    ): JsonElement {
        val localWins = if (local.updatedAt != remote.updatedAt) {
            local.updatedAt > remote.updatedAt
        } else {
            local.deviceId < remote.deviceId
        }
        val chosen = if (localWins) localValue else remoteValue
        val discarded = if (localWins) remoteValue else localValue
        conflicts += FieldConflict(field, chosen, discarded, reason)
        return chosen
    }

    private companion object {
        const val CATEGORY_ID = "category_id"
        const val CATEGORY_LOCKED = "category_locked_by_user"
        const val NOTES_CONFLICT_MARKER = "--- synced from another device ---"
    }
}
