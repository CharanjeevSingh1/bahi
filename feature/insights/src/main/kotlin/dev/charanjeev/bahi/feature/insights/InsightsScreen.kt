package dev.charanjeev.bahi.feature.insights

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.MonthlyTotal
import dev.charanjeev.bahi.core.model.SystemCategoryIds
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.ui.displayName
import dev.charanjeev.bahi.core.ui.formatMoney
import dev.charanjeev.bahi.core.ui.shortDisplayName
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList

@Composable
fun InsightsRoute(
    viewModel: InsightsViewModel = hiltViewModel(),
    onSetUpRules: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    InsightsScreen(
        uiState = uiState,
        onPreviousMonth = viewModel::onPreviousMonth,
        onNextMonth = viewModel::onNextMonth,
        onSetUpRules = onSetUpRules,
        onOpenSettings = onOpenSettings,
    )
}

/** Stateless and previewable; the Route above owns the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(
    uiState: InsightsUiState,
    modifier: Modifier = Modifier,
    onPreviousMonth: () -> Unit = {},
    onNextMonth: () -> Unit = {},
    onSetUpRules: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.insights_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(InsightsTestTags.SETTINGS_ACTION)) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.insights_settings_content_description),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // Outside the `when`, matching BudgetsScreen: the month switcher
            // stays in place while a month loads or turns out to have no
            // spend, instead of the control the user just pressed vanishing.
            MonthSwitcher(month = uiState.month, onPreviousMonth = onPreviousMonth, onNextMonth = onNextMonth)

            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState) {
                    is InsightsUiState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).testTag(InsightsTestTags.LOADING),
                    )

                    is InsightsUiState.NoHistory -> NoHistoryContent(
                        modifier = Modifier.testTag(InsightsTestTags.NO_HISTORY),
                    )

                    is InsightsUiState.Success -> InsightsContent(
                        uiState = uiState,
                        onSetUpRules = onSetUpRules,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthSwitcher(month: YearMonth, onPreviousMonth: () -> Unit, onNextMonth: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPreviousMonth, modifier = Modifier.testTag(InsightsTestTags.PREVIOUS_MONTH)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.insights_previous_month),
            )
        }
        Text(
            text = month.displayName(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(InsightsTestTags.MONTH_LABEL),
        )
        IconButton(onClick = onNextMonth, modifier = Modifier.testTag(InsightsTestTags.NEXT_MONTH)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.insights_next_month),
            )
        }
    }
}

@Composable
private fun NoHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.insights_no_history_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.insights_no_history_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InsightsContent(uiState: InsightsUiState.Success, onSetUpRules: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(InsightsTestTags.CONTENT),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item {
            SectionCard(
                title = stringResource(R.string.insights_category_breakdown_title),
                caption = stringResource(R.string.insights_category_breakdown_caption),
            ) {
                if (!uiState.hasAnySpend) {
                    Text(
                        text = stringResource(R.string.insights_no_spend, uiState.month.displayName()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(InsightsTestTags.NO_SPEND_NOTE),
                    )
                } else {
                    uiState.categorySlices.forEach { slice ->
                        CategorySliceRow(slice = slice, totalSpend = uiState.totalSpend, currencyCode = uiState.currencyCode)
                    }
                    if (uiState.categorySlices.any { it.category?.id == SystemCategoryIds.UNCATEGORISED }) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.insights_uncategorised_prompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onSetUpRules,
                            modifier = Modifier.testTag(InsightsTestTags.UNCATEGORISED_PROMPT),
                        ) {
                            Text(stringResource(R.string.insights_uncategorised_action))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.insights_trend_title),
                caption = stringResource(R.string.insights_trend_caption),
            ) {
                if (uiState.hasComparison) {
                    SpendTrendChart(
                        months = uiState.trend,
                        currentMonth = uiState.month,
                        modifier = Modifier.testTag(InsightsTestTags.TREND_CHART),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.insights_trend_no_comparison),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(InsightsTestTags.TREND_NO_COMPARISON),
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.insights_over_budget_title)) {
                when {
                    !uiState.hasAnyBudgets -> Text(
                        text = stringResource(R.string.insights_over_budget_no_budgets),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(InsightsTestTags.OVER_BUDGET_NO_BUDGETS),
                    )

                    uiState.overBudget.isEmpty() -> Text(
                        text = stringResource(R.string.insights_over_budget_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(InsightsTestTags.OVER_BUDGET_NONE),
                    )

                    else -> Column(modifier = Modifier.testTag(InsightsTestTags.OVER_BUDGET_LIST)) {
                        uiState.overBudget.forEach { row ->
                            Text(
                                text = stringResource(
                                    R.string.insights_over_by,
                                    row.category?.name ?: stringResource(R.string.insights_unknown_category),
                                    formatMoney(row.progress.remaining.absolute, uiState.currencyCode),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    caption: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (caption != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * One horizontal bar. Its length is a fraction of [totalSpend], the exact
 * figure the caption above claims this chart computed -- not a fraction of
 * the largest slice, which would make two charts with different totals look
 * the same width.
 */
@Composable
private fun CategorySliceRow(slice: CategorySlice, totalSpend: Money, currencyCode: String) {
    val fraction = if (totalSpend > Money.ZERO) {
        slice.spent.minorUnits.toFloat() / totalSpend.minorUnits.toFloat()
    } else {
        0f
    }
    val color = slice.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.outline
    val name = slice.category?.name ?: stringResource(R.string.insights_unknown_category)
    val roundedPercent = (fraction * 100).roundToInt()
    // A slice with real money against it is never truly 0% of the total --
    // that only happens when it rounds down from a genuine sliver. Say
    // "<1%" rather than claim a precision the chart doesn't have.
    val percentText = if (roundedPercent == 0 && fraction > 0f) {
        stringResource(R.string.insights_less_than_one_percent)
    } else {
        "$roundedPercent%"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag(InsightsTestTags.categorySlice(slice.category?.id ?: "unknown")),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${formatMoney(slice.spent, currencyCode)} · $percentText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        SliceBar(fraction = fraction, color = color)
    }
}

/**
 * Track and fill drawn from the same `size.width` in one Canvas, rather than
 * two nested `fillMaxWidth()` Boxes -- those resolve their pixel widths
 * through separate constraint passes, and at a fill fraction close to 1 the
 * two rounded-rect clips could disagree by a pixel, leaving the track short
 * of the edge every other row reaches.
 */
@Composable
private fun SliceBar(fraction: Float, color: Color) {
    val clampedFraction = fraction.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(4.dp.toPx())
        drawRoundRect(color = color.copy(alpha = 0.2f), size = size, cornerRadius = radius)
        if (clampedFraction > 0f) {
            drawRoundRect(color = color, size = size.copy(width = size.width * clampedFraction), cornerRadius = radius)
        }
    }
}

/**
 * A plain Canvas bar chart -- no charting dependency, because a handful of
 * vertical bars is well within what Canvas draws directly, and the
 * dependency list stays as small as it was (CLAUDE.md).
 *
 * The bar for [currentMonth] is drawn in the solid primary colour; every
 * other month is the lighter container tone, so the one figure being set
 * against its history is visually the answer to "compared to what".
 */
@Composable
private fun SpendTrendChart(months: ImmutableList<MonthlyTotal>, currentMonth: YearMonth, modifier: Modifier = Modifier) {
    val maxSpent = months.maxOf { it.spent.minorUnits }.coerceAtLeast(1L).toFloat()
    val currentColor = MaterialTheme.colorScheme.primary
    val pastColor = MaterialTheme.colorScheme.primaryContainer

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val gap = 8.dp.toPx()
            val barWidth = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { index, point ->
                val fraction = point.spent.minorUnits / maxSpent
                val barHeight = size.height * fraction
                val left = index * (barWidth + gap)
                drawRect(
                    color = if (point.month == currentMonth) currentColor else pastColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            months.forEach { point ->
                Text(
                    text = point.month.shortDisplayName(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
