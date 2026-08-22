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

    data object Empty : TransactionsUiState

    data class Success(
        val groups: ImmutableList<TransactionGroup> = persistentListOf(),
        /** Income minus expenses within [periodMonth] only -- see netTotalForMonth. An all-time net is meaningless once a paycheck is in the list. */
        val netTotal: Money = Money.ZERO,
        /** Which calendar month [netTotal] covers; only its year/month are used. Until Slice 4 adds real period filtering, this is always "now". */
        val periodMonth: LocalDate = LocalDate(1970, 1, 1),
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
