plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.charanjeev.bahi.core.datastore"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)
}
