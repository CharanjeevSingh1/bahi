plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
}

android {
    namespace = "dev.charanjeev.bahi.core.testing"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.junit4)
    api(libs.truth)
    api(libs.turbine)
    api(libs.kotlinx.coroutines.test)
    api(libs.androidx.test.ext.junit)
    api(libs.hilt.android.testing)
}
