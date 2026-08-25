package dev.charanjeev.bahi.feature.budgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleEditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Reads the same strings.xml the screen does, so a copy change can't
    // silently desync the test from the real UI text.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val categories = persistentListOf(
        Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant"),
        Category(id = "transport", name = "Transport", colorArgb = 0, iconKey = "train"),
    )

    private fun editing(
        merchantContains: String = "",
        categoryId: String? = null,
        merchantTouched: Boolean = false,
        dialog: RuleApplyDialog? = null,
    ) = RuleEditorUiState.Editing(
        mode = RuleEditorMode.ADD,
        merchantContains = merchantContains,
        categoryId = categoryId,
        categories = categories,
        merchantTouched = merchantTouched,
        dialog = dialog,
    )

    // --- the blank rule cannot be submitted at all ---

    @Test
    fun saveIsDisabled_onAnEmptyForm() {
        composeTestRule.setContent { RuleEditorScreen(uiState = editing()) }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun saveIsDisabled_whenOnlyTheCategoryIsChosen() {
        composeTestRule.setContent { RuleEditorScreen(uiState = editing(categoryId = "food")) }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun saveIsDisabled_whenTheMerchantFieldHoldsOnlyWhitespace() {
        // The case the button's enabled state has to get right on its own:
        // "   " looks typed-in, and a length check would let it through.
        composeTestRule.setContent {
            RuleEditorScreen(uiState = editing(merchantContains = "   ", categoryId = "food"))
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun saveIsEnabled_onceTheFormIsComplete() {
        composeTestRule.setContent {
            RuleEditorScreen(uiState = editing(merchantContains = "SWIGGY", categoryId = "food"))
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsEnabled()
    }

    /**
     * Typing and then clearing the field, driven through the same
     * state-update path the ViewModel uses. This is the end-to-end version of
     * the guard: there is no sequence of interactions that leaves an empty
     * rule submittable.
     */
    @Test
    fun clearingTheMerchantFieldDisablesSaveAgain() {
        var saveClicks = 0

        composeTestRule.setContent {
            var uiState by remember { mutableStateOf(editing(categoryId = "food")) }
            RuleEditorScreen(
                uiState = uiState,
                onMerchantContainsChange = { value ->
                    uiState = uiState.copy(merchantContains = value, merchantTouched = true)
                },
                onSave = { saveClicks++ },
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.MERCHANT_FIELD).performTextInput("SWIGGY")
        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsEnabled()

        composeTestRule.onNodeWithTag(RuleEditorTestTags.MERCHANT_FIELD).performTextReplacement("")
        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).assertIsNotEnabled()

        // Tapping a disabled button does nothing, so onSave is never reached.
        composeTestRule.onNodeWithTag(RuleEditorTestTags.SAVE).performClick()
        assertThat(saveClicks).isEqualTo(0)
    }

    @Test
    fun theBlankErrorExplainsWhyRatherThanJustSayingRequired() {
        composeTestRule.setContent {
            RuleEditorScreen(uiState = editing(merchantContains = "", merchantTouched = true))
        }

        // The copy names the consequence -- "would match every transaction you
        // have" -- because "required" doesn't tell the user why this field is
        // the dangerous one.
        composeTestRule
            .onNodeWithText(string(R.string.rule_editor_merchant_error_blank))
            .assertIsDisplayed()
    }

    @Test
    fun theHintIsShownBeforeTheUserHasTouchedTheField() {
        composeTestRule.setContent { RuleEditorScreen(uiState = editing()) }

        composeTestRule.onNodeWithText(string(R.string.rule_editor_merchant_hint)).assertIsDisplayed()
    }

    // --- the consent gate ---

    @Test
    fun theConfirmDialogSaysHowManyTransactionsWillChange() {
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Confirm(matchedCount = 14, lockedSkippedCount = 0),
                ),
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.CONFIRM_DIALOG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.rule_apply_body, 14, 14),
            )
            .assertIsDisplayed()
    }

    @Test
    fun theConfirmDialogSaysLockedTransactionsWillBeSkipped() {
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Confirm(matchedCount = 12, lockedSkippedCount = 3),
                ),
            )
        }

        composeTestRule
            .onNodeWithText(
                context.resources.getQuantityString(R.plurals.rule_apply_locked_note, 3, 3),
            )
            .assertIsDisplayed()
    }

    @Test
    fun theLockedNoteIsAbsentWhenNothingIsLocked() {
        // Shown only when true. A permanent footnote would be noise on every
        // dialog and would stop being read by the time it mattered.
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Confirm(matchedCount = 12, lockedSkippedCount = 0),
                ),
            )
        }

        composeTestRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.rule_apply_locked_note, 1, 1))
            .assertDoesNotExist()
    }

    @Test
    fun applyingRequiresAnExplicitTap() {
        var confirmed = false
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Confirm(matchedCount = 14, lockedSkippedCount = 0),
                ),
                onApplyConfirmed = { confirmed = true },
            )
        }

        assertThat(confirmed).isFalse()
        composeTestRule.onNodeWithTag(RuleEditorTestTags.CONFIRM_APPLY).performClick()
        assertThat(confirmed).isTrue()
    }

    @Test
    fun decliningIsOfferedAlongsideApplying() {
        var declined = false
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Confirm(matchedCount = 14, lockedSkippedCount = 0),
                ),
                onApplyDeclined = { declined = true },
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.CONFIRM_SKIP).performClick()
        assertThat(declined).isTrue()
    }

    @Test
    fun aRuleMatchingNothingOffersNoApplyButton() {
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.NothingToDo(lockedSkippedCount = 0),
                ),
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.NOTHING_TO_DO_DIALOG).assertIsDisplayed()
        // Nothing to consent to, so no Apply -- offering one would leave the
        // user wondering what it did.
        composeTestRule.onNodeWithText(string(R.string.rule_apply_confirm)).assertDoesNotExist()
    }

    @Test
    fun theResultSaysSoWhenFewerRowsChangedThanWerePromised() {
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(
                    merchantContains = "SWIGGY",
                    categoryId = "food",
                    dialog = RuleApplyDialog.Done(changedCount = 11, previewedCount = 14),
                ),
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.DONE_DIALOG).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.rule_apply_done_fell_short, 11, 14))
            .assertIsDisplayed()
    }

    @Test
    fun theCategoryCanBeChosenFromTheMenu() {
        var chosen: String? = null
        composeTestRule.setContent {
            RuleEditorScreen(
                uiState = editing(merchantContains = "SWIGGY"),
                onCategorySelected = { chosen = it },
            )
        }

        composeTestRule.onNodeWithTag(RuleEditorTestTags.CATEGORY_FIELD).performClick()
        composeTestRule.onNodeWithTag(RuleEditorTestTags.categoryOption("transport")).performClick()

        assertThat(chosen).isEqualTo("transport")
    }
}
