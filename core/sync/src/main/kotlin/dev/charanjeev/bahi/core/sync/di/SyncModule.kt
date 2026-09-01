package dev.charanjeev.bahi.core.sync.di

import dagger.Binds
import dagger.Module
import dagger.Provides
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
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.oauth.PlayServicesDriveAuthorization
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.OkHttpClient

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
     * Bound unconditionally, not behind a `SyncConfiguration.isConfigured`
     * check, even though `DriveTransport` (slice 9e) exists now --
     * conditional binding is deferred to slice 9g, on purpose.
     * [DisabledSyncTransport]'s
     * own doc has the reasoning.
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

    /** See [PlayServicesDriveAuthorization]'s doc for what this cannot be verified against in this repo. */
    @Binds
    @Singleton
    fun bindDriveAuthorization(implementation: PlayServicesDriveAuthorization): DriveAuthorization
}

/**
 * [Call.Factory], not the concrete [OkHttpClient], is what
 * [dev.charanjeev.bahi.core.sync.drive.DriveApi] asks for -- the same reason
 * [DriveAuthorization] is a seam rather than `PlayServicesDriveAuthorization`
 * itself: a test supplies a fake that implements the same interface without
 * touching the network. `@Provides`, not `@Binds`, because `OkHttpClient` is
 * a class this module doesn't own the constructor of.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCallFactory(): Call.Factory = OkHttpClient()
}
