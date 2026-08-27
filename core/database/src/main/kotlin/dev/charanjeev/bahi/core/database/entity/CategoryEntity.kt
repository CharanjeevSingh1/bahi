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
