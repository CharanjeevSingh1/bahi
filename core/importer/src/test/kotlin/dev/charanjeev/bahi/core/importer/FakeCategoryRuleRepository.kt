package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
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

    override suspend fun reorder(orderedIds: List<String>) {
        stored = stored.map { rule ->
            rule.copy(priority = orderedIds.indexOf(rule.id).takeIf { it >= 0 } ?: rule.priority)
        }
    }

    // Import-time categorisation goes through the importer's own path, not
    // these -- the preview-and-confirm flow is a user action on the rules
    // screens (docs/budgets-design.md §1.3, §1.6) and an import never asks.
    // Failing loudly beats returning an empty preview that would let a test
    // silently assert nothing.
    override suspend fun previewApplyToExisting(rule: CategoryRule): RuleApplicationPreview =
        throw UnsupportedOperationException("The importer never previews; it categorises rows it just inserted.")

    override suspend fun previewRecategoriseUncategorised(): RuleApplicationPreview =
        throw UnsupportedOperationException("The importer never previews; it categorises rows it just inserted.")

    override suspend fun apply(preview: RuleApplicationPreview): Int =
        throw UnsupportedOperationException("The importer writes through TransactionRepository.applyRuleCategories.")

    /** Not exercised: nothing under test here reaches the sync engine's push step. */
    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = emptyList()
    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean = false
}
