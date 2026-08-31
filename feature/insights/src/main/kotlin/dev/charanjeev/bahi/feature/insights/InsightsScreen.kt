package dev.charanjeev.bahi.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The Route/Screen pair with nothing in the Route yet: there is no state to
 * own until M5 builds the real thing. The split stays because it is the shape
 * every other screen has, and it is where the ViewModel goes when there is
 * one -- [InsightsScreen] is already stateless and previewable.
 */
@Composable
fun InsightsRoute(onSetUpRules: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    InsightsScreen(onSetUpRules = onSetUpRules, onOpenSettings = onOpenSettings)
}

/**
 * An empty state, not a "coming soon" banner. It has to do two things a
 * splash of grey text cannot: say what will actually appear here, so the
 * screen reads as unbuilt rather than broken, and give the user something
 * worth doing now.
 *
 * The action is the same one the budgets empty state offers, for the same
 * reason -- these numbers are aggregated by category, so an uncategorised
 * transaction is a hole in every chart M5 will draw. Rules are what close it,
 * and they work on the data that is already on the device, so setting them up
 * today is not busywork against a screen that does not exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(
    modifier: Modifier = Modifier,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp)
                .testTag(InsightsTestTags.PLACEHOLDER),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.insights_placeholder_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.insights_placeholder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.insights_placeholder_prerequisite),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = onSetUpRules,
                        modifier = Modifier.testTag(InsightsTestTags.RULES_ACTION),
                    ) {
                        Text(stringResource(R.string.insights_placeholder_action))
                    }
                }
            }
        }
    }
}

internal object InsightsTestTags {
    const val PLACEHOLDER = "insights:placeholder"
    const val RULES_ACTION = "insights:rules_action"
    const val SETTINGS_ACTION = "insights:settings_action"
}
