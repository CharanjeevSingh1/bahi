package dev.charanjeev.bahi.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncConflict
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSyncConflictRepository()
    private val syncConfiguration = FakeSyncConfiguration()

    private fun viewModel() = SettingsViewModel(repository, syncConfiguration)

    private fun conflict(
        id: String = "c1",
        field: String = "notes",
        chosen: ConflictValue = ConflictValue.Text("kept"),
        discarded: ConflictValue = ConflictValue.Text("lost"),
    ) = SyncConflict(
        id = id,
        table = SyncTable.TRANSACTIONS,
        rowId = "t1",
        field = field,
        resolvedAt = Instant.fromEpochMilliseconds(1_000),
        chosenValue = chosen,
        discardedValue = discarded,
        reason = "newest wins",
        acknowledgedAt = null,
    )

    @Test
    fun `starts in loading state`() = runTest {
        assertThat(viewModel().uiState.value).isEqualTo(SettingsUiState.Loading(syncConfigured = true))
    }

    @Test
    fun `carries syncConfigured through every state, not just once it loads`() = runTest {
        val viewModel = SettingsViewModel(repository, FakeSyncConfiguration(isConfigured = false))

        viewModel.uiState.test {
            assertThat(awaitItem().syncConfigured).isFalse()
            assertThat(awaitItem().syncConfigured).isFalse() // Empty, once conflicts load
        }
    }

    @Test
    fun `emits empty when there are no conflicts`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem()).isEqualTo(SettingsUiState.Empty())
        }
    }

    @Test
    fun `lists conflicts newest first with values carried through untouched`() = runTest {
        repository.emit(
            listOf(
                conflict(id = "old", chosen = ConflictValue.Text("a")).copy(resolvedAt = Instant.fromEpochMilliseconds(1_000)),
                conflict(id = "new", chosen = ConflictValue.Text("b")).copy(resolvedAt = Instant.fromEpochMilliseconds(2_000)),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            assertThat(state.conflicts.map { it.id }).containsExactly("new", "old").inOrder()
            assertThat(state.conflicts.first { it.id == "new" }.chosenValue).isEqualTo(ConflictValue.Text("b"))
        }
    }

    @Test
    fun `restoring a conflict asks the repository and surfaces the outcome`() = runTest {
        repository.emit(listOf(conflict(id = "c1")))
        repository.restoreResult = RestoreOutcome.RESTORED
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Success with c1 still present

            viewModel.onRestoreRequested("c1")

            // A successful restore acknowledges the conflict -- the list empties,
            // but the message still has to reach the screen (SettingsUiState's
            // own doc on why every state carries restoreMessage).
            val afterRestore = awaitItem() as SettingsUiState.Empty
            assertThat(afterRestore.restoreMessage).isEqualTo(RestoreMessage("c1", RestoreOutcome.RESTORED))
        }
        assertThat(repository.restoreCalls).containsExactly("c1")
    }

    @Test
    fun `a refused restore leaves the conflict on the list and says why`() = runTest {
        repository.emit(listOf(conflict(id = "c1")))
        repository.restoreResult = RestoreOutcome.VALUE_CHANGED_SINCE
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Success with c1 still present

            viewModel.onRestoreRequested("c1")

            val afterRefusal = awaitItem() as SettingsUiState.Success
            // Refused, not silently dropped: still on the list, with a message
            // saying why -- exactly what CLAUDE.md's rule for this screen asks for.
            assertThat(afterRefusal.conflicts.map { it.id }).containsExactly("c1")
            assertThat(afterRefusal.restoreMessage).isEqualTo(RestoreMessage("c1", RestoreOutcome.VALUE_CHANGED_SINCE))
        }
    }

    @Test
    fun `dismissing acknowledges without restoring`() = runTest {
        repository.emit(listOf(conflict(id = "c1")))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Success with c1 still present

            viewModel.onDismissRequested("c1")

            assertThat(awaitItem()).isInstanceOf(SettingsUiState.Empty::class.java)
        }
        assertThat(repository.acknowledgeCalls).containsExactly("c1")
        assertThat(repository.restoreCalls).isEmpty()
    }

    @Test
    fun `acknowledging the restore message clears it without touching the list`() = runTest {
        repository.emit(listOf(conflict(id = "c1")))
        repository.restoreResult = RestoreOutcome.ROW_GONE
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Success with c1 still present
            viewModel.onRestoreRequested("c1")
            skipItems(1) // Success with the ROW_GONE message

            viewModel.onRestoreMessageShown()

            val cleared = awaitItem() as SettingsUiState.Success
            assertThat(cleared.restoreMessage).isNull()
            assertThat(cleared.conflicts.map { it.id }).containsExactly("c1")
        }
    }
}
