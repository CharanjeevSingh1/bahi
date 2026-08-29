package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.SyncConflictDao
import dev.charanjeev.bahi.core.database.dao.SyncShadowDao
import dev.charanjeev.bahi.core.database.entity.SyncConflictEntity
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The merge base and the conflict log, against real SQLite.
 *
 * Both tables are written by code that does not exist yet -- the resolver is
 * slice 4 and the engine slice 5 -- so nothing here can test "sync works". What
 * it can test is the two invariants that live in these DAOs rather than in the
 * engine, and would be silently lost if the engine were written against a fake
 * that agreed with it: the shadow's three-way distinction between no base, a
 * live base and a deleted base, and the conflict table's bound.
 */
@RunWith(AndroidJUnit4::class)
class SyncDaoTest {

    private lateinit var database: BahiDatabase
    private lateinit var shadowDao: SyncShadowDao
    private lateinit var conflictDao: SyncConflictDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        shadowDao = database.syncShadowDao()
        conflictDao = database.syncConflictDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --- sync_shadow ---

    /**
     * The distinction the whole design rests on. "No row here" means no agreed
     * base, which docs/sync-design.md §4.1 merges without one and records as a
     * conflict if the fields differ. "A row here with a null payload" means the
     * remote and this device agreed the row was deleted. Reading the second as
     * the first turns a pulled deletion into an unattributable conflict; reading
     * the first as the second deletes a row nobody deleted.
     */
    @Test
    fun baseOf_distinguishesNoBaseFromABaseThatSaysDeleted() = runTest {
        shadowDao.record(shadow(rowId = "deleted-row", payload = null))

        assertThat(shadowDao.baseOf("transactions", "deleted-row")).isNotNull()
        assertThat(shadowDao.baseOf("transactions", "deleted-row")!!.payload).isNull()
        assertThat(shadowDao.baseOf("transactions", "never-synced")).isNull()
    }

    /**
     * The primary key is (table_name, row_id), not row_id: a budget and a
     * transaction can hold the same id string without either being a fact about
     * the other. Since v5 that is not hypothetical -- budget ids are derived
     * from a natural key and transaction ids from content, so both are strings
     * the app mints rather than UUIDs it can assume are unique across tables.
     */
    @Test
    fun theSameRowIdInTwoTablesIsTwoDifferentBases() = runTest {
        shadowDao.record(shadow(table = "transactions", rowId = "shared-id", payload = """{"a":1}"""))
        shadowDao.record(shadow(table = "budgets", rowId = "shared-id", payload = """{"a":2}"""))

        assertThat(shadowDao.baseOf("transactions", "shared-id")!!.payload).isEqualTo("""{"a":1}""")
        assertThat(shadowDao.baseOf("budgets", "shared-id")!!.payload).isEqualTo("""{"a":2}""")
    }

    @Test
    fun record_replacesTheBaseRatherThanAccumulatingRevisions() = runTest {
        shadowDao.record(shadow(remoteRevision = 4, payload = """{"notes":"old"}"""))
        shadowDao.record(shadow(remoteRevision = 5, payload = """{"notes":"new"}"""))

        val base = shadowDao.baseOf("transactions", "row-1")
        assertThat(base!!.remoteRevision).isEqualTo(5)
        assertThat(base.payload).isEqualTo("""{"notes":"new"}""")
        assertThat(shadowDao.count()).isEqualTo(1)
    }

    /**
     * `forget` really removes the row, unlike every user-facing delete in this
     * database (CLAUDE.md rule 7). A shadow has no tombstone worth keeping: the
     * absence of a base is itself a meaningful state, and a soft-deleted shadow
     * would be a third state meaning the same thing as one of the two.
     */
    @Test
    fun forget_removesTheBaseSoTheRowIsMergedWithoutOne() = runTest {
        shadowDao.record(shadow())

        assertThat(shadowDao.forget("transactions", "row-1")).isEqualTo(1)
        assertThat(shadowDao.baseOf("transactions", "row-1")).isNull()
    }

    /**
     * §4.1 treats "this row has no base" and "this device has no bases at all"
     * differently: the first merges without one, the second is a fresh install
     * or a restored backup and takes the full-reconciliation path in §7 rather
     * than merging a whole history blind.
     */
    @Test
    fun count_separatesAnEmptyShadowFromOneMissingASingleRow() = runTest {
        assertThat(shadowDao.count()).isEqualTo(0)

        shadowDao.recordAll(listOf(shadow(rowId = "a"), shadow(rowId = "b")))

        assertThat(shadowDao.count()).isEqualTo(2)
        assertThat(shadowDao.baseOf("transactions", "c")).isNull()
    }

    @Test
    fun basesOf_returnsOnlyTheRequestedRowsFromTheRequestedTable() = runTest {
        shadowDao.recordAll(
            listOf(
                shadow(rowId = "a"),
                shadow(rowId = "b"),
                shadow(table = "budgets", rowId = "a"),
            ),
        )

        assertThat(shadowDao.basesOf("transactions", listOf("a", "missing")).map { it.rowId })
            .containsExactly("a")
    }

    // --- sync_conflicts ---

    /**
     * The bound on the table, and the answer to "does anything ever delete from
     * it". Two devices that keep disagreeing about the same field would
     * otherwise leave a row per sync, growing with sync volume rather than with
     * anything the user did.
     */
    @Test
    fun record_supersedesTheEarlierUnacknowledgedConflictOnTheSameField() = runTest {
        conflictDao.record(conflict(id = "c1", discardedValue = """"first""""))
        conflictDao.record(conflict(id = "c2", discardedValue = """"second""""))

        val open = conflictDao.observeUnacknowledged().first()
        assertThat(open.map { it.id }).containsExactly("c2")
        assertThat(open.single().discardedValue).isEqualTo(""""second"""")
    }

    /**
     * Superseding is per field, not per row. A sync in which both the amount and
     * the notes conflicted has to leave two entries, or the user sees one of the
     * two discarded values and never learns about the other.
     */
    @Test
    fun record_doesNotSupersedeAConflictOnADifferentFieldOfTheSameRow() = runTest {
        conflictDao.record(conflict(id = "c1", field = "notes"))
        conflictDao.record(conflict(id = "c2", field = "amount_minor"))

        assertThat(conflictDao.observeUnacknowledged().first().map { it.id })
            .containsExactly("c1", "c2")
    }

    /**
     * Once the user has seen it the row is history, and history is what this
     * table is for. Superseding an acknowledged conflict would rewrite the
     * record of a decision the user has already been shown.
     */
    @Test
    fun record_leavesAnAcknowledgedConflictOnTheSameFieldAlone() = runTest {
        conflictDao.record(conflict(id = "c1"))
        conflictDao.acknowledge("c1", at = 1_000L)

        conflictDao.record(conflict(id = "c2"))

        assertThat(conflictDao.observeForRow("transactions", "row-1").first().map { it.id })
            .containsExactly("c1", "c2")
        assertThat(conflictDao.observeUnacknowledged().first().map { it.id }).containsExactly("c2")
    }

    /**
     * Guarded like `TransactionDao.markSynced`, for a smaller reason with the
     * same shape: `acknowledged_at` is the clock the horizon sweep reads, so an
     * unguarded UPDATE would push a conflict's expiry forward every time the
     * screen was opened, and a conflict acknowledged in 2026 would still be
     * there in 2027.
     */
    @Test
    fun acknowledge_refusesToMoveTheClockOnAnAlreadyAcknowledgedConflict() = runTest {
        conflictDao.record(conflict(id = "c1"))

        assertThat(conflictDao.acknowledge("c1", at = 1_000L)).isEqualTo(1)
        assertThat(conflictDao.acknowledge("c1", at = 9_000L)).isEqualTo(0)

        assertThat(conflictDao.observeForRow("transactions", "row-1").first().single().acknowledgedAt)
            .isEqualTo(1_000L)
    }

    /**
     * The horizon sweep (§5.6, §7). Acknowledged and old goes; acknowledged and
     * recent stays; unacknowledged stays whatever its age, because deleting one
     * would discard the losing value before anyone had the chance to look at it,
     * which is the one thing this table exists to prevent.
     */
    @Test
    fun deleteAcknowledgedBefore_neverTakesAConflictTheUserHasNotSeen() = runTest {
        conflictDao.record(conflict(id = "old", field = "notes", resolvedAt = 1L))
        conflictDao.record(conflict(id = "recent", field = "amount_minor", resolvedAt = 1L))
        conflictDao.record(conflict(id = "unseen", field = "date", resolvedAt = 1L))
        conflictDao.acknowledge("old", at = 1_000L)
        conflictDao.acknowledge("recent", at = 9_000L)

        assertThat(conflictDao.deleteAcknowledgedBefore(before = 5_000L)).isEqualTo(1)

        assertThat(conflictDao.observeForRow("transactions", "row-1").first().map { it.id })
            .containsExactly("recent", "unseen")
    }

    /**
     * When a tombstone crosses the horizon its row is hard-deleted, and its
     * conflicts have to go with it -- there is no foreign key to do it, because
     * the parent table is named by a column. A conflict outliving its row is an
     * entry the screen can only render as pointing at nothing.
     */
    @Test
    fun forgetRow_takesTheConflictsOfOneRowAndLeavesTheOthers() = runTest {
        conflictDao.record(conflict(id = "c1", rowId = "row-1"))
        conflictDao.record(conflict(id = "c2", rowId = "row-2"))
        conflictDao.record(conflict(id = "c3", table = "budgets", rowId = "row-1"))

        assertThat(conflictDao.forgetRow("transactions", "row-1")).isEqualTo(1)

        assertThat(conflictDao.observeUnacknowledged().first().map { it.id })
            .containsExactly("c2", "c3")
    }

    @Test
    fun observeUnacknowledgedCount_isWhatTheSettingsRowShows() = runTest {
        conflictDao.record(conflict(id = "c1", field = "notes"))
        conflictDao.record(conflict(id = "c2", field = "date"))
        conflictDao.acknowledge("c1", at = 1_000L)

        assertThat(conflictDao.observeUnacknowledgedCount().first()).isEqualTo(1)
    }

    private fun shadow(
        table: String = "transactions",
        rowId: String = "row-1",
        remoteRevision: Long = 4,
        payload: String? = """{"notes":"a"}""",
    ) = SyncShadowEntity(
        tableName = table,
        rowId = rowId,
        remoteRevision = remoteRevision,
        payload = payload,
    )

    private fun conflict(
        id: String,
        table: String = "transactions",
        rowId: String = "row-1",
        field: String = "notes",
        resolvedAt: Long = 500L,
        discardedValue: String = """"discarded"""",
    ) = SyncConflictEntity(
        id = id,
        tableName = table,
        rowId = rowId,
        field = field,
        resolvedAt = resolvedAt,
        chosenValue = """"chosen"""",
        discardedValue = discardedValue,
        reason = "newest-wins",
    )
}
