package dev.charanjeev.bahi.feature.csvimport

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

private val DATE_FORMAT_CHOICES = listOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MMM-yyyy")

/**
 * The floor §3 describes: assign each role by hand from the same raw cells
 * the confident path would have inferred over. Deliberately simple relative
 * to inference -- no preamble/header detection, [rows] is always treated as
 * starting at the first data row -- since this is the fallback for when
 * automatic inference already couldn't make sense of the file, not a second
 * inference engine.
 *
 * Role-centric, not column-centric: one field per role (Date, Description,
 * Amount/Money out/Money in), each showing which column currently fills it.
 * The earlier column-centric shape (one dropdown per column, all defaulting
 * to "Not used") had two problems -- every field showed the same unhelpful
 * label until the user acted, and nothing stopped two columns being assigned
 * the same role. Fixing the role as the field's label and the column as its
 * value solves both: the label always names what's being asked for, and
 * picking a column for one role doesn't touch the others.
 */
@Composable
internal fun RawGridEditor(
    rows: List<List<String>>,
    onApply: (ColumnMapping) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    val samples = remember(rows, columnCount) {
        List(columnCount) { column ->
            rows.firstNotNullOfOrNull { it.getOrNull(column)?.takeIf(String::isNotBlank) }.orEmpty()
        }
    }

    var dateColumn by remember { mutableStateOf<Int?>(null) }
    var descriptionColumn by remember { mutableStateOf<Int?>(null) }
    var useDebitCredit by remember { mutableStateOf(false) }
    var amountColumn by remember { mutableStateOf<Int?>(null) }
    var debitColumn by remember { mutableStateOf<Int?>(null) }
    var creditColumn by remember { mutableStateOf<Int?>(null) }
    var dateFormat by remember { mutableStateOf(DATE_FORMAT_CHOICES.first()) }
    var showInvalid by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(stringResource(R.string.import_raw_grid_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.import_raw_grid_body), modifier = Modifier.padding(bottom = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            item {
                RoleField(
                    label = stringResource(R.string.import_raw_grid_role_date),
                    selectedColumn = dateColumn,
                    samples = samples,
                    onColumnSelected = { dateColumn = it },
                    testTag = "import:raw_grid:role:date",
                )
            }
            item {
                RoleField(
                    label = stringResource(R.string.import_raw_grid_role_description),
                    selectedColumn = descriptionColumn,
                    samples = samples,
                    onColumnSelected = { descriptionColumn = it },
                    testTag = "import:raw_grid:role:description",
                )
            }
            item {
                Text(
                    text = stringResource(R.string.import_raw_grid_amount_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SegmentedButton(
                        selected = !useDebitCredit,
                        onClick = { useDebitCredit = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.testTag("import:raw_grid:amount_mode:single"),
                    ) { Text(stringResource(R.string.import_raw_grid_amount_mode_single)) }
                    SegmentedButton(
                        selected = useDebitCredit,
                        onClick = { useDebitCredit = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.testTag("import:raw_grid:amount_mode:debit_credit"),
                    ) { Text(stringResource(R.string.import_raw_grid_amount_mode_debit_credit)) }
                }
            }
            if (useDebitCredit) {
                item {
                    RoleField(
                        label = stringResource(R.string.import_raw_grid_role_debit),
                        selectedColumn = debitColumn,
                        samples = samples,
                        onColumnSelected = { debitColumn = it },
                        testTag = "import:raw_grid:role:debit",
                    )
                }
                item {
                    RoleField(
                        label = stringResource(R.string.import_raw_grid_role_credit),
                        selectedColumn = creditColumn,
                        samples = samples,
                        onColumnSelected = { creditColumn = it },
                        testTag = "import:raw_grid:role:credit",
                    )
                }
            } else {
                item {
                    RoleField(
                        label = stringResource(R.string.import_raw_grid_role_amount),
                        selectedColumn = amountColumn,
                        samples = samples,
                        onColumnSelected = { amountColumn = it },
                        testTag = "import:raw_grid:role:amount",
                    )
                }
            }
        }

        if (dateColumn != null) {
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
                val mapping = mappingFrom(
                    dateColumn = dateColumn,
                    descriptionColumn = descriptionColumn,
                    useDebitCredit = useDebitCredit,
                    amountColumn = amountColumn,
                    debitColumn = debitColumn,
                    creditColumn = creditColumn,
                    dateFormat = dateFormat,
                )
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

/** One role's field: [label] never changes: it names the role being asked for, not whatever's currently assigned to it. */
@Composable
private fun RoleField(
    label: String,
    selectedColumn: Int?,
    samples: List<String>,
    onColumnSelected: (Int) -> Unit,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val value = selectedColumn?.let { column ->
        stringResource(R.string.import_raw_grid_column_value, column + 1, samples[column])
    } ?: stringResource(R.string.import_raw_grid_unassigned)

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag),
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
                samples.forEachIndexed { column, sample ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_raw_grid_column_value, column + 1, sample)) },
                        onClick = { onColumnSelected(column); expanded = false },
                        modifier = Modifier.testTag("$testTag:column:$column"),
                    )
                }
            }
        }
    }
}

/** Null means the combination can't build a valid ColumnMapping (§1's own invariants) -- shown as one message, not per-field validation. */
private fun mappingFrom(
    dateColumn: Int?,
    descriptionColumn: Int?,
    useDebitCredit: Boolean,
    amountColumn: Int?,
    debitColumn: Int?,
    creditColumn: Int?,
    dateFormat: String,
): ColumnMapping? {
    val date = dateColumn ?: return null
    val description = descriptionColumn ?: return null

    return runCatching {
        if (useDebitCredit) {
            ColumnMapping(
                headerRowIndex = null,
                firstDataRowIndex = 0,
                dateColumn = date,
                dateFormat = dateFormat,
                descriptionColumn = description,
                amountColumn = null,
                amountSign = null,
                signColumn = null,
                debitColumn = debitColumn ?: return null,
                creditColumn = creditColumn ?: return null,
            )
        } else {
            ColumnMapping(
                headerRowIndex = null,
                firstDataRowIndex = 0,
                dateColumn = date,
                dateFormat = dateFormat,
                descriptionColumn = description,
                amountColumn = amountColumn ?: return null,
                amountSign = AmountSign.NEGATIVE_IS_DEBIT,
                signColumn = null,
                debitColumn = null,
                creditColumn = null,
            )
        }
    }.getOrNull()
}
