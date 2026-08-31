package dev.charanjeev.bahi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index("parent_id")],
)
data class CategoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    @ColumnInfo(name = "is_system_defined") val isSystemDefined: Boolean,
    /**
     * Added in v7, after every other synced table already had one
     * (docs/sync-design.md §4.3's category gap). No `created_at`: nothing
     * needs it, and adding one nobody reads would be a second unused column
     * the next reader has to explain. `updated_at` is not optional the same
     * way -- it is [dev.charanjeev.bahi.core.model.SyncOp.updatedAt] and the
     * resolver's tiebreak (§5.5) both need a real per-row timestamp, and
     * `local_revision` cannot stand in for one: it is a per-device counter,
     * not a comparable wall-clock value.
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    // --- sync bookkeeping ---
    // Added in v4, after every other table already had these. `categories`
    // was the last table with a hard delete and no tombstone, which made it
    // the one table transaction sync could not carry: transactions.category_id
    // is a foreign key into it, so a category that only exists on one device
    // is a transaction that cannot be inserted on the other
    // (docs/sync-design.md §1.2).
    @ColumnInfo(name = "local_revision") val localRevision: Long = 1,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long? = null,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)
