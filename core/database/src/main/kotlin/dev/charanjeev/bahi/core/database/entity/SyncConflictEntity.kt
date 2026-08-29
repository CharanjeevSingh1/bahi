package dev.charanjeev.bahi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One field, on one row, where both devices changed it away from the same base
 * and a policy had to pick (docs/sync-design.md §5.6).
 *
 * Only the fourth row of §5.2's table lands here. A fast-forward is not a
 * conflict and is not recorded: recording those would produce noise
 * proportional to sync volume, and a list that grows with every sync is a list
 * the user learns to ignore.
 *
 * [discardedValue] is what the table is for. No merge rule has to be *right*
 * if the value it discarded is still reachable -- which is what makes the
 * arbitrary tiebreaks in §5.5 acceptable, and why the recording had to ship in
 * M4a even though the screen that reads it is slice 8. A discarded value
 * cannot be reconstructed afterwards; a screen can be built at any time.
 *
 * [field] is a database column name, matching the payload keys in
 * [dev.charanjeev.bahi.core.model.SyncOp]. The screen maps it to a label; the
 * resolver would otherwise have to translate in the one place a translation
 * can silently stop matching the thing it names.
 */
@Entity(
    tableName = "sync_conflicts",
    indices = [
        // Both the supersede check on write and the "conflicts on this row"
        // read start from (table_name, row_id), so one composite index serves
        // both. A lone index on row_id, which §9 originally proposed, would
        // not: row_id is not a prefix of this one, and no query asks for a row
        // id without knowing which table it belongs to.
        Index(value = ["table_name", "row_id", "field"]),
        Index("acknowledged_at"),
    ],
)
data class SyncConflictEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    @ColumnInfo(name = "field") val field: String,
    @ColumnInfo(name = "resolved_at") val resolvedAt: Long,
    /** JSON, the value that won. */
    @ColumnInfo(name = "chosen_value") val chosenValue: String,
    /** JSON, the value that lost, and the only copy of it that exists. */
    @ColumnInfo(name = "discarded_value") val discardedValue: String,
    /** Which rule fired, so the list can say why and not just what. */
    @ColumnInfo(name = "reason") val reason: String,
    /**
     * Null until the user has seen it. Also the clock the horizon sweep reads:
     * an acknowledged conflict ages out 90 days after acknowledgement, an
     * unacknowledged one never ages out at all (§7).
     */
    @ColumnInfo(name = "acknowledged_at") val acknowledgedAt: Long? = null,
)
