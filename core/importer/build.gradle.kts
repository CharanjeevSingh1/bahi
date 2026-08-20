plugins {
    alias(libs.plugins.finflow.android.library)
    alias(libs.plugins.finflow.hilt)
}

android {
    namespace = "dev.charanjeev.finflow.core.importer"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}
