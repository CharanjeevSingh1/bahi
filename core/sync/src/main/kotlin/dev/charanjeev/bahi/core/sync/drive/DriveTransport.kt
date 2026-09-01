package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.sync.crypto.OpBatchCipher
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Call

private const val KIND = "kind"
private const val KIND_OPS = "ops"
private const val KIND_SALT = "salt"

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

    private val api = DriveApi(callFactory, ::accessToken)

    override suspend fun push(batch: OpBatch): Unit = withContext(ioDispatcher) {
        val key = requireKey()
        val envelope = OpBatchCipher.encrypt(batch, key)
        api.create(
            name = "ops-${batch.deviceId}-${batch.seq}.json",
            appProperties = mapOf(KIND to KIND_OPS, "deviceId" to batch.deviceId, "seq" to batch.seq.toString()),
            content = envelope,
        )
    }

    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = withContext(ioDispatcher) {
        val key = requireKey()
        api.list(KIND, KIND_OPS)
            .filter { file ->
                val deviceId = file.appProperties["deviceId"]
                val seq = file.appProperties["seq"]?.toLongOrNull()
                deviceId != null && seq != null && seq > (after[deviceId] ?: 0L)
            }
            .map { file -> OpBatchCipher.decrypt(api.get(file.id), key) }
    }

    /**
     * No writer of a compacted snapshot exists yet -- that is slice 9f's job,
     * along with the wire format one should use, which isn't this slice's to
     * invent ahead of the code that first writes one. Until then there is
     * nothing an incremental [pull] could miss, so this always answers with
     * [RemoteSnapshot]'s empty default, matching exactly what
     * `SyncTransportContractTest`'s only check on this method already
     * requires: "snapshot of a transport nothing has compacted has an empty
     * horizon."
     */
    override suspend fun snapshot(): RemoteSnapshot = RemoteSnapshot(horizon = emptyMap(), rows = emptyList())

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

    private suspend fun accessToken(): String = when (val outcome = driveAuthorization.currentAccessToken()) {
        is AuthorizationOutcome.Authorized -> outcome.accessToken
        is AuthorizationOutcome.NeedsReauthorization ->
            throw DriveTransportException("Drive access needs to be reauthorized", retryable = false)
        is AuthorizationOutcome.Failed ->
            throw DriveTransportException(outcome.message, retryable = outcome.retryable)
    }

    private suspend fun requireKey() = keyStore.cachedKey()
        ?: throw DriveTransportException("Sync encryption is not set up yet -- see PassphraseScreen", retryable = false)
}
