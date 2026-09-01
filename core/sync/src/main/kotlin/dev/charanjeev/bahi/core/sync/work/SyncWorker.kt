package dev.charanjeev.bahi.core.sync.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.charanjeev.bahi.core.sync.SyncRunOutcome
import dev.charanjeev.bahi.core.sync.SyncRunner

/**
 * `SyncEngine`'s first real caller anywhere in the app (docs/sync-design.md
 * §8.7, §13 slice 9g). Deliberately thin: every decision worth testing --
 * which device id, whether to compact, how to classify a failure -- is
 * [SyncRunner]'s, so it can be proven with hand-written fakes the way the
 * rest of this codebase is, rather than needing `androidx.work:work-testing`
 * (a dependency this repo does not have and this slice does not add one
 * for). This class only exists to translate [SyncRunOutcome] into the
 * `Result` WorkManager understands -- too small a mapping to be worth a
 * fourth "not yet a signal" gap to test the way the rest of this file's
 * gaps were.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRunner: SyncRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncRunner.run()) {
        SyncRunOutcome.SUCCESS -> Result.success()
        // WorkManager's default backoff (BackoffPolicy.EXPONENTIAL, a 30s
        // initial delay) applies to every retry(); §8.7 explicitly wants
        // nothing hand-rolled on top of it.
        SyncRunOutcome.RETRYABLE_FAILURE -> Result.retry()
        // No further attempts this tick -- the next periodic tick or
        // foreground/Settings-open trigger is what tries again (§8.7).
        SyncRunOutcome.TERMINAL_FAILURE -> Result.failure()
    }
}
