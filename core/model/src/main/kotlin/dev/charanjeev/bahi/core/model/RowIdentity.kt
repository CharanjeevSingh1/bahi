package dev.charanjeev.bahi.core.model

import java.security.MessageDigest

/**
 * Row ids that two devices derive independently and agree on.
 *
 * Most rows here are identified by a UUID, which is the right default: an id
 * has to be unique and nothing more. It stops being enough the moment two
 * devices can independently create *the same thing*. Importing January's
 * statement on a phone and again on a tablet produces two sets of rows that
 * are identical in every field that matters and share no id, so nothing
 * downstream can tell they are duplicates -- de-duplication runs at import
 * time against the local table and never runs again
 * (docs/sync-design.md §3).
 *
 * The fix is to make the id a function of the content rather than of chance,
 * for exactly the rows where that is meaningful. Both devices compute the same
 * id, so the duplicate is not de-duplicated: it never exists.
 *
 * Manual entries keep UUIDs deliberately. Two people-with-one-account are not
 * going to type the same transaction on two devices and mean one row; if they
 * both typed it, there are two.
 */

/**
 * Version tag on the content-derived id scheme, carried in the id itself as a
 * prefix.
 *
 * This is what keeps the scheme from being a one-way door. If the tuple or the
 * hash ever has to change, new rows get an `h2:` prefix and existing rows keep
 * their `h1:` one; both are valid ids, both stay readable, and nothing has to
 * rewrite primary
 * keys on every device and every row on the remote. The only cost of a version
 * bump is that a re-import across the boundary stops recognising its own
 * earlier rows and duplicates them -- a de-duplication miss, not data loss.
 *
 * For that to be true, the prefix has to be *read* somewhere and branched on,
 * not merely written. It is: [schemeOf] is what
 * `Migrations.MIGRATION_4_5` and `OfflineFirstTransactionRepository.importAll`
 * use to leave an already-derived id alone, including one from a scheme this
 * build has never heard of.
 */
enum class ContentIdScheme(val prefix: String) {
    /** SHA-256 over the identity tuple, truncated to 128 bits, lowercase hex. */
    H1("h1"),
    ;

    companion object {
        /** The scheme new ids are minted under. */
        val CURRENT: ContentIdScheme = H1

        /**
         * The scheme [id] was minted under, or null if it is not a
         * content-derived id at all -- a UUID, most often.
         *
         * Returns null for an id from a *newer* scheme too, which is why
         * callers deciding whether to re-key a row must ask
         * [isContentDerived] instead: "I don't recognise this" and "this is a
         * UUID" have to lead to different decisions, and conflating them is
         * how a forward-compatible id scheme quietly stops being one.
         */
        fun schemeOf(id: String): ContentIdScheme? =
            entries.firstOrNull { id.startsWith("${it.prefix}:") }

        /**
         * Whether [id] has the shape of a content-derived id under any scheme
         * version, known or not.
         */
        fun isContentDerived(id: String): Boolean = SHAPE.matches(id)

        private val SHAPE = Regex("""^h\d+:[0-9a-f]+#\d+$""")
    }
}

/**
 * The identity tuple: which fields decide that two rows are the same bank
 * transaction.
 *
 * Deliberately excludes id, category and notes, for the same reason
 * `contentHashOf` always has -- the same bank row re-imported after the user
 * has categorised it must still be recognised as a duplicate.
 *
 * `uppercase()` with no argument is locale-invariant (it uses `Locale.ROOT`).
 * That was worth having when this only fed a de-duplication column and is
 * load-bearing now that it feeds a primary key: a device with a Turkish locale
 * would otherwise fold `i` differently and derive a different id for the same
 * transaction, and the two devices would never converge.
 */
private fun identityTuple(
    accountId: String,
    date: String,
    amountMinor: Long,
    description: String,
): String = listOf(
    accountId,
    date,
    amountMinor.toString(),
    description.trim().uppercase(),
).joinToString(separator = "|")

/**
 * The content hash for a transaction's identity tuple, under [scheme].
 *
 * SHA-256 truncated to 128 bits rather than the `String.hashCode()` this used
 * to be. `String.hashCode` is specified by the JLS, so it is stable across
 * devices and JVM versions, which is the property that matters most and the
 * reason it was never wrong as a de-duplication key -- a 32-bit collision
 * there merges two unrelated rows in one import, which is bad but bounded and
 * local. As a *primary key* it is not survivable: the birthday bound puts a
 * collision at odds-on somewhere in the low tens of thousands of rows, and a
 * collision then means two different transactions are one row forever, on
 * every device.
 *
 * `MessageDigest` is a platform API on both the JVM and Android, so this costs
 * no dependency.
 */
fun contentHashOf(
    scheme: ContentIdScheme,
    accountId: String,
    date: String,
    amountMinor: Long,
    description: String,
): String {
    val tuple = identityTuple(accountId, date, amountMinor, description)
    return when (scheme) {
        ContentIdScheme.H1 -> sha256Truncated(tuple, bytes = 16)
    }
}

/**
 * The row id for the [occurrence]-th transaction sharing [contentHash].
 *
 * The occurrence index is what lets two genuinely identical coffees both
 * survive: they get `#0` and `#1` on both devices, and a device that imported
 * two of a tuple merging with one that imported three converges on three with
 * no special logic. It is assigned by the same count-aware quota
 * `TransactionDao.importBatch` already computes (docs/csv-import-design.md §4),
 * not by a second de-duplication pass.
 *
 * The id is derived from a description string that different exports of the
 * same transaction can render differently -- one strips a reference number the
 * other keeps, one pads a column the other trims. Where that happens the two
 * exports produce different hashes, so the same real transaction gets two ids
 * and both devices keep both rows. **That is a de-duplication miss, not a
 * correctness bug:** no row is lost, no amount is wrong, nothing merges that
 * should not have. It is also not new -- `content_hash` has always had exactly
 * this fragility -- and deriving the id from it does not make it worse, only
 * more visible.
 */
fun contentDerivedId(
    scheme: ContentIdScheme,
    contentHash: String,
    occurrence: Int,
): String = "${scheme.prefix}:$contentHash#$occurrence"

/**
 * A budget's id is its natural key: one budget per category per month.
 *
 * The uniqueness rule already exists and is enforced in
 * `OfflineFirstBudgetRepository.upsert`, because a partial unique index over
 * `deleted_at IS NULL` is what a constraint would need and Room's
 * `@Entity(indices = ...)` cannot declare one (docs/budgets-design.md §4.1).
 * Sync does not go through that repository, so two offline devices could each
 * create an August Food budget and both would arrive -- a duplicate the
 * repository is still perfectly correct about and cannot fix, because the
 * second row never passed through it. Deriving the id from the key turns that
 * into an ordinary same-row conflict on `limit_minor` (docs/sync-design.md
 * §3.2).
 */
fun budgetIdFor(categoryId: String, month: YearMonth): String = "budget:$categoryId:$month"

private fun sha256Truncated(input: String, bytes: Int): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .take(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
