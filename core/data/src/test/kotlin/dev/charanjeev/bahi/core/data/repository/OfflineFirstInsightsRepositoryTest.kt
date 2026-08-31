package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.CategorySpend
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.MonthlyTotal
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OfflineFirstInsightsRepositoryTest {

    private val transactionDao = FakeTransactionDao()
    private val repository = OfflineFirstInsightsRepository(transactionDao)

    private val august = YearMonth.of(2026, 8)

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

    // --- observeHasAnyHistory ---

    @Test
    fun `no history is reported before any transaction exists`() = runTest {
        repository.observeHasAnyHistory().test {
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `history is reported as soon as any live transaction exists, income included`() = runTest {
        repository.observeHasAnyHistory().test {
            assertThat(awaitItem()).isFalse()

            transactionDao.upsert(transaction("salary", amountMinor = 900_000, categoryId = "income"))

            assertThat(expectMostRecentItem()).isTrue()
        }
    }

    @Test
    fun `deleting the only transaction reverts to no history`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -10_000))

        repository.observeHasAnyHistory().test {
            assertThat(awaitItem()).isTrue()

            transactionDao.softDelete("t1", deletedAt = 1_000L)

            assertThat(expectMostRecentItem()).isFalse()
        }
    }

    // --- observeCategoryBreakdown ---

    @Test
    fun `an empty month reports no category spend and no uncategorised spend`() = runTest {
        repository.observeCategoryBreakdown(august).test {
            val breakdown = awaitItem()
            assertThat(breakdown.categorySpend).isEmpty()
            assertThat(breakdown.uncategorisedSpend).isEqualTo(Money.ZERO)
            assertThat(breakdown.hasAnySpend).isFalse()
        }
    }

    @Test
    fun `spend is grouped and summed per category`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, categoryId = "food"))
        transactionDao.upsert(transaction("t2", amountMinor = -30_000, categoryId = "food"))
        transactionDao.upsert(transaction("t3", amountMinor = -100_000, categoryId = "transport"))

        repository.observeCategoryBreakdown(august).test {
            assertThat(awaitItem().categorySpend).containsExactly(
                CategorySpend("food", Money(75_000)),
                CategorySpend("transport", Money(100_000)),
            )
        }
    }

    @Test
    fun `income never contributes to the breakdown, regardless of its category`() = runTest {
        // The ₹150,000 salary case: a positive amount is excluded by sign,
        // not because "Income" is a special category id.
        transactionDao.upsert(transaction("salary", amountMinor = 15_000_000, categoryId = "income"))
        transactionDao.upsert(transaction("expense", amountMinor = -45_000, categoryId = "food"))

        repository.observeCategoryBreakdown(august).test {
            val breakdown = awaitItem()
            assertThat(breakdown.categorySpend).containsExactly(CategorySpend("food", Money(45_000)))
            assertThat(breakdown.totalSpend).isEqualTo(Money(45_000))
        }
    }

    @Test
    fun `uncategorised spend is reported separately, never folded into a category`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, categoryId = "food"))
        transactionDao.upsert(transaction("t2", amountMinor = -20_000, categoryId = null))

        repository.observeCategoryBreakdown(august).test {
            val breakdown = awaitItem()
            assertThat(breakdown.categorySpend).containsExactly(CategorySpend("food", Money(45_000)))
            assertThat(breakdown.uncategorisedSpend).isEqualTo(Money(20_000))
            assertThat(breakdown.totalSpend).isEqualTo(Money(65_000))
        }
    }

    // --- the two months that must not be conflated (§2.2) ---

    @Test
    fun `an empty month and an all-uncategorised month differ only in the uncategorised figure`() = runTest {
        repository.observeCategoryBreakdown(august).test {
            assertThat(awaitItem().hasAnySpend).isFalse()
        }

        transactionDao.upsert(transaction("t1", amountMinor = -620_000, categoryId = null))

        repository.observeCategoryBreakdown(august).test {
            val breakdown = awaitItem()
            assertThat(breakdown.categorySpend).isEmpty()
            assertThat(breakdown.hasAnySpend).isTrue()
            assertThat(breakdown.uncategorisedSpend).isEqualTo(Money(620_000))
        }
    }

    @Test
    fun `spend outside the month is not counted`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2026-09-01"))

        repository.observeCategoryBreakdown(august).test {
            assertThat(awaitItem().hasAnySpend).isFalse()
        }
    }

    // --- observeSpendTrend ---

    @Test
    fun `no history at all reports a single month with no comparison`() = runTest {
        repository.observeSpendTrend(august).test {
            val trend = awaitItem()
            assertThat(trend.months).containsExactly(MonthlyTotal(august, Money.ZERO))
            assertThat(trend.hasComparison).isFalse()
        }
    }

    @Test
    fun `a single month of history reports just that month`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -45_000, date = "2026-08-05"))

        repository.observeSpendTrend(august).test {
            val trend = awaitItem()
            assertThat(trend.months).containsExactly(MonthlyTotal(august, Money(45_000)))
            assertThat(trend.hasComparison).isFalse()
        }
    }

    @Test
    fun `prior months with history are zero-filled honestly, not omitted`() = runTest {
        // June had a transaction (so it "existed"), July had none at all, and
        // August has one. July's bar must read a real zero, not be missing.
        transactionDao.upsert(transaction("june", amountMinor = -10_000, date = "2026-06-10"))
        transactionDao.upsert(transaction("august", amountMinor = -20_000, date = "2026-08-10"))

        repository.observeSpendTrend(august, maxMonths = 6).test {
            val trend = awaitItem()
            assertThat(trend.months).containsExactly(
                MonthlyTotal(YearMonth.of(2026, 6), Money(10_000)),
                MonthlyTotal(YearMonth.of(2026, 7), Money.ZERO),
                MonthlyTotal(YearMonth.of(2026, 8), Money(20_000)),
            ).inOrder()
            assertThat(trend.hasComparison).isTrue()
        }
    }

    @Test
    fun `the window never reaches earlier than the app's first transaction`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -10_000, date = "2026-07-01"))

        repository.observeSpendTrend(august, maxMonths = 6).test {
            // Without the earliest-transaction floor this would start in
            // March -- five months back -- which the app has no history for.
            assertThat(awaitItem().months.map { it.month }).containsExactly(
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 8),
            ).inOrder()
        }
    }

    @Test
    fun `the window is capped at maxMonths even with much older history`() = runTest {
        transactionDao.upsert(transaction("old", amountMinor = -10_000, date = "2025-01-15"))
        transactionDao.upsert(transaction("recent", amountMinor = -20_000, date = "2026-08-10"))

        repository.observeSpendTrend(august, maxMonths = 3).test {
            assertThat(awaitItem().months.map { it.month }).containsExactly(
                YearMonth.of(2026, 6),
                YearMonth.of(2026, 7),
                YearMonth.of(2026, 8),
            ).inOrder()
        }
    }

    @Test
    fun `income does not appear in the trend either`() = runTest {
        transactionDao.upsert(transaction("salary", amountMinor = 900_000, date = "2026-08-01"))

        repository.observeSpendTrend(august).test {
            assertThat(awaitItem().months).containsExactly(MonthlyTotal(august, Money.ZERO))
        }
    }

    @Test
    fun `deleting the app's only transaction collapses the trend back to a single month`() = runTest {
        transactionDao.upsert(transaction("t1", amountMinor = -10_000, date = "2026-06-01"))

        repository.observeSpendTrend(august).test {
            assertThat(awaitItem().months).hasSize(3) // June, July, August

            transactionDao.softDelete("t1", deletedAt = 1_000L)

            assertThat(expectMostRecentItem().months).containsExactly(MonthlyTotal(august, Money.ZERO))
        }
    }
}
