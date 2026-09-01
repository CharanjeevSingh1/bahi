package dev.charanjeev.bahi.core.sync.drive

import dev.charanjeev.bahi.core.sync.oauth.AuthorizationOutcome
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization

/**
 * Turns [DriveAuthorization.currentAccessToken] into either a bearer token or
 * a [DriveTransportException] -- the one piece of [DriveTransport] logic
 * [DriveCompactor] also needs verbatim (slice 9f), since compaction makes the
 * same four REST calls [DriveApi] does and has to fail the same way when
 * authorization has lapsed. Extracted here rather than duplicated so the two
 * classes cannot drift on what "needs reauthorization" versus "a retryable
 * failure" means.
 */
internal class DriveAccessToken(private val driveAuthorization: DriveAuthorization) {

    suspend operator fun invoke(): String = when (val outcome = driveAuthorization.currentAccessToken()) {
        is AuthorizationOutcome.Authorized -> outcome.accessToken
        is AuthorizationOutcome.NeedsReauthorization ->
            throw DriveTransportException("Drive access needs to be reauthorized", retryable = false, needsReauthorization = true)
        is AuthorizationOutcome.Failed ->
            throw DriveTransportException(outcome.message, retryable = outcome.retryable)
    }
}
