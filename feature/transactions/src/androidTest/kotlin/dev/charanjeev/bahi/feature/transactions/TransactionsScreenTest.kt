package dev.charanjeev.bahi.feature.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Reads the same strings.xml the screen does, so a copy change can't
    // silently desync the test from the real UI text.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `shows loading indicator in loading state`() {
        composeTestRule.setContent {
            TransactionsScreen(uiState = TransactionsUiState.Loading)
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun `shows an explanation and a call to action in the empty state`() {
        composeTestRule.setContent {
            TransactionsScreen(uiState = TransactionsUiState.Empty)
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `shows the error message with a working retry action`() {
        var retried = false
        composeTestRule.setContent {
            TransactionsScreen(
                uiState = TransactionsUiState.Error("disk on fire"),
                onRetry = { retried = true },
            )
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.ERROR).assertIsDisplayed()
        composeTestRule.onNodeWithText("disk on fire").assertIsDisplayed()
        composeTestRule.onNodeWithTag(TransactionsTestTags.ERROR_RETRY).performClick()

        assertThat(retried).isTrue()
    }

    @Test
    fun `shows transactions grouped under their date header`() {
        val item = TransactionListItem(
            transaction = TestData.transaction(id = "a", description = "COFFEE", date = LocalDate(2026, 3, 14)),
            category = null,
        )
        val state = TransactionsUiState.Success(
            groups = persistentListOf(TransactionGroup(header = DateHeader.Today, items = persistentListOf(item))),
            netTotal = item.transaction.amount,
            currencyCode = item.transaction.currencyCode,
        )

        composeTestRule.setContent {
            TransactionsScreen(uiState = state)
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.LIST).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_date_header_today)).assertIsDisplayed()
        // Title-cased for display -- the row never shows the raw stored text.
        composeTestRule.onNodeWithText("Coffee").assertIsDisplayed()
    }

    @Test
    fun `swiping a row to delete shows an undo snackbar that restores it`() {
        val item = TransactionListItem(
            transaction = TestData.transaction(id = "a", description = "COFFEE", date = LocalDate(2026, 3, 14)),
            category = null,
        )
        val loaded = TransactionsUiState.Success(
            groups = persistentListOf(TransactionGroup(header = DateHeader.Today, items = persistentListOf(item))),
            netTotal = item.transaction.amount,
            currencyCode = item.transaction.currencyCode,
        )
        var deletedItem: TransactionListItem? = null
        var undone = false

        composeTestRule.setContent {
            // Stands in for the ViewModel: a real delete removes the row from the
            // repository's flow (row leaves composition) while the pending-delete
            // snapshot lives on in state, which is exactly what this reproduces.
            var uiState by remember { mutableStateOf<TransactionsUiState>(loaded) }
            TransactionsScreen(
                uiState = uiState,
                onDeleteTransaction = { deleted ->
                    deletedItem = deleted
                    uiState = loaded.copy(groups = persistentListOf(), pendingDelete = deleted)
                },
                onUndoDelete = { undone = true },
            )
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.rowTag("a"))
            .performTouchInput { swipeLeft() }

        assertThat(deletedItem).isEqualTo(item)
        composeTestRule.onNodeWithText(string(R.string.transactions_deleted_snackbar, "Coffee")).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.transactions_undo)).performClick()

        assertThat(undone).isTrue()
    }
}
