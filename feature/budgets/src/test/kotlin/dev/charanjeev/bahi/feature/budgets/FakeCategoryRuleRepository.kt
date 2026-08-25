package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake carrying a small transaction table, so preview and
 * apply behave rather than returning whatever a test told them to. A fake
 * that let each test dictate its own preview count would assert that the
 * ViewModel can display a number, not that it shows the user the number the
 * write is actually going to produce.
 *
 * The matching here is a deliberately simplified stand-in for the real
 * engine, which is `internal` to :core:data and covered by its own tests --
 * substring, case-insensitive, first rule wins, locked rows excluded. The
 * blank-needle guard is mirrored too: a fake that matched everything for a
 * blank rule would hide the exact bug the UI guard exists to prevent.
 */
class FakeCategoryRuleRepository(
    initialRules: List<CategoryRule> = emptyList(),
) : CategoryRuleRepository {

    private val backing = MutableStateFlow(initialRules.associateBy { it.id })

    private val transactions = mutableListOf<Transaction>()

    /** Records every rule handed to [upsert], so a test can assert what was persisted. */
    val upserted = mutableListOf<CategoryRule>()
    val applied = mutableListOf<RuleApplicationPreview>()
    var reorderedTo: List<String>? = null
        private set
    val deleted = mutableListOf<String>()

    fun seedTransactions(vararg transaction: Transaction) {
        transactions += transaction
    }

    fun transaction(id: String): Transaction? = transactions.firstOrNull { it.id == id }

    override fun observeRules(): Flow<List<CategoryRule>> =
        backing.map { rules -> rules.values.sortedWith(compareBy({ it.priority }, { it.id })) }

    override suspend fun rules(): List<CategoryRule> =
        backing.value.values.sortedWith(compareBy({ it.priority }, { it.id }))

    override suspend fun upsert(rule: CategoryRule) {
        // The real repository throws on a blank needle; mirroring that is what
        // lets a ViewModel test prove the guard above it holds.
        require(rule.merchantContains.isNotBlank()) {
            "A rule's merchantContains cannot be blank -- it would match every transaction."
        }
        upserted += rule
        backing.value = backing.value + (rule.id to rule)
    }

    override suspend fun delete(id: String) {
        deleted += id
        backing.value = backing.value - id
    }

    override suspend fun reorder(orderedIds: List<String>) {
        reorderedTo = orderedIds
        backing.value = backing.value.mapValues { (id, rule) ->
            rule.copy(priority = orderedIds.indexOf(id).takeIf { it >= 0 } ?: rule.priority)
        }
    }

    override suspend fun previewApplyToExisting(rule: CategoryRule): RuleApplicationPreview =
        preview(listOf(rule), uncategorisedOnly = false)

    override suspend fun previewRecategoriseUncategorised(): RuleApplicationPreview =
        preview(rules(), uncategorisedOnly = true)

    override suspend fun apply(preview: RuleApplicationPreview): Int {
        applied += preview
        var changed = 0
        preview.assignments.forEach { (id, categoryId) ->
            val index = transactions.indexOfFirst { it.id == id }
            if (index < 0) return@forEach
            // applyRuleCategory's WHERE clause, by hand: a row locked since
            // the preview was taken is refused, and the count says so.
            if (transactions[index].categoryLockedByUser) return@forEach
            transactions[index] = transactions[index].copy(categoryId = categoryId)
            changed++
        }
        return changed
    }

    private fun preview(rules: List<CategoryRule>, uncategorisedOnly: Boolean): RuleApplicationPreview {
        val ordered = rules
            .filter { it.merchantContains.isNotBlank() }
            .sortedWith(compareBy({ it.priority }, { it.id }))
        fun match(transaction: Transaction): CategoryRule? = ordered.firstOrNull {
            transaction.description.uppercase().contains(it.merchantContains.trim().uppercase())
        }

        val inScope = transactions.filter { !uncategorisedOnly || it.categoryId == null }
        val assignments = inScope
            .filterNot { it.categoryLockedByUser }
            .mapNotNull { transaction ->
                val rule = match(transaction) ?: return@mapNotNull null
                if (rule.categoryId == transaction.categoryId) null else transaction.id to rule.categoryId
            }
            .toMap()
        val lockedSkipped = inScope.count { transaction ->
            transaction.categoryLockedByUser && match(transaction)?.categoryId != transaction.categoryId &&
                match(transaction) != null
        }
        return RuleApplicationPreview(assignments, lockedSkipped)
    }
}
