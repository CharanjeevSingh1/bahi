import com.android.build.api.dsl.LibraryExtension
import dev.charanjeev.finflow.configureKotlinAndroid
import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)

            defaultConfig {
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            testOptions {
                unitTests {
                    isIncludeAndroidResources = true
                    isReturnDefaultValues = true
                }
            }

            // Library modules never ship BuildConfig -- keeps incremental builds fast.
            buildFeatures {
                androidResources = false
                buildConfig = false
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("turbine").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
