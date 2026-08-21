plugins {
    alias(libs.plugins.bahi.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
}
