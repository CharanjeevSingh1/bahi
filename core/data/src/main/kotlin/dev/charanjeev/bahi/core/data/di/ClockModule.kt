package dev.charanjeev.bahi.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock

/**
 * Injected rather than read via Clock.System directly, so time-dependent
 * behaviour -- date grouping's Today/Yesterday boundary, an import's
 * createdAt/updatedAt -- can be pinned to a fixed instant in tests. Lives
 * here rather than in :feature:transactions (its original home) because
 * :core:importer needs it too, and a feature module isn't something a core
 * module can depend on.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun provideClock(): Clock = Clock.System
}
