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
import dev.charanjeev.bahi.core.model.Category
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
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent {
            TransactionsScreen(uiState = TransactionsUiState.Loading)
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun emptyState_showsExplanationAndCallToAction() {
        composeTestRule.setContent {
            TransactionsScreen(uiState = TransactionsUiState.Empty)
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_empty_title)).assertIsDisplayed()
    }

    @Test
    fun errorState_showsMessageWithWorkingRetryAction() {
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
    fun successState_showsTransactionsGroupedUnderDateHeader() {
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
    fun swipeToDelete_showsUndoSnackbarThatRestoresItem() {
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

    @Test
    fun addFab_invokesOnAddTransaction() {
        var addClicked = false
        composeTestRule.setContent {
            TransactionsScreen(
                uiState = TransactionsUiState.Empty,
                onAddTransaction = { addClicked = true },
            )
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.ADD_FAB).performClick()

        assertThat(addClicked).isTrue()
    }

    @Test
    fun filteredEmptyState_showsDistinctCopyFromTheOnboardingEmptyState() {
        val state = TransactionsUiState.EmptyFiltered(filter = TransactionFilterState(categoryIds = setOf("food")))
        var cleared = false

        composeTestRule.setContent {
            TransactionsScreen(uiState = state, onClearFilters = { cleared = true })
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.EMPTY_FILTERED).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_empty_filtered_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TransactionsTestTags.EMPTY).assertDoesNotExist()

        composeTestRule.onNodeWithTag(TransactionsTestTags.EMPTY_FILTERED_CLEAR).performClick()
        assertThat(cleared).isTrue()
    }

    @Test
    fun filteredEmptyState_stillShowsTheZeroTotalForTheFilteredRange() {
        val from = LocalDate(2026, 8, 1)
        val to = LocalDate(2026, 8, 9)
        val state = TransactionsUiState.EmptyFiltered(
            filter = TransactionFilterState(dateRangeOption = DateRangeOption.CUSTOM, customFrom = from, customTo = to),
            netPeriod = NetPeriod.Range(from, to),
        )

        composeTestRule.setContent {
            TransactionsScreen(uiState = state)
        }

        // A missing total here would read as a rendering bug rather than
        // "nothing matched this filter" -- it must show even at zero.
        composeTestRule.onNodeWithText(string(R.string.transactions_net_label_range, "1 Aug", "9 Aug"), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun noActiveFilter_hidesTheClearButton() {
        val item = TransactionListItem(
            transaction = TestData.transaction(id = "a", date = LocalDate(2026, 3, 14)),
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

        composeTestRule.onNodeWithTag(FilterBarTestTags.CLEAR_BUTTON).assertDoesNotExist()
    }

    @Test
    fun activeFilter_showsSelectedChipsAndAClearButton() {
        val item = TransactionListItem(
            transaction = TestData.transaction(id = "a", categoryId = "food", date = LocalDate(2026, 3, 14)),
            category = null,
        )
        val category = Category(
            id = "food",
            name = "Food",
            colorArgb = 0xFFEF5350.toInt(),
            iconKey = "restaurant",
        )
        val state = TransactionsUiState.Success(
            groups = persistentListOf(TransactionGroup(header = DateHeader.Today, items = persistentListOf(item))),
            netTotal = item.transaction.amount,
            currencyCode = item.transaction.currencyCode,
            filter = TransactionFilterState(categoryIds = setOf("food"), dateRangeOption = DateRangeOption.THIS_MONTH),
            availableCategories = persistentListOf(category),
        )
        var cleared = false

        composeTestRule.setContent {
            TransactionsScreen(uiState = state, onClearFilters = { cleared = true })
        }

        composeTestRule.onNodeWithText("Food").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.transactions_filter_date_this_month)).assertIsDisplayed()

        composeTestRule.onNodeWithTag(FilterBarTestTags.CLEAR_BUTTON).performClick()
        assertThat(cleared).isTrue()
    }

    @Test
    fun tappingARow_invokesOnTransactionClickWithItsId() {
        val item = TransactionListItem(
            transaction = TestData.transaction(id = "a", description = "COFFEE", date = LocalDate(2026, 3, 14)),
            category = null,
        )
        val state = TransactionsUiState.Success(
            groups = persistentListOf(TransactionGroup(header = DateHeader.Today, items = persistentListOf(item))),
            netTotal = item.transaction.amount,
            currencyCode = item.transaction.currencyCode,
        )
        var clickedId: String? = null

        composeTestRule.setContent {
            TransactionsScreen(uiState = state, onTransactionClick = { clickedId = it })
        }

        composeTestRule.onNodeWithTag(TransactionsTestTags.rowTag("a")).performClick()

        assertThat(clickedId).isEqualTo("a")
    }
}
