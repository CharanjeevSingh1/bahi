package dev.charanjeev.bahi.feature.budgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The dialogs both rule flows share. Kept in one place because the rules list
 * and the rule editor owe the user the same disclosure, and two copies of a
 * consent screen is how they end up saying different things.
 */
@Composable
internal fun RuleApplyDialogs(
    dialog: RuleApplyDialog?,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
    confirmTestTag: String,
    dialogTestTag: String,
    nothingToDoTestTag: String,
    doneTestTag: String,
    declineTestTag: String? = null,
) {
    when (dialog) {
        null -> Unit

        is RuleApplyDialog.Confirm -> AlertDialog(
            onDismissRequest = onDecline,
            modifier = Modifier.testTag(dialogTestTag),
            title = { Text(stringResource(R.string.rule_apply_title)) },
            text = {
                Column {
                    Text(
                        pluralStringResource(
                            R.plurals.rule_apply_body,
                            dialog.matchedCount,
                            dialog.matchedCount,
                        ),
                    )
                    // Shown only when it is actually true. A permanent "locked
                    // ones are skipped" footnote would be noise on every
                    // dialog and would stop being read by the time it matters.
                    if (dialog.lockedSkippedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.rule_apply_locked_note,
                                dialog.lockedSkippedCount,
                                dialog.lockedSkippedCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTestTag)) {
                    Text(stringResource(R.string.rule_apply_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDecline,
                    modifier = declineTestTag?.let { Modifier.testTag(it) } ?: Modifier,
                ) {
                    Text(stringResource(R.string.rule_apply_skip))
                }
            },
        )

        is RuleApplyDialog.NothingToDo -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(nothingToDoTestTag),
            title = { Text(stringResource(R.string.rule_apply_nothing_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.rule_apply_nothing_body))
                    if (dialog.lockedSkippedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.rule_apply_nothing_locked_note,
                                dialog.lockedSkippedCount,
                                dialog.lockedSkippedCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            // No Apply button: there is nothing to consent to, and offering
            // one would leave the user wondering what it did.
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_ok)) }
            },
        )

        is RuleApplyDialog.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag(doneTestTag),
            title = { Text(stringResource(R.string.rule_apply_done_title)) },
            text = {
                Column {
                    Text(
                        pluralStringResource(
                            R.plurals.rule_apply_done_body,
                            dialog.changedCount,
                            dialog.changedCount,
                        ),
                    )
                    // Says so when fewer rows changed than were promised,
                    // rather than quietly reporting a smaller number and
                    // leaving the user to wonder whether it worked.
                    if (dialog.fellShort) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.rule_apply_done_fell_short,
                                dialog.changedCount,
                                dialog.previewedCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_ok)) }
            },
        )
    }
}
