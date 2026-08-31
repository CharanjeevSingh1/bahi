package dev.charanjeev.bahi.core.sync

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.syncedFieldNames
import dev.charanjeev.bahi.core.model.SyncTable
import org.junit.Test

/**
 * The guard docs/sync-design.md §5.4 asks for: a column with no merge policy
 * fails the build with the column's name in the failure, rather than silently
 * picking a side nobody chose. Every table's expected column set comes from
 * `:core:data`'s [syncedFieldNames] -- the same function
 * [dev.charanjeev.bahi.core.data.repository.toFieldMap] backs -- so there is
 * no hand-copied list here to drift from the entities.
 */
class FieldPolicyCoverageTest {

    @Test
    fun `every synced transaction column has a merge policy`() {
        assertThat(fieldPoliciesFor(SyncTable.TRANSACTIONS).keys)
            .containsExactlyElementsIn(syncedFieldNames(SyncTable.TRANSACTIONS))
    }

    @Test
    fun `every synced category column has a merge policy`() {
        assertThat(fieldPoliciesFor(SyncTable.CATEGORIES).keys)
            .containsExactlyElementsIn(syncedFieldNames(SyncTable.CATEGORIES))
    }

    @Test
    fun `every synced budget column has a merge policy`() {
        assertThat(fieldPoliciesFor(SyncTable.BUDGETS).keys)
            .containsExactlyElementsIn(syncedFieldNames(SyncTable.BUDGETS))
    }

    @Test
    fun `every synced category rule column has a merge policy`() {
        assertThat(fieldPoliciesFor(SyncTable.CATEGORY_RULES).keys)
            .containsExactlyElementsIn(syncedFieldNames(SyncTable.CATEGORY_RULES))
    }
}
