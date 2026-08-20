import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Everything a :feature module needs, in one line of its build file.
 * Note what is deliberately absent: no other feature module, and no direct
 * :core:database access. Features talk to repositories, never to DAOs.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("finflow.android.library.compose")
            apply("finflow.hilt")
        }

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:common"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))

            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
            add("implementation", libs.findLibrary("kotlinx-collections-immutable").get())

            add("testImplementation", project(":core:testing"))
            add("androidTestImplementation", project(":core:testing"))
        }
    }
}
