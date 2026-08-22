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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.Category
import dev.charanjeev.bahi.core.ui.MoneyText
import dev.charanjeev.bahi.core.ui.titleCaseTransactionDescription
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
    // Resolved here, in composition, and captured by the effect below --
    // stringResource is @Composable and can't be called from inside it.
    val undoLabel = stringResource(R.string.transactions_undo)
    val deletedMessage = pendingDelete?.let {
        stringResource(R.string.transactions_deleted_snackbar, titleCaseTransactionDescription(it.transaction.description))
    }
    LaunchedEffect(pendingDelete) {
        if (deletedMessage == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
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
                Text(stringResource(R.string.transactions_title), style = MaterialTheme.typography.titleLarge)
                if (uiState is TransactionsUiState.Success) {
                    val monthName = uiState.periodMonth.toJavaLocalDate()
                        .format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))
                    Row {
                        Text(
                            text = stringResource(R.string.transactions_net_label, monthName) + " ",
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
            text = stringResource(R.string.transactions_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.transactions_empty_body),
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
            text = stringResource(R.string.transactions_error_title),
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
            Text(stringResource(R.string.transactions_retry))
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
            // primary (blue) read as a link; this is structure, not an
            // action, so it gets a muted colour and leans on weight instead.
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DateHeader.displayText(locale: Locale = Locale.getDefault()): String = when (this) {
    DateHeader.Today -> stringResource(R.string.transactions_date_header_today)
    DateHeader.Yesterday -> stringResource(R.string.transactions_date_header_yesterday)
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
                    text = stringResource(R.string.transactions_swipe_delete_label),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        TransactionRow(item)
    }
}

/**
 * Amount is what people scan for, so it carries the most weight; description
 * is a step lighter; the category chip is the lightest element in the row.
 * All three come from the M3 type scale rather than one-off font sizes, so
 * the hierarchy stays consistent with the rest of the app.
 *
 * A plain Row rather than ListItem: ListItem's built-in two-line spec height
 * (~72dp) is what held the list to about nine rows on a large phone. This
 * wraps to content instead, with heightIn(min = 48.dp) as the accessibility
 * floor rather than the ~72dp default.
 */
@Composable
private fun TransactionRow(item: TransactionListItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque: ListItem used to give the row a solid background for
            // free. Without it, the swipe backgroundContent (red "Delete")
            // shows straight through the row even at rest.
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleCaseTransactionDescription(item.transaction.description),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            CategoryChip(item.category)
        }
        Spacer(modifier = Modifier.width(12.dp))
        MoneyText(
            money = item.transaction.amount,
            currencyCode = item.transaction.currencyCode,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun CategoryChip(category: Category?, modifier: Modifier = Modifier) {
    val label = category?.name ?: stringResource(R.string.transactions_uncategorised)
    val tint = category?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = tint.copy(alpha = 0.18f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
