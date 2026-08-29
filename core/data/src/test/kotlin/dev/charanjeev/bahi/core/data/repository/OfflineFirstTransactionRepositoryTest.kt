package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.ContentIdScheme
import dev.charanjeev.bahi.core.model.DateWindow
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.TransactionFilter
import dev.charanjeev.bahi.core.model.TransactionSource
import dev.charanjeev.bahi.core.model.contentDerivedId
import dev.charanjeev.bahi.core.model.contentHashOf
import dev.charanjeev.bahi.core.testing.FixedClock
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class OfflineFirstTransactionRepositoryTest {

    private val dao = FakeTransactionDao()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(DELETED_AT))
    private val repository = OfflineFirstTransactionRepository(dao, clock, UnconfinedTestDispatcher())

    private fun imported(id: String = "placeholder", description: String = "BLUE TOKAI COFFEE") =
        TestData.transaction(id = id, description = description, source = TransactionSource.CSV_IMPORT)

    private fun expectedId(description: String = "BLUE TOKAI COFFEE", occurrence: Int = 0) = contentDerivedId(
        ContentIdScheme.CURRENT,
        contentHashOf(
            scheme = ContentIdScheme.CURRENT,
            accountId = "acct-1",
            date = "2026-03-14",
            amountMinor = -45_000,
            description = description,
        ),
        occurrence,
    )

    // --- content-derived ids (docs/sync-design.md §3.1) ---

    @Test
    fun `importAll replaces an imported row's id with one derived from its content`() = runTest {
        repository.importAll(listOf(imported()))

        assertThat(dao.rows.value.keys).containsExactly(expectedId())
    }

    @Test
    fun `importAll leaves a manual row's id alone`() = runTest {
        // Two devices are not going to independently type the same transaction
        // and mean one row, so there is nothing for a derived id to converge.
        repository.importAll(listOf(TestData.transaction(id = "typed-by-hand")))

        assertThat(dao.rows.value.keys).containsExactly("typed-by-hand")
    }

    @Test
    fun `two identical imported rows are numbered so both survive`() = runTest {
        // The case csv-import-design §4 was fixed for: two genuinely identical
        // coffees are two transactions, not one duplicated.
        val result = repository.importAll(listOf(imported("a"), imported("b")))

        assertThat(result.insertedCount).isEqualTo(2)
        assertThat(dao.rows.value.keys).containsExactly(expectedId(occurrence = 0), expectedId(occurrence = 1))
    }

    @Test
    fun `two devices importing the same statement derive the same ids`() = runTest {
        // The whole point. Without this the two devices hold 2N rows with 2N
        // ids, nothing downstream can tell they are duplicates, and
        // de-duplication never runs again after import time.
        val otherDevice = OfflineFirstTransactionRepository(
            FakeTransactionDao(),
            FixedClock(Instant.fromEpochMilliseconds(DELETED_AT)),
            UnconfinedTestDispatcher(),
        )
        val statement = listOf(imported("phone-1"), imported("phone-2", description = "ELECTRICITY"))

        repository.importAll(statement)
        otherDevice.importAll(listOf(imported("tablet-1"), imported("tablet-2", description = "ELECTRICITY")))

        assertThat(dao.rows.value.keys).containsExactlyElementsIn(
            statement.map { expectedId(it.description) },
        )
    }

    @Test
    fun `re-importing the same statement adds nothing and changes no id`() = runTest {
        repository.importAll(listOf(imported()))

        val second = repository.importAll(listOf(imported()))

        assertThat(second.insertedCount).isEqualTo(0)
        assertThat(dao.rows.value.keys).containsExactly(expectedId())
    }

    @Test
    fun `an id from an unknown scheme version survives an import`() = runTest {
        // Re-keying it to h1 would split the row from every other device
        // holding it, which is the one-way door the version prefix avoids.
        repository.importAll(listOf(imported(id = "h2:deadbeef#0")))

        assertThat(dao.rows.value.keys).containsExactly("h2:deadbeef#0")
    }

    // --- revision bookkeeping (docs/sync-design.md §4.3) ---

    @Test
    fun `a newly created transaction is queued for the next push`() = runTest {
        // Transactions were the odd one out: update, softDelete,
        // undoSoftDelete and softDeleteBatch all mark the row, and upsert --
        // the create path -- wrote toEntity's defaults. A transaction the user
        // typed would have been invisible to pendingChanges and never pushed
        // at all, which is a row that exists on one device forever.
        repository.upsert(TestData.transaction(id = "a"))

        assertThat(dao.pendingChanges().map { it.id }).containsExactly("a")
        assertThat(dao.entity("a")?.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `editing through upsert bumps the revision rather than resetting it`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))

        repository.upsert(TestData.transaction(id = "a", description = "OTHER"))

        assertThat(dao.entity("a")?.localRevision).isEqualTo(2)
    }

    @Test
    fun `reviving a deleted transaction continues its revision rather than restarting`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        dao.markSynced(id = "a", remoteRevision = 7, expectedLocalRevision = 1)
        repository.delete("a")

        repository.upsert(TestData.transaction(id = "a", description = "OTHER"))

        val entity = dao.entity("a")
        assertThat(entity?.localRevision).isEqualTo(3)
        assertThat(entity?.remoteRevision).isEqualTo(7)
        assertThat(entity?.deletedAt).isNull()
    }

    @Test
    fun `delete sets a pending DELETE and a tombstone`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))

        repository.delete("a")

        val entity = dao.entity("a")
        // The exact instant, not merely non-null: deleted_at is what sync
        // orders a deletion against, so a repository free to invent its own
        // "now" is a repository whose tombstones can't be reasoned about.
        assertThat(entity?.deletedAt).isEqualTo(DELETED_AT)
        assertThat(entity?.pendingOperation).isEqualTo("DELETE")
    }

    @Test
    fun `undoing an import tombstones the batch at the injected instant`() = runTest {
        val result = repository.importAll(
            listOf(TestData.transaction(id = "a"), TestData.transaction(id = "b", description = "OTHER")),
        )

        repository.undoImport(result.batchId)

        assertThat(dao.entity("a")?.deletedAt).isEqualTo(DELETED_AT)
        assertThat(dao.entity("b")?.deletedAt).isEqualTo(DELETED_AT)
    }

    @Test
    fun `undo after delete clears the tombstone and re-asserts the row to sync`() = runTest {
        // Not NULL: NULL means "in sync with remote", which would be false if
        // the DELETE this undoes had already been pushed -- the remote would
        // keep the deletion and the row would vanish again on the next sync.
        repository.upsert(TestData.transaction(id = "a"))
        repository.delete("a")

        repository.undoDelete("a")

        val entity = dao.entity("a")
        assertThat(entity?.deletedAt).isNull()
        assertThat(entity?.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `undo bumps the local revision so sync notices the row changed again`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        repository.delete("a")
        val revisionAfterDelete = dao.entity("a")!!.localRevision

        repository.undoDelete("a")

        assertThat(dao.entity("a")!!.localRevision).isGreaterThan(revisionAfterDelete)
    }

    @Test
    fun `update bumps the local revision and marks the row pending UPSERT`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        val revisionAfterCreate = dao.entity("a")!!.localRevision

        repository.update(TestData.transaction(id = "a", description = "RENAMED"))

        val entity = dao.entity("a")!!
        assertThat(entity.localRevision).isGreaterThan(revisionAfterCreate)
        assertThat(entity.pendingOperation).isEqualTo("UPSERT")
        assertThat(entity.description).isEqualTo("RENAMED")
    }

    @Test
    fun `update is a no-op for an id that doesn't exist`() = runTest {
        repository.update(TestData.transaction(id = "missing"))

        assertThat(dao.entity("missing")).isNull()
    }

    // --- Import batches and undo ---

    @Test
    fun `importAll stamps every inserted row with the same generated batch id`() = runTest {
        val result = repository.importAll(
            listOf(TestData.transaction(id = "a"), TestData.transaction(id = "b")),
        )

        assertThat(result.insertedCount).isEqualTo(2)
        assertThat(dao.entity("a")?.importBatchId).isEqualTo(result.batchId)
        assertThat(dao.entity("b")?.importBatchId).isEqualTo(result.batchId)
    }

    @Test
    fun `undoImport removes only rows from that batch, not an unrelated one`() = runTest {
        // Distinct descriptions -- same content, same hash, would make the
        // second import's row read as a duplicate of the first's and never
        // get written at all, which isn't what this test is about.
        val firstBatch = repository.importAll(listOf(TestData.transaction(id = "a", description = "Coffee Shop")))
        val secondBatch = repository.importAll(listOf(TestData.transaction(id = "b", description = "Electricity Bill")))

        val removedCount = repository.undoImport(firstBatch.batchId)

        assertThat(removedCount).isEqualTo(1)
        assertThat(dao.entity("a")?.deletedAt).isNotNull()
        assertThat(dao.entity("b")?.deletedAt).isNull()
        assertThat(dao.entity("b")?.importBatchId).isEqualTo(secondBatch.batchId)
    }

    @Test
    fun `undoImport is a soft delete -- it tombstones, it doesn't erase`() = runTest {
        val batch = repository.importAll(listOf(TestData.transaction(id = "a")))

        repository.undoImport(batch.batchId)

        val entity = dao.entity("a")
        assertThat(entity).isNotNull()
        assertThat(entity?.deletedAt).isNotNull()
        assertThat(entity?.pendingOperation).isEqualTo("DELETE")
    }

    @Test
    fun `a row hand-edited after import is left alone by a later undoImport, and the returned count says so`() = runTest {
        val batch = repository.importAll(
            listOf(
                TestData.transaction(id = "a", description = "Coffee Shop"),
                TestData.transaction(id = "b", description = "Electricity Bill"),
            ),
        )
        repository.update(TestData.transaction(id = "a", description = "Corrected Merchant Name"))

        // 1, not 2: "a" left the batch when it was edited, so only "b" is
        // actually tombstoned. Reporting 2 here is exactly the bug this
        // return value exists to prevent.
        val removedCount = repository.undoImport(batch.batchId)

        assertThat(removedCount).isEqualTo(1)
        val edited = dao.entity("a")
        assertThat(edited?.deletedAt).isNull()
        assertThat(edited?.description).isEqualTo("Corrected Merchant Name")
        assertThat(dao.entity("b")?.deletedAt).isNotNull()
    }

    // --- Auto-categorisation writes ---

    @Test
    fun `applyRuleCategories categorises unlocked rows and reports how many actually changed`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        repository.upsert(TestData.transaction(id = "b", description = "OTHER"))

        val changed = repository.applyRuleCategories(mapOf("a" to "food", "b" to "transport"))

        assertThat(changed).isEqualTo(2)
        assertThat(dao.entity("a")?.categoryId).isEqualTo("food")
        assertThat(dao.entity("b")?.categoryId).isEqualTo("transport")
    }

    @Test
    fun `applyRuleCategories never overwrites a category the user locked`() = runTest {
        repository.upsert(
            TestData.transaction(id = "a", categoryId = "shopping").copy(categoryLockedByUser = true),
        )

        val changed = repository.applyRuleCategories(mapOf("a" to "food"))

        // 0, and the user's category stands. The repository doesn't check
        // this itself -- the DAO's WHERE clause does, which is the point.
        assertThat(changed).isEqualTo(0)
        assertThat(dao.entity("a")?.categoryId).isEqualTo("shopping")
    }

    @Test
    fun `applyRuleCategories marks changed rows pending sync at the injected instant`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))

        repository.applyRuleCategories(mapOf("a" to "food"))

        val entity = dao.entity("a")!!
        assertThat(entity.updatedAt).isEqualTo(DELETED_AT)
        assertThat(entity.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `applyRuleCategories keeps a categorised row in its import batch, unlike a hand edit`() = runTest {
        val batch = repository.importAll(listOf(TestData.transaction(id = "a")))

        repository.applyRuleCategories(mapOf("a" to "food"))

        // Still undoable as part of the import: a rule categorising a row is
        // not the user taking ownership of it.
        assertThat(repository.undoImport(batch.batchId)).isEqualTo(1)
    }

    @Test
    fun `applyRuleCategories with nothing to do is a no-op`() = runTest {
        assertThat(repository.applyRuleCategories(emptyMap())).isEqualTo(0)
    }

    // --- Filtering: a query, not the caller filtering the returned list ---

    @Test
    fun `no filter returns everything`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 14)))
        repository.upsert(TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 1, 1)))

        val result = repository.observeTransactions(TransactionFilter.NONE).first()

        assertThat(result.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `category filter returns only matching categories, any date`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 1, 1)))
        repository.upsert(TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 6, 1)))
        repository.upsert(TestData.transaction(id = "c", categoryId = "groceries", date = LocalDate(2026, 6, 1)))

        val result = repository.observeTransactions(
            TransactionFilter(categoryIds = setOf("food", "groceries")),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a", "c")
    }

    @Test
    fun `date window filter returns only transactions inside the window, any category`() = runTest {
        repository.upsert(TestData.transaction(id = "a", date = LocalDate(2026, 3, 1)))
        repository.upsert(TestData.transaction(id = "b", date = LocalDate(2026, 3, 31)))
        repository.upsert(TestData.transaction(id = "c", date = LocalDate(2026, 4, 1)))

        val result = repository.observeTransactions(
            TransactionFilter(dateWindow = DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31))),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `category and date window compose -- both must match, not either`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 14)))
        // Right category, wrong month.
        repository.upsert(TestData.transaction(id = "b", categoryId = "food", date = LocalDate(2026, 4, 14)))
        // Right month, wrong category.
        repository.upsert(TestData.transaction(id = "c", categoryId = "rent", date = LocalDate(2026, 3, 14)))

        val result = repository.observeTransactions(
            TransactionFilter(
                categoryIds = setOf("food"),
                dateWindow = DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31)),
            ),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a")
    }

    @Test
    fun `a filter matching nothing returns an empty list, not an error`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food"))

        val result = repository.observeTransactions(TransactionFilter(categoryIds = setOf("nonexistent"))).first()

        assertThat(result).isEmpty()
    }

    @Test
    fun `filtered transactions still map amount and category through the domain model`() = runTest {
        repository.upsert(
            TestData.transaction(id = "a", categoryId = "food", amount = Money(-4500), date = LocalDate(2026, 3, 14)),
        )

        val result = repository.observeTransactions(TransactionFilter(categoryIds = setOf("food"))).first()

        assertThat(result.single().amount).isEqualTo(Money(-4500))
    }

    private companion object {
        const val DELETED_AT = 1_700_000_000_000L
    }
}
