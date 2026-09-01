package dev.charanjeev.bahi.core.sync.oauth

import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * `drive.appdata` access, seamed the same way [dev.charanjeev.bahi.core.sync.
 * SyncEncryptionKeyStore] is over `AndroidKeyStore` (docs/sync-design.md §8.6,
 * slice 9d): [PlayServicesDriveAuthorization] needs a real Google Play
 * Services install and, to complete consent, a real Google account -- neither
 * of which any automated test in this repo has. Everything built *on* this
 * interface (the ViewModel, the Settings row, the transient/terminal
 * classification a future periodic worker will need) is tested against a
 * fake; this interface is the line past which this codebase's testing
 * conventions cannot reach. See [PlayServicesDriveAuthorization]'s doc for
 * exactly what was and wasn't verified.
 */
interface DriveAuthorization {

    val connectionState: Flow<DriveConnectionState>

    /** Asks for a fresh access token, refreshing silently if consent already stands. */
    suspend fun currentAccessToken(): AuthorizationOutcome

    /** Starts (or re-starts) consent. [ConsentRequest.NeedsConsent]'s `pendingIntent` must be launched via the Activity Result API; the result comes back through [completeAuthorization]. */
    suspend fun beginAuthorization(): ConsentRequest

    /** Call with the [android.app.Activity]'s result once a [ConsentRequest.NeedsConsent] `pendingIntent` has been launched and returned. */
    suspend fun completeAuthorization(resultCode: Int, data: Intent?): AuthorizationOutcome
}

/** `https://www.googleapis.com/auth/drive.appdata`, and nothing broader, ever (§8.6). */
const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
