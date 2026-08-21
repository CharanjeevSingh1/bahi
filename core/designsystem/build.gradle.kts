plugins {
    alias(libs.plugins.bahi.android.library.compose)
}

android {
    namespace = "dev.charanjeev.bahi.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)
}
