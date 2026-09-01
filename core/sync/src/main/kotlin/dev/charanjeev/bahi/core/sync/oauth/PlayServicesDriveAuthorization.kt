package dev.charanjeev.bahi.core.sync.oauth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest as GmsAuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The real [DriveAuthorization]: Google's Authorization API
 * (`Identity.getAuthorizationClient`), not the deprecated `GoogleSignInClient`
 * (docs/sync-design.md §8.6 explains the choice). Requesting only
 * [DRIVE_APPDATA_SCOPE] is what keeps this app out of Google's OAuth
 * verification review (§8.5, §8.6) -- a broader scope would need it.
 *
 * **What is and isn't verified.** This class cannot be exercised by any
 * automated test in this repo -- [DriveAuthorization]'s own doc says why. What
 * I was able to check manually, on the emulator also used to verify slice 9c
 * (real Play Services and Play Store, no Google account signed in): tapping
 * "Connect" really does call `Identity.getAuthorizationClient(context)
 * .authorize(...)`, really does get back an [com.google.android.gms.auth.api.
 * identity.AuthorizationResult] with `hasResolution() == true` and a real
 * `pendingIntent`, and that `pendingIntent`, launched through
 * `SettingsRoute`'s `rememberLauncherForActivityResult`, really does open
 * Google's own account/consent UI (`AuthorizationActivity` ->
 * `UnpackingRedirectActivity` -> the account-add flow, confirmed in `logcat`
 * by component name, not assumed). Backing out of it returns cleanly to
 * `DriveConnectionState.NOT_CONNECTED` with no crash and no stuck state. So
 * the wiring this class sits inside of -- button to Play Services to a real
 * system consent screen and back -- is verified, not just written to a
 * documented shape. **What I could not check**: completing a real consent
 * grant and everything downstream of one, because that requires a signed-in
 * Google account and this environment has neither one nor credentials to add
 * one. That means the [AuthorizationOutcome.Authorized] branch of
 * [completeAuthorization], and the exact [ApiException] status codes Google
 * returns for a revoked grant in [classify], rest on reading Google's
 * documented API contract rather than having watched them happen.
 * `docs/sync-manual-test-plan.md` (slice 9e) is where that gap gets a
 * checklist row instead of a claim.
 */
class PlayServicesDriveAuthorization @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: UserPreferencesDataSource,
) : DriveAuthorization {

    // Revocation is discovered, not stored (§8.6: "the next token request...
    // fails"), so it lives only as long as this process does. A restart with
    // driveAuthorized still true from the persisted flag shows CONNECTED
    // again until the next real token request re-discovers the revocation --
    // the same "safe to be briefly optimistic, not safe to be permanently
    // wrong" trade-off a cursor makes.
    private val sessionOverride = MutableStateFlow<DriveConnectionState?>(null)

    override val connectionState: Flow<DriveConnectionState> =
        combine(preferences.driveAuthorized, sessionOverride) { persisted, override ->
            override ?: if (persisted) DriveConnectionState.CONNECTED else DriveConnectionState.NOT_CONNECTED
        }

    override suspend fun currentAccessToken(): AuthorizationOutcome = try {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (result.hasResolution()) {
            // A silent refresh that still needs a resolution is the same
            // "the user has to act again" shape as an outright revocation --
            // §8.6 draws no distinction between them for classification
            // purposes, only between that and a transient failure.
            markNeedsReauthorization()
        } else {
            val token = result.accessToken
            if (token != null) AuthorizationOutcome.Authorized(token) else missingTokenFailure()
        }
    } catch (e: ApiException) {
        classify(e)
    }

    override suspend fun beginAuthorization(): ConsentRequest = try {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (result.hasResolution()) {
            ConsentRequest.NeedsConsent(requireNotNull(result.pendingIntent) { "hasResolution() true but pendingIntent null" })
        } else {
            val token = result.accessToken
            ConsentRequest.Resolved(if (token != null) grant(token) else missingTokenFailure())
        }
    } catch (e: ApiException) {
        ConsentRequest.Resolved(classify(e))
    }

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): AuthorizationOutcome {
        if (resultCode != Activity.RESULT_OK || data == null) {
            // The user backed out of the consent screen -- worth trying
            // again whenever they choose to, not a broken grant to react to.
            return AuthorizationOutcome.Failed("Consent was cancelled", retryable = true)
        }
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token != null) grant(token) else missingTokenFailure()
        } catch (e: ApiException) {
            classify(e)
        }
    }

    private suspend fun grant(accessToken: String): AuthorizationOutcome {
        preferences.setDriveAuthorized(true)
        sessionOverride.value = null
        return AuthorizationOutcome.Authorized(accessToken)
    }

    private fun markNeedsReauthorization(): AuthorizationOutcome {
        sessionOverride.value = DriveConnectionState.NEEDS_REAUTHORIZATION
        return AuthorizationOutcome.NeedsReauthorization
    }

    private fun missingTokenFailure(): AuthorizationOutcome =
        AuthorizationOutcome.Failed("Authorization completed with no access token", retryable = true)

    private fun classify(e: ApiException): AuthorizationOutcome = when (e.statusCode) {
        // Both codes mean the same thing here: whatever this device thought
        // it had is no longer honoured, and only fresh consent fixes it.
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.RESOLUTION_REQUIRED -> markNeedsReauthorization()
        else -> AuthorizationOutcome.Failed(e.message ?: "Authorization failed (status ${e.statusCode})", retryable = true)
    }

    private fun request(): GmsAuthorizationRequest =
        GmsAuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE))).build()

    /**
     * Bridges Play Services' callback-based [Task] to a suspend function
     * without the extra `kotlinx-coroutines-play-services` dependency --
     * `Task`'s listener API is already transitively on the classpath via
     * `play-services-auth`, and this is the whole of what that artifact would
     * have bought here.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
