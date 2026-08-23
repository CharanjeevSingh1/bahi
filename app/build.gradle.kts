plugins {
    alias(libs.plugins.bahi.android.application)
    alias(libs.plugins.bahi.hilt)
}

android {
    namespace = "dev.charanjeev.bahi"

    defaultConfig {
        applicationId = "dev.charanjeev.bahi"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.sync)

    implementation(projects.feature.import)
    implementation(projects.feature.transactions)
    implementation(projects.feature.budgets)
    implementation(projects.feature.insights)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.espresso.core)
}
