package dev.charanjeev.bahi.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.charanjeev.bahi.core.model.Category
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime

/**
 * Two filter chips (category, date) plus a clear action that only appears
 * once a filter is active -- the chips themselves are the "visible
 * indication" the filter spec asks for, since a selected FilterChip already
 * reads as on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBar(
    filter: TransactionFilterState,
    availableCategories: ImmutableList<Category>,
    onCategoryFilterToggled: (String) -> Unit,
    onDateRangeOptionSelected: (DateRangeOption?) -> Unit,
    onCustomDateRangeSelected: (LocalDate, LocalDate) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCategorySheet by remember { mutableStateOf(false) }
    var categorySearchQuery by remember { mutableStateOf("") }
    var showDateSheet by remember { mutableStateOf(false) }
    var showCustomRangeDialog by remember { mutableStateOf(false) }

    Row(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FilterChip(
            selected = filter.categoryIds.isNotEmpty(),
            onClick = { showCategorySheet = true },
            label = {
                Text(
                    text = categoryChipLabel(filter, availableCategories),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = if (filter.categoryIds.isNotEmpty()) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            },
            // weight(fill = false), not a plain weight: the chip should only
            // grow to what its (now-bounded) label needs and otherwise shrink
            // out of the Date chip's way, never force itself to fill the row.
            modifier = Modifier.weight(1f, fill = false).testTag(FilterBarTestTags.CATEGORY_CHIP),
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = filter.dateRangeOption != null,
            onClick = { showDateSheet = true },
            label = { Text(dateChipLabel(filter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = if (filter.dateRangeOption != null) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            },
            // A measured minimum so the category chip's weight(fill = false)
            // can never squeeze this one down to a vertically-stacked sliver.
            modifier = Modifier.widthIn(min = 96.dp).testTag(FilterBarTestTags.DATE_CHIP),
        )
        if (filter.isActive) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onClearFilters,
                modifier = Modifier.testTag(FilterBarTestTags.CLEAR_BUTTON),
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.transactions_filter_clear_content_description),
                )
            }
        }
    }

    if (showCategorySheet) {
        CategoryPickerSheet(
            categories = availableCategories,
            isSelected = { it.id in filter.categoryIds },
            onCategoryClick = { category -> onCategoryFilterToggled(category.id) },
            onDismissRequest = { showCategorySheet = false; categorySearchQuery = "" },
            searchQuery = categorySearchQuery,
            onSearchQueryChange = { categorySearchQuery = it },
        )
    }

    if (showDateSheet) {
        DateRangeOptionSheet(
            selected = filter.dateRangeOption,
            onOptionSelected = { option ->
                showDateSheet = false
                if (option == DateRangeOption.CUSTOM) {
                    showCustomRangeDialog = true
                } else {
                    onDateRangeOptionSelected(option)
                }
            },
            onDismissRequest = { showDateSheet = false },
        )
    }

    if (showCustomRangeDialog) {
        CustomDateRangeDialog(
            initialFrom = filter.customFrom,
            initialTo = filter.customTo,
            onConfirm = { from, to ->
                onCustomDateRangeSelected(from, to)
                showCustomRangeDialog = false
            },
            onDismissRequest = { showCustomRangeDialog = false },
        )
    }
}

@Composable
private fun categoryChipLabel(filter: TransactionFilterState, availableCategories: ImmutableList<Category>): String =
    when (val content = filter.categoryChipContent(availableCategories)) {
        CategoryChipContent.Placeholder -> stringResource(R.string.transactions_filter_category_label)
        is CategoryChipContent.Names -> content.names.joinToString(", ")
        is CategoryChipContent.Count -> stringResource(R.string.transactions_filter_category_count, content.count)
    }

@Composable
private fun dateChipLabel(filter: TransactionFilterState): String = when (filter.dateRangeOption) {
    null -> stringResource(R.string.transactions_filter_date_label)
    DateRangeOption.THIS_MONTH -> stringResource(R.string.transactions_filter_date_this_month)
    DateRangeOption.LAST_MONTH -> stringResource(R.string.transactions_filter_date_last_month)
    DateRangeOption.CUSTOM -> {
        val from = filter.customFrom
        val to = filter.customTo
        if (from != null && to != null) {
            stringResource(R.string.transactions_filter_date_custom_range, formatShortDate(from), formatShortDate(to))
        } else {
            stringResource(R.string.transactions_filter_date_custom)
        }
    }
}

private fun formatShortDate(date: LocalDate): String =
    date.toJavaLocalDate().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeOptionSheet(
    selected: DateRangeOption?,
    onOptionSelected: (DateRangeOption) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = Modifier.testTag(FilterBarTestTags.DATE_SHEET)) {
        LazyColumn {
            items(DateRangeOption.entries) { option ->
                DateRangeOptionRow(
                    option = option,
                    selected = option == selected,
                    onClick = { onOptionSelected(option) },
                )
            }
        }
    }
}

@Composable
private fun DateRangeOptionRow(option: DateRangeOption, selected: Boolean, onClick: () -> Unit) {
    val label = when (option) {
        DateRangeOption.THIS_MONTH -> stringResource(R.string.transactions_filter_date_this_month)
        DateRangeOption.LAST_MONTH -> stringResource(R.string.transactions_filter_date_last_month)
        DateRangeOption.CUSTOM -> stringResource(R.string.transactions_filter_date_custom)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(FilterBarTestTags.dateOptionTag(option))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
        initialSelectedEndDateMillis = initialTo?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val startMillis = state.selectedStartDateMillis
                    val endMillis = state.selectedEndDateMillis
                    if (startMillis != null && endMillis != null) {
                        val from = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(TimeZone.UTC).date
                        val to = Instant.fromEpochMilliseconds(endMillis).toLocalDateTime(TimeZone.UTC).date
                        onConfirm(from, to)
                    }
                },
                modifier = Modifier.testTag(FilterBarTestTags.CUSTOM_RANGE_APPLY),
            ) { Text(stringResource(R.string.transactions_filter_date_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.transactions_filter_date_cancel)) }
        },
    ) {
        DateRangePicker(state = state, modifier = Modifier.testTag(FilterBarTestTags.CUSTOM_RANGE_PICKER))
    }
}

object FilterBarTestTags {
    const val CATEGORY_CHIP = "transactions_filter:category_chip"
    const val DATE_CHIP = "transactions_filter:date_chip"
    const val CLEAR_BUTTON = "transactions_filter:clear"
    const val DATE_SHEET = "transactions_filter:date_sheet"
    const val CUSTOM_RANGE_PICKER = "transactions_filter:custom_range_picker"
    const val CUSTOM_RANGE_APPLY = "transactions_filter:custom_range_apply"
    fun dateOptionTag(option: DateRangeOption) = "transactions_filter:date_option:${option.name}"
}
