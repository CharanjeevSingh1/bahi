plugins {
    alias(libs.plugins.bahi.android.library.compose)
}

android {
    namespace = "dev.charanjeev.bahi.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
