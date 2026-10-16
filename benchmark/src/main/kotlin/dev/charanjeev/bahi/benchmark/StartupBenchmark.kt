package dev.charanjeev.bahi.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start on a fresh install: an empty database, no profile, nothing
 * precompiled. This is the worst case and not the README's headline; it is the
 * first measurement that proves the harness runs and reports both
 * timeToInitialDisplayMs and timeToFullDisplayMs (docs/baseline-profile-design.md §3).
 *
 * Only [CompilationMode.None] exists in this slice. A Partial run with
 * BaselineProfileMode.Require would correctly fail until a profile is committed.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone_emptyDatabase() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "dev.charanjeev.bahi"

        // §1: at least 10 per mode.
        const val ITERATIONS = 10
    }
}
