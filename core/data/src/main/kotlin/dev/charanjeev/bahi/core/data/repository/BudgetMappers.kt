package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth

/**
 * Entities never leave the data layer. Keeping the mapping in one file makes
 * the boundary obvious and gives the mapping its own unit test.
 */
internal fun toDomain(entity: BudgetEntity): Budget = Budget(
    id = entity.id,
    categoryId = entity.categoryId,
    month = YearMonth.parse(entity.yearMonth),
    limit = Money(entity.limitMinor),
    currencyCode = entity.currencyCode,
)

/**
 * [createdAt] is passed in rather than read off the model: the domain [Budget]
 * carries no timestamps -- they're row bookkeeping, not something a feature
 * sets -- so the repository supplies both, preserving the original createdAt
 * when it's updating a row that already exists.
 */
internal fun toEntity(
    model: Budget,
    createdAt: Long,
    updatedAt: Long,
): BudgetEntity = BudgetEntity(
    id = model.id,
    categoryId = model.categoryId,
    yearMonth = model.month.toString(),
    limitMinor = model.limit.minorUnits,
    currencyCode = model.currencyCode,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
