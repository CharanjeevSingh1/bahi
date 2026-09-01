package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.sync.crypto.OpBatchCipher
import javax.crypto.SecretKey

/**
 * The most recent `kind=snapshot` file, decrypted -- both [DriveTransport]
 * (answering [dev.charanjeev.bahi.core.sync.SyncTransport.snapshot]) and
 * [DriveCompactor] (needing the previous round's state to fold the next one
 * onto, slice 9f) read this the same way, so it lives once rather than in
 * each class separately.
 */
internal suspend fun DriveApi.latestSnapshot(key: SecretKey): RemoteSnapshot? {
    val latest = list(KIND, KIND_SNAPSHOT).maxByOrNull { it.appProperties["n"]?.toLongOrNull() ?: -1L } ?: return null
    return OpBatchCipher.decryptSnapshot(get(latest.id), key)
}
