import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.bahi.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

baselineProfile {
    // The generator drives the physical device, same as the measurement
    // (docs/baseline-profile-design.md §1); no managed emulator.
    useConnectedDevices = true
}

android {
    namespace = "dev.charanjeev.bahi.benchmark"

    // A com.android.test module reaches :app through this path, not through a
    // ProjectDependency, so the root build's moduleEdges() reads it from here
    // (docs/baseline-profile-design.md §5).
    targetProjectPath = ":app"

    buildTypes {
        // Pairs with :app's `benchmark` build type. The test APK itself is
        // debuggable (it has to be instrumented); the *target* is not.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The benchmark drives :app from a separate process; it never shares its
    // instrumentation with the target.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}

// ---------------------------------------------------------------------------
// ./gradlew :benchmark:checkBenchmarkData  (docs/baseline-profile-design.md §6)
//
// Run after a benchmark run. A ReportDrawnWhen that never fires does not fail
// the test: timeToFullDisplayMs simply vanishes from the output while the run
// passes, and against benchmark-macro 1.5.0 nothing inside the test can see
// the metrics (measureRepeated returns Unit, Metric is sealed). So the test
// alone cannot catch this and the check around it can; that gap is permanent.
//
// Stale output must not count, and nobody should have to remember to run this.
// The benchmark's connected task (a) deletes its own output directory before it
// runs and (b) is finalized by this check, so every run that reaches the task is
// judged on that run's data and nothing else's. A run that produces no JSON (it
// failed early, or the filter matched nothing) leaves the directory empty, and an
// empty directory FAILS the check; it is never skipped. A build that dies before
// the task starts never runs the finalizer, and is red on its own account.
//
// Two earlier versions, both wrong: reading every file under the outputs
// directory and relying on someone clearing it by hand; then deleting inside
// the task alone, which does nothing when the build fails first (a stale file
// that passed then passed again).
// ---------------------------------------------------------------------------
val benchmarkTestTaskName = "connectedBenchmarkBenchmarkAndroidTest"

// Plain File, resolved now: a task action that reaches back into the build
// script's own state is not configuration-cache safe.
val benchmarkOutputDir = layout.buildDirectory
    .dir("outputs/connected_android_test_additional_output/benchmarkBenchmark").get().asFile

tasks.matching { it.name == benchmarkTestTaskName }.configureEach {
    val staleOutput = benchmarkOutputDir
    doFirst { staleOutput.deleteRecursively() }
    finalizedBy("checkBenchmarkData")
}

// Both of the above are attached by name. If a build-type change renames the
// task, the match silently finds nothing and stale data counts again, which is
// the failure this exists to prevent. So a rename fails configuration instead.
afterEvaluate {
    check(benchmarkTestTaskName in tasks.names) {
        "$benchmarkTestTaskName no longer exists; checkBenchmarkData's cleanup and " +
            "finalizer are attached to it by name. Update benchmarkTestTaskName."
    }
}

tasks.register("checkBenchmarkData") {
    group = "verification"
    description = "Fails if the last benchmark run left no benchmarkData.json, or any benchmark in it lacks timeToFullDisplayMs. Runs automatically after the benchmark task."
    val dir = benchmarkOutputDir
    val testTask = benchmarkTestTaskName
    doLast {
        val files = dir.walkTopDown().filter { it.name.endsWith("benchmarkData.json") }.toList()
        check(files.isNotEmpty()) { "No benchmarkData.json under $dir: the last $testTask run produced none." }
        val problems = files.flatMap { file ->
            val root = JsonSlurper().parse(file) as Map<*, *>
            (root["benchmarks"] as List<*>).mapNotNull { entry ->
                val benchmark = entry as Map<*, *>
                val metrics = benchmark["metrics"] as Map<*, *>
                if ("timeToFullDisplayMs" in metrics) null else "${benchmark["name"]}: timeToFullDisplayMs is absent (has ${metrics.keys})"
            }
        }
        check(problems.isEmpty()) { "Benchmark data check failed:\n  " + problems.joinToString("\n  ") }
        println("Benchmark data OK: ${files.size} file(s), timeToFullDisplayMs present in every benchmark")
    }
}
