package dev.charanjeev.bahi.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.TOMBSTONE_HORIZON_DAYS
import dev.charanjeev.bahi.core.testing.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.days

/**
 * The local half of the tombstone horizon against real SQLite
 * (docs/sync-design.md §7, D8): a tombstone old enough is hard-deleted, its
 * `sync_shadow`/`sync_conflicts` rows go with it, and a category old enough
 * to reap never trips `ON DELETE CASCADE` on a budget or rule that was not
 * itself old enough (see [RoomTombstoneReaper]'s ordering doc).
 */
@RunWith(AndroidJUnit4::class)
class TombstoneReaperTest {

    private lateinit var database: BahiDatabase
    private val clock = MutableClock(startEpochMillis = HORIZON_MILLIS + 1_000L)
    private lateinit var reaper: RoomTombstoneReaper

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        reaper = RoomTombstoneReaper(database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reapHardDeletesATombstoneOlderThanTheHorizonAndForgetsItsShadowAndConflicts() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", deletedAt = 0L))
        database.syncShadowDao().record(shadow("transactions", "t1"))
        database.syncConflictDao().record(conflict("c1", "transactions", "t1"))

        reaper.reap()

        assertThat(database.transactionDao().rowById("t1")).isNull()
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")).isNull()
        assertThat(database.syncConflictDao().observeForRow("transactions", "t1").first()).isEmpty()
    }

    @Test
    fun reapLeavesATombstoneYoungerThanTheHorizonAlone() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", deletedAt = clock.now().toEpochMilliseconds() - 1_000L))

        reaper.reap()

        assertThat(database.transactionDao().rowById("t1")).isNotNull()
    }

    @Test
    fun reapNeverTouchesALiveRow() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", deletedAt = null))

        reaper.reap()

        assertThat(database.transactionDao().rowById("t1")).isNotNull()
    }

    @Test
    fun reapPurgesAnAcknowledgedConflictPastTheHorizonButNotAnUnacknowledgedOne() = runTest {
        database.syncConflictDao().record(conflict("acked", "transactions", "row-1"))
        database.syncConflictDao().record(conflict("unacked", "transactions", "row-2"))
        database.syncConflictDao().acknowledge("acked", at = 0L)

        reaper.reap()

        val remaining = database.syncConflictDao().observeForRow("transactions", "row-2").first()
        assertThat(remaining.map { it.id }).containsExactly("unacked")
        assertThat(database.syncConflictDao().observeForRow("transactions", "row-1").first()).isEmpty()
    }

    /**
     * The FK-safety case [RoomTombstoneReaper]'s doc argues for rather than
     * merely asserts: a category and the budget/rule cascading from it
     * (`ON DELETE CASCADE`) are tombstoned together by
     * `CategoryDao.softDeleteUserCategory` -- same instant, same age -- so
     * children are reaped on their own account before the category's pass
     * ever runs, and the cascade this test's category hard-delete would
     * otherwise trigger finds nothing left to cascade over. If reap order
     * were reversed, the budget and rule's `sync_shadow`/`sync_conflicts`
     * rows would survive the cascade as orphans -- this asserts they don't.
     */
    @Test
    fun reapDeletesACategoryAndItsCascadedChildrenTogetherWithNoOrphanedShadowOrConflictRows() = runTest {
        val deletedAt = 0L
        database.categoryDao().upsertAll(listOf(category(id = "cat1", deletedAt = deletedAt)))
        database.budgetDao().upsert(budget(id = "b1", categoryId = "cat1", deletedAt = deletedAt))
        database.categoryRuleDao().upsert(rule(id = "r1", categoryId = "cat1", deletedAt = deletedAt))
        for ((table, id) in listOf("categories" to "cat1", "budgets" to "b1", "category_rules" to "r1")) {
            database.syncShadowDao().record(shadow(table, id))
            database.syncConflictDao().record(conflict("c-$id", table, id))
        }

        reaper.reap()

        assertThat(database.categoryDao().rowById("cat1")).isNull()
        assertThat(database.budgetDao().rowById("b1")).isNull()
        assertThat(database.categoryRuleDao().rowById("r1")).isNull()
        for ((table, id) in listOf("categories" to "cat1", "budgets" to "b1", "category_rules" to "r1")) {
            assertThat(database.syncShadowDao().baseOf(table, id)).isNull()
            assertThat(database.syncConflictDao().observeForRow(table, id).first()).isEmpty()
        }
    }

    private fun transaction(id: String, deletedAt: Long?) = TransactionEntity(
        id = id,
        amountMinor = -100,
        currencyCode = "INR",
        date = "2026-01-05",
        description = "Coffee",
        merchant = null,
        categoryId = null,
        accountId = "acct-1",
        source = "MANUAL",
        notes = null,
        categoryLockedByUser = false,
        contentHash = "hash-$id",
        createdAt = 0L,
        updatedAt = 0L,
        localRevision = 1,
        remoteRevision = 1,
        pendingOperation = null,
        deletedAt = deletedAt,
    )

    private fun category(id: String, deletedAt: Long?) = CategoryEntity(
        id = id,
        name = id,
        parentId = null,
        colorArgb = 0xFF00FF,
        iconKey = "tag",
        isSystemDefined = false,
        updatedAt = 0L,
        localRevision = 1,
        remoteRevision = 1,
        pendingOperation = null,
        deletedAt = deletedAt,
    )

    private fun budget(id: String, categoryId: String, deletedAt: Long?) = BudgetEntity(
        id = id,
        categoryId = categoryId,
        yearMonth = "2026-01",
        limitMinor = 10_000,
        currencyCode = "INR",
        createdAt = 0L,
        updatedAt = 0L,
        localRevision = 1,
        remoteRevision = 1,
        pendingOperation = null,
        deletedAt = deletedAt,
    )

    private fun rule(id: String, categoryId: String, deletedAt: Long?) = CategoryRuleEntity(
        id = id,
        categoryId = categoryId,
        merchantContains = "COFFEE",
        priority = 0,
        createdAt = 0L,
        updatedAt = 0L,
        localRevision = 1,
        remoteRevision = 1,
        pendingOperation = null,
        deletedAt = deletedAt,
    )

    private fun shadow(table: String, rowId: String) = SyncShadowEntity(
        tableName = table,
        rowId = rowId,
        remoteRevision = 1,
        payload = null,
    )

    private fun conflict(id: String, table: String, rowId: String) = SyncConflictEntity(
        id = id,
        tableName = table,
        rowId = rowId,
        field = "notes",
        resolvedAt = 0L,
        chosenValue = """"chosen"""",
        discardedValue = """"discarded"""",
        reason = "test",
    )

    private companion object {
        val HORIZON_MILLIS = (TOMBSTONE_HORIZON_DAYS.days).inWholeMilliseconds
    }
}
