import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.bahi.android.application)
    alias(libs.plugins.bahi.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

val releaseLikeSourceSets = setOf("benchmark", "nonMinifiedRelease", "benchmarkRelease")

android {
    namespace = "dev.charanjeev.bahi"

    // Off everywhere else (gradle.properties, AndroidLibraryConventionPlugin) to
    // keep incremental builds fast on modules that don't need it. :app does: it's
    // the only module that can read `sync.properties` and turn its presence into
    // something `:core:sync`'s SyncConfiguration seam can consume at runtime
    // (docs/sync-setup.md, docs/sync-design.md §8.5, D12).
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.charanjeev.bahi"
        versionCode = 1
        versionName = "0.1.0"

        // `sync.properties` is gitignored, same as `local.properties` -- its only
        // job right now is to exist or not. Nothing reads a value out of it yet;
        // M4b slice 9d is what gives it a real key to hold (docs/sync-setup.md).
        buildConfigField("boolean", "SYNC_CONFIGURED", rootProject.file("sync.properties").exists().toString())
    }

    buildTypes {
        // What :benchmark measures. Copies release wholesale -- R8, resource
        // shrinking, debug signing -- so the numbers are for the app that ships.
        // Not debuggable: Macrobenchmark refuses a debuggable target, which is
        // the point (docs/baseline-profile-design.md §4, §6).
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = false
        }
    }

    // A custom build type gets no src/<name> of its own, and release's
    // DebugSeeder no-op lives in src/release; without this the benchmark
    // variant has no DebugSeeder at all. Sharing the directory (rather than
    // copying the file) keeps the two from drifting.
    //
    // The baselineprofile plugin adds `nonMinifiedRelease` and `benchmarkRelease`
    // build types of its own, created after this block runs, hence configureEach
    // rather than getByName.
    sourceSets.configureEach {
        if (name in releaseLikeSourceSets) java.srcDir("src/release/kotlin")
    }

    // The plugin wires its generated profile into the variants it creates, not
    // into this one, so without this the benchmark APK carries only the
    // libraries' profiles (docs/baseline-profile-design.md §4).
    sourceSets.getByName("benchmark").baselineProfiles.srcDir("src/main/generated/baselineProfiles")
}

baselineProfile {
    // The plugin's default location, src/main/generated/baselineProfiles/. Only
    // the variants the plugin creates read it on their own; the custom
    // `benchmark` build type is wired to it by hand in the sourceSets block
    // below (§4).
    //
    // Not `baselineProfileOutputDir = "."`, which would put the file at
    // src/main/baseline-prof.txt where every variant finds it. Tried and
    // reverted: the plugin then feeds the whole of src/main/ to the release
    // variant's profile merge, which swallows res/ XML and fails
    // expandReleaseArtProfileWildcards ("Illegal token '<'").
    mergeIntoMain = true
    // The generator has to import a CSV to populate the database (it cannot be
    // seeded before collect(); see BaselineProfileGenerator), which would put the
    // import path in the profile. Import is outside the §3 journey, and every
    // rule costs install-time work.
    filter {
        exclude("dev.charanjeev.bahi.feature.csvimport.**")
        exclude("dev.charanjeev.bahi.core.importer.**")
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.sync)

    implementation(projects.feature.import)
    implementation(projects.feature.transactions)
    implementation(projects.feature.budgets)
    implementation(projects.feature.insights)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Already on the classpath transitively (activity, compose-ui, lifecycle);
    // declared so the ProfileVerifier check in M6 asserts against a pinned
    // version and survives an unrelated library dropping it.
    implementation(libs.androidx.profileinstaller)

    // BahiApplication is the only place that implements Configuration.Provider
    // (docs/sync-design.md §8.7, §13 slice 9g) -- it needs both artifacts on
    // its own classpath even though :core:sync (SyncWorker) already depends
    // on them, because a HiltWorkerFactory has to be handed to WorkManager
    // from application code, not from a library module.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    // Consumer side of the generator in :benchmark. The `baselineProfile`
    // configuration is not one moduleEdges() reads, so this is not a graph edge.
    baselineProfile(projects.benchmark)

    testImplementation(projects.core.testing)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
}

// ---------------------------------------------------------------------------
// ./gradlew :app:checkBaselineProfile  (docs/baseline-profile-design.md §6)
//
// A profile that is missing, empty or not packaged all still produce an APK
// that builds and runs; nothing downstream notices. Two floors, because they
// fail differently:
//   - the rules AGP merged for the `benchmark` variant must include app-class
//     rules. This reads the variant's own merged profile, not the committed
//     file: the first version of this check read src/ and passed while the
//     variant ignored it (the file sat in a directory only the plugin's own
//     variants read), with the APK's baseline.prof coming from AndroidX
//     libraries alone,
//   - the APK must contain a non-trivial assets/dexopt/baseline.prof. Size
//     cannot tell "libraries only" (5307 bytes) from "libraries plus this app"
//     (6555), so it is a presence check, not a rule count; the rule count is
//     the merged-profile check above.
// ---------------------------------------------------------------------------
val mergedBenchmarkProfile = layout.buildDirectory.file(
    "intermediates/r8_art_profile/benchmark/expandBenchmarkArtProfileWildcards/baseline-prof.txt",
)
val benchmarkApk = layout.buildDirectory.file("outputs/apk/benchmark/app-benchmark.apk")

tasks.register("checkBaselineProfile") {
    group = "verification"
    description = "Fails if the benchmark APK lacks a baseline profile or the variant merged too few app rules."
    dependsOn("assembleBenchmark")
    val merged = mergedBenchmarkProfile
    val apkFile = benchmarkApk
    doLast {
        val minAppRules = 500
        val minApkProfileBytes = 2_000L

        val mergedFile = merged.get().asFile
        val apk = apkFile.get().asFile
        val appRules = if (mergedFile.exists()) {
            mergedFile.useLines { lines -> lines.count { "Ldev/charanjeev/bahi/" in it } }
        } else {
            0
        }
        val entry = ZipFile(apk).use { zip -> zip.getEntry("assets/dexopt/baseline.prof")?.size }
        val problems = buildList {
            if (appRules < minAppRules) add("benchmark variant merged $appRules app-class rules, floor is $minAppRules")
            if (entry == null) add("assets/dexopt/baseline.prof is not in ${apk.name}")
            else if (entry < minApkProfileBytes) add("assets/dexopt/baseline.prof is $entry bytes, floor is $minApkProfileBytes")
        }
        check(problems.isEmpty()) { "Baseline profile check failed:\n  " + problems.joinToString("\n  ") }
        println("Baseline profile OK: $appRules app-class rules merged, baseline.prof $entry bytes in the APK")
    }
}
