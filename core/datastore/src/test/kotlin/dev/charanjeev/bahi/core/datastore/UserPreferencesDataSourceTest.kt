package dev.charanjeev.bahi.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [UserPreferencesDataSource.syncCursor] against a real [DataStore] file --
 * the same pattern `DeviceIdentityTest` (`:core:sync`) uses, since the two
 * properties are read back the same way: this is the encode/decode
 * round-trip half, `SyncRunnerTest` is the "a later process sees what an
 * earlier one wrote" half against the actual caller.
 */
class UserPreferencesDataSourceTest {

    private fun dataStore(file: File, scope: CoroutineScope = CoroutineScope(SupervisorJob())): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    @Test
    fun `syncCursor is empty for an install that has never synced`() = runTest {
        val preferences = UserPreferencesDataSource(dataStore(File.createTempFile("user-preferences", ".preferences_pb")))

        assertThat(preferences.syncCursor.first()).isEmpty()
    }

    @Test
    fun `setSyncCursor round-trips a per-device map, not just the last-written peer`() = runTest {
        val preferences = UserPreferencesDataSource(dataStore(File.createTempFile("user-preferences", ".preferences_pb")))

        preferences.setSyncCursor(mapOf("device-a" to 3L, "device-b" to 7L))

        assertThat(preferences.syncCursor.first()).containsExactly("device-a", 3L, "device-b", 7L)
    }

    @Test
    fun `a cursor written by one process is read back whole by a later one`() = runTest {
        val file = File.createTempFile("user-preferences", ".preferences_pb")
        val firstProcessScope = CoroutineScope(SupervisorJob())
        UserPreferencesDataSource(dataStore(file, firstProcessScope)).setSyncCursor(mapOf("device-a" to 1L, "device-b" to 2L))
        // DataStore refuses two live instances over the same file at once;
        // cancelling this scope is what makes the second instance below a
        // stand-in for the app process actually having restarted.
        firstProcessScope.cancel()

        val reread = UserPreferencesDataSource(dataStore(file)).syncCursor.first()

        assertThat(reread).containsExactly("device-a", 1L, "device-b", 2L)
    }

    @Test
    fun `pushSeq is null for an install that has never pushed`() = runTest {
        val preferences = UserPreferencesDataSource(dataStore(File.createTempFile("user-preferences", ".preferences_pb")))

        assertThat(preferences.pushSeq.first()).isNull()
    }

    @Test
    fun `a push sequence written by one process is read back by a later one`() = runTest {
        val file = File.createTempFile("user-preferences", ".preferences_pb")
        val firstProcessScope = CoroutineScope(SupervisorJob())
        UserPreferencesDataSource(dataStore(file, firstProcessScope)).setPushSeq(5L)
        firstProcessScope.cancel()

        val reread = UserPreferencesDataSource(dataStore(file)).pushSeq.first()

        assertThat(reread).isEqualTo(5L)
    }
}
