plugins {
    alias(libs.plugins.bahi.android.library)
    alias(libs.plugins.bahi.hilt)
}

android {
    namespace = "dev.charanjeev.bahi.core.importer"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
}
