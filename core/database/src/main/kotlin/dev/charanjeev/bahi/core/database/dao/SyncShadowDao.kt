package dev.charanjeev.bahi.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.charanjeev.bahi.core.database.entity.SyncShadowEntity

/**
 * Read by the resolver, written by the engine, and by nothing else
 * (docs/sync-design.md §4.1).
 *
 * There is no `deleted_at` and no `pending_operation` here, and no soft
 * delete: [forget] really does remove the row. A shadow is not user data and
 * has no tombstone worth keeping -- the absence of a base is itself the
 * meaningful state, and it is the state [forget] is for.
 */
@Dao
interface SyncShadowDao {

    @Query("SELECT * FROM sync_shadow WHERE table_name = :table AND row_id = :rowId")
    suspend fun baseOf(table: String, rowId: String): SyncShadowEntity?

    /** Batched form of [baseOf]: the engine classifies a whole pulled batch at once. */
    @Query("SELECT * FROM sync_shadow WHERE table_name = :table AND row_id IN (:rowIds)")
    suspend fun basesOf(table: String, rowIds: List<String>): List<SyncShadowEntity>

    @Upsert
    suspend fun record(shadow: SyncShadowEntity)

    @Upsert
    suspend fun recordAll(shadows: List<SyncShadowEntity>)

    @Query("DELETE FROM sync_shadow WHERE table_name = :table AND row_id = :rowId")
    suspend fun forget(table: String, rowId: String): Int

    /**
     * Distinguishes "this row has no base" from "this device has no bases at
     * all". §4.1 treats the two differently on purpose: the first is a row to
     * merge without one (§4.1's first-sync cases), the second is a fresh
     * install or a restored backup, which takes the full-reconciliation path
     * in §7 rather than merging thousands of rows blind.
     */
    @Query("SELECT COUNT(*) FROM sync_shadow")
    suspend fun count(): Int
}
