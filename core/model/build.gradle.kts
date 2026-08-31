plugins {
    alias(libs.plugins.bahi.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.datetime)
    // api, not implementation: JsonObject is in SyncOp's public signature.
    api(libs.kotlinx.serialization.json)
}
