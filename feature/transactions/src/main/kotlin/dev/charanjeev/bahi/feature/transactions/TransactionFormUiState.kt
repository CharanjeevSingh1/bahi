package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.Category
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate

/**
 * A sealed UI state rather than a bag of nullable fields, matching
 * TransactionsUiState -- Loading and Editing need to be distinguishable
 * while the transaction is still being fetched for an edit.
 */
sealed interface TransactionFormUiState {

    data object Loading : TransactionFormUiState

    data class Editing(
        val mode: FormMode,
        /** Always the output of sanitizeAmountInput -- never raw keyboard/paste text. */
        val amountText: String = "",
        val type: TransactionType = TransactionType.EXPENSE,
        val date: LocalDate,
        val description: String = "",
        val categoryId: String? = null,
        val categoryLockedByUser: Boolean = false,
        val notes: String = "",
        val categories: ImmutableList<Category> = persistentListOf(),
        val currencyCode: String = "INR",
        /** Errors are computed, not stored, so they can never drift from the fields they describe. */
        val submitAttempted: Boolean = false,
        val isDirty: Boolean = false,
        val isSaving: Boolean = false,
        val showDiscardConfirmation: Boolean = false,
    ) : TransactionFormUiState {

        val amountError: AmountError?
            get() = when {
                amountText.isBlank() -> AmountError.EMPTY
                parseAmountMagnitude(amountText, decimalPlacesFor(currencyCode)) == null -> AmountError.INVALID
                else -> null
            }

        val descriptionError: Boolean get() = description.isBlank()

        val hasErrors: Boolean get() = amountError != null || descriptionError

        // Errors exist as soon as a required field is empty, but showing them
        // before the user has tried to save reads as the form scolding them
        // for not having typed anything yet.
        val showAmountError: Boolean get() = submitAttempted && amountError != null
        val showDescriptionError: Boolean get() = submitAttempted && descriptionError
    }

    data class Error(val message: String) : TransactionFormUiState
}

enum class FormMode { ADD, EDIT }

enum class TransactionType { EXPENSE, INCOME }

enum class AmountError { EMPTY, INVALID }
