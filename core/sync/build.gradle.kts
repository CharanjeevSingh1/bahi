plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.charanjeev.bahi.core.sync"
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
