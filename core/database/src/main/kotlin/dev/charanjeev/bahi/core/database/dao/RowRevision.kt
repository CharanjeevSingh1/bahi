package dev.charanjeev.bahi.core.database.dao

import androidx.room.ColumnInfo

/**
 * A row's sync revision counters, and nothing else.
 *
 * Every repository's `upsert` has to read the stored revision before writing:
 * `local_revision` counts local edits and must never go backwards, and
 * `remote_revision` records what the remote last acknowledged and must never
 * be invented. They used to be read off the row a `getById` returned, which
 * filters `deleted_at IS NULL` -- so upserting an id that was tombstoned found
 * nothing, restarted the count at 1 and dropped the remote's acknowledgement,
 * producing a resurrected row that claims to be brand new
 * (docs/sync-design.md §4.3).
 *
 * A revision is a fact about the row, not about whether the row is currently
 * visible, so the queries backing this deliberately have no `deleted_at`
 * condition. That is also why it is a narrow projection rather than a second
 * "get including deleted": it cannot be mistaken for something to display,
 * and a caller that wanted a tombstoned row's *contents* still has to say so.
 */
data class RowRevision(
    @ColumnInfo(name = "local_revision") val localRevision: Long,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long?,
)
