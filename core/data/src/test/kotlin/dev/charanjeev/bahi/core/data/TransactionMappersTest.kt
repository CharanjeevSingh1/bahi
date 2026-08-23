package dev.charanjeev.bahi.core.data

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.contentHashOf
import dev.charanjeev.bahi.core.data.repository.toEntity
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.TestData
import org.junit.Test

class TransactionMappersTest {

    @Test
    fun `toEntity defaults importBatchId to null for a plain upsert`() {
        assertThat(toEntity(TestData.transaction()).importBatchId).isNull()
    }

    @Test
    fun `toEntity carries the batch id through when one is supplied`() {
        val entity = toEntity(TestData.transaction(), importBatchId = "batch-1")

        assertThat(entity.importBatchId).isEqualTo("batch-1")
    }

    @Test
    fun `content hash ignores category so recategorised rows still dedupe`() {
        val uncategorised = TestData.transaction(categoryId = null)
        val categorised = uncategorised.copy(categoryId = "food")

        assertThat(contentHashOf(categorised)).isEqualTo(contentHashOf(uncategorised))
    }

    @Test
    fun `content hash ignores description casing and padding`() {
        val a = TestData.transaction(description = "blue tokai coffee ")
        val b = TestData.transaction(description = "BLUE TOKAI COFFEE")

        assertThat(contentHashOf(a)).isEqualTo(contentHashOf(b))
    }

    @Test
    fun `content hash distinguishes different amounts`() {
        val a = TestData.transaction(amount = Money(-45000))
        val b = TestData.transaction(amount = Money(-45001))

        assertThat(contentHashOf(a)).isNotEqualTo(contentHashOf(b))
    }
}
