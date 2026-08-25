package dev.charanjeev.bahi.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
    )
}
