package dev.charanjeev.bahi.navigation

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.charanjeev.bahi.MainActivity
import dev.charanjeev.bahi.ui.BahiAppTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dev.charanjeev.bahi.feature.budgets.R as BudgetsR
import dev.charanjeev.bahi.feature.transactions.R as TransactionsR

/**
 * Drives the real Activity, so the graph under test is the one that ships -- a
 * NavHost hand-built inside the test would pass happily while the app's own
 * wiring was broken, which is the failure this exists to catch.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    private fun tab(destination: TopLevelDestination) =
        composeTestRule.onNodeWithTag(BahiAppTestTags.tab(destination))

    private fun openAddTransactionForm() {
        composeTestRule
            .onNodeWithContentDescription(string(TransactionsR.string.transactions_add_content_description))
            .performClick()
    }

    @Test
    fun tappingATab_selectsItAndShowsItsScreen() {
        tab(TopLevelDestination.BUDGETS).performClick()

        tab(TopLevelDestination.BUDGETS).assertIsSelected()
        tab(TopLevelDestination.TRANSACTIONS).assertIsNotSelected()
        // The budgets top bar's own action, rather than the screen title --
        // "Budgets" is also the tab's label, and two matches is not an assertion.
        composeTestRule.onNodeWithText(string(BudgetsR.string.budgets_rules_action)).assertIsDisplayed()
    }

    @Test
    fun switchingTabs_keepsADeepScreenOnTheTabItWasOpenedFrom() {
        tab(TopLevelDestination.BUDGETS).performClick()
        composeTestRule.onNodeWithText(string(BudgetsR.string.budgets_rules_action)).performClick()
        composeTestRule.onNodeWithText(string(BudgetsR.string.rules_title)).assertIsDisplayed()

        tab(TopLevelDestination.TRANSACTIONS).performClick()
        tab(TopLevelDestination.BUDGETS).performClick()

        // Not the budgets list: the tab comes back where it was left, which is
        // the point of one back stack per tab rather than one shared one.
        composeTestRule.onNodeWithText(string(BudgetsR.string.rules_title)).assertIsDisplayed()
    }

    @Test
    fun switchingTabs_keepsAHalfFilledFormIntact() {
        openAddTransactionForm()
        // Amount is the form's first text field. :app cannot see the feature's
        // internal test tags, so it is addressed by position rather than by tag
        // -- and read back the same way, so both ends mean the same field.
        val amountField = { composeTestRule.onAllNodes(hasSetTextAction()).onFirst() }
        amountField().performTextInput("42")

        tab(TopLevelDestination.INSIGHTS).performClick()
        tab(TopLevelDestination.TRANSACTIONS).performClick()

        composeTestRule.onNodeWithText(string(TransactionsR.string.transactions_form_title_add)).assertIsDisplayed()
        // substring, because the field normalises to "42.00" when the tab tap
        // takes focus off it. What is being asserted is that the digits are
        // still there, not how the field chooses to render them.
        amountField().assertIsDisplayed().assert(hasText("42", substring = true))
    }

    @Test
    fun theBar_staysVisibleOnAPushedScreenWithItsOwnTabStillSelected() {
        openAddTransactionForm()

        composeTestRule.onNodeWithTag(BahiAppTestTags.BOTTOM_BAR).assertIsDisplayed()
        // The form is inside the transactions graph, so that is the tab the
        // user is in, even though the route on screen belongs to no tab.
        tab(TopLevelDestination.TRANSACTIONS).assertIsSelected()
    }

    @Test
    fun reTappingTheActiveTab_returnsToItsRoot() {
        openAddTransactionForm()
        composeTestRule.onNodeWithText(string(TransactionsR.string.transactions_form_title_add)).assertIsDisplayed()

        tab(TopLevelDestination.TRANSACTIONS).performClick()

        composeTestRule.onNodeWithText(string(TransactionsR.string.transactions_form_title_add)).assertDoesNotExist()
    }

    @Test
    fun theSelectedTab_survivesProcessDeath() {
        tab(TopLevelDestination.INSIGHTS).performClick()
        tab(TopLevelDestination.INSIGHTS).assertIsSelected()

        // recreate() runs the saved-state path a process death would: the
        // Activity is torn down and rebuilt from the bundle it wrote. Nothing
        // holds the selected tab in memory across that -- it is read back out
        // of the restored nav back stack.
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        tab(TopLevelDestination.INSIGHTS).assertIsSelected()
    }
}
