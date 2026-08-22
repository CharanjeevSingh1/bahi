package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake, matching FakeCategoryDao. softDelete/undoSoftDelete
 * mirror the real queries' pending_operation semantics by hand -- there's no
 * SQLite backing this fake, so getting that transition right here is exactly
 * as important as getting the real @Query right.
 */
class FakeTransactionDao : TransactionDao {

    private val backing = MutableStateFlow<Map<String, TransactionEntity>>(emptyMap())

    fun entity(id: String): TransactionEntity? = backing.value[id]

    override fun observeById(id: String): Flow<TransactionEntity?> =
        backing.map { it[id]?.takeIf { entity -> entity.deletedAt == null } }

    override fun observeFiltered(
        categoryIds: List<String>,
        categoryCount: Int,
        hasDateWindow: Int,
        from: String,
        to: String,
    ): Flow<List<TransactionEntity>> =
        backing.map { entities ->
            entities.values
                .filter { it.deletedAt == null }
                .filter { categoryCount == 0 || it.categoryId in categoryIds }
                .filter { hasDateWindow == 0 || (it.date >= from && it.date <= to) }
                .sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.createdAt })
        }

    override suspend fun findExistingHashes(hashes: List<String>): List<String> =
        backing.value.values.map { it.contentHash }.filter { it in hashes }

    override suspend fun upsert(transaction: TransactionEntity) {
        backing.value = backing.value + (transaction.id to transaction)
    }

    override suspend fun update(
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
    ) {
        val existing = backing.value[id] ?: return
        backing.value = backing.value + (
            id to existing.copy(
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                date = date,
                description = description,
                merchant = merchant,
                categoryId = categoryId,
                accountId = accountId,
                notes = notes,
                categoryLockedByUser = categoryLockedByUser,
                contentHash = contentHash,
                updatedAt = updatedAt,
                pendingOperation = "UPSERT",
                localRevision = existing.localRevision + 1,
            )
        )
    }

    override suspend fun insertAllIgnoringConflicts(transactions: List<TransactionEntity>): List<Long> {
        val fresh = transactions.filterNot { it.id in backing.value }
        backing.value = backing.value + fresh.associateBy(TransactionEntity::id)
        return fresh.map { 1L }
    }

    override suspend fun softDelete(id: String, deletedAt: Long) {
        val existing = backing.value[id] ?: return
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = deletedAt,
                pendingOperation = "DELETE",
                localRevision = existing.localRevision + 1,
            )
        )
    }

    override suspend fun undoSoftDelete(id: String) {
        val existing = backing.value[id] ?: return
        backing.value = backing.value + (
            id to existing.copy(
                deletedAt = null,
                pendingOperation = "UPSERT",
                localRevision = existing.localRevision + 1,
            )
        )
    }

    override suspend fun pendingChanges(limit: Int): List<TransactionEntity> =
        backing.value.values.filter { it.pendingOperation != null }.take(limit)

    override suspend fun markSynced(id: String, remoteRevision: Long) {
        val existing = backing.value[id] ?: return
        backing.value = backing.value + (
            id to existing.copy(pendingOperation = null, remoteRevision = remoteRevision)
        )
    }
}
