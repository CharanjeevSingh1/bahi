package dev.charanjeev.bahi.core.sync

import dev.charanjeev.bahi.core.data.repository.BudgetRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRepository
import dev.charanjeev.bahi.core.data.repository.CategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.SyncApplier
import dev.charanjeev.bahi.core.data.repository.TombstoneReaper
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import dev.charanjeev.bahi.core.sync.drive.DriveCompactor
import dev.charanjeev.bahi.core.sync.drive.DriveTransportException
import dev.charanjeev.bahi.core.sync.oauth.DriveAuthorization
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import okhttp3.Call

/** What one [SyncRunner.run] found, in the shape [dev.charanjeev.bahi.core.sync.work.SyncWorker] needs to turn into a `ListenableWorker.Result` (docs/sync-design.md §8.7). */
enum class SyncRunOutcome { SUCCESS, RETRYABLE_FAILURE, TERMINAL_FAILURE }

/**
 * `SyncEngine`'s first real caller (docs/sync-design.md §8.7, §13 slice 9g) --
 * everything this class does, `SyncWorker` and `:feature:settings` used to
 * have no reason to exist for, because nothing ran a cycle. Three things
 * converge here that were each deferred to "whoever wires the worker":
 *
 * **Device identity.** [SyncEngine] and [DriveCompactor] both take a
 * `deviceId` constructor parameter neither of their own docs could answer
 * (M4a, then slice 9f). [DeviceIdentity.current] is the answer, and this is
 * the one place it is asked for -- once per [run], not cached on this class,
 * because [DeviceIdentity] itself already caches it and re-asking is what
 * lets a device id minted mid-process (the very first call, ever) still
 * reach a [run] that started before it existed.
 *
 * **Compaction only runs against a real backend.** [DriveCompactor] is
 * Drive-specific -- election only means something when multiple untrusted
 * writers share one storage location (§8.3) -- so it is only constructed
 * when [SyncConfiguration.isConfigured], never against
 * [DisabledSyncTransport] or a transport a future backend might add.
 * `electIfNeeded` then `compact` is exactly the repeated call
 * [DriveCompactor]'s own doc named as slice 9g's job: cheap after the first
 * successful election (a single `list` call), so paying it every cycle
 * rather than gating it further is the same call `SyncEngine` already makes
 * for [reconcileIfBehindHorizon][SyncEngine]'s per-cycle snapshot check.
 * Compaction failing never fails the run: `SyncEngine.sync()` above it
 * already succeeded, so a lost election race or a transient Drive error
 * here is next cycle's problem, not a reason to tell the user their data
 * didn't sync when it did.
 *
 * **Failure classification.** §8.7 needs three outcomes, not two:
 * transient (retry), revoked authorization (terminal, needs the user),
 * and everything else terminal (quota, a malformed remote state) -- also the
 * user's problem, but not one re-consenting fixes. [DriveTransportException]
 * now carries both bits it takes to tell those apart
 * ([DriveTransportException.retryable], [DriveTransportException.needsReauthorization]);
 * this is the one place that reads both and reports through
 * [SyncStatusRepository], which is what makes [dev.charanjeev.bahi.core.model.SyncStatus]
 * stop being decorative.
 *
 * **One [SyncEngine] per process, not per [run].** `SyncEngine`'s cursor and
 * push sequence have to "survive across many `sync` calls on the same
 * instance" (its own doc) -- a fresh instance on every call is a fresh,
 * empty cursor, which would turn every periodic tick into a full re-pull of
 * every peer's entire history rather than an incremental one. [engineFor]
 * exists so that within one process's lifetime, every [run] reuses the same
 * engine -- the same "long-lived within one process" shape
 * docs/sync-design.md §10.1's two-device harness already relies on when it
 * keeps one `SyncEngine` per simulated device.
 *
 * **The cursor now survives process death too.** WorkManager does not keep
 * a process alive between periodic executions -- on a real device, most
 * 4-hour ticks (§8.7) run in a freshly-started process, which used to mean a
 * fresh, empty cursor and therefore a full re-pull of every peer's entire
 * history (and likely a §7 horizon reconciliation on top of it) on most real
 * ticks, not just the first one ever. [engineFor] now seeds a new engine
 * from [preferences]' `syncCursor` (§8.3's per-device map, unused since M0)
 * instead of starting empty, and [run] writes [SyncEngine.cursorSnapshot]
 * back after every cycle that completes its pull. A run that throws before
 * reaching that write persists nothing from this cycle, but the cached
 * engine itself is untouched -- a later call in the same process still has
 * whatever progress it made, and a retry that starts a new process re-pulls
 * only back to the last successful cycle's cursor, not from zero.
 *
 * **The push sequence survives process death too, and needs a different
 * shape of fix.** A reused push sequence number is worse than a redundant
 * pull: idempotence forgives re-delivering a batch, but a peer whose cursor
 * for this device already covers the reused number filters the new batch
 * out before ever looking inside it -- silently, with no conflict record and
 * nothing to reconcile against later (see [SyncEngine]'s own doc). So unlike
 * the cursor, [engineFor] does not seed [SyncEngine.initialPushSeq] from
 * `UserPreferencesDataSource.pushSeq` alone, and [run] does not wait for the
 * whole cycle to finish before persisting it: [SyncEngine] calls
 * [UserPreferencesDataSource.setPushSeq] itself, through the
 * [SyncEngine.persistPushSeq] hook wired below, the moment a number is
 * reserved and before it is ever handed to [transport] -- see
 * [SyncEngine]'s doc for why that order is what makes a crash burn a number
 * rather than reuse one. The seed is rebased against the persisted cursor's
 * own-device entry too, not read from `pushSeq` in isolation, for the same
 * migration-gap reason [SyncEngine]'s doc names.
 */
@Singleton
class SyncRunner @Inject constructor(
    private val deviceIdentity: DeviceIdentity,
    private val transport: SyncTransport,
    private val applier: SyncApplier,
    private val reaper: TombstoneReaper,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository,
    private val categoryRuleRepository: CategoryRuleRepository,
    private val statusRepository: SyncStatusRepository,
    private val preferences: UserPreferencesDataSource,
    private val syncConfiguration: SyncConfiguration,
    private val driveAuthorization: DriveAuthorization,
    private val keyStore: SyncEncryptionKeyStore,
    private val callFactory: Call.Factory,
    // The same injected kotlinx.datetime.Clock every other time-dependent
    // class in this app takes (:core:data's ClockModule), not a raw
    // java.time.Instant::now lambda the way DriveCompactor's own [clock]
    // parameter is: DriveCompactor is plain-constructed here, never by
    // Hilt, so nothing stops it from defaulting a parameter Dagger will
    // never see, but this class's constructor is @Inject and Hilt resolves
    // every parameter regardless of a Kotlin default.
    private val clock: Clock,
) {

    private val engineMutex = Mutex()
    private var cachedEngine: SyncEngine? = null

    /**
     * Get-or-create, guarded the same way [DeviceIdentity.current] is -- two
     * calls racing on the first-ever [run] in a process must not each build
     * their own engine and silently drop one's cursor. The seed read from
     * [preferences] happens inside this same guard, once, for the same
     * reason: it is only correct as the *first* engine's starting point,
     * never a later call's.
     */
    private suspend fun engineFor(deviceId: String): SyncEngine {
        cachedEngine?.let { return it }
        return engineMutex.withLock {
            cachedEngine ?: run {
                val cursor = preferences.syncCursor.first()
                SyncEngine(
                    transport, applier, reaper,
                    transactionRepository, categoryRepository, budgetRepository, categoryRuleRepository,
                    deviceId,
                    initialCursor = cursor,
                    // Rebased against this device's own entry in its persisted
                    // cursor, not read from pushSeq alone -- see SyncEngine's
                    // doc for the migration gap this closes.
                    initialPushSeq = maxOf(preferences.pushSeq.first() ?: 0L, cursor[deviceId] ?: 0L),
                    persistPushSeq = preferences::setPushSeq,
                ).also { cachedEngine = it }
            }
        }
    }

    suspend fun run(): SyncRunOutcome {
        statusRepository.reportRunning()
        val deviceId = deviceIdentity.current()

        return try {
            val engine = engineFor(deviceId)
            engine.sync()
            preferences.setSyncCursor(engine.cursorSnapshot)

            if (syncConfiguration.isConfigured) runCompaction(deviceId)

            statusRepository.reportSuccess(clock.now())
            SyncRunOutcome.SUCCESS
        } catch (e: DriveTransportException) {
            when {
                e.needsReauthorization -> {
                    statusRepository.reportNeedsReauthorization()
                    SyncRunOutcome.TERMINAL_FAILURE
                }
                e.retryable -> {
                    statusRepository.reportFailed(e.message ?: "sync failed", retryable = true)
                    SyncRunOutcome.RETRYABLE_FAILURE
                }
                else -> {
                    statusRepository.reportFailed(e.message ?: "sync failed", retryable = false)
                    SyncRunOutcome.TERMINAL_FAILURE
                }
            }
        }
    }

    private suspend fun runCompaction(deviceId: String) {
        try {
            val compactor = DriveCompactor(driveAuthorization, keyStore, callFactory, deviceId)
            compactor.electIfNeeded()
            compactor.compact()
        } catch (e: DriveTransportException) {
            // Best-effort maintenance: the sync cycle this run exists to
            // report on already succeeded above. A lost election race or a
            // transient Drive error here is next cycle's problem (compaction
            // has no user-visible deadline), not a reason to turn a
            // successful sync into a reported failure.
        }
    }
}
