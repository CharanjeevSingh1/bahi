package dev.charanjeev.bahi.feature.csvimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.charanjeev.bahi.core.importer.AmountSign
import dev.charanjeev.bahi.core.importer.ColumnMapping

/** A role a column can be assigned in the manual fallback (§3's "floor"). */
private enum class ColumnRole { NONE, DATE, DESCRIPTION, AMOUNT, DEBIT, CREDIT }

private val DATE_FORMAT_CHOICES = listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MMM-yyyy")

/**
 * The floor §3 describes: assign each column's role by hand from the same
 * raw cells the confident path would have inferred over. Deliberately
 * simple relative to inference -- no preamble/header detection, [rows] is
 * always treated as starting at the first data row -- since this is the
 * fallback for when automatic inference already couldn't make sense of the
 * file, not a second inference engine.
 */
@Composable
internal fun RawGridEditor(
    rows: List<List<String>>,
    onApply: (ColumnMapping) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    var roles by remember(columnCount) { mutableStateOf(List(columnCount) { ColumnRole.NONE }) }
    var dateFormat by remember { mutableStateOf(DATE_FORMAT_CHOICES.first()) }
    var showInvalid by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(stringResource(R.string.import_raw_grid_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.import_raw_grid_body), modifier = Modifier.padding(bottom = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(columnCount) { column ->
                ColumnRoleRow(
                    column = column,
                    sample = rows.firstNotNullOfOrNull { it.getOrNull(column)?.takeIf(String::isNotBlank) }.orEmpty(),
                    role = roles[column],
                    onRoleSelected = { role -> roles = roles.toMutableList().also { it[column] = role } },
                )
            }
        }

        if (roles.contains(ColumnRole.DATE)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                DATE_FORMAT_CHOICES.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = dateFormat == format,
                        onClick = { dateFormat = format },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DATE_FORMAT_CHOICES.size),
                        modifier = Modifier.testTag("import:raw_grid:date_format:$format"),
                    ) { Text(format) }
                }
            }
        }

        if (showInvalid) {
            Text(
                stringResource(R.string.import_raw_grid_invalid),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            onClick = {
                val mapping = mappingFrom(roles, dateFormat)
                if (mapping != null) {
                    showInvalid = false
                    onApply(mapping)
                } else {
                    showInvalid = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag(ImportTestTags.RAW_GRID_APPLY),
        ) {
            Text(stringResource(R.string.import_raw_grid_apply))
        }
    }
}

@Composable
private fun ColumnRoleRow(
    column: Int,
    sample: String,
    role: ColumnRole,
    onRoleSelected: (ColumnRole) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = "${column + 1}: $sample",
                onValueChange = {},
                readOnly = true,
                label = { Text(roleLabel(role)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import:raw_grid:column:$column"),
            )
            // A readOnly TextField still consumes clicks for cursor placement,
            // so opening the menu needs its own transparent layer on top,
            // matching TransactionFormScreen's date/category field pattern.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ColumnRole.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(roleLabel(candidate)) },
                        onClick = { onRoleSelected(candidate); expanded = false },
                        modifier = Modifier.testTag("import:raw_grid:column:$column:role:${candidate.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun roleLabel(role: ColumnRole): String = stringResource(
    when (role) {
        ColumnRole.NONE -> R.string.import_raw_grid_role_none
        ColumnRole.DATE -> R.string.import_raw_grid_role_date
        ColumnRole.DESCRIPTION -> R.string.import_raw_grid_role_description
        ColumnRole.AMOUNT -> R.string.import_raw_grid_role_amount
        ColumnRole.DEBIT -> R.string.import_raw_grid_role_debit
        ColumnRole.CREDIT -> R.string.import_raw_grid_role_credit
    },
)

/** Null means the combination can't build a valid ColumnMapping (§1's own invariants) -- shown as one message, not per-field validation. */
private fun mappingFrom(roles: List<ColumnRole>, dateFormat: String): ColumnMapping? {
    val dateColumn = roles.indexOf(ColumnRole.DATE).takeIf { it >= 0 } ?: return null
    val descriptionColumn = roles.indexOf(ColumnRole.DESCRIPTION).takeIf { it >= 0 } ?: return null
    val amountColumn = roles.indexOf(ColumnRole.AMOUNT).takeIf { it >= 0 }
    val debitColumn = roles.indexOf(ColumnRole.DEBIT).takeIf { it >= 0 }
    val creditColumn = roles.indexOf(ColumnRole.CREDIT).takeIf { it >= 0 }
    val hasAmount = amountColumn != null
    val hasDebitCredit = debitColumn != null && creditColumn != null
    if (hasAmount == hasDebitCredit) return null // needs exactly one, matching ColumnMapping's own invariant

    return runCatching {
        ColumnMapping(
            headerRowIndex = null,
            firstDataRowIndex = 0,
            dateColumn = dateColumn,
            dateFormat = dateFormat,
            descriptionColumn = descriptionColumn,
            amountColumn = amountColumn,
            amountSign = amountColumn?.let { AmountSign.NEGATIVE_IS_DEBIT },
            signColumn = null,
            debitColumn = debitColumn,
            creditColumn = creditColumn,
        )
    }.getOrNull()
}
