package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.sync.SyncEncryptionKeyStore
import javax.crypto.SecretKey

/**
 * [DriveTransport] and [DriveCompactor] (slice 9f) both refuse to touch
 * ciphertext without a real key rather than silently no-op or write
 * plaintext -- the same refusal, needed in two places, so it lives once.
 */
internal suspend fun SyncEncryptionKeyStore.requireSyncKey(): SecretKey =
    cachedKey() ?: throw DriveTransportException("Sync encryption is not set up yet -- see PassphraseScreen", retryable = false)
