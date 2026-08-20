plugins {
    alias(libs.plugins.finflow.android.library)
    alias(libs.plugins.finflow.hilt)
}

android {
    namespace = "dev.charanjeev.finflow.core.data"
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
