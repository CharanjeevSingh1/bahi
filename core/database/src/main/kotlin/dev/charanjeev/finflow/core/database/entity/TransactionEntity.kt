package dev.charanjeev.finflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("category_id"),
        // Composite index: the transaction list is always ordered by date within
        // an account, so this is the index that actually gets used.
        Index(value = ["account_id", "date"]),
        // Import de-duplication looks up by content hash.
        Index(value = ["content_hash"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "currency_code") val currencyCode: String,
    /** ISO-8601 date, stored as TEXT so it sorts lexicographically. */
    @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "merchant") val merchant: String?,
    @ColumnInfo(name = "category_id") val categoryId: String?,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "category_locked_by_user") val categoryLockedByUser: Boolean,
    /**
     * Stable hash of (date, amount, description, account). Re-importing the same
     * statement must not create duplicates, and bank CSVs rarely carry a usable
     * transaction id of their own.
     */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    // --- sync bookkeeping ---
    @ColumnInfo(name = "local_revision") val localRevision: Long = 1,
    @ColumnInfo(name = "remote_revision") val remoteRevision: Long? = null,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)
