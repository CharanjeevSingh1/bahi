package dev.charanjeev.bahi.core.sync.drive

/**
 * Something [DriveTransport] could not do, with [retryable] carrying the
 * first distinction that matters to a caller (docs/sync-design.md §8.7,
 * §10.5's "reports a terminal failure, not an infinite retry" manual-plan
 * row): a 429 or 5xx is Drive being temporarily unavailable, worth trying
 * again; a 401/403/404 -- no authorization, no quota, no file -- is not
 * something retrying on a schedule fixes.
 *
 * [needsReauthorization] carries the second distinction, which [retryable]
 * alone cannot: §8.7 also wants "revoked authorization" told apart from
 * "Drive quota genuinely exhausted," but both reach [DriveApi] the same
 * way -- a non-2xx response -- and [DriveAccessToken] is the one place that
 * actually knows *why* a call never got a bearer token to send. Only it sets
 * this true; every other throw site (an HTTP status [DriveApi] itself saw)
 * leaves it false, meaning "terminal, but not something re-consenting would
 * fix." `SyncRunner` (slice 9g) is what acts on this; this class only carries
 * the fact.
 */
class DriveTransportException(
    message: String,
    val retryable: Boolean,
    val needsReauthorization: Boolean = false,
) : Exception(message)
