package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.sync.SyncConfiguration

/** Defaults to configured -- most ViewModel tests care about conflicts, not this. */
class FakeSyncConfiguration(override val isConfigured: Boolean = true) : SyncConfiguration
