package dev.charanjeev.bahi.core.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SnapshotRow
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine's apply step against real SQLite (docs/sync-design.md §5, §6.2,
 * slice 5c): classify, merge, write, all inside one Room transaction.
 *
 * [TestMerge] stands in for `:core:sync`'s real resolver -- deliberately a
 * simple whole-payload rule rather than the real per-field policies, because
 * what this suite is proving is [RoomSyncApplier]'s own mechanics (revision
 * bookkeeping, idempotence, the transaction boundary, which write branch a
 * row takes), not merge policy correctness. That is `ConflictResolverTest`
 * and `FieldPolicyCoverageTest`'s job, in `:core:sync`, against
 * [RemoteMerge]'s real implementation.
 */
@RunWith(AndroidJUnit4::class)
class SyncApplierTest {

    private lateinit var database: BahiDatabase
    private lateinit var applier: RoomSyncApplier
    private val clock = FixedClock(Instant.fromEpochMilliseconds(10_000L))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        applier = RoomSyncApplier(database, TestMerge(), clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun applyCreatesARowThatDidNotExistLocally() = runTest {
        applier.apply(listOf(transactionOp(rowId = "t1", remoteRevision = 5, amountMinor = -100)), localDeviceId = "device-a")

        val row = database.transactionDao().rowById("t1")
        assertThat(row).isNotNull()
        assertThat(row!!.amountMinor).isEqualTo(-100)
        // Nothing local to contribute -- the outcome is exactly what remote
        // sent, so this device owes no push back (§5.2's fast-forward row).
        assertThat(row.pendingOperation).isNull()
        assertThat(row.localRevision).isEqualTo(5)
        assertThat(row.remoteRevision).isEqualTo(5)

        val shadow = database.syncShadowDao().baseOf("transactions", "t1")
        assertThat(shadow).isNotNull()
        assertThat(shadow!!.remoteRevision).isEqualTo(5)
    }

    @Test
    fun applyIsIdempotent_reapplyingTheSameOpChangesNothing() = runTest {
        val op = transactionOp(rowId = "t1", remoteRevision = 5, amountMinor = -100)
        applier.apply(listOf(op), localDeviceId = "device-a")
        val afterFirst = database.transactionDao().rowById("t1")
        val shadowAfterFirst = database.syncShadowDao().baseOf("transactions", "t1")

        applier.apply(listOf(op), localDeviceId = "device-a")

        assertThat(database.transactionDao().rowById("t1")).isEqualTo(afterFirst)
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")).isEqualTo(shadowAfterFirst)
    }

    @Test
    fun applySkipsAnOpNoNewerThanWhatTheShadowAlreadyRecords() = runTest {
        applier.apply(listOf(transactionOp(rowId = "t1", remoteRevision = 5, amountMinor = -100)), localDeviceId = "device-a")

        // A stale re-delivery of an *older* revision for the same row --
        // at-least-once storage can produce this, not just an exact repeat.
        applier.apply(listOf(transactionOp(rowId = "t1", remoteRevision = 3, amountMinor = -999)), localDeviceId = "device-a")

        assertThat(database.transactionDao().rowById("t1")!!.amountMinor).isEqualTo(-100)
    }

    @Test
    fun aRemoteTombstoneDeletesARowThatExists() = runTest {
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -100, localRevision = 1))
        database.syncShadowDao().record(shadow(rowId = "t1", remoteRevision = 1, payload = payloadOf(-100)))

        applier.apply(listOf(transactionOp(rowId = "t1", remoteRevision = 2, payload = null)), localDeviceId = "device-a")

        val row = database.transactionDao().rowById("t1")
        assertThat(row!!.deletedAt).isNotNull()
        assertThat(row.remoteRevision).isEqualTo(2)
    }

    @Test
    fun aRemoteTombstoneForARowThatNeverExistedLocallyIsNotMaterialised() = runTest {
        applier.apply(listOf(transactionOp(rowId = "never-seen", remoteRevision = 1, payload = null)), localDeviceId = "device-a")

        assertThat(database.transactionDao().rowById("never-seen")).isNull()
    }

    @Test
    fun onlyTheNewestOpForARowInOneBatchIsApplied() = runTest {
        applier.apply(
            listOf(
                transactionOp(rowId = "t1", remoteRevision = 3, amountMinor = -300),
                transactionOp(rowId = "t1", remoteRevision = 7, amountMinor = -700),
                transactionOp(rowId = "t1", remoteRevision = 5, amountMinor = -500),
            ),
            localDeviceId = "device-a",
        )

        val row = database.transactionDao().rowById("t1")
        assertThat(row!!.amountMinor).isEqualTo(-700)
        assertThat(row.remoteRevision).isEqualTo(7)
    }

    /**
     * A genuine conflict -- both sides changed the row since the shared base
     * -- has to leave the row dirty (`pending_operation` set, `local_revision`
     * above the new shadow) so the *next* push carries the merge outcome back
     * to whichever side didn't already have it, and it has to be recorded in
     * `sync_conflicts` so the discarded value is not lost (§5.6).
     */
    @Test
    fun aGenuineConflictStaysDirtyForTheNextPushAndIsRecorded() = runTest {
        // Base -50: differs from both local's -100 and remote's -900, so
        // both sides genuinely changed the row since they last agreed.
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -100, localRevision = 4, updatedAt = 50_000))
        database.syncShadowDao().record(shadow(rowId = "t1", remoteRevision = 2, payload = payloadOf(-50, updatedAt = 1_000)))

        applier.apply(
            listOf(transactionOp(rowId = "t1", remoteRevision = 9, amountMinor = -900, updatedAt = 20_000)),
            localDeviceId = "device-a",
        )

        val row = database.transactionDao().rowById("t1")!!
        // Local's own edit (-100, updatedAt 50_000) is newer than remote's
        // (-900, updatedAt 20_000), so TestMerge's tiebreak keeps it -- and
        // because that differs from what the op itself carried, this device
        // still owes remote a push.
        assertThat(row.amountMinor).isEqualTo(-100)
        assertThat(row.pendingOperation).isEqualTo("UPSERT")
        assertThat(row.localRevision).isGreaterThan(9L)

        val conflicts = database.syncConflictDao().observeUnacknowledged().first()
        assertThat(conflicts.map { it.rowId }).containsExactly("t1")
        assertThat(conflicts.single().discardedValue).contains("-900")
    }

    /**
     * The bug found while building this slice (SyncApplier's KDoc on
     * `decide`): `local_revision` is a per-device counter with no shared
     * scale across devices. Applying a remote op from a device whose own
     * counter is far ahead must not leave this device's counter *behind* the
     * shadow it just wrote, or a genuinely new local edit right after would
     * look already-synced and never get pushed.
     */
    @Test
    fun revisionRebase_localEditAfterALargeRemoteRevisionStaysDirty() = runTest {
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -100, localRevision = 2, updatedAt = 3_000))
        database.syncShadowDao().record(shadow(rowId = "t1", remoteRevision = 1, payload = payloadOf(-50, updatedAt = 500)))

        // A device whose own counter for this row happens to be 500 --
        // nothing about that number is comparable to this device's 2.
        applier.apply(
            listOf(transactionOp(rowId = "t1", remoteRevision = 500, amountMinor = -900, updatedAt = 2_000)),
            localDeviceId = "device-a",
        )
        val afterMerge = database.transactionDao().rowById("t1")!!
        // Base (-50) differs from both local (-100) and remote (-900): a
        // genuine conflict: local wins the tiebreak (newer updatedAt).
        assertThat(afterMerge.amountMinor).isEqualTo(-100)
        assertThat(afterMerge.localRevision).isEqualTo(501L)

        // A brand new local edit, exactly as TransactionDao.update performs it.
        database.transactionDao().update(
            id = "t1",
            amountMinor = -150,
            currencyCode = afterMerge.currencyCode,
            date = afterMerge.date,
            description = afterMerge.description,
            merchant = afterMerge.merchant,
            categoryId = afterMerge.categoryId,
            accountId = afterMerge.accountId,
            notes = afterMerge.notes,
            categoryLockedByUser = afterMerge.categoryLockedByUser,
            contentHash = afterMerge.contentHash,
            updatedAt = 3_000,
        )

        // The bug this test catches: without the rebase, local_revision would
        // have been 3 (2 + 1), which is *less* than the shadow's new 500 --
        // and this row would silently stop being pushed.
        assertThat(database.transactionDao().dirtyRows().map { it.id }).contains("t1")
    }

    @Test
    fun applyDispatchesToTheRightTableByOpName() = runTest {
        applier.apply(
            listOf(
                SyncOp(
                    table = SyncTable.CATEGORIES.tableName,
                    rowId = "cat1",
                    remoteRevision = 1,
                    deviceId = "device-a",
                    updatedAt = 1_000,
                    payload = buildJsonObject {
                        put("name", JsonPrimitive("Food"))
                        put("parent_id", JsonPrimitive(null as String?))
                        put("color_argb", JsonPrimitive(0))
                        put("icon_key", JsonPrimitive("food"))
                        put("is_system_defined", JsonPrimitive(false))
                    },
                ),
            ),
            localDeviceId = "device-a",
        )

        val category: CategoryEntity? = database.categoryDao().rowById("cat1")
        assertThat(category).isNotNull()
        assertThat(category!!.name).isEqualTo("Food")
    }

    // --- reconcile (docs/sync-design.md §7, D8's full-reconciliation path) ---

    @Test
    fun reconcileAppliesEverySnapshotRowLikeAnOrdinaryPulledOp() = runTest {
        applier.reconcile(
            RemoteSnapshot(horizon = mapOf("device-b" to 1), rows = listOf(snapshotRow(rowId = "t1", remoteRevision = 5, amountMinor = -100))),
            localDeviceId = "device-a",
        )

        val row = database.transactionDao().rowById("t1")
        assertThat(row).isNotNull()
        assertThat(row!!.amountMinor).isEqualTo(-100)
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")!!.remoteRevision).isEqualTo(5)
    }

    /**
     * §7's clean case: the row is not merely tombstoned locally, it may still
     * look entirely live here, because the tombstone that would have told
     * this device about the deletion was itself compacted away before this
     * device ever pulled it. Nothing beyond what was last agreed
     * ([shadow]'s revision equals the row's own) means there is no local
     * edit to protect, so remote having forgotten the row entirely is taken
     * at face value.
     */
    @Test
    fun reconcileHardDeletesALiveLocalRowMissingFromTheSnapshotWithNothingUnpushed() = runTest {
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -100, localRevision = 3))
        database.syncShadowDao().record(shadow(rowId = "t1", remoteRevision = 3, payload = payloadOf(-100)))
        database.syncConflictDao().record(conflictOf(rowId = "t1"))

        applier.reconcile(RemoteSnapshot(horizon = mapOf("device-b" to 9), rows = emptyList()), localDeviceId = "device-a")

        assertThat(database.transactionDao().rowById("t1")).isNull()
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")).isNull()
        assertThat(database.syncConflictDao().observeForRow("transactions", "t1").first()).isEmpty()
    }

    /**
     * Edit-over-delete (§5.3), one horizon later: this device edited the row
     * after the last state it and remote agreed on, and remote has since
     * forgotten the row entirely. The edit survives -- a lost edit does not
     * come back, a redundant re-push costs nothing -- and the stale base is
     * forgotten so the row is pushed as if it were a fresh creation rather
     * than compared against a base remote can no longer produce.
     */
    @Test
    fun reconcileKeepsALocalEditNewerThanTheShadowEvenWhenMissingFromTheSnapshot() = runTest {
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -200, localRevision = 5))
        database.syncShadowDao().record(shadow(rowId = "t1", remoteRevision = 3, payload = payloadOf(-100)))

        applier.reconcile(RemoteSnapshot(horizon = mapOf("device-b" to 9), rows = emptyList()), localDeviceId = "device-a")

        val row = database.transactionDao().rowById("t1")
        assertThat(row).isNotNull()
        assertThat(row!!.amountMinor).isEqualTo(-200)
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")).isNull()
    }

    /** A row this device has never synced at all -- absence from the snapshot is simply "remote never got it yet". */
    @Test
    fun reconcileLeavesAGenuineLocalCreationNeverSyncedAlone() = runTest {
        database.transactionDao().upsert(transactionEntity(id = "t1", amountMinor = -300, localRevision = 1))

        applier.reconcile(RemoteSnapshot(horizon = mapOf("device-b" to 9), rows = emptyList()), localDeviceId = "device-a")

        assertThat(database.transactionDao().rowById("t1")).isNotNull()
        assertThat(database.syncShadowDao().baseOf("transactions", "t1")).isNull()
    }

    private fun snapshotRow(rowId: String, remoteRevision: Long, amountMinor: Long, updatedAt: Long = 1_000) = SnapshotRow(
        table = SyncTable.TRANSACTIONS.tableName,
        rowId = rowId,
        remoteRevision = remoteRevision,
        updatedAt = updatedAt,
        payload = payloadOf(amountMinor, updatedAt),
    )

    private fun conflictOf(rowId: String) = dev.charanjeev.bahi.core.database.entity.SyncConflictEntity(
        id = "conflict-$rowId",
        tableName = "transactions",
        rowId = rowId,
        field = "notes",
        resolvedAt = 1L,
        chosenValue = """"chosen"""",
        discardedValue = """"discarded"""",
        reason = "test",
    )

    private fun payloadOf(amountMinor: Long, updatedAt: Long = 0L): JsonObject = transactionOp(
        rowId = "unused",
        remoteRevision = 0,
        amountMinor = amountMinor,
        updatedAt = updatedAt,
    ).payload!!

    private fun transactionOp(
        rowId: String,
        remoteRevision: Long,
        amountMinor: Long = -100,
        updatedAt: Long = 1_000,
        payload: JsonObject? = buildJsonObject {
            put("amount_minor", JsonPrimitive(amountMinor))
            put("currency_code", JsonPrimitive("INR"))
            put("date", JsonPrimitive("2026-01-05"))
            put("description", JsonPrimitive("Coffee"))
            put("merchant", JsonPrimitive(null as String?))
            put("category_id", JsonPrimitive(null as String?))
            put("account_id", JsonPrimitive("acct-1"))
            put("source", JsonPrimitive("MANUAL"))
            put("notes", JsonPrimitive(null as String?))
            put("category_locked_by_user", JsonPrimitive(false))
            put("import_batch_id", JsonPrimitive(null as String?))
        },
    ) = SyncOp(
        table = SyncTable.TRANSACTIONS.tableName,
        rowId = rowId,
        remoteRevision = remoteRevision,
        deviceId = "device-b",
        updatedAt = updatedAt,
        payload = payload,
    )

    private fun transactionEntity(
        id: String,
        amountMinor: Long,
        localRevision: Long,
        updatedAt: Long = 1_000,
    ) = TransactionEntity(
        id = id,
        amountMinor = amountMinor,
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
        createdAt = 500L,
        updatedAt = updatedAt,
        localRevision = localRevision,
        remoteRevision = null,
        pendingOperation = "UPSERT",
    )

    private fun shadow(rowId: String, remoteRevision: Long, payload: JsonObject?) =
        dev.charanjeev.bahi.core.database.entity.SyncShadowEntity(
            tableName = "transactions",
            rowId = rowId,
            remoteRevision = remoteRevision,
            payload = payload?.toString(),
        )
}

/**
 * A simple whole-payload merge: not the real per-field policy, see the class
 * doc on [SyncApplierTest]. Same causal shape as the real resolver though --
 * fast-forward when only one side moved from the base, tiebreak-and-record
 * when both did.
 */
private class TestMerge : RemoteMerge {
    override fun merge(
        table: SyncTable,
        local: MergeSideInput,
        remote: MergeSideInput,
        base: JsonObject?,
    ): MergeOutcome {
        val localPayload = local.payload
        val remotePayload = remote.payload
        return when {
            // Includes null == null: both sides agree, nothing to do.
            localPayload == remotePayload -> MergeOutcome(localPayload)
            // Local unchanged since the shared base (or there was none, and
            // local never existed) -- fast-forward remote's value, deletion
            // included. Checked before the null-vs-non-null branch below, or
            // a clean remote delete of an untouched row would be read as a
            // conflict with nothing to be in conflict with.
            base == localPayload -> MergeOutcome(remotePayload)
            base == remotePayload -> MergeOutcome(localPayload)
            // Genuinely concurrent: one side deleted, the other edited since
            // the same base. Edit wins (§5.3) -- a re-delete costs one tap, a
            // lost edit does not come back.
            localPayload == null -> MergeOutcome(remotePayload)
            remotePayload == null -> MergeOutcome(localPayload)
            else -> {
                val localWins = local.updatedAt >= remote.updatedAt
                val chosen = if (localWins) localPayload else remotePayload
                val discarded = if (localWins) remotePayload else localPayload
                MergeOutcome(chosen, listOf(ResolvedField("payload", chosen, discarded, "newest wins")))
            }
        }
    }
}
