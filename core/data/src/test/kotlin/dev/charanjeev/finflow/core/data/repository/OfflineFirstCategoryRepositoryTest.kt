package dev.charanjeev.finflow.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.finflow.core.model.Category
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OfflineFirstCategoryRepositoryTest {

    private val dao = FakeCategoryDao()
    private val repository = OfflineFirstCategoryRepository(dao, UnconfinedTestDispatcher())

    @Test
    fun `seeding on first launch inserts every system category`() = runTest {
        // Also covers reinstalls: a reinstall wipes Room along with the rest of
        // app-private storage, so the DAO backing a "reinstalled" repository
        // starts empty too -- indistinguishable from first launch here.
        repository.seedSystemCategoriesIfNeeded()

        repository.observeCategories().test {
            assertThat(awaitItem()).containsExactlyElementsIn(systemCategories)
        }
    }

    @Test
    fun `reseeding does not overwrite a system category the user renamed and recoloured`() = runTest {
        repository.seedSystemCategoriesIfNeeded()
        val customised = Category(
            id = "food",
            name = "Eating Out",
            colorArgb = 0xFF000000.toInt(),
            iconKey = "restaurant",
            isSystemDefined = true,
        )
        repository.upsert(customised)

        repository.seedSystemCategoriesIfNeeded()

        repository.observeCategories().test {
            val food = awaitItem().first { it.id == "food" }
            assertThat(food).isEqualTo(customised)
        }
    }

    @Test
    fun `user category can be deleted`() = runTest {
        val userCategory = Category(
            id = "user-hobbies",
            name = "Hobbies",
            colorArgb = 0xFF00FF00.toInt(),
            iconKey = "palette",
            isSystemDefined = false,
        )
        repository.upsert(userCategory)

        repository.delete("user-hobbies")

        repository.observeCategories().test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `system category cannot be deleted`() = runTest {
        repository.seedSystemCategoriesIfNeeded()

        repository.delete("food")

        repository.observeCategories().test {
            assertThat(awaitItem().map { it.id }).contains("food")
        }
    }
}
