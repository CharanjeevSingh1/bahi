package dev.charanjeev.bahi.core.sync.oauth

import android.app.PendingIntent

/** What asking for a Drive access token came back with (docs/sync-design.md §8.6, slice 9d). */
sealed interface AuthorizationOutcome {
    data class Authorized(val accessToken: String) : AuthorizationOutcome

    /**
     * Access was revoked, or never granted at all -- the same outcome either
     * way, since neither is something retrying on a backoff schedule can fix
     * on its own (§8.6: "that failure has to be classified, not treated as an
     * ordinary network error"). Only the user re-running consent resolves it.
     */
    data object NeedsReauthorization : AuthorizationOutcome

    /** Transient (network, a Play Services error unrelated to consent) -- worth retrying. */
    data class Failed(val message: String, val retryable: Boolean) : AuthorizationOutcome
}

/**
 * What starting (or re-starting) consent came back with. A separate type
 * from [AuthorizationOutcome], not a nullable [PendingIntent] alongside it,
 * because "consent is needed" and "here is the outcome" are not the same
 * shape of answer -- the caller either already has a usable
 * [AuthorizationOutcome] or has a [PendingIntent] to launch and no outcome
 * yet.
 */
sealed interface ConsentRequest {
    data class Resolved(val outcome: AuthorizationOutcome) : ConsentRequest
    data class NeedsConsent(val pendingIntent: PendingIntent) : ConsentRequest
}

/**
 * What `:feature:settings`' Drive connection row renders (docs/sync-design.md
 * §8.6, slice 9d) -- distinct from [dev.charanjeev.bahi.core.model.SyncStatus],
 * which is `SyncEngine`'s run-state and has no caller until 9g. This is live
 * from the moment a device first tries to authorize, the same way
 * `SyncEncryptionKeyStore.isSetUp` is live from 9c rather than waiting on the
 * transport that will eventually use it.
 */
enum class DriveConnectionState { NOT_CONNECTED, CONNECTED, NEEDS_REAUTHORIZATION }
