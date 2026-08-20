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
        // needs Dagger annotations still gets the compiler above.
        pluginManager.withPlugin("org.jetbrains.kotlin.android") {
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
            }
        }
    }
}
