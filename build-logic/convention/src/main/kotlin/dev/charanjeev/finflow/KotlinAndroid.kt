package dev.charanjeev.finflow

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Single place where compile SDK, min SDK, Java version and compiler flags are
 * set for every Android module. Changing the JVM target here changes it for all
 * 15 modules -- which is the entire reason convention plugins exist.
 */
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = libs.intVersion("compileSdk")

        defaultConfig {
            minSdk = libs.intVersion("minSdk")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        packaging {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(warningsAsErrors())
            freeCompilerArgs.addAll(sharedCompilerArgs)
            freeCompilerArgs.addAll(provider { coroutinesOptIns() })
        }
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(warningsAsErrors())
            freeCompilerArgs.addAll(sharedCompilerArgs)
            freeCompilerArgs.addAll(provider { coroutinesOptIns() })
        }
    }
}

/**
 * Opt-in surface used across the codebase, declared once so individual files
 * don't need @OptIn annotations scattered through them.
 */
private val sharedCompilerArgs = listOf(
    "-opt-in=kotlin.RequiresOptIn",
)

/** CI passes -PwarningsAsErrors=true; local builds stay forgiving. */
private fun Project.warningsAsErrors(): Boolean =
    providers.gradleProperty("warningsAsErrors").orNull.toBoolean()

/**
 * `kotlinx.coroutines.ExperimentalCoroutinesApi` gates real APIs (e.g.
 * `setMain`/`resetMain` in :core:testing), so the opt-in can't just be dropped
 * for everyone -- but it also shouldn't be forced on modules that never touch
 * coroutines (:core:model). Wrapped in `provider { }` above rather than called
 * directly: this function reads the module's own "api"/"implementation"
 * dependencies, which its build.gradle.kts hasn't declared yet at the point
 * configureKotlinAndroid/configureKotlinJvm run -- they're invoked from a
 * convention plugin's apply(), before the consuming script's dependencies {}
 * block executes. A Provider defers the read until the property's value is
 * actually needed, by which point the whole script has run.
 */
private fun Project.coroutinesOptIns(): List<String> =
    if (usesCoroutines()) listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi") else emptyList()

private fun Project.usesCoroutines(): Boolean {
    val coroutinesArtifacts = listOf(
        "kotlinx-coroutines-core",
        "kotlinx-coroutines-android",
        "kotlinx-coroutines-test",
    ).map { libs.findLibrary(it).get().get() }

    return listOf("api", "implementation").any { configName ->
        configurations.findByName(configName)?.dependencies.orEmpty().any { dependency ->
            coroutinesArtifacts.any { it.group == dependency.group && it.name == dependency.name }
        }
    }
}
