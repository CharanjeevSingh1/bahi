plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.charanjeev.bahi.core.sync"

    // `DriveTransportContractTest` (docs/sync-design.md §10.5) needs a real,
    // already-authorized Drive account -- nothing CI has. `src/driveTest/`
    // is only ever added as a source directory when `drive-test.properties`
    // (gitignored: OAuth client id/secret and a refresh token for a
    // throwaway account) exists, so on every clone and every CI run this
    // module compiles exactly as if that directory didn't exist -- not
    // merely "the task isn't run", the sources are never even seen. AGP has
    // no notion of a custom-named test source set (it wires tasks only for
    // build-type/variant-shaped names), so rather than fight that, this adds
    // the directory to the existing `test` source set and relies on the
    // `driveTest`/`testDebugUnitTest` task split below to keep it out of
    // routine unit test runs even on a machine that does have the file.
    sourceSets.getByName("test") {
        if (file("drive-test.properties").exists()) {
            java.srcDir("src/driveTest/kotlin")
        }
    }

    defaultConfig {
        // ConvergencePropertyTest's CI-vs-nightly split (docs/sync-design.md
        // §10.3, §13 slice 7): `./gradlew connectedDebugAndroidTest` runs the
        // default 50 seeds; `.github/workflows/nightly.yml` passes
        // `-PseedCount=1000` for the larger corpus. Only ever read if a test
        // asks InstrumentationRegistry for it, so this line is a no-op for
        // every other instrumented test in this module.
        testInstrumentationRunnerArguments["seedCount"] = (project.findProperty("seedCount") as String?) ?: "50"
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.datastore)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)

    testImplementation(projects.core.testing)

    // The two-device convergence suite (docs/sync-design.md §10.1) needs a
    // real BahiDatabase per simulated device -- SyncEngine's own compile
    // dependencies stop at :core:data, which keeps :core:database off this
    // module's *production* classpath, but the harness that proves two
    // engines converge has to construct real Room instances, so androidTest
    // adds it directly. This is androidTest-only: it does not appear in
    // moduleEdges() (root build.gradle.kts only inspects implementation/api/
    // compileOnly), so it changes neither the module graph nor
    // checkModuleBoundaries.
    androidTestImplementation(projects.core.database)
    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}

// ---------------------------------------------------------------------------
// ./gradlew :core:sync:driveTest
//
// DriveTransportContractTest, run against a real Drive account
// (docs/sync-design.md §10.5). It reuses testDebugUnitTest's own classpath
// and compiled output rather than a from-scratch compile task -- that
// classpath already carries this module's main and test output, every
// project dependency's compiled classes, and every testImplementation
// library, which is everything driveTest sources need too. Registered in
// afterEvaluate because AGP adds testDebugUnitTest to the task container
// during variant configuration, after this script's own top-level code runs.
//
// The exclude filter on testDebugUnitTest matters even though CI never has
// drive-test.properties: a developer's machine that *does* have it would
// otherwise run DriveTransportContractTest -- a real network call against a
// real account -- on every routine `./gradlew unitTests`, which is exactly
// what living outside `test`/`androidTest` was supposed to prevent.
// ---------------------------------------------------------------------------
afterEvaluate {
    val driveTestPattern = "dev.charanjeev.bahi.core.sync.drivetest.*"
    // Captured as a plain File at configuration time, not called again inside
    // doFirst -- Task.file(...) resolves through the Project, and a Project
    // reference can't be stored by the configuration cache.
    val credentialsFile = file("drive-test.properties")

    tasks.register<Test>("driveTest") {
        group = "verification"
        description = "Runs DriveTransportContractTest against a real Drive account (docs/sync-design.md §10.5)"
        val debugUnitTest = tasks.named<Test>("testDebugUnitTest")
        dependsOn(debugUnitTest)
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        filter { includeTestsMatching(driveTestPattern) }
        outputs.upToDateWhen { false } // hits a real, possibly-changed remote account every time
        doFirst {
            if (!credentialsFile.exists()) {
                throw GradleException(
                    "core/sync/drive-test.properties is missing -- see docs/sync-manual-test-plan.md for what it needs to contain.",
                )
            }
        }
    }

    tasks.named<Test>("testDebugUnitTest") {
        filter { excludeTestsMatching(driveTestPattern) }
    }
}
