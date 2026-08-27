package dev.charanjeev.bahi.core.testing

import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

object TestData {

    fun transaction(
        id: String = "txn-1",
        amount: Money = Money(-45000),
        date: LocalDate = LocalDate(2026, 3, 14),
        description: String = "BLUE TOKAI COFFEE",
        categoryId: String? = null,
        source: TransactionSource = TransactionSource.MANUAL,
    ) = Transaction(
        id = id,
        amount = amount,
        currencyCode = "INR",
        date = date,
        description = description,
        merchant = null,
        categoryId = categoryId,
        accountId = "acct-1",
        source = source,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
