package dev.charanjeev.bahi.feature.settings

import android.content.Intent
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.oauth.DriveConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stands in for [dev.charanjeev.bahi.core.sync.oauth.PlayServicesDriveAuthorization],
 * which needs a real Google account -- see that class's doc. Scripted rather
 * than behavioural: [beginAuthorizationResult] and [completeAuthorizationResult]
 * are set by the test to whatever the scenario needs, since there is no real
 * consent flow to run underneath.
 */
class FakeDriveAuthorization(initialState: DriveConnectionState = DriveConnectionState.NOT_CONNECTED) : DriveAuthorization {

    val connectionStateFlow = MutableStateFlow(initialState)
    override val connectionState = connectionStateFlow

    var beginAuthorizationResult: ConsentRequest = ConsentRequest.Resolved(AuthorizationOutcome.Authorized("fake-token"))
    var completeAuthorizationResult: AuthorizationOutcome = AuthorizationOutcome.Authorized("fake-token")
    var beginAuthorizationCalls = 0
        private set
    var completeAuthorizationCalls = 0
        private set

    override suspend fun currentAccessToken(): AuthorizationOutcome = AuthorizationOutcome.Authorized("fake-token")

    override suspend fun beginAuthorization(): ConsentRequest {
        beginAuthorizationCalls++
        if (beginAuthorizationResult is ConsentRequest.Resolved) {
            applyOutcome((beginAuthorizationResult as ConsentRequest.Resolved).outcome)
        }
        return beginAuthorizationResult
    }

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): AuthorizationOutcome {
        completeAuthorizationCalls++
        applyOutcome(completeAuthorizationResult)
        return completeAuthorizationResult
    }

    private fun applyOutcome(outcome: AuthorizationOutcome) {
        connectionStateFlow.value = when (outcome) {
            is AuthorizationOutcome.Authorized -> DriveConnectionState.CONNECTED
            is AuthorizationOutcome.NeedsReauthorization -> DriveConnectionState.NEEDS_REAUTHORIZATION
            is AuthorizationOutcome.Failed -> connectionStateFlow.value
        }
    }
}
