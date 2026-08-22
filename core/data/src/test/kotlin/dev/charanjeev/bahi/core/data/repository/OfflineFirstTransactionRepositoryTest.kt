package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.DateWindow
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.TransactionFilter
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Test

class OfflineFirstTransactionRepositoryTest {

    private val dao = FakeTransactionDao()
    private val repository = OfflineFirstTransactionRepository(dao, UnconfinedTestDispatcher())

    @Test
    fun `delete sets a pending DELETE and a tombstone`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))

        repository.delete("a")

        val entity = dao.entity("a")
        assertThat(entity?.deletedAt).isNotNull()
        assertThat(entity?.pendingOperation).isEqualTo("DELETE")
    }

    @Test
    fun `undo after delete clears the tombstone and re-asserts the row to sync`() = runTest {
        // Not NULL: NULL means "in sync with remote", which would be false if
        // the DELETE this undoes had already been pushed -- the remote would
        // keep the deletion and the row would vanish again on the next sync.
        repository.upsert(TestData.transaction(id = "a"))
        repository.delete("a")

        repository.undoDelete("a")

        val entity = dao.entity("a")
        assertThat(entity?.deletedAt).isNull()
        assertThat(entity?.pendingOperation).isEqualTo("UPSERT")
    }

    @Test
    fun `undo bumps the local revision so sync notices the row changed again`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        repository.delete("a")
        val revisionAfterDelete = dao.entity("a")!!.localRevision

        repository.undoDelete("a")

        assertThat(dao.entity("a")!!.localRevision).isGreaterThan(revisionAfterDelete)
    }

    @Test
    fun `update bumps the local revision and marks the row pending UPSERT`() = runTest {
        repository.upsert(TestData.transaction(id = "a"))
        val revisionAfterCreate = dao.entity("a")!!.localRevision

        repository.update(TestData.transaction(id = "a", description = "RENAMED"))

        val entity = dao.entity("a")!!
        assertThat(entity.localRevision).isGreaterThan(revisionAfterCreate)
        assertThat(entity.pendingOperation).isEqualTo("UPSERT")
        assertThat(entity.description).isEqualTo("RENAMED")
    }

    @Test
    fun `update is a no-op for an id that doesn't exist`() = runTest {
        repository.update(TestData.transaction(id = "missing"))

        assertThat(dao.entity("missing")).isNull()
    }

    // --- Filtering: a query, not the caller filtering the returned list ---

    @Test
    fun `no filter returns everything`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 14)))
        repository.upsert(TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 1, 1)))

        val result = repository.observeTransactions(TransactionFilter.NONE).first()

        assertThat(result.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `category filter returns only matching categories, any date`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 1, 1)))
        repository.upsert(TestData.transaction(id = "b", categoryId = "rent", date = LocalDate(2026, 6, 1)))
        repository.upsert(TestData.transaction(id = "c", categoryId = "groceries", date = LocalDate(2026, 6, 1)))

        val result = repository.observeTransactions(
            TransactionFilter(categoryIds = setOf("food", "groceries")),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a", "c")
    }

    @Test
    fun `date window filter returns only transactions inside the window, any category`() = runTest {
        repository.upsert(TestData.transaction(id = "a", date = LocalDate(2026, 3, 1)))
        repository.upsert(TestData.transaction(id = "b", date = LocalDate(2026, 3, 31)))
        repository.upsert(TestData.transaction(id = "c", date = LocalDate(2026, 4, 1)))

        val result = repository.observeTransactions(
            TransactionFilter(dateWindow = DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31))),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a", "b")
    }

    @Test
    fun `category and date window compose -- both must match, not either`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 14)))
        // Right category, wrong month.
        repository.upsert(TestData.transaction(id = "b", categoryId = "food", date = LocalDate(2026, 4, 14)))
        // Right month, wrong category.
        repository.upsert(TestData.transaction(id = "c", categoryId = "rent", date = LocalDate(2026, 3, 14)))

        val result = repository.observeTransactions(
            TransactionFilter(
                categoryIds = setOf("food"),
                dateWindow = DateWindow(LocalDate(2026, 3, 1), LocalDate(2026, 3, 31)),
            ),
        ).first()

        assertThat(result.map { it.id }).containsExactly("a")
    }

    @Test
    fun `a filter matching nothing returns an empty list, not an error`() = runTest {
        repository.upsert(TestData.transaction(id = "a", categoryId = "food"))

        val result = repository.observeTransactions(TransactionFilter(categoryIds = setOf("nonexistent"))).first()

        assertThat(result).isEmpty()
    }

    @Test
    fun `filtered transactions still map amount and category through the domain model`() = runTest {
        repository.upsert(
            TestData.transaction(id = "a", categoryId = "food", amount = Money(-4500), date = LocalDate(2026, 3, 14)),
        )

        val result = repository.observeTransactions(TransactionFilter(categoryIds = setOf("food"))).first()

        assertThat(result.single().amount).isEqualTo(Money(-4500))
    }
}
