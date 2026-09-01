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
import dev.charanjeev.bahi.core.sync.DefaultDeviceIdentity
import dev.charanjeev.bahi.core.sync.DefaultSyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.DefaultSyncStatusRepository
import dev.charanjeev.bahi.core.sync.DeviceIdentity
import dev.charanjeev.bahi.core.sync.DisabledSyncTransport
import dev.charanjeev.bahi.core.sync.SyncConfiguration
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.SyncStatusRepository
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.crypto.AndroidKeyStoreKeyWrapper
import dev.charanjeev.bahi.core.sync.crypto.KeyWrapper
import dev.charanjeev.bahi.core.sync.drive.DriveTransport
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.oauth.PlayServicesDriveAuthorization
import dev.charanjeev.bahi.core.sync.work.DefaultSyncScheduler
import dev.charanjeev.bahi.core.sync.work.SyncScheduler
import javax.inject.Provider
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

    @Binds
    @Singleton
    fun bindDeviceIdentity(implementation: DefaultDeviceIdentity): DeviceIdentity

    @Binds
    @Singleton
    fun bindSyncStatusRepository(implementation: DefaultSyncStatusRepository): SyncStatusRepository

    @Binds
    @Singleton
    fun bindSyncScheduler(implementation: DefaultSyncScheduler): SyncScheduler
}

/**
 * This is where slice 9g makes good on [DisabledSyncTransport]'s own doc:
 * `DriveTransport` has existed, fully built and tested, since slice 9e, but
 * binding it here unconditionally would mean the app talks to Drive the
 * moment a build happens to have `sync.properties` and Google Play Services
 * lying around, whether or not the user ever set up sync -- exactly the
 * silent-behind-a-flag surprise `DisabledSyncTransport` throwing loudly
 * exists to prevent. `Provider<T>`, not the transport itself, as the
 * parameter: injecting `DriveTransport` directly would construct it (and
 * everything it depends on transitively) on every graph build regardless of
 * which branch wins, which defeats the point of `DisabledSyncTransport`
 * being the cheap, side-effect-free default for the common (unconfigured)
 * case this app ships as.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncTransportModule {

    @Provides
    @Singleton
    fun provideSyncTransport(
        syncConfiguration: SyncConfiguration,
        driveTransport: Provider<DriveTransport>,
        disabledSyncTransport: Provider<DisabledSyncTransport>,
    ): SyncTransport = if (syncConfiguration.isConfigured) driveTransport.get() else disabledSyncTransport.get()
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
