package dev.charanjeev.bahi.core.sync.convergence

import android.content.Context
import androidx.room.Room
import dev.charanjeev.bahi.core.data.repository.OfflineFirstBudgetRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstCategoryRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstCategoryRuleRepository
import dev.charanjeev.bahi.core.data.repository.OfflineFirstTransactionRepository
import dev.charanjeev.bahi.core.data.repository.RoomSyncApplier
import dev.charanjeev.bahi.core.data.repository.RoomSyncConflictRepository
import dev.charanjeev.bahi.core.data.repository.RoomTombstoneReaper
import dev.charanjeev.bahi.core.data.repository.SyncConflictRepository
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.sync.ConflictResolverRemoteMerge
import dev.charanjeev.bahi.core.sync.DefaultConflictResolver
import dev.charanjeev.bahi.core.sync.SyncEngine
import dev.charanjeev.bahi.core.sync.SyncTransport
import dev.charanjeev.bahi.core.testing.MutableClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

/**
 * One simulated device: its own real Room database, its own repositories,
 * its own [SyncEngine] -- everything a phone would have, minus the process
 * boundary. Two of these sharing one [SyncTransport] is
 * docs/sync-design.md §10.1's "two devices, one process": nothing in
 * [SyncEngine] or the repositories it drives is a singleton or reaches for a
 * global, so constructing two is the whole of what a genuine two-device test
 * needs.
 *
 * Lives in `:core:sync`'s androidTest rather than `:core:data`'s because
 * [SyncEngine] does: `:core:data` cannot depend on `:core:sync` (that would
 * be the cycle `:core:sync` -> `:core:data` -> `:core:sync`), so anything that
 * wires the two together for a test has to sit on the `:core:sync` side of
 * that edge. See this module's build.gradle.kts for why `:core:database`
 * being reachable here doesn't touch the module graph.
 *
 * Each device gets its own [MutableClock] on purpose: two real devices have
 * two clocks, possibly skewed, and never a shared one (§5.3's "wall clock is
 * never consulted for causality" -- but §5.5's tiebreak does read
 * `updated_at`, and a scenario that wants to control which side of that
 * tiebreak wins needs to move its own device's clock independently of the
 * other's).
 */
class SyncTestDevice(
    context: Context,
    transport: SyncTransport,
    val deviceId: String,
) {
    val clock = MutableClock()

    private val database: BahiDatabase = Room.inMemoryDatabaseBuilder(context, BahiDatabase::class.java).build()

    private val ioDispatcher = Dispatchers.IO

    val transactionRepository = OfflineFirstTransactionRepository(database.transactionDao(), clock, ioDispatcher)
    val categoryRepository = OfflineFirstCategoryRepository(database.categoryDao(), clock, ioDispatcher)
    val budgetRepository = OfflineFirstBudgetRepository(database.budgetDao(), database.transactionDao(), clock, ioDispatcher)
    val categoryRuleRepository = OfflineFirstCategoryRuleRepository(database.categoryRuleDao(), database.transactionDao(), clock, ioDispatcher)

    // The real resolver, not a stand-in -- this is what SyncApplierTest's
    // TestMerge deliberately isn't (its own doc says so): the convergence
    // suite has to prove the shipped policy converges, not a simplified
    // stand-in for it.
    private val applier = RoomSyncApplier(database, ConflictResolverRemoteMerge(DefaultConflictResolver()), clock)
    private val reaper = RoomTombstoneReaper(database, clock)

    val engine = SyncEngine(
        transport = transport,
        applier = applier,
        reaper = reaper,
        transactionRepository = transactionRepository,
        categoryRepository = categoryRepository,
        budgetRepository = budgetRepository,
        categoryRuleRepository = categoryRuleRepository,
        deviceId = deviceId,
    )

    /** Direct DAO access for [dump] only -- see that file for why. */
    internal suspend fun allBudgets() = database.budgetDao().getAllActive()

    /**
     * What [DefaultConflictResolver] recorded on this device (§5.6) --
     * scenarios assert on this directly, since "a field was resolved by
     * policy rather than fast-forwarded" is not otherwise observable from
     * [dump], which only ever shows the winning value.
     */
    suspend fun unacknowledgedConflicts() = database.syncConflictDao().observeUnacknowledged().first()

    /**
     * The same [SyncConflictRepository] Hilt binds in production
     * (`SyncModule`), not a stand-in -- what §8.8/slice 9h needs to prove
     * that a genuine merge produces a [dev.charanjeev.bahi.core.model.SyncConflict]
     * a real repository decodes correctly, not just the raw
     * [dev.charanjeev.bahi.core.database.entity.SyncConflictEntity]
     * [unacknowledgedConflicts] already exposed.
     */
    val conflictRepository: SyncConflictRepository = RoomSyncConflictRepository(database, clock, ioDispatcher)

    suspend fun sync() = engine.sync()

    fun close() = database.close()
}
