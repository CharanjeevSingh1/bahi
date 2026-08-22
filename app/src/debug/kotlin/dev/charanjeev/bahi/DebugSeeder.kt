package dev.charanjeev.bahi

import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Debug-build-only sample data. Slice 3 (manual add) and CSV import (M2) are
 * the real ways transactions get onto the device and neither exists yet, so
 * this is what puts something on screen for development. It goes through
 * TransactionRepository.upsert like any real write, and it never ships: see
 * the no-op twin of this function in src/release, which is what a release
 * build actually compiles.
 */
fun seedTransactionsForDevelopment(repository: TransactionRepository, scope: CoroutineScope) {
    scope.launch {
        if (repository.observeTransactions().first().isNotEmpty()) return@launch
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        sampleTransactions(today).forEach { repository.upsert(it) }
    }
}

private fun sampleTransactions(today: LocalDate): List<Transaction> {
    val now = Clock.System.now()
    fun daysAgo(days: Int) = LocalDate.fromEpochDays(today.toEpochDays() - days)

    fun sample(id: String, amountMinor: Long, date: LocalDate, description: String, categoryId: String) =
        Transaction(
            id = id,
            amount = Money(amountMinor),
            currencyCode = "INR",
            date = date,
            description = description,
            merchant = null,
            categoryId = categoryId,
            accountId = "acct-1",
            source = TransactionSource.MANUAL,
            createdAt = now,
            updatedAt = now,
        )

    // Ids matching SystemCategories so the chip resolves to a real, coloured
    // category rather than "Uncategorised" -- categories are seeded on every
    // launch, so these are always present by the time this runs.
    return listOf(
        sample("seed-1", -45_00, daysAgo(0), "BLUE TOKAI COFFEE", "food"),
        sample("seed-2", -129_00, daysAgo(0), "UBER TRIP", "transport"),
        sample("seed-3", -3_200_00, daysAgo(1), "BIGBASKET GROCERIES", "groceries"),
        sample("seed-4", 150_000_00, daysAgo(1), "SALARY", "income"),
        sample("seed-5", -25_000_00, daysAgo(1), "RENT", "rent"),
        sample("seed-6", -999_00, daysAgo(3), "NETFLIX", "entertainment"),
        sample("seed-7", -840_00, daysAgo(3), "ELECTRICITY BILL", "utilities"),
        sample("seed-8", -1_500_00, daysAgo(10), "PVR CINEMAS", "entertainment"),
        sample("seed-9", -6_800_00, daysAgo(35), "AMAZON SHOPPING", "shopping"),
        sample("seed-10", -3_200_00, daysAgo(35), "APOLLO PHARMACY", "health"),
    )
}
