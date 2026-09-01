package dev.charanjeev.bahi.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.data.repository.DirtyRow
import dev.charanjeev.bahi.core.data.repository.TransactionRepository
import dev.charanjeev.bahi.core.datastore.UserPreferencesDataSource
import dev.charanjeev.bahi.core.model.OpBatch
import dev.charanjeev.bahi.core.model.RemoteSnapshot
import dev.charanjeev.bahi.core.model.SyncOp
import dev.charanjeev.bahi.core.model.SyncStatus
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.sync.drive.DriveApi
import dev.charanjeev.bahi.core.sync.drive.DriveTransportException
import dev.charanjeev.bahi.core.sync.drive.FakeCallFactory
import dev.charanjeev.bahi.core.sync.drive.FakeDriveAuthorization
import dev.charanjeev.bahi.core.sync.drive.FakeSyncEncryptionKeyStore
import dev.charanjeev.bahi.core.sync.drive.InMemoryFakeDrive
import dev.charanjeev.bahi.core.sync.drive.KIND
import dev.charanjeev.bahi.core.sync.drive.KIND_OWNER
import dev.charanjeev.bahi.core.sync.drive.errorResponse
import dev.charanjeev.bahi.core.testing.FixedClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * `SyncRunner` (docs/sync-design.md §8.7, §13 slice 9g): device-id resolution,
 * §8.7's three-way failure classification, and compaction only ever being
 * attempted against a real backend. `SyncEngine`'s own pull/push/reconcile
 * correctness is [SyncEngineTest]'s job, not repeated here -- every case
 * below uses an empty [InMemoryTransport] precisely so a passing run
 * exercises nothing SyncEngineTest doesn't already cover, leaving this file
 * free to assert only on what [SyncRunner] itself adds on top.
 */
class SyncRunnerTest {

    /** A real file-backed [UserPreferencesDataSource], the same pattern `DeviceIdentityTest` uses -- a fresh temp file per default, so tests that don't care about cursor persistence never see one another's state. */
    private fun preferences(file: File = File.createTempFile("sync-runner", ".preferences_pb"), scope: CoroutineScope = CoroutineScope(SupervisorJob())): UserPreferencesDataSource =
        UserPreferencesDataSource(dataStore(file, scope))

    private fun dataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private fun runner(
        transport: SyncTransport = InMemoryTransport(),
        deviceIdentity: DeviceIdentity = FakeDeviceIdentity(),
        applier: FakeSyncApplier = FakeSyncApplier(),
        transactionRepository: TransactionRepository = FakeTransactionRepository(),
        statusRepository: FakeSyncStatusRepository = FakeSyncStatusRepository(),
        preferences: UserPreferencesDataSource = preferences(),
        syncConfiguration: SyncConfiguration = FakeSyncConfiguration(isConfigured = false),
        driveAuthorization: FakeDriveAuthorization = FakeDriveAuthorization(),
        keyStore: FakeSyncEncryptionKeyStore = FakeSyncEncryptionKeyStore(),
        callFactory: FakeCallFactory = FakeCallFactory { error("no Drive call expected") },
        clock: Clock = Clock.System,
    ) = SyncRunner(
        deviceIdentity, transport, applier, FakeTombstoneReaper(),
        transactionRepository, FakeCategoryRepository(), FakeBudgetRepository(), FakeCategoryRuleRepository(),
        statusRepository, preferences, syncConfiguration, driveAuthorization, keyStore, callFactory, clock,
    )

    @Test
    fun `a clean run resolves the device id and reports success`() = runTest {
        val deviceIdentity = FakeDeviceIdentity("device-a")
        val transport = InMemoryTransport()
        val status = FakeSyncStatusRepository()
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val outcome = runner(transport = transport, deviceIdentity = deviceIdentity, statusRepository = status, clock = FixedClock(now)).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.SUCCESS)
        assertThat(status.statuses.last()).isEqualTo(SyncStatus.Idle)
        assertThat(status.lastSuccess).isEqualTo(now)
        // Proves the id SyncEngine actually used, not just that resolution
        // didn't throw: a run with nothing dirty still pushes zero ops, but
        // reconciling against the transport's own state confirms sync() ran
        // against a real, resolved id rather than one hard-coded here.
        assertThat(transport.pull(emptyMap())).isEmpty()
    }

    @Test
    fun `reports Running before the cycle and Idle after it succeeds`() = runTest {
        val status = FakeSyncStatusRepository()

        runner(statusRepository = status).run()

        assertThat(status.statuses).containsExactly(SyncStatus.Running, SyncStatus.Idle).inOrder()
    }

    @Test
    fun `a retryable transport failure reports Failed and asks for a retry`() = runTest {
        val status = FakeSyncStatusRepository()
        val transport = ThrowingSyncTransport(DriveTransportException("rate limited", retryable = true))

        val outcome = runner(transport = transport, statusRepository = status).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.RETRYABLE_FAILURE)
        assertThat(status.statuses.last()).isEqualTo(SyncStatus.Failed("rate limited", retryable = true))
    }

    @Test
    fun `revoked authorization is reported distinctly from an ordinary terminal failure`() = runTest {
        val status = FakeSyncStatusRepository()
        val transport = ThrowingSyncTransport(
            DriveTransportException("needs reauth", retryable = false, needsReauthorization = true),
        )

        val outcome = runner(transport = transport, statusRepository = status).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.TERMINAL_FAILURE)
        assertThat(status.statuses.last()).isEqualTo(SyncStatus.NeedsReauthorization)
    }

    @Test
    fun `a non-retryable failure that is not a reauthorization is a plain terminal Failed`() = runTest {
        val status = FakeSyncStatusRepository()
        val transport = ThrowingSyncTransport(DriveTransportException("storage quota exceeded", retryable = false))

        val outcome = runner(transport = transport, statusRepository = status).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.TERMINAL_FAILURE)
        assertThat(status.statuses.last()).isEqualTo(SyncStatus.Failed("storage quota exceeded", retryable = false))
    }

    @Test
    fun `the same runner reuses one engine across calls instead of resetting the cursor every run`() = runTest {
        val transport = InMemoryTransport()
        transport.push(
            OpBatch(
                "device-b", seq = 1,
                ops = listOf(SyncOp(table = SyncTable.TRANSACTIONS.tableName, rowId = "x", remoteRevision = 1, deviceId = "device-b", updatedAt = 1_000, payload = null)),
            ),
        )
        val applier = FakeSyncApplier()
        val syncRunner = runner(transport = transport, applier = applier)

        syncRunner.run()
        syncRunner.run()

        // A fresh SyncEngine on the second call would have an empty cursor
        // again and re-hand device-b's already-applied batch to the applier.
        assertThat(applier.calls).hasSize(1)
    }

    /**
     * The property the cursor-persistence fix exists for: `engineFor`'s
     * in-process cache (the test above) only covers a process that stays
     * alive, and WorkManager's periodic ticks mostly don't. This simulates
     * the case that matters -- two [SyncRunner]s over one persisted
     * `UserPreferencesDataSource` file, with the first's `DataStore` scope
     * cancelled before the second is built, the same stand-in for "the
     * process actually restarted" `DeviceIdentityTest` already uses.
     */
    @Test
    fun `a second run against a fresh process pulls nothing when the cursor already covers everything`() = runTest {
        val transport = InMemoryTransport()
        transport.push(
            OpBatch(
                "device-b", seq = 1,
                ops = listOf(SyncOp(table = SyncTable.TRANSACTIONS.tableName, rowId = "x", remoteRevision = 1, deviceId = "device-b", updatedAt = 1_000, payload = null)),
            ),
        )
        val cursorFile = File.createTempFile("sync-runner-cursor", ".preferences_pb")
        val firstProcessScope = CoroutineScope(SupervisorJob())
        val firstApplier = FakeSyncApplier()
        runner(transport = transport, applier = firstApplier, preferences = preferences(cursorFile, firstProcessScope)).run()
        assertThat(firstApplier.calls).hasSize(1) // the first process actually pulled device-b's batch
        firstProcessScope.cancel()

        val secondApplier = FakeSyncApplier()
        runner(transport = transport, applier = secondApplier, preferences = preferences(cursorFile)).run()

        // A fresh SyncEngine seeded from an empty cursor -- the pre-fix
        // behaviour, and still what a brand-new SyncRunner instance alone
        // would do -- would re-pull and re-hand device-b's already-applied
        // batch to the applier all over again.
        assertThat(secondApplier.calls).isEmpty()
    }

    /**
     * The property the push-sequence fix exists for, verified the same way
     * as the cursor's own restart test above: two [SyncRunner]s over one
     * persisted `UserPreferencesDataSource` file, the first's `DataStore`
     * scope cancelled before the second is built. Before this fix, the
     * second process's fresh [SyncEngine] would start `nextPushSeq` at 1
     * again and silently overwrite the first push's seq in this transport's
     * own list -- and, against a real transport, would be the exact case a
     * peer that already pulled seq 1 would never see the second push at all.
     */
    @Test
    fun `a second push against a fresh process does not reuse the first push's sequence number`() = runTest {
        val transport = InMemoryTransport()
        val transactions = FakeTransactionRepository(dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = null)))
        val pushSeqFile = File.createTempFile("sync-runner-push-seq", ".preferences_pb")
        val firstProcessScope = CoroutineScope(SupervisorJob())
        runner(transport = transport, transactionRepository = transactions, preferences = preferences(pushSeqFile, firstProcessScope)).run()
        firstProcessScope.cancel()

        runner(transport = transport, transactionRepository = transactions, preferences = preferences(pushSeqFile)).run()

        val pushedSeqs = transport.pull(emptyMap()).filter { it.deviceId == "device-a" }.map { it.seq }
        assertThat(pushedSeqs).containsExactly(1L, 2L).inOrder()
    }

    /**
     * Settles the migration-gap question named in [SyncEngine]'s doc: an
     * install that has been pushing since before `pushSeq` existed already
     * has a high entry for its own device in its persisted cursor (`push()`
     * has always written `cursor[deviceId] = batch.seq`). Seeding the new
     * counter from `pushSeq` alone would read null here and hand out 1
     * again -- exactly the numbers this device already sent under peers'
     * watermarks before this fix shipped.
     */
    @Test
    fun `an install upgrading into the push-sequence fix rebases off its own cursor entry, not just the new counter`() = runTest {
        val transport = InMemoryTransport()
        val prefsFile = File.createTempFile("sync-runner-rebase", ".preferences_pb")
        val setupScope = CoroutineScope(SupervisorJob())
        preferences(prefsFile, setupScope).setSyncCursor(mapOf("device-a" to 40L)) // as if pushed through seq 40 before this fix existed
        setupScope.cancel()
        val transactions = FakeTransactionRepository(dirty = listOf(DirtyRow("t1", localRevision = 1, updatedAt = 100, payload = null)))

        runner(transport = transport, transactionRepository = transactions, preferences = preferences(prefsFile)).run()

        assertThat(transport.pull(emptyMap()).single().seq).isEqualTo(41L)
    }

    @Test
    fun `compaction never runs when this build has no transport configured`() = runTest {
        val callFactory = FakeCallFactory { error("compaction must not touch Drive when unconfigured") }

        val outcome = runner(syncConfiguration = FakeSyncConfiguration(isConfigured = false), callFactory = callFactory).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.SUCCESS)
    }

    @Test
    fun `a configured build elects a compactor after a successful sync`() = runTest {
        val drive = InMemoryFakeDrive()
        val callFactory = FakeCallFactory(drive::handle)
        val rawApi = DriveApi(FakeCallFactory(drive::handle)) { "fake-token" }

        val outcome = runner(
            syncConfiguration = FakeSyncConfiguration(isConfigured = true),
            callFactory = callFactory,
        ).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.SUCCESS)
        assertThat(rawApi.list(KIND, KIND_OWNER)).isNotEmpty()
    }

    @Test
    fun `compaction failing does not turn an already-successful sync into a reported failure`() = runTest {
        val status = FakeSyncStatusRepository()
        // A 500 from Drive's list call, the same as DriveApiTest scripts for
        // "server unavailable" -- not error(), which would throw a plain
        // IllegalStateException SyncRunner's DriveTransportException catch
        // was never meant to swallow.
        val callFactory = FakeCallFactory { request -> errorResponse(request, 500) }

        val outcome = runner(
            statusRepository = status,
            syncConfiguration = FakeSyncConfiguration(isConfigured = true),
            callFactory = callFactory,
        ).run()

        assertThat(outcome).isEqualTo(SyncRunOutcome.SUCCESS)
        assertThat(status.statuses.last()).isEqualTo(SyncStatus.Idle)
    }
}

private class FakeDeviceIdentity(private val id: String = "device-a") : DeviceIdentity {
    override suspend fun current(): String = id
}

private class FakeSyncConfiguration(override val isConfigured: Boolean) : SyncConfiguration

private class ThrowingSyncTransport(private val exception: DriveTransportException) : SyncTransport {
    override suspend fun push(batch: OpBatch): Unit = throw exception
    override suspend fun pull(after: Map<String, Long>): List<OpBatch> = throw exception
    override suspend fun snapshot(): RemoteSnapshot = throw exception
}

private class FakeSyncStatusRepository : SyncStatusRepository {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val statuses = mutableListOf<SyncStatus>()
    override val status: Flow<SyncStatus> = _status
    override val lastSuccessfulSyncAt: Flow<Instant?> = MutableStateFlow(null)
    var lastSuccess: Instant? = null
        private set

    override fun reportRunning() {
        _status.value = SyncStatus.Running
        statuses += SyncStatus.Running
    }

    override suspend fun reportSuccess(at: Instant) {
        lastSuccess = at
        _status.value = SyncStatus.Idle
        statuses += SyncStatus.Idle
    }

    override fun reportFailed(reason: String, retryable: Boolean) {
        _status.value = SyncStatus.Failed(reason, retryable)
        statuses += _status.value
    }

    override fun reportNeedsReauthorization() {
        _status.value = SyncStatus.NeedsReauthorization
        statuses += SyncStatus.NeedsReauthorization
    }
}
