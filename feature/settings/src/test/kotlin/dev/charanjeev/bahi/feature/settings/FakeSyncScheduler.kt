package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.sync.work.SyncScheduler

class FakeSyncScheduler : SyncScheduler {
    var expeditedSyncRequests = 0
        private set

    override fun schedulePeriodic(): Unit = error("not used by SettingsViewModel")

    override fun requestExpeditedSync() {
        expeditedSyncRequests += 1
    }
}
