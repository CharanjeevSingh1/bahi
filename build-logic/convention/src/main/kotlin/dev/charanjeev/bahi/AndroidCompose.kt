package dev.charanjeev.bahi

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose setup shared by :app, :core:ui, :core:designsystem and every feature.
 * Also wires the Compose compiler metrics tasks -- run with
 *   ./gradlew assembleRelease -PenableComposeCompilerMetrics=true
 * to get stability reports you can screenshot for the README performance section.
 */
internal fun Project.configureAndroidCompose(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        buildFeatures {
            compose = true
        }

        dependencies {
            val bom = libs.findLibrary("androidx-compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))

            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())

            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())

            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
        }
    }

    extensions.configure(org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension::class.java) {
        val enableMetrics = providers.gradleProperty("enableComposeCompilerMetrics").orNull.toBoolean()
        if (enableMetrics) {
            val dir = layout.buildDirectory.dir("compose-metrics")
            metricsDestination.set(dir)
            reportsDestination.set(dir)
        }
    }
}
