plugins {
    alias(libs.plugins.finflow.android.library.compose)
}

android {
    namespace = "dev.charanjeev.finflow.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)
}
