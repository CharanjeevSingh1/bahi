package dev.charanjeev.bahi.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.charanjeev.bahi.core.model.ContentIdScheme
import dev.charanjeev.bahi.core.model.contentDerivedId
import dev.charanjeev.bahi.core.model.contentHashOf

/**
 * Every migration here has a matching test in MigrationTest. When you bump the
 * @Database version, add the migration, add the test, and commit the generated
 * schema JSON in the same PR -- CI checks for all three.
 */
object Migrations {

    /**
     * Adds import_batch_id (docs/csv-import-design.md §11.1): nullable, so
     * every existing row gets NULL rather than a fabricated value -- those
     * rows genuinely predate batch tracking and are correctly unreachable by
     * undo, not a migration gap. The index mirrors content_hash's -- both
     * back a query that looks rows up by that column alone.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN import_batch_id TEXT DEFAULT NULL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_import_batch_id ON transactions (import_batch_id)")
        }
    }

    /**
     * Adds the two M3 tables (docs/budgets-design.md §4.1). Purely additive --
     * no existing table is touched, so there is no data to migrate and nothing
     * a pre-M3 row can be wrong about.
     *
     * The CREATE TABLE statements have to match what Room generates for
     * [dev.charanjeev.bahi.core.database.entity.CategoryRuleEntity] and
     * [dev.charanjeev.bahi.core.database.entity.BudgetEntity] exactly, down to
     * column order and the ON UPDATE clause Room always emits -- Room's own
     * schema validation compares the two and fails the migration if they
     * diverge. Copied from the generated schemas/3.json rather than written by
     * hand for that reason.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `category_rules` (
                    `id` TEXT NOT NULL, `category_id` TEXT NOT NULL,
                    `merchant_contains` TEXT NOT NULL, `priority` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                    `local_revision` INTEGER NOT NULL, `remote_revision` INTEGER,
                    `pending_operation` TEXT, `deleted_at` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_category_rules_category_id` " +
                    "ON `category_rules` (`category_id`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budgets` (
                    `id` TEXT NOT NULL, `category_id` TEXT NOT NULL,
                    `year_month` TEXT NOT NULL, `limit_minor` INTEGER NOT NULL,
                    `currency_code` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                    `local_revision` INTEGER NOT NULL, `remote_revision` INTEGER,
                    `pending_operation` TEXT, `deleted_at` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_budgets_category_id` ON `budgets` (`category_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_budgets_year_month` ON `budgets` (`year_month`)",
            )
        }
    }

    /**
     * Gives `categories` the four sync columns every other table has carried
     * since it was created, so that it can be soft-deleted and therefore
     * synced (docs/sync-design.md §1.2). Purely additive -- four ADD COLUMNs,
     * no table rebuild -- which is why this is three lines rather than the
     * twelve-step dance SQLite needs to change a column below 3.35.
     *
     * `local_revision` needs a DEFAULT because SQLite will not add a NOT NULL
     * column without one. 1 is the same value [dev.charanjeev.bahi.core.database.entity.CategoryEntity] gives a new
     * row, so a pre-v4 category is indistinguishable from one created after
     * the migration -- which is right: they are all equally unsynced, and
     * revision 1 means "never pushed" rather than "version 1 of a synced row".
     * The entity declares no `defaultValue`, so Room compares only type and
     * nullability here and a DEFAULT in the database alone does not diverge
     * from the schema.
     *
     * Existing rows get `deleted_at = NULL`, i.e. every category that exists
     * today is alive. There is no data to migrate: nothing was ever
     * soft-deleted, because until now the delete was a DELETE.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE categories ADD COLUMN local_revision INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE categories ADD COLUMN remote_revision INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE categories ADD COLUMN pending_operation TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE categories ADD COLUMN deleted_at INTEGER DEFAULT NULL")
        }
    }

    /**
     * No schema change at all -- this one rewrites data (docs/sync-design.md
     * §3). Two devices have to be able to derive the same id for the same row
     * independently, and every row created before now has a UUID, which by
     * construction they cannot.
     *
     * It runs in Kotlin rather than SQL because SHA-256 is not a SQLite
     * function and the occurrence index is not expressible as one either. Both
     * halves read every row of their table into memory first and write after
     * the cursor is closed: the alternative is updating a primary key while
     * iterating the table it indexes. A row's worth of state here is a few
     * dozen bytes, so this is bounded by row count -- tens of thousands is
     * megabytes -- and it happens once, inside the transaction Room already
     * wraps a migration in, so it either fully applies or not at all.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rewriteTransactionIdentity(db)
            rewriteBudgetIds(db)
        }
    }

    /**
     * The merge base and the conflict log (docs/sync-design.md §4.1, §5.6).
     * Purely additive, like MIGRATION_2_3, and for the same reason there is no
     * data to migrate: no row has ever been synced, so there is no agreed base
     * for any of them and an empty `sync_shadow` is the truthful starting
     * state rather than a gap.
     *
     * That is not the same as the table being unused on first sync. An empty
     * shadow is exactly what §4.1's first-sync cases are written for -- a row
     * the remote has never seen is a creation, and a row both devices hold
     * with no base is only a conflict on the fields that actually differ. What
     * this migration must not do is invent a base, which would silently claim
     * "this device changed nothing" for every row it touched.
     *
     * Neither table gets the four sync columns. They are local records about
     * sync; syncing them would be circular. Their entities say so, because in
     * a schema where every other table has them their absence would otherwise
     * read as an oversight.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_shadow` (
                    `table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL,
                    `remote_revision` INTEGER NOT NULL, `payload` TEXT,
                    PRIMARY KEY(`table_name`, `row_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sync_conflicts` (
                    `id` TEXT NOT NULL, `table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL,
                    `field` TEXT NOT NULL, `resolved_at` INTEGER NOT NULL,
                    `chosen_value` TEXT NOT NULL, `discarded_value` TEXT NOT NULL,
                    `reason` TEXT NOT NULL, `acknowledged_at` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_table_name_row_id_field` " +
                    "ON `sync_conflicts` (`table_name`, `row_id`, `field`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_acknowledged_at` " +
                    "ON `sync_conflicts` (`acknowledged_at`)",
            )
        }
    }

    /**
     * **Every row gets a new `content_hash`, not just the imported ones**, and
     * that is the half of this migration with a present-day consequence.
     * `contentHashOf` moved off `String.hashCode()` (32 bits is survivable for
     * de-duplication and not for a primary key), so every stored hash is now
     * computed by a function nothing uses. Skip this and
     * `existingRowsByHash` matches nothing on the next import, and a user
     * re-importing an overlapping statement gets the overlap inserted a second
     * time -- a data bug on one device with no sync anywhere near it.
     *
     * `CSV_IMPORT` rows additionally get a content-derived id. The occurrence
     * index is assigned per hash in insertion order, which is the numbering a
     * single clean import of the same rows would have produced, so two devices
     * holding the same imported history arrive at the same ids. `MANUAL` rows
     * keep their UUIDs by design, and are counted out of the occurrence index
     * entirely -- a hand-typed row that happens to share a tuple with an
     * imported one must not shift the imported one's number.
     *
     * A row whose id is already content-derived is left alone, under any
     * scheme version. That is what makes this migration idempotent and what
     * would keep a future `h2:` rewrite from downgrading rows it does not own.
     */
    private fun rewriteTransactionIdentity(db: SupportSQLiteDatabase) {
        data class Row(val id: String, val newHash: String, val source: String)

        val rows = mutableListOf<Row>()
        db.query(
            "SELECT id, account_id, date, amount_minor, description, source FROM transactions ORDER BY rowid",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    id = cursor.getString(0),
                    newHash = contentHashOf(
                        scheme = ContentIdScheme.CURRENT,
                        accountId = cursor.getString(1),
                        date = cursor.getString(2),
                        amountMinor = cursor.getLong(3),
                        description = cursor.getString(4),
                    ),
                    source = cursor.getString(5),
                )
            }
        }

        val occurrences = mutableMapOf<String, Int>()
        db.compileStatement("UPDATE transactions SET id = ?, content_hash = ? WHERE id = ?").use { update ->
            for (row in rows) {
                val newId = if (row.source == CSV_IMPORT && !ContentIdScheme.isContentDerived(row.id)) {
                    val occurrence = occurrences.getOrDefault(row.newHash, 0)
                    occurrences[row.newHash] = occurrence + 1
                    contentDerivedId(ContentIdScheme.CURRENT, row.newHash, occurrence)
                } else {
                    row.id
                }
                update.bindString(1, newId)
                update.bindString(2, row.newHash)
                update.bindString(3, row.id)
                update.executeUpdateDelete()
            }
        }
    }

    /**
     * A budget's id becomes its natural key, `budget:<categoryId>:<yearMonth>`,
     * so that two devices creating the same August Food budget offline produce
     * one row with a conflict on `limit_minor` rather than two rows nothing can
     * reconcile (docs/sync-design.md §3.2).
     *
     * **Not a blanket UPDATE, because the key is not unique in the table
     * today.** `BudgetDao.findActive` filters `deleted_at IS NULL` precisely so
     * that deleting an August Food budget and creating another leaves a
     * tombstone and a live row sharing the key -- documented behaviour, not
     * corruption (docs/budgets-design.md §4.1). Mapping both onto one primary
     * key would fail the migration on exactly the devices whose users have
     * used budgets most.
     *
     * So one claimant per key: the live row if there is one, newest first,
     * ties broken by id so two devices with the same rows choose the same
     * winner. Everything else keeps its UUID, including a losing *live* row,
     * which the repository cannot produce but which this has no business
     * deleting if it somehow exists -- a migration that silently discards a row
     * the user can see is a worse failure than two rows the user can see.
     */
    private fun rewriteBudgetIds(db: SupportSQLiteDatabase) {
        data class Row(val id: String, val key: String, val deletedAt: Long?, val updatedAt: Long)

        val rows = mutableListOf<Row>()
        db.query("SELECT id, category_id, year_month, deleted_at, updated_at FROM budgets").use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    id = cursor.getString(0),
                    key = "budget:${cursor.getString(1)}:${cursor.getString(2)}",
                    deletedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                    updatedAt = cursor.getLong(4),
                )
            }
        }

        val claimants = rows.groupBy(Row::key).values.mapNotNull { group ->
            group.sortedWith(
                compareBy<Row> { it.deletedAt != null }
                    .thenByDescending { it.deletedAt ?: it.updatedAt }
                    .thenBy { it.id },
            ).first().takeIf { it.id != it.key }
        }

        db.compileStatement("UPDATE budgets SET id = ? WHERE id = ?").use { update ->
            for (row in claimants) {
                update.bindString(1, row.key)
                update.bindString(2, row.id)
                update.executeUpdateDelete()
            }
        }
    }

    /**
     * Gives `categories` an `updated_at`, the one column every other synced
     * table already had and `categories` never needed before sync
     * (docs/sync-design.md §4.3's category gap, found while building slice
     * 5a). Purely additive, same shape as MIGRATION_3_4.
     *
     * `DEFAULT 0` for the same reason `local_revision` needed one in
     * MIGRATION_3_4: SQLite refuses a NOT NULL column with no default, and
     * the entity declares none of its own, so Room compares only type and
     * nullability and a database-only DEFAULT does not diverge from the
     * schema. 0 undersells how recently a pre-migration category was
     * actually touched, which only matters the moment it next conflicts with
     * an edit on another device -- at which point it is `0` against that
     * edit's real timestamp, so the *other* device's edit wins the tiebreak.
     * That is the safe direction to be wrong in: the alternative, `now()` at
     * migration time, would make every untouched category look freshly
     * edited and let it beat a genuine concurrent edit made earlier but
     * pushed later.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE categories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    private const val CSV_IMPORT = "CSV_IMPORT"

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
    )
}
