@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val categories = persistentListOf(
        Category(id = "food", name = "Food", colorArgb = 0xFFEF5350.toInt(), iconKey = "restaurant"),
        Category(id = "groceries", name = "Groceries", colorArgb = 0xFF66BB6A.toInt(), iconKey = "cart"),
        Category(id = "rent", name = "Rent", colorArgb = 0xFF8D6E63.toInt(), iconKey = "home"),
    )

    @Test
    fun typingInSearch_filtersCategoriesByName() {
        composeTestRule.setContent {
            var query by remember { mutableStateOf("") }
            CategoryPickerSheet(
                categories = categories,
                isSelected = { false },
                onCategoryClick = {},
                onDismissRequest = {},
                searchQuery = query,
                onSearchQueryChange = { query = it },
            )
        }

        composeTestRule.onNodeWithTag(CategoryPickerTestTags.SEARCH_FIELD).performTextInput("gro")

        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("Food").assertDoesNotExist()
        composeTestRule.onNodeWithText("Rent").assertDoesNotExist()
    }

    @Test
    fun searchWithNoMatches_showsNoResultsMessage() {
        composeTestRule.setContent {
            var query by remember { mutableStateOf("") }
            CategoryPickerSheet(
                categories = categories,
                isSelected = { false },
                onCategoryClick = {},
                onDismissRequest = {},
                searchQuery = query,
                onSearchQueryChange = { query = it },
            )
        }

        composeTestRule.onNodeWithTag(CategoryPickerTestTags.SEARCH_FIELD).performTextInput("xyz")

        composeTestRule.onNodeWithTag(CategoryPickerTestTags.NO_RESULTS).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.category_picker_no_results, "xyz")).assertIsDisplayed()
    }

    @Test
    fun tappingACategory_invokesOnCategoryClickWithIt() {
        var clicked: Category? = null
        composeTestRule.setContent {
            CategoryPickerSheet(
                categories = categories,
                isSelected = { false },
                onCategoryClick = { clicked = it },
                onDismissRequest = {},
                searchQuery = "",
                onSearchQueryChange = {},
            )
        }

        composeTestRule.onNodeWithTag(CategoryPickerTestTags.categoryRowTag("groceries")).performClick()

        assertThat(clicked?.id).isEqualTo("groceries")
    }

    @Test
    fun theCurrentSelectionShowsACheckmark() {
        composeTestRule.setContent {
            CategoryPickerSheet(
                categories = categories,
                isSelected = { it.id == "food" },
                onCategoryClick = {},
                onDismissRequest = {},
                searchQuery = "",
                onSearchQueryChange = {},
            )
        }

        // useUnmergedTree: the row's own clickable merges its descendants'
        // semantics, including the icon's testTag, into the row's single
        // merged node -- the icon is only independently queryable if we ask
        // for the unmerged tree.
        composeTestRule
            .onNodeWithTag(CategoryPickerTestTags.categorySelectedTag("food"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(CategoryPickerTestTags.categorySelectedTag("groceries"), useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
