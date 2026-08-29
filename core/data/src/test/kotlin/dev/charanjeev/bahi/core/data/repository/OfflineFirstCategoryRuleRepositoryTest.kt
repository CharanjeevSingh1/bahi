package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class OfflineFirstCategoryRuleRepositoryTest {

    private val dao = FakeCategoryRuleDao()
    private val transactionDao = FakeTransactionDao()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(1_000))
    private val repository =
        OfflineFirstCategoryRuleRepository(dao, transactionDao, clock, UnconfinedTestDispatcher())

    private fun rule(
        id: String = "rule-1",
        categoryId: String = "food",
        merchantContains: String = "SWIGGY",
        priority: Int = 0,
    ) = CategoryRule(
        id = id,
        categoryId = categoryId,
        merchantContains = merchantContains,
        priority = priority,
    )

    @Test
    fun `reviving a deleted rule continues its revision rather than restarting`() = runTest {
        // Same shape as the category and budget cases: the revision read has
        // to see through the tombstone (docs/sync-design.md §4.3), or the
        // revived row claims to be brand new to a remote that has already
        // acknowledged version 7 of it.
        repository.upsert(rule())
        dao.upsert(dao.allRows().single().copy(remoteRevision = 7))
        repository.delete("rule-1")

        repository.upsert(rule(merchantContains = "ZOMATO"))

        val row = dao.allRows().single()
        assertThat(row.localRevision).isEqualTo(3)
        assertThat(row.remoteRevision).isEqualTo(7)
        assertThat(row.deletedAt).isNull()
    }

    @Test
    fun `rules are observed in evaluation order`() = runTest {
        repository.upsert(rule(id = "rule-late", priority = 10))
        repository.upsert(rule(id = "rule-early", priority = 1))

        repository.observeRules().test {
            assertThat(awaitItem().map { it.id }).containsExactly("rule-early", "rule-late").inOrder()
        }
    }

    @Test
    fun `equal priorities are ordered by id so evaluation order is total`() = runTest {
        repository.upsert(rule(id = "rule-b", priority = 5))
        repository.upsert(rule(id = "rule-a", priority = 5))

        repository.observeRules().test {
            assertThat(awaitItem().map { it.id }).containsExactly("rule-a", "rule-b").inOrder()
        }
    }

    @Test
    fun `two rules may share a merchant string pointing at different categories`() = runTest {
        // Deliberately allowed: §1.5 resolves this by priority rather than
        // rejecting it, so the repository must not treat the string as a key.
        repository.upsert(rule(id = "rule-1", merchantContains = "SWIGGY", categoryId = "food", priority = 0))
        repository.upsert(rule(id = "rule-2", merchantContains = "SWIGGY", categoryId = "groceries", priority = 1))

        repository.observeRules().test {
            assertThat(awaitItem()).hasSize(2)
        }
    }

    @Test
    fun `editing a rule replaces it rather than adding a second`() = runTest {
        repository.upsert(rule(merchantContains = "SWIGGY"))

        repository.upsert(rule(merchantContains = "ZOMATO"))

        repository.observeRules().test {
            assertThat(awaitItem().single().merchantContains).isEqualTo("ZOMATO")
        }
    }

    @Test
    fun `editing a rule preserves createdAt and bumps the revision`() = runTest {
        repository.upsert(rule())
        val created = dao.allRows().single()

        repository.upsert(rule(merchantContains = "ZOMATO"))

        val updated = dao.allRows().single()
        assertThat(updated.createdAt).isEqualTo(created.createdAt)
        assertThat(updated.localRevision).isEqualTo(created.localRevision + 1)
        assertThat(updated.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `deleting a rule tombstones it rather than removing the row`() = runTest {
        repository.upsert(rule())

        repository.delete("rule-1")

        repository.observeRules().test {
            assertThat(awaitItem()).isEmpty()
        }
        val row = dao.allRows().single()
        assertThat(row.deletedAt).isEqualTo(1_000)
        assertThat(row.pendingOperation).isEqualTo("DELETE")
    }

    // --- the blank-rule guard at creation ---

    @Test
    fun `upsert refuses a blank merchant string`() = runTest {
        // `contains("")` is true of every string. A blank rule saved here
        // would recategorise the user's whole history the next time any
        // trigger fires, so this refuses rather than storing it.
        val thrown = runCatching { repository.upsert(rule(merchantContains = "")) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `upsert refuses a merchant string that is only whitespace`() = runTest {
        // isBlank, not isEmpty: "   " reads as typed-something in the UI but
        // trims to the same catastrophic empty needle.
        val thrown = runCatching { repository.upsert(rule(merchantContains = "   ")) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a refused rule is not written at all`() = runTest {
        runCatching { repository.upsert(rule(merchantContains = "")) }

        assertThat(dao.allRows()).isEmpty()
    }

    // --- reorder ---

    @Test
    fun `reorder rewrites priorities to match list position`() = runTest {
        repository.upsert(rule(id = "a", merchantContains = "SWIGGY", priority = 0))
        repository.upsert(rule(id = "b", merchantContains = "ZOMATO", priority = 1))
        repository.upsert(rule(id = "c", merchantContains = "UBER", priority = 2))

        repository.reorder(listOf("c", "a", "b"))

        repository.observeRules().test {
            assertThat(awaitItem().map { it.id }).containsExactly("c", "a", "b").inOrder()
        }
    }

    @Test
    fun `reorder leaves priorities dense and zero-based so repeated reorders cannot drift`() = runTest {
        repository.upsert(rule(id = "a", merchantContains = "SWIGGY", priority = 40))
        repository.upsert(rule(id = "b", merchantContains = "ZOMATO", priority = 90))

        repository.reorder(listOf("b", "a"))

        assertThat(dao.allRows().sortedBy { it.priority }.map { it.priority }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `reorder marks the moved rules pending sync`() = runTest {
        // Priority decides which rule wins a conflict, so a reorder is a real
        // edit -- another device has to learn about it.
        repository.upsert(rule(id = "a", merchantContains = "SWIGGY"))
        val before = dao.allRows().single().localRevision

        repository.reorder(listOf("a"))

        val after = dao.allRows().single()
        assertThat(after.localRevision).isEqualTo(before + 1)
        assertThat(after.pendingOperation).isEqualTo("UPSERT")
    }

    // --- preview: what it says before anything is written ---

    private fun transactionEntity(
        id: String,
        description: String = "SWIGGY ORDER",
        categoryId: String? = null,
        locked: Boolean = false,
    ) = TransactionEntity(
        id = id,
        amountMinor = -45_000,
        currencyCode = "INR",
        date = "2026-08-14",
        description = description,
        merchant = null,
        categoryId = categoryId,
        accountId = "acct-1",
        source = "CSV_IMPORT",
        notes = null,
        categoryLockedByUser = locked,
        contentHash = id,
        importBatchId = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `previewApplyToExisting reports what would change without changing anything`() = runTest {
        transactionDao.upsert(transactionEntity("t1"))
        transactionDao.upsert(transactionEntity("t2"))

        val preview = repository.previewApplyToExisting(rule(merchantContains = "SWIGGY"))

        assertThat(preview.matchedCount).isEqualTo(2)
        // Nothing written yet -- the whole point of a preview.
        assertThat(transactionDao.entity("t1")?.categoryId).isNull()
        assertThat(transactionDao.entity("t2")?.categoryId).isNull()
    }

    @Test
    fun `previewApplyToExisting counts a locked transaction as skipped rather than matched`() = runTest {
        transactionDao.upsert(transactionEntity("unlocked"))
        transactionDao.upsert(transactionEntity("locked", categoryId = "shopping", locked = true))

        val preview = repository.previewApplyToExisting(rule(merchantContains = "SWIGGY"))

        // Two numbers, not one. "1 will change" on its own reads as wrong to
        // someone looking at two matching transactions.
        assertThat(preview.matchedCount).isEqualTo(1)
        assertThat(preview.lockedSkippedCount).isEqualTo(1)
        assertThat(preview.assignments.keys).containsExactly("unlocked")
    }

    @Test
    fun `previewApplyToExisting can move a transaction that already has a category`() = runTest {
        // The case §1.6 exists for: editing a rule to fix a mis-categorised
        // transaction has to be able to move it, not just fill in blanks.
        transactionDao.upsert(transactionEntity("t1", categoryId = "shopping"))

        val preview = repository.previewApplyToExisting(rule(categoryId = "food", merchantContains = "SWIGGY"))

        assertThat(preview.assignments).containsExactly("t1", "food")
    }

    @Test
    fun `previewApplyToExisting previews only the rule it was given`() = runTest {
        repository.upsert(rule(id = "other", categoryId = "transport", merchantContains = "UBER"))
        transactionDao.upsert(transactionEntity("swiggy", description = "SWIGGY ORDER"))
        transactionDao.upsert(transactionEntity("uber", description = "UBER TRIP"))

        val preview = repository.previewApplyToExisting(rule(id = "new", merchantContains = "SWIGGY"))

        // An unrelated saved rule must not inflate the count for the rule the
        // user is looking at.
        assertThat(preview.assignments.keys).containsExactly("swiggy")
    }

    @Test
    fun `previewRecategoriseUncategorised leaves already-categorised transactions alone`() = runTest {
        repository.upsert(rule(categoryId = "food", merchantContains = "SWIGGY"))
        transactionDao.upsert(transactionEntity("blank", categoryId = null))
        transactionDao.upsert(transactionEntity("filed", categoryId = "shopping"))

        val preview = repository.previewRecategoriseUncategorised()

        // "Fill in the blanks" must not quietly rearrange categories the user
        // already set, even unlocked ones.
        assertThat(preview.assignments.keys).containsExactly("blank")
    }

    @Test
    fun `previewRecategoriseUncategorised runs every rule, not one`() = runTest {
        repository.upsert(rule(id = "a", categoryId = "food", merchantContains = "SWIGGY"))
        repository.upsert(rule(id = "b", categoryId = "transport", merchantContains = "UBER"))
        transactionDao.upsert(transactionEntity("s", description = "SWIGGY ORDER"))
        transactionDao.upsert(transactionEntity("u", description = "UBER TRIP"))

        val preview = repository.previewRecategoriseUncategorised()

        assertThat(preview.assignments).containsExactly("s", "food", "u", "transport")
    }

    @Test
    fun `a preview that matches nothing is empty rather than a zero-count confirm`() = runTest {
        transactionDao.upsert(transactionEntity("t1", description = "RENT"))

        val preview = repository.previewApplyToExisting(rule(merchantContains = "SWIGGY"))

        assertThat(preview.isEmpty).isTrue()
        assertThat(preview.matchedCount).isEqualTo(0)
    }

    // --- apply: committing exactly what was previewed ---

    @Test
    fun `apply commits exactly the assignments the preview carried`() = runTest {
        transactionDao.upsert(transactionEntity("t1"))
        transactionDao.upsert(transactionEntity("t2"))
        val preview = repository.previewApplyToExisting(rule(categoryId = "food", merchantContains = "SWIGGY"))

        val changed = repository.apply(preview)

        assertThat(changed).isEqualTo(2)
        assertThat(transactionDao.entity("t1")?.categoryId).isEqualTo("food")
        assertThat(transactionDao.entity("t2")?.categoryId).isEqualTo("food")
    }

    @Test
    fun `apply reports fewer than previewed when a row was locked in between`() = runTest {
        transactionDao.upsert(transactionEntity("t1"))
        transactionDao.upsert(transactionEntity("t2"))
        val preview = repository.previewApplyToExisting(rule(categoryId = "food", merchantContains = "SWIGGY"))

        // The user categorised t2 by hand while the confirm dialog was open.
        transactionDao.upsert(transactionEntity("t2", categoryId = "shopping", locked = true))

        val changed = repository.apply(preview)

        // 1, not the 2 that were previewed. applyRuleCategory's WHERE clause
        // refuses the locked row, and the honest number is what gets reported.
        assertThat(changed).isEqualTo(1)
        assertThat(transactionDao.entity("t2")?.categoryId).isEqualTo("shopping")
    }

    @Test
    fun `apply does not touch a transaction the preview did not name`() = runTest {
        transactionDao.upsert(transactionEntity("matched"))
        transactionDao.upsert(transactionEntity("unmatched", description = "RENT"))
        val preview = repository.previewApplyToExisting(rule(categoryId = "food", merchantContains = "SWIGGY"))

        repository.apply(preview)

        assertThat(transactionDao.entity("unmatched")?.categoryId).isNull()
    }

    @Test
    fun `applying an empty preview writes nothing and reports zero`() = runTest {
        transactionDao.upsert(transactionEntity("t1", description = "RENT"))
        val preview = repository.previewApplyToExisting(rule(merchantContains = "SWIGGY"))

        assertThat(repository.apply(preview)).isEqualTo(0)
        assertThat(transactionDao.entity("t1")?.categoryId).isNull()
    }
}
