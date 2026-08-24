package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryRuleDao {

    /**
     * Ordered the way rules are evaluated (docs/budgets-design.md §1.5), so
     * the matching engine can take the first hit rather than re-sorting, and
     * the management screen shows them in the order they actually apply. `id`
     * breaks a priority tie so the order is total rather than merely
     * consistent-per-query.
     */
    @Query(
        """
        SELECT * FROM category_rules
        WHERE deleted_at IS NULL
        ORDER BY priority ASC, id ASC
        """,
    )
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): CategoryRuleEntity?

    @Upsert
    suspend fun upsert(rule: CategoryRuleEntity)

    /** Soft delete: sync needs the tombstone, same as every other table (rule 7). */
    @Query(
        """
        UPDATE category_rules
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id AND deleted_at IS NULL
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)
}
