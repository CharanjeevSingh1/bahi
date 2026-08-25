package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

sealed interface BudgetEditorUiState {

    data object Loading : BudgetEditorUiState

    data class Editing(
        val mode: BudgetEditorMode,
        val month: YearMonth,
        val limitText: String = "",
        val categoryId: String? = null,
        /**
         * Excludes Income and Transfers. A budget against either would read
         * ₹0 spent forever -- the totals query only ever sees negative
         * amounts (§2.2) -- which is confusing UI rather than a data bug, and
         * the design puts the fix here in the picker rather than in the
         * schema.
         */
        val categories: ImmutableList<Category> = persistentListOf(),
        val limitTouched: Boolean = false,
        val categoryTouched: Boolean = false,
        val isSaving: Boolean = false,
        val currencyCode: String = "INR",
    ) : BudgetEditorUiState {

        /**
         * Parsed through [Money.parse] rather than a local `toLong`: bank-ish
         * number formats ("8,000", "8000.00") are exactly what it exists to
         * handle, and a second parser here would be a second set of bugs.
         */
        val limit: Money? get() = Money.parse(limitText.trim())

        val limitError: LimitError?
            get() = when {
                limitText.isBlank() -> LimitError.EMPTY
                limit == null -> LimitError.INVALID
                // Zero is rejected, having previously been allowed on the
                // grounds that "I intend to spend nothing here" is a real
                // intention. It is -- but it is not a real *budget*: a zero
                // limit has no state except over budget, so the row is red
                // from the first rupee and stays red, and none of the
                // progress display means anything. An intention to spend
                // nothing is better served by having no budget and watching
                // the category, which costs the user nothing to express.
                // Negative is rejected for the older reason: there is nothing
                // to compare against.
                limit!! <= Money.ZERO -> LimitError.NOT_POSITIVE
                else -> null
            }

        val categoryError: CategoryError? get() = if (categoryId == null) CategoryError.NOT_CHOSEN else null

        val hasErrors: Boolean get() = limitError != null || categoryError != null

        val canSave: Boolean get() = !hasErrors && !isSaving

        val showLimitError: Boolean get() = limitTouched && limitError != null
        val showCategoryError: Boolean get() = categoryTouched && categoryError != null

        val selectedCategory: Category? get() = categories.firstOrNull { it.id == categoryId }
    }

    data class Error(val message: String) : BudgetEditorUiState
}

enum class BudgetEditorMode { ADD, EDIT }

enum class LimitError { EMPTY, INVALID, NOT_POSITIVE }

internal object BudgetEditorTestTags {
    const val LOADING = "budgetEditor:loading"
    const val LIMIT_FIELD = "budgetEditor:limit"
    const val LIMIT_ERROR = "budgetEditor:limitError"
    const val CATEGORY_FIELD = "budgetEditor:category"
    const val SAVE = "budgetEditor:save"

    fun categoryOption(categoryId: String) = "budgetEditor:categoryOption:$categoryId"
}
