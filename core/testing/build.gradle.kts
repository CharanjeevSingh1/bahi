plugins {
    alias(libs.plugins.finflow.android.library)
    alias(libs.plugins.finflow.hilt)
}

android {
    namespace = "dev.charanjeev.finflow.core.testing"
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
