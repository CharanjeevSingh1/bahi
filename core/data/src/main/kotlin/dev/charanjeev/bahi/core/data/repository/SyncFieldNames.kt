package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.SyncTable

/**
 * The column names [toFieldMap] carries for [table] -- what a merge policy in
 * `:core:sync` has to cover, without exposing the entity that produced them.
 *
 * `:core:sync`'s field-coverage test (docs/sync-design.md §5.4) needs this
 * set to catch a column `ConflictResolver` forgot, but Room entities never
 * leave `:core:data` (CLAUDE.md rule 3) -- `core:data`'s
 * `implementation(projects.core.database)` enforces that at compile time, so
 * `:core:sync` has no entity type on its classpath to reflect over even if it
 * wanted to. This wraps the one function that already knows the answer
 * ([toFieldMap]) rather than hand-listing the columns a second time, which is
 * exactly the kind of second copy that drifts from the first.
 *
 * The entity instances below are never read back; only the resulting
 * payload's keys are. Their field values are placeholders.
 */
fun syncedFieldNames(table: SyncTable): Set<String> = when (table) {
    SyncTable.CATEGORIES -> toFieldMap(
        CategoryEntity(
            id = "placeholder",
            name = "",
            parentId = null,
            colorArgb = 0,
            iconKey = "",
            isSystemDefined = false,
        ),
    ).keys

    SyncTable.TRANSACTIONS -> toFieldMap(
        TransactionEntity(
            id = "placeholder",
            amountMinor = 0,
            currencyCode = "",
            date = "",
            description = "",
            merchant = null,
            categoryId = null,
            accountId = "",
            source = "",
            notes = null,
            categoryLockedByUser = false,
            contentHash = "",
            createdAt = 0,
            updatedAt = 0,
        ),
    ).keys

    SyncTable.BUDGETS -> toFieldMap(
        BudgetEntity(
            id = "placeholder",
            categoryId = "",
            yearMonth = "",
            limitMinor = 0,
            currencyCode = "",
            createdAt = 0,
            updatedAt = 0,
        ),
    ).keys

    SyncTable.CATEGORY_RULES -> toFieldMap(
        CategoryRuleEntity(
            id = "placeholder",
            categoryId = "",
            merchantContains = "",
            priority = 0,
            createdAt = 0,
            updatedAt = 0,
        ),
    ).keys
}
