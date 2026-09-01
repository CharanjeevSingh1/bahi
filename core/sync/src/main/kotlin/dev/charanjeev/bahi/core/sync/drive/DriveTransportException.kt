package dev.charanjeev.bahi.core.sync.drive

/**
 * Something [DriveTransport] could not do, with [retryable] carrying the one
 * distinction that actually matters to a caller (docs/sync-design.md §8.7,
 * §10.5's "reports a terminal failure, not an infinite retry" manual-plan
 * row): a 429 or 5xx is Drive being temporarily unavailable, worth trying
 * again; a 401/403/404 -- no authorization, no quota, no file -- is not
 * something retrying on a schedule fixes. Classifying *how* to react to
 * either is the periodic worker's job (slice 9g), not this class's; this is
 * only the fact the worker will need.
 */
class DriveTransportException(message: String, val retryable: Boolean) : Exception(message)
