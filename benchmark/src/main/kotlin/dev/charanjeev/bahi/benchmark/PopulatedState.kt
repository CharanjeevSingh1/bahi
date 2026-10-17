package dev.charanjeev.bahi.benchmark

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.time.LocalDate
import java.util.Random

/**
 * The populated database (docs/baseline-profile-design.md §2): the app's own CSV
 * importer, driven through the system file picker, with a fixture this module
 * generates and writes itself. Nothing here needs a hook in :app.
 *
 * Callers run [seed] once, before measurement or generation starts. It wipes
 * the app's data first, so the row count it asserts is exact rather than
 * "at least", and a silently empty or doubled state fails here.
 */
internal object PopulatedState {
    const val TARGET_PACKAGE = "dev.charanjeev.bahi"
    const val ROW_COUNT = 300

    private const val UI_TIMEOUT_MS = 10_000L

    // Descriptions carry " #<n>" so the generator can tell a populated list from
    // an empty one by text alone, and so no two rows collide on the importer's
    // duplicate check.
    const val ROW_MARKER = " #"

    fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext

        device.executeShellCommand("pm clear $TARGET_PACKAGE")
        // Unique per run: MediaStore renames an insert that collides with a file
        // on disk ("name (1).csv") and the picker is then driven by the wrong
        // text. Found when a stale file from a manual probe did exactly that.
        val fixtureName = "bahi-seed-${System.currentTimeMillis()}.csv"
        val fixtureUri = writeFixtureToDownloads(context, fixtureName)

        // Not startActivityAndWait: that is a MacrobenchmarkScope member, and
        // this runs outside any measured block.
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)) {
            "$TARGET_PACKAGE is not installed"
        }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

        device.clickWhenPresent(By.desc("Import from CSV"))
        device.clickWhenPresent(By.text("Choose CSV file"))
        // The picker opens on Recent, where a MediaStore-indexed file is the
        // newest entry. (A file placed with plain `adb push` is not indexed and
        // is invisible there; that is why the fixture goes through MediaStore.)
        device.clickWhenPresent(By.text(fixtureName))

        check(device.wait(Until.hasObject(By.text("$ROW_COUNT transactions found")), UI_TIMEOUT_MS)) {
            "Import preview did not report $ROW_COUNT transactions found"
        }
        device.clickWhenPresent(By.text("Import"))
        check(device.wait(Until.hasObject(By.text("$ROW_COUNT new transactions")), UI_TIMEOUT_MS)) {
            "Import result did not report $ROW_COUNT new transactions"
        }
        device.clickWhenPresent(By.text("Done"))
        check(device.wait(Until.hasObject(By.textContains(ROW_MARKER)), UI_TIMEOUT_MS)) {
            "Transactions list shows no imported row after Done"
        }
        context.contentResolver.delete(fixtureUri, null, null)
        // Leave nothing foregrounded for the caller to inherit.
        device.pressHome()
    }

    private fun UiDevice.clickWhenPresent(selector: BySelector) {
        val node = checkNotNull(wait(Until.findObject(selector), UI_TIMEOUT_MS)) {
            "Timed out waiting for $selector"
        }
        node.click()
    }

    private fun writeFixtureToDownloads(context: Context, name: String): Uri {
        val resolver = context.contentResolver
        // Needs no storage permission on API 29+ for files this app creates.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "MediaStore refused the fixture insert"
        }
        resolver.openOutputStream(uri)!!.use { it.write(fixtureCsv().toByteArray()) }
        return uri
    }

    /**
     * Three rows a day ending today, so the current month has data for budgets
     * and insights. Seeded Random: the same fixture on every run.
     */
    private fun fixtureCsv(): String {
        val random = Random(SEED)
        val today = LocalDate.now()
        return buildString {
            appendLine("Date,Description,Amount")
            repeat(ROW_COUNT) { i ->
                val date = today.minusDays((i / 3).toLong())
                val merchant = MERCHANTS[random.nextInt(MERCHANTS.size)]
                val rupees = 50 + random.nextInt(4_950)
                appendLine("$date,$merchant$ROW_MARKER$i,-$rupees.${"%02d".format(i % 100)}")
            }
        }
    }

    private const val SEED = 1L
    private val MERCHANTS = listOf(
        "BLUE TOKAI COFFEE", "UBER TRIP", "BIGBASKET GROCERIES", "NETFLIX", "ELECTRICITY BILL",
        "SWIGGY ORDER", "AMAZON PAY", "ZOMATO", "METRO RECHARGE", "PHARMEASY",
    )
}
