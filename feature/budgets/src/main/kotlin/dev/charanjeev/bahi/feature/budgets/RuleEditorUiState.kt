package dev.charanjeev.bahi.feature.budgets

import dev.charanjeev.bahi.core.model.Category
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Long enough for a real bank merchant string, short enough that a paste can't become a rule nobody can read. */
internal const val MERCHANT_CONTAINS_MAX_LENGTH = 120

sealed interface RuleEditorUiState {

    data object Loading : RuleEditorUiState

    data class Editing(
        val mode: RuleEditorMode,
        val merchantContains: String = "",
        val categoryId: String? = null,
        val categories: ImmutableList<Category> = persistentListOf(),
        /**
         * Errors show once the user has been in the field, not on arrival --
         * a fresh Add screen scolding someone for not having typed yet reads
         * as broken. Distinct from the transaction form's `submitAttempted`,
         * which works there because that form's save button is always
         * enabled; this one's is not (see [canSave]), so a submit attempt
         * that never happens can't be the thing that reveals the error.
         */
        val merchantTouched: Boolean = false,
        val categoryTouched: Boolean = false,
        val isSaving: Boolean = false,
        val dialog: RuleApplyDialog? = null,
    ) : RuleEditorUiState {

        /**
         * Blank is the only merchant error, and it is not a formatting
         * nicety. `"".contains` is true of every string, so a blank rule
         * doesn't match nothing -- it matches every transaction the user has
         * and files all of them under one category
         * (docs/budgets-design.md §1.1).
         */
        val merchantError: MerchantError? get() = if (merchantContains.isBlank()) MerchantError.BLANK else null

        val categoryError: CategoryError? get() = if (categoryId == null) CategoryError.NOT_CHOSEN else null

        val hasErrors: Boolean get() = merchantError != null || categoryError != null

        /**
         * Drives the save action's enabled state, so an invalid rule cannot
         * be submitted at all rather than being submitted and rejected.
         *
         * This is the outermost of four guards on the blank case, and the
         * only one the user ever sees: the ViewModel refuses independently of
         * this flag, the repository throws rather than storing a blank rule,
         * and the matching engine filters blank needles out even if one were
         * somehow persisted. Each of the inner three is defence; none of them
         * should ever be what stops a real user.
         */
        val canSave: Boolean get() = !hasErrors && !isSaving

        val showMerchantError: Boolean get() = merchantTouched && merchantError != null
        val showCategoryError: Boolean get() = categoryTouched && categoryError != null

        val selectedCategory: Category? get() = categories.firstOrNull { it.id == categoryId }
    }

    data class Error(val message: String) : RuleEditorUiState
}

enum class RuleEditorMode { ADD, EDIT }

enum class MerchantError { BLANK }

enum class CategoryError { NOT_CHOSEN }

internal object RuleEditorTestTags {
    const val LOADING = "ruleEditor:loading"
    const val MERCHANT_FIELD = "ruleEditor:merchant"
    const val MERCHANT_ERROR = "ruleEditor:merchantError"
    const val CATEGORY_FIELD = "ruleEditor:category"
    const val CATEGORY_MENU = "ruleEditor:categoryMenu"
    const val SAVE = "ruleEditor:save"
    const val CONFIRM_DIALOG = "ruleEditor:confirmDialog"
    const val CONFIRM_APPLY = "ruleEditor:confirmApply"
    const val CONFIRM_SKIP = "ruleEditor:confirmSkip"
    const val NOTHING_TO_DO_DIALOG = "ruleEditor:nothingToDoDialog"
    const val DONE_DIALOG = "ruleEditor:doneDialog"

    fun categoryOption(categoryId: String) = "ruleEditor:categoryOption:$categoryId"
}
