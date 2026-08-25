package dev.charanjeev.bahi.navigation

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.feature.budgets.navigation.RulesRoute
import dev.charanjeev.bahi.feature.csvimport.navigation.ImportRoute
import dev.charanjeev.bahi.feature.transactions.navigation.NewTransactionRoute
import org.junit.Test

/**
 * [TopLevelDestination.forGraphRoute] decides which tab is lit. It answers for
 * graphs and only for graphs, which is what lets the bar keep a tab highlighted
 * while the user is several screens deep inside it.
 */
class TopLevelDestinationTest {

    @Test
    fun `every tab is recognised by its own graph route`() {
        val recognised = TopLevelDestination.entries.map { TopLevelDestination.forGraphRoute(it.graphRoute) }

        assertThat(recognised).containsExactlyElementsIn(TopLevelDestination.entries).inOrder()
    }

    @Test
    fun `no two tabs claim the same graph route`() {
        assertThat(TopLevelDestination.entries.map { it.graphRoute }).containsNoDuplicates()
    }

    // The reason selection is a hierarchy walk in BahiApp rather than a lookup
    // on the current route: no screen route answers here, not even a tab's own
    // root. Every match has to come from a parent graph.
    @Test
    fun `a screen route is never a tab, including the screen at a tab's root`() {
        val screenRoutes = TopLevelDestination.entries.map { it.startRoute } +
            listOf(NewTransactionRoute, ImportRoute, RulesRoute)

        assertThat(screenRoutes.mapNotNull { TopLevelDestination.forGraphRoute(it) }).isEmpty()
    }

    @Test
    fun `an unset route is not a tab`() {
        // currentDestination is null for a frame before the graph is set, and
        // a null route must not match a tab that happens to have none.
        assertThat(TopLevelDestination.forGraphRoute(null)).isNull()
    }
}
