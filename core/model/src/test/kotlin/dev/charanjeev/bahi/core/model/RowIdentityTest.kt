package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RowIdentityTest {

    /**
     * A golden value, and the one test in this file that is allowed to look
     * like a change-detector. It is: changing the tuple, its separator, its
     * field order or the hash function is precisely what must not happen
     * silently, because every device that has already minted ids under the old
     * one keeps them and stops agreeing with every device that has not. If this
     * has to change, it changes by adding a scheme version, not by editing the
     * expected string.
     */
    @Test
    fun `h1 content hash is a fixed value for a fixed tuple`() {
        val hash = contentHashOf(
            scheme = ContentIdScheme.H1,
            accountId = "acct-1",
            date = "2026-01-05",
            amountMinor = -45_000,
            description = "Coffee Shop",
        )

        assertThat(hash).isEqualTo("e69daf8267b11c3689db7a3e6d95f3fb")
    }

    @Test
    fun `content hash ignores case and surrounding whitespace in the description`() {
        val canonical = contentHashOf(ContentIdScheme.H1, "acct-1", "2026-01-05", -45_000, "Coffee Shop")
        val messy = contentHashOf(ContentIdScheme.H1, "acct-1", "2026-01-05", -45_000, "  coffee shop  ")

        assertThat(messy).isEqualTo(canonical)
    }

    @Test
    fun `content hash separates fields so a shifted boundary is a different transaction`() {
        // Without a separator, ("acct-1", "2") and ("acct", "-12") would join
        // to the same string. The tuple is user-supplied text, so this is not
        // hypothetical -- an account id is whatever the app was told.
        val left = contentHashOf(ContentIdScheme.H1, "acct-1", "2026-01-05", -45_000, "A")
        val right = contentHashOf(ContentIdScheme.H1, "acct", "-12026-01-05", -45_000, "A")

        assertThat(left).isNotEqualTo(right)
    }

    @Test
    fun `content derived id carries the scheme prefix and the occurrence`() {
        val id = contentDerivedId(ContentIdScheme.H1, "abc123", occurrence = 2)

        assertThat(id).isEqualTo("h1:abc123#2")
    }

    @Test
    fun `schemeOf reads the prefix back`() {
        assertThat(ContentIdScheme.schemeOf("h1:abc123#0")).isEqualTo(ContentIdScheme.H1)
    }

    @Test
    fun `schemeOf returns null for a uuid`() {
        assertThat(ContentIdScheme.schemeOf("6f1c8b4e-2c2f-4a3d-9d21-3b0c9f2f7a11")).isNull()
    }

    @Test
    fun `an id from an unknown scheme is content derived but has no known scheme`() {
        // The two questions have to answer differently. A caller deciding
        // whether to re-key a row asks isContentDerived and leaves an h2 row
        // alone; a caller deciding how to *interpret* one asks schemeOf and
        // finds out it cannot. Conflating them is how the version prefix would
        // quietly stop being an escape hatch: an unrecognised id would look
        // like a UUID and get downgraded to h1 on the next import.
        assertThat(ContentIdScheme.isContentDerived("h2:abc123#0")).isTrue()
        assertThat(ContentIdScheme.schemeOf("h2:abc123#0")).isNull()
    }

    @Test
    fun `a uuid is not content derived`() {
        assertThat(ContentIdScheme.isContentDerived("6f1c8b4e-2c2f-4a3d-9d21-3b0c9f2f7a11")).isFalse()
    }

    @Test
    fun `a hash that merely looks like one is not content derived without the occurrence`() {
        assertThat(ContentIdScheme.isContentDerived("h1:abc123")).isFalse()
    }

    @Test
    fun `budget id is its natural key`() {
        val id = budgetIdFor("food", YearMonth.of(2026, 8))

        assertThat(id).isEqualTo("budget:food:2026-08")
    }

    @Test
    fun `two devices derive the same budget id from the same category and month`() {
        assertThat(budgetIdFor("food", YearMonth.of(2026, 8)))
            .isEqualTo(budgetIdFor("food", YearMonth.parse("2026-08")))
    }
}
