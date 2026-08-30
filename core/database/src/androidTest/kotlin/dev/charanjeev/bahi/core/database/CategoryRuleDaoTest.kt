package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reorder, against real SQLite. Priority is what decides which of two
 * matching rules wins (docs/budgets-design.md §1.5), so getting the stored
 * values wrong doesn't error -- it silently files transactions under the
 * wrong category.
 */
@RunWith(AndroidJUnit4::class)
class CategoryRuleDaoTest {

    private lateinit var database: BahiDatabase
    private lateinit var dao: CategoryRuleDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        dao = database.categoryRuleDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reorder_assignsPriorityByPositionInTheGivenOrder() = runTest {
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 0))
        dao.upsert(ruleEntity(id = "b", priority = 1))
        dao.upsert(ruleEntity(id = "c", priority = 2))

        dao.reorder(listOf("c", "a", "b"), updatedAt = 5_000L)

        // getAll orders by priority, so this is the evaluation order the
        // matching engine will actually use -- not just the stored numbers.
        assertThat(dao.getAll().map(CategoryRuleEntity::id)).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun reorder_leavesPrioritiesDenseAndZeroBased() = runTest {
        // Sparse or ever-growing priorities would eventually collide and be
        // resolved by the id tie-break rather than by what the user chose.
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 40))
        dao.upsert(ruleEntity(id = "b", priority = 90))

        dao.reorder(listOf("b", "a"), updatedAt = 5_000L)

        assertThat(dao.getAll().map(CategoryRuleEntity::priority)).containsExactly(0, 1).inOrder()
    }

    @Test
    fun reorder_marksEachMovedRulePendingSync() = runTest {
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 0))

        dao.reorder(listOf("a"), updatedAt = 5_000L)

        val row = dao.getAll().single()
        assertThat(row.localRevision).isEqualTo(2)
        assertThat(row.pendingOperation).isEqualTo("UPSERT")
        assertThat(row.updatedAt).isEqualTo(5_000L)
    }

    @Test
    fun reorder_leavesTheRulesOwnFieldsAlone() = runTest {
        // Only the priority column is written, so a reorder can't restate a
        // merchant string or category from a stale copy the screen was holding.
        seedCategories()
        dao.upsert(ruleEntity(id = "a", merchantContains = "SWIGGY", categoryId = "food", priority = 0))

        dao.reorder(listOf("a"), updatedAt = 5_000L)

        val row = dao.getAll().single()
        assertThat(row.merchantContains).isEqualTo("SWIGGY")
        assertThat(row.categoryId).isEqualTo("food")
    }

    @Test
    fun reorder_ignoresATombstonedRule() = runTest {
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 0))
        dao.softDelete("a", deletedAt = 1_000L)

        dao.reorder(listOf("a"), updatedAt = 5_000L)

        // The tombstone keeps its DELETE -- a reorder must not resurrect it
        // as a pending UPSERT, which would push the rule back on next sync.
        assertThat(dao.getAll()).isEmpty()
    }

    // --- dirtyRows: the shadow join (TransactionDaoTest covers it fully; §4.3) ---

    @Test
    fun dirtyRows_includesARowWithNoShadow() = runTest {
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 0))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("a")
    }

    @Test
    fun dirtyRows_excludesARowWhoseShadowMatchesItsLocalRevision() = runTest {
        seedCategories()
        dao.upsert(ruleEntity(id = "a", priority = 0))
        database.syncShadowDao().record(shadow(table = "category_rules", rowId = "a", remoteRevision = 1))

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
        dao.upsert(ruleEntity(id = "a", priority = 0))
        database.syncShadowDao().record(shadow(table = "categories", rowId = "a", remoteRevision = 1))

        assertThat(dao.dirtyRows().map { it.id }).containsExactly("a")
    }

    private fun shadow(table: String, rowId: String, remoteRevision: Long) = SyncShadowEntity(
        tableName = table,
        rowId = rowId,
        remoteRevision = remoteRevision,
        payload = """{"notes":"a"}""",
    )

    private suspend fun seedCategories() {
        database.categoryDao().insertAllIgnoringConflicts(
            listOf(categoryEntity("food"), categoryEntity("transport")),
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

    private fun ruleEntity(
        id: String,
        priority: Int,
        merchantContains: String = "SWIGGY",
        categoryId: String = "food",
    ) = CategoryRuleEntity(
        id = id,
        categoryId = categoryId,
        merchantContains = merchantContains,
        priority = priority,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
