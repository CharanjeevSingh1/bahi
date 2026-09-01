package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.crypto.OpBatchCipher
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * The real [SyncTransport] (docs/sync-design.md §8.3, §13 slice 9e): each
 * push is one immutable Drive file under `appDataFolder`, named and tagged
 * so a pull can list exactly the ops it hasn't seen. [DriveApi] is the raw
 * REST layer this builds on; `SyncTransportContractTest` (subclassed
 * manually by `DriveTransportContractTest`, §10.5) is the specification this
 * class has to satisfy regardless of backend.
 *
 * **Encryption happens here, not above.** [SyncTransport.push] takes a
 * structured [OpBatch], not bytes -- so the byte-transform §8.4 designed
 * ([OpBatchCipher]) can only actually run at the one place an [OpBatch] gets
 * turned into bytes to leave the device, which is this class. Nothing else
 * in the app calls [OpBatchCipher] for that reason. A device with no key set
 * up ([SyncEncryptionKeyStore.cachedKey] null) cannot safely do either
 * operation, so both refuse loudly via [DriveTransportException] rather than
 * ever writing or reading plaintext.
 *
 * **The salt, published unencrypted.** [SyncEncryptionKeyStore]'s own doc
 * names this as this class's job: a second device cannot derive the same key
 * until it has the salt the first device generated, and nothing about a
 * shared `appDataFolder` should be assumed private -- encryption exists
 * *because* it isn't (§8.4). [publishSalt] and [readPublishedSalt] write and
 * read that one small plaintext file, entirely separate from the encrypted
 * op log. This is additive: `PassphraseScreen`'s manual pairing-code flow
 * (slice 9c) is untouched and still works on its own. Wiring a second device
 * to call [readPublishedSalt] automatically instead of asking for a pasted
 * code is a UX decision for whoever picks that up next -- not assumed here.
 */
class DriveTransport @Inject constructor(
    private val driveAuthorization: DriveAuthorization,
    private val keyStore: SyncEncryptionKeyStore,
    callFactory: Call.Factory,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : SyncTransport {

    private val api = DriveApi(callFactory, DriveAccessToken(driveAuthorization)::invoke)

    override suspend fun push(batch: OpBatch): Unit = withContext(ioDispatcher) {
        val key = keyStore.requireSyncKey()
        val envelope = OpBatchCipher.encrypt(batch, key)
        api.create(
            name = "ops-${batch.deviceId}-${batch.seq}.json",
            appProperties = mapOf(KIND to KIND_OPS, "deviceId" to batch.deviceId, "seq" to batch.seq.toString()),
            content = envelope,
        )
    }

    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = withContext(ioDispatcher) {
        val key = keyStore.requireSyncKey()
        api.list(KIND, KIND_OPS)
            .filter { file ->
                val deviceId = file.appProperties["deviceId"]
                val seq = file.appProperties["seq"]?.toLongOrNull()
                deviceId != null && seq != null && seq > (after[deviceId] ?: 0L)
            }
            .map { file -> OpBatchCipher.decrypt(api.get(file.id), key) }
    }

    /**
     * The most recently written compacted snapshot, or [RemoteSnapshot]'s
     * empty default if [DriveCompactor] (slice 9f) has never written one --
     * satisfying `SyncTransportContractTest`'s "snapshot of a transport
     * nothing has compacted has an empty horizon" the same way it always did,
     * now because nothing tagged `snapshot` exists yet rather than because
     * this method never looked.
     *
     * Picks the file with the highest `n` among every `kind=snapshot` file
     * present. §8.3's original worry about that -- "`n` is assigned locally
     * by whichever device compacts... two devices computing it from their own
     * stale listings can produce values in either order or even collide" --
     * is exactly what D13's single-elected-compactor decision closes: with
     * only one device ever writing a snapshot, `n` increases in the order
     * that device assigned it, and "highest `n`" stops being a guess and
     * becomes the correct answer by construction.
     */
    override suspend fun snapshot(): RemoteSnapshot = withContext(ioDispatcher) {
        api.latestSnapshot(keyStore.requireSyncKey()) ?: RemoteSnapshot(horizon = emptyMap(), rows = emptyList())
    }

    /** Publishes this device's salt in the clear, so a second device can find it instead of being told it by hand. */
    suspend fun publishSalt(salt: ByteArray): Unit = withContext(ioDispatcher) {
        api.create(name = "salt.json", appProperties = mapOf(KIND to KIND_SALT), content = salt)
    }

    /**
     * The published salt, or null if no device has published one yet. Only
     * the first device in a sync group ever calls [publishSalt] -- every
     * other device pairs against that same salt rather than minting its own
     * -- so `firstOrNull()` picking an arbitrary file only matters if that
     * invariant is ever broken, which would itself be the bug to fix.
     */
    suspend fun readPublishedSalt(): ByteArray? = withContext(ioDispatcher) {
        api.list(KIND, KIND_SALT).firstOrNull()?.let { api.get(it.id) }
    }
}
