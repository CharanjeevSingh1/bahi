package dev.charanjeev.bahi.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Re-measures `core/database`'s `BudgetTotalsTransientTest` under the write
 * docs/sync-design.md §6.2 point 3 says that test never covered: a category
 * change arriving through [RoomSyncApplier.apply] -- one remote op, applied
 * inside sync's own `database.withTransaction` -- rather than a direct DAO
 * call from a screen the user is looking at. The claim under test is
 * unchanged (`combine` can pair one flow's new value with the other's stale
 * one for exactly one frame); what changes is which code path produces the
 * write, because that path is now reachable while the budgets screen is
 * open and idle, not just from a user action on it.
 *
 * **30 runs per direction**, per CLAUDE.md's rule on this exact test after
 * the original 9-run sample produced a claim ("the transient can only ever
 * overstate, never lose money") that was wrong in both directions and failed
 * about half the time once CI exercised it on every push. Every run asserts
 * the same three order-independent properties `BudgetTotalsTransientTest`
 * does (no invented total, exactly one transition per side, settles
 * correctly) -- a single run failing any of those fails the test outright,
 * so the loop is a sample size for the *tear frequency* claim below, not a
 * substitute for per-run correctness.
 *
 * Result, 30 runs each direction, one emulator (Pixel_9_Pro_XL API 36):
 * FORWARD tore on 19/30 runs (2 double-counted, 17 counted in neither);
 * REVERSE tore on 21/30 runs (17 double-counted, 4 counted in neither).
 * Both shapes occur in both directions, same as `BudgetTotalsTransientTest`'s
 * own finding -- which side re-queries first is still not ordered by
 * anything the write path controls, sync-driven or not. All 30+30 runs
 * settled on the correct pair with exactly one transition per side; none
 * violated the three order-independent properties. See docs/sync-design.md
 * §6.2 for how this result is used.
 */
@RunWith(AndroidJUnit4::class)
class SyncDrivenBudgetTotalsTransientTest {

    private val august = "2026-08"
    private val from = "2026-08-01"
    private val to = "2026-08-31"
    private val amount = 45_000L
    private val clock = FixedClock(Instant.fromEpochMilliseconds(10_000L))

    private data class Frame(val budgetSpendMinor: Long, val uncategorisedMinor: Long) {
        val isTorn: Boolean get() = (budgetSpendMinor > 0L) == (uncategorisedMinor > 0L)
    }

    private data class Timed(val frame: Frame, val atNanos: Long)

    @Test
    fun categorisingATransactionViaSync_settlesWithTheSpendInTheBudget() = runBlocking {
        repeat(RUNS) { run ->
            val database = newDatabase()
            try {
                seedBudget(database)
                database.transactionDao().upsert(transactionEntity(id = "t1", categoryId = null))

                val recording = observeFrames(database)
                recording.awaitFrame(Frame(0L, amount))

                RoomSyncApplier(database, FastForwardTestMerge(), clock).apply(
                    listOf(transactionOp(rowId = "t1", categoryId = "food", remoteRevision = 1, updatedAt = 2_000L)),
                    localDeviceId = "device-a",
                )

                recording.assertSettles(from = Frame(0L, amount), to = Frame(amount, 0L), label = "SYNC_FORWARD run=$run")
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun clearingACategoryViaSync_settlesWithTheSpendUncategorised() = runBlocking {
        repeat(RUNS) { run ->
            val database = newDatabase()
            try {
                seedBudget(database)
                database.transactionDao().upsert(transactionEntity(id = "t1", categoryId = "food"))

                val recording = observeFrames(database)
                recording.awaitFrame(Frame(amount, 0L))

                RoomSyncApplier(database, FastForwardTestMerge(), clock).apply(
                    listOf(transactionOp(rowId = "t1", categoryId = null, remoteRevision = 1, updatedAt = 2_000L)),
                    localDeviceId = "device-a",
                )

                recording.assertSettles(from = Frame(amount, 0L), to = Frame(0L, amount), label = "SYNC_REVERSE run=$run")
            } finally {
                database.close()
            }
        }
    }

    private fun newDatabase(): BahiDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        BahiDatabase::class.java,
    ).build()

    private suspend fun seedBudget(database: BahiDatabase) {
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

    private class Recording(val timed: CopyOnWriteArrayList<Timed>, val job: Job)

    private fun CoroutineScope.observeFrames(database: BahiDatabase): Recording {
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

    private suspend fun Recording.awaitFrame(frame: Frame) {
        withTimeout(TIMEOUT_MILLIS) {
            while (timed.lastOrNull()?.frame != frame) yield()
        }
    }

    private suspend fun Recording.assertSettles(from: Frame, to: Frame, label: String) {
        awaitFrame(to)
        job.cancelAndJoin()

        val frames = timed.map { it.frame }
        println("BUDGET_TRANSIENT_$label frames=${frames.size} tornFrames=${frames.count { it.isTorn }} all=$frames")

        val budgetSnapshots = listOf(from.budgetSpendMinor, to.budgetSpendMinor)
        val uncategorisedSnapshots = listOf(from.uncategorisedMinor, to.uncategorisedMinor)
        frames.forEach { frame ->
            assertThat(frame.budgetSpendMinor).isIn(budgetSnapshots)
            assertThat(frame.uncategorisedMinor).isIn(uncategorisedSnapshots)
        }

        assertThat(frames.map { it.budgetSpendMinor }.transitions()).isEqualTo(1)
        assertThat(frames.map { it.uncategorisedMinor }.transitions()).isEqualTo(1)

        assertThat(frames.first()).isEqualTo(from)
        assertThat(frames.last()).isEqualTo(to)
        assertThat(frames.last().isTorn).isFalse()
    }

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
        updatedAt = 1_000L,
    )

    /** A full-row remote op, the shape a real sync push carries -- not the single-column update the direct-DAO test uses. */
    private fun transactionOp(rowId: String, categoryId: String?, remoteRevision: Long, updatedAt: Long) = SyncOp(
        table = SyncTable.TRANSACTIONS.tableName,
        rowId = rowId,
        remoteRevision = remoteRevision,
        deviceId = "device-b",
        updatedAt = updatedAt,
        payload = buildJsonObject {
            put("amount_minor", JsonPrimitive(-amount))
            put("currency_code", JsonPrimitive("INR"))
            put("date", JsonPrimitive("2026-08-14"))
            put("description", JsonPrimitive("Coffee Shop"))
            put("merchant", JsonPrimitive(null as String?))
            put("category_id", JsonPrimitive(categoryId))
            put("account_id", JsonPrimitive("acct-1"))
            put("source", JsonPrimitive("CSV_IMPORT"))
            put("notes", JsonPrimitive(null as String?))
            put("category_locked_by_user", JsonPrimitive(false))
            put("import_batch_id", JsonPrimitive(null as String?))
        },
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val RUNS = 30
    }
}

/**
 * Fast-forward-only stand-in for `:core:sync`'s real resolver, same role
 * [dev.charanjeev.bahi.core.data.repository] test doubles of it already play
 * in `SyncApplierTest` -- `:core:data` cannot depend on `:core:sync` to use
 * the genuine one. What is under measurement here is the transaction
 * boundary and the write shape, not merge policy, so remote simply wins
 * whenever the two payloads differ and remote is newer.
 */
private class FastForwardTestMerge : RemoteMerge {
    override fun merge(
        table: SyncTable,
        local: MergeSideInput,
        remote: MergeSideInput,
        base: kotlinx.serialization.json.JsonObject?,
    ): MergeOutcome {
        val localPayload = local.payload
        val remotePayload = remote.payload
        return when {
            localPayload == remotePayload -> MergeOutcome(localPayload)
            base == localPayload -> MergeOutcome(remotePayload)
            base == remotePayload -> MergeOutcome(localPayload)
            localPayload == null -> MergeOutcome(remotePayload)
            remotePayload == null -> MergeOutcome(localPayload)
            else -> MergeOutcome(if (local.updatedAt >= remote.updatedAt) localPayload else remotePayload)
        }
    }
}
