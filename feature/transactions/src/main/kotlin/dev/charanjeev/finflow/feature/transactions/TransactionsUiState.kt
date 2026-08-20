package dev.charanjeev.finflow.feature.transactions

import dev.charanjeev.finflow.core.model.Transaction
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
        val transactions: ImmutableList<Transaction> = persistentListOf(),
        val isRefreshing: Boolean = false,
    ) : TransactionsUiState

    data class Error(val message: String) : TransactionsUiState
}
