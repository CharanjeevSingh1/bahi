package dev.charanjeev.finflow.core.testing

import dev.charanjeev.finflow.core.model.Money
import dev.charanjeev.finflow.core.model.Transaction
import dev.charanjeev.finflow.core.model.TransactionSource
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

object TestData {

    fun transaction(
        id: String = "txn-1",
        amount: Money = Money(-45000),
        date: LocalDate = LocalDate(2026, 3, 14),
        description: String = "BLUE TOKAI COFFEE",
        categoryId: String? = null,
    ) = Transaction(
        id = id,
        amount = amount,
        currencyCode = "INR",
        date = date,
        description = description,
        merchant = null,
        categoryId = categoryId,
        accountId = "acct-1",
        source = TransactionSource.MANUAL,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
