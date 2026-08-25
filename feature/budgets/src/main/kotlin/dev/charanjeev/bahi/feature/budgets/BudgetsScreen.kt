package dev.charanjeev.bahi.feature.budgets

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.BudgetStatus
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.designsystem.theme.LocalSemanticColors
import dev.charanjeev.bahi.core.ui.MoneyText
import dev.charanjeev.bahi.core.ui.formatMoney

@Composable
fun BudgetsRoute(
    viewModel: BudgetsViewModel = hiltViewModel(),
    onAddBudget: (YearMonth) -> Unit = {},
    onEditBudget: (String, YearMonth) -> Unit = { _, _ -> },
    onOpenRules: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BudgetsScreen(
        uiState = uiState,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onAddBudget = { onAddBudget(viewModel.currentMonth()) },
        onEditBudget = { id -> onEditBudget(id, viewModel.currentMonth()) },
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteConfirmed = viewModel::onDeleteConfirmed,
        onDeleteCancelled = viewModel::onDeleteCancelled,
        onOpenRules = onOpenRules,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetsScreen(
    uiState: BudgetsUiState,
    modifier: Modifier = Modifier,
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onAddBudget: () -> Unit = {},
    onEditBudget: (String) -> Unit = {},
    onDeleteRequested: (String, String) -> Unit = { _, _ -> },
    onDeleteConfirmed: () -> Unit = {},
    onDeleteCancelled: () -> Unit = {},
    onOpenRules: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.budgets_title)) },
                actions = {
                    TextButton(onClick = onOpenRules, modifier = Modifier.testTag(BudgetsTestTags.RULES_ACTION)) {
                        Text(stringResource(R.string.budgets_rules_action))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddBudget, modifier = Modifier.testTag(BudgetsTestTags.ADD_FAB)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.budgets_add_content_description),
                )
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // Outside the `when`: the month switcher stays put while a month
            // loads, so moving between months doesn't make the control the
            // user is pressing disappear underneath them.
            MonthSwitcher(
                month = uiState.month,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
            )

            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState) {
                    is BudgetsUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).testTag(BudgetsTestTags.LOADING),
                    )

                    is BudgetsUiState.Empty -> EmptyBudgets(
                        uiState = uiState,
                        onOpenRules = onOpenRules,
                        modifier = Modifier.testTag(BudgetsTestTags.EMPTY),
                    )

                    is BudgetsUiState.Success -> BudgetList(
                        uiState = uiState,
                        onEditBudget = onEditBudget,
                        onDeleteRequested = onDeleteRequested,
                        onOpenRules = onOpenRules,
                    )
                }
            }
        }
    }

    val pendingDelete = (uiState as? BudgetsUiState.Success)?.pendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onDeleteCancelled,
            modifier = Modifier.testTag(BudgetsTestTags.DELETE_DIALOG),
            title = { Text(stringResource(R.string.budgets_delete_title, pendingDelete.categoryName)) },
            text = { Text(stringResource(R.string.budgets_delete_body)) },
            confirmButton = {
                TextButton(onClick = onDeleteConfirmed) { Text(stringResource(R.string.budgets_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onDeleteCancelled) { Text(stringResource(R.string.budgets_cancel)) }
            },
        )
    }
}

@Composable
private fun MonthSwitcher(
    month: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPreviousMonth, modifier = Modifier.testTag(BudgetsTestTags.PREVIOUS_MONTH)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.budgets_previous_month),
            )
        }
        Text(
            text = month.displayName(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(BudgetsTestTags.MONTH_LABEL),
        )
        IconButton(onClick = onNextMonth, modifier = Modifier.testTag(BudgetsTestTags.NEXT_MONTH)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.budgets_next_month),
            )
        }
    }
}

@Composable
private fun BudgetList(
    uiState: BudgetsUiState.Success,
    onEditBudget: (String) -> Unit,
    onDeleteRequested: (String, String) -> Unit,
    onOpenRules: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(BudgetsTestTags.LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
    ) {
        items(uiState.budgets, key = { it.progress.budget.id }) { row ->
            BudgetRowItem(
                row = row,
                currencyCode = uiState.currencyCode,
                onClick = { onEditBudget(row.progress.budget.id) },
                onDelete = {
                    onDeleteRequested(row.progress.budget.id, row.category?.name.orEmpty())
                },
            )
        }

        item {
            // The two states this screen must not conflate. A month with no
            // transactions at all and a month whose spending is entirely
            // uncategorised have *identical* budget rows above -- every one
            // reading zero -- so if the screen stopped here they would be
            // indistinguishable. What separates them is below.
            if (uiState.hasUncategorisedSpend) {
                UncategorisedCard(
                    amount = uiState.uncategorisedSpend,
                    currencyCode = uiState.currencyCode,
                    onOpenRules = onOpenRules,
                )
            } else if (uiState.nothingCountedYet) {
                Text(
                    text = stringResource(R.string.budgets_nothing_counted),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag(BudgetsTestTags.NOTHING_COUNTED_NOTE),
                )
            }
        }
    }
}

@Composable
private fun BudgetRowItem(
    row: BudgetRow,
    currencyCode: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = row.progress
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(BudgetsTestTags.row(progress.budget.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.category?.name ?: stringResource(R.string.budgets_unknown_category),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick) { Text(stringResource(R.string.budgets_edit)) }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.budgets_delete_content_description),
                )
            }
        }

        Text(
            text = stringResource(
                R.string.budgets_spent_of_limit,
                formatMoney(progress.spent, currencyCode),
                formatMoney(progress.budget.limit, currencyCode),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(6.dp))

        // Three colours, because there are three things worth saying. Under
        // and over were never the hard part: the state that needed its own
        // signal is "at or nearly at the limit", which rendered in the normal
        // colour is a warning the user only receives after it stops being
        // actionable.
        //
        // fractionOfLimit is deliberately unclamped in the model, so nothing
        // is lost there -- but LinearProgressIndicator clamps anyway, so an
        // over-budget bar can't communicate overflow by length. Colour and
        // the line below carry it instead, which reads better than a bar
        // pinned at full either way.
        //
        // At progress = 0f Material3 1.3.2 draws the track and its stop
        // indicator and no fill at all, which is the correct "nothing spent"
        // rendering and is only ambiguous if a zero-limit row can exist
        // alongside it -- which the editor no longer allows.
        LinearProgressIndicator(
            progress = { progress.fractionOfLimit.coerceIn(0f, 1f) },
            color = when (progress.status) {
                BudgetStatus.OVER -> MaterialTheme.colorScheme.error
                BudgetStatus.NEAR_LIMIT -> LocalSemanticColors.current.warning
                BudgetStatus.UNDER -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .testTag(BudgetsTestTags.bar(progress.budget.id)),
        )

        Spacer(Modifier.height(6.dp))

        when (progress.status) {
            BudgetStatus.OVER -> Text(
                text = stringResource(
                    R.string.budgets_over_by,
                    formatMoney(progress.remaining.absolute, currencyCode),
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(BudgetsTestTags.overBudget(progress.budget.id)),
            )

            BudgetStatus.NEAR_LIMIT -> Text(
                // "₹0.00 left" is accurate and reads like nothing happened.
                // Naming the state is what makes it land.
                text = if (progress.isExactlyAtLimit) {
                    stringResource(R.string.budgets_limit_reached)
                } else {
                    stringResource(
                        R.string.budgets_remaining,
                        formatMoney(progress.remaining, currencyCode),
                    )
                },
                color = LocalSemanticColors.current.warning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(BudgetsTestTags.nearLimit(progress.budget.id)),
            )

            BudgetStatus.UNDER -> Text(
                text = stringResource(
                    R.string.budgets_remaining,
                    formatMoney(progress.remaining, currencyCode),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The line that tells an empty month apart from an all-uncategorised one
 * (§2.2). A card rather than a footnote: this is real money the budgets
 * above structurally cannot see, and burying it would leave the user reading
 * a screen of zeroes with no hint that anything was spent.
 */
@Composable
private fun UncategorisedCard(
    amount: Money,
    currencyCode: String,
    onOpenRules: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag(BudgetsTestTags.UNCATEGORISED_CARD),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.budgets_uncategorised_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            MoneyText(
                money = amount,
                currencyCode = currencyCode,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.budgets_uncategorised_body),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onOpenRules) {
                Text(stringResource(R.string.budgets_uncategorised_action))
            }
        }
    }
}

@Composable
private fun EmptyBudgets(
    uiState: BudgetsUiState.Empty,
    onOpenRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.budgets_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.budgets_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // Even with no budgets at all, uncategorised spending is worth
        // surfacing -- otherwise the emptiest screen is the one that tells
        // the user least about where their money actually went.
        if (uiState.hasUncategorisedSpend) {
            Spacer(Modifier.height(24.dp))
            UncategorisedCard(
                amount = uiState.uncategorisedSpend,
                currencyCode = "INR",
                onOpenRules = onOpenRules,
            )
        }
    }
}
