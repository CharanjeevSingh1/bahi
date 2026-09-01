package dev.charanjeev.bahi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.charanjeev.bahi.core.common.ApplicationScope
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.sync.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BahiApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var categoryRepository: CategoryRepository

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    // AndroidManifest.xml removes WorkManager's default startup initializer
    // for exactly this -- a HiltWorkerFactory-aware WorkManager has to come
    // from here, the only place the two Hilt-injected fields above exist.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Off the main thread and safe alongside the first read of categories:
        // seedSystemCategoriesIfNeeded only inserts ids that are absent, so it
        // never races a concurrent read into an inconsistent state -- at worst
        // the first emission just precedes the seeded rows.
        applicationScope.launch { categoryRepository.seedSystemCategoriesIfNeeded() }

        // No-op in release: see DebugSeeder.kt in src/debug vs. src/release.
        seedTransactionsForDevelopment(transactionRepository, applicationScope)

        // Idempotent (ExistingPeriodicWorkPolicy.KEEP) and a no-op on an
        // unconfigured build -- safe to call on every process start
        // (docs/sync-design.md §8.7, §13 slice 9g).
        syncScheduler.schedulePeriodic()
    }
}
