package dev.charanjeev.bahi.core.sync.convergence

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.sync.InMemoryTransport

/**
 * Two [SyncTestDevice]s over one [InMemoryTransport] -- docs/sync-design.md
 * §10.1's "two devices, one process". Every scripted scenario and the
 * property test build one of these, diverge the two devices' local state
 * (`a.transactionRepository.upsert(...)`, etc. -- there is deliberately no
 * `offline { }` wrapper: nothing here ever calls the network on its own, so
 * every device is "offline" until a test calls [syncToQuiescence]), then
 * assert convergence.
 */
class TwoDeviceHarness(context: Context = InstrumentationRegistry.getInstrumentation().targetContext) {

    val transport = InMemoryTransport()
    val a = SyncTestDevice(context, transport, deviceId = "device-a")
    val b = SyncTestDevice(context, transport, deviceId = "device-b")

    private suspend fun SyncTestDevice.totalDirtyCount(): Int =
        transactionRepository.dirtyRows().size +
            categoryRepository.dirtyRows().size +
            budgetRepository.dirtyRows().size +
            categoryRuleRepository.dirtyRows().size

    /**
     * Alternates `sync()` on both devices for [rounds] full rounds, then
     * asserts neither has anything left to push.
     *
     * Not a dirty-count early-exit -- an earlier version of this harness
     * tried that and it was wrong in a way worth recording: [SyncEngine.sync]
     * pulls *and* pushes in one call, so a device that just applied an
     * incoming op and immediately bounced the merge outcome back (§5.2's
     * fourth row) reports zero dirty rows by the end of its own `sync()` --
     * having pushed the correction is not the same as the other device
     * having *pulled* it yet. Checking "both zero" right after a round can
     * therefore stop one round too early, with the peer's copy of the row
     * still holding the pre-merge value. `disjointFieldEdits_...` caught
     * this: the two databases matched (so `assertConverged` alone would not
     * have caught it either) but had both silently kept the *pre-merge*
     * value.
     *
     * A genuine conflict needs at most two round trips regardless of how
     * many rows are involved -- push, apply-and-bounce-back, apply the
     * bounce-back -- because every dirty row rides in the same batch. So a
     * small fixed [rounds] is enough for any scenario this suite constructs,
     * and the trailing assertion is what still catches a row that genuinely
     * never settles (the missing-shadow-on-push-ack bug found while building
     * this harness left a row dirty forever; this is the check that would
     * have failed loudly on it instead of timing out via an early-exit
     * heuristic).
     */
    suspend fun syncToQuiescence(rounds: Int = 6) {
        repeat(rounds) {
            a.sync()
            b.sync()
        }
        val aDirty = a.totalDirtyCount()
        val bDirty = b.totalDirtyCount()
        check(aDirty == 0 && bDirty == 0) {
            "device-a and device-b did not reach quiescence within $rounds rounds (a dirty=$aDirty, b dirty=$bDirty)"
        }
    }

    /**
     * The property test's assertion (§10.3) and every scripted scenario's
     * final check: both devices' [dump] must be `equals()`, not merely
     * "look similar". A mismatch's assertion message is a full field-by-field
     * diff of two data classes, which is what makes a failing seed
     * (§10.3) or a failing scenario actionable without attaching a debugger.
     */
    suspend fun assertConverged() {
        assertThat(a.dump()).isEqualTo(b.dump())
    }

    fun close() {
        a.close()
        b.close()
    }
}
