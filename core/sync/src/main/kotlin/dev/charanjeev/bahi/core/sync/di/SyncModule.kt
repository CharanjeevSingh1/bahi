package dev.charanjeev.bahi.core.sync.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.charanjeev.bahi.core.data.repository.RemoteMerge
import dev.charanjeev.bahi.core.sync.ConflictResolver
import dev.charanjeev.bahi.core.sync.ConflictResolverRemoteMerge
import dev.charanjeev.bahi.core.sync.DefaultConflictResolver
import dev.charanjeev.bahi.core.sync.DefaultSyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.DisabledSyncTransport
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.crypto.AndroidKeyStoreKeyWrapper
import dev.charanjeev.bahi.core.sync.crypto.KeyWrapper
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

    /**
     * Bound unconditionally -- there is only one `SyncTransport`
     * implementation to choose between so far, configured or not.
     * [DisabledSyncTransport]'s own doc has the reasoning.
     */
    @Binds
    @Singleton
    fun bindSyncTransport(implementation: DisabledSyncTransport): SyncTransport

    /** `AndroidKeyStore`-backed -- see [AndroidKeyStoreKeyWrapper]'s doc for why this is the only implementation and why it is verified on-device rather than by `testDebugUnitTest`. */
    @Binds
    @Singleton
    fun bindKeyWrapper(implementation: AndroidKeyStoreKeyWrapper): KeyWrapper

    @Binds
    @Singleton
    fun bindSyncEncryptionKeyStore(implementation: DefaultSyncEncryptionKeyStore): SyncEncryptionKeyStore
}
