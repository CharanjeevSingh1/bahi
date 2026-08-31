package dev.charanjeev.bahi.core.sync.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.TransactionSource
import dev.charanjeev.bahi.core.model.YearMonth
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two real [dev.charanjeev.bahi.core.sync.SyncEngine]s, two real Room
 * databases, one [dev.charanjeev.bahi.core.sync.InMemoryTransport] --
 * docs/sync-design.md §10.1/§10.2, slice 6. One test per row of the conflict
 * matrix (§10.2's numbered list; test names below cite the number).
 */
@RunWith(AndroidJUnit4::class)
class ConvergenceScenariosTest {

    // --- 3: fast-forward ---

    @Test
    fun aEditsBUnchanged_fastForward_noConflictRecorded() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", amount = -1_000, at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(amount = Money(-2_000), updatedAt = a.clock.now()))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions.single().amountMinor).isEqualTo(-2_000)
        assertThat(a.unacknowledgedConflicts()).isEmpty()
        assertThat(b.unacknowledgedConflicts()).isEmpty()
    }

    // --- 1: disjoint fields ---

    @Test
    fun disjointFieldEdits_bothSurvive_noConflictRecorded() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", amount = -1_000, notes = null, at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(amount = Money(-2_000), updatedAt = a.clock.now()))

        b.clock.advanceBy(2_000)
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(notes = "from the tablet", updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        val merged = a.dump().transactions.single()
        assertThat(merged.amountMinor).isEqualTo(-2_000)
        assertThat(merged.notes).isEqualTo("from the tablet")
        assertThat(a.unacknowledgedConflicts()).isEmpty()
        assertThat(b.unacknowledgedConflicts()).isEmpty()
    }

    // --- 2: both edit the same field ---

    @Test
    fun bothEditAmount_deterministicWinner_oneConflictRecordedPerDevice() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", amount = -1_000, at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(amount = Money(-2_000), updatedAt = a.clock.now()))

        b.clock.advanceBy(5_000) // strictly later -- B's edit is the deterministic winner
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(amount = Money(-3_000), updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions.single().amountMinor).isEqualTo(-3_000)

        // Recorded on whichever device's own edit was still un-pushed at the
        // moment it applied the other's -- see recordPushed's doc: a device
        // treats its own just-pushed value as its new base immediately, so
        // it fast-forwards rather than re-discovering a conflict the *next*
        // time it hears from the peer. Exactly one record should exist
        // somewhere, not zero and not two.
        val allConflicts = a.unacknowledgedConflicts() + b.unacknowledgedConflicts()
        assertThat(allConflicts).hasSize(1)
        assertThat(allConflicts.single().field).isEqualTo("amount_minor")
        assertThat(allConflicts.single().discardedValue).contains("-2000")
    }

    // --- 4: delete vs. concurrent edit ---

    @Test
    fun aDeletesBEditsConcurrently_editWins_rowLives() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", amount = -1_000, at = a.clock.now()))
        syncToQuiescence()

        a.transactionRepository.delete("t1")

        b.clock.advanceBy(1_000)
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(amount = Money(-4_000), updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        val row = a.dump().transactions.single()
        assertThat(row.id).isEqualTo("t1")
        assertThat(row.amountMinor).isEqualTo(-4_000)
    }

    // --- 5: edit synced, then a causally-later delete ---

    @Test
    fun aEditsAndSyncs_thenBDeletesAfterPulling_deleteWins() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", amount = -1_000, at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(amount = Money(-2_000), updatedAt = a.clock.now()))
        syncToQuiescence() // B pulls A's edit before deleting -- the delete is causally after it, not concurrent with it.

        b.transactionRepository.delete("t1")

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions).isEmpty()
    }

    // --- 6: a hand lock beats a rule guess that hasn't seen it yet ---

    @Test
    fun handLockBeatsConcurrentRuleGuess() = convergenceTest {
        a.categoryRepository.upsert(category("cat-food", "Food"))
        a.categoryRepository.upsert(category("cat-other", "Other"))
        a.transactionRepository.upsert(tx("t1", description = "BLUE TOKAI", at = a.clock.now()))
        syncToQuiescence()

        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(
            onA.copy(categoryId = "cat-food", categoryLockedByUser = true, updatedAt = a.clock.now()),
        )
        // B's rule engine runs before B has pulled A's lock -- exactly the
        // motivating case for §5.4: all three of B's own guards correctly
        // permit this, since B's copy of t1 is still unlocked.
        b.transactionRepository.applyRuleCategories(mapOf("t1" to "cat-other"))

        syncToQuiescence()
        assertConverged()
        val row = a.dump().transactions.single()
        assertThat(row.categoryId).isEqualTo("cat-food")
        assertThat(row.categoryLockedByUser).isTrue()
    }

    // --- 7: both lock, different categories ---

    @Test
    fun bothLockDifferentCategories_tiebreakRecorded() = convergenceTest {
        a.categoryRepository.upsert(category("cat-a"))
        a.categoryRepository.upsert(category("cat-b"))
        a.transactionRepository.upsert(tx("t1", at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(categoryId = "cat-a", categoryLockedByUser = true, updatedAt = a.clock.now()))

        b.clock.advanceBy(5_000)
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(categoryId = "cat-b", categoryLockedByUser = true, updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions.single().categoryId).isEqualTo("cat-b")

        val conflicts = a.unacknowledgedConflicts() + b.unacknowledgedConflicts()
        assertThat(conflicts).hasSize(1)
        assertThat(conflicts.single().field).isEqualTo("category_id")
        assertThat(conflicts.single().reason).contains("locked")
    }

    // --- 8: independently-created budget for the same category/month ---

    @Test
    fun bothCreateSameBudget_oneRowNotTwo() = convergenceTest {
        a.categoryRepository.upsert(category("cat-food"))
        syncToQuiescence()

        val month = YearMonth.of(2026, 8)
        a.budgetRepository.upsert(budget("ignored-a", "cat-food", month, limitMinor = 500_000))
        b.budgetRepository.upsert(budget("ignored-b", "cat-food", month, limitMinor = 700_000))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().budgets).hasSize(1)
        assertThat(a.dump().budgets.single().id).isEqualTo("budget:cat-food:2026-08")
    }

    // --- 9: the same statement imported on both devices ---

    @Test
    fun sameStatementImportedOnBothDevices_notDoubled() = convergenceTest {
        val rows = listOf(
            tx("x", amount = -100, description = "COFFEE", source = TransactionSource.CSV_IMPORT, at = a.clock.now()),
            tx("x", amount = -200, description = "LUNCH", source = TransactionSource.CSV_IMPORT, at = a.clock.now()),
            tx("x", amount = -300, description = "TAXI", source = TransactionSource.CSV_IMPORT, at = a.clock.now()),
        )
        a.transactionRepository.importAll(rows)
        b.transactionRepository.importAll(rows)

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions).hasSize(3)
    }

    // --- 10: that, plus a genuine duplicate on one side ---

    @Test
    fun sameStatementPlusAGenuineDuplicateOnOneSide_duplicateSurvives() = convergenceTest {
        val coffee = tx("x", amount = -100, description = "COFFEE", source = TransactionSource.CSV_IMPORT, at = a.clock.now())
        val lunch = tx("x", amount = -200, description = "LUNCH", source = TransactionSource.CSV_IMPORT, at = a.clock.now())
        val taxi = tx("x", amount = -300, description = "TAXI", source = TransactionSource.CSV_IMPORT, at = a.clock.now())

        a.transactionRepository.importAll(listOf(coffee, lunch, taxi))
        // B genuinely bought two coffees -- two identical-tuple rows in the
        // same import, exactly the case csv-import-design §4's count-aware
        // quota exists for.
        b.transactionRepository.importAll(listOf(coffee, lunch, taxi, coffee))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions).hasSize(4)
    }

    // --- 11: import syncs, a hand edit survives its own batch's undo ---

    @Test
    fun editedRowsSurviveTheirOwnBatchUndoAcrossDevices() = convergenceTest {
        val rows = (0 until 5).map { i ->
            tx("x", amount = -(i + 1) * 100L, description = "ROW$i", source = TransactionSource.CSV_IMPORT, at = a.clock.now())
        }
        val batch = a.transactionRepository.importAll(rows)
        assertThat(batch.insertedCount).isEqualTo(5)
        syncToQuiescence()

        val edited = batch.insertedIds.take(2)
        for (id in edited) {
            val onB = b.currentTransaction(id)
            b.transactionRepository.update(onB.copy(merchant = "edited on tablet", updatedAt = b.clock.now()))
        }
        // A has to learn the edits cleared those rows' import_batch_id
        // *before* it undoes the batch locally, or its own copies still
        // carry the id and get swept up in the undo too (§6.1).
        syncToQuiescence()

        val undone = a.transactionRepository.undoImport(batch.batchId)
        assertThat(undone).isEqualTo(3)

        syncToQuiescence()
        assertConverged()
        val remaining = a.dump().transactions
        assertThat(remaining.map { it.id }).containsExactlyElementsIn(edited)
    }

    // --- 12: notes, the substring branch ---

    @Test
    fun notesOneContainsTheOther_takesTheLonger() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", notes = "Coffee run", at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(notes = "Coffee run with Sam", updatedAt = a.clock.now()))

        b.clock.advanceBy(2_000)
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(notes = "Coffee run with Sam and Jess", updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().transactions.single().notes).isEqualTo("Coffee run with Sam and Jess")
        // A substring match is resolved without consulting the tiebreak-and-record path.
        assertThat(a.unacknowledgedConflicts()).isEmpty()
    }

    // --- 12: notes, the genuinely-divergent branch ---

    @Test
    fun notesBothDiverge_keepsBothAndRecordsConflict() = convergenceTest {
        a.transactionRepository.upsert(tx("t1", notes = "shared lunch", at = a.clock.now()))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        val onA = a.currentTransaction("t1")
        a.transactionRepository.update(onA.copy(notes = "paid by me", updatedAt = a.clock.now()))

        b.clock.advanceBy(2_000)
        val onB = b.currentTransaction("t1")
        b.transactionRepository.update(onB.copy(notes = "actually paid by Sam", updatedAt = b.clock.now()))

        syncToQuiescence()
        assertConverged()
        val notes = a.dump().transactions.single().notes!!
        assertThat(notes).contains("paid by me")
        assertThat(notes).contains("actually paid by Sam")

        val conflicts = a.unacknowledgedConflicts() + b.unacknowledgedConflicts()
        assertThat(conflicts).hasSize(1)
        assertThat(conflicts.single().field).isEqualTo("notes")
    }

    // --- 13: category delete, transactions survive, restore brings it back ---

    @Test
    fun categoryDeleteLeavesTransactionsLive_restoreBringsCategoryBack() = convergenceTest {
        a.categoryRepository.upsert(category("cat-food", "Food"))
        a.transactionRepository.upsert(tx("t1", categoryId = "cat-food", at = a.clock.now()))
        syncToQuiescence()

        a.categoryRepository.delete("cat-food")
        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().categories).isEmpty()
        // No fan-out write: the transaction keeps pointing at the tombstoned
        // category rather than being nulled out (§1.2).
        assertThat(a.dump().transactions.single().categoryId).isEqualTo("cat-food")

        a.categoryRepository.upsert(category("cat-food", "Food"))
        syncToQuiescence()
        assertConverged()
        assertThat(a.dump().categories.single().id).isEqualTo("cat-food")
    }

    // --- 14: concurrent rule reordering converges on *a* total order ---

    @Test
    fun concurrentRuleReordering_convergesOnOneOrder() = convergenceTest {
        a.categoryRepository.upsert(category("cat-food"))
        a.categoryRuleRepository.upsert(rule("r1", "cat-food", "COFFEE", priority = 0))
        a.categoryRuleRepository.upsert(rule("r2", "cat-food", "LUNCH", priority = 1))
        syncToQuiescence()

        a.clock.advanceBy(1_000)
        a.categoryRuleRepository.reorder(listOf("r2", "r1"))
        b.clock.advanceBy(5_000)
        b.categoryRuleRepository.reorder(listOf("r1", "r2"))

        syncToQuiescence()
        assertConverged()
        // Garbling is accepted (D10, §6.5); agreement is not optional.
        assertThat(a.dump().categoryRules.map { it.id }).isEqualTo(b.dump().categoryRules.map { it.id })
    }
}
