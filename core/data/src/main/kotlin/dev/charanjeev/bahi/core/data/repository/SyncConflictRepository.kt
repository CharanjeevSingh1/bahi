package dev.charanjeev.bahi.core.data.repository

import androidx.room.withTransaction
import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncConflict
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * What Settings' sync screen reads and acts on (docs/sync-design.md §5.6,
 * slice 8) -- the count, the list, and the one write path: restoring a
 * [SyncConflict.discardedValue] back onto its row.
 */
interface SyncConflictRepository {

    /** Unacknowledged conflicts, newest first -- what the list screen shows. */
    fun observeConflicts(): Flow<List<SyncConflict>>

    /** The count behind "N conflicts resolved -- review" on the Settings row. */
    fun observeUnacknowledgedCount(): Flow<Int>

    /** Marks a conflict seen without restoring it -- the screen's "dismiss". */
    suspend fun acknowledge(id: String)

    /** See [RestoreOutcome]. */
    suspend fun restore(id: String): RestoreOutcome
}

/**
 * What actually happened when the user asked to put a discarded value back.
 * Three ways this is *not* [RESTORED], and none of them fail silently --
 * §5.6's whole argument is that a merge rule only has to be reversible, and a
 * restore that quietly no-ops or overwrites something newer breaks that
 * promise as surely as never having recorded the value at all.
 */
enum class RestoreOutcome {
    RESTORED,

    /** The row no longer exists, or is tombstoned -- nothing to restore a field into. */
    ROW_GONE,

    /**
     * The field's live value no longer matches [SyncConflict.chosenValue]:
     * something has edited it again since this conflict resolved, on this
     * device or a synced one. Restoring anyway would silently overwrite that
     * newer value with one that is now at least two edits stale -- the same
     * cost `SyncConflictDao.record`'s doc already names for a superseded
     * conflict, just reached by a different path. Refused rather than risked.
     */
    VALUE_CHANGED_SINCE,

    /** The conflict itself is gone -- superseded or aged out under the caller. */
    NOT_FOUND,
}

class RoomSyncConflictRepository @Inject constructor(
    private val database: BahiDatabase,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : SyncConflictRepository {

    override fun observeConflicts(): Flow<List<SyncConflict>> =
        database.syncConflictDao().observeUnacknowledged().map { entities -> entities.map(::toDomain) }

    override fun observeUnacknowledgedCount(): Flow<Int> = database.syncConflictDao().observeUnacknowledgedCount()

    override suspend fun acknowledge(id: String) = withContext(ioDispatcher) {
        database.syncConflictDao().acknowledge(id, clock.now().toEpochMilliseconds())
        Unit
    }

    override suspend fun restore(id: String): RestoreOutcome = withContext(ioDispatcher) {
        // One transaction for the read-compare-write and the conflict's own
        // acknowledgement, same reasoning RoomSyncApplier.apply gives for its
        // own transaction boundary: a row that changed between the staleness
        // check and the write must not be silently overwritten by a write
        // that read stale state.
        database.withTransaction {
            val conflict = database.syncConflictDao().getById(id) ?: return@withTransaction RestoreOutcome.NOT_FOUND
            // table_name is only ever written by our own resolver (SyncApplier's
            // recordShadowAndConflicts), so a value SyncTable.of can't parse is a
            // bug in this build, not bad data to route around.
            val table = requireNotNull(SyncTable.of(conflict.tableName)) { "unknown sync table ${conflict.tableName}" }
            val chosen = Json.parseToJsonElement(conflict.chosenValue)
            val discarded = Json.parseToJsonElement(conflict.discardedValue)

            val outcome = when (table) {
                SyncTable.TRANSACTIONS -> restoreTransaction(conflict.rowId, conflict.field, chosen, discarded)
                SyncTable.CATEGORIES -> restoreCategory(conflict.rowId, conflict.field, chosen, discarded)
                SyncTable.BUDGETS -> restoreBudget(conflict.rowId, conflict.field, chosen, discarded)
                SyncTable.CATEGORY_RULES -> restoreCategoryRule(conflict.rowId, conflict.field, chosen, discarded)
            }

            // A restored value has been seen and acted on -- acknowledge rather
            // than delete, so it still ages out on the ordinary horizon sweep
            // (§7) instead of vanishing from the list with no trace it happened.
            if (outcome == RestoreOutcome.RESTORED) {
                database.syncConflictDao().acknowledge(id, clock.now().toEpochMilliseconds())
            }
            outcome
        }
    }

    private suspend fun restoreTransaction(rowId: String, field: String, chosen: JsonElement, discarded: JsonElement): RestoreOutcome {
        val existing = database.transactionDao().rowById(rowId) ?: return RestoreOutcome.ROW_GONE
        if (existing.deletedAt != null) return RestoreOutcome.ROW_GONE
        val current = toFieldMap(existing)
        if (current[field] != chosen) return RestoreOutcome.VALUE_CHANGED_SINCE
        val revision = database.transactionDao().revisionOf(rowId) ?: return RestoreOutcome.ROW_GONE
        database.transactionDao().upsert(
            transactionFromFieldMap(
                id = rowId,
                payload = current.patched(field, discarded),
                createdAt = existing.createdAt,
                updatedAt = clock.now().toEpochMilliseconds(),
                localRevision = revision.localRevision + 1,
                remoteRevision = revision.remoteRevision,
                pendingOperation = "UPSERT",
            ),
        )
        return RestoreOutcome.RESTORED
    }

    private suspend fun restoreCategory(rowId: String, field: String, chosen: JsonElement, discarded: JsonElement): RestoreOutcome {
        val existing = database.categoryDao().rowById(rowId) ?: return RestoreOutcome.ROW_GONE
        if (existing.deletedAt != null) return RestoreOutcome.ROW_GONE
        val current = toFieldMap(existing)
        if (current[field] != chosen) return RestoreOutcome.VALUE_CHANGED_SINCE
        val revision = database.categoryDao().revisionOf(rowId) ?: return RestoreOutcome.ROW_GONE
        database.categoryDao().upsertAll(
            listOf(
                categoryFromFieldMap(
                    id = rowId,
                    payload = current.patched(field, discarded),
                    updatedAt = clock.now().toEpochMilliseconds(),
                    localRevision = revision.localRevision + 1,
                    remoteRevision = revision.remoteRevision,
                    pendingOperation = "UPSERT",
                ),
            ),
        )
        return RestoreOutcome.RESTORED
    }

    private suspend fun restoreBudget(rowId: String, field: String, chosen: JsonElement, discarded: JsonElement): RestoreOutcome {
        val existing = database.budgetDao().rowById(rowId) ?: return RestoreOutcome.ROW_GONE
        if (existing.deletedAt != null) return RestoreOutcome.ROW_GONE
        val current = toFieldMap(existing)
        if (current[field] != chosen) return RestoreOutcome.VALUE_CHANGED_SINCE
        val revision = database.budgetDao().revisionOf(rowId) ?: return RestoreOutcome.ROW_GONE
        database.budgetDao().upsert(
            budgetFromFieldMap(
                id = rowId,
                payload = current.patched(field, discarded),
                createdAt = existing.createdAt,
                updatedAt = clock.now().toEpochMilliseconds(),
                localRevision = revision.localRevision + 1,
                remoteRevision = revision.remoteRevision,
                pendingOperation = "UPSERT",
            ),
        )
        return RestoreOutcome.RESTORED
    }

    private suspend fun restoreCategoryRule(rowId: String, field: String, chosen: JsonElement, discarded: JsonElement): RestoreOutcome {
        val existing = database.categoryRuleDao().rowById(rowId) ?: return RestoreOutcome.ROW_GONE
        if (existing.deletedAt != null) return RestoreOutcome.ROW_GONE
        val current = toFieldMap(existing)
        if (current[field] != chosen) return RestoreOutcome.VALUE_CHANGED_SINCE
        val revision = database.categoryRuleDao().revisionOf(rowId) ?: return RestoreOutcome.ROW_GONE
        database.categoryRuleDao().upsert(
            categoryRuleFromFieldMap(
                id = rowId,
                payload = current.patched(field, discarded),
                createdAt = existing.createdAt,
                updatedAt = clock.now().toEpochMilliseconds(),
                localRevision = revision.localRevision + 1,
                remoteRevision = revision.remoteRevision,
                pendingOperation = "UPSERT",
            ),
        )
        return RestoreOutcome.RESTORED
    }

    private fun toDomain(entity: SyncConflictEntity): SyncConflict = SyncConflict(
        id = entity.id,
        table = requireNotNull(SyncTable.of(entity.tableName)) { "unknown sync table ${entity.tableName}" },
        rowId = entity.rowId,
        field = entity.field,
        resolvedAt = Instant.fromEpochMilliseconds(entity.resolvedAt),
        chosenValue = Json.parseToJsonElement(entity.chosenValue).toConflictValue(),
        discardedValue = Json.parseToJsonElement(entity.discardedValue).toConflictValue(),
        reason = entity.reason,
        acknowledgedAt = entity.acknowledgedAt?.let(Instant::fromEpochMilliseconds),
    )
}

private fun JsonObject.patched(field: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().apply { put(field, value) })

private fun JsonElement.toConflictValue(): ConflictValue = when {
    this is JsonNull -> ConflictValue.None
    this is JsonPrimitive && isString -> ConflictValue.Text(content)
    this is JsonPrimitive && booleanOrNull != null -> ConflictValue.Flag(boolean)
    this is JsonPrimitive && longOrNull != null -> ConflictValue.Number(long)
    else -> ConflictValue.Text(toString())
}
