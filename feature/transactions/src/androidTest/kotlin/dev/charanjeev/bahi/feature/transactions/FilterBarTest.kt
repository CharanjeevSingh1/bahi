package dev.charanjeev.bahi.feature.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilterBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setFilterBar(filter: TransactionFilterState = TransactionFilterState()) {
        composeTestRule.setContent {
            FilterBar(
                filter = filter,
                availableCategories = persistentListOf(),
                onCategoryFilterToggled = {},
                onDateRangeOptionSelected = {},
                onCustomDateRangeSelected = { _, _ -> },
                onClearFilters = {},
            )
        }
    }

    @Test
    fun openingTheDateSheet_doesNotSelectTheDateChipOnItsOwn() {
        setFilterBar()

        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).assertIsNotSelected()
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).performClick()
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_SHEET).assertIsDisplayed()

        // The chip's selected state tracks filter.dateRangeOption, not
        // whether the sheet happens to be open right now.
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).assertIsNotSelected()
    }

    @Test
    fun dismissingTheDateSheetWithoutChoosing_leavesTheDateChipUnselected() {
        setFilterBar()

        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).performClick()
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_SHEET).assertIsDisplayed()

        // Swipe-to-dismiss without picking an option -- no option row is
        // tapped, so onDateRangeOptionSelected is never invoked.
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_SHEET).performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_SHEET).assertDoesNotExist()
        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).assertIsNotSelected()
    }

    @Test
    fun activeDateFilter_selectsTheDateChip() {
        setFilterBar(filter = TransactionFilterState(dateRangeOption = DateRangeOption.THIS_MONTH))

        composeTestRule.onNodeWithTag(FilterBarTestTags.DATE_CHIP).assertIsSelected()
    }
}
