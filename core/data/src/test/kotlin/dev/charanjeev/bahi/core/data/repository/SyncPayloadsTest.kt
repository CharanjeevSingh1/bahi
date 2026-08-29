package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.database.entity.BudgetEntity
import dev.charanjeev.bahi.core.database.entity.CategoryEntity
import dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.serialization.json.JsonNull
import org.junit.Test

/**
 * What a row looks like on the wire and in the merge base. These are the tests
 * that hold the exclusion list in SyncPayloads honest: every column left out is
 * left out for a stated reason, and a column added later that quietly joins the
 * map changes what two devices treat as a conflict.
 */
class SyncPayloadsTest {

    @Test
    fun `a transaction payload carries the fields a user can see and nothing else`() {
        assertThat(toFieldMap(transaction()).keys).containsExactly(
            "amount_minor", "currency_code", "date", "description", "merchant",
            "category_id", "account_id", "source", "notes",
            "category_locked_by_user", "import_batch_id",
        )
    }

    /**
     * The single most consequential exclusion, and the reason first sync is
     * survivable. Two devices that imported the same statement a week apart
     * hold the same row under the same content-derived id with `created_at` a
     * week apart. If that column were in the payload they would differ on it,
     * with no shadow to attribute the difference to -- a recorded conflict per
     * row on the very first sync, which is the list-nobody-reads failure
     * docs/sync-design.md §5.6 is trying to avoid. Excluded, they are
     * identical, and identical needs no policy at all.
     */
    @Test
    fun `two devices that imported the same row at different times produce the same payload`() {
        val phone = transaction(createdAt = 1_000L, updatedAt = 1_000L, localRevision = 1)
        val tablet = transaction(createdAt = 604_800_000L, updatedAt = 604_800_000L, localRevision = 4)

        assertThat(toFieldMap(phone)).isEqualTo(toFieldMap(tablet))
    }

    /**
     * `content_hash` is derived from four columns already in the map, so it
     * can only ever manufacture a disagreement between two devices that agree
     * on everything it is computed from.
     */
    @Test
    fun `a stale content hash does not change the payload`() {
        val current = transaction(contentHash = "e69daf8267b11c3689db7a3e6d95f3fb")
        val stale = transaction(contentHash = "-1740514521")

        assertThat(toFieldMap(current)).isEqualTo(toFieldMap(stale))
    }

    /**
     * A tombstone is a null payload, not a `deleted_at` field, so a soft-
     * deleted row's payload is indistinguishable from a live one's. That is
     * the point: the deletion is carried by the op, and a payload that also
     * carried it could contradict it.
     */
    @Test
    fun `a tombstoned row's payload is the same as a live one's`() {
        assertThat(toFieldMap(transaction(deletedAt = 5_000L)))
            .isEqualTo(toFieldMap(transaction(deletedAt = null)))
    }

    /**
     * A null field is present-and-null, not absent. The resolver reads a base
     * by key; a missing key means "this version had no such field" and a
     * JsonNull means "it had one and it was empty". Collapsing them would make
     * clearing a note look like a schema difference.
     */
    @Test
    fun `a null column is carried as json null rather than dropped`() {
        val payload = toFieldMap(transaction(merchant = null, notes = null))

        assertThat(payload["merchant"]).isEqualTo(JsonNull)
        assertThat(payload["notes"]).isEqualTo(JsonNull)
    }

    @Test
    fun `a category payload carries no timestamps and no bookkeeping`() {
        val category = CategoryEntity(
            id = "food",
            name = "Food",
            parentId = null,
            colorArgb = 0xFF00FF00.toInt(),
            iconKey = "restaurant",
            isSystemDefined = true,
            localRevision = 9,
            remoteRevision = 4,
            pendingOperation = "UPSERT",
            deletedAt = 12L,
        )

        assertThat(toFieldMap(category).keys)
            .containsExactly("name", "parent_id", "color_argb", "icon_key", "is_system_defined")
    }

    @Test
    fun `a budget payload carries its natural key alongside its limit`() {
        val budget = BudgetEntity(
            id = "budget:food:2026-08",
            categoryId = "food",
            yearMonth = "2026-08",
            limitMinor = 500_000,
            currencyCode = "INR",
            createdAt = 1L,
            updatedAt = 2L,
        )

        assertThat(toFieldMap(budget).keys)
            .containsExactly("category_id", "year_month", "limit_minor", "currency_code")
    }

    @Test
    fun `a rule payload carries its matcher and its priority`() {
        val rule = CategoryRuleEntity(
            id = "rule-1",
            categoryId = "food",
            merchantContains = "coffee",
            priority = 10,
            createdAt = 1L,
            updatedAt = 2L,
        )

        assertThat(toFieldMap(rule).keys)
            .containsExactly("category_id", "merchant_contains", "priority")
    }

    private fun transaction(
        merchant: String? = "Blue Tokai",
        notes: String? = "with Ann",
        contentHash: String = "e69daf8267b11c3689db7a3e6d95f3fb",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        localRevision: Long = 1,
        deletedAt: Long? = null,
    ) = TransactionEntity(
        id = "h1:e69daf8267b11c3689db7a3e6d95f3fb#0",
        amountMinor = -45_000,
        currencyCode = "INR",
        date = "2026-01-05",
        description = "Coffee Shop",
        merchant = merchant,
        categoryId = "food",
        accountId = "acct-1",
        source = "CSV_IMPORT",
        notes = notes,
        categoryLockedByUser = false,
        contentHash = contentHash,
        importBatchId = "batch-1",
        createdAt = createdAt,
        updatedAt = updatedAt,
        localRevision = localRevision,
        deletedAt = deletedAt,
    )
}
