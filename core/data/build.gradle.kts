plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
}

android {
    namespace = "dev.charanjeev.bahi.core.data"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    // For BahiDatabase and BahiDatabase.withTransaction -- SyncApplier's
    // per-batch transaction (docs/sync-design.md §6.2). Both are already
    // dependencies of :core:database via the Room convention plugin; needed
    // here directly because `implementation` dependencies of a dependency
    // aren't visible to this module's own code.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(projects.core.testing)
    testImplementation(libs.truth)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
