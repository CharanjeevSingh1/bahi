plugins {
    alias(libs.plugins.finflow.android.library)
    alias(libs.plugins.finflow.android.room)
    alias(libs.plugins.finflow.hilt)
}

android {
    namespace = "dev.charanjeev.finflow.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
