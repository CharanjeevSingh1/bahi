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

    private fun countOf(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 3
    }
}
