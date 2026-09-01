package dev.charanjeev.bahi.core.sync.drivetest

import android.content.Intent
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.oauth.DriveConnectionState
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Request

@Serializable
private data class TokenResponse(@SerialName("access_token") val accessToken: String)

/**
 * [dev.charanjeev.bahi.core.sync.oauth.PlayServicesDriveAuthorization] needs
 * a real Android runtime and Play Services -- neither of which a JVM test
 * has, so `DriveTransportContractTest` (docs/sync-design.md §10.5) cannot use
 * it. This does the same job with plain HTTP: a standing OAuth2 refresh
 * token for a throwaway Google account (already consented once, by hand, the
 * same setup step `docs/sync-manual-test-plan.md` documents) exchanged for a
 * fresh access token via Google's token endpoint directly. This is
 * deliberately not `PlayServicesDriveAuthorization`'s replacement -- it only
 * exists inside `driveTest`, is never bound by Hilt, and only implements
 * [currentAccessToken] for real; the interactive-consent methods have no
 * meaning for a token that was already granted out of band.
 */
class RefreshTokenDriveAuthorization(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val callFactory: Call.Factory,
) : DriveAuthorization {

    override val connectionState = flowOf(DriveConnectionState.CONNECTED)

    override suspend fun currentAccessToken(): AuthorizationOutcome {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()
        callFactory.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return AuthorizationOutcome.Failed("Token refresh failed (${response.code}): $text", retryable = false)
            val token = Json { ignoreUnknownKeys = true }.decodeFromString(TokenResponse.serializer(), text)
            return AuthorizationOutcome.Authorized(token.accessToken)
        }
    }

    override suspend fun beginAuthorization(): ConsentRequest = error("consent was already granted out of band -- see this class's doc")

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): AuthorizationOutcome =
        error("consent was already granted out of band -- see this class's doc")
}
