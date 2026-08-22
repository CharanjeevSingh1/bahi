package dev.charanjeev.bahi.feature.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionFormScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Reads the same strings.xml the screen does, so a copy change can't
    // silently desync the test from the real UI text.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent {
            TransactionFormScreen(uiState = TransactionFormUiState.Loading)
        }

        composeTestRule.onNodeWithTag(TransactionFormTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun errorState_showsMessage() {
        composeTestRule.setContent {
            TransactionFormScreen(uiState = TransactionFormUiState.Error("This transaction no longer exists."))
        }

        composeTestRule.onNodeWithTag(TransactionFormTestTags.ERROR).assertIsDisplayed()
        composeTestRule.onNodeWithText("This transaction no longer exists.").assertIsDisplayed()
    }

    @Test
    fun addFlow_fillingTheFormAndSavingInvokesOnSave() {
        var saved = false

        composeTestRule.setContent {
            // Stands in for the ViewModel: onAmountTextChange/onDescriptionChange
            // apply the same sanitize-then-store step the real ViewModel does,
            // so typing behaves the way it would on device.
            var uiState by remember {
                mutableStateOf<TransactionFormUiState>(
                    TransactionFormUiState.Editing(mode = FormMode.ADD, date = LocalDate(2026, 3, 14)),
                )
            }
            TransactionFormScreen(
                uiState = uiState,
                onAmountTextChange = { text ->
                    uiState = (uiState as TransactionFormUiState.Editing).copy(amountText = sanitizeAmountInput(text))
                },
                onDescriptionChange = { text ->
                    uiState = (uiState as TransactionFormUiState.Editing).copy(description = text)
                },
                onSave = { saved = true },
            )
        }

        composeTestRule.onNodeWithTag(TransactionFormTestTags.AMOUNT_FIELD).performTextInput("450")
        composeTestRule.onNodeWithTag(TransactionFormTestTags.DESCRIPTION_FIELD).performTextInput("Coffee")
        composeTestRule.onNodeWithTag(TransactionFormTestTags.SAVE_BUTTON).performClick()

        assertThat(saved).isTrue()
    }

    @Test
    fun editFlow_showsPrefilledFieldsAndConfirmingDeleteInvokesOnDelete() {
        var deleted = false
        val state = TransactionFormUiState.Editing(
            mode = FormMode.EDIT,
            amountText = "450.00",
            description = "COFFEE",
            date = LocalDate(2026, 3, 14),
        )

        composeTestRule.setContent {
            TransactionFormScreen(uiState = state, onDelete = { deleted = true })
        }

        composeTestRule.onNodeWithText(string(R.string.transactions_form_title_edit)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TransactionFormTestTags.AMOUNT_FIELD).assertTextContains("450.00")
        composeTestRule.onNodeWithTag(TransactionFormTestTags.DESCRIPTION_FIELD).assertTextContains("COFFEE")

        composeTestRule.onNodeWithTag(TransactionFormTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(TransactionFormTestTags.DELETE_CONFIRM).performClick()

        assertThat(deleted).isTrue()
    }

    @Test
    fun addMode_hasNoDeleteAction() {
        val state = TransactionFormUiState.Editing(mode = FormMode.ADD, date = LocalDate(2026, 3, 14))

        composeTestRule.setContent {
            TransactionFormScreen(uiState = state)
        }

        composeTestRule.onNodeWithText(string(R.string.transactions_form_title_add)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TransactionFormTestTags.DELETE_BUTTON).assertDoesNotExist()
    }

    @Test
    fun validationErrorState_showsAmountAndDescriptionErrorsOnceSubmitWasAttempted() {
        val state = TransactionFormUiState.Editing(
            mode = FormMode.ADD,
            date = LocalDate(2026, 3, 14),
            submitAttempted = true,
        )

        composeTestRule.setContent {
            TransactionFormScreen(uiState = state)
        }

        composeTestRule.onNodeWithText(string(R.string.transactions_form_amount_error_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_form_description_error)).assertIsDisplayed()
    }

    @Test
    fun validationErrors_areNotShownBeforeSubmitIsAttempted() {
        val state = TransactionFormUiState.Editing(mode = FormMode.ADD, date = LocalDate(2026, 3, 14))

        composeTestRule.setContent {
            TransactionFormScreen(uiState = state)
        }

        composeTestRule.onNodeWithText(string(R.string.transactions_form_amount_error_empty)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.transactions_form_description_error)).assertDoesNotExist()
    }
}
