package dev.charanjeev.bahi.core.sync.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.YearMonth
import dev.charanjeev.bahi.core.model.budgetIdFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * docs/sync-design.md §10.3: scripted scenarios test the cases someone
 * thought of; the interleaving that breaks a merge engine usually is not one
 * of them. A generator produces random operation sequences over a small
 * alphabet, applies them to two devices with single (not to-quiescence) sync
 * points at random positions, syncs to quiescence at the end, and asserts
 * `dump(A) == dump(B)`.
 *
 * **The alphabet here is a subset of §10.3's**, and that gap is itself the
 * thing §10.3 warns about ("the property test's alphabet is a convention,"
 * §11): `import a batch` and `create` are missing (covered instead by the
 * scripted duplicate-import scenarios, which need CSV-specific setup the
 * random generator has no use for) and every op targets a fixed, pre-seeded
 * pool of ids rather than minting new ones. Extending the alphabet -- most
 * importantly to cover row creation racing an import, which this pool-based
 * design cannot exercise at all -- is exactly the kind of thing that has to
 * be remembered rather than enforced, per that same section.
 *
 * Run count, stated per CLAUDE.md's rule on this after
 * `BudgetTotalsTransientTest`: [DEFAULT_SEED_COUNT] fixed seeds on every push
 * (`.github/workflows/ci.yml`'s `connectedDebugAndroidTest`), and a larger
 * corpus on a nightly schedule (`.github/workflows/nightly.yml`) that passes
 * `-PseedCount=1000` -- `core/sync/build.gradle.kts` wires that Gradle
 * property into `testInstrumentationRunnerArguments["seedCount"]`, and
 * [configuredSeedCount] reads it back at run time, so the same test class
 * (and the same APK) runs either size depending on how it was launched.
 * Slice 7's own split: slice 6 built this test at a single fixed count with
 * this split named as the piece still owed. Each seed is deterministic and
 * reproducible regardless of count: a failure names the seed, and re-running
 * [runSeed] with just that number reproduces it with no other state.
 *
 * The whole loop runs inside one `runTest`, so it is one coroutine subject to
 * `runTest`'s own watchdog -- not a per-seed budget. The 10-minute `timeout`
 * on [seeds_convergeToTheSameDatabase] was set by measuring, not guessing:
 * 1,000 seeds took ~132s of actual test-body wall-clock time (the JUnit
 * report's `time=` attribute, not the surrounding Gradle/install overhead)
 * on a local emulator, so 10 minutes is ~4.5x headroom against CI hardware
 * being slower. This is a real-device measurement, not virtual time --
 * [TwoDeviceHarness] builds real Room databases per seed, so nothing here is
 * skippable the way `delay()` is under `runTest`. **The trap:** raising
 * [DEFAULT_SEED_COUNT] or the nightly `-PseedCount` further does not just
 * mean "more seeds" -- it also means re-measuring throughput and moving this
 * timeout, or a genuine hang and 1,000 seeds simply running past the budget
 * become indistinguishable failures.
 */
@RunWith(AndroidJUnit4::class)
class ConvergencePropertyTest {

    @Test
    fun seeds_convergeToTheSameDatabase() = runTest(timeout = 10.minutes) {
        val seedCount = configuredSeedCount()
        val failures = mutableListOf<String>()
        for (seed in 1..seedCount) {
            try {
                runSeed(seed)
            } catch (e: CancellationException) {
                // Not a per-seed failure: runTest's own watchdog cancelling this body on
                // timeout also surfaces as a CancellationException here. Swallowing it
                // let one real infrastructure failure (the loop ran out of time) replay
                // as a fabricated failure for every seed from here to seedCount, burying
                // whatever seed actually mattered under noise. Let it propagate so a
                // timeout fails loudly, once, as itself.
                throw e
            } catch (e: Throwable) {
                failures += "seed $seed: ${e.message}"
            }
        }
        assertWithMessage(
            "Convergence failed for ${failures.size}/$seedCount seeds. Reproduce with " +
                "ConvergencePropertyTest.runSeed(<seed>) directly.\n" +
                failures.joinToString("\n"),
        ).that(failures).isEmpty()
    }

    companion object {
        const val DEFAULT_SEED_COUNT = 50

        private fun configuredSeedCount(): Int =
            InstrumentationRegistry.getArguments().getString("seedCount")?.toIntOrNull() ?: DEFAULT_SEED_COUNT
    }
}

private const val CAT_A = "cat-a"
private const val CAT_B = "cat-b"
private val TX_IDS = listOf("t1", "t2", "t3", "t4")
private val BUDGET_MONTH = YearMonth.of(2026, 1)
private val RULE_IDS = listOf("r1", "r2")

private enum class Device { A, B }

private sealed interface ScenarioOp {
    val device: Device

    data class EditAmount(override val device: Device, val txId: String, val amountMinor: Long) : ScenarioOp
    data class EditNotes(override val device: Device, val txId: String, val notes: String) : ScenarioOp
    data class EditMerchant(override val device: Device, val txId: String, val merchant: String) : ScenarioOp
    data class DeleteTx(override val device: Device, val txId: String) : ScenarioOp
    data class UndeleteTx(override val device: Device, val txId: String) : ScenarioOp
    data class LockCategory(override val device: Device, val txId: String, val categoryId: String) : ScenarioOp
    data class RuleGuess(override val device: Device, val txId: String, val categoryId: String) : ScenarioOp
    data class EditBudgetLimit(override val device: Device, val categoryId: String, val limitMinor: Long) : ScenarioOp
    data class DeleteBudget(override val device: Device, val categoryId: String) : ScenarioOp
    data class ReorderRules(override val device: Device, val order: List<String>) : ScenarioOp
    data class SyncOnce(override val device: Device) : ScenarioOp
}

private fun generate(random: Random, length: Int): List<ScenarioOp> = List(length) {
    val device = if (random.nextBoolean()) Device.A else Device.B
    when (random.nextInt(11)) {
        0 -> ScenarioOp.EditAmount(device, TX_IDS.random(random), random.nextLong(-10_000, -1))
        1 -> ScenarioOp.EditNotes(device, TX_IDS.random(random), "note-${random.nextInt(1000)}")
        2 -> ScenarioOp.EditMerchant(device, TX_IDS.random(random), "merchant-${random.nextInt(1000)}")
        3 -> ScenarioOp.DeleteTx(device, TX_IDS.random(random))
        4 -> ScenarioOp.UndeleteTx(device, TX_IDS.random(random))
        5 -> ScenarioOp.LockCategory(device, TX_IDS.random(random), if (random.nextBoolean()) CAT_A else CAT_B)
        6 -> ScenarioOp.RuleGuess(device, TX_IDS.random(random), if (random.nextBoolean()) CAT_A else CAT_B)
        7 -> ScenarioOp.EditBudgetLimit(device, CAT_A, random.nextLong(1_000, 100_000))
        8 -> ScenarioOp.DeleteBudget(device, CAT_A)
        9 -> ScenarioOp.ReorderRules(device, if (random.nextBoolean()) RULE_IDS else RULE_IDS.reversed())
        else -> ScenarioOp.SyncOnce(device)
    }
}

private suspend fun TwoDeviceHarness.seedBaseline() {
    a.categoryRepository.upsert(category(CAT_A, "Food"))
    a.categoryRepository.upsert(category(CAT_B, "Transport"))
    for (id in TX_IDS) {
        a.transactionRepository.upsert(tx(id, amount = -500, description = "baseline $id", at = a.clock.now()))
    }
    a.budgetRepository.upsert(budget("ignored", CAT_A, BUDGET_MONTH, limitMinor = 50_000))
    a.categoryRuleRepository.upsert(rule(RULE_IDS[0], CAT_A, "COFFEE", priority = 0))
    a.categoryRuleRepository.upsert(rule(RULE_IDS[1], CAT_A, "LUNCH", priority = 1))
    syncToQuiescence()
}

private suspend fun TwoDeviceHarness.execute(op: ScenarioOp) {
    val device = if (op.device == Device.A) a else b
    device.clock.advanceBy(1)
    when (op) {
        is ScenarioOp.EditAmount -> {
            val current = device.transactionRepository.observeTransaction(op.txId).first() ?: return
            device.transactionRepository.update(current.copy(amount = Money(op.amountMinor), updatedAt = device.clock.now()))
        }
        is ScenarioOp.EditNotes -> {
            val current = device.transactionRepository.observeTransaction(op.txId).first() ?: return
            device.transactionRepository.update(current.copy(notes = op.notes, updatedAt = device.clock.now()))
        }
        is ScenarioOp.EditMerchant -> {
            val current = device.transactionRepository.observeTransaction(op.txId).first() ?: return
            device.transactionRepository.update(current.copy(merchant = op.merchant, updatedAt = device.clock.now()))
        }
        is ScenarioOp.DeleteTx -> device.transactionRepository.delete(op.txId)
        is ScenarioOp.UndeleteTx -> device.transactionRepository.undoDelete(op.txId)
        is ScenarioOp.LockCategory -> {
            val current = device.transactionRepository.observeTransaction(op.txId).first() ?: return
            device.transactionRepository.update(
                current.copy(categoryId = op.categoryId, categoryLockedByUser = true, updatedAt = device.clock.now()),
            )
        }
        is ScenarioOp.RuleGuess -> device.transactionRepository.applyRuleCategories(mapOf(op.txId to op.categoryId))
        is ScenarioOp.EditBudgetLimit ->
            device.budgetRepository.upsert(budget("ignored", op.categoryId, BUDGET_MONTH, op.limitMinor))
        is ScenarioOp.DeleteBudget -> device.budgetRepository.delete(budgetIdFor(op.categoryId, BUDGET_MONTH))
        is ScenarioOp.ReorderRules -> device.categoryRuleRepository.reorder(op.order)
        is ScenarioOp.SyncOnce -> device.sync()
    }
}

/** Reproduces one seed in isolation -- what a failing [SEED_COUNT] run points at. */
suspend fun runSeed(seed: Int, opsPerSeed: Int = 40) {
    val harness = TwoDeviceHarness()
    try {
        harness.seedBaseline()
        val ops = generate(Random(seed), opsPerSeed)
        for (op in ops) harness.execute(op)
        harness.syncToQuiescence()
        harness.assertConverged()
    } finally {
        harness.close()
    }
}
