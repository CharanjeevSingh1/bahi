package dev.charanjeev.bahi.core.sync

/**
 * Whether this build has M4b's Drive transport set up at all
 * (docs/sync-design.md §8.5, D12, slice 9a) -- read from whether
 * `sync.properties` existed at build time, not something this module can
 * check itself: only `:app`'s generated `BuildConfig` knows it, and
 * `:core:sync` has no dependency on `:app` to read that from directly (nor
 * does it generate its own `BuildConfig` -- library modules don't, per
 * `AndroidLibraryConventionPlugin`). This interface is the seam `:app` binds
 * a real answer through; `:feature:settings` depends on it to decide whether
 * to show the "not configured" row.
 */
interface SyncConfiguration {
    val isConfigured: Boolean
}
