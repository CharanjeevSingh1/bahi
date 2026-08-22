package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate

/**
 * A sealed UI state rather than a bag of nullable fields: it makes
 * "loading with stale data" and "empty" distinguishable, which is exactly the
 * distinction an offline-first app has to get right.
 */
sealed interface TransactionsUiState {
    data object Loading : TransactionsUiState

    /** No transactions exist at all -- onboarding copy, not to be confused with [EmptyFiltered]. */
    data object Empty : TransactionsUiState

    /**
     * At least one transaction exists, but none match the active filter.
     * Distinct from [Empty]: the fix is to clear the filter, not to add a
     * transaction. Still carries a net total -- zero, since nothing matched
     * -- so the top bar shows "...₹0.00" rather than nothing at all, which
     * would read as a rendering bug instead of a deliberate answer.
     */
    data class EmptyFiltered(
        val filter: TransactionFilterState,
        val availableCategories: ImmutableList<Category> = persistentListOf(),
        val netTotal: Money = Money.ZERO,
        val netPeriod: NetPeriod = NetPeriod.Month(LocalDate(1970, 1, 1)),
        val currencyCode: String = "INR",
    ) : TransactionsUiState

    data class Success(
        val groups: ImmutableList<TransactionGroup> = persistentListOf(),
        /** Income minus expenses over [netPeriod] -- see netPeriod's own doc for what that covers. */
        val netTotal: Money = Money.ZERO,
        val netPeriod: NetPeriod = NetPeriod.Month(LocalDate(1970, 1, 1)),
        val currencyCode: String = "INR",
        /**
         * The transaction a swipe just deleted, kept here -- not in row-local
         * composable state -- because the row that triggered it leaves
         * composition the moment the delete lands. Non-null drives the undo
         * snackbar; the ViewModel clears it once the snackbar resolves.
         */
        val pendingDelete: TransactionListItem? = null,
        val filter: TransactionFilterState = TransactionFilterState(),
        val availableCategories: ImmutableList<Category> = persistentListOf(),
    ) : TransactionsUiState

    data class Error(val message: String) : TransactionsUiState
}

data class TransactionListItem(
    val transaction: Transaction,
    /** Null means uncategorised, not "not loaded yet" -- categories load eagerly. */
    val category: Category?,
)
