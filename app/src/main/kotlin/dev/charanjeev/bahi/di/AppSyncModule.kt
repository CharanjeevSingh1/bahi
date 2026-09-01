package dev.charanjeev.bahi.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.BuildConfig
import dev.charanjeev.bahi.core.sync.SyncConfiguration
import javax.inject.Singleton

/**
 * `:core:sync`'s [SyncConfiguration] seam, answered here rather than inside
 * `:core:sync` itself: `BuildConfig.SYNC_CONFIGURED` only exists because
 * `:app`'s build script read `sync.properties` at configuration time
 * (app/build.gradle.kts, docs/sync-setup.md) -- library modules in this repo
 * don't generate a `BuildConfig` at all.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppSyncModule {

    @Provides
    @Singleton
    fun provideSyncConfiguration(): SyncConfiguration = object : SyncConfiguration {
        override val isConfigured: Boolean = BuildConfig.SYNC_CONFIGURED
    }
}
