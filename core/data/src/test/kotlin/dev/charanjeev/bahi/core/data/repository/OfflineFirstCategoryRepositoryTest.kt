package dev.charanjeev.bahi.core.data.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

private const val DELETED_AT = 1_700L

class OfflineFirstCategoryRepositoryTest {

    private val dao = FakeCategoryDao()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(DELETED_AT))
    private val repository = OfflineFirstCategoryRepository(dao, clock, UnconfinedTestDispatcher())

    private val hobbies = Category(
        id = "user-hobbies",
        name = "Hobbies",
        colorArgb = 0xFF00FF00.toInt(),
        iconKey = "palette",
        isSystemDefined = false,
    )

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
        repository.upsert(hobbies)

        repository.delete("user-hobbies")

        repository.observeCategories().test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `deleting a user category keeps the row as a tombstone`() = runTest {
        // The whole point of the soft delete: a hard delete leaves nothing to
        // push, so the other device pushes its live copy back and the category
        // returns (docs/sync-design.md §1.2).
        repository.upsert(hobbies)

        repository.delete("user-hobbies")

        val row = dao.rowsIncludingDeleted().single { it.id == "user-hobbies" }
        assertThat(row.deletedAt).isEqualTo(DELETED_AT)
        assertThat(row.pendingOperation).isEqualTo("DELETE")
    }

    @Test
    fun `deleting a user category cascades to its budgets and rules`() = runTest {
        // A soft delete fires no foreign key, so the cascade ON DELETE CASCADE
        // used to perform has to be driven explicitly or budgets and rules
        // outlive the category they belong to.
        repository.upsert(hobbies)

        repository.delete("user-hobbies")

        assertThat(dao.budgetsCascadedFor).containsExactly("user-hobbies")
        assertThat(dao.rulesCascadedFor).containsExactly("user-hobbies")
    }

    @Test
    fun `refusing to delete a system category cascades nothing`() = runTest {
        // The guard is on the category UPDATE, so a no-op there must stop the
        // cascade too -- otherwise "delete" would silently wipe the budgets and
        // rules of a category that is still there.
        repository.seedSystemCategoriesIfNeeded()

        repository.delete("food")

        assertThat(dao.budgetsCascadedFor).isEmpty()
        assertThat(dao.rulesCascadedFor).isEmpty()
    }

    @Test
    fun `upsert marks the row pending and bumps its revision`() = runTest {
        repository.upsert(hobbies)
        repository.upsert(hobbies.copy(name = "Hobbies and crafts"))

        val row = dao.rowsIncludingDeleted().single { it.id == "user-hobbies" }
        assertThat(row.localRevision).isEqualTo(2)
        assertThat(row.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `system category cannot be deleted`() = runTest {
        repository.seedSystemCategoriesIfNeeded()

        repository.delete("food")

        repository.observeCategories().test {
            assertThat(awaitItem().map { it.id }).contains("food")
        }
    }

    @Test
    fun `upsert cannot launder a system category into a deletable one`() = runTest {
        repository.seedSystemCategoriesIfNeeded()
        val food = systemCategories.first { it.id == "food" }

        repository.upsert(food.copy(isSystemDefined = false))
        repository.delete("food")

        repository.observeCategories().test {
            assertThat(awaitItem().map { it.id }).contains("food")
        }
    }
}
