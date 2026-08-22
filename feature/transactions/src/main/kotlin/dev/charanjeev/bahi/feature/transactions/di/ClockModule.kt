package dev.charanjeev.bahi.feature.transactions.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock

/**
 * Injected rather than read via Clock.System directly, so date grouping's
 * Today/Yesterday boundary can be pinned to a fixed instant in tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun provideClock(): Clock = Clock.System
}
