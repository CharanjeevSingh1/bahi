package dev.charanjeev.bahi.feature.budgets

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val food = Category(id = "food", name = "Food", colorArgb = 0, iconKey = "restaurant")
    private val transport = Category(id = "transport", name = "Transport", colorArgb = 0, iconKey = "train")

    private val ruleRepository = FakeCategoryRuleRepository()
    private val categoryRepository = FakeCategoryRepository(listOf(food, transport))

    private fun viewModel() = RulesViewModel(ruleRepository, categoryRepository)

    private suspend fun seedRules(vararg rules: CategoryRule) = rules.forEach { ruleRepository.upsert(it) }

    private fun rule(
        id: String = "rule-1",
        categoryId: String = "food",
        merchantContains: String = "SWIGGY",
        priority: Int = 0,
    ) = CategoryRule(id, categoryId, merchantContains, priority)

    @Test
    fun `starts in loading state`() = runTest {
        assertThat(viewModel().uiState.value).isEqualTo(RulesUiState.Loading)
    }

    @Test
    fun `emits empty when there are no rules`() = runTest {
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            // Empty rather than Success with an empty list: with nothing to
            // run, "recategorise uncategorised transactions" has no meaning,
            // so the state that offers it is not the state to be in.
            assertThat(awaitItem()).isEqualTo(RulesUiState.Empty)
        }
    }

    @Test
    fun `lists rules in evaluation order with their categories resolved`() = runTest {
        seedRules(
            rule(id = "b", categoryId = "transport", merchantContains = "UBER", priority = 1),
            rule(id = "a", categoryId = "food", merchantContains = "SWIGGY", priority = 0),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RulesUiState.Success
            // Priority order, which is the order they actually apply in --
            // the screen shows what the engine does, not an alphabetised view.
            assertThat(state.rules.map { it.rule.id }).containsExactly("a", "b").inOrder()
            assertThat(state.rules.map { it.category?.name }).containsExactly("Food", "Transport").inOrder()
        }
    }

    @Test
    fun `a rule whose category has not arrived yet renders without one rather than crashing`() = runTest {
        categoryRepository.emit(emptyList())
        seedRules(rule(categoryId = "food"))
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as RulesUiState.Success).rules.single().category).isNull()
        }
    }

    // --- reorder ---

    @Test
    fun `moving a rule down sends the whole new order`() = runTest {
        seedRules(rule(id = "a", priority = 0), rule(id = "b", merchantContains = "UBER", priority = 1))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onMoveDown("a")
        advanceUntilIdle()

        // The complete order, not "move a down" -- position is what becomes
        // priority, so the repository is told the result, not the gesture.
        assertThat(ruleRepository.reorderedTo).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `moving a rule up sends the whole new order`() = runTest {
        seedRules(rule(id = "a", priority = 0), rule(id = "b", merchantContains = "UBER", priority = 1))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onMoveUp("b")
        advanceUntilIdle()

        assertThat(ruleRepository.reorderedTo).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `moving the first rule up does nothing`() = runTest {
        seedRules(rule(id = "a", priority = 0), rule(id = "b", merchantContains = "UBER", priority = 1))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onMoveUp("a")
        advanceUntilIdle()

        // Ignored rather than clamped: a stale tap on a disabled button must
        // not silently reorder something the user wasn't aiming at.
        assertThat(ruleRepository.reorderedTo).isNull()
    }

    @Test
    fun `moving the last rule down does nothing`() = runTest {
        seedRules(rule(id = "a", priority = 0), rule(id = "b", merchantContains = "UBER", priority = 1))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onMoveDown("b")
        advanceUntilIdle()

        assertThat(ruleRepository.reorderedTo).isNull()
    }

    @Test
    fun `reordering changes which rule wins a conflict`() = runTest {
        // The whole reason priority is editable: two rules matching the same
        // description, resolved by order rather than by asking the user.
        seedRules(
            rule(id = "a", categoryId = "food", merchantContains = "SWIGGY", priority = 0),
            rule(id = "b", categoryId = "transport", merchantContains = "SWIGGY", priority = 1),
        )
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "SWIGGY ORDER"))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(ruleRepository.previewRecategoriseUncategorised().assignments)
            .containsExactly("t1", "food")

        viewModel.onMoveDown("a")
        advanceUntilIdle()

        assertThat(ruleRepository.previewRecategoriseUncategorised().assignments)
            .containsExactly("t1", "transport")
    }

    // --- delete ---

    @Test
    fun `deleting asks first and names the rule`() = runTest {
        seedRules(rule(id = "a", merchantContains = "SWIGGY"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onDeleteRequested("a")
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val pending = (awaitItem() as RulesUiState.Success).pendingDelete
            assertThat(pending?.merchantContains).isEqualTo("SWIGGY")
        }
        assertThat(ruleRepository.deleted).isEmpty()
    }

    @Test
    fun `cancelling a delete leaves the rule alone`() = runTest {
        seedRules(rule(id = "a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDeleteRequested("a")

        viewModel.onDeleteCancelled()
        advanceUntilIdle()

        assertThat(ruleRepository.deleted).isEmpty()
    }

    @Test
    fun `confirming a delete removes the rule`() = runTest {
        seedRules(rule(id = "a"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onDeleteRequested("a")

        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertThat(ruleRepository.deleted).containsExactly("a")
    }

    // --- recategorise: preview before write ---

    @Test
    fun `recategorising previews rather than writing`() = runTest {
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(
            TestData.transaction(id = "t1", description = "SWIGGY ORDER"),
            TestData.transaction(id = "t2", description = "SWIGGY LATE NIGHT"),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val dialog = (awaitItem() as RulesUiState.Success).dialog as RuleApplyDialog.Confirm
            assertThat(dialog.matchedCount).isEqualTo(2)
        }
        // The button previews. There is no path from it straight to a write.
        assertThat(ruleRepository.applied).isEmpty()
        assertThat(ruleRepository.transaction("t1")?.categoryId).isNull()
    }

    @Test
    fun `the preview reports locked transactions as skipped`() = runTest {
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(
            TestData.transaction(id = "open", description = "SWIGGY ORDER"),
            TestData.transaction(id = "locked", description = "SWIGGY DINNER").copy(categoryLockedByUser = true),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val dialog = (awaitItem() as RulesUiState.Success).dialog as RuleApplyDialog.Confirm
            assertThat(dialog.matchedCount).isEqualTo(1)
            assertThat(dialog.lockedSkippedCount).isEqualTo(1)
        }
    }

    @Test
    fun `dismissing the preview writes nothing`() = runTest {
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "SWIGGY ORDER"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.onDialogDismissed()
        advanceUntilIdle()

        assertThat(ruleRepository.applied).isEmpty()
        assertThat(ruleRepository.transaction("t1")?.categoryId).isNull()
    }

    @Test
    fun `confirming applies and reports how many actually changed`() = runTest {
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "SWIGGY ORDER"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.onApplyConfirmed()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val dialog = (awaitItem() as RulesUiState.Success).dialog as RuleApplyDialog.Done
            assertThat(dialog.changedCount).isEqualTo(1)
        }
        assertThat(ruleRepository.transaction("t1")?.categoryId).isEqualTo("food")
    }

    @Test
    fun `applying commits the previewed set rather than matching again`() = runTest {
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "SWIGGY ORDER"))
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.onApplyConfirmed()
        advanceUntilIdle()

        // The same object the count came from -- the number consented to and
        // the rows written cannot be two different answers.
        assertThat(ruleRepository.applied.single().assignments).containsExactly("t1", "food")
    }

    @Test
    fun `nothing matching gets an acknowledgement rather than a confirm button`() = runTest {
        seedRules(rule(merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(TestData.transaction(id = "t1", description = "RENT"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            val state = awaitItem() as RulesUiState.Success
            assertThat(state.dialog).isInstanceOf(RuleApplyDialog.NothingToDo::class.java)
        }
    }

    @Test
    fun `recategorising leaves an already-categorised transaction alone`() = runTest {
        // "Fill in the blanks" must not quietly rearrange categories the user
        // already set, even ones they never explicitly locked.
        seedRules(rule(categoryId = "food", merchantContains = "SWIGGY"))
        ruleRepository.seedTransactions(
            TestData.transaction(id = "filed", description = "SWIGGY ORDER", categoryId = "transport"),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onRecategoriseRequested()
        advanceUntilIdle()

        viewModel.uiState.test {
            skipItems(1) // Loading
            assertThat((awaitItem() as RulesUiState.Success).dialog)
                .isInstanceOf(RuleApplyDialog.NothingToDo::class.java)
        }
    }
}
