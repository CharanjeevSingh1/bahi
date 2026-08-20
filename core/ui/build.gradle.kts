plugins {
    alias(libs.plugins.finflow.android.library.compose)
}

android {
    namespace = "dev.charanjeev.finflow.core.ui"
}

dependencies {
    api(projects.core.designsystem)
    api(projects.core.model)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
