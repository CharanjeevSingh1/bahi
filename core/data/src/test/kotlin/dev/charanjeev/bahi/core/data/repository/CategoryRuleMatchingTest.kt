package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.testing.TestData
import org.junit.Test

class CategoryRuleMatchingTest {

    private fun rule(
        id: String = "rule-1",
        categoryId: String = "food",
        merchantContains: String = "SWIGGY",
        priority: Int = 0,
    ) = CategoryRule(id, categoryId, merchantContains, priority)

    private fun transaction(
        id: String = "txn-1",
        description: String = "SWIGGY ORDER",
        categoryId: String? = null,
        locked: Boolean = false,
        merchant: String? = null,
    ): Transaction = TestData.transaction(id = id, description = description, categoryId = categoryId)
        .copy(merchant = merchant, categoryLockedByUser = locked)

    // --- matching ---

    @Test
    fun `matches a substring anywhere in the description`() {
        val result = applyRules(listOf(rule()), listOf(transaction()))

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `matches a merchant buried in bank export noise`() {
        // The shape a real description actually takes -- the reason matching
        // is substring rather than equality.
        val result = applyRules(
            listOf(rule()),
            listOf(transaction(description = "UPI/SWIGGY*ORDER/BANGALORE/423891")),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `matching is case-insensitive in both directions`() {
        val lowercaseRule = applyRules(
            listOf(rule(merchantContains = "swiggy")),
            listOf(transaction(description = "SWIGGY ORDER")),
        )
        val lowercaseDescription = applyRules(
            listOf(rule(merchantContains = "SWIGGY")),
            listOf(transaction(description = "swiggy order")),
        )

        assertThat(lowercaseRule).containsExactly("txn-1", "food")
        assertThat(lowercaseDescription).containsExactly("txn-1", "food")
    }

    @Test
    fun `a padded rule string is trimmed before matching`() {
        val result = applyRules(listOf(rule(merchantContains = "  SWIGGY  ")), listOf(transaction()))

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `no rule matching leaves the transaction alone`() {
        val result = applyRules(
            listOf(rule(merchantContains = "ZOMATO")),
            listOf(transaction(description = "SWIGGY ORDER")),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `no rules and no candidates both produce nothing`() {
        assertThat(applyRules(emptyList(), listOf(transaction()))).isEmpty()
        assertThat(applyRules(listOf(rule()), emptyList())).isEmpty()
    }

    @Test
    fun `merchant is preferred over description when it is populated`() {
        // merchant is null for everything the app can currently produce, so
        // this pins the intended precedence before anything writes it.
        val result = applyRules(
            listOf(rule(merchantContains = "ZOMATO", categoryId = "food")),
            listOf(transaction(description = "SWIGGY ORDER", merchant = "ZOMATO")),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `description is ignored once merchant is populated`() {
        val result = applyRules(
            listOf(rule(merchantContains = "SWIGGY")),
            listOf(transaction(description = "SWIGGY ORDER", merchant = "ZOMATO")),
        )

        assertThat(result).isEmpty()
    }

    // --- conflict resolution (§1.5) ---

    @Test
    fun `the lowest priority rule wins when two rules match`() {
        val result = applyRules(
            listOf(
                rule(id = "rule-groceries", categoryId = "groceries", merchantContains = "SWIGGY", priority = 10),
                rule(id = "rule-food", categoryId = "food", merchantContains = "SWIGGY", priority = 1),
            ),
            listOf(transaction()),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `equal priorities are broken by id so the outcome is deterministic`() {
        // Passed in reverse id order on purpose: without the tie-break this
        // silently depends on the order the caller happened to supply.
        val result = applyRules(
            listOf(
                rule(id = "rule-b", categoryId = "groceries", priority = 5),
                rule(id = "rule-a", categoryId = "food", priority = 5),
            ),
            listOf(transaction()),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    @Test
    fun `a more specific rule only wins if the user gave it a lower priority`() {
        // Documents the actual behaviour rather than an assumed one: match
        // length is not a signal, priority is. "SWIGGY" at priority 0 beats
        // "SWIGGY INSTAMART" at 1 even though the latter is more specific.
        val result = applyRules(
            listOf(
                rule(id = "broad", categoryId = "food", merchantContains = "SWIGGY", priority = 0),
                rule(id = "specific", categoryId = "groceries", merchantContains = "SWIGGY INSTAMART", priority = 1),
            ),
            listOf(transaction(description = "SWIGGY INSTAMART ORDER")),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    // --- the constraint the whole feature exists to protect (§1.4) ---

    @Test
    fun `a locked transaction is never recategorised, even when passed in unfiltered`() {
        val result = applyRules(
            listOf(rule()),
            listOf(transaction(locked = true)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `a locked transaction does not stop an unlocked one being matched`() {
        val result = applyRules(
            listOf(rule()),
            listOf(transaction(id = "locked", locked = true), transaction(id = "unlocked")),
        )

        assertThat(result).containsExactly("unlocked", "food")
    }

    // --- what counts as a change ---

    @Test
    fun `a transaction already in the rule's category is not reported as a change`() {
        // Otherwise re-running the rules reports work it will not do, and the
        // preview count in §1.6 lies to the user.
        val result = applyRules(listOf(rule()), listOf(transaction(categoryId = "food")))

        assertThat(result).isEmpty()
    }

    @Test
    fun `a transaction in a different category is recategorised`() {
        val result = applyRules(listOf(rule()), listOf(transaction(categoryId = "shopping")))

        assertThat(result).containsExactly("txn-1", "food")
    }

    // --- the unbounded-damage case ---

    @Test
    fun `a blank rule matches nothing rather than everything`() {
        // `contains("")` is true for every string, so an empty rule that got
        // through would recategorise the user's entire history in one pass.
        val result = applyRules(
            listOf(rule(merchantContains = ""), rule(id = "rule-2", merchantContains = "   ")),
            listOf(transaction(id = "a"), transaction(id = "b", description = "ANYTHING AT ALL")),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `a blank rule does not shadow a real one that would have matched`() {
        val result = applyRules(
            listOf(
                rule(id = "blank", categoryId = "shopping", merchantContains = "", priority = 0),
                rule(id = "real", categoryId = "food", merchantContains = "SWIGGY", priority = 1),
            ),
            listOf(transaction()),
        )

        assertThat(result).containsExactly("txn-1", "food")
    }

    // --- countLockedMatches: the preview's "and these will be skipped" line ---

    @Test
    fun `counts a locked transaction the rule would otherwise have moved`() {
        val count = countLockedMatches(
            listOf(rule()),
            listOf(transaction(categoryId = "shopping", locked = true)),
        )

        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `does not count an unlocked transaction`() {
        // Handed the unlocked candidate set by mistake it counts nothing,
        // rather than double-reporting rows applyRules has already claimed.
        val count = countLockedMatches(
            listOf(rule()),
            listOf(transaction(categoryId = "shopping", locked = false)),
        )

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `does not count a locked transaction the rule does not match`() {
        val count = countLockedMatches(
            listOf(rule()),
            listOf(transaction(description = "RENT", categoryId = "housing", locked = true)),
        )

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `does not count a locked transaction already in the rule's category`() {
        // Nothing would have changed for this row even if it were unlocked,
        // so warning about it would overstate what the lock is protecting.
        val count = countLockedMatches(
            listOf(rule(categoryId = "food")),
            listOf(transaction(categoryId = "food", locked = true)),
        )

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `a blank rule counts no locked matches either`() {
        // The same unbounded-damage case as above, on the counting path:
        // a blank rule reporting "would have moved all 900 of your locked
        // transactions" is its own kind of alarming nonsense.
        val count = countLockedMatches(
            listOf(rule(merchantContains = "")),
            listOf(transaction(categoryId = "shopping", locked = true)),
        )

        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `counting uses the same priority order as applying`() {
        // Rule A wins for both functions, so the skipped count describes the
        // move that would actually have happened -- not a different one.
        val rules = listOf(
            rule(id = "a", categoryId = "food", merchantContains = "SWIGGY", priority = 0),
            rule(id = "b", categoryId = "groceries", merchantContains = "SWIGGY", priority = 1),
        )

        assertThat(applyRules(rules, listOf(transaction()))).containsExactly("txn-1", "food")
        // Locked and already in "food" -- rule A would not have changed it,
        // so it isn't reported as protected by the lock.
        assertThat(countLockedMatches(rules, listOf(transaction(categoryId = "food", locked = true))))
            .isEqualTo(0)
        // Locked and in "groceries" -- rule A *would* have moved it to food.
        assertThat(countLockedMatches(rules, listOf(transaction(categoryId = "groceries", locked = true))))
            .isEqualTo(1)
    }
}
