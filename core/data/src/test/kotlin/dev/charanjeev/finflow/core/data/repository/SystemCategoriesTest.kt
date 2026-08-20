package dev.charanjeev.finflow.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SystemCategoriesTest {

    @Test
    fun `system category seed list has no duplicate ids`() {
        assertThat(systemCategories.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `every seeded category is marked system defined`() {
        assertThat(systemCategories.all { it.isSystemDefined }).isTrue()
    }

    /**
     * Guards the "never change an id" warning on [systemCategories]: changing
     * or removing one silently orphans an installed user's row instead of
     * failing anything, so the exact set has to be pinned down in a test.
     */
    @Test
    fun `system category ids match the expected fixed set`() {
        val expectedIds = setOf(
            "food",
            "transport",
            "rent",
            "utilities",
            "groceries",
            "health",
            "shopping",
            "entertainment",
            "transfers",
            "income",
            "fees",
            "uncategorised",
        )

        assertThat(systemCategories.map { it.id }.toSet()).isEqualTo(expectedIds)
    }
}
