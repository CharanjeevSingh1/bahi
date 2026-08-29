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

    /**
     * Every budget for [yearMonth] with what has been spent against it,
     * aggregated by SQLite. Not a list of budgets the caller then folds
     * transactions into: §3 of docs/budgets-design.md depends on this being
     * one query, because Room's Flow invalidation watches the tables the
     * query touches -- `transactions` included -- so a rule, an import or a
     * hand edit changing a `category_id` re-emits every affected budget with
     * no invalidation step for anyone to forget.
     *
     * **The four join conditions are in `ON`, not `WHERE`, and that is
     * load-bearing.** Moving any of them into the WHERE clause turns the
     * LEFT JOIN back into an INNER one: a budget with no matching
     * transactions this month would produce no row at all and vanish off the
     * screen, instead of showing ₹0 of its limit. Same instinct as
     * `observeFiltered` writing its optional conditions as OR-with-a-flag
     * rather than omitting them.
     *
     * [from] and [to] are resolved by the caller from [yearMonth] (§2.3):
     * this DAO is never handed "this month" as a concept and never converts
     * through an Instant or a device time zone to get there. They look
     * redundant with [yearMonth] but aren't -- `year_month` picks which
     * budgets are in play, and `transactions` has no `year_month` column, so
     * BETWEEN is what actually scopes the sum.
     *
     * COALESCE because SUM over zero rows is NULL, and "nothing spent" has
     * to arrive as 0 rather than as a null every caller must remember.
     *
     * No currency condition on the join: every writer in the app produces one
     * currency today (`DEFAULT_CURRENCY_CODE`), and adding `t.currency_code =
     * b.currency_code` before there is a second one would silently drop spend
     * from a budget rather than surface the mismatch. Multi-currency needs a
     * conversion decision first, not a filter here.
     */
    @Query(
        """
        SELECT b.*, COALESCE(SUM(-t.amount_minor), 0) AS spent_minor
        FROM budgets b
        LEFT JOIN transactions t
          ON t.category_id = b.category_id
         AND t.deleted_at IS NULL
         AND t.amount_minor < 0
         AND t.date BETWEEN :from AND :to
        WHERE b.deleted_at IS NULL AND b.year_month = :yearMonth
        GROUP BY b.id
        ORDER BY b.category_id ASC
        """,
    )
    fun observeBudgetsWithSpend(yearMonth: String, from: String, to: String): Flow<List<BudgetWithSpend>>

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

    /** See [RowRevision]: no `deleted_at` condition, deliberately. */
    @Query("SELECT local_revision, remote_revision FROM budgets WHERE id = :id")
    suspend fun revisionOf(id: String): RowRevision?

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
