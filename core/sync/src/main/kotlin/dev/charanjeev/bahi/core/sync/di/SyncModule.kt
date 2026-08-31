package dev.charanjeev.bahi.core.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.core.data.repository.RemoteMerge
import dev.charanjeev.bahi.core.sync.ConflictResolver
import dev.charanjeev.bahi.core.sync.ConflictResolverRemoteMerge
import dev.charanjeev.bahi.core.sync.DefaultConflictResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {

    @Binds
    @Singleton
    fun bindConflictResolver(implementation: DefaultConflictResolver): ConflictResolver

    /**
     * `:core:data`'s `SyncApplier` needs the merge decision inside its own
     * transaction and cannot depend on `:core:sync` to get it -- see
     * [RemoteMerge]'s doc. This is the one place the two meet.
     */
    @Binds
    @Singleton
    fun bindRemoteMerge(implementation: ConflictResolverRemoteMerge): RemoteMerge
}
