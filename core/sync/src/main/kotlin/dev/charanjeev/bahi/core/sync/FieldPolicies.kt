package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.model.FieldResolution
import dev.charanjeev.bahi.core.model.FieldResolution.CATEGORY_LOCK_TIEBREAK
import dev.charanjeev.bahi.core.model.FieldResolution.MERGE
import dev.charanjeev.bahi.core.model.FieldResolution.USER_PROMPT
import dev.charanjeev.bahi.core.model.SyncTable

/**
 * What [DefaultConflictResolver] does with each synced column, when both
 * devices genuinely changed it (docs/sync-design.md §5.2's fourth row).
 *
 * Every column [dev.charanjeev.bahi.core.data.repository.syncedFieldNames]
 * returns for a table has to have an entry here -- `FieldPolicyCoverageTest`
 * fails the build otherwise, with the missing column's name in the failure.
 * That is the mechanism §5.4 asks for: a column added to an entity without
 * anyone deciding how it merges does not silently default to picking a side,
 * it fails a test.
 */
private val TRANSACTION_FIELD_POLICIES: Map<String, FieldResolution> = mapOf(
    "amount_minor" to USER_PROMPT,
    "currency_code" to USER_PROMPT,
    "date" to USER_PROMPT,
    "description" to USER_PROMPT,
    "merchant" to USER_PROMPT,
    // Resolved jointly with category_locked_by_user below, not independently
    // -- see CATEGORY_LOCK_TIEBREAK and DefaultConflictResolver.resolveCategoryPair.
    "category_id" to CATEGORY_LOCK_TIEBREAK,
    "account_id" to USER_PROMPT,
    "source" to USER_PROMPT,
    "notes" to MERGE,
    "category_locked_by_user" to CATEGORY_LOCK_TIEBREAK,
    "import_batch_id" to USER_PROMPT,
)

private val CATEGORY_FIELD_POLICIES: Map<String, FieldResolution> = mapOf(
    "name" to USER_PROMPT,
    "parent_id" to USER_PROMPT,
    "color_argb" to USER_PROMPT,
    "icon_key" to USER_PROMPT,
    "is_system_defined" to USER_PROMPT,
)

private val BUDGET_FIELD_POLICIES: Map<String, FieldResolution> = mapOf(
    // category_id and year_month are the row's identity since v5
    // (`budget:<category>:<month>`, docs/sync-design.md §3.2) -- two devices
    // never disagree on them for the same row id, so this is unreachable in
    // practice. USER_PROMPT is a safe default, not a claim anything relies on it.
    "category_id" to USER_PROMPT,
    "year_month" to USER_PROMPT,
    "limit_minor" to USER_PROMPT,
    "currency_code" to USER_PROMPT,
)

private val CATEGORY_RULE_FIELD_POLICIES: Map<String, FieldResolution> = mapOf(
    "category_id" to USER_PROMPT,
    "merchant_contains" to USER_PROMPT,
    // Concurrent reordering is accepted garbling, not resolved (D10, §6.5):
    // budgets-design §1.5's (priority, id) total order keeps rule evaluation
    // deterministic even when two devices pick different numbers for the same
    // rule, so this field gets the same tiebreak as everything else.
    "priority" to USER_PROMPT,
)

internal fun fieldPoliciesFor(table: SyncTable): Map<String, FieldResolution> = when (table) {
    SyncTable.TRANSACTIONS -> TRANSACTION_FIELD_POLICIES
    SyncTable.CATEGORIES -> CATEGORY_FIELD_POLICIES
    SyncTable.BUDGETS -> BUDGET_FIELD_POLICIES
    SyncTable.CATEGORY_RULES -> CATEGORY_RULE_FIELD_POLICIES
}
