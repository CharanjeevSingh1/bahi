package dev.charanjeev.finflow.core.database.entity

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
)
