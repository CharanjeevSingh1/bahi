package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * The only surface features are allowed to touch for category data, matching
 * TransactionRepository's shape.
 */
interface CategoryRepository {

    fun observeCategories(): Flow<List<Category>>

    suspend fun upsert(category: Category)

    /**
     * Soft delete, and it takes the category's budgets and rules with it --
     * the cascade the foreign keys used to perform, which a soft delete no
     * longer fires. Transactions keep their `categoryId` and read as
     * uncategorised until the category comes back; see
     * `CategoryDao.softDeleteUserCategory`.
     *
     * No-op for a system category, cascade included: the guard is on the
     * category row itself.
     */
    suspend fun delete(id: String)

    /**
     * Inserts any system category whose id isn't already present. Safe to call
     * unconditionally -- first launch, every later launch, or after a reinstall
     * all take the same path -- because it never touches a row that already
     * exists, so a user's rename or recolour of a system category is never
     * overwritten.
     */
    suspend fun seedSystemCategoriesIfNeeded()
}
