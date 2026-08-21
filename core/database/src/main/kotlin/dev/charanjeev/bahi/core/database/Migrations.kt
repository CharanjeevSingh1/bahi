package dev.charanjeev.bahi.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every migration here has a matching test in MigrationTest. When you bump the
 * @Database version, add the migration, add the test, and commit the generated
 * schema JSON in the same PR -- CI checks for all three.
 */
object Migrations {

    val ALL: Array<Migration> = arrayOf(
        // MIGRATION_1_2,
    )

    /**
     * Example of the shape a real migration takes. Left commented until the
     * schema actually changes, so the array above stays honest.
     *
     * val MIGRATION_1_2 = object : Migration(1, 2) {
     *     override fun migrate(db: SupportSQLiteDatabase) {
     *         db.execSQL("ALTER TABLE transactions ADD COLUMN tags TEXT")
     *     }
     * }
     */
}
