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

    testImplementation(projects.core.testing)
}
