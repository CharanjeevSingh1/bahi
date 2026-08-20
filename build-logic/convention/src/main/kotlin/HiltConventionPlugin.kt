import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.devtools.ksp")
        }

        dependencies {
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }

        // Only Android modules get the Hilt Gradle plugin; a pure-JVM module that
        // needs Dagger annotations still gets the compiler above, plus the plain
        // JSR-330 annotations that hilt-android would otherwise supply -- without
        // pulling in an Android dependency.
        pluginManager.withPlugin("org.jetbrains.kotlin.android") {
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
            }
        }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            dependencies {
                add("api", libs.findLibrary("javax-inject").get())
            }
        }
    }
}
