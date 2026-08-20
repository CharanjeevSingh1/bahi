plugins {
    alias(libs.plugins.finflow.jvm.library)
    alias(libs.plugins.finflow.hilt)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:model"))
}
