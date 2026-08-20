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
        }
    }
}

/**
 * Opt-in surface used across the codebase, declared once so individual files
 * don't need @OptIn annotations scattered through them.
 */
private val sharedCompilerArgs = listOf(
    "-opt-in=kotlin.RequiresOptIn",
    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
)

/** CI passes -PwarningsAsErrors=true; local builds stay forgiving. */
private fun Project.warningsAsErrors(): Boolean =
    providers.gradleProperty("warningsAsErrors").orNull.toBoolean()
