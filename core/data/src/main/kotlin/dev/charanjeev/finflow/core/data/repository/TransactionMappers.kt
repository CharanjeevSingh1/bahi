package dev.charanjeev.finflow.core.data.repository

import dev.charanjeev.finflow.core.database.entity.TransactionEntity
import dev.charanjeev.finflow.core.model.Money
import dev.charanjeev.finflow.core.model.Transaction
import dev.charanjeev.finflow.core.model.TransactionSource
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Entities never leave the data layer. Keeping the mapping in one file makes
 * the boundary obvious and gives the mapping its own unit test.
 */
internal fun toDomain(entity: TransactionEntity): Transaction = Transaction(
    id = entity.id,
    amount = Money(entity.amountMinor),
    currencyCode = entity.currencyCode,
    date = LocalDate.parse(entity.date),
    description = entity.description,
    merchant = entity.merchant,
    categoryId = entity.categoryId,
    accountId = entity.accountId,
    source = TransactionSource.valueOf(entity.source),
    notes = entity.notes,
    categoryLockedByUser = entity.categoryLockedByUser,
    createdAt = Instant.fromEpochMilliseconds(entity.createdAt),
    updatedAt = Instant.fromEpochMilliseconds(entity.updatedAt),
)

internal fun toEntity(model: Transaction): TransactionEntity = TransactionEntity(
    id = model.id,
    amountMinor = model.amount.minorUnits,
    currencyCode = model.currencyCode,
    date = model.date.toString(),
    description = model.description,
    merchant = model.merchant,
    categoryId = model.categoryId,
    accountId = model.accountId,
    source = model.source.name,
    notes = model.notes,
    categoryLockedByUser = model.categoryLockedByUser,
    contentHash = contentHashOf(model),
    createdAt = model.createdAt.toEpochMilliseconds(),
    updatedAt = model.updatedAt.toEpochMilliseconds(),
)

/**
 * Deliberately excludes id, category and notes: the same bank row re-imported
 * after the user has categorised it must still be recognised as a duplicate.
 */
internal fun contentHashOf(model: Transaction): String = listOf(
    model.accountId,
    model.date.toString(),
    model.amount.minorUnits.toString(),
    model.description.trim().uppercase(),
).joinToString(separator = "|").hashCode().toString()
