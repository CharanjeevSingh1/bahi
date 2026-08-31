package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.BudgetDao
import dev.charanjeev.bahi.core.database.dao.CategoryDao
import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The category soft delete and its cascade, against real SQLite.
 *
 * The repository test for the same behaviours runs against FakeCategoryDao,
 * which records that the cascade was driven but has no budgets or rules for it
 * to land on. Only this file can catch the cascade writing to the wrong rows,
 * the system-category guard being lost, or `@Transaction` not covering all
 * three statements.
 */
@RunWith(AndroidJUnit4::class)
class CategoryDaoTest {

    private lateinit var database: BahiDatabase
    private lateinit var dao: CategoryDao
    private lateinit var budgetDao: BudgetDao
    private lateinit var ruleDao: CategoryRuleDao
    private lateinit var transactionDao: TransactionDao

    private val deletedAt = 1_700L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        dao = database.categoryDao()
        budgetDao = database.budgetDao()
        ruleDao = database.categoryRuleDao()
        transactionDao = database.transactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun softDeleteUserCategory_keepsTheRowAndMarksItPendingDelete() = runTest {
        dao.upsertAll(listOf(category("user-hobbies", systemDefined = false)))

        dao.softDeleteUserCategory("user-hobbies", deletedAt)

        assertThat(dao.observeAll().first()).isEmpty()
        assertThat(dao.getById("user-hobbies")).isNull()
        // The row is still there, which is the entire point -- a hard delete
        // leaves nothing to push and the other device restores the category.
        val stored = rawCategory("user-hobbies")
        assertThat(stored.deletedAt).isEqualTo(deletedAt)
        assertThat(stored.pendingOperation).isEqualTo("DELETE")
        assertThat(stored.localRevision).isEqualTo(2)
    }

    @Test
    fun softDeleteUserCategory_tombstonesItsBudgetsAndRules() = runTest {
        dao.upsertAll(listOf(category("food", systemDefined = false), category("rent", systemDefined = false)))
        budgetDao.upsert(budget("budget-food", "food"))
        budgetDao.upsert(budget("budget-rent", "rent"))
        ruleDao.upsert(rule("rule-food", "food"))
        ruleDao.upsert(rule("rule-rent", "rent"))

        dao.softDeleteUserCategory("food", deletedAt)

        assertThat(budgetDao.observeForMonth("2026-08").first().map { it.id })
            .containsExactly("budget-rent")
        assertThat(ruleDao.observeAll().first().map { it.id }).containsExactly("rule-rent")
    }

    @Test
    fun softDeleteUserCategory_marksTheCascadedRowsPendingDeleteToo() = runTest {
        // The cascade exists for sync, not just for the screen: a budget that
        // vanished locally with no tombstone is a budget the other device
        // pushes straight back.
        dao.upsertAll(listOf(category("food", systemDefined = false)))
        budgetDao.upsert(budget("budget-food", "food"))

        dao.softDeleteUserCategory("food", deletedAt)

        database.query("SELECT deleted_at, pending_operation FROM budgets WHERE id = 'budget-food'", null)
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(deletedAt)
                assertThat(cursor.getString(1)).isEqualTo("DELETE")
            }
    }

    @Test
    fun softDeleteUserCategory_isANoOpForASystemCategory_cascadeIncluded() = runTest {
        dao.upsertAll(listOf(category("food", systemDefined = true)))
        budgetDao.upsert(budget("budget-food", "food"))

        dao.softDeleteUserCategory("food", deletedAt)

        assertThat(dao.getById("food")).isNotNull()
        // The guard is checked before the cascade runs. Without that ordering,
        // "delete" on a system category would silently wipe its budgets while
        // leaving the category itself on screen.
        assertThat(budgetDao.observeForMonth("2026-08").first().map { it.id })
            .containsExactly("budget-food")
    }

    @Test
    fun softDeleteUserCategory_leavesTransactionsPointingAtTheTombstone() = runTest {
        // ON DELETE SET_NULL does not fire for a soft delete, and that is the
        // behaviour this design wants: the categorisation survives, so
        // undeleting the category restores it, and one delete stays one write
        // rather than becoming one per transaction.
        dao.upsertAll(listOf(category("food", systemDefined = false)))
        transactionDao.upsert(transaction("txn-1", categoryId = "food"))

        dao.softDeleteUserCategory("food", deletedAt)

        assertThat(transactionDao.observeById("txn-1").first()?.categoryId).isEqualTo("food")
    }

    @Test
    fun observeUncategorisedSpend_countsATransactionWhoseCategoryWasDeleted() = runTest {
        // Its budget was tombstoned by the same cascade, so if this query did
        // not pick the transaction up the spend would be on no line of the
        // budgets screen at all.
        dao.upsertAll(listOf(category("food", systemDefined = false)))
        budgetDao.upsert(budget("budget-food", "food"))
        transactionDao.upsert(transaction("txn-1", categoryId = "food"))

        val before = transactionDao.observeUncategorisedSpend("2026-08-01", "2026-08-31").first()
        dao.softDeleteUserCategory("food", deletedAt)
        val after = transactionDao.observeUncategorisedSpend("2026-08-01", "2026-08-31").first()

        assertThat(before).isEqualTo(0)
        assertThat(after).isEqualTo(45_000)
    }

    @Test
    fun insertAllIgnoringConflicts_doesNotResurrectATombstonedCategory() = runTest {
        // Reseeding must stay a no-op for a row that exists, and a tombstone is
        // a row that exists. This is only safe because the system-category
        // guard means a seeded id can never be tombstoned in the first place --
        // if that guard were dropped, a deleted system category would be
        // permanently unreachable rather than reseeded.
        dao.upsertAll(listOf(category("user-hobbies", systemDefined = false)))
        dao.softDeleteUserCategory("user-hobbies", deletedAt)

        dao.insertAllIgnoringConflicts(listOf(category("user-hobbies", systemDefined = false)))

        assertThat(dao.observeAll().first()).isEmpty()
    }

    // --- dirtyRows: the shadow join (TransactionDaoTest covers it fully; §4.3) ---

    @Test
    fun dirtyRows_includesARowWithNoShadow() = runTest {
        dao.upsertAll(listOf(category("food", systemDefined = false)))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("food")
    }

    @Test
    fun dirtyRows_excludesARowWhoseShadowMatchesItsLocalRevision() = runTest {
        dao.upsertAll(listOf(category("food", systemDefined = false)))
        database.syncShadowDao().record(shadow(table = "categories", rowId = "food", remoteRevision = 1))

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
        dao.upsertAll(listOf(category("food", systemDefined = false)))
        database.syncShadowDao().record(shadow(table = "budgets", rowId = "food", remoteRevision = 1))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("food")
    }

    private fun shadow(table: String, rowId: String, remoteRevision: Long) = SyncShadowEntity(
        tableName = table,
        rowId = rowId,
        remoteRevision = remoteRevision,
        payload = """{"notes":"a"}""",
    )

    private suspend fun rawCategory(id: String): CategoryEntity =
        database.query("SELECT * FROM categories WHERE id = ?", arrayOf(id)).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            CategoryEntity(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                parentId = null,
                colorArgb = cursor.getInt(cursor.getColumnIndexOrThrow("color_argb")),
                iconKey = cursor.getString(cursor.getColumnIndexOrThrow("icon_key")),
                isSystemDefined = cursor.getInt(cursor.getColumnIndexOrThrow("is_system_defined")) == 1,
                localRevision = cursor.getLong(cursor.getColumnIndexOrThrow("local_revision")),
                remoteRevision = null,
                pendingOperation = cursor.getString(cursor.getColumnIndexOrThrow("pending_operation")),
                deletedAt = cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")),
            )
        }

    private fun category(id: String, systemDefined: Boolean) = CategoryEntity(
        id = id,
        name = id,
        parentId = null,
        colorArgb = 0,
        iconKey = "icon",
        isSystemDefined = systemDefined,
    )

    private fun budget(id: String, categoryId: String) = BudgetEntity(
        id = id,
        categoryId = categoryId,
        yearMonth = "2026-08",
        limitMinor = 800_000,
        currencyCode = "INR",
        createdAt = 0,
        updatedAt = 0,
    )

    private fun rule(id: String, categoryId: String) = CategoryRuleEntity(
        id = id,
        categoryId = categoryId,
        merchantContains = "SWIGGY",
        priority = 0,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun transaction(id: String, categoryId: String?) = TransactionEntity(
        id = id,
        amountMinor = -45_000,
        currencyCode = "INR",
        date = "2026-08-14",
        description = "Coffee Shop",
        merchant = null,
        categoryId = categoryId,
        accountId = "acct-1",
        source = "MANUAL",
        notes = null,
        categoryLockedByUser = false,
        contentHash = "hash-$id",
        createdAt = 0,
        updatedAt = 0,
    )
}
