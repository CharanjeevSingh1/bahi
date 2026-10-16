plugins {
    alias(libs.plugins.bahi.android.application)
    alias(libs.plugins.bahi.hilt)
}

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
    sourceSets {
        getByName("benchmark").java.srcDir("src/release/kotlin")
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

    testImplementation(projects.core.testing)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
}
