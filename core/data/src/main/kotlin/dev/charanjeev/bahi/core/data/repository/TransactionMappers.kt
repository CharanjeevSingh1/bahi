package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.ContentIdScheme
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.contentHashOf
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionSource
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

/**
 * [importBatchId] isn't on the domain [Transaction] -- like [contentHashOf],
 * it's import/dedup bookkeeping the data layer owns, not something a feature
 * reads or sets on a model it holds. The one caller that needs it,
 * [OfflineFirstTransactionRepository.importAll], passes it in directly.
 */
internal fun toEntity(model: Transaction, importBatchId: String? = null): TransactionEntity = TransactionEntity(
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
    importBatchId = importBatchId,
    createdAt = model.createdAt.toEpochMilliseconds(),
    updatedAt = model.updatedAt.toEpochMilliseconds(),
)

/**
 * Which fields decide identity, and the hash over them, both live in
 * `:core:model`'s RowIdentity -- the migration that rewrote these values
 * (`Migrations.MIGRATION_4_5`) has to compute them identically from raw
 * columns, and two implementations of "the same hash" is the kind of thing
 * that agrees right up until someone edits one of them.
 */
internal fun contentHashOf(model: Transaction): String = contentHashOf(
    scheme = ContentIdScheme.CURRENT,
    accountId = model.accountId,
    date = model.date.toString(),
    amountMinor = model.amount.minorUnits,
    description = model.description,
)
