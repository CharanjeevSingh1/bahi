package dev.charanjeev.bahi.feature.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.Transaction

@Composable
fun TransactionsRoute(
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(uiState = uiState)
}

/**
 * Stateless and previewable. The Route composable above owns the ViewModel so
 * this one can be driven directly from Compose UI tests and @Preview.
 */
@Composable
internal fun TransactionsScreen(
    uiState: TransactionsUiState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (uiState) {
            TransactionsUiState.Loading -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag(TransactionsTestTags.LOADING),
            )

            TransactionsUiState.Empty -> Text(
                text = "No transactions yet. Import a statement to get started.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .testTag(TransactionsTestTags.EMPTY),
            )

            is TransactionsUiState.Error -> Text(
                text = uiState.message,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .testTag(TransactionsTestTags.ERROR),
            )

            is TransactionsUiState.Success -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TransactionsTestTags.LIST),
            ) {
                items(uiState.transactions, key = Transaction::id) { transaction ->
                    ListItem(
                        headlineContent = { Text(transaction.description) },
                        supportingContent = { Text(transaction.date.toString()) },
                        trailingContent = { Text(transaction.amount.minorUnits.toString()) },
                    )
                }
            }
        }
    }
}

internal object TransactionsTestTags {
    const val LOADING = "transactions:loading"
    const val EMPTY = "transactions:empty"
    const val ERROR = "transactions:error"
    const val LIST = "transactions:list"
}
