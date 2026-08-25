package dev.charanjeev.bahi.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spending limit for one category in one calendar month
 * (docs/budgets-design.md §2.1).
 *
 * [categoryId] is deliberately non-null: a nullable "overall budget" would
 * break the one-per-category-per-month invariant silently, since SQL treats
 * every NULL as distinct in a UNIQUE index. If an overall budget is ever
 * wanted, it gets a real sentinel category id, not a null one (§2.1, D3).
 *
 * CASCADE for the same reason as CategoryRuleEntity: a budget for a deleted
 * category is meaningless rather than degraded.
 */
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // No UNIQUE(category_id, year_month), even though that is a real
    // invariant: a tombstoned budget still occupies the key, so re-creating
    // one the user deleted would fail a constraint they cannot see the cause
    // of. SQLite can express `UNIQUE ... WHERE deleted_at IS NULL`, but
    // @Entity(indices) cannot declare a partial index. The invariant lives in
    // the repository instead (§4.1) -- same place OfflineFirstCategoryRepository
    // keeps its own.
    indices = [
        Index("category_id"),
        Index("year_month"),
    ],
)
data class BudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    /** "2026-08". TEXT so it sorts lexicographically, same as `transactions.date`. */
    @ColumnInfo(name = "year_month") val yearMonth: String,
    @ColumnInfo(name = "limit_minor") val limitMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // --- sync bookkeeping ---
    @ColumnInfo(name = "local_revision") val localRevision: Long = 1,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long? = null,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)
