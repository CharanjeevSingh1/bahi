package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.SystemCategoryIds

/**
 * The three ids other modules reason about by name come from
 * SystemCategoryIds in :core:model, so a feature can reference them without
 * duplicating a string literal across a module boundary. The rest stay here.
 *
 * Fixed ids are the entire idempotency mechanism for seeding: CategoryDao
 * .insertAllIgnoringConflicts only inserts a row when its id is absent, so
 * renaming or recolouring one of these afterwards makes every later seed call
 * a no-op for that id. Never change an id here -- that orphans the existing
 * row and reseeds a duplicate under the new id.
 */
internal val systemCategories: List<Category> = listOf(
    Category(id = "food", name = "Food", colorArgb = 0xFFEF5350.toInt(), iconKey = "restaurant", isSystemDefined = true),
    Category(id = "transport", name = "Transport", colorArgb = 0xFF42A5F5.toInt(), iconKey = "directions_car", isSystemDefined = true),
    Category(id = "rent", name = "Rent", colorArgb = 0xFF8D6E63.toInt(), iconKey = "home", isSystemDefined = true),
    Category(id = "utilities", name = "Utilities", colorArgb = 0xFFFFCA28.toInt(), iconKey = "bolt", isSystemDefined = true),
    Category(id = "groceries", name = "Groceries", colorArgb = 0xFF66BB6A.toInt(), iconKey = "shopping_cart", isSystemDefined = true),
    Category(id = "health", name = "Health", colorArgb = 0xFFEC407A.toInt(), iconKey = "favorite", isSystemDefined = true),
    Category(id = "shopping", name = "Shopping", colorArgb = 0xFFAB47BC.toInt(), iconKey = "shopping_bag", isSystemDefined = true),
    Category(id = "entertainment", name = "Entertainment", colorArgb = 0xFF7E57C2.toInt(), iconKey = "movie", isSystemDefined = true),
    Category(id = SystemCategoryIds.TRANSFERS, name = "Transfers", colorArgb = 0xFF26A69A.toInt(), iconKey = "swap_horiz", isSystemDefined = true),
    Category(id = SystemCategoryIds.INCOME, name = "Income", colorArgb = 0xFF9CCC65.toInt(), iconKey = "payments", isSystemDefined = true),
    Category(id = "fees", name = "Fees", colorArgb = 0xFF78909C.toInt(), iconKey = "receipt_long", isSystemDefined = true),
    Category(id = SystemCategoryIds.UNCATEGORISED, name = "Uncategorised", colorArgb = 0xFFBDBDBD.toInt(), iconKey = "help_outline", isSystemDefined = true),
)
