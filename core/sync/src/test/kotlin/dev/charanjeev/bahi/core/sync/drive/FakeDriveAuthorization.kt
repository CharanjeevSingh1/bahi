package dev.charanjeev.bahi.core.sync.drive

import android.content.Intent
import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.ConsentRequest
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import dev.charanjeev.bahi.core.sync.oauth.DriveConnectionState
import kotlinx.coroutines.flow.flowOf

/**
 * `:feature:settings` already has a `FakeDriveAuthorization` scripting
 * `beginAuthorization`/`completeAuthorization` (docs/sync-design.md slice
 * 9d) -- not reusable here, since a feature module's test sources can't be a
 * dependency of `:core:sync` (CLAUDE.md rule 1). [DriveTransport] only ever
 * calls [currentAccessToken], so this fake is smaller on purpose: the other
 * two methods exist to satisfy the interface and are never expected to run.
 */
class FakeDriveAuthorization(private var outcome: AuthorizationOutcome = AuthorizationOutcome.Authorized("fake-token")) : DriveAuthorization {

    override val connectionState = flowOf(DriveConnectionState.CONNECTED)

    fun setOutcome(outcome: AuthorizationOutcome) {
        this.outcome = outcome
    }

    override suspend fun currentAccessToken(): AuthorizationOutcome = outcome

    override suspend fun beginAuthorization(): ConsentRequest = error("not exercised by DriveTransport")

    override suspend fun completeAuthorization(resultCode: Int, data: Intent?): AuthorizationOutcome =
        error("not exercised by DriveTransport")
}
