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

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
    )
}
