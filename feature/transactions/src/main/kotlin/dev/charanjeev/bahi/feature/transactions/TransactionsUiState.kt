package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A sealed UI state rather than a bag of nullable fields: it makes
 * "loading with stale data" and "empty" distinguishable, which is exactly the
 * distinction an offline-first app has to get right.
 */
sealed interface TransactionsUiState {
    data object Loading : TransactionsUiState

    data object Empty : TransactionsUiState

    data class Success(
        val groups: ImmutableList<TransactionGroup> = persistentListOf(),
        /** Income minus expenses across every loaded transaction -- see MoneyText usage in the top bar for the "Net" label. */
        val netTotal: Money = Money.ZERO,
        val currencyCode: String = "INR",
        /**
         * The transaction a swipe just deleted, kept here -- not in row-local
         * composable state -- because the row that triggered it leaves
         * composition the moment the delete lands. Non-null drives the undo
         * snackbar; the ViewModel clears it once the snackbar resolves.
         */
        val pendingDelete: TransactionListItem? = null,
    ) : TransactionsUiState

    data class Error(val message: String) : TransactionsUiState
}

data class TransactionListItem(
    val transaction: Transaction,
    /** Null means uncategorised, not "not loaded yet" -- categories load eagerly. */
    val category: Category?,
)
