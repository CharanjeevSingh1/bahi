package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.model.Category

/**
 * Entities never leave the data layer. Keeping the mapping in one file makes
 * the boundary obvious and gives the mapping its own unit test.
 */
internal fun toDomain(entity: CategoryEntity): Category = Category(
    id = entity.id,
    name = entity.name,
    parentId = entity.parentId,
    colorArgb = entity.colorArgb,
    iconKey = entity.iconKey,
    isSystemDefined = entity.isSystemDefined,
)

/**
 * [updatedAt] is passed in rather than read off the model, matching
 * BudgetMappers.toEntity: the domain [Category] carries no timestamp, so the
 * repository supplies it.
 */
internal fun toEntity(model: Category, updatedAt: Long): CategoryEntity = CategoryEntity(
    id = model.id,
    name = model.name,
    parentId = model.parentId,
    colorArgb = model.colorArgb,
    iconKey = model.iconKey,
    isSystemDefined = model.isSystemDefined,
    updatedAt = updatedAt,
)
