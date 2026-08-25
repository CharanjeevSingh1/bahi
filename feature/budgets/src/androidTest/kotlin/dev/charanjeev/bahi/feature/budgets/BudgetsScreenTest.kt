package dev.charanjeev.bahi.feature.budgets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Budget
import dev.charanjeev.bahi.core.model.BudgetProgress
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val august = YearMonth.of(2026, 8)
    private val food = Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant")

    private fun row(
        id: String = "b-food",
        limit: Money = Money(800_000),
        spent: Money = Money.ZERO,
    ) = BudgetRow(
        progress = BudgetProgress(Budget(id, "food", august, limit, "INR"), spent),
        category = food,
    )

    private fun success(
        rows: List<BudgetRow> = listOf(row()),
        uncategorised: Money = Money.ZERO,
    ) = BudgetsUiState.Success(
        month = august,
        budgets = persistentListOf(*rows.toTypedArray()),
        uncategorisedSpend = uncategorised,
        currencyCode = "INR",
    )

    @Test
    fun loadingState_showsLoadingIndicator() {
        composeTestRule.setContent { BudgetsScreen(uiState = BudgetsUiState.Loading(august)) }

        composeTestRule.onNodeWithTag(BudgetsTestTags.LOADING).assertIsDisplayed()
    }

    @Test
    fun theMonthSwitcherStaysVisibleWhileLoading() {
        // Otherwise the control the user just pressed disappears underneath
        // them every time the month changes.
        composeTestRule.setContent { BudgetsScreen(uiState = BudgetsUiState.Loading(august)) }

        composeTestRule.onNodeWithTag(BudgetsTestTags.MONTH_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BudgetsTestTags.NEXT_MONTH).assertIsDisplayed()
    }

    @Test
    fun emptyState_explainsWhatABudgetDoes() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = BudgetsUiState.Empty(august, Money.ZERO))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.budgets_empty_body)).assertIsDisplayed()
    }

    // --- the two months that must not render identically (§2.2) ---

    /**
     * The pair this screen exists to keep apart. Both have a budget reading
     * ₹0 of ₹8,000 -- identical rows, correctly so -- and the difference is
     * carried entirely by what sits underneath them.
     */
    @Test
    fun aMonthWithNoTransactions_saysNothingHasBeenCounted_andShowsNoUncategorisedCard() {
        composeTestRule.setContent { BudgetsScreen(uiState = success(uncategorised = Money.ZERO)) }

        composeTestRule.onNodeWithTag(BudgetsTestTags.NOTHING_COUNTED_NOTE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BudgetsTestTags.UNCATEGORISED_CARD).assertDoesNotExist()
    }

    @Test
    fun aMonthOfEntirelyUncategorisedSpending_showsTheUncategorisedCard_andNotTheNothingCountedNote() {
        composeTestRule.setContent { BudgetsScreen(uiState = success(uncategorised = Money(620_000))) }

        composeTestRule.onNodeWithTag(BudgetsTestTags.UNCATEGORISED_CARD).assertIsDisplayed()
        // Replaced, not shown alongside: "nothing has been counted" next to
        // "₹6,200 uncategorised" reads as a contradiction.
        composeTestRule.onNodeWithTag(BudgetsTestTags.NOTHING_COUNTED_NOTE).assertDoesNotExist()
    }

    @Test
    fun theUncategorisedCardSaysTheMoneyIsNotCountedTowardAnyBudget() {
        composeTestRule.setContent { BudgetsScreen(uiState = success(uncategorised = Money(620_000))) }

        composeTestRule.onNodeWithText(string(R.string.budgets_uncategorised_body)).assertIsDisplayed()
    }

    @Test
    fun uncategorisedSpendingIsShownEvenWithNoBudgetsAtAll() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = BudgetsUiState.Empty(august, Money(620_000)))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.UNCATEGORISED_CARD).assertIsDisplayed()
    }

    // --- progress and the over-budget state (§2.5) ---

    @Test
    fun aBudgetUnderItsLimitShowsWhatIsLeft() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(listOf(row(limit = Money(800_000), spent = Money(430_000)))))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.overBudget("b-food")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(BudgetsTestTags.bar("b-food")).assertIsDisplayed()
    }

    @Test
    fun spendingExactlyTheLimitIsNotOverBudget() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(listOf(row(limit = Money(800_000), spent = Money(800_000)))))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.overBudget("b-food")).assertDoesNotExist()
    }

    @Test
    fun anOverBudgetRowSaysHowMuchOverItIs() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(listOf(row(limit = Money(800_000), spent = Money(834_000)))))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.overBudget("b-food")).assertIsDisplayed()
    }

    // --- the zero-limit case, rendered rather than assumed ---

    /**
     * A ₹0 budget is something the editor lets a user create, so this checks
     * what it actually looks like rather than trusting that fractionOfLimit's
     * guard is enough. With nothing spent it is not over budget: an empty bar
     * and "₹0 left", which is the truthful reading.
     */
    @Test
    fun aZeroLimitBudgetWithNothingSpentRendersAsEmptyAndNotOverBudget() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(listOf(row(limit = Money.ZERO, spent = Money.ZERO))))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.bar("b-food")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BudgetsTestTags.overBudget("b-food")).assertDoesNotExist()
    }

    /**
     * The case that would be Infinity without the model's guard. It renders
     * as a full bar and a real over-budget figure, not as NaN, an empty bar,
     * or a crash.
     */
    @Test
    fun aZeroLimitBudgetWithSpendingRendersAsFullAndOverBudget() {
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(listOf(row(limit = Money.ZERO, spent = Money(50_000)))))
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.bar("b-food")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BudgetsTestTags.overBudget("b-food")).assertIsDisplayed()
    }

    // --- navigation affordances ---

    @Test
    fun theMonthLabelNamesTheMonth() {
        composeTestRule.setContent { BudgetsScreen(uiState = success()) }

        composeTestRule.onNodeWithText("August 2026").assertIsDisplayed()
    }

    @Test
    fun movingBetweenMonthsReportsTheDirection() {
        var back = 0
        var forward = 0
        composeTestRule.setContent {
            BudgetsScreen(uiState = success(), onPreviousMonth = { back++ }, onNextMonth = { forward++ })
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.PREVIOUS_MONTH).performClick()
        composeTestRule.onNodeWithTag(BudgetsTestTags.NEXT_MONTH).performClick()

        assertThat(back).isEqualTo(1)
        assertThat(forward).isEqualTo(1)
    }

    @Test
    fun deletingABudgetAsksFirstAndSaysTransactionsAreUntouched() {
        composeTestRule.setContent {
            BudgetsScreen(
                uiState = success().copy(pendingDelete = PendingBudgetDelete("b-food", "Food")),
            )
        }

        composeTestRule.onNodeWithTag(BudgetsTestTags.DELETE_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.budgets_delete_body)).assertIsDisplayed()
    }
}
