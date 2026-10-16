plugins {
    alias(libs.plugins.bahi.android.test)
}

android {
    namespace = "dev.charanjeev.bahi.benchmark"

    // A com.android.test module reaches :app through this path, not through a
    // ProjectDependency, so the root build's moduleEdges() reads it from here
    // (docs/baseline-profile-design.md §5).
    targetProjectPath = ":app"

    buildTypes {
        // Pairs with :app's `benchmark` build type. The test APK itself is
        // debuggable (it has to be instrumented); the *target* is not.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The benchmark drives :app from a separate process; it never shares its
    // instrumentation with the target.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}
