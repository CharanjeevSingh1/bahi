package dev.charanjeev.finflow.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.finflow.core.model.Category
import org.junit.Test

class CategoryMappersTest {

    @Test
    fun `entity round-trips through domain unchanged`() {
        val category = Category(
            id = "food",
            name = "Food",
            parentId = null,
            colorArgb = 0xFFEF5350.toInt(),
            iconKey = "restaurant",
            isSystemDefined = true,
        )

        assertThat(toDomain(toEntity(category))).isEqualTo(category)
    }
}
