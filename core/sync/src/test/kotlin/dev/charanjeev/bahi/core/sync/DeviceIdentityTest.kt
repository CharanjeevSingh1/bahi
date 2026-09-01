package dev.charanjeev.bahi.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [DefaultDeviceIdentity] against a real [DataStore] file, not a fake --
 * this is the first test in this module to need one (`:core:datastore` has
 * had none before, docs/sync-design.md §13 slice 9g), and the two things
 * worth a real file rather than an in-memory stand-in are exactly the two
 * this class's own doc makes claims about: that a second caller racing the
 * first still agrees on one id, and that the id a process wrote is the id a
 * later process reads back.
 */
class DeviceIdentityTest {

    private fun dataStore(file: File, scope: CoroutineScope = CoroutineScope(SupervisorJob())): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    @Test
    fun `mints an id on first call and returns the same one on every later call`() = runTest {
        val identity = DefaultDeviceIdentity(UserPreferencesDataSource(dataStore(File.createTempFile("device-identity", ".preferences_pb"))))

        val first = identity.current()
        val second = identity.current()

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `two concurrent first callers still agree on one id`() = runTest {
        val identity = DefaultDeviceIdentity(UserPreferencesDataSource(dataStore(File.createTempFile("device-identity", ".preferences_pb"))))

        val a = async { identity.current() }
        val b = async { identity.current() }

        assertThat(a.await()).isEqualTo(b.await())
    }

    @Test
    fun `an id minted by one process is read back by a later one`() = runTest {
        val file = File.createTempFile("device-identity", ".preferences_pb")
        val firstProcessScope = CoroutineScope(SupervisorJob())
        val mintedId = DefaultDeviceIdentity(UserPreferencesDataSource(dataStore(file, firstProcessScope))).current()
        // DataStore refuses two live instances over the same file at once
        // (it is not this test's job to prove that); cancelling this scope
        // is what makes the second instance below a stand-in for the app
        // process actually having restarted, not a second instance racing
        // the first.
        firstProcessScope.cancel()

        val reread = DefaultDeviceIdentity(UserPreferencesDataSource(dataStore(file))).current()

        assertThat(reread).isEqualTo(mintedId)
    }
}
