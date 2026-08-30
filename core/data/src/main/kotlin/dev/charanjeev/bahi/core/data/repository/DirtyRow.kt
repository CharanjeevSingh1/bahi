package dev.charanjeev.bahi.core.data.repository

import kotlinx.serialization.json.JsonObject

/**
 * One row a repository's `dirtyRows` found unsynced (docs/sync-design.md
 * §4.3): its own revision counter, its own `updated_at`, and its fields as
 * [toFieldMap] already renders them for the shadow -- exactly what
 * `:core:sync` needs to build the [dev.charanjeev.bahi.core.model.SyncOp] it
 * pushes, and nothing that would require handing back the entity underneath
 * (CLAUDE.md rule 3).
 *
 * [payload] is null for a tombstone, matching [dev.charanjeev.bahi.core.model.SyncOp.payload].
 */
data class DirtyRow(
    val rowId: String,
    val localRevision: Long,
    val updatedAt: Long,
    val payload: JsonObject?,
)
