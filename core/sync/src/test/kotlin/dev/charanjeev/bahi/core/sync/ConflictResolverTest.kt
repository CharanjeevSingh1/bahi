package dev.charanjeev.bahi.core.sync

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

/**
 * The merge rules in docs/sync-design.md §5, exercised directly against JSON
 * payloads rather than entities -- the resolver never sees a Room entity
 * (CLAUDE.md rule 3), so neither does this test.
 */
class ConflictResolverTest {

    private val resolver = DefaultConflictResolver()

    private fun payload(
        amountMinor: Long = -45_000,
        description: String = "Coffee Shop",
        categoryId: String? = "food",
        categoryLockedByUser: Boolean = false,
        notes: String? = "coffee",
    ): JsonObject = buildJsonObject {
        put("amount_minor", JsonPrimitive(amountMinor))
        put("currency_code", JsonPrimitive("INR"))
        put("date", JsonPrimitive("2026-01-05"))
        put("description", JsonPrimitive(description))
        put("merchant", JsonPrimitive("Blue Tokai"))
        put("category_id", JsonPrimitive(categoryId))
        put("account_id", JsonPrimitive("acct-1"))
        put("source", JsonPrimitive("MANUAL"))
        put("notes", notes?.let { JsonPrimitive(it) } ?: JsonNull)
        put("category_locked_by_user", JsonPrimitive(categoryLockedByUser))
        put("import_batch_id", JsonNull)
    }

    private fun side(payload: JsonObject?, updatedAt: Long = 1_000L, deviceId: String = "device-a") =
        MergeSide(payload, updatedAt, deviceId)

    // --- §5.2: per-field classification inside an already-conflicting row ---

    @Test
    fun `fields both sides agree on need no policy even with no base`() {
        val local = payload()
        val remote = payload()

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(local), side(remote), base = null)

        assertThat(result.payload).isEqualTo(local)
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `a field only one side changed is fast-forwarded without a policy`() {
        val base = payload(amountMinor = -1_000, notes = "original")
        val local = payload(amountMinor = -2_000, notes = "original")
        val remote = payload(amountMinor = -1_000, notes = "edited on tablet")

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(local), side(remote), base)

        assertThat(result.payload!!.getValue("amount_minor")).isEqualTo(JsonPrimitive(-2_000))
        assertThat(result.payload!!.getValue("notes")).isEqualTo(JsonPrimitive("edited on tablet"))
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `a genuinely conflicting field keeps the newer side and records the loser`() {
        val base = payload(description = "Coffee Shop")
        val local = payload(description = "Blue Tokai Coffee")
        val remote = payload(description = "Blue Tokai")

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000),
            side(remote, updatedAt = 2_000),
            base,
        )

        assertThat(result.payload!!.getValue("description")).isEqualTo(JsonPrimitive("Blue Tokai"))
        assertThat(result.conflicts).hasSize(1)
        assertThat(result.conflicts.single().field).isEqualTo("description")
        assertThat(result.conflicts.single().discardedValue).isEqualTo(JsonPrimitive("Blue Tokai Coffee"))
    }

    @Test
    fun `a tie in updated_at is broken by device id`() {
        val base = payload(description = "Coffee Shop")
        val local = payload(description = "Local Desc")
        val remote = payload(description = "Remote Desc")

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000, deviceId = "device-b"),
            side(remote, updatedAt = 1_000, deviceId = "device-a"),
            base,
        )

        // "device-a" < "device-b" lexicographically -> remote wins.
        assertThat(result.payload!!.getValue("description")).isEqualTo(JsonPrimitive("Remote Desc"))
    }

    // --- §5.5: notes ---

    @Test
    fun `notes -- the longer text wins when one side's edit is a superset of the other's`() {
        val base = payload(notes = "with Ann")
        val local = payload(notes = "with Ann, split the bill")
        val remote = payload(notes = "with Ann, split")

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(local), side(remote), base)

        assertThat(result.payload!!.getValue("notes")).isEqualTo(JsonPrimitive("with Ann, split the bill"))
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `notes -- two divergent edits are kept, both, newest first, and recorded`() {
        val base = payload(notes = "with Ann")
        val local = payload(notes = "with Ann at Blue Tokai")
        val remote = payload(notes = "with Ann, she's back in town")

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000),
            side(remote, updatedAt = 2_000),
            base,
        )

        val merged = (result.payload!!.getValue("notes") as JsonPrimitive).content
        assertThat(merged).contains("with Ann, she's back in town")
        assertThat(merged).contains("with Ann at Blue Tokai")
        assertThat(merged.indexOf("with Ann, she's back in town"))
            .isLessThan(merged.indexOf("with Ann at Blue Tokai"))
        assertThat(result.conflicts.map { it.field }).containsExactly("notes")
    }

    @Test
    fun `notes -- one side clearing the note falls to the tiebreak rather than the substring rule`() {
        val base = payload(notes = "with Ann")
        val local = payload(notes = null)
        val remote = payload(notes = "with Ann, running late")

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000),
            side(remote, updatedAt = 2_000),
            base,
        )

        assertThat(result.payload!!.getValue("notes")).isEqualTo(JsonPrimitive("with Ann, running late"))
        assertThat(result.conflicts.single().field).isEqualTo("notes")
    }

    // --- §5.4: category_id / category_locked_by_user ---

    @Test
    fun `category -- a locked side beats an unlocked side even when the unlocked side is newer`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val local = payload(categoryId = "food", categoryLockedByUser = true)
        val remote = payload(categoryId = "transport", categoryLockedByUser = false)

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000),
            side(remote, updatedAt = 9_000),
            base,
        )

        assertThat(result.payload!!.getValue("category_id")).isEqualTo(JsonPrimitive("food"))
        assertThat(result.payload!!.getValue("category_locked_by_user")).isEqualTo(JsonPrimitive(true))
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `category -- both locked to the same category is not a conflict`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val local = payload(categoryId = "food", categoryLockedByUser = true)
        val remote = payload(categoryId = "food", categoryLockedByUser = true)

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(local), side(remote), base)

        assertThat(result.payload!!.getValue("category_id")).isEqualTo(JsonPrimitive("food"))
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `category -- both locked to different categories falls to the tiebreak and is recorded`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val local = payload(categoryId = "food", categoryLockedByUser = true)
        val remote = payload(categoryId = "groceries", categoryLockedByUser = true)

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 1_000),
            side(remote, updatedAt = 5_000),
            base,
        )

        assertThat(result.payload!!.getValue("category_id")).isEqualTo(JsonPrimitive("groceries"))
        assertThat(result.payload!!.getValue("category_locked_by_user")).isEqualTo(JsonPrimitive(true))
        assertThat(result.conflicts.map { it.field }).containsExactly("category_id")
    }

    @Test
    fun `category -- two unlocked guesses that disagree also falls to the tiebreak`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val local = payload(categoryId = "food", categoryLockedByUser = false)
        val remote = payload(categoryId = "dining", categoryLockedByUser = false)

        val result = resolver.resolve(
            SyncTable.TRANSACTIONS,
            side(local, updatedAt = 5_000),
            side(remote, updatedAt = 1_000),
            base,
        )

        assertThat(result.payload!!.getValue("category_id")).isEqualTo(JsonPrimitive("food"))
        assertThat(result.payload!!.getValue("category_locked_by_user")).isEqualTo(JsonPrimitive(false))
        assertThat(result.conflicts.map { it.field }).containsExactly("category_id")
    }

    @Test
    fun `category -- a rule firing on the other device before it has synced is fast-forwarded`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val local = payload(categoryId = "food", categoryLockedByUser = true) // locked by hand
        val remote = payload(categoryId = "uncategorised", categoryLockedByUser = false) // untouched

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(local), side(remote), base)

        assertThat(result.payload!!.getValue("category_id")).isEqualTo(JsonPrimitive("food"))
        assertThat(result.payload!!.getValue("category_locked_by_user")).isEqualTo(JsonPrimitive(true))
        assertThat(result.conflicts).isEmpty()
    }

    // --- §5.3: deletion versus edit ---

    @Test
    fun `deletion vs edit -- the edit wins and the row survives, unrecorded`() {
        val base = payload(notes = "original")
        val edited = payload(notes = "edited on the tablet")

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(null), side(edited), base)

        assertThat(result.payload).isEqualTo(edited)
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `deletion vs edit -- a rule's own guess does not resurrect the row`() {
        val base = payload(categoryId = "uncategorised", categoryLockedByUser = false)
        val ruleEdited = payload(categoryId = "food", categoryLockedByUser = false)

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(null), side(ruleEdited), base)

        assertThat(result.payload).isNull()
        assertThat(result.conflicts).isEmpty()
    }

    /**
     * Found while building slice 6's two-device harness: an "edited" side
     * that is actually unchanged since [base] is not a concurrent edit at
     * all -- it is §5.2's fast-forward row, and the delete should win
     * outright. Before this test existed, an unchanged side and a genuinely
     * edited side both fell into "keep the edited payload", which meant a
     * delete that had already been pulled and fast-forwarded on one device
     * came back to life the moment that device's *own* unchanged copy was
     * compared against the delete on a later sync.
     */
    @Test
    fun `deletion vs edit -- an unchanged side is not an edit, and the deletion wins`() {
        val base = payload(notes = "original")

        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(null), side(base), base)

        assertThat(result.payload).isNull()
        assertThat(result.conflicts).isEmpty()
    }

    @Test
    fun `deletion vs edit -- both sides deleting the row is not a conflict`() {
        val result = resolver.resolve(SyncTable.TRANSACTIONS, side(null), side(null), payload())

        assertThat(result.payload).isNull()
        assertThat(result.conflicts).isEmpty()
    }
}
