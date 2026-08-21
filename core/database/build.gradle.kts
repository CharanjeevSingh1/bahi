plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.android.room)
    alias(libs.plugins.bahi.hilt)
}

android {
    namespace = "dev.charanjeev.bahi.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
