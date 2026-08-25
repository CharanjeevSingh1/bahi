package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.model.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A hand-written fake. Both rule ViewModels only ever read categories. */
class FakeCategoryRepository(
    initial: List<Category> = emptyList(),
) : CategoryRepository {

    private val backing = MutableStateFlow(initial)

    fun emit(categories: List<Category>) {
        backing.value = categories
    }

    override fun observeCategories(): Flow<List<Category>> = backing

    override suspend fun upsert(category: Category) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun seedSystemCategoriesIfNeeded() = Unit
}
