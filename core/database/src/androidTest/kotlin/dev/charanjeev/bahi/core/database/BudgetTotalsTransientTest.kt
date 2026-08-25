package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Measures the transient documented on
 * `OfflineFirstBudgetRepository.observeMonthlyBudgets` and in
 * docs/budgets-design.md §2.2.
 *
 * The claim under test: budget spend and uncategorised spend come from two
 * separate Room flows invalidated by the same write, and because they
 * re-query independently, `combine` can briefly pair one's new value with
 * the other's old one -- so a write that moves money between the two lines
 * can emit one frame where they disagree about which write they reflect.
 *
 * This reproduces that combine at the DAO level rather than through the
 * repository, which only maps: the mechanism is entirely Room's invalidation
 * feeding two flows. `runBlocking` on a real dispatcher rather than
 * `runTest`, because Room's InvalidationTracker delivers on its own executor
 * and virtual time cannot advance a real thread pool.
 *
 * **What this test may and may not assert.** Which of the two flows re-queries
 * first is not ordered by anything -- not by Room, not by `combine`, not by
 * the write. So the intermediate frame lands in one of two shapes, and an
 * earlier version of this test asserted one of them could never happen:
 *
 * | first flow to re-query | frame while the other is stale        |
 * |------------------------|---------------------------------------|
 * | budget spend           | counted twice -- in budget *and* in uncategorised |
 * | uncategorised spend    | counted in neither -- both read zero  |
 *
 * Both were observed, in both directions, over 22 instrumented runs on one
 * machine -- 6 of the first 12 runs failed the old assertion outright.
 * "The money is never missing from both sides" is therefore not a property
 * of this design; it is a description of whichever way the race happened to
 * fall across the nine runs it was originally sampled over.
 *
 * What is order-independent, and what the assertions below cover:
 *
 * 1. Every frame pairs a legitimate snapshot of one side with a legitimate
 *    snapshot of the other. `combine` may show two sides from different
 *    instants, but neither side may ever report a total that no query would
 *    return -- that would be a real defect in the SQL or the write.
 * 2. Each side changes value exactly once. One write, one transition per
 *    side: no oscillation, no invalidation storm, no missed update.
 * 3. The flow settles on the correct pair, and stays there.
 *
 * Together those bound the transient to at most one intermediate frame
 * without pinning which one it is. The shape and duration of that frame is
 * printed rather than asserted, because it depends on Room's delivery
 * batching and pinning it either way would make this a change-detector for
 * Room's internals.
 */
@RunWith(AndroidJUnit4::class)
class BudgetTotalsTransientTest {

    private lateinit var database: BahiDatabase

    private val august = "2026-08"
    private val from = "2026-08-01"
    private val to = "2026-08-31"

    /** The one transaction both queries are arguing over, in minor units. */
    private val amount = 45_000L

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
        /**
         * The two sides reflect different writes. A settled frame has the
         * transaction on exactly one side, so a frame with it on both (counted
         * twice) or on neither (counted nowhere) is torn. Both are the same
         * phenomenon seen from opposite ends of the same race, which is why
         * this is one predicate rather than two.
         */
        val isTorn: Boolean get() = (budgetSpendMinor > 0L) == (uncategorisedMinor > 0L)
    }

    /**
     * A frame with the wall-clock time it arrived. Duration is what decides
     * whether the transient matters: a frame that exists for less than one
     * display refresh (~16ms at 60Hz) is never composed, let alone seen.
     */
    private data class Timed(val frame: Frame, val atNanos: Long)

    /** How long a torn frame stayed on screen before the next one replaced it. */
    private fun List<Timed>.tornDurationsMillis(): List<Double> =
        windowed(2).filter { (first, _) -> first.frame.isTorn }
            .map { (first, second) -> (second.atNanos - first.atNanos) / 1_000_000.0 }

    @Test
    fun categorisingATransaction_settlesWithTheSpendInTheBudget() = runBlocking {
        val transactionDao = database.transactionDao()
        seedBudget()
        transactionDao.upsert(transactionEntity(id = "t1", categoryId = null))

        val recording = observeFrames()
        recording.awaitFrame(Frame(0L, amount))

        transactionDao.applyRuleCategory(id = "t1", categoryId = "food", updatedAt = 2_000L)

        recording.assertSettles(from = Frame(0L, amount), to = Frame(amount, 0L), label = "FORWARD")
    }

    /** The reverse direction: money leaving a budget for the uncategorised line. */
    @Test
    fun clearingACategory_settlesWithTheSpendUncategorised() = runBlocking {
        val transactionDao = database.transactionDao()
        seedBudget()
        transactionDao.upsert(transactionEntity(id = "t1", categoryId = "food"))

        val recording = observeFrames()
        recording.awaitFrame(Frame(amount, 0L))

        transactionDao.update(
            id = "t1",
            amountMinor = -amount,
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

        recording.assertSettles(from = Frame(amount, 0L), to = Frame(0L, amount), label = "REVERSE")
    }

    private suspend fun seedBudget() {
        database.categoryDao().insertAllIgnoringConflicts(listOf(categoryEntity("food")))
        database.budgetDao().upsert(
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
    }

    /**
     * The emissions collected so far, and the collector producing them.
     *
     * [timed] is a [CopyOnWriteArrayList] because the collector writes it on
     * [Dispatchers.IO] while [awaitFrame] spins on it from the `runBlocking`
     * thread. A plain `ArrayList` there is a data race, and one that would
     * corrupt exactly the evidence this test exists to gather.
     */
    private class Recording(val timed: CopyOnWriteArrayList<Timed>, val job: Job)

    /** Collects the pair the budgets screen renders, timestamping each emission. */
    private fun CoroutineScope.observeFrames(): Recording {
        val timed = CopyOnWriteArrayList<Timed>()
        val job = launch(Dispatchers.IO) {
            combine(
                database.budgetDao().observeBudgetsWithSpend(august, from, to),
                database.transactionDao().observeUncategorisedSpend(from, to),
            ) { budgets, uncategorised ->
                Frame(budgets.single().spentMinor, uncategorised)
            }.collect { timed += Timed(it, System.nanoTime()) }
        }
        return Recording(timed, job)
    }

    /** Spins until the flow reaches [frame], so the write lands on a known state. */
    private suspend fun Recording.awaitFrame(frame: Frame) {
        withTimeout(TIMEOUT_MILLIS) {
            while (timed.lastOrNull()?.frame != frame) yield()
        }
    }

    /**
     * Waits for [to], stops the collector, and asserts the three
     * order-independent properties described on the class.
     */
    private suspend fun Recording.assertSettles(from: Frame, to: Frame, label: String) {
        awaitFrame(to)
        // cancelAndJoin, not cancel: a bare cancel leaves the collector free to
        // append one more frame while the assertions below are reading the list,
        // which would make a failure depend on when the snapshot was taken.
        job.cancelAndJoin()

        val frames = timed.map { it.frame }
        println(
            "BUDGET_TRANSIENT_$label frames=${frames.size} tornFrames=${frames.count { it.isTorn }} " +
                "durationsMs=${timed.tornDurationsMillis()} all=$frames",
        )

        // 1. Neither side ever reports a total no query would return. This is
        // the assertion that fails if the join, the sign filter or the write
        // regresses -- tearing pairs two valid snapshots, it never invents one.
        val budgetSnapshots = listOf(from.budgetSpendMinor, to.budgetSpendMinor)
        val uncategorisedSnapshots = listOf(from.uncategorisedMinor, to.uncategorisedMinor)
        frames.forEach { frame ->
            assertThat(frame.budgetSpendMinor).isIn(budgetSnapshots)
            assertThat(frame.uncategorisedMinor).isIn(uncategorisedSnapshots)
        }

        // 2. One write, one transition per side. Bounds the transient to a
        // single intermediate frame without saying which of the two it is, and
        // catches the regressions that would actually hurt: a flow that
        // oscillates, or one that re-emits on every unrelated invalidation.
        assertThat(frames.map { it.budgetSpendMinor }.transitions()).isEqualTo(1)
        assertThat(frames.map { it.uncategorisedMinor }.transitions()).isEqualTo(1)

        // 3. It starts and ends where it should, and the torn frame is
        // genuinely transient rather than the resting state.
        assertThat(frames.first()).isEqualTo(from)
        assertThat(frames.last()).isEqualTo(to)
        assertThat(frames.last().isTorn).isFalse()
    }

    /** Number of times consecutive values differ; repeats of a value are not changes. */
    private fun List<Long>.transitions(): Int = zipWithNext().count { (a, b) -> a != b }

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
        amountMinor = -amount,
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

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
