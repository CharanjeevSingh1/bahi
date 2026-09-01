package dev.charanjeev.bahi.feature.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncConflict
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveConnectionState
import dev.charanjeev.bahi.core.testing.FixedClock
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
    private val driveAuthorization = FakeDriveAuthorization()
    private val syncStatusRepository = FakeSyncStatusRepository()
    private val syncScheduler = FakeSyncScheduler()
    private val categoryRepository = FakeCategoryRepository()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(10_000_000))

    private fun viewModel() = SettingsViewModel(
        repository, driveAuthorization, syncConfiguration, syncStatusRepository, syncScheduler, categoryRepository, clock,
    )

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

    private fun category(id: String, name: String) = Category(id = id, name = name, colorArgb = 0xFF00FF, iconKey = "tag")

    @Test
    fun `starts in loading state`() = runTest {
        assertThat(viewModel().uiState.value).isEqualTo(SettingsUiState.Loading(syncConfigured = true))
    }

    @Test
    fun `carries syncConfigured through every state, not just once it loads`() = runTest {
        val viewModel = SettingsViewModel(
            repository, driveAuthorization, FakeSyncConfiguration(isConfigured = false), syncStatusRepository, syncScheduler,
            categoryRepository, clock,
        )

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
    fun `a category_id conflict resolves both values to the category's name, not its id`() = runTest {
        categoryRepository.emit(listOf(category("cat-groceries", "Groceries"), category("cat-takeout", "Takeout")))
        repository.emit(
            listOf(
                conflict(
                    field = "category_id",
                    chosen = ConflictValue.Text("cat-takeout"),
                    discarded = ConflictValue.Text("cat-groceries"),
                ),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            val item = state.conflicts.single()
            assertThat(item.chosenValue).isEqualTo(ConflictValue.Text("Takeout"))
            assertThat(item.discardedValue).isEqualTo(ConflictValue.Text("Groceries"))
        }
    }

    @Test
    fun `a category_id conflict falls back to the raw id when the category is gone`() = runTest {
        // No category seeded at all -- soft-deleted, hard-reaped, or a shadow
        // lost across a restore all look the same here: absent from
        // observeCategories(), which is already filtered to deleted_at IS NULL.
        repository.emit(listOf(conflict(field = "category_id", chosen = ConflictValue.Text("cat-gone"))))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            assertThat(state.conflicts.single().chosenValue).isEqualTo(ConflictValue.Text("cat-gone"))
        }
    }

    @Test
    fun `a parent_id conflict on categories also resolves to a name`() = runTest {
        categoryRepository.emit(listOf(category("cat-food", "Food")))
        repository.emit(
            listOf(
                conflict(field = "parent_id", chosen = ConflictValue.Text("cat-food")).copy(table = SyncTable.CATEGORIES),
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            assertThat(state.conflicts.single().chosenValue).isEqualTo(ConflictValue.Text("Food"))
        }
    }

    @Test
    fun `a category_id on the wrong table -- parent_id -- is left as the raw id`() = runTest {
        // parent_id only names a category on the CATEGORIES table -- on any
        // other table it would be a coincidence of naming, not a reference,
        // and there is no such column today, but the join has to be scoped
        // by table rather than by field name alone.
        categoryRepository.emit(listOf(category("cat-food", "Food")))
        repository.emit(listOf(conflict(field = "parent_id", chosen = ConflictValue.Text("cat-food"))))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            assertThat(state.conflicts.single().chosenValue).isEqualTo(ConflictValue.Text("cat-food"))
        }
    }

    @Test
    fun `an id-shaped field with no table to join against is left untouched`() = runTest {
        categoryRepository.emit(listOf(category("cat-food", "Food")))
        repository.emit(listOf(conflict(field = "import_batch_id", chosen = ConflictValue.Text("batch-1"))))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as SettingsUiState.Success
            assertThat(state.conflicts.single().chosenValue).isEqualTo(ConflictValue.Text("batch-1"))
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

    @Test
    fun `carries driveConnection through every state that has one`() = runTest {
        val driveAuth = FakeDriveAuthorization(initialState = DriveConnectionState.NEEDS_REAUTHORIZATION)
        val viewModel = SettingsViewModel(
            repository, driveAuth, syncConfiguration, syncStatusRepository, syncScheduler, categoryRepository, clock,
        )

        viewModel.uiState.test {
            skipItems(1) // Loading -- no driveConnection to carry
            val empty = awaitItem() as SettingsUiState.Empty
            assertThat(empty.driveConnection).isEqualTo(DriveConnectionState.NEEDS_REAUTHORIZATION)
        }
    }

    @Test
    fun `connecting drive with no resolution needed leaves nothing to launch`() = runTest {
        driveAuthorization.beginAuthorizationResult = ConsentRequest.Resolved(AuthorizationOutcome.Authorized("token"))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Empty, NOT_CONNECTED

            viewModel.onConnectDriveRequested()

            val afterConnect = awaitItem() as SettingsUiState.Empty
            assertThat(afterConnect.driveConnection).isEqualTo(DriveConnectionState.CONNECTED)
        }
        assertThat(driveAuthorization.beginAuthorizationCalls).isEqualTo(1)
    }

    @Test
    fun `completing authorization forwards the activity result to the key store`() = runTest {
        driveAuthorization.completeAuthorizationResult = AuthorizationOutcome.Authorized("token")
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // Empty, NOT_CONNECTED

            viewModel.onAuthorizationResult(resultCode = -1, data = null)

            val afterComplete = awaitItem() as SettingsUiState.Empty
            assertThat(afterComplete.driveConnection).isEqualTo(DriveConnectionState.CONNECTED)
        }
        assertThat(driveAuthorization.completeAuthorizationCalls).isEqualTo(1)
    }

    @Test
    fun `requests an expedited sync as soon as the screen is opened`() = runTest {
        viewModel()

        assertThat(syncScheduler.expeditedSyncRequests).isEqualTo(1)
    }

    @Test
    fun `carries the last-synced display through every state that has one`() = runTest {
        val fiveMinutesAgo = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds() - 5 * 60_000)
        val statusRepository = FakeSyncStatusRepository(lastSuccessfulSyncAt = fiveMinutesAgo)
        val viewModel = SettingsViewModel(
            repository, driveAuthorization, syncConfiguration, statusRepository, syncScheduler, categoryRepository, clock,
        )

        viewModel.uiState.test {
            skipItems(1) // Loading -- no lastSyncDisplay to carry
            val empty = awaitItem() as SettingsUiState.Empty
            assertThat(empty.lastSyncDisplay).isEqualTo(LastSyncDisplay.MinutesAgo(5))
        }
    }
}
