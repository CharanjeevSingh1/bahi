package dev.charanjeev.bahi.feature.transactions

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.FixedClock
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.core.testing.TestData
import dev.charanjeev.bahi.feature.transactions.navigation.TransactionIdArg
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

class TransactionFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val transactionRepository = FakeTransactionRepository()
    private val categoryRepository = FakeCategoryRepository()
    private val clock = FixedClock(LocalDate(2026, 3, 14))

    private fun addViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()) =
        TransactionFormViewModel(transactionRepository, categoryRepository, clock, savedStateHandle)

    private fun editViewModel(
        transactionId: String,
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf(TransactionIdArg to transactionId)),
    ) = TransactionFormViewModel(transactionRepository, categoryRepository, clock, savedStateHandle)

    @Test
    fun `add mode starts editable immediately with today's date and no id to load`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(TransactionFormUiState.Loading)

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.mode).isEqualTo(FormMode.ADD)
            assertThat(state.date).isEqualTo(LocalDate(2026, 3, 14))
            assertThat(state.amountText).isEmpty()
        }
    }

    @Test
    fun `edit mode loads the transaction and prefills the form as an expense`() = runTest {
        transactionRepository.upsert(
            TestData.transaction(id = "a", amount = Money(-45000), description = "COFFEE", categoryId = "food"),
        )
        val viewModel = editViewModel("a")

        viewModel.uiState.test {
            skipItems(1) // Loading

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.mode).isEqualTo(FormMode.EDIT)
            assertThat(state.description).isEqualTo("COFFEE")
            assertThat(state.amountText).isEqualTo("450.00")
            assertThat(state.type).isEqualTo(TransactionType.EXPENSE)
            assertThat(state.categoryId).isEqualTo("food")
        }
    }

    @Test
    fun `edit mode prefills income for a positive amount`() = runTest {
        transactionRepository.upsert(TestData.transaction(id = "a", amount = Money(150_000_00)))
        val viewModel = editViewModel("a")

        viewModel.uiState.test {
            skipItems(1) // Loading

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.type).isEqualTo(TransactionType.INCOME)
            assertThat(state.amountText).isEqualTo("150000.00")
        }
    }

    @Test
    fun `loading a transaction id that no longer exists shows an error`() = runTest {
        // The fake's backing flow has to have emitted at least once for
        // observeTransaction(id).first() to resolve at all -- an id that was
        // simply never inserted would hang forever waiting for a first value,
        // same as a real Room query would just never complete on an open cursor.
        transactionRepository.emit(emptyList())
        val viewModel = editViewModel("missing")

        viewModel.uiState.test {
            skipItems(1) // Loading

            assertThat(awaitItem()).isInstanceOf(TransactionFormUiState.Error::class.java)
        }
    }

    // --- Amount input: the cases from the design conversation ---

    @Test
    fun `typing sanitizes commas out of the amount as it's entered`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("1,2")

            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("12")
        }
    }

    @Test
    fun `pasting into a non-empty amount field sanitizes the whole resulting string, not just the paste`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("45")
            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("45")

            // Compose delivers a paste as one onValueChange carrying the whole
            // new value -- "45" already present, pasting "₹1,234.56" arrives
            // here as "45₹1,234.56" before filtering, not the pasted text alone.
            viewModel.onAmountTextChange("45₹1,234.56")
            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("451234.56")
        }
    }

    @Test
    fun `a second decimal point typed later in the field is dropped`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("12.3.4")

            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("12.34")
        }
    }

    @Test
    fun `blur formats a valid partial amount to the currency's decimal places`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("12.")
            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("12.")

            viewModel.onAmountFieldFocusLost()
            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo("12.00")
        }
    }

    @Test
    fun `blur leaves a lone decimal point unchanged instead of formatting it to zero`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange(".")
            assertThat((awaitItem() as TransactionFormUiState.Editing).amountText).isEqualTo(".")

            // Money.parse(".") alone returns Money(0) -- if onAmountFieldFocusLost
            // trusted that the way the save path does, this typo would silently
            // become a real zero-value transaction on blur. It shouldn't.
            viewModel.onAmountFieldFocusLost()
            expectNoEvents()

            assertThat(viewModel.uiState.value)
                .isInstanceOf(TransactionFormUiState.Editing::class.java)
            val stillDot = viewModel.uiState.value as TransactionFormUiState.Editing
            assertThat(stillDot.amountText).isEqualTo(".")
            assertThat(stillDot.amountError).isEqualTo(AmountError.INVALID)
        }
    }

    // --- Sign ---

    @Test
    fun `expense produces a negative amount`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("100")
            awaitItem()
            viewModel.onDescriptionChange("Coffee")
            awaitItem()

            viewModel.onSave()
            awaitItem() // isSaving = true
            awaitItem() // isSaving = false, right before navigating back
        }

        assertThat(transactionRepository.upserted.single().amount).isEqualTo(Money(-10000))
    }

    @Test
    fun `switching to income before saving produces a positive amount`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("100")
            awaitItem()
            viewModel.onDescriptionChange("Salary")
            awaitItem()
            viewModel.onTypeChange(TransactionType.INCOME)
            awaitItem()

            viewModel.onSave()
            awaitItem() // isSaving = true
            awaitItem() // isSaving = false
        }

        assertThat(transactionRepository.upserted.single().amount).isEqualTo(Money(10000))
    }

    // --- Validation ---

    @Test
    fun `empty amount is not shown as an error until save is attempted`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.amountError).isEqualTo(AmountError.EMPTY)
            assertThat(state.showAmountError).isFalse()
        }
    }

    @Test
    fun `saving with an empty amount is blocked and surfaces the error`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onDescriptionChange("Coffee")
            awaitItem()

            viewModel.onSave()
            val afterSave = awaitItem() as TransactionFormUiState.Editing
            assertThat(afterSave.showAmountError).isTrue()
        }
        assertThat(transactionRepository.upserted).isEmpty()
    }

    @Test
    fun `saving with a missing description is blocked and surfaces the error`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("100")
            awaitItem()

            viewModel.onSave()
            val afterSave = awaitItem() as TransactionFormUiState.Editing
            assertThat(afterSave.showDescriptionError).isTrue()
        }
        assertThat(transactionRepository.upserted).isEmpty()
    }

    @Test
    fun `typing past the description limit is truncated as it's entered`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onDescriptionChange("a".repeat(150))

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.description).hasLength(DESCRIPTION_MAX_LENGTH)
        }
    }

    @Test
    fun `a description already over the limit when a transaction loads is blocked from saving`() = runTest {
        transactionRepository.upsert(
            TestData.transaction(id = "a", description = "a".repeat(DESCRIPTION_MAX_LENGTH + 1)),
        )
        val viewModel = editViewModel("a")

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, prefilled

            viewModel.onSave()
            val afterSave = awaitItem() as TransactionFormUiState.Editing
            assertThat(afterSave.showDescriptionError).isTrue()
            assertThat(afterSave.descriptionError).isEqualTo(DescriptionError.TOO_LONG)
        }
        assertThat(transactionRepository.updated).isEmpty()
    }

    // --- Category ---

    @Test
    fun `selecting a category locks it to the user's choice`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onCategorySelected("food")

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.categoryId).isEqualTo("food")
            assertThat(state.categoryLockedByUser).isTrue()
        }
    }

    // --- Editing bumps the revision, not upsert ---

    @Test
    fun `saving an edit calls update, not upsert`() = runTest {
        transactionRepository.upsert(TestData.transaction(id = "a", description = "OLD"))
        val viewModel = editViewModel("a")

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, prefilled

            viewModel.onDescriptionChange("NEW")
            awaitItem()

            viewModel.onSave()
            awaitItem() // isSaving = true
            awaitItem() // isSaving = false
        }

        assertThat(transactionRepository.updated.single().description).isEqualTo("NEW")
        assertThat(transactionRepository.upserted).hasSize(1) // only the setup call
    }

    @Test
    fun `saving a new transaction calls upsert, not update`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onAmountTextChange("100")
            awaitItem()
            viewModel.onDescriptionChange("Coffee")
            awaitItem()

            viewModel.onSave()
            awaitItem() // isSaving = true
            awaitItem() // isSaving = false
        }

        assertThat(transactionRepository.upserted).hasSize(1)
        assertThat(transactionRepository.updated).isEmpty()
    }

    @Test
    fun `deleting calls the repository and navigates back`() = runTest {
        transactionRepository.upsert(TestData.transaction(id = "a"))
        val viewModel = editViewModel("a")

        // onDelete reads the transaction id off the async fetch that edit
        // mode kicks off in init{} -- give it a turn to complete first,
        // otherwise "original" is still null and onDelete is a no-op.
        viewModel.uiState.test {
            skipItems(1) // Loading
            awaitItem() // loaded, prefilled
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.events.test {
            viewModel.onDelete()
            assertThat(awaitItem()).isEqualTo(TransactionFormEvent.NavigateBack)
        }

        assertThat(transactionRepository.deletedIds).containsExactly("a")
    }

    // --- Unsaved-changes confirmation ---

    @Test
    fun `back with no changes navigates back immediately`() = runTest {
        val viewModel = addViewModel()

        viewModel.events.test {
            viewModel.onBackRequested()
            assertThat(awaitItem()).isEqualTo(TransactionFormEvent.NavigateBack)
        }
    }

    @Test
    fun `back with unsaved changes asks for confirmation instead of navigating`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onDescriptionChange("Coffee")
            awaitItem()

            viewModel.onBackRequested()
            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.showDiscardConfirmation).isTrue()
        }
    }

    @Test
    fun `confirming discard navigates back`() = runTest {
        val viewModel = addViewModel()

        viewModel.uiState.test {
            skipItems(1) // Loading
            skipItems(1) // loaded, empty

            viewModel.onDescriptionChange("Coffee")
            awaitItem()
            viewModel.onBackRequested()
            awaitItem() // showDiscardConfirmation = true

            viewModel.events.test {
                viewModel.onDiscardConfirmed()
                assertThat(awaitItem()).isEqualTo(TransactionFormEvent.NavigateBack)
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- SavedStateHandle survives process death ---

    @Test
    fun `restoring from a saved state handle keeps a dirty form's edits instead of the fetched transaction`() = runTest {
        transactionRepository.upsert(TestData.transaction(id = "a", description = "ORIGINAL"))

        // Simulates process death mid-edit: the handle already carries the
        // in-progress values a real process-death restore would replay.
        val restoredHandle = SavedStateHandle(
            mapOf(
                TransactionIdArg to "a",
                "amountText" to "999.00",
                "description" to "EDITED BEFORE DEATH",
                "isDirty" to true,
                // A real process-death restore carries this too, since it was
                // set the first time the transaction was fetched, before death.
                "hasSeededFromRepository" to true,
            ),
        )
        val viewModel = editViewModel("a", restoredHandle)

        viewModel.uiState.test {
            skipItems(1) // Loading

            val state = awaitItem() as TransactionFormUiState.Editing
            assertThat(state.description).isEqualTo("EDITED BEFORE DEATH")
            assertThat(state.amountText).isEqualTo("999.00")
            assertThat(state.isDirty).isTrue()
        }
    }
}
