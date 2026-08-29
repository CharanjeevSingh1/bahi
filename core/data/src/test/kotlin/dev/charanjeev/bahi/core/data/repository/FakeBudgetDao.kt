package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.BudgetWithSpend
import dev.charanjeev.bahi.core.database.dao.RowRevision
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake, matching FakeTransactionDao. Every query's
 * `deleted_at IS NULL` is mirrored by hand here -- with no SQLite behind the
 * fake, forgetting one is how a test would pass while the real query
 * resurrects a tombstoned row.
 */
class FakeBudgetDao(
    /**
     * The spend query is a join, so the fake needs both sides. Sharing the
     * transaction fake rather than keeping a second transaction store here
     * means a repository test that writes a transaction sees it in the join,
     * which is the whole behaviour under test.
     */
    private val transactions: FakeTransactionDao = FakeTransactionDao(),
) : BudgetDao {

    private val backing = MutableStateFlow<Map<String, BudgetEntity>>(emptyMap())

    /** Tombstones included, unlike every query below -- tests assert on them. */
    fun allRows(): List<BudgetEntity> = backing.value.values.toList()

    override fun observeForMonth(yearMonth: String): Flow<List<BudgetEntity>> =
        backing.map { entities ->
            entities.values
                .filter { it.deletedAt == null && it.yearMonth == yearMonth }
                .sortedBy { it.categoryId }
        }

    /**
     * Hand-rolled aggregation is exactly what the production path must not
     * do -- here it is unavoidable, since there's no SQLite behind the fake.
     * That asymmetry is why BudgetDaoTest exists: this fake can only prove
     * the repository composes the two queries correctly, never that the SQL
     * means what it is supposed to mean. `LEFT JOIN` in particular has no
     * analogue here -- the fake maps over budgets, so a budget with no
     * matching transactions can't accidentally vanish the way it would if
     * the real query moved a join condition into its WHERE clause.
     */
    override fun observeBudgetsWithSpend(
        yearMonth: String,
        from: String,
        to: String,
    ): Flow<List<BudgetWithSpend>> =
        combine(backing, transactions.rows) { budgets, transactionRows ->
            budgets.values
                .filter { it.deletedAt == null && it.yearMonth == yearMonth }
                .sortedBy { it.categoryId }
                .map { budget ->
                    val spentMinor = transactionRows.values
                        .filter { it.deletedAt == null && it.amountMinor < 0 }
                        .filter { it.categoryId == budget.categoryId }
                        .filter { it.date >= from && it.date <= to }
                        .sumOf { -it.amountMinor }
                    BudgetWithSpend(budget, spentMinor)
                }
        }

    override suspend fun getById(id: String): BudgetEntity? =
        backing.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun findActive(categoryId: String, yearMonth: String): BudgetEntity? =
        backing.value.values.firstOrNull {
            it.categoryId == categoryId && it.yearMonth == yearMonth && it.deletedAt == null
        }

    /**
     * Reads through the tombstone, exactly as the real query does -- a fake
     * that filtered `deletedAt == null` here would hide the resurrection bug
     * this exists to fix and agree with whatever it was written to agree with.
     */
    override suspend fun revisionOf(id: String): RowRevision? =
        backing.value[id]?.let { RowRevision(it.localRevision, it.remoteRevision) }

    override suspend fun upsert(budget: BudgetEntity) {
        backing.value = backing.value + (budget.id to budget)
    }

    override suspend fun softDelete(id: String, deletedAt: Long) {
        val existing = backing.value[id]?.takeIf { it.deletedAt == null } ?: return
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = deletedAt,
                pendingOperation = "DELETE",
                localRevision = existing.localRevision + 1,
            )
        )
    }
}
