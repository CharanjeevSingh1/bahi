package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE deleted_at IS NULL ORDER BY name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): CategoryEntity?

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    /** See [RowRevision]: no `deleted_at` condition, deliberately. */
    @Query("SELECT local_revision, remote_revision FROM categories WHERE id = :id")
    suspend fun revisionOf(id: String): RowRevision?

    /**
     * Soft delete: sync needs the tombstone, same as every other table (rule
     * 7). `categories` was the last table still hard-deleting, which nothing
     * had caught because nothing needed the tombstone until sync did -- a hard
     * delete leaves nothing to push, so the other device pushes its still-live
     * copy back and the category returns silently (docs/sync-design.md §1.2).
     *
     * `is_system_defined = 0` is the same guard the hard delete carried,
     * moved onto the UPDATE unchanged in meaning. It has to stay: a
     * tombstoned system category would be invisible to [observeAll] *and*
     * un-reseedable, because [insertAllIgnoringConflicts] still sees the row
     * and still ignores the conflict.
     */
    @Query(
        """
        UPDATE categories
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id AND is_system_defined = 0 AND deleted_at IS NULL
        """,
    )
    suspend fun tombstoneUserCategory(id: String, deletedAt: Long): Int

    /**
     * What `ON DELETE CASCADE` on `budgets.category_id` used to do. A soft
     * delete never fires a foreign key, so deleting a category would leave its
     * budgets alive and nameless on the budgets screen -- and, worse under
     * sync, with no tombstone of their own to propagate.
     */
    @Query(
        """
        UPDATE budgets
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE category_id = :categoryId AND deleted_at IS NULL
        """,
    )
    suspend fun tombstoneBudgetsOf(categoryId: String, deletedAt: Long)

    /** The same, for `category_rules.category_id`. See [tombstoneBudgetsOf]. */
    @Query(
        """
        UPDATE category_rules
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE category_id = :categoryId AND deleted_at IS NULL
        """,
    )
    suspend fun tombstoneRulesOf(categoryId: String, deletedAt: Long)

    /**
     * Deleting a category, cascade included.
     *
     * Two queries here read tables this DAO does not own, which
     * `observeUncategorisedSpend`'s comment on TransactionDao says is normally
     * the repository's job to compose. The exception is deliberate: this
     * replaces a foreign-key cascade, and the property that made that cascade
     * trustworthy was that it could not half-happen. Composed across three
     * DAOs in a repository it would be three separate transactions, and a
     * crash between them leaves budgets and rules pointing at a category that
     * is gone. `@Transaction` buys back exactly what `ON DELETE CASCADE` gave
     * away.
     *
     * The cascade runs whether or not the category was deletable -- a system
     * category's [tombstoneUserCategory] is a no-op, so the guard is checked
     * first and the rest is skipped.
     *
     * Transactions are deliberately *not* touched. `ON DELETE SET_NULL` would
     * have blanked their `category_id`; this leaves it pointing at the
     * tombstone, which keeps the categorisation recoverable if the category
     * comes back and turns one delete into one write instead of hundreds --
     * each of which would otherwise be its own sync operation
     * (docs/sync-design.md §1.2). The cost is that a transaction can point at
     * a category no live row matches, which
     * [TransactionDao.observeUncategorisedSpend] has to count as uncategorised.
     */
    @Transaction
    suspend fun softDeleteUserCategory(id: String, deletedAt: Long) {
        if (tombstoneUserCategory(id, deletedAt) == 0) return
        tombstoneBudgetsOf(id, deletedAt)
        tombstoneRulesOf(id, deletedAt)
    }

    /**
     * Seeding uses this instead of [upsertAll]: ignoring a conflict on the
     * fixed system-category ids is what makes reseeding a no-op for any
     * category the user has already renamed or recoloured.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(categories: List<CategoryEntity>): List<Long>

    /** See [TransactionDao.dirtyRows]: derived from the shadow, not `pending_operation`. */
    @Query(
        """
        SELECT c.* FROM categories c
        LEFT JOIN sync_shadow s ON s.table_name = 'categories' AND s.row_id = c.id
        WHERE c.local_revision > COALESCE(s.remote_revision, 0)
        ORDER BY c.id ASC
        LIMIT :limit
        """,
    )
    suspend fun dirtyRows(limit: Int = 200): List<CategoryEntity>

    /** See [TransactionDao.markSynced]: guarded so a push acknowledgement can't clear a newer edit. */
    @Query(
        """
        UPDATE categories
        SET pending_operation = NULL, remote_revision = :remoteRevision
        WHERE id = :id AND local_revision = :expectedLocalRevision
        """,
    )
    suspend fun markSynced(id: String, remoteRevision: Long, expectedLocalRevision: Long): Int
}
