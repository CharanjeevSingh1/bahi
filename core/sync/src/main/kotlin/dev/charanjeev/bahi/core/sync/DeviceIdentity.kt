package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The id `SyncEngine` puts on every op it produces and `DriveCompactor` uses
 * as its election tiebreak (docs/sync-design.md §13 slice 9g) -- the gap both
 * of those classes' own docs named as unresolved since M4a/9f: neither
 * generates or persists one itself.
 *
 * **A random id, not a hardware one.** `Settings.Secure.ANDROID_ID` or the
 * old `TelephonyManager` device ids exist, but reaching for either would be
 * asking a value meant to identify *hardware* to instead identify *this
 * app's install* on it -- the two are the same thing for exactly as long as
 * nobody reinstalls, which is precisely the case this design has to get
 * right (see [DefaultDeviceIdentity]'s doc). A fresh [UUID] generated here
 * and persisted alongside the rest of this install's sync state
 * ([dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource.deviceId])
 * has no such coupling, and matches how every other id in this app is
 * minted (`UUID.randomUUID()` for a category, a budget, an import batch).
 */
interface DeviceIdentity {
    /** Get-or-create: the first caller in this install's lifetime mints and persists an id; every later caller, in this process or a future one, reads the same value back. */
    suspend fun current(): String
}

/**
 * **What a reinstall does to this id, stated plainly rather than left
 * implicit.** `AndroidManifest.xml` sets `allowBackup="false"`, so
 * [UserPreferencesDataSource]'s whole `Preferences` file -- this id
 * included -- is gone the moment the app is uninstalled, the same as
 * [UserPreferencesDataSource.driveAuthorized] and
 * [UserPreferencesDataSource.syncEncryptionKeyMaterial] are. A reinstalled
 * app mints a new id the next time [current] is called and is, correctly, a
 * new device to every peer it syncs with: its old id's already-pushed ops
 * are not deleted or reattributed, they simply belong to a device that no
 * longer exists, which is a case this codebase already has to handle for
 * the ordinary "device lost, never coming back" scenario (`SyncEngine`
 * itself only ever pulls forward, never depends on a peer id it has seen
 * before continuing to mean the same physical device). If the old id had
 * been elected `DriveCompactor`'s owner, that claim is now exactly the
 * "gone device" case `DriveCompactor.isStale`/`takeOver` already exist to
 * resolve (docs/sync-design.md §13 slice 9f) -- reinstalling is not a new
 * failure mode compaction has to learn, it is the same one under a
 * different cause.
 */
@Singleton
class DefaultDeviceIdentity @Inject constructor(
    private val preferences: UserPreferencesDataSource,
) : DeviceIdentity {

    // Guards against two concurrent first-callers (the foreground trigger and
    // the periodic worker both racing to sync right after a fresh install)
    // minting two different ids -- DataStore itself has no get-or-create, so
    // without this the second writer's id would silently win.
    private val mutex = Mutex()

    override suspend fun current(): String {
        preferences.deviceId.first()?.let { return it }
        return mutex.withLock {
            preferences.deviceId.first()?.let { return@withLock it }
            UUID.randomUUID().toString().also { preferences.setDeviceId(it) }
        }
    }
}
