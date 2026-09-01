package dev.charanjeev.bahi.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * [UserPreferencesDataSource][dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource]
 * has existed since M0 with nothing providing the [DataStore] its constructor
 * asks for -- nothing needed it before slice 9c, since `lastSyncCursor` has no
 * caller until M4b's transport does (docs/sync-design.md §8.3) and encryption
 * key material (§8.4) is the first thing that actually reads and writes this
 * store. One file, one preferences instance, shared by everything in
 * `:core:datastore` -- there is no reason for two.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("bahi_preferences") }
}
