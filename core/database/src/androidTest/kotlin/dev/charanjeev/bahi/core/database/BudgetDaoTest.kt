package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.BudgetWithSpend
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The budget-totals query, against real SQLite.
 *
 * This is the test that matters for slice 6, because the repository test that
 * covers the same behaviours runs against FakeBudgetDao, which aggregates by
 * hand and therefore agrees with whatever it was written to agree with. Only
 * this file can catch a LEFT JOIN that has quietly become an INNER one, or a
 * BETWEEN that is off by a day.
 *
 * What it deliberately does not assert: that Room re-delivers the flow when
 * `transactions` changes. That is Room's invalidation machinery, not this
 * query's behaviour, and asserting it needs a live collector racing a real
 * background executor. The property this file can hold is the one that
 * matters for correctness -- that the total is always recomputed from current
 * rows and never cached anywhere -- which
 * [observeBudgetsWithSpend_recomputesAfterATransactionIsRecategorised] covers
 * by re-reading.
 */
@RunWith(AndroidJUnit4::class)
class BudgetDaoTest {

    private lateinit var database: BahiDatabase
    private lateinit var dao: BudgetDao
    private lateinit var transactionDao: TransactionDao

    private val august = "2026-08"
    private val augustFrom = "2026-08-01"
    private val augustTo = "2026-08-31"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        dao = database.budgetDao()
        transactionDao = database.transactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * The LEFT JOIN test. Move any of the four join conditions into the WHERE
     * clause and this budget stops producing a row at all -- it disappears
     * off the screen instead of showing ₹0 of ₹8,000, which is the kind of
     * bug that looks like "the list is just empty" rather than like a
     * regression.
     */
    @Test
    fun observeBudgetsWithSpend_reportsZeroForABudgetWithNoTransactions_ratherThanOmittingIt() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))

        val rows = spendForAugust()

        assertThat(rows.map { it.budget.id }).containsExactly("b-food")
        assertThat(rows.single().spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_sumsExpensesInThatCategory_asAPositiveTotal() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food"))
        transactionDao.upsert(transactionEntity(id = "t2", amountMinor = -120_000, categoryId = "food"))

        // Positive: amount_minor is negative for an expense and the query
        // negates it, so "spent ₹1,650" needs no sign flip at the call site.
        assertThat(spendForAugust().single().spentMinor).isEqualTo(165_000L)
    }

    @Test
    fun observeBudgetsWithSpend_ignoresIncomeFiledUnderTheSameCategory() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(transactionEntity(id = "spend", amountMinor = -45_000, categoryId = "food"))
        // Without `t.amount_minor < 0` this credit nets off against the
        // expense and the budget reads ₹0 spent -- or worse, negative (§2.2).
        transactionDao.upsert(transactionEntity(id = "credit", amountMinor = 900_000, categoryId = "food"))

        assertThat(spendForAugust().single().spentMinor).isEqualTo(45_000L)
    }

    @Test
    fun observeBudgetsWithSpend_countsATransactionOnTheLastDayOfTheMonth() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        // BETWEEN is inclusive at both ends, and `date` is TEXT compared
        // lexicographically -- the 31st is in August, not in the gap between
        // months that an exclusive upper bound would create.
        transactionDao.upsert(
            transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food", date = "2026-08-31"),
        )

        assertThat(spendForAugust().single().spentMinor).isEqualTo(45_000L)
    }

    @Test
    fun observeBudgetsWithSpend_excludesATransactionOnTheFirstDayOfTheNextMonth() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(
            transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food", date = "2026-09-01"),
        )

        assertThat(spendForAugust().single().spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_excludesATransactionOnTheDayBeforeTheMonthStarts() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(
            transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food", date = "2026-07-31"),
        )

        assertThat(spendForAugust().single().spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_ignoresASoftDeletedTransaction() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food"))
        transactionDao.softDelete("t1", deletedAt = 1_000L)

        // The tombstone is still a row. Without `t.deleted_at IS NULL` in the
        // join, deleting a transaction leaves its money in the budget.
        assertThat(spendForAugust().single().spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_ignoresUncategorisedSpending() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        transactionDao.upsert(transactionEntity(id = "t1", amountMinor = -620_000, categoryId = null))

        // `t.category_id = b.category_id` is never true for a NULL, so this
        // can't leak into any budget. It is reported on its own line instead
        // -- see TransactionDaoTest's observeUncategorisedSpend cases.
        assertThat(spendForAugust().single().spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_keepsEachCategorysSpendToItsOwnBudget() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        dao.upsert(budgetEntity(id = "b-groceries", categoryId = "groceries"))
        transactionDao.upsert(transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food"))

        // Both budgets present, one with spend and one at zero -- a GROUP BY
        // that collapsed rows, or a join that spilled across categories,
        // shows up here rather than in a single-budget test.
        val byId = spendForAugust().associateBy { it.budget.id }
        assertThat(byId.keys).containsExactly("b-food", "b-groceries")
        assertThat(byId.getValue("b-food").spentMinor).isEqualTo(45_000L)
        assertThat(byId.getValue("b-groceries").spentMinor).isEqualTo(0L)
    }

    @Test
    fun observeBudgetsWithSpend_omitsABudgetForAnotherMonth() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-sep", categoryId = "food", yearMonth = "2026-09"))

        assertThat(spendForAugust()).isEmpty()
    }

    @Test
    fun observeBudgetsWithSpend_omitsASoftDeletedBudget() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        dao.softDelete("b-food", deletedAt = 1_000L)

        assertThat(spendForAugust()).isEmpty()
    }

    /**
     * §3: a rule moving a transaction between two categories moves its money
     * between two budgets in the same month, with no recompute step anywhere.
     * Re-reading the query is what proves the total is derived rather than
     * stored -- a cached aggregate would need invalidating at this call site
     * and at every other one that can write a `category_id`.
     */
    @Test
    fun observeBudgetsWithSpend_recomputesAfterATransactionIsRecategorised() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        dao.upsert(budgetEntity(id = "b-groceries", categoryId = "groceries"))
        transactionDao.upsert(transactionEntity(id = "t1", amountMinor = -45_000, categoryId = "food"))

        val before = spendForAugust().associateBy { it.budget.id }
        assertThat(before.getValue("b-food").spentMinor).isEqualTo(45_000L)
        assertThat(before.getValue("b-groceries").spentMinor).isEqualTo(0L)

        transactionDao.applyRuleCategory(id = "t1", categoryId = "groceries", updatedAt = 2_000L)

        val after = spendForAugust().associateBy { it.budget.id }
        assertThat(after.getValue("b-food").spentMinor).isEqualTo(0L)
        assertThat(after.getValue("b-groceries").spentMinor).isEqualTo(45_000L)
    }

    // --- dirtyRows: the shadow join (TransactionDaoTest covers it fully; §4.3) ---

    @Test
    fun dirtyRows_includesARowWithNoShadow() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("b-food")
    }

    @Test
    fun dirtyRows_excludesARowWhoseShadowMatchesItsLocalRevision() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        database.syncShadowDao().record(shadow(table = "budgets", rowId = "b-food", remoteRevision = 1))

        assertThat(dao.dirtyRows()).isEmpty()
    }

    /**
     * Each of the four dirtyRows queries hardcodes its own table name into the
     * join condition, copy-pasted from TransactionDao's. A shadow recorded
     * under a different table with the same row id would wrongly clear this
     * row if that literal were ever wrong.
     */
    @Test
    fun dirtyRows_isNotFooledByAShadowRecordedForAnotherTableWithTheSameRowId() = runTest {
        seedCategories()
        dao.upsert(budgetEntity(id = "b-food", categoryId = "food"))
        database.syncShadowDao().record(shadow(table = "transactions", rowId = "b-food", remoteRevision = 1))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("b-food")
    }

    private fun shadow(table: String, rowId: String, remoteRevision: Long) = SyncShadowEntity(
        tableName = table,
        rowId = rowId,
        remoteRevision = remoteRevision,
        payload = """{"notes":"a"}""",
    )

    private suspend fun spendForAugust(): List<BudgetWithSpend> =
        dao.observeBudgetsWithSpend(yearMonth = august, from = augustFrom, to = augustTo).first()

    /** Both tables carry a CASCADE foreign key to `categories`, so rows need one to exist. */
    private suspend fun seedCategories() {
        database.categoryDao().insertAllIgnoringConflicts(
            listOf(categoryEntity("food"), categoryEntity("groceries")),
        )
    }

    private fun categoryEntity(id: String) = CategoryEntity(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        parentId = null,
        colorArgb = 0,
        iconKey = "help_outline",
        isSystemDefined = true,
    )

    private fun budgetEntity(
        id: String,
        categoryId: String,
        yearMonth: String = august,
        limitMinor: Long = 800_000,
    ) = BudgetEntity(
        id = id,
        categoryId = categoryId,
        yearMonth = yearMonth,
        limitMinor = limitMinor,
        currencyCode = "INR",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun transactionEntity(
        id: String,
        amountMinor: Long,
        categoryId: String?,
        date: String = "2026-08-14",
    ) = TransactionEntity(
        id = id,
        amountMinor = amountMinor,
        currencyCode = "INR",
        date = date,
        description = "Coffee Shop",
        merchant = null,
        categoryId = categoryId,
        accountId = "acct-1",
        source = "CSV_IMPORT",
        notes = null,
        categoryLockedByUser = false,
        contentHash = id,
        importBatchId = null,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
