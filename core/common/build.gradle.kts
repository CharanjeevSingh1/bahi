plugins {
    alias(libs.plugins.bahi.jvm.library)
    alias(libs.plugins.bahi.hilt)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:model"))
}
