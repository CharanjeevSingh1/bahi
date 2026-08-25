package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the transient documented on
 * `OfflineFirstBudgetRepository.observeMonthlyBudgets` and in
 * docs/budgets-design.md §2.2.
 *
 * The claim under test: budget spend and uncategorised spend come from two
 * separate Room flows invalidated by the same write, and because they
 * re-query independently, `combine` can briefly pair one's new value with
 * the other's old one -- so categorising a transaction can emit one frame
 * counting it in both its new budget and the uncategorised line.
 *
 * This reproduces that combine at the DAO level rather than through the
 * repository, which only maps: the mechanism is entirely Room's invalidation
 * feeding two flows. `runBlocking` on a real dispatcher rather than
 * `runTest`, because Room's InvalidationTracker delivers on its own executor
 * and virtual time cannot advance a real thread pool.
 *
 * It records every emission so the answer is counted rather than asserted
 * from the armchair -- an intermediate frame either shows up here or the
 * transient is theoretical, and the test says which.
 */
@RunWith(AndroidJUnit4::class)
class BudgetTotalsTransientTest {

    private lateinit var database: BahiDatabase

    private val august = "2026-08"
    private val from = "2026-08-01"
    private val to = "2026-08-31"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** One emission of the pair the budgets screen renders. */
    private data class Frame(val budgetSpendMinor: Long, val uncategorisedMinor: Long) {
        /** The transient: the same money counted in a budget *and* in the uncategorised line. */
        val doubleCounts: Boolean get() = budgetSpendMinor > 0L && uncategorisedMinor > 0L
    }

    /**
     * A frame with the wall-clock time it arrived. Duration is the whole
     * question for the transient: a frame that exists for less than one
     * display refresh (~16ms at 60Hz) is never composed, let alone seen.
     */
    private data class Timed(val frame: Frame, val atNanos: Long)

    /** How long an intermediate frame stayed on screen before the next one replaced it. */
    private fun List<Timed>.transientDurationsMillis(): List<Double> =
        windowed(2).filter { (first, _) -> first.frame.doubleCounts }
            .map { (first, second) -> (second.atNanos - first.atNanos) / 1_000_000.0 }

    @Test
    fun categorisingATransaction_settlesWithoutLeavingMoneyDoubleCounted() = runBlocking {
        val budgetDao = database.budgetDao()
        val transactionDao = database.transactionDao()
        database.categoryDao().insertAllIgnoringConflicts(listOf(categoryEntity("food")))
        budgetDao.upsert(
            BudgetEntity(
                id = "b-food",
                categoryId = "food",
                yearMonth = august,
                limitMinor = 800_000,
                currencyCode = "INR",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        transactionDao.upsert(transactionEntity(id = "t1", categoryId = null))

        val timed = mutableListOf<Timed>()
        val collector = launch(Dispatchers.IO) {
            combine(
                budgetDao.observeBudgetsWithSpend(august, from, to),
                transactionDao.observeUncategorisedSpend(from, to),
            ) { budgets, uncategorised ->
                Frame(budgets.single().spentMinor, uncategorised)
            }.collect { timed += Timed(it, System.nanoTime()) }
        }

        // Wait for the pre-write steady state: nothing in the budget, all of
        // it uncategorised.
        withTimeout(5_000) {
            while (timed.lastOrNull()?.frame != Frame(0L, 45_000L)) yield()
        }

        transactionDao.applyRuleCategory(id = "t1", categoryId = "food", updatedAt = 2_000L)

        // Wait for the post-write steady state: all of it in the budget,
        // nothing uncategorised.
        withTimeout(5_000) {
            while (timed.lastOrNull()?.frame != Frame(45_000L, 0L)) yield()
        }
        collector.cancel()

        // What this actually asserts is the part that matters to a user: the
        // flow settles on the correct pair, and never on a frame that has
        // lost money. Whether an intermediate double-counting frame appears
        // at all is reported below rather than asserted, because it depends
        // on Room's delivery batching and pinning it either way would make
        // this test a change-detector for Room's internals.
        assertThat(timed.last().frame).isEqualTo(Frame(45_000L, 0L))
        // No frame ever under-counts: the money is in one place or the other,
        // or briefly in both -- never in neither. Overstating is
        // self-correcting; losing a transaction from the screen is not.
        assertThat(timed.none { (it) -> it.budgetSpendMinor == 0L && it.uncategorisedMinor == 0L }).isTrue()

        val doubleCounted = timed.count { it.frame.doubleCounts }
        println(
            "BUDGET_TRANSIENT frames=${timed.size} doubleCountingFrames=$doubleCounted " +
                "durationsMs=${timed.transientDurationsMillis()} all=${timed.map { it.frame }}",
        )
    }

    /** The reverse direction: money leaving a budget for the uncategorised line. */
    @Test
    fun clearingACategory_alsoSettlesWithoutLosingTheTransaction() = runBlocking {
        val budgetDao = database.budgetDao()
        val transactionDao = database.transactionDao()
        database.categoryDao().insertAllIgnoringConflicts(listOf(categoryEntity("food")))
        budgetDao.upsert(
            BudgetEntity(
                id = "b-food",
                categoryId = "food",
                yearMonth = august,
                limitMinor = 800_000,
                currencyCode = "INR",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        transactionDao.upsert(transactionEntity(id = "t1", categoryId = "food"))

        val timed = mutableListOf<Timed>()
        val collector = launch(Dispatchers.IO) {
            combine(
                budgetDao.observeBudgetsWithSpend(august, from, to),
                transactionDao.observeUncategorisedSpend(from, to),
            ) { budgets, uncategorised ->
                Frame(budgets.single().spentMinor, uncategorised)
            }.collect { timed += Timed(it, System.nanoTime()) }
        }

        withTimeout(5_000) {
            while (timed.lastOrNull()?.frame != Frame(45_000L, 0L)) yield()
        }

        transactionDao.update(
            id = "t1",
            amountMinor = -45_000,
            currencyCode = "INR",
            date = "2026-08-14",
            description = "Coffee Shop",
            merchant = null,
            categoryId = null,
            accountId = "acct-1",
            notes = null,
            categoryLockedByUser = false,
            contentHash = "t1",
            updatedAt = 2_000L,
        )

        withTimeout(5_000) {
            while (timed.lastOrNull()?.frame != Frame(0L, 45_000L)) yield()
        }
        collector.cancel()

        assertThat(timed.last().frame).isEqualTo(Frame(0L, 45_000L))
        assertThat(timed.none { (it) -> it.budgetSpendMinor == 0L && it.uncategorisedMinor == 0L }).isTrue()

        val doubleCounted = timed.count { it.frame.doubleCounts }
        println(
            "BUDGET_TRANSIENT_REVERSE frames=${timed.size} doubleCountingFrames=$doubleCounted " +
                "durationsMs=${timed.transientDurationsMillis()} all=${timed.map { it.frame }}",
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

    private fun transactionEntity(id: String, categoryId: String?) = TransactionEntity(
        id = id,
        amountMinor = -45_000,
        currencyCode = "INR",
        date = "2026-08-14",
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
