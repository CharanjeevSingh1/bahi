package dev.charanjeev.bahi.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs each migration against a database created at the previous schema version,
 * using the JSON schemas committed under core/database/schemas/.
 *
 * This is the test most Android portfolios don't have. It is also the one that
 * catches the bug that loses a user's data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BahiDatabase::class.java,
    )

    @Test
    fun migrateAll_fromVersion1_toLatest() {
        // Create the database at version 1 and close it immediately.
        helper.createDatabase(TEST_DB, 1).close()

        // Open with the real Room builder so every registered migration runs and
        // Room validates the resulting schema against the current entities.
        helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *Migrations.ALL)
    }

    /**
     * The migration this repo has actually never run until this slice.
     * Seeds a row at v1 shape (raw SQL -- there is no v1 TransactionEntity
     * left in the codebase to construct one from), migrates it, and checks
     * the two things that matter: the column exists, and a row that
     * predates it gets NULL rather than a fabricated batch id -- which is
     * what makes that row correctly un-batch-undoable rather than silently
     * wrong.
     */
    @Test
    fun migrate1To2_addsImportBatchIdColumn_asNullOnAPreExistingRow() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO transactions (
                    id, amount_minor, currency_code, date, description, merchant, category_id,
                    account_id, source, notes, category_locked_by_user, content_hash,
                    created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
                ) VALUES (
                    'pre-migration-1', -45000, 'INR', '2026-01-05', 'Coffee Shop', NULL, NULL,
                    'acct-1', 'MANUAL', NULL, 0, 'hash-1',
                    0, 0, 1, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        migrated.query("SELECT import_batch_id FROM transactions WHERE id = 'pre-migration-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
    }

    /**
     * MIGRATION_2_3 is purely additive (docs/budgets-design.md §4.1), so
     * there is no "did existing data survive" question to ask -- the thing
     * worth asserting instead is that the tables it creates are the ones
     * Room expects. `runMigrationsAndValidate` covers the shape; these
     * inserts cover that the columns are usable in the declared order, and
     * the delete covers the ON DELETE CASCADE both entities declare, which
     * is the one behavioural difference from `transactions`' SET_NULL.
     */
    @Test
    fun migrate2To3_createsRuleAndBudgetTables_cascadingFromTheirCategory() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO categories (id, name, parent_id, color_argb, icon_key, is_system_defined)
                VALUES ('food', 'Food', NULL, 0, 'restaurant', 1)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        migrated.execSQL(
            """
            INSERT INTO category_rules (
                id, category_id, merchant_contains, priority,
                created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
            ) VALUES ('rule-1', 'food', 'SWIGGY', 0, 0, 0, 1, NULL, NULL, NULL)
            """.trimIndent(),
        )
        migrated.execSQL(
            """
            INSERT INTO budgets (
                id, category_id, year_month, limit_minor, currency_code,
                created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
            ) VALUES ('budget-1', 'food', '2026-08', 800000, 'INR', 0, 0, 1, NULL, NULL, NULL)
            """.trimIndent(),
        )
        assertThat(countOf(migrated, "category_rules")).isEqualTo(1)
        assertThat(countOf(migrated, "budgets")).isEqualTo(1)

        // Asserted rather than assumed: a cascade is invisible unless foreign
        // keys are enforced on this connection, and enforcement is off by
        // default on a raw SupportSQLiteDatabase -- Room only turns it on for
        // databases opened through its own builder. Without this check, a
        // migration that dropped the foreign key entirely would still pass
        // the delete below.
        migrated.setForeignKeyConstraintsEnabled(true)
        migrated.query("PRAGMA foreign_keys").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }

        migrated.execSQL("DELETE FROM categories WHERE id = 'food'")

        assertThat(countOf(migrated, "category_rules")).isEqualTo(0)
        assertThat(countOf(migrated, "budgets")).isEqualTo(0)
    }

    /**
     * MIGRATION_3_4 gives `categories` the sync columns (docs/sync-design.md
     * §1.2). Additive, so the question is not whether data survived but what
     * a row that predates the columns is now *saying*: alive, never synced,
     * nothing pending. `local_revision` is the only one with a DEFAULT --
     * SQLite refuses a NOT NULL column without one -- so it is the only one
     * that could have come out wrong.
     */
    @Test
    fun migrate3To4_addsSyncColumnsToCategories_leavingPreExistingRowsAliveAndUnsynced() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO categories (id, name, parent_id, color_argb, icon_key, is_system_defined)
                VALUES ('user-hobbies', 'Hobbies', NULL, 0, 'palette', 0)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)

        migrated.query(
            """
            SELECT local_revision, remote_revision, pending_operation, deleted_at
            FROM categories WHERE id = 'user-hobbies'
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
            assertThat(cursor.isNull(3)).isTrue()
        }
    }

    /**
     * The cascade that migrate2To3 asserts is still declared at v4, and this
     * is the test that would catch it being lost. It is worth having twice
     * because the foreign key is now the *dormant* half of the rule: the app
     * soft-deletes categories, so nothing in production fires this any more
     * (CategoryDao.softDeleteUserCategory does the cascade itself), and a
     * dormant constraint is exactly the kind that gets dropped by a later
     * table rebuild without anyone noticing.
     */
    @Test
    fun migrate3To4_keepsTheForeignKeyCascadeFromCategories() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO categories (id, name, parent_id, color_argb, icon_key, is_system_defined)
                VALUES ('food', 'Food', NULL, 0, 'restaurant', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO budgets (
                    id, category_id, year_month, limit_minor, currency_code,
                    created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
                ) VALUES ('budget-1', 'food', '2026-08', 800000, 'INR', 0, 0, 1, NULL, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)
        migrated.setForeignKeyConstraintsEnabled(true)

        migrated.execSQL("DELETE FROM categories WHERE id = 'food'")

        assertThat(countOf(migrated, "budgets")).isEqualTo(0)
    }

    /**
     * MIGRATION_4_5 rewrites data rather than schema (docs/sync-design.md §3),
     * so `runMigrationsAndValidate` proves nothing on its own and every
     * assertion here is on values.
     *
     * Two imported rows sharing an identity tuple and one manual row that
     * shares it too. The imported pair must come out as `#0` and `#1` in
     * insertion order; the manual row must keep its UUID *and* be counted out
     * of the numbering, which is the assertion that fails if the occurrence
     * index is taken over all rows instead of imported ones.
     */
    @Test
    fun migrate4To5_derivesImportedTransactionIds_countingManualRowsOutOfTheNumbering() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertTransactionAtV4(this, id = "uuid-import-1", source = "CSV_IMPORT")
            insertTransactionAtV4(this, id = "uuid-manual", source = "MANUAL")
            insertTransactionAtV4(this, id = "uuid-import-2", source = "CSV_IMPORT")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        val idsByHash = migrated.query("SELECT id, content_hash FROM transactions ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
        val hash = idsByHash.first().second
        assertThat(idsByHash.map { it.first })
            .containsExactly("h1:$hash#0", "uuid-manual", "h1:$hash#1")
            .inOrder()
        // All three share a tuple, so all three share a hash -- the manual row
        // is excluded from the id scheme, not from the hash.
        assertThat(idsByHash.map { it.second }).containsExactly(hash, hash, hash)
    }

    /**
     * The half of MIGRATION_4_5 with a consequence that has nothing to do with
     * sync. `contentHashOf` moved off `String.hashCode()`, so a row that keeps
     * its old hash is a row the next import's `existingRowsByHash` cannot
     * match, and the user re-importing an overlapping statement gets the
     * overlap a second time.
     */
    @Test
    fun migrate4To5_recomputesContentHashForEveryRowIncludingManualOnes() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertTransactionAtV4(this, id = "uuid-manual", source = "MANUAL", contentHash = "-1234567")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        migrated.query("SELECT content_hash FROM transactions WHERE id = 'uuid-manual'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            // Pinned in :core:model's RowIdentityTest; repeated here so that a
            // migration reading the wrong columns -- date and description
            // swapped, say -- fails rather than agreeing with itself.
            assertThat(cursor.getString(0)).isEqualTo("e69daf8267b11c3689db7a3e6d95f3fb")
        }
    }

    /**
     * The version prefix earns its place here. An id from a scheme this build
     * has never heard of must survive untouched: re-keying it to `h1:` would
     * split that row from every other device holding it, which is exactly the
     * one-way door the prefix exists to keep open.
     */
    @Test
    fun migrate4To5_leavesAnIdFromAnUnknownSchemeVersionAlone() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertTransactionAtV4(this, id = "h2:deadbeef#0", source = "CSV_IMPORT")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        migrated.query("SELECT id FROM transactions").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("h2:deadbeef#0")
        }
    }

    /**
     * The case a blanket `UPDATE budgets SET id = ...` fails on. A deleted
     * budget and a recreated one legitimately share a natural key -- that is
     * what `findActive`'s `deleted_at IS NULL` is for -- so only one of them
     * can take it as a primary key. The live row wins; the tombstone keeps its
     * UUID rather than being deleted.
     */
    @Test
    fun migrate4To5_givesTheNaturalKeyToTheLiveBudgetWhenATombstoneSharesIt() {
        helper.createDatabase(TEST_DB, 4).apply {
            insertCategoryAtV4(this, id = "food")
            insertBudgetAtV4(this, id = "uuid-deleted", deletedAt = 500)
            insertBudgetAtV4(this, id = "uuid-live", deletedAt = null)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        val ids = migrated.query("SELECT id FROM budgets ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertThat(ids).containsExactly("budget:food:2026-08", "uuid-deleted")
    }

    @Test
    fun migrate4To5_givesTheNaturalKeyToTheNewestTombstoneWhenNoLiveBudgetHoldsIt() {
        // So that a budget deleted before this migration still propagates its
        // delete: the other device's live row has the natural-key id, and a
        // tombstone that kept a UUID would never reach it.
        helper.createDatabase(TEST_DB, 4).apply {
            insertCategoryAtV4(this, id = "food")
            insertBudgetAtV4(this, id = "uuid-older", deletedAt = 500)
            insertBudgetAtV4(this, id = "uuid-newer", deletedAt = 900)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        migrated.query("SELECT id FROM budgets WHERE deleted_at = 900").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("budget:food:2026-08")
        }
    }

    /**
     * The whole of MIGRATION_5_6's data behaviour: it invents nothing.
     *
     * An empty `sync_shadow` is the truthful state for a device that has never
     * synced, and it is not the same as a useless one -- docs/sync-design.md
     * §4.1's first-sync cases are written for exactly this. The failure this
     * guards against is the tempting one: seeding a base from the current row,
     * which would claim "this device has changed nothing since the remote last
     * saw it" for rows the remote has never seen at all, and hand every
     * differing field to the other device without recording that it did.
     */
    @Test
    fun migrate5To6_addsTheSyncTablesEmpty_inventingNoBaseForRowsThatPredateThem() {
        helper.createDatabase(TEST_DB, 5).apply {
            insertCategoryAtV4(this, id = "food")
            insertTransactionAtV4(this, id = "h1:abc#0", source = "CSV_IMPORT")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        assertThat(countOf(migrated, "transactions")).isEqualTo(1)
        assertThat(countOf(migrated, "sync_shadow")).isEqualTo(0)
        assertThat(countOf(migrated, "sync_conflicts")).isEqualTo(0)
    }

    /**
     * `runMigrationsAndValidate` covers the tables' shape. This covers that
     * the columns are usable in the order declared, and the one nullability
     * that carries meaning: a shadow row whose payload is NULL is a base that
     * says "deleted at this revision", which is a different fact from having
     * no base at all. A NOT NULL payload -- which §9 originally specified --
     * would make the two indistinguishable.
     */
    @Test
    fun migrate5To6_acceptsAShadowWhosePayloadIsNull_meaningDeletedAtThatRevision() {
        helper.createDatabase(TEST_DB, 5).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)
        migrated.execSQL(
            "INSERT INTO sync_shadow (table_name, row_id, remote_revision, payload) " +
                "VALUES ('transactions', 'h1:abc#0', 4, NULL)",
        )
        migrated.execSQL(
            """
            INSERT INTO sync_conflicts (
                id, table_name, row_id, field, resolved_at,
                chosen_value, discarded_value, reason, acknowledged_at
            ) VALUES ('c1', 'transactions', 'h1:abc#0', 'notes', 900, '"a"', '"b"', 'newest', NULL)
            """.trimIndent(),
        )

        migrated.query("SELECT payload FROM sync_shadow WHERE row_id = 'h1:abc#0'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }
        assertThat(countOf(migrated, "sync_conflicts")).isEqualTo(1)
    }

    /**
     * MIGRATION_6_7 gives `categories` the `updated_at` column every other
     * synced table already had (docs/sync-design.md §4.3's category gap).
     * Additive, same shape as migrate3To4's sync-columns test: the column
     * exists, and a pre-existing row gets `0` rather than a fabricated
     * "just touched" timestamp -- the DEFAULT the migration comment argues
     * for.
     */
    @Test
    fun migrate6To7_addsUpdatedAtToCategories_defaultingPreExistingRowsToZero() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO categories (
                    id, name, parent_id, color_argb, icon_key, is_system_defined,
                    local_revision, remote_revision, pending_operation, deleted_at
                ) VALUES ('user-hobbies', 'Hobbies', NULL, 0, 'palette', 0, 1, NULL, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7)

        migrated.query("SELECT updated_at FROM categories WHERE id = 'user-hobbies'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(0)
        }
    }

    private fun insertTransactionAtV4(
        db: SupportSQLiteDatabase,
        id: String,
        source: String,
        contentHash: String = "legacy-hash",
    ) = db.execSQL(
        """
        INSERT INTO transactions (
            id, amount_minor, currency_code, date, description, merchant, category_id,
            account_id, source, notes, category_locked_by_user, content_hash, import_batch_id,
            created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
        ) VALUES (
            '$id', -45000, 'INR', '2026-01-05', 'Coffee Shop', NULL, NULL,
            'acct-1', '$source', NULL, 0, '$contentHash', NULL,
            0, 0, 1, NULL, NULL, NULL
        )
        """.trimIndent(),
    )

    private fun insertCategoryAtV4(db: SupportSQLiteDatabase, id: String) = db.execSQL(
        """
        INSERT INTO categories (
            id, name, parent_id, color_argb, icon_key, is_system_defined,
            local_revision, remote_revision, pending_operation, deleted_at
        ) VALUES ('$id', '$id', NULL, 0, 'icon', 0, 1, NULL, NULL, NULL)
        """.trimIndent(),
    )

    private fun insertBudgetAtV4(db: SupportSQLiteDatabase, id: String, deletedAt: Long?) = db.execSQL(
        """
        INSERT INTO budgets (
            id, category_id, year_month, limit_minor, currency_code,
            created_at, updated_at, local_revision, remote_revision, pending_operation, deleted_at
        ) VALUES ('$id', 'food', '2026-08', 800000, 'INR', 0, 0, 1, NULL, NULL, ${deletedAt ?: "NULL"})
        """.trimIndent(),
    )

    private fun countOf(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 7
    }
}
