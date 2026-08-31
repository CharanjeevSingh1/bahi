package dev.charanjeev.bahi.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RoomSyncConflictRepository]: the read side (docs/sync-design.md §5.6) and,
 * the part worth the most scrutiny, [RestoreOutcome] -- restore has exactly
 * one way to succeed and three honest ways to refuse, and CLAUDE.md's rule
 * for this repo's style ("say so rather than shipping a button that looks
 * like it can") is what [RestoreOutcome.VALUE_CHANGED_SINCE] exists to keep.
 */
@RunWith(AndroidJUnit4::class)
class SyncConflictRepositoryTest {

    private lateinit var database: BahiDatabase
    private lateinit var repository: RoomSyncConflictRepository
    private val clock = FixedClock(Instant.fromEpochMilliseconds(10_000L))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        repository = RoomSyncConflictRepository(database, clock, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun observeConflictsDecodesEveryFieldOfTheEntity() = runTest {
        database.syncConflictDao().record(
            conflict(id = "c1", field = "notes", chosen = jsonOf("keep this"), discarded = jsonOf("lost this")),
        )

        val conflict = repository.observeConflicts().first().single()

        assertThat(conflict.id).isEqualTo("c1")
        assertThat(conflict.rowId).isEqualTo("t1")
        assertThat(conflict.field).isEqualTo("notes")
        assertThat(conflict.chosenValue).isEqualTo(ConflictValue.Text("keep this"))
        assertThat(conflict.discardedValue).isEqualTo(ConflictValue.Text("lost this"))
        assertThat(conflict.reason).isEqualTo("test")
        assertThat(conflict.acknowledgedAt).isNull()
    }

    @Test
    fun observeUnacknowledgedCountTracksTheTable() = runTest {
        assertThat(repository.observeUnacknowledgedCount().first()).isEqualTo(0)

        database.syncConflictDao().record(conflict(id = "c1"))

        assertThat(repository.observeUnacknowledgedCount().first()).isEqualTo(1)
    }

    @Test
    fun acknowledgeRemovesAConflictFromTheUnacknowledgedList() = runTest {
        database.syncConflictDao().record(conflict(id = "c1"))

        repository.acknowledge("c1")

        assertThat(repository.observeConflicts().first()).isEmpty()
    }

    @Test
    fun restoreWritesTheDiscardedValueBackWhenNothingHasChangedSince() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", notes = "the merged value", localRevision = 3))
        database.syncConflictDao().record(
            conflict(id = "c1", field = "notes", chosen = jsonOf("the merged value"), discarded = jsonOf("what the user actually typed")),
        )

        val outcome = repository.restore("c1")

        assertThat(outcome).isEqualTo(RestoreOutcome.RESTORED)
        assertThat(database.transactionDao().rowById("t1")!!.notes).isEqualTo("what the user actually typed")
        // The row bumped, dirty for the next push -- a restore is a normal edit.
        assertThat(database.transactionDao().rowById("t1")!!.localRevision).isEqualTo(4)
        assertThat(database.transactionDao().rowById("t1")!!.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun restoreAcknowledgesTheConflictSoItDoesNotStayOnTheList() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", notes = "the merged value", localRevision = 1))
        database.syncConflictDao().record(
            conflict(id = "c1", field = "notes", chosen = jsonOf("the merged value"), discarded = jsonOf("older note")),
        )

        repository.restore("c1")

        assertThat(repository.observeConflicts().first()).isEmpty()
    }

    @Test
    fun restoreRefusesWhenTheFieldHasChangedSinceTheConflictResolved() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", notes = "a newer edit nobody recorded", localRevision = 5))
        // chosen no longer matches the live row -- something has edited notes again since.
        database.syncConflictDao().record(
            conflict(id = "c1", field = "notes", chosen = jsonOf("the merged value"), discarded = jsonOf("older note")),
        )

        val outcome = repository.restore("c1")

        assertThat(outcome).isEqualTo(RestoreOutcome.VALUE_CHANGED_SINCE)
        // Refused, not silently skipped -- the newer value is untouched and the conflict stays for the user to see.
        assertThat(database.transactionDao().rowById("t1")!!.notes).isEqualTo("a newer edit nobody recorded")
        assertThat(repository.observeConflicts().first()).hasSize(1)
    }

    @Test
    fun restoreRefusesWhenTheRowNoLongerExists() = runTest {
        database.syncConflictDao().record(
            conflict(id = "c1", rowId = "never-existed", field = "notes", chosen = jsonOf("x"), discarded = jsonOf("y")),
        )

        assertThat(repository.restore("c1")).isEqualTo(RestoreOutcome.ROW_GONE)
    }

    @Test
    fun restoreRefusesWhenTheRowIsTombstoned() = runTest {
        database.transactionDao().upsert(transaction(id = "t1", notes = "the merged value", localRevision = 1))
        database.transactionDao().softDelete("t1", deletedAt = 5_000L)
        database.syncConflictDao().record(
            conflict(id = "c1", field = "notes", chosen = jsonOf("the merged value"), discarded = jsonOf("older note")),
        )

        assertThat(repository.restore("c1")).isEqualTo(RestoreOutcome.ROW_GONE)
    }

    @Test
    fun restoreOfAnUnknownConflictIdReportsNotFound() = runTest {
        assertThat(repository.restore("missing")).isEqualTo(RestoreOutcome.NOT_FOUND)
    }

    private fun jsonOf(value: String) = JsonPrimitive(value).toString()

    private fun conflict(
        id: String,
        rowId: String = "t1",
        field: String = "notes",
        chosen: String = jsonOf("chosen"),
        discarded: String = jsonOf("discarded"),
    ) = SyncConflictEntity(
        id = id,
        tableName = "transactions",
        rowId = rowId,
        field = field,
        resolvedAt = 1_000L,
        chosenValue = chosen,
        discardedValue = discarded,
        reason = "test",
    )

    private fun transaction(id: String, notes: String?, localRevision: Long) = TransactionEntity(
        id = id,
        amountMinor = -100,
        currencyCode = "INR",
        date = "2026-01-05",
        description = "Coffee",
        merchant = null,
        categoryId = null,
        accountId = "acct-1",
        source = "MANUAL",
        notes = notes,
        categoryLockedByUser = false,
        contentHash = "hash-$id",
        createdAt = 500L,
        updatedAt = 1_000L,
        localRevision = localRevision,
        remoteRevision = null,
        pendingOperation = "UPSERT",
    )
}
