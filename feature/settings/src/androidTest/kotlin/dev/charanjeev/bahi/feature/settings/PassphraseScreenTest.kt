package dev.charanjeev.bahi.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassphraseScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent { PassphraseScreen(uiState = PassphraseUiState.Loading) }

        composeTestRule.onNodeWithTag(PassphraseTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun setupEntry_showsTheLostPassphraseWarningVerbatim() {
        // §8.4's exact wording -- see PassphraseScreen's string resource doc.
        composeTestRule.setContent {
            PassphraseScreen(uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP))
        }

        composeTestRule.onNodeWithText(string(R.string.passphrase_lost_warning)).assertIsDisplayed()
    }

    @Test
    fun setupEntry_hasNoPairingCodeField() {
        composeTestRule.setContent {
            PassphraseScreen(uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP))
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.PAIRING_CODE_FIELD).assertDoesNotExist()
    }

    @Test
    fun pairEntry_showsThePairingCodeFieldInsteadOfConfirmation() {
        composeTestRule.setContent {
            PassphraseScreen(uiState = PassphraseUiState.Entry(mode = PassphraseMode.PAIR))
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.PAIRING_CODE_FIELD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PassphraseTestTags.CONFIRM_PASSPHRASE_FIELD).assertDoesNotExist()
    }

    @Test
    fun typingIntoThePassphraseFieldReportsEachChange() {
        var value = ""
        composeTestRule.setContent {
            PassphraseScreen(
                uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP),
                onPassphraseChanged = { value = it },
            )
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.PASSPHRASE_FIELD).performTextInput("hello")

        assertThat(value).isEqualTo("hello")
    }

    @Test
    fun modeToggleSwitchesBetweenSetupAndPair() {
        var mode: PassphraseMode? = null
        composeTestRule.setContent {
            PassphraseScreen(
                uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP),
                onModeChanged = { mode = it },
            )
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.MODE_TOGGLE).performClick()

        assertThat(mode).isEqualTo(PassphraseMode.PAIR)
    }

    @Test
    fun submitInvokesTheCallback() {
        var submitted = false
        composeTestRule.setContent {
            PassphraseScreen(
                uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP),
                onSubmit = { submitted = true },
            )
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.SUBMIT).performClick()

        assertThat(submitted).isTrue()
    }

    @Test
    fun anErrorIsShownWhenPresent() {
        composeTestRule.setContent {
            PassphraseScreen(
                uiState = PassphraseUiState.Entry(mode = PassphraseMode.SET_UP, error = PassphraseEntryError.PASSPHRASE_MISMATCH),
            )
        }

        composeTestRule.onNodeWithTag(PassphraseTestTags.ERROR).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.passphrase_error_mismatch)).assertIsDisplayed()
    }

    @Test
    fun doneState_showsTheSelectablePairingCode() {
        composeTestRule.setContent { PassphraseScreen(uiState = PassphraseUiState.Done(pairingCode = "abc123==")) }

        composeTestRule.onNodeWithTag(PassphraseTestTags.PAIRING_CODE_DISPLAY).assertIsDisplayed()
        composeTestRule.onNodeWithText("abc123==").assertIsDisplayed()
    }
}
