package dev.charanjeev.bahi.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/main/baseline-prof.txt (docs/baseline-profile-design.md §3):
 * cold start, the transactions list scrolled once, budgets, insights. Settings
 * and import are deliberately outside the journey.
 *
 * Runs against the same populated state as the populated benchmark. §3 wanted
 * that state seeded before collect(), and that cannot work on this device:
 * collect() reinstalls the target on its first iteration (API 33, not rooted:
 * CompilationMode.resetAndCompile's "User builds pre-U" branch), which wipes
 * the database. So the seed runs inside iteration 1, after the reinstall and
 * before the journey, and iterations 2.. reuse it. That puts the import flow
 * in the target process's profile; app/build.gradle.kts filters it back out.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(packageName = PopulatedState.TARGET_PACKAGE) {
            if (iteration == 1) {
                PopulatedState.seed()
                killProcess()
            }
            pressHome()
            startActivityAndWait()

            // A populated list, not an empty one that happens to be drawn.
            check(device.wait(Until.hasObject(By.textContains(PopulatedState.ROW_MARKER)), WAIT_MS)) {
                "Transactions list is empty: the generator would record the empty-state path. " +
                    "On screen: " + device.findObjects(By.pkg(PopulatedState.TARGET_PACKAGE)).mapNotNull { it.text }
            }
            device.findObject(By.scrollable(true)).apply {
                setGestureMargin(device.displayWidth / 5)
                fling(Direction.DOWN)
            }
            device.waitForIdle()

            device.findObject(By.text("Budgets")).click()
            device.waitForIdle()
            device.findObject(By.text("Insights")).click()
            device.waitForIdle()
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
