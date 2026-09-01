package dev.charanjeev.bahi.core.sync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.charanjeev.bahi.core.sync.SyncConfiguration
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two triggers §8.7 asks for: a periodic safety net and an expedited
 * nudge when the user is actually looking at the app. Both are gated on
 * [SyncConfiguration.isConfigured] here, once, rather than at every call
 * site ([dev.charanjeev.bahi.core.sync.work.SyncWorker] would only ever fail
 * loudly against [dev.charanjeev.bahi.core.sync.DisabledSyncTransport]
 * anyway) -- the same "one seam owns the not-configured case" shape
 * [DisabledSyncTransport] itself is.
 */
interface SyncScheduler {
    /** Enqueued once, idempotently, from `BahiApplication.onCreate` -- safe to call on every process start. */
    fun schedulePeriodic()

    /** Called on app foreground and on opening Settings (§8.7) -- a user who just edited on another device shouldn't wait up to 4 hours to see it here. */
    fun requestExpeditedSync()
}

@Singleton
class DefaultSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val syncConfiguration: SyncConfiguration,
) : SyncScheduler {

    override fun schedulePeriodic() {
        if (!syncConfiguration.isConfigured) return
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // Not UNMETERED: the payload is small JSON, not media: gating
                    // a finance app's sync on Wi-Fi only would mean days of
                    // drift for someone mostly on cellular (§8.7).
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        // KEEP, not REPLACE: calling this again on every process start (it is
        // called unconditionally from BahiApplication.onCreate) must not
        // reset an already-scheduled tick's countdown back to a full 4 hours.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun requestExpeditedSync() {
        if (!syncConfiguration.isConfigured) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP: the foreground trigger and the Settings-open trigger can both
        // fire within moments of each other (opening Settings right after
        // launching the app) -- one in-flight expedited sync is enough.
        WorkManager.getInstance(context).enqueueUniqueWork(EXPEDITED_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC_INTERVAL_HOURS = 4L
        const val PERIODIC_WORK_NAME = "sync-periodic"
        const val EXPEDITED_WORK_NAME = "sync-expedited"
    }
}
