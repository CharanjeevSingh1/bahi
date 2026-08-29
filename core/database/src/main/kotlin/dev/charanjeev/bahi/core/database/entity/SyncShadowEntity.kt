package dev.charanjeev.bahi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * The last state this device and the remote agreed on, for one row
 * (docs/sync-design.md §4.1). The merge base: what makes "I changed this
 * field" distinguishable from "you did", which is the question two-way
 * resolution cannot answer and the reason the M0 sketch was wrong (§5.1).
 *
 * Written at exactly two moments, both of them "we now agree": after a remote
 * op is applied, and after a push is acknowledged. Never by a user write path
 * -- that is the property this table is bought with, and the reason it beat
 * per-field dirty flags, which would have made every present and future write
 * path responsible for remembering something (§4.1, option ii).
 *
 * [remoteRevision] is the revision the payload describes, and must equal the
 * data row's `remote_revision`. Room cannot express that: the parent table is
 * named by a *column*, and SQLite has no polymorphic foreign key. So the
 * invariant lives in the engine writing both in one transaction, and
 * `remoteRevision` is a parameter of every write here rather than something
 * inferred, so that drifting from the row requires saying so.
 *
 * A null [payload] is a tombstone -- the row was deleted as of
 * [remoteRevision]. It is not "no base": no base is the absence of a row here,
 * and the two mean opposite things. Without the distinction a device that
 * pulled a deletion and then revived the row locally could not tell its
 * revival from a row the remote had never deleted.
 *
 * No sync bookkeeping columns, unlike every other table in this database. This
 * is a local record *about* sync; syncing it would be circular. Same for
 * [SyncConflictEntity].
 */
@Entity(
    tableName = "sync_shadow",
    primaryKeys = ["table_name", "row_id"],
)
data class SyncShadowEntity(
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long,
    /**
     * The row's synced fields as a JSON object, keyed by column name -- the
     * same shape as [dev.charanjeev.bahi.core.model.SyncOp.payload], because
     * the resolver compares one against the other.
     *
     * TEXT rather than a converted type: what the bytes mean is `:core:data`'s
     * business, and a TypeConverter would put JSON parsing on every read of
     * this database whether or not anything wanted it parsed.
     */
    @ColumnInfo(name = "payload") val payload: String?,
)
