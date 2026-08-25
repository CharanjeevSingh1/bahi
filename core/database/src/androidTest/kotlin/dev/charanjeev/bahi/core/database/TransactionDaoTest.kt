package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * importBatch and countExistingHashes are exercised here for the first time
 * since they were written -- nothing in the app calls them yet, since
 * CsvImporter isn't implemented (that's a later slice). The test is what
 * makes the count-aware de-duplication behaviour real rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class TransactionDaoTest {

    private lateinit var database: BahiDatabase
    private lateinit var dao: TransactionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BahiDatabase::class.java,
        ).build()
        dao = database.transactionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importBatch_dedupesByCountNotPresence_acrossOverlappingReimports() = runTest {
        // Step 1: two genuinely identical transactions -- same coffee shop,
        // same amount, twice -- imported into an empty database. Presence-
        // based de-duplication would already get this right; count-based
        // has to get it right too.
        val coffeeHash = "date=2026-01-05|amount=-45000|desc=COFFEE SHOP"
        val firstImport = listOf(
            transactionEntity(id = "t1", contentHash = coffeeHash),
            transactionEntity(id = "t2", contentHash = coffeeHash),
        )
        val firstInsertedIds = dao.importBatch(firstImport)
        // The ids, not just the count: auto-categorisation runs against
        // exactly these, so naming them wrongly is a silent bug downstream.
        assertThat(firstInsertedIds).containsExactly("t1", "t2")
        assertThat(allTransactions()).hasSize(2)

        // Step 2: the exact same file, re-imported. This is where presence-
        // based de-duplication already worked too -- both are recognised as
        // already present and skipped.
        val secondImport = listOf(
            transactionEntity(id = "t3", contentHash = coffeeHash),
            transactionEntity(id = "t4", contentHash = coffeeHash),
        )
        val secondInsertedIds = dao.importBatch(secondImport)
        assertThat(secondInsertedIds).isEmpty()
        assertThat(allTransactions()).hasSize(2)

        // Step 3: an overlapping re-export that now contains a third,
        // genuinely new transaction sharing the same tuple -- a third
        // coffee at the same shop for the same amount. Presence-based
        // de-duplication drops all three, silently losing the real one.
        // Count-based recognises exactly two as already-seen and keeps the
        // third.
        val thirdImport = listOf(
            transactionEntity(id = "t5", contentHash = coffeeHash),
            transactionEntity(id = "t6", contentHash = coffeeHash),
            transactionEntity(id = "t7", contentHash = coffeeHash),
        )
        // The third row of the incoming batch is the one kept -- the first
        // two are consumed as matches for the two already present.
        val thirdInsertedIds = dao.importBatch(thirdImport)
        assertThat(thirdInsertedIds).containsExactly("t7")
        assertThat(allTransactions()).hasSize(3)
    }

    @Test
    fun softDeleteBatch_removesOnlyThatBatchsRows_notAnUnrelatedBatch() = runTest {
        dao.importBatch(
            listOf(
                transactionEntity(id = "a1", contentHash = "h1", importBatchId = "batch-a"),
                transactionEntity(id = "a2", contentHash = "h2", importBatchId = "batch-a"),
            ),
        )
        dao.importBatch(listOf(transactionEntity(id = "b1", contentHash = "h3", importBatchId = "batch-b")))

        val affected = dao.softDeleteBatch("batch-a", deletedAt = 1000L)

        assertThat(affected).isEqualTo(2)
        val remaining = allTransactions().map(TransactionEntity::id)
        assertThat(remaining).containsExactly("b1")
    }

    @Test
    fun softDeleteBatch_leavesARowTheUserHasSinceHandEditedAlone() = runTest {
        dao.importBatch(
            listOf(
                transactionEntity(id = "a1", contentHash = "h1", importBatchId = "batch-a"),
                transactionEntity(id = "a2", contentHash = "h2", importBatchId = "batch-a"),
            ),
        )
        // update() clears import_batch_id (TransactionDao.update's own doc) --
        // this is the mechanism, exercised directly against the real query.
        dao.update(
            id = "a2",
            amountMinor = -45000,
            currencyCode = "INR",
            date = "2026-01-05",
            description = "Corrected Merchant Name",
            merchant = null,
            categoryId = null,
            accountId = "acct-1",
            notes = null,
            categoryLockedByUser = false,
            contentHash = "h2",
            updatedAt = 2000L,
        )

        // Only 1 tombstoned, not 2 -- the return value is what the Result
        // screen reports, and claiming 2 here would overstate what actually
        // happened to the edited row.
        val affected = dao.softDeleteBatch("batch-a", deletedAt = 1000L)

        assertThat(affected).isEqualTo(1)
        val remaining = allTransactions().map(TransactionEntity::id)
        assertThat(remaining).containsExactly("a2")
    }

    @Test
    fun softDeleteBatch_leavesARowKeptByALaterOverlappingImportUntouched() = runTest {
        val coffeeHash = "h-coffee"
        // Batch A: a coffee that will get "re-imported" by an overlapping
        // statement, plus a second, unrelated row.
        dao.importBatch(
            listOf(
                transactionEntity(id = "a1", contentHash = coffeeHash, importBatchId = "batch-a"),
                transactionEntity(id = "a2", contentHash = "h-rent", importBatchId = "batch-a"),
            ),
        )
        // Batch B: overlaps on the coffee (de-duplicated away, so it never
        // gets batch-b's id) and adds one genuinely new row.
        dao.importBatch(
            listOf(
                transactionEntity(id = "b1", contentHash = coffeeHash, importBatchId = "batch-b"),
                transactionEntity(id = "b2", contentHash = "h-salary", importBatchId = "batch-b"),
            ),
        )

        val affected = dao.softDeleteBatch("batch-a", deletedAt = 1000L)

        assertThat(affected).isEqualTo(2)
        // Both of batch A's own rows are gone -- including the coffee, which
        // is still batch A's row since the de-duplication skipped inserting
        // batch B's copy of it. Batch B's genuinely new row survives.
        val remaining = allTransactions().map(TransactionEntity::id)
        assertThat(remaining).containsExactly("b2")
    }

    // --- applyRuleCategory: the guard the auto-categoriser is built around ---

    /**
     * The test that matters: not "the caller filtered correctly" but "the
     * write itself refuses." Called directly with a locked row's id, with
     * nothing upstream to stop it -- which is exactly the situation a future
     * feature forgetting layer 1 would produce.
     */
    @Test
    fun applyRuleCategory_leavesALockedRowAlone_evenWhenCalledDirectlyWithItsId() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "locked", contentHash = "h1", categoryId = "food", locked = true))

        val affected = dao.applyRuleCategory(id = "locked", categoryId = "groceries", updatedAt = 5000L)

        // 0, not 1: the row was not merely left unchanged, the UPDATE matched
        // nothing -- which is what lets the caller report honestly.
        assertThat(affected).isEqualTo(0)
        val row = allTransactions().single()
        assertThat(row.categoryId).isEqualTo("food")
        // Untouched means untouched: no revision bump, no pending write, no
        // updated_at. A locked row must not even look changed to sync.
        assertThat(row.localRevision).isEqualTo(1)
        assertThat(row.pendingOperation).isNull()
        assertThat(row.updatedAt).isEqualTo(0L)
    }

    @Test
    fun applyRuleCategory_categorisesAnUnlockedRow() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "unlocked", contentHash = "h1"))

        val affected = dao.applyRuleCategory(id = "unlocked", categoryId = "food", updatedAt = 5000L)

        assertThat(affected).isEqualTo(1)
        val row = allTransactions().single()
        assertThat(row.categoryId).isEqualTo("food")
        assertThat(row.updatedAt).isEqualTo(5000L)
        assertThat(row.pendingOperation).isEqualTo("UPSERT")
        assertThat(row.localRevision).isEqualTo(2)
    }

    @Test
    fun applyRuleCategory_leavesATombstonedRowAlone() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "gone", contentHash = "h1"))
        dao.softDelete("gone", deletedAt = 1000L)

        val affected = dao.applyRuleCategory(id = "gone", categoryId = "food", updatedAt = 5000L)

        assertThat(affected).isEqualTo(0)
    }

    @Test
    fun applyRuleCategory_keepsTheRowInItsImportBatch_unlikeAHandEdit() = runTest {
        // update() evicts a row from its batch because a hand-edit means the
        // user owns it now. A rule categorising it is not that, so batch undo
        // must still reach it.
        seedCategories()
        dao.importBatch(listOf(transactionEntity(id = "a1", contentHash = "h1", importBatchId = "batch-a")))

        dao.applyRuleCategory(id = "a1", categoryId = "food", updatedAt = 5000L)

        assertThat(dao.softDeleteBatch("batch-a", deletedAt = 6000L)).isEqualTo(1)
    }

    @Test
    fun applyRuleCategories_appliesOnlyTheRowsItIsAllowedTo_andSaysHowMany() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "unlocked", contentHash = "h1"))
        dao.upsert(transactionEntity(id = "locked", contentHash = "h2", categoryId = "food", locked = true))

        // 1, not 2. Reporting 2 here -- "recategorised 2 transactions" when
        // one was refused -- is the bug the return value exists to prevent.
        val affected = dao.applyRuleCategories(
            assignments = mapOf("unlocked" to "groceries", "locked" to "groceries"),
            updatedAt = 5000L,
        )

        assertThat(affected).isEqualTo(1)
        val byId = allTransactions().associateBy(TransactionEntity::id)
        assertThat(byId.getValue("unlocked").categoryId).isEqualTo("groceries")
        assertThat(byId.getValue("locked").categoryId).isEqualTo("food")
    }

    // --- observeUncategorisedSpend: the line that keeps an empty month and an
    // --- uncategorised month from looking the same (docs/budgets-design.md §2.2)

    @Test
    fun observeUncategorisedSpend_isZeroWhenThereIsNothingToCount_notNull() = runTest {
        // SUM over no rows is NULL in SQLite. Without COALESCE this comes back
        // as a null the Kotlin signature says can't happen, which is a crash
        // on the emptiest possible month rather than on an unusual one.
        assertThat(uncategorisedSpendInJanuary()).isEqualTo(0L)
    }

    @Test
    fun observeUncategorisedSpend_sumsExpensesFiledUnderNoCategory() = runTest {
        dao.upsert(transactionEntity(id = "t1", contentHash = "h1", amountMinor = -45_000))
        dao.upsert(transactionEntity(id = "t2", contentHash = "h2", amountMinor = -575_000))

        assertThat(uncategorisedSpendInJanuary()).isEqualTo(620_000L)
    }

    @Test
    fun observeUncategorisedSpend_ignoresATransactionThatHasACategory() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "t1", contentHash = "h1", categoryId = "food"))

        // Once a transaction has a category it is a budget's business, not
        // this line's -- counting it in both would double-report the money.
        assertThat(uncategorisedSpendInJanuary()).isEqualTo(0L)
    }

    @Test
    fun observeUncategorisedSpend_ignoresUncategorisedIncome() = runTest {
        dao.upsert(transactionEntity(id = "spend", contentHash = "h1", amountMinor = -45_000))
        dao.upsert(transactionEntity(id = "credit", contentHash = "h2", amountMinor = 900_000))

        assertThat(uncategorisedSpendInJanuary()).isEqualTo(45_000L)
    }

    @Test
    fun observeUncategorisedSpend_ignoresASoftDeletedTransaction() = runTest {
        dao.upsert(transactionEntity(id = "t1", contentHash = "h1", amountMinor = -45_000))
        dao.softDelete("t1", deletedAt = 1_000L)

        assertThat(uncategorisedSpendInJanuary()).isEqualTo(0L)
    }

    @Test
    fun observeUncategorisedSpend_countsTheLastDayOfTheWindowAndNotTheDayAfter() = runTest {
        dao.upsert(
            transactionEntity(id = "last", contentHash = "h1", amountMinor = -45_000, date = "2026-01-31"),
        )
        dao.upsert(
            transactionEntity(id = "next", contentHash = "h2", amountMinor = -999_000, date = "2026-02-01"),
        )

        assertThat(uncategorisedSpendInJanuary()).isEqualTo(45_000L)
    }

    @Test
    fun observeUncategorisedSpend_dropsToZeroOnceTheTransactionIsCategorised() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "t1", contentHash = "h1", amountMinor = -45_000))
        assertThat(uncategorisedSpendInJanuary()).isEqualTo(45_000L)

        dao.applyRuleCategory(id = "t1", categoryId = "food", updatedAt = 2_000L)

        // The money leaves this line the moment it becomes a budget's -- the
        // two figures are computed from the same rows, so they cannot
        // disagree about where a transaction lives.
        assertThat(uncategorisedSpendInJanuary()).isEqualTo(0L)
    }

    private suspend fun uncategorisedSpendInJanuary(): Long =
        dao.observeUncategorisedSpend(from = "2026-01-01", to = "2026-01-31").first()

    // --- ruleCandidates: layer 1 of the lock guard (docs/budgets-design.md §1.4)

    /**
     * The property the whole feature rests on, asserted against the real
     * query rather than against a caller that remembered to filter: a locked
     * transaction is not in the candidate set, so there is no downstream
     * mistake that can reach one.
     */
    @Test
    fun ruleCandidates_neverIncludesALockedTransaction() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "unlocked", contentHash = "h1"))
        dao.upsert(transactionEntity(id = "locked", contentHash = "h2", categoryId = "food", locked = true))

        val candidates = dao.ruleCandidates(uncategorisedOnly = 0)

        assertThat(candidates.map(TransactionEntity::id)).containsExactly("unlocked")
    }

    @Test
    fun ruleCandidates_neverIncludesASoftDeletedTransaction() = runTest {
        dao.upsert(transactionEntity(id = "gone", contentHash = "h1"))
        dao.softDelete("gone", deletedAt = 1_000L)

        assertThat(dao.ruleCandidates(uncategorisedOnly = 0)).isEmpty()
    }

    @Test
    fun ruleCandidates_includesACategorisedRow_whenNotScopedToUncategorised() = runTest {
        // Applying an edited rule has to be able to move a transaction that
        // already has a category -- that is the point of editing it (§1.6).
        seedCategories()
        dao.upsert(transactionEntity(id = "filed", contentHash = "h1", categoryId = "food"))

        val candidates = dao.ruleCandidates(uncategorisedOnly = 0)

        assertThat(candidates.map(TransactionEntity::id)).containsExactly("filed")
    }

    @Test
    fun ruleCandidates_scopedToUncategorised_excludesACategorisedRow() = runTest {
        // "Recategorise uncategorised transactions" fills in blanks and must
        // not rearrange categories the user already chose.
        seedCategories()
        dao.upsert(transactionEntity(id = "blank", contentHash = "h1"))
        dao.upsert(transactionEntity(id = "filed", contentHash = "h2", categoryId = "food"))

        val candidates = dao.ruleCandidates(uncategorisedOnly = 1)

        assertThat(candidates.map(TransactionEntity::id)).containsExactly("blank")
    }

    @Test
    fun lockedRuleMatchCandidates_returnsOnlyLockedRows_scopedTheSameWay() = runTest {
        // The two queries partition the same population: what one returns the
        // other must not, or the preview would count a row twice.
        seedCategories()
        dao.upsert(transactionEntity(id = "unlocked", contentHash = "h1"))
        dao.upsert(transactionEntity(id = "locked", contentHash = "h2", categoryId = "food", locked = true))

        val locked = dao.lockedRuleMatchCandidates(uncategorisedOnly = 0)

        assertThat(locked.map(TransactionEntity::id)).containsExactly("locked")
        assertThat(dao.ruleCandidates(uncategorisedOnly = 0).map(TransactionEntity::id))
            .containsExactly("unlocked")
    }

    @Test
    fun lockedRuleMatchCandidates_excludesASoftDeletedRow() = runTest {
        seedCategories()
        dao.upsert(transactionEntity(id = "locked", contentHash = "h1", categoryId = "food", locked = true))
        dao.softDelete("locked", deletedAt = 1_000L)

        assertThat(dao.lockedRuleMatchCandidates(uncategorisedOnly = 0)).isEmpty()
    }

    /**
     * Room enables foreign key constraints by default, so a transaction can
     * only carry a category_id that exists. Only these tests need it -- the
     * de-duplication tests above leave every row uncategorised.
     */
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

    private suspend fun allTransactions(): List<TransactionEntity> =
        dao.observeFiltered(categoryIds = emptyList(), categoryCount = 0, hasDateWindow = 0, from = "", to = "")
            .first()

    private fun transactionEntity(
        id: String,
        contentHash: String,
        importBatchId: String? = null,
        categoryId: String? = null,
        locked: Boolean = false,
        amountMinor: Long = -45000,
        date: String = "2026-01-05",
    ): TransactionEntity = TransactionEntity(
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
        categoryLockedByUser = locked,
        contentHash = contentHash,
        importBatchId = importBatchId,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
