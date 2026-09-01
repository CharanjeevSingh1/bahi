package dev.charanjeev.bahi.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.model.ConflictValue
import dev.charanjeev.bahi.core.model.SyncTable
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun conflict(
        id: String = "c1",
        field: String = "notes",
        chosen: ConflictValue = ConflictValue.Text("kept"),
        discarded: ConflictValue = ConflictValue.Text("lost"),
    ) = ConflictListItem(
        id = id,
        table = SyncTable.TRANSACTIONS,
        field = field,
        chosenValue = chosen,
        discardedValue = discarded,
        reason = "newest wins",
        resolvedAt = Instant.fromEpochMilliseconds(1_000),
    )

    @Test
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Loading(syncConfigured = true)) }

        composeTestRule.onNodeWithTag(SettingsTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun notConfigured_showsExplanationRowAboveWhateverStateFollows() {
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Empty(syncConfigured = false)) }

        composeTestRule.onNodeWithTag(SettingsTestTags.NOT_CONFIGURED).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_sync_not_configured_title)).assertIsDisplayed()
    }

    @Test
    fun configured_hidesTheNotConfiguredRow() {
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Empty(syncConfigured = true)) }

        composeTestRule.onNodeWithTag(SettingsTestTags.NOT_CONFIGURED).assertDoesNotExist()
    }

    @Test
    fun configured_showsTheEncryptionRow() {
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Empty(syncConfigured = true)) }

        composeTestRule.onNodeWithTag(SettingsTestTags.ENCRYPTION_ROW).assertIsDisplayed()
    }

    @Test
    fun notConfigured_hidesTheEncryptionRow() {
        // Setting up encryption for a transport this build doesn't have would be
        // a dead end -- same gate as NotConfiguredRow, opposite branch.
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Empty(syncConfigured = false)) }

        composeTestRule.onNodeWithTag(SettingsTestTags.ENCRYPTION_ROW).assertDoesNotExist()
    }

    @Test
    fun tappingTheEncryptionRowNavigates() {
        var opened = false
        composeTestRule.setContent {
            SettingsScreen(uiState = SettingsUiState.Empty(syncConfigured = true), onOpenEncryptionSetup = { opened = true })
        }

        composeTestRule.onNodeWithTag(SettingsTestTags.ENCRYPTION_ROW).performClick()

        assertThat(opened).isTrue()
    }

    @Test
    fun emptyState_explainsWhatAConflictIs() {
        composeTestRule.setContent { SettingsScreen(uiState = SettingsUiState.Empty()) }

        composeTestRule.onNodeWithTag(SettingsTestTags.EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_conflicts_none)).assertIsDisplayed()
    }

    @Test
    fun conflictsAreListedWithBothValues() {
        composeTestRule.setContent {
            SettingsScreen(uiState = SettingsUiState.Success(persistentListOf(conflict())))
        }

        composeTestRule.onNodeWithTag(SettingsTestTags.row("c1")).assertIsDisplayed()
        composeTestRule.onNodeWithText("${string(R.string.settings_conflict_chosen)}: kept").assertIsDisplayed()
        composeTestRule.onNodeWithText("${string(R.string.settings_conflict_discarded)}: lost").assertIsDisplayed()
    }

    @Test
    fun anUnknownFieldFallsBackToItsRawColumnNameRatherThanCrashing() {
        composeTestRule.setContent {
            SettingsScreen(uiState = SettingsUiState.Success(persistentListOf(conflict(field = "not_a_known_field"))))
        }

        composeTestRule.onNodeWithText("not_a_known_field", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingRestoreReportsWhichConflict() {
        var restored: String? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState.Success(persistentListOf(conflict(id = "c1"))),
                onRestoreRequested = { restored = it },
            )
        }

        composeTestRule.onNodeWithTag(SettingsTestTags.restore("c1")).performClick()

        assertThat(restored).isEqualTo("c1")
    }

    @Test
    fun tappingDismissReportsWhichConflict() {
        var dismissed: String? = null
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState.Success(persistentListOf(conflict(id = "c1"))),
                onDismissRequested = { dismissed = it },
            )
        }

        composeTestRule.onNodeWithTag(SettingsTestTags.dismiss("c1")).performClick()

        assertThat(dismissed).isEqualTo("c1")
    }

    @Test
    fun aRefusedRestoreSaysSoRatherThanLookingLikeItWorked() {
        // The exact case CLAUDE.md's rule for this screen calls out: a
        // restore that can't honestly reconstruct a usable state has to say
        // so, not silently no-op behind a button that looked like success.
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState.Success(
                    conflicts = persistentListOf(conflict(id = "c1")),
                    restoreMessage = RestoreMessage("c1", RestoreOutcome.VALUE_CHANGED_SINCE),
                ),
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_restore_value_changed)).assertIsDisplayed()
    }

    @Test
    fun aRestoreMessageIsShownEvenWhenTheListHasJustEmptied() {
        // Regression case: restoring the last conflict must not lose the
        // message just because the state collapses to Empty.
        composeTestRule.setContent {
            SettingsScreen(
                uiState = SettingsUiState.Empty(restoreMessage = RestoreMessage("c1", RestoreOutcome.RESTORED)),
            )
        }

        composeTestRule.onNodeWithText(string(R.string.settings_restore_succeeded)).assertIsDisplayed()
    }
}
