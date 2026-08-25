package dev.charanjeev.bahi.feature.budgets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val food = Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant")
    private val transport = Category(id = "transport", name = "Transport", colorArgb = 0, iconKey = "train")

    private fun success(
        dialog: RuleApplyDialog? = null,
        pendingDelete: PendingDelete? = null,
        isWorking: Boolean = false,
    ) = RulesUiState.Success(
        rules = persistentListOf(
            RuleListItem(CategoryRule("a", "food", "SWIGGY", priority = 0), food),
            RuleListItem(CategoryRule("b", "transport", "UBER", priority = 1), transport),
        ),
        dialog = dialog,
        pendingDelete = pendingDelete,
        isWorking = isWorking,
    )

    @Test
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent { RulesScreen(uiState = RulesUiState.Loading) }

        composeTestRule.onNodeWithTag(RulesTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun emptyState_explainsWhatARuleDoes() {
        composeTestRule.setContent { RulesScreen(uiState = RulesUiState.Empty) }

        composeTestRule.onNodeWithTag(RulesTestTags.EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.rules_empty_body)).assertIsDisplayed()
    }

    @Test
    fun emptyState_doesNotOfferRecategorise() {
        // With no rules the action can only ever report zero, so it isn't
        // offered rather than being offered and doing nothing.
        composeTestRule.setContent { RulesScreen(uiState = RulesUiState.Empty) }

        composeTestRule.onNodeWithTag(RulesTestTags.RECATEGORISE_ACTION).assertDoesNotExist()
    }

    @Test
    fun rulesAreListedWithTheirCategories() {
        composeTestRule.setContent { RulesScreen(uiState = success()) }

        composeTestRule.onNodeWithText(string(R.string.rules_summary, "SWIGGY")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Food").assertIsDisplayed()
    }

    @Test
    fun theOrderNoteExplainsThatTheFirstMatchWins() {
        // Reordering is meaningless to the user unless the screen says what
        // order does.
        composeTestRule.setContent { RulesScreen(uiState = success()) }

        composeTestRule.onNodeWithText(string(R.string.rules_order_note)).assertIsDisplayed()
    }

    @Test
    fun theFirstRuleCannotBeMovedUpAndTheLastCannotBeMovedDown() {
        composeTestRule.setContent { RulesScreen(uiState = success()) }

        composeTestRule.onNodeWithTag(RulesTestTags.moveUp("a")).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(RulesTestTags.moveDown("a")).assertIsEnabled()
        composeTestRule.onNodeWithTag(RulesTestTags.moveUp("b")).assertIsEnabled()
        composeTestRule.onNodeWithTag(RulesTestTags.moveDown("b")).assertIsNotEnabled()
    }

    @Test
    fun movingARuleReportsWhichRuleMoved() {
        var moved: String? = null
        composeTestRule.setContent {
            RulesScreen(uiState = success(), onMoveDown = { moved = it })
        }

        composeTestRule.onNodeWithTag(RulesTestTags.moveDown("a")).performClick()

        assertThat(moved).isEqualTo("a")
    }

    // --- delete asks first ---

    @Test
    fun deletingARuleAsksBeforeItHappens() {
        var confirmed = false
        composeTestRule.setContent {
            RulesScreen(
                uiState = success(pendingDelete = PendingDelete("a", "SWIGGY")),
                onDeleteConfirmed = { confirmed = true },
            )
        }

        composeTestRule.onNodeWithTag(RulesTestTags.DELETE_DIALOG).assertIsDisplayed()
        assertThat(confirmed).isFalse()
    }

    @Test
    fun theDeleteDialogSaysWhatItDoesNotDo() {
        // Someone warned that rules rewrite categories in bulk has every
        // reason to wonder whether deleting one rewrites them back.
        composeTestRule.setContent {
            RulesScreen(uiState = success(pendingDelete = PendingDelete("a", "SWIGGY")))
        }

        composeTestRule.onNodeWithText(string(R.string.rules_delete_body)).assertIsDisplayed()
    }

    // --- recategorise previews before it writes ---

    @Test
    fun theRecategoriseActionIsOfferedWhenRulesExist() {
        composeTestRule.setContent { RulesScreen(uiState = success()) }

        composeTestRule.onNodeWithTag(RulesTestTags.RECATEGORISE_ACTION).assertIsDisplayed()
    }

    @Test
    fun theRecategoriseActionIsBlockedWhileAPreviewOrApplyIsRunning() {
        composeTestRule.setContent { RulesScreen(uiState = success(isWorking = true)) }

        composeTestRule.onNodeWithTag(RulesTestTags.RECATEGORISE_ACTION).assertIsNotEnabled()
    }

    @Test
    fun theConfirmDialogSaysHowManyAndHowManyAreSkipped() {
        composeTestRule.setContent {
            RulesScreen(
                uiState = success(dialog = RuleApplyDialog.Confirm(matchedCount = 12, lockedSkippedCount = 3)),
            )
        }

        composeTestRule.onNodeWithTag(RulesTestTags.CONFIRM_DIALOG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.rule_apply_body, 12, 12))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.rule_apply_locked_note, 3, 3))
            .assertIsDisplayed()
    }

    @Test
    fun applyingRequiresAnExplicitTap() {
        var confirmed = false
        composeTestRule.setContent {
            RulesScreen(
                uiState = success(dialog = RuleApplyDialog.Confirm(matchedCount = 12, lockedSkippedCount = 0)),
                onApplyConfirmed = { confirmed = true },
            )
        }

        assertThat(confirmed).isFalse()
        composeTestRule.onNodeWithTag(RulesTestTags.CONFIRM_APPLY).performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    fun theResultReportsWhatActuallyChanged() {
        composeTestRule.setContent {
            RulesScreen(uiState = success(dialog = RuleApplyDialog.Done(changedCount = 12, previewedCount = 12)))
        }

        composeTestRule.onNodeWithTag(RulesTestTags.DONE_DIALOG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.rule_apply_done_body, 12, 12))
            .assertIsDisplayed()
    }
}
