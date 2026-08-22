package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A hand-written fake, matching FakeTransactionRepository. The ViewModel only
 * reads categories to resolve a row's chip, so upsert/delete/seed are no-ops.
 */
class FakeCategoryRepository(
    initial: List<Category> = emptyList(),
) : CategoryRepository {

    private val backing = MutableSharedFlow<List<Category>>(replay = 1).apply { tryEmit(initial) }

    suspend fun emit(categories: List<Category>) = backing.emit(categories)

    override fun observeCategories(): Flow<List<Category>> = backing

    override suspend fun upsert(category: Category) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun seedSystemCategoriesIfNeeded() = Unit
}
