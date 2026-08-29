package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.database.entity.TransactionEntity
import dev.charanjeev.bahi.core.model.ContentIdScheme
import dev.charanjeev.bahi.core.model.Transaction
import dev.charanjeev.bahi.core.model.TransactionFilter
import dev.charanjeev.bahi.core.model.TransactionSource
import dev.charanjeev.bahi.core.model.contentDerivedId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.util.UUID
import javax.inject.Inject

/**
 * Offline-first: reads always come from Room, never from the network. Sync
 * writes into the same tables, so the UI updates through the normal Flow with
 * no special-casing for "we just synced".
 *
 * TODO(M4): conflict resolution hooks land here once :core:sync is built.
 */
class OfflineFirstTransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    // Injected for the same reason dispatchers are: a tombstone's deleted_at
    // is observable behaviour that sync depends on, and System
    // .currentTimeMillis() makes it unassertable in a test.
    private val clock: Clock,
    // @param: pins the qualifier to the constructor parameter, which is what Hilt
    // reads. Kotlin 2.2 warns that the default target is changing in a future
    // release; being explicit keeps injection working either way.
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : TransactionRepository {

    override fun observeTransactions(filter: TransactionFilter): Flow<List<Transaction>> =
        transactionDao.observeFiltered(
            categoryIds = filter.categoryIds.toList(),
            categoryCount = filter.categoryIds.size,
            hasDateWindow = if (filter.dateWindow != null) 1 else 0,
            from = filter.dateWindow?.from?.toString().orEmpty(),
            to = filter.dateWindow?.to?.toString().orEmpty(),
        ).map { entities -> entities.map(::toDomain) }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.let(::toDomain) }

    override suspend fun upsert(transaction: Transaction) = withContext(ioDispatcher) {
        // Transactions were the odd one out: every other repository's upsert
        // marks the row pending and bumps its revision, and this one wrote
        // `toEntity`'s defaults -- pending_operation null, local_revision 1 --
        // so a newly created transaction was invisible to `pendingChanges`
        // and would never have been pushed at all (docs/sync-design.md §4.3).
        //
        // Reading the revision through `revisionOf` rather than a `getById`
        // is the other half: upserting an id that is tombstoned would
        // otherwise restart the count at 1 and drop the remote's
        // acknowledgement. See RowRevision.
        val revision = transactionDao.revisionOf(transaction.id)
        transactionDao.upsert(
            toEntity(transaction).copy(
                pendingOperation = "UPSERT",
                localRevision = (revision?.localRevision ?: 0) + 1,
                remoteRevision = revision?.remoteRevision,
            ),
        )
    }

    override suspend fun update(transaction: Transaction) = withContext(ioDispatcher) {
        val entity = toEntity(transaction)
        transactionDao.update(
            id = entity.id,
            amountMinor = entity.amountMinor,
            currencyCode = entity.currencyCode,
            date = entity.date,
            description = entity.description,
            merchant = entity.merchant,
            categoryId = entity.categoryId,
            accountId = entity.accountId,
            notes = entity.notes,
            categoryLockedByUser = entity.categoryLockedByUser,
            contentHash = entity.contentHash,
            updatedAt = entity.updatedAt,
        )
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        transactionDao.softDelete(id, clock.now().toEpochMilliseconds())
    }

    override suspend fun undoDelete(id: String) = withContext(ioDispatcher) {
        transactionDao.undoSoftDelete(id)
    }

    override suspend fun importAll(transactions: List<Transaction>): ImportBatchResult =
        withContext(ioDispatcher) {
            val batchId = UUID.randomUUID().toString()
            val entities = withContentDerivedIds(
                transactions.map { toEntity(it, importBatchId = batchId) },
            )
            val insertedIds = transactionDao.importBatch(entities)
            ImportBatchResult(batchId, insertedIds)
        }

    /**
     * Replaces the importer's placeholder UUIDs with ids derived from what the
     * rows actually contain (docs/sync-design.md §3.1), so that two devices
     * importing the same statement produce byte-identical ids and the
     * duplicate never exists to be cleaned up.
     *
     * Here rather than in the importer because this is where a parsed row
     * becomes a row: `contentHash` is computed one line above by `toEntity`,
     * and deriving the id anywhere else would mean a second implementation of
     * the same tuple. The importer's UUID is a placeholder for the preview
     * stage and nothing reads it before this point.
     *
     * The occurrence counter runs over the incoming list in file order, which
     * is the same order `importBatch`'s count-aware quota consumes it in -- so
     * a re-import of an overlapping statement numbers its rows the same way
     * the first import did, the quota drops exactly the leading duplicates,
     * and the survivors carry the ids the first import would have given them.
     * That agreement is not a coincidence but it is also not enforced: both
     * rest on same-tuple rows keeping a stable relative order across
     * re-exports, which `importBatch`'s own doc records as an assumption. What
     * is new is that a violation now surfaces as an id collision the insert
     * reports, rather than as a silently dropped row.
     *
     * A row whose id is already content-derived is left alone, including under
     * a scheme version this build does not know -- re-keying an `h2:` row to
     * `h1:` would be a downgrade that splits it from every other device.
     */
    private fun withContentDerivedIds(entities: List<TransactionEntity>): List<TransactionEntity> {
        val occurrences = mutableMapOf<String, Int>()
        return entities.map { entity ->
            if (entity.source != TransactionSource.CSV_IMPORT.name ||
                ContentIdScheme.isContentDerived(entity.id)
            ) {
                return@map entity
            }
            val occurrence = occurrences.getOrDefault(entity.contentHash, 0)
            occurrences[entity.contentHash] = occurrence + 1
            entity.copy(
                id = contentDerivedId(ContentIdScheme.CURRENT, entity.contentHash, occurrence),
            )
        }
    }

    override suspend fun undoImport(batchId: String): Int = withContext(ioDispatcher) {
        transactionDao.softDeleteBatch(batchId, clock.now().toEpochMilliseconds())
    }

    override suspend fun applyRuleCategories(assignments: Map<String, String>): Int =
        withContext(ioDispatcher) {
            if (assignments.isEmpty()) return@withContext 0
            transactionDao.applyRuleCategories(assignments, clock.now().toEpochMilliseconds())
        }
}
