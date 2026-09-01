package dev.charanjeev.bahi.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, non-relational state only: last sync cursor, default account, theme.
 * Anything the user can query or aggregate belongs in Room, not here.
 */
@Singleton
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /**
     * This device's pull cursor for every peer it has ever synced with --
     * `{deviceId: seq}` (docs/sync-design.md §8.3: "everything after cursor
     * X" is not expressible as one number across independently-appending
     * writers, which is why this is a map and not the single `String?` this
     * key started as at M0). Empty for an install that has never synced --
     * the same starting state [dev.charanjeev.bahi.core.sync.SyncEngine]'s
     * own in-memory cursor has before its first pull, so a caller that seeds
     * a fresh engine from this needs no separate "never synced" case.
     *
     * Reinstalling wipes this the same way it wipes [deviceId]
     * (`allowBackup="false"`, both in the same [Preferences] file) -- see
     * [deviceId]'s doc for why losing this alongside it is the correct
     * outcome and not a gap: the two only ever mean anything together.
     */
    val syncCursor: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_CURSOR]?.let { Json.decodeFromString(SYNC_CURSOR_SERIALIZER, it) } ?: emptyMap()
    }

    suspend fun setSyncCursor(cursor: Map<String, Long>) {
        dataStore.edit { it[KEY_SYNC_CURSOR] = Json.encodeToString(SYNC_CURSOR_SERIALIZER, cursor) }
    }

    /**
     * The AndroidKeyStore-wrapped sync encryption key (docs/sync-design.md
     * §8.4, D9, slice 9c) -- per-device by definition, same as [syncCursor].
     * The three fields are read from one [Preferences] snapshot in a single
     * `map`, not as three separate flows: [setSyncEncryptionKeyMaterial] writes
     * them inside one `edit` block, so they only ever change together, and
     * reading them together is what keeps a caller from ever observing two of
     * the three updated and the third not -- the same momentary-wrong-then-
     * correct shape this codebase refuses elsewhere (`SettingsUiState.Loading`,
     * budgets-design's `YearMonth`).
     */
    val syncEncryptionKeyMaterial: Flow<SyncEncryptionKeyMaterial?> = dataStore.data.map { prefs ->
        val salt = prefs[KEY_SYNC_ENCRYPTION_SALT]
        val wrappedKey = prefs[KEY_SYNC_ENCRYPTION_WRAPPED_KEY]
        val wrappedKeyIv = prefs[KEY_SYNC_ENCRYPTION_WRAPPED_KEY_IV]
        if (salt != null && wrappedKey != null && wrappedKeyIv != null) {
            SyncEncryptionKeyMaterial(salt, wrappedKey, wrappedKeyIv)
        } else {
            null
        }
    }

    suspend fun setSyncEncryptionKeyMaterial(material: SyncEncryptionKeyMaterial) {
        dataStore.edit {
            it[KEY_SYNC_ENCRYPTION_SALT] = material.saltBase64
            it[KEY_SYNC_ENCRYPTION_WRAPPED_KEY] = material.wrappedKeyBase64
            it[KEY_SYNC_ENCRYPTION_WRAPPED_KEY_IV] = material.wrappedKeyIvBase64
        }
    }

    /**
     * The one boolean-shaped fact §8.6 says this device's OAuth state reduces
     * to: whether `drive.appdata` access has ever been granted. Not a token
     * and not a refresh token -- the Authorization API owns minting and
     * refreshing those, silently, as long as consent hasn't been revoked
     * (slice 9d). Defaults `false` for a fresh install, same direction
     * `categories.updated_at`'s migration default picked for the same reason:
     * the safe side to be wrong on is the one that asks again, not the one
     * that assumes access it doesn't have.
     */
    val driveAuthorized: Flow<Boolean> = dataStore.data.map { it[KEY_DRIVE_AUTHORIZED] ?: false }

    suspend fun setDriveAuthorized(authorized: Boolean) {
        dataStore.edit { it[KEY_DRIVE_AUTHORIZED] = authorized }
    }

    /**
     * This installation's sync device id (docs/sync-design.md §13 slice 9g) --
     * generated once, by [dev.charanjeev.bahi.core.sync.DeviceIdentity], the
     * first time anything needs it, and reused for the life of the install.
     * `allowBackup="false"` (AndroidManifest.xml) means it cannot survive an
     * uninstall the way none of this file's other columns can either: a
     * reinstalled app is, correctly, a new device to `SyncEngine` and
     * `DriveCompactor` both, the same as it is a new consent grant
     * ([driveAuthorized] above) and a new encryption key
     * ([syncEncryptionKeyMaterial]).
     */
    val deviceId: Flow<String?> = dataStore.data.map { it[KEY_DEVICE_ID] }

    suspend fun setDeviceId(id: String) {
        dataStore.edit { it[KEY_DEVICE_ID] = id }
    }

    /**
     * This device's next push sequence number for
     * [dev.charanjeev.bahi.core.sync.SyncEngine] (docs/sync-design.md §13
     * slice 9g) -- the push-side counterpart to [syncCursor], which only
     * ever seeds and reads back a *pull* cursor. Null for an install that has
     * never pushed, the same "never synced" starting state [syncCursor]'s
     * empty map represents for pulling.
     *
     * Reinstalling wipes this alongside [deviceId] and [syncCursor] for the
     * same reason ([deviceId]'s doc): a fresh id has never pushed anything
     * under any peer's watermark, so a fresh counter for it is correct, not
     * a gap. See [dev.charanjeev.bahi.core.sync.SyncEngine]'s doc for why an
     * *existing* device's counter cannot simply restart at zero the same way.
     */
    val pushSeq: Flow<Long?> = dataStore.data.map { it[KEY_PUSH_SEQ] }

    suspend fun setPushSeq(seq: Long) {
        dataStore.edit { it[KEY_PUSH_SEQ] = seq }
    }

    /**
     * When [dev.charanjeev.bahi.core.sync.work.SyncWorker] last completed a
     * full cycle without error -- the signal §8.7 asks for specifically
     * because [KEY_DRIVE_AUTHORIZED] and the conflict count can both be
     * healthy while sync itself has been silently failing. Null until the
     * first successful run.
     */
    val lastSuccessfulSyncAt: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SUCCESSFUL_SYNC_AT]?.let(Instant::fromEpochMilliseconds)
    }

    suspend fun setLastSuccessfulSyncAt(instant: Instant) {
        dataStore.edit { it[KEY_LAST_SUCCESSFUL_SYNC_AT] = instant.toEpochMilliseconds() }
    }

    private companion object {
        val SYNC_CURSOR_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
        val KEY_SYNC_CURSOR = stringPreferencesKey("last_sync_cursor")
        val KEY_SYNC_ENCRYPTION_SALT = stringPreferencesKey("sync_encryption_salt")
        val KEY_SYNC_ENCRYPTION_WRAPPED_KEY = stringPreferencesKey("sync_encryption_wrapped_key")
        val KEY_SYNC_ENCRYPTION_WRAPPED_KEY_IV = stringPreferencesKey("sync_encryption_wrapped_key_iv")
        val KEY_DRIVE_AUTHORIZED = booleanPreferencesKey("drive_authorized")
        val KEY_DEVICE_ID = stringPreferencesKey("sync_device_id")
        val KEY_LAST_SUCCESSFUL_SYNC_AT = longPreferencesKey("last_successful_sync_at")
        val KEY_PUSH_SEQ = longPreferencesKey("sync_push_seq")
    }
}

/**
 * All three fields are opaque base64 already -- this module has no reason to
 * know they are crypto material, only that they are three strings that travel
 * together. Decoding and interpreting them is `:core:sync`'s job.
 */
data class SyncEncryptionKeyMaterial(
    val saltBase64: String,
    val wrappedKeyBase64: String,
    val wrappedKeyIvBase64: String,
)
