package dev.charanjeev.bahi.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.charanjeev.bahi.navigation.BahiNavHost
import dev.charanjeev.bahi.navigation.TopLevelDestination
import dev.charanjeev.bahi.navigation.navigateToTopLevelDestination

/**
 * The app shell: the bottom bar plus the nav host it drives.
 *
 * Nothing here holds the selected tab. It is read back out of the navigation
 * back stack every recomposition, which is the only copy of that fact and the
 * one the platform already saves and restores -- so surviving process death
 * costs nothing and cannot drift out of step with what is on screen.
 */
@Composable
fun BahiApp(navController: NavHostController = rememberNavController()) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    // Up the parent chain rather than off the current route: a pushed screen
    // sits inside its tab's graph, and the tab it is inside is the tab that
    // should stay lit while the user is on it.
    val selected = currentBackStackEntry?.destination?.hierarchy
        ?.firstNotNullOfOrNull { TopLevelDestination.forGraphRoute(it.route) }

    Scaffold(
        bottomBar = {
            // The bar stays up on pushed screens too -- the form, the import
            // flow, the editors. Tapping another tab there parks the screen
            // rather than discarding it: the tab's whole back stack is saved
            // and restored, so a half-typed transaction is still half-typed
            // when the user comes back. Hiding the bar would be guarding
            // against a data loss that per-tab back stacks already prevent,
            // at the cost of the one thing a finance app is for -- checking
            // what is left in a budget while entering the expense against it.
            //
            // Back is untouched, so the form's discard confirmation still
            // guards the exit that actually throws work away.
            if (selected != null) {
                BahiBottomBar(
                    selected = selected,
                    onSelect = { destination ->
                        navController.navigateToTopLevelDestination(
                            destination = destination,
                            isCurrent = destination == selected,
                        )
                    },
                )
            }
        },
        // Every feature screen owns a Scaffold with its own TopAppBar, so the
        // status bar and side insets are theirs to apply. This one owns only
        // the bottom bar; consumeWindowInsets then tells those inner Scaffolds
        // the bottom inset is already spoken for, so the space above the bar
        // isn't padded twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BahiNavHost(
            navController = navController,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        )
    }
}

@Composable
private fun BahiBottomBar(
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(modifier = Modifier.testTag(BahiAppTestTags.BOTTOM_BAR)) {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                        // The label below already says it, and a screen reader
                        // reading each tab's name twice is worse than once.
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                modifier = Modifier.testTag(BahiAppTestTags.tab(destination)),
            )
        }
    }
}

internal object BahiAppTestTags {
    const val BOTTOM_BAR = "app:bottom_bar"
    fun tab(destination: TopLevelDestination) = "app:bottom_bar:${destination.name.lowercase()}"
}
