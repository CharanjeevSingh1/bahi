package dev.charanjeev.bahi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An auto-categorisation rule: "description contains X -> category Y"
 * (docs/budgets-design.md §1.1).
 *
 * CASCADE, not SET_NULL as transactions use for the same foreign key: a
 * transaction survives its category being deleted by falling back to
 * uncategorised, but a rule *is* its category relationship -- one pointing at
 * a category that no longer exists isn't degraded, it's meaningless.
 */
@Entity(
    tableName = "category_rules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("category_id")],
)
data class CategoryRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    /** Matched case-insensitively against the transaction's description. */
    @ColumnInfo(name = "merchant_contains") val merchantContains: String,
    /**
     * Ascending: the lowest-priority value that matches wins and evaluation
     * stops there. Rules never combine, so two rules matching the same
     * transaction is resolved rather than surfaced as ambiguity
     * (docs/budgets-design.md §1.5).
     */
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // --- sync bookkeeping ---
    // Present before :core:sync exists on purpose: rule 7 has no "not synced
    // yet" exemption, and adding these to a table that already holds user data
    // costs strictly more than creating the table with them.
    @ColumnInfo(name = "local_revision") val localRevision: Long = 1,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long? = null,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)
