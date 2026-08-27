package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Query("SELECT * FROM transactions WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    /**
     * categoryCount and hasDateWindow turn an absent filter into a no-op
     * condition instead of Room needing a separate query per combination --
     * an empty :categoryIds would otherwise make `IN ()` reject every row.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND (:categoryCount = 0 OR category_id IN (:categoryIds))
          AND (:hasDateWindow = 0 OR date BETWEEN :from AND :to)
        ORDER BY date DESC, created_at DESC
        """,
    )
    fun observeFiltered(
        categoryIds: List<String>,
        categoryCount: Int,
        hasDateWindow: Int,
        from: String,
        to: String,
    ): Flow<List<TransactionEntity>>

    /**
     * A count per hash, not just presence: two genuinely identical
     * transactions -- same coffee shop, same amount, twice -- hash the
     * same, and presence alone can't tell "this exact one was already
     * imported" from "one of these was, the other wasn't." See
     * [importBatch] for how the count is used.
     */
    @Query(
        """
        SELECT content_hash, COUNT(*) AS existing_count FROM transactions
        WHERE content_hash IN (:hashes)
        GROUP BY content_hash
        """,
    )
    suspend fun countExistingHashes(
        hashes: List<String>,
    ): Map<@MapColumn(columnName = "content_hash") String, @MapColumn(columnName = "existing_count") Int>

    /**
     * Expense spending in a date window that is filed under no category at
     * all, as a positive total -- the "Uncategorised: ₹X this month, not
     * counted toward any budget" line (docs/budgets-design.md §2.2).
     *
     * `category_id IS NULL`, never `category_id = 'uncategorised'`: nothing
     * in the app writes the system category's id onto a transaction (CSV
     * import leaves the column null), so that sentinel row exists for the
     * category picker, not for the data. Matching on it would report ₹0
     * forever.
     *
     * Lives here rather than on BudgetDao even though only the budgets screen
     * asks for it, because the table it reads is the one this DAO owns.
     * `observeBudgetsWithSpend` joins `transactions` but *selects* budget
     * rows, which is why it belongs the other way round.
     *
     * COALESCE for the same reason as there: no uncategorised spending has to
     * arrive as 0, not as a null the caller has to remember to handle.
     *
     * **A transaction whose category was deleted counts here too, and the
     * second condition is not optional.** Since v4 a deleted category is a
     * tombstone rather than a missing row, so `ON DELETE SET_NULL` no longer
     * fires and the transaction keeps pointing at it
     * (`CategoryDao.softDeleteUserCategory`). Its budget was tombstoned by the
     * same cascade, so without this the spend would be in no budget *and* not
     * uncategorised -- money that is on the ledger and on no line of the
     * budgets screen. Reading `categories` also puts that table in the flow's
     * invalidation set, which is what makes the uncategorised line update the
     * moment a category is deleted rather than on the next unrelated write.
     */
    @Query(
        """
        SELECT COALESCE(SUM(-amount_minor), 0) FROM transactions
        WHERE (
                category_id IS NULL
                OR category_id NOT IN (SELECT id FROM categories WHERE deleted_at IS NULL)
              )
          AND amount_minor < 0
          AND deleted_at IS NULL
          AND date BETWEEN :from AND :to
        """,
    )
    fun observeUncategorisedSpend(from: String, to: String): Flow<Long>

    /**
     * Layer 1 of the lock guard (docs/budgets-design.md §1.4): the candidate
     * set for a rule run, with `category_locked_by_user = 0` as a *query
     * condition* rather than a filter the caller applies afterwards. A
     * transaction the user categorised by hand never becomes a candidate in
     * the first place, so forgetting to filter downstream isn't a way to
     * reach one.
     *
     * There is deliberately no parameter that can switch the lock condition
     * off. [lockedRuleMatchCandidates] returns those rows instead, and it
     * can't feed a write.
     *
     * [uncategorisedOnly] selects between the two triggers §1.3 allows:
     * 1 for "recategorise uncategorised transactions", which only ever fills
     * in a blank, and 0 for applying one rule to existing transactions,
     * which has to be able to move an already-categorised row -- that is the
     * entire point of editing a rule (§1.6). Written as a flag rather than
     * two queries for the same reason [observeFiltered] does it: one query
     * whose conditions are visible together, not two that can drift apart.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND category_locked_by_user = 0
          AND (:uncategorisedOnly = 0 OR category_id IS NULL)
        ORDER BY date DESC, created_at DESC
        """,
    )
    suspend fun ruleCandidates(uncategorisedOnly: Int): List<TransactionEntity>

    /**
     * The rows a rule run must refuse to touch, scoped exactly like
     * [ruleCandidates] so the two partition the same population.
     *
     * **Only ever counted, never written.** countLockedMatches turns these
     * into an integer for the preview's "3 locked transactions will be
     * skipped" line and returns no assignments, so there is no route from
     * this query to applyRuleCategory. Telling the user why a number is
     * smaller than they expected is worth a query; it is not worth a second
     * write path.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted_at IS NULL
          AND category_locked_by_user = 1
          AND (:uncategorisedOnly = 0 OR category_id IS NULL)
        """,
    )
    suspend fun lockedRuleMatchCandidates(uncategorisedOnly: Int): List<TransactionEntity>

    @Upsert
    suspend fun upsert(transaction: TransactionEntity)

    /**
     * Distinct from [upsert]: a user edit always bumps local_revision and
     * marks the row pending sync, the same way softDelete/undoSoftDelete do
     * it in one atomic UPDATE rather than a read-then-write. Plain upsert
     * stays as-is for creation, seeding and CSV import, which don't have an
     * existing revision to bump.
     *
     * Also clears import_batch_id: once a user has hand-edited a row, a
     * later "undo this import" is no longer allowed to delete it. That's the
     * whole rule for what an edited row does on batch undo -- expressed here
     * as "it leaves the batch," so [softDeleteBatch]'s query doesn't need to
     * know anything about edits at all.
     */
    @Query(
        """
        UPDATE transactions
        SET amount_minor = :amountMinor, currency_code = :currencyCode, date = :date,
            description = :description, merchant = :merchant, category_id = :categoryId,
            account_id = :accountId, notes = :notes, category_locked_by_user = :categoryLockedByUser,
            content_hash = :contentHash, updated_at = :updatedAt,
            pending_operation = 'UPSERT', local_revision = local_revision + 1, import_batch_id = NULL
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: String,
        amountMinor: Long,
        currencyCode: String,
        date: String,
        description: String,
        merchant: String?,
        categoryId: String?,
        accountId: String,
        notes: String?,
        categoryLockedByUser: Boolean,
        contentHash: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(transactions: List<TransactionEntity>): List<Long>

    /** Soft delete: sync needs the tombstone so other devices learn about it. */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Reverses [softDelete]. Sets pending_operation back to UPSERT rather than
     * clearing it -- NULL means "in sync with remote", which is false the
     * moment the DELETE this undoes has already been pushed: the remote would
     * keep the deletion and the row would vanish again on the next sync.
     * UPSERT re-asserts the row so sync pushes it back.
     */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = NULL, pending_operation = 'UPSERT', local_revision = local_revision + 1
        WHERE id = :id
        """,
    )
    suspend fun undoSoftDelete(id: String)

    /**
     * Batch undo, not a hard delete: reuses the same tombstone machinery as
     * [softDelete] so sync learns about it exactly like any other deletion.
     * `import_batch_id = :batchId` naturally excludes two kinds of row
     * without any extra condition: rows from a different import (different
     * id), and rows this one's [update] has since hand-edited (id cleared to
     * NULL there, and NULL never equals a batchId in SQL). `deleted_at IS
     * NULL` skips rows already removed, matching [softDelete]'s own guard.
     *
     * Returns the number of rows actually tombstoned -- Room fills this in
     * automatically for an UPDATE @Query. That count can be less than the
     * batch's original size (a hand-edited row left it, per the doc above),
     * and the caller needs to report that honestly rather than assuming
     * "undo ran" means "undo removed everything."
     */
    @Query(
        """
        UPDATE transactions
        SET deleted_at = :deletedAt, pending_operation = 'DELETE', local_revision = local_revision + 1
        WHERE import_batch_id = :batchId AND deleted_at IS NULL
        """,
    )
    suspend fun softDeleteBatch(batchId: String, deletedAt: Long): Int

    /**
     * The only write path allowed to set a category without the user having
     * chosen it -- the auto-categoriser today, and anything that behaves like
     * one later. Never [update] or [upsert] for that: those are user intent.
     *
     * `category_locked_by_user = 0` lives in the WHERE clause rather than in
     * the caller because that is what makes the guarantee independent of the
     * caller being correct. A caller is *also* expected to have selected
     * candidates with the same condition (docs/budgets-design.md §1.4, layer
     * 1), and applyRules filters it again -- but if both of those regress,
     * this UPDATE still matches zero rows and the user's own categorisation
     * survives. A rule silently reverting a category the user set by hand is
     * the failure this whole feature is built around not doing.
     *
     * Deliberately does not clear import_batch_id, unlike [update]: a rule
     * categorising a row is not the user hand-editing it, so the row stays
     * part of its import batch and batch undo still reaches it. It also
     * leaves content_hash alone, which is correct because the hash already
     * excludes category by design (see contentHashOf).
     *
     * Returns rows actually updated -- 0 when the row is locked, already
     * deleted, or absent. Callers report that number, not how many they
     * asked for, the same way [softDeleteBatch] does.
     */
    @Query(
        """
        UPDATE transactions
        SET category_id = :categoryId, updated_at = :updatedAt,
            pending_operation = 'UPSERT', local_revision = local_revision + 1
        WHERE id = :id AND category_locked_by_user = 0 AND deleted_at IS NULL
        """,
    )
    suspend fun applyRuleCategory(id: String, categoryId: String, updatedAt: Long): Int

    /**
     * One transaction for the whole pass, matching [importBatch]'s reasoning:
     * a recategorisation over hundreds of rows that half-applied on process
     * death would leave the user's ledger in a state no screen explains.
     *
     * The returned count can be lower than `assignments.size` -- every skip
     * in [applyRuleCategory] is a row that was locked, deleted or gone.
     */
    @Transaction
    suspend fun applyRuleCategories(assignments: Map<String, String>, updatedAt: Long): Int =
        assignments.entries.sumOf { (id, categoryId) -> applyRuleCategory(id, categoryId, updatedAt) }

    @Query("SELECT * FROM transactions WHERE pending_operation IS NOT NULL LIMIT :limit")
    suspend fun pendingChanges(limit: Int = 200): List<TransactionEntity>

    @Query("UPDATE transactions SET pending_operation = NULL, remote_revision = :remoteRevision WHERE id = :id")
    suspend fun markSynced(id: String, remoteRevision: Long)

    /**
     * Runs de-duplication and insert inside one transaction so a large import
     * can't half-apply if the process dies midway.
     *
     * De-duplication is count-aware, not presence-aware (docs/csv-import-
     * design.md §4): each incoming row consumes one unit of "already exists"
     * quota for its hash before any row with that hash is treated as fresh,
     * so two identical-tuple rows re-imported alongside a genuinely new
     * third one are recognised as two duplicates and one addition, not
     * three duplicates or three additions. This is only correct if same-
     * tuple rows keep a stable relative order across re-exports of an
     * overlapping statement period -- every bank export encountered so far
     * does (chronological, ties broken by an internal sequence number), but
     * nothing enforces it. If a re-export ever reordered same-tuple rows
     * relative to an earlier import, this can drop the wrong one and
     * re-insert a duplicate of the wrong one instead -- the total row count
     * would still look right, which is what would make it easy to miss.
     */
    @Transaction
    suspend fun importBatch(transactions: List<TransactionEntity>): List<String> {
        val remainingExisting = countExistingHashes(transactions.map { it.contentHash }).toMutableMap()
        val fresh = transactions.filter { transaction ->
            val remaining = remainingExisting.getOrDefault(transaction.contentHash, 0)
            if (remaining > 0) {
                remainingExisting[transaction.contentHash] = remaining - 1
                false
            } else {
                true
            }
        }
        // Which rows were written, not merely how many. Auto-categorisation
        // has to run against exactly these -- running it over everything
        // parsed would count rows that de-duplication threw away.
        //
        // Filtered on the insert's own answer rather than assuming `fresh`
        // all landed: onConflict = IGNORE returns -1 for a row it skipped.
        // That can't happen today (ids are freshly generated UUIDs), which
        // is precisely why assuming it would be the kind of thing nobody
        // notices when it stops being true.
        val rowIds = insertAllIgnoringConflicts(fresh)
        return fresh.filterIndexed { index, _ -> rowIds[index] != -1L }.map { it.id }
    }
}
