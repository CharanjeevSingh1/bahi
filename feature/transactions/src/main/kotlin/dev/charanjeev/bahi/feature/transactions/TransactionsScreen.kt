package dev.charanjeev.bahi.feature.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.ui.MoneyText
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.datetime.toJavaLocalDate

@Composable
fun TransactionsRoute(
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(
        uiState = uiState,
        onDeleteTransaction = viewModel::onDeleteTransaction,
        onUndoDelete = viewModel::onUndoDelete,
        onDeleteSnackbarDismissed = viewModel::onDeleteSnackbarDismissed,
        onRetry = viewModel::onRetry,
    )
}

/**
 * Stateless and previewable. The Route composable above owns the ViewModel so
 * this one can be driven directly from Compose UI tests and @Preview.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TransactionsScreen(
    uiState: TransactionsUiState,
    modifier: Modifier = Modifier,
    onDeleteTransaction: (TransactionListItem) -> Unit = {},
    onUndoDelete: () -> Unit = {},
    onDeleteSnackbarDismissed: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Keyed on the pending delete itself (not just "is it non-null") so a
    // second swipe while the first snackbar is still up starts a fresh one.
    val pendingDelete = (uiState as? TransactionsUiState.Success)?.pendingDelete
    LaunchedEffect(pendingDelete) {
        if (pendingDelete == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Deleted \"${pendingDelete.transaction.description}\"",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoDelete() else onDeleteSnackbarDismissed()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TransactionsTopBar(uiState) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when (uiState) {
                TransactionsUiState.Loading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TransactionsTestTags.LOADING),
                )

                TransactionsUiState.Empty -> EmptyState(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TransactionsTestTags.EMPTY),
                )

                is TransactionsUiState.Error -> ErrorState(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(TransactionsTestTags.ERROR),
                )

                is TransactionsUiState.Success -> TransactionsList(
                    uiState = uiState,
                    onDeleteTransaction = onDeleteTransaction,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TransactionsTestTags.LIST),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionsTopBar(uiState: TransactionsUiState) {
    TopAppBar(
        title = {
            Column {
                Text("Transactions", style = MaterialTheme.typography.titleLarge)
                if (uiState is TransactionsUiState.Success) {
                    Row {
                        Text(
                            text = "Net ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MoneyText(
                            money = uiState.netTotal,
                            currencyCode = uiState.currencyCode,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No transactions yet",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add a transaction by hand, or import a bank statement, " +
                "and it'll show up here grouped by day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Couldn't load transactions",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag(TransactionsTestTags.ERROR_RETRY),
        ) {
            Text("Retry")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionsList(
    uiState: TransactionsUiState.Success,
    onDeleteTransaction: (TransactionListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        uiState.groups.forEach { group ->
            stickyHeader(key = "header-${group.header}") {
                DateHeaderRow(group.header)
            }
            items(group.items, key = { it.transaction.id }) { item ->
                SwipeableTransactionRow(item = item, onDelete = { onDeleteTransaction(item) })
            }
        }
    }
}

@Composable
private fun DateHeaderRow(header: DateHeader, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Text(
            text = header.displayText(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun DateHeader.displayText(locale: Locale = Locale.getDefault()): String = when (this) {
    DateHeader.Today -> "Today"
    DateHeader.Yesterday -> "Yesterday"
    is DateHeader.Dated -> date.toJavaLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTransactionRow(
    item: TransactionListItem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
            true
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.testTag(TransactionsTestTags.rowTag(item.transaction.id)),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        TransactionRow(item)
    }
}

@Composable
private fun TransactionRow(item: TransactionListItem) {
    ListItem(
        headlineContent = { Text(item.transaction.description) },
        supportingContent = { CategoryChip(item.category) },
        trailingContent = {
            MoneyText(
                money = item.transaction.amount,
                currencyCode = item.transaction.currencyCode,
            )
        },
    )
}

@Composable
private fun CategoryChip(category: Category?, modifier: Modifier = Modifier) {
    val label = category?.name ?: "Uncategorised"
    val tint = category?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = tint.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

internal object TransactionsTestTags {
    const val LOADING = "transactions:loading"
    const val EMPTY = "transactions:empty"
    const val ERROR = "transactions:error"
    const val ERROR_RETRY = "transactions:error:retry"
    const val LIST = "transactions:list"
    fun rowTag(transactionId: String) = "transactions:row:$transactionId"
}
