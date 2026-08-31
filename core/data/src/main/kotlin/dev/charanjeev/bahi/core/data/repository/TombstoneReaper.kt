package dev.charanjeev.bahi.core.data.repository

import androidx.room.withTransaction
import dev.charanjeev.bahi.core.database.BahiDatabase
import dev.charanjeev.bahi.core.model.SyncTable
import dev.charanjeev.bahi.core.model.TOMBSTONE_HORIZON_DAYS
import kotlinx.datetime.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.days

/**
 * The local half of the tombstone horizon (docs/sync-design.md §7, D8): a
 * tombstone old enough that no device still plausibly offline could still be
 * relying on seeing it is hard-deleted, along with the `sync_shadow` and
 * `sync_conflicts` rows that were the only things still pointing at it --
 * neither has a real foreign key back to its parent table, because the
 * parent is named by a column, so nothing forgets them automatically.
 * Acknowledged conflicts age out on the same horizon (§5.6).
 *
 * Reaped in [SyncTable.entries] reversed -- children before
 * [SyncTable.CATEGORIES] -- so a category old enough to reap never fires
 * `ON DELETE CASCADE` on a budget or rule that was not itself old enough to
 * reap on its own account: by the time a category is reaped, anything that
 * would cascade from it is already gone (and already forgotten from
 * `sync_shadow`/`sync_conflicts`), or is not old enough and is deliberately
 * still there. `transactions.category_id` cascades `SET NULL`, not `CASCADE`,
 * so a live transaction still pointing at the category is unaffected by
 * reap order and simply loses that reference when the category goes.
 *
 * Run from [dev.charanjeev.bahi.core.sync.SyncEngine.sync] rather than a
 * periodic worker: M4a has no WorkManager wiring yet (that is slice 9,
 * M4b's job), so "once per sync" is what makes this an exercised code path
 * today instead of a theoretical one waiting on scheduling infrastructure
 * that does not exist yet -- D8's whole point about the horizon.
 */
interface TombstoneReaper {
    suspend fun reap()
}

class RoomTombstoneReaper @Inject constructor(
    private val database: BahiDatabase,
    private val clock: Clock,
) : TombstoneReaper {

    override suspend fun reap() {
        val cutoff = (clock.now() - TOMBSTONE_HORIZON_DAYS.days).toEpochMilliseconds()
        database.withTransaction {
            reapTable(SyncTable.CATEGORY_RULES, cutoff, database.categoryRuleDao()::tombstonesOlderThan, database.categoryRuleDao()::hardDelete)
            reapTable(SyncTable.BUDGETS, cutoff, database.budgetDao()::tombstonesOlderThan, database.budgetDao()::hardDelete)
            reapTable(SyncTable.TRANSACTIONS, cutoff, database.transactionDao()::tombstonesOlderThan, database.transactionDao()::hardDelete)
            reapTable(SyncTable.CATEGORIES, cutoff, database.categoryDao()::tombstonesOlderThan, database.categoryDao()::hardDelete)
            database.syncConflictDao().deleteAcknowledgedBefore(cutoff)
        }
    }

    private suspend fun reapTable(
        table: SyncTable,
        cutoff: Long,
        tombstonesOlderThan: suspend (Long) -> List<String>,
        hardDelete: suspend (String) -> Int,
    ) {
        for (id in tombstonesOlderThan(cutoff)) {
            hardDelete(id)
            database.syncShadowDao().forget(table.tableName, id)
            database.syncConflictDao().forgetRow(table.tableName, id)
        }
    }
}
