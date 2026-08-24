package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.model.CategoryRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Rules are fixed per test; nothing here edits them mid-import. */
class FakeCategoryRuleRepository(private var stored: List<CategoryRule> = emptyList()) : CategoryRuleRepository {

    override fun observeRules(): Flow<List<CategoryRule>> = flowOf(stored)
    override suspend fun rules(): List<CategoryRule> = stored
    override suspend fun upsert(rule: CategoryRule) {
        stored = stored.filterNot { it.id == rule.id } + rule
    }

    override suspend fun delete(id: String) {
        stored = stored.filterNot { it.id == id }
    }
}
