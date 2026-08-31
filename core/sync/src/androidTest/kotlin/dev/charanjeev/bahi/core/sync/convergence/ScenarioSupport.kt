package dev.charanjeev.bahi.core.sync.convergence

import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** Builders and a per-test harness lifecycle, shared by every scenario and the property test. */

fun tx(
    id: String,
    amount: Long = -1_000,
    date: LocalDate = LocalDate(2026, 1, 5),
    description: String = "Coffee",
    merchant: String? = null,
    categoryId: String? = null,
    source: TransactionSource = TransactionSource.MANUAL,
    notes: String? = null,
    locked: Boolean = false,
    at: Instant,
) = Transaction(
    id = id,
    amount = Money(amount),
    currencyCode = "INR",
    date = date,
    description = description,
    merchant = merchant,
    categoryId = categoryId,
    accountId = "acct-1",
    source = source,
    notes = notes,
    categoryLockedByUser = locked,
    createdAt = at,
    updatedAt = at,
)

fun category(id: String, name: String = id, parentId: String? = null) = Category(
    id = id,
    name = name,
    parentId = parentId,
    colorArgb = 0xFF00FF,
    iconKey = "tag",
)

fun budget(id: String, categoryId: String, month: YearMonth, limitMinor: Long) = Budget(
    id = id,
    categoryId = categoryId,
    month = month,
    limit = Money(limitMinor),
    currencyCode = "INR",
)

fun rule(id: String, categoryId: String, merchantContains: String, priority: Int) = CategoryRule(
    id = id,
    categoryId = categoryId,
    merchantContains = merchantContains,
    priority = priority,
)

suspend fun SyncTestDevice.currentTransaction(id: String): Transaction =
    transactionRepository.observeTransaction(id).first()
        ?: error("device $deviceId has no transaction $id")

/** Every scenario and every property-test seed runs through this: build, run, always close. */
fun convergenceTest(block: suspend TwoDeviceHarness.() -> Unit) = runTest {
    val harness = TwoDeviceHarness()
    try {
        harness.block()
    } finally {
        harness.close()
    }
}
