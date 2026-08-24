package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class OfflineFirstBudgetRepositoryTest {

    private val dao = FakeBudgetDao()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(1_000))
    private val repository = OfflineFirstBudgetRepository(dao, clock, UnconfinedTestDispatcher())

    private val august = YearMonth.of(2026, 8)

    private fun budget(
        id: String = "budget-1",
        categoryId: String = "food",
        month: YearMonth = august,
        limit: Money = Money(800_000),
    ) = Budget(id = id, categoryId = categoryId, month = month, limit = limit, currencyCode = "INR")

    @Test
    fun `budget is observable in the month it covers`() = runTest {
        repository.upsert(budget())

        repository.observeBudgets(august).test {
            assertThat(awaitItem()).containsExactly(budget())
        }
    }

    @Test
    fun `budget is not observable in a month it does not cover`() = runTest {
        repository.upsert(budget())

        repository.observeBudgets(YearMonth.of(2026, 9)).test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `second upsert for the same category and month replaces the first rather than adding one`() = runTest {
        repository.upsert(budget(id = "budget-1", limit = Money(800_000)))

        // A caller creating a "new" budget for a category and month that
        // already has one: different id, same natural key. Without the
        // natural-key lookup this inserts a second row and the category
        // silently has two budgets for August.
        repository.upsert(budget(id = "budget-2", limit = Money(950_000)))

        repository.observeBudgets(august).test {
            val budgets = awaitItem()
            assertThat(budgets).hasSize(1)
            assertThat(budgets.single().limit).isEqualTo(Money(950_000))
            // The surviving row keeps the id it was created with -- the second
            // caller's id is discarded, not the row.
            assertThat(budgets.single().id).isEqualTo("budget-1")
        }
    }

    @Test
    fun `budgets for different categories in the same month coexist`() = runTest {
        repository.upsert(budget(id = "budget-food", categoryId = "food"))
        repository.upsert(budget(id = "budget-transport", categoryId = "transport"))

        repository.observeBudgets(august).test {
            assertThat(awaitItem().map { it.categoryId }).containsExactly("food", "transport")
        }
    }

    @Test
    fun `budgets for the same category in different months coexist`() = runTest {
        repository.upsert(budget(id = "budget-aug", month = august))
        repository.upsert(budget(id = "budget-sep", month = YearMonth.of(2026, 9)))

        repository.observeBudgets(august).test {
            assertThat(awaitItem().map { it.id }).containsExactly("budget-aug")
        }
    }

    @Test
    fun `deleting a budget tombstones it rather than removing the row`() = runTest {
        repository.upsert(budget())

        repository.delete("budget-1")

        repository.observeBudgets(august).test {
            assertThat(awaitItem()).isEmpty()
        }
        val row = dao.allRows().single()
        assertThat(row.deletedAt).isEqualTo(1_000)
        assertThat(row.pendingOperation).isEqualTo("DELETE")
    }

    @Test
    fun `a deleted budget does not block creating a new one for the same category and month`() = runTest {
        // The reason the invariant is enforced in the repository and not as a
        // UNIQUE index: the tombstone still occupies (category, month), so a
        // constraint would reject this insert with no cause the user can see.
        repository.upsert(budget(id = "budget-1"))
        repository.delete("budget-1")

        repository.upsert(budget(id = "budget-2", limit = Money(500_000)))

        repository.observeBudgets(august).test {
            val budgets = awaitItem()
            assertThat(budgets.map { it.id }).containsExactly("budget-2")
            assertThat(budgets.single().limit).isEqualTo(Money(500_000))
        }
    }

    @Test
    fun `updating a budget preserves createdAt and bumps the revision`() = runTest {
        repository.upsert(budget())
        val created = dao.allRows().single()

        repository.upsert(budget(id = "ignored", limit = Money(120_000)))

        val updated = dao.allRows().single()
        assertThat(updated.createdAt).isEqualTo(created.createdAt)
        assertThat(updated.localRevision).isEqualTo(created.localRevision + 1)
        assertThat(updated.pendingOperation).isEqualTo("UPSERT")
    }
}
