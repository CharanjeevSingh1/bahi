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

    testImplementation(projects.core.testing)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
}
