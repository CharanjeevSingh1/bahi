package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Query(
        """
        SELECT * FROM budgets
        WHERE deleted_at IS NULL AND year_month = :yearMonth
        ORDER BY category_id ASC
        """,
    )
    fun observeForMonth(yearMonth: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): BudgetEntity?

    /**
     * The natural key `budgets` has no UNIQUE index for: one budget per
     * category per month. `deleted_at IS NULL` is the load-bearing part --
     * a tombstoned budget still occupies the key, and treating it as an
     * occupant would resurrect a deleted row when the user creates a new
     * budget for the same category and month. The uniqueness rule this backs
     * is enforced in OfflineFirstBudgetRepository.upsert, not here; see
     * docs/budgets-design.md §4.1 for why it can't be a constraint.
     */
    @Query(
        """
        SELECT * FROM budgets
        WHERE category_id = :categoryId AND year_month = :yearMonth AND deleted_at IS NULL
        """,
    )
    suspend fun findActive(categoryId: String, yearMonth: String): BudgetEntity?

    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    /** Soft delete: sync needs the tombstone, same as every other table (rule 7). */
    @Query(
        """
        UPDATE budgets
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)
}
