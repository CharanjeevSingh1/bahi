package dev.charanjeev.bahi.feature.budgets

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.core.testing.TestData
import dev.charanjeev.bahi.feature.budgets.navigation.RuleIdArg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleEditorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val food = Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant")
    private val shopping = Category(id = "shopping", name = "Shopping", colorArgb = 0, iconKey = "shopping")

    private val ruleRepository = FakeCategoryRuleRepository()
    private val categoryRepository = FakeCategoryRepository(listOf(food, shopping))

    private fun viewModel(ruleId: String? = null) = RuleEditorViewModel(
        ruleRepository,
        categoryRepository,
        SavedStateHandle(if (ruleId == null) emptyMap() else mapOf(RuleIdArg to ruleId)),
    )

    // --- the blank-rule guard, at the outermost layer ---

    @Test
    fun `a new rule cannot be saved before anything is typed`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            // `contains("")` is true of every string, so this is not a
            // formatting nicety -- a blank rule would file the user's entire
            // history under one category.
            assertThat(state.canSave).isFalse()
            assertThat(state.merchantError).isEqualTo(MerchantError.BLANK)
        }
    }

    @Test
    fun `a merchant string of only whitespace still cannot be saved`() = runTest {
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")
        viewModel.onMerchantContainsChange("   ")

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            // isBlank, not isEmpty: spaces read as "I typed something" but
            // trim to the same catastrophic empty needle.
            assertThat(state.canSave).isFalse()
            assertThat(state.merchantError).isEqualTo(MerchantError.BLANK)
        }
    }

    @Test
    fun `onSave refuses a blank rule even when called directly`() = runTest {
        // The screen disables the button, but canSave is a property of a
        // state object -- a redesign or a new entry point that stopped
        // consulting it must not be one mistake away from persisting a rule
        // that matches everything.
        val viewModel = viewModel()
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        assertThat(ruleRepository.upserted).isEmpty()
    }

    @Test
    fun `onSave refuses when no category has been chosen`() = runTest {
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")

        viewModel.onSave()
        advanceUntilIdle()

        assertThat(ruleRepository.upserted).isEmpty()
    }

    @Test
    fun `the blank error is not shown before the user has been in the field`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            // Save is already blocked; scolding someone for not having typed
            // yet is a different thing from stopping them submitting.
            assertThat(state.showMerchantError).isFalse()
            assertThat(state.canSave).isFalse()
        }
    }

    @Test
    fun `the blank error appears once the field has been typed in and cleared`() = runTest {
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onMerchantContainsChange("")

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            assertThat(state.showMerchantError).isTrue()
            assertThat(state.canSave).isFalse()
        }
    }

    @Test
    fun `a complete rule can be saved`() = runTest {
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as RuleEditorUiState.Editing).canSave).isTrue()
        }
    }

    @Test
    fun `saving trims the merchant string`() = runTest {
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("  SWIGGY  ")
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        assertThat(ruleRepository.upserted.single().merchantContains).isEqualTo("SWIGGY")
    }

    @Test
    fun `an over-long merchant string is clamped as it is typed`() = runTest {
        val viewModel = viewModel()

        viewModel.onMerchantContainsChange("X".repeat(MERCHANT_CONTAINS_MAX_LENGTH + 50))

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            assertThat(state.merchantContains).hasLength(MERCHANT_CONTAINS_MAX_LENGTH)
        }
    }

    // --- apply-to-existing: preview before write ---

    @Test
    fun `saving offers a preview instead of silently recategorising`() = runTest {
        ruleRepository.seedTransactions(
            TestData.transaction(id = "t1", description = "SWIGGY ORDER"),
            TestData.transaction(id = "t2", description = "SWIGGY DINNER"),
        )
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            val dialog = state.dialog as RuleApplyDialog.Confirm
            assertThat(dialog.matchedCount).isEqualTo(2)
            // Nothing written yet -- the count is an offer, not a report.
            assertThat(ruleRepository.applied).isEmpty()
            assertThat(ruleRepository.transaction("t1")?.categoryId).isNull()
        }
    }

    @Test
    fun `the preview says how many locked transactions will be skipped`() = runTest {
        ruleRepository.seedTransactions(
            TestData.transaction(id = "unlocked", description = "SWIGGY ORDER"),
            TestData.transaction(id = "locked", description = "SWIGGY DINNER", categoryId = "shopping")
                .copy(categoryLockedByUser = true),
        )
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val dialog = (awaitItem() as RuleEditorUiState.Editing).dialog as RuleApplyDialog.Confirm
            // Two numbers, not one. "1 will change" reads as wrong to someone
            // looking at two transactions that obviously match.
            assertThat(dialog.matchedCount).isEqualTo(1)
            assertThat(dialog.lockedSkippedCount).isEqualTo(1)
        }
    }

    @Test
    fun `declining the preview leaves existing transactions alone but keeps the rule`() = runTest {
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "SWIGGY ORDER"))
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")
        viewModel.onSave()
        advanceUntilIdle()

        viewModel.onApplyDeclined()
        advanceUntilIdle()

        assertThat(ruleRepository.applied).isEmpty()
        assertThat(ruleRepository.transaction("t1")?.categoryId).isNull()
        // The rule itself is saved either way -- applying it to history is a
        // separate decision, not a condition of saving.
        assertThat(ruleRepository.upserted.single().merchantContains).isEqualTo("SWIGGY")
    }

    @Test
    fun `confirming applies exactly the previewed set and reports what changed`() = runTest {
        ruleRepository.seedTransactions(
            TestData.transaction(id = "t1", description = "SWIGGY ORDER"),
            TestData.transaction(id = "other", description = "RENT"),
        )
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")
        viewModel.onSave()
        advanceUntilIdle()

        viewModel.onApplyConfirmed()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val dialog = (awaitItem() as RuleEditorUiState.Editing).dialog as RuleApplyDialog.Done
            assertThat(dialog.changedCount).isEqualTo(1)
            assertThat(dialog.fellShort).isFalse()
        }
        assertThat(ruleRepository.transaction("t1")?.categoryId).isEqualTo("food")
        assertThat(ruleRepository.transaction("other")?.categoryId).isNull()
    }

    @Test
    fun `a rule matching nothing gets an acknowledgement, not a confirm button`() = runTest {
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "RENT"))
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            // Not Confirm(0): there is nothing to consent to, and an Apply
            // button that would do nothing invites "what did that do?".
            assertThat(state.dialog).isInstanceOf(RuleApplyDialog.NothingToDo::class.java)
        }
    }

    @Test
    fun `a new rule is appended rather than outranking rules the user already ordered`() = runTest {
        ruleRepository.upsert(CategoryRule("existing", "shopping", "AMAZON", priority = 0))
        val viewModel = viewModel()
        viewModel.onMerchantContainsChange("SWIGGY")
        viewModel.onCategorySelected("food")

        viewModel.onSave()
        advanceUntilIdle()

        assertThat(ruleRepository.upserted.last().priority).isEqualTo(1)
    }

    // --- edit mode ---

    @Test
    fun `editing seeds the form from the stored rule`() = runTest {
        ruleRepository.upsert(CategoryRule("rule-1", "shopping", "AMAZON", priority = 3))
        val viewModel = viewModel(ruleId = "rule-1")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RuleEditorUiState.Editing
            assertThat(state.mode).isEqualTo(RuleEditorMode.EDIT)
            assertThat(state.merchantContains).isEqualTo("AMAZON")
            assertThat(state.categoryId).isEqualTo("shopping")
        }
    }

    @Test
    fun `editing preserves the priority the screen never shows`() = runTest {
        ruleRepository.upsert(CategoryRule("rule-1", "shopping", "AMAZON", priority = 3))
        val viewModel = viewModel(ruleId = "rule-1")
        advanceUntilIdle()
        viewModel.onMerchantContainsChange("AMAZON.IN")

        viewModel.onSave()
        advanceUntilIdle()

        assertThat(ruleRepository.upserted.last().priority).isEqualTo(3)
        assertThat(ruleRepository.upserted.last().id).isEqualTo("rule-1")
    }

    @Test
    fun `editing a rule that no longer exists surfaces an error rather than an empty form`() = runTest {
        val viewModel = viewModel(ruleId = "gone")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat(awaitItem()).isInstanceOf(RuleEditorUiState.Error::class.java)
        }
    }
}
