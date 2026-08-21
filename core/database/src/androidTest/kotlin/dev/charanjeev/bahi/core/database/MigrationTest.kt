package dev.charanjeev.bahi.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST_VERSION = 1
    }
}
