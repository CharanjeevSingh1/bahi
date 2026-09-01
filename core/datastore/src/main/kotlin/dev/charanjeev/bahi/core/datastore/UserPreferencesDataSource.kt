package dev.charanjeev.bahi.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val lastSyncCursor: Flow<String?> = dataStore.data.map { it[KEY_SYNC_CURSOR] }

    suspend fun setLastSyncCursor(cursor: String) {
        dataStore.edit { it[KEY_SYNC_CURSOR] = cursor }
    }

    /**
     * The AndroidKeyStore-wrapped sync encryption key (docs/sync-design.md
     * §8.4, D9, slice 9c) -- per-device by definition, same as [lastSyncCursor].
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

    private companion object {
        val KEY_SYNC_CURSOR = stringPreferencesKey("last_sync_cursor")
        val KEY_SYNC_ENCRYPTION_SALT = stringPreferencesKey("sync_encryption_salt")
        val KEY_SYNC_ENCRYPTION_WRAPPED_KEY = stringPreferencesKey("sync_encryption_wrapped_key")
        val KEY_SYNC_ENCRYPTION_WRAPPED_KEY_IV = stringPreferencesKey("sync_encryption_wrapped_key_iv")
        val KEY_DRIVE_AUTHORIZED = booleanPreferencesKey("drive_authorized")
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
