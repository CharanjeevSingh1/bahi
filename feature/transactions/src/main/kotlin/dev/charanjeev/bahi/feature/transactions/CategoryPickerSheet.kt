package dev.charanjeev.bahi.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.charanjeev.bahi.core.model.Category
import kotlinx.collections.immutable.ImmutableList

/**
 * A search field over a list of categories, shared by the transaction form's
 * single-select field and the transaction list's multi-select filter -- both
 * are "find a category by name in a bottom sheet", differing only in what a
 * tap on a row means to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    categories: ImmutableList<Category>,
    isSelected: (Category) -> Boolean,
    onCategoryClick: (Category) -> Unit,
    onDismissRequest: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.testTag(CategoryPickerTestTags.SHEET),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text(stringResource(R.string.category_picker_search_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(CategoryPickerTestTags.SEARCH_FIELD),
        )

        val filtered = filterCategoriesByName(categories, searchQuery)

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.category_picker_no_results, searchQuery),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag(CategoryPickerTestTags.NO_RESULTS),
            )
        }

        LazyColumn(
            // imePadding here, not just on the sheet root, so the last row
            // scrolls clear of the keyboard instead of the sheet merely
            // resizing underneath it -- what actually keeps the search field
            // itself on-screen is skipPartiallyExpanded above.
            modifier = Modifier.imePadding(),
        ) {
            items(filtered, key = { it.id }) { category ->
                CategoryPickerRow(
                    category = category,
                    selected = isSelected(category),
                    onClick = { onCategoryClick(category) },
                )
            }
        }
    }
}

@Composable
private fun CategoryPickerRow(category: Category, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(CategoryPickerTestTags.categoryRowTag(category.id))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color = Color(category.colorArgb), shape = CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = category.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(CategoryPickerTestTags.categorySelectedTag(category.id)),
            )
        }
    }
}

/** Case-insensitive substring match on the category's name; blank matches everything. */
internal fun filterCategoriesByName(categories: ImmutableList<Category>, query: String): List<Category> =
    if (query.isBlank()) {
        categories
    } else {
        categories.filter { it.name.contains(query, ignoreCase = true) }
    }

object CategoryPickerTestTags {
    const val SHEET = "category_picker:sheet"
    const val SEARCH_FIELD = "category_picker:search"
    const val NO_RESULTS = "category_picker:no_results"
    fun categoryRowTag(categoryId: String) = "category_picker:row:$categoryId"
    fun categorySelectedTag(categoryId: String) = "category_picker:selected:$categoryId"
}
