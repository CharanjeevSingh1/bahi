package dev.charanjeev.bahi.core.sync.convergence

import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.coroutines.flow.first

/**
 * What "converged" means for one table: every field a user can see, in a
 * canonical (sorted) order, so two devices that agree produce `equals()`
 * lists regardless of which one is compared first.
 *
 * [Transaction.createdAt] and a tombstone's exact `deleted_at` are
 * deliberately absent. Both are documented in docs/sync-design.md §4.1/§4.3
 * as facts about *when this device found out*, not about the row -- two
 * devices that independently create the same content-derived id keep their
 * own unrelated `createdAt`, and a device that applies a remote tombstone
 * stamps its own apply time, never a value carried on the wire. Comparing
 * either would fail convergence tests for rows that are, by this design's own
 * stated rules, correctly converged. Deletion itself still has to converge --
 * that is exactly what a tombstoned row's *absence* from this list proves,
 * since every repository already filters `deleted_at IS NOT NULL`.
 */
data class TransactionSnapshot(
    val id: String,
    val amountMinor: Long,
    val currencyCode: String,
    val date: String,
    val description: String,
    val merchant: String?,
    val categoryId: String?,
    val accountId: String,
    val source: String,
    val notes: String?,
    val categoryLockedByUser: Boolean,
)

data class CategorySnapshot(
    val id: String,
    val name: String,
    val parentId: String?,
    val colorArgb: Int,
    val iconKey: String,
    val isSystemDefined: Boolean,
)

data class BudgetSnapshot(
    val id: String,
    val categoryId: String,
    val yearMonth: String,
    val limitMinor: Long,
    val currencyCode: String,
)

data class CategoryRuleSnapshot(
    val id: String,
    val categoryId: String,
    val merchantContains: String,
    val priority: Int,
)

data class DatabaseDump(
    val transactions: List<TransactionSnapshot>,
    val categories: List<CategorySnapshot>,
    val budgets: List<BudgetSnapshot>,
    // Deliberately in evaluation order (CategoryRuleRepository.rules()'s own
    // contract: ascending priority, ties broken by id), not sorted by id --
    // scenario 14 (§10.2) is specifically that two devices agree on this
    // *order*, which sorting by id would hide a disagreement about.
    val categoryRules: List<CategoryRuleSnapshot>,
)

private fun Transaction.toSnapshot() = TransactionSnapshot(
    id = id,
    amountMinor = amount.minorUnits,
    currencyCode = currencyCode,
    date = date.toString(),
    description = description,
    merchant = merchant,
    categoryId = categoryId,
    accountId = accountId,
    source = source.name,
    notes = notes,
    categoryLockedByUser = categoryLockedByUser,
)

private fun Category.toSnapshot() = CategorySnapshot(
    id = id,
    name = name,
    parentId = parentId,
    colorArgb = colorArgb,
    iconKey = iconKey,
    isSystemDefined = isSystemDefined,
)

private fun BudgetEntity.toSnapshot() = BudgetSnapshot(
    id = id,
    categoryId = categoryId,
    yearMonth = yearMonth,
    limitMinor = limitMinor,
    currencyCode = currencyCode,
)

private fun CategoryRule.toSnapshot() = CategoryRuleSnapshot(
    id = id,
    categoryId = categoryId,
    merchantContains = merchantContains,
    priority = priority,
)

/**
 * Reads every synced table off [this] device in the canonical shape two
 * devices are asserted to agree on (docs/sync-design.md §10.1's
 * `assertConverged`).
 */
suspend fun SyncTestDevice.dump(): DatabaseDump = DatabaseDump(
    transactions = transactionRepository.observeTransactions().first().map { it.toSnapshot() }.sortedBy { it.id },
    categories = categoryRepository.observeCategories().first().map { it.toSnapshot() }.sortedBy { it.id },
    budgets = allBudgets().map { it.toSnapshot() }.sortedBy { it.id },
    categoryRules = categoryRuleRepository.rules().map { it.toSnapshot() },
)
