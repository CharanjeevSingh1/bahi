package dev.charanjeev.finflow.core.data.repository

import dev.charanjeev.finflow.core.database.entity.CategoryEntity
import dev.charanjeev.finflow.core.model.Category

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

internal fun toEntity(model: Category): CategoryEntity = CategoryEntity(
    id = model.id,
    name = model.name,
    parentId = model.parentId,
    colorArgb = model.colorArgb,
    iconKey = model.iconKey,
    isSystemDefined = model.isSystemDefined,
)
