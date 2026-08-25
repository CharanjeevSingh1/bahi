package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.model.CategoryRule

/**
 * Entities never leave the data layer. Keeping the mapping in one file makes
 * the boundary obvious and gives the mapping its own unit test.
 */
internal fun toDomain(entity: CategoryRuleEntity): CategoryRule = CategoryRule(
    id = entity.id,
    categoryId = entity.categoryId,
    merchantContains = entity.merchantContains,
    priority = entity.priority,
)

/** See BudgetMappers' toEntity for why the timestamps are parameters. */
internal fun toEntity(
    model: CategoryRule,
    createdAt: Long,
    updatedAt: Long,
): CategoryRuleEntity = CategoryRuleEntity(
    id = model.id,
    categoryId = model.categoryId,
    merchantContains = model.merchantContains,
    priority = model.priority,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
