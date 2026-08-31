package dev.charanjeev.bahi.feature.settings

import dev.charanjeev.bahi.core.data.repository.RestoreOutcome
import dev.charanjeev.bahi.core.data.repository.SyncConflictRepository
import dev.charanjeev.bahi.core.model.SyncConflict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake, matching FakeCategoryRuleRepository's shape in
 * :feature:budgets. [restoreResult] lets a test choose which of
 * [RestoreOutcome]'s four answers `restore` returns without needing a real
 * database -- the repository layer's own androidTest already covers how each
 * one is actually decided.
 */
class FakeSyncConflictRepository(
    initial: List<SyncConflict> = emptyList(),
) : SyncConflictRepository {

    private val backing = MutableStateFlow(initial.associateBy { it.id })

    var restoreResult: RestoreOutcome = RestoreOutcome.RESTORED
    val restoreCalls = mutableListOf<String>()
    val acknowledgeCalls = mutableListOf<String>()

    fun emit(conflicts: List<SyncConflict>) {
        backing.value = conflicts.associateBy { it.id }
    }

    override fun observeConflicts(): Flow<List<SyncConflict>> =
        backing.map { it.values.sortedByDescending { conflict -> conflict.resolvedAt } }

    override fun observeUnacknowledgedCount(): Flow<Int> = backing.map { it.size }

    override suspend fun acknowledge(id: String) {
        acknowledgeCalls += id
        backing.value = backing.value - id
    }

    override suspend fun restore(id: String): RestoreOutcome {
        restoreCalls += id
        // A successful restore acknowledges the conflict (RoomSyncConflictRepository's
        // own doc explains why); the fake mirrors that so a ViewModel test sees
        // the same "restored rows drop off the list" behaviour the real one has.
        if (restoreResult == RestoreOutcome.RESTORED) backing.value = backing.value - id
        return restoreResult
    }
}
