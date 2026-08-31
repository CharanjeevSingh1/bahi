package dev.charanjeev.bahi.core.data.repository

import dev.charanjeev.bahi.core.common.BahiDispatcher
import dev.charanjeev.bahi.core.common.Dispatcher
import dev.charanjeev.bahi.core.database.dao.CategoryRuleDao
import dev.charanjeev.bahi.core.database.dao.TransactionDao
import dev.charanjeev.bahi.core.model.CategoryRule
import dev.charanjeev.bahi.core.model.RuleApplicationPreview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import javax.inject.Inject

class OfflineFirstCategoryRuleRepository @Inject constructor(
    private val categoryRuleDao: CategoryRuleDao,
    // A rule run is rules applied to transactions, so this repository needs
    // both tables. Same shape as OfflineFirstBudgetRepository reaching for
    // TransactionDao to get at uncategorised spend.
    private val transactionDao: TransactionDao,
    private val clock: Clock,
    @param:Dispatcher(BahiDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : CategoryRuleRepository {

    override fun observeRules(): Flow<List<CategoryRule>> =
        categoryRuleDao.observeAll().map { entities -> entities.map(::toDomain) }

    override suspend fun rules(): List<CategoryRule> = withContext(ioDispatcher) {
        categoryRuleDao.getAll().map(::toDomain)
    }

    override suspend fun upsert(rule: CategoryRule) = withContext(ioDispatcher) {
        // See the interface doc: a blank needle matches every transaction the
        // user has, so this is a crash-rather-than-corrupt guard, not
        // validation the UI is expected to lean on.
        require(rule.merchantContains.isNotBlank()) {
            "A rule's merchantContains cannot be blank -- it would match every transaction."
        }
        val now = clock.now().toEpochMilliseconds()
        // Editing a rule must not restate when it was created, so an existing
        // row's createdAt is carried over rather than overwritten with now.
        val existing = categoryRuleDao.getById(rule.id)
        // Read through the tombstone for the revision only: see RowRevision.
        // createdAt stays on the live-row read on purpose -- a rule the user
        // deleted and recreated was created when they recreated it, and that
        // is a fact about the rule rather than about the row.
        val revision = categoryRuleDao.revisionOf(rule.id)
        val entity = toEntity(rule, createdAt = existing?.createdAt ?: now, updatedAt = now).copy(
            pendingOperation = "UPSERT",
            localRevision = (revision?.localRevision ?: 0) + 1,
            remoteRevision = revision?.remoteRevision,
        )
        categoryRuleDao.upsert(entity)
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        categoryRuleDao.softDelete(id, clock.now().toEpochMilliseconds())
    }

    override suspend fun reorder(orderedIds: List<String>) = withContext(ioDispatcher) {
        categoryRuleDao.reorder(orderedIds, clock.now().toEpochMilliseconds())
    }

    override suspend fun previewApplyToExisting(rule: CategoryRule): RuleApplicationPreview =
        // One rule, not the whole set: this previews what *this* rule does,
        // so a rule the user is editing can't have its count inflated by an
        // unrelated rule that happens to match the same transactions.
        preview(rules = listOf(rule), uncategorisedOnly = false)

    override suspend fun previewRecategoriseUncategorised(): RuleApplicationPreview =
        preview(rules = rules(), uncategorisedOnly = true)

    override suspend fun apply(preview: RuleApplicationPreview): Int = withContext(ioDispatcher) {
        // The preview's own assignments, not a fresh match. Recomputing here
        // would let the number the user consented to and the number written
        // differ, which is the one thing the preview exists to prevent.
        transactionDao.applyRuleCategories(preview.assignments, clock.now().toEpochMilliseconds())
    }

    private suspend fun preview(
        rules: List<CategoryRule>,
        uncategorisedOnly: Boolean,
    ): RuleApplicationPreview = withContext(ioDispatcher) {
        val scope = if (uncategorisedOnly) 1 else 0
        val candidates = transactionDao.ruleCandidates(scope).map(::toDomain)
        val assignments = applyRules(rules, candidates)
        // A second query rather than deriving the skipped count from the
        // first: layer 1's candidate query cannot return a locked row at all,
        // which is exactly the property that makes it safe (§1.4) and
        // exactly why the locked ones have to be fetched separately to be
        // counted at all.
        val locked = transactionDao.lockedRuleMatchCandidates(scope).map(::toDomain)
        RuleApplicationPreview(
            assignments = assignments,
            lockedSkippedCount = countLockedMatches(rules, locked),
        )
    }

    override suspend fun dirtyRows(limit: Int): List<DirtyRow> = withContext(ioDispatcher) {
        categoryRuleDao.dirtyRows(limit).map { entity ->
            DirtyRow(
                rowId = entity.id,
                localRevision = entity.localRevision,
                updatedAt = entity.updatedAt,
                payload = if (entity.deletedAt != null) null else toFieldMap(entity),
            )
        }
    }

    override suspend fun markSynced(rowId: String, remoteRevision: Long, expectedLocalRevision: Long): Boolean =
        withContext(ioDispatcher) {
            categoryRuleDao.markSynced(rowId, remoteRevision, expectedLocalRevision) > 0
        }
}
