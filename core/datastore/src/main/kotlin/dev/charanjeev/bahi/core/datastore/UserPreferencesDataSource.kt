package dev.charanjeev.bahi.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private companion object {
        val KEY_SYNC_CURSOR = stringPreferencesKey("last_sync_cursor")
    }
}
