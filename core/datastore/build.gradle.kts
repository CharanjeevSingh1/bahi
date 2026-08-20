plugins {
    alias(libs.plugins.finflow.android.library)
    alias(libs.plugins.finflow.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.charanjeev.finflow.core.datastore"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.serialization.json)
}
