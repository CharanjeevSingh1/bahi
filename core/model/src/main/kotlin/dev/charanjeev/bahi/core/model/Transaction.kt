package dev.charanjeev.bahi.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class Transaction(
    val id: String,
    val amount: Money,
    val currencyCode: String,
    val date: LocalDate,
    val description: String,
    val merchant: String?,
    val categoryId: String?,
    val accountId: String,
    val source: TransactionSource,
    val notes: String? = null,
    /** Set by the user; blocks the auto-categoriser from overwriting the choice. */
    val categoryLockedByUser: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isExpense: Boolean get() = amount.isNegative
}

enum class TransactionSource {
    MANUAL,
    CSV_IMPORT,
    SYNCED,
}
