package dev.charanjeev.bahi.core.data.repository

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
}
