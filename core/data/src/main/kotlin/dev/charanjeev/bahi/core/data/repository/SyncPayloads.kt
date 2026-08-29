package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A row's synced fields, keyed by column name -- the payload carried by a
 * [dev.charanjeev.bahi.core.model.SyncOp] and stored as the merge base in
 * `sync_shadow` (docs/sync-design.md §4.2, §4.1).
 *
 * These are `internal` because they take entities, and entities never leave
 * `:core:data` (CLAUDE.md rule 3). `:core:sync` gets at them through a
 * repository, never directly.
 *
 * Only one direction lives here. The resolver reads base *values*
 * (`base["amount_minor"]`) and never needs an entity back, so a field map is
 * the whole answer for the shadow. Turning a merged field map back into a row
 * is what *apply* does, and apply is the engine's job (slice 5) -- it also has
 * to handle a partial map, which this has no opinion about.
 *
 * ### What is deliberately not in a payload
 *
 * **`local_revision`, `remote_revision`, `pending_operation`.** Local
 * bookkeeping. They describe this device's relationship to the remote, not the
 * row, and every device's copy is legitimately different.
 *
 * **`deleted_at`.** Deletion is expressed by a null payload, not by a field
 * (see [dev.charanjeev.bahi.core.model.SyncOp]). Carrying both would admit
 * "deleted, and here are its values."
 *
 * **`updated_at`.** Already a field of the op itself.
 *
 * **`created_at`, and this is the one that matters.** It is a fact about when
 * *this device* first held the row, not about the transaction. Two devices
 * that import the same statement a week apart produce byte-identical rows with
 * `created_at` a week apart -- and since v5 those rows have the same content-
 * derived id, so they meet on first sync. Include `created_at` and every one
 * of them is a field that differs with no base to attribute it to, which is a
 * recorded conflict per row (§5.6) on the first sync of a shared history.
 * Exclude it and those rows are identical, which is the case §4.1 resolves
 * with no policy and no conflict at all. It is resolved at apply as
 * `min(local, remote)` instead: the row was created when the first device
 * created it, and `min` converges whatever order the devices sync in.
 *
 * **`transactions.content_hash`.** Derived from four columns that are already
 * in the map, so it carries no information of its own and can only ever
 * manufacture a disagreement -- two devices on different id scheme versions
 * would differ on it while agreeing on everything it is computed from.
 */
internal fun toFieldMap(entity: TransactionEntity): JsonObject = buildJsonObject {
    put("amount_minor", JsonPrimitive(entity.amountMinor))
    put("currency_code", JsonPrimitive(entity.currencyCode))
    put("date", JsonPrimitive(entity.date))
    put("description", JsonPrimitive(entity.description))
    put("merchant", JsonPrimitive(entity.merchant))
    put("category_id", JsonPrimitive(entity.categoryId))
    put("account_id", JsonPrimitive(entity.accountId))
    put("source", JsonPrimitive(entity.source))
    put("notes", JsonPrimitive(entity.notes))
    put("category_locked_by_user", JsonPrimitive(entity.categoryLockedByUser))
    // Synced so that undoing an import on one device can undo it on the other
    // (§6.1). Null on any row that predates the column or was never imported.
    put("import_batch_id", JsonPrimitive(entity.importBatchId))
}

internal fun toFieldMap(entity: CategoryEntity): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(entity.name))
    put("parent_id", JsonPrimitive(entity.parentId))
    put("color_argb", JsonPrimitive(entity.colorArgb))
    put("icon_key", JsonPrimitive(entity.iconKey))
    // Seeded identically on every device (SystemCategories), so in practice
    // this never differs. Carried anyway: "never differs" is an assumption
    // about seeding, and dropping the column would make a device that seeded
    // differently silently agree.
    put("is_system_defined", JsonPrimitive(entity.isSystemDefined))
}

internal fun toFieldMap(entity: BudgetEntity): JsonObject = buildJsonObject {
    // category_id and year_month are already the row's id since v5
    // (`budget:<category>:<month>`). Carried rather than derived back out of
    // the key, because a payload that only makes sense once parsed against an
    // id format is a payload that breaks when the id format changes.
    put("category_id", JsonPrimitive(entity.categoryId))
    put("year_month", JsonPrimitive(entity.yearMonth))
    put("limit_minor", JsonPrimitive(entity.limitMinor))
    put("currency_code", JsonPrimitive(entity.currencyCode))
}

internal fun toFieldMap(entity: CategoryRuleEntity): JsonObject = buildJsonObject {
    put("category_id", JsonPrimitive(entity.categoryId))
    put("merchant_contains", JsonPrimitive(entity.merchantContains))
    put("priority", JsonPrimitive(entity.priority))
}
