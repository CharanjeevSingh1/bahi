package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class OfflineFirstBudgetRepositoryTest {

    private val transactionDao = FakeTransactionDao()
    private val dao = FakeBudgetDao(transactionDao)
    private val clock = FixedClock(Instant.fromEpochMilliseconds(1_000))
    private val repository =
        OfflineFirstBudgetRepository(dao, transactionDao, clock, UnconfinedTestDispatcher())

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

    // --- observeMonthlyBudgets ---
    //
    // These prove the repository composes the two queries and resolves the
    // month to the right date window. They cannot prove the SQL means what it
    // says -- FakeBudgetDao aggregates by hand, so it agrees with whatever it
    // was written to agree with. BudgetDaoTest is where the query itself is
    // held to account, against real SQLite.

    private fun transaction(
        id: String,
        amountMinor: Long,
        date: String = "2026-08-14",
        categoryId: String? = "food",
    ) = TransactionEntity(
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
        categoryLockedByUser = false,
        contentHash = id,
        importBatchId = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `a budget with no transactions reports zero spend rather than being absent`() = runTest {
        repository.upsert(budget())

        repository.observeMonthlyBudgets(august).test {
            val monthly = awaitItem()
            // Present-with-zero, not missing. A budget dropping off the screen
            // because nothing has been spent against it yet is the failure the
            // LEFT JOIN exists to prevent.
            assertThat(monthly.budgets.map { it.budget.id }).containsExactly("budget-1")
            assertThat(monthly.budgets.single().spent).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `spend is the sum of that category's expenses in the month`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -45_000))
        transactionDao.upsert(transaction("t2", amountMinor = -120_000))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money(165_000))
        }
    }

    @Test
    fun `income filed under a budgeted category does not reduce that budget's spend`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("expense", amountMinor = -45_000))
        // A refund or a salary credit filed under the same category. Netting
        // it off would understate spending; the sign filter means the query
        // never sees the row at all (§2.2).
        transactionDao.upsert(transaction("income", amountMinor = 900_000))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money(45_000))
        }
    }

    @Test
    fun `a transaction in another category does not count toward this budget`() = runTest {
        repository.upsert(budget(categoryId = "food"))
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, categoryId = "transport"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `a transaction on the last day of the month counts toward it`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2026-08-31"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money(45_000))
        }
    }

    @Test
    fun `a transaction on the first day of the next month does not`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2026-09-01"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `a transaction on the day before the month starts does not`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2026-07-31"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `February in a leap year includes the 29th`() = runTest {
        // The window comes from YearMonth.dateRange's calendar arithmetic, so
        // a leap February is 29 days without anything here knowing that. A
        // hard-coded 28 would silently drop a day's spending once every four
        // years -- and this is the layer that would hide it, since the DAO
        // only ever sees the dates it is given.
        val february = YearMonth.of(2028, 2)
        repository.upsert(budget(month = february))
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2028-02-29"))

        repository.observeMonthlyBudgets(february).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money(45_000))
        }
    }

    @Test
    fun `a soft-deleted budget is not reported at all`() = runTest {
        repository.upsert(budget())
        repository.delete("budget-1")

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets).isEmpty()
        }
    }

    // --- the two months that must not render identically ---

    @Test
    fun `a month with no transactions reports zero spend and zero uncategorised`() = runTest {
        repository.upsert(budget())

        repository.observeMonthlyBudgets(august).test {
            val monthly = awaitItem()
            assertThat(monthly.budgets.map { it.spent }).containsExactly(Money.ZERO)
            assertThat(monthly.uncategorisedSpend).isEqualTo(Money.ZERO)
            assertThat(monthly.hasUncategorisedSpend).isFalse()
        }
    }

    @Test
    fun `a month of only uncategorised spending reports zero spend but a non-zero uncategorised total`() =
        runTest {
            repository.upsert(budget())
            transactionDao.upsert(transaction("t1", amountMinor = -620_000, categoryId = null))

            repository.observeMonthlyBudgets(august).test {
                val monthly = awaitItem()
                // Identical to the empty month on the budget rows -- correctly,
                // since uncategorised money can't be attributed to a category.
                // The uncategorised total is the entire difference between the
                // two states, which is why it is part of the same value rather
                // than something the screen fetches separately and might not.
                assertThat(monthly.budgets.map { it.spent }).containsExactly(Money.ZERO)
                assertThat(monthly.uncategorisedSpend).isEqualTo(Money(620_000))
                assertThat(monthly.hasUncategorisedSpend).isTrue()
            }
        }

    @Test
    fun `uncategorised income is not reported as uncategorised spending`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = 900_000, categoryId = null))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().uncategorisedSpend).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `uncategorised spending outside the month is not counted`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -620_000, categoryId = null, date = "2026-09-01"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().uncategorisedSpend).isEqualTo(Money.ZERO)
        }
    }

    @Test
    fun `categorising an uncategorised transaction moves it from the uncategorised line into its budget`() =
        runTest {
            repository.upsert(budget(categoryId = "food"))
            transactionDao.upsert(transaction("t1", amountMinor = -45_000, categoryId = null))

            repository.observeMonthlyBudgets(august).test {
                val before = awaitItem()
                assertThat(before.budgets.single().spent).isEqualTo(Money.ZERO)
                assertThat(before.uncategorisedSpend).isEqualTo(Money(45_000))

                // The write path a rule uses. No recompute call, no
                // invalidation: the queries observe `transactions`, so this
                // re-emits on its own (§3).
                transactionDao.applyRuleCategory(id = "t1", categoryId = "food", updatedAt = 2_000L)

                // The settled state, not the next emission. The two queries
                // re-run independently, so combine can put out one
                // intermediate frame first -- documented on
                // observeMonthlyBudgets. Asserting on awaitItem() here would
                // be asserting on which of the two won a race.
                val after = expectMostRecentItem()
                assertThat(after.budgets.single().spent).isEqualTo(Money(45_000))
                assertThat(after.uncategorisedSpend).isEqualTo(Money.ZERO)
            }
        }

    @Test
    fun `a rule reclassifying a transaction shifts spend between two budgets in the same month`() = runTest {
        // §3's case, stated precisely: Food and Groceries in the *same* month,
        // not two months. A rule changes category_id, never date.
        repository.upsert(budget(id = "budget-food", categoryId = "food"))
        repository.upsert(budget(id = "budget-groceries", categoryId = "groceries"))
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, categoryId = "food"))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.map { it.spent })
                .containsExactly(Money(45_000), Money.ZERO)
                .inOrder()

            transactionDao.applyRuleCategory(id = "t1", categoryId = "groceries", updatedAt = 2_000L)

            // Ordered by category id, so food first, groceries second.
            assertThat(expectMostRecentItem().budgets.map { it.spent })
                .containsExactly(Money.ZERO, Money(45_000))
                .inOrder()
        }
    }

    @Test
    fun `soft-deleting a transaction removes its spend from the budget`() = runTest {
        repository.upsert(budget())
        transactionDao.upsert(transaction("t1", amountMinor = -45_000))

        repository.observeMonthlyBudgets(august).test {
            assertThat(awaitItem().budgets.single().spent).isEqualTo(Money(45_000))

            transactionDao.softDelete("t1", deletedAt = 2_000L)

            assertThat(expectMostRecentItem().budgets.single().spent).isEqualTo(Money.ZERO)
        }
    }
}
