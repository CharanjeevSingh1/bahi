package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class OfflineFirstCategoryRuleRepositoryTest {

    private val dao = FakeCategoryRuleDao()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(1_000))
    private val repository = OfflineFirstCategoryRuleRepository(dao, clock, UnconfinedTestDispatcher())

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
}
