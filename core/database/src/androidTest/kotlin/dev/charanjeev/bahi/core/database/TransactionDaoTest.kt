package dev.charanjeev.bahi.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.dao.TransactionDao
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
        val firstImportedCount = dao.importBatch(firstImport)
        assertThat(firstImportedCount).isEqualTo(2)
        assertThat(allTransactions()).hasSize(2)

        // Step 2: the exact same file, re-imported. This is where presence-
        // based de-duplication already worked too -- both are recognised as
        // already present and skipped.
        val secondImport = listOf(
            transactionEntity(id = "t3", contentHash = coffeeHash),
            transactionEntity(id = "t4", contentHash = coffeeHash),
        )
        val secondImportedCount = dao.importBatch(secondImport)
        assertThat(secondImportedCount).isEqualTo(0)
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
        val thirdImportedCount = dao.importBatch(thirdImport)
        assertThat(thirdImportedCount).isEqualTo(1)
        assertThat(allTransactions()).hasSize(3)
    }

    private suspend fun allTransactions(): List<TransactionEntity> =
        dao.observeFiltered(categoryIds = emptyList(), categoryCount = 0, hasDateWindow = 0, from = "", to = "")
            .first()

    private fun transactionEntity(id: String, contentHash: String): TransactionEntity = TransactionEntity(
        id = id,
        amountMinor = -45000,
        currencyCode = "INR",
        date = "2026-01-05",
        description = "Coffee Shop",
        merchant = null,
        categoryId = null,
        accountId = "acct-1",
        source = "CSV_IMPORT",
        notes = null,
        categoryLockedByUser = false,
        contentHash = contentHash,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
