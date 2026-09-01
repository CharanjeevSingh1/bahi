package dev.charanjeev.bahi.core.sync.drive

/**
 * The `appProperties[KIND]` tag every file this app writes to `appDataFolder`
 * carries, and the values it takes -- shared between [DriveTransport] (`ops`,
 * `salt`) and [DriveCompactor] (`snapshot`, `owner`, slice 9f) so the two
 * classes tag and query the same folder consistently rather than each
 * inventing its own scheme.
 */
internal const val KIND = "kind"
internal const val KIND_OPS = "ops"
internal const val KIND_SALT = "salt"
internal const val KIND_SNAPSHOT = "snapshot"
internal const val KIND_OWNER = "owner"
