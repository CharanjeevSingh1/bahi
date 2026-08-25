package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * FakeBudgetDao joins against these. A Flow rather than a snapshot,
     * because the behaviour being faked is Room re-running the join when
     * `transactions` changes -- a snapshot would make the fake pass a test
     * that the real query only passes because of that invalidation.
     */
    val rows: StateFlow<Map<String, TransactionEntity>> get() = backing

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

    /**
     * The real query's four conditions, written out by hand -- `amount_minor
     * < 0` in particular, since dropping it here would make income cancel
     * spending out and the fake would disagree with SQLite about the sign.
     */
    override fun observeUncategorisedSpend(from: String, to: String): Flow<Long> =
        backing.map { entities ->
            entities.values
                .filter { it.categoryId == null && it.amountMinor < 0 && it.deletedAt == null }
                .filter { it.date >= from && it.date <= to }
                .sumOf { -it.amountMinor }
        }

    override suspend fun countExistingHashes(hashes: List<String>): Map<String, Int> =
        backing.value.values
            .map { it.contentHash }
            .filter { it in hashes }
            .groupingBy { it }
            .eachCount()

    /**
     * The real query's lock condition is `category_locked_by_user = 0` in the
     * WHERE clause -- mirrored here rather than left implicit, because a fake
     * that returned a locked row would make a repository test pass while the
     * real candidate set can't produce one.
     */
    override suspend fun ruleCandidates(uncategorisedOnly: Int): List<TransactionEntity> =
        backing.value.values
            .filter { it.deletedAt == null && !it.categoryLockedByUser }
            .filter { uncategorisedOnly == 0 || it.categoryId == null }
            .sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.createdAt })

    override suspend fun lockedRuleMatchCandidates(uncategorisedOnly: Int): List<TransactionEntity> =
        backing.value.values
            .filter { it.deletedAt == null && it.categoryLockedByUser }
            .filter { uncategorisedOnly == 0 || it.categoryId == null }

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
                importBatchId = null,
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

    override suspend fun softDeleteBatch(batchId: String, deletedAt: Long): Int {
        var affected = 0
        backing.value = backing.value.mapValues { (_, entity) ->
            if (entity.importBatchId == batchId && entity.deletedAt == null) {
                affected++
                entity.copy(deletedAt = deletedAt, pendingOperation = "DELETE", localRevision = entity.localRevision + 1)
            } else {
                entity
            }
        }
        return affected
    }

    /**
     * The real query's guard is three conditions in a WHERE clause; here it
     * has to be written out by hand. Getting it wrong would make the fake
     * more permissive than SQLite and let a repository test pass while the
     * real write is blocked -- or worse, the reverse.
     */
    override suspend fun applyRuleCategory(id: String, categoryId: String, updatedAt: Long): Int {
        val existing = backing.value[id] ?: return 0
        if (existing.categoryLockedByUser || existing.deletedAt != null) return 0
        backing.value = backing.value + (
            id to existing.copy(
                categoryId = categoryId,
                updatedAt = updatedAt,
                pendingOperation = "UPSERT",
                localRevision = existing.localRevision + 1,
            )
        )
        return 1
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
