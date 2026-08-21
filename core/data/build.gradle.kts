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

    testImplementation(projects.core.testing)
    testImplementation(libs.truth)
}
