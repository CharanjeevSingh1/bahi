import dev.charanjeev.finflow.configureKotlinJvm
import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure-Kotlin modules (:core:model, :core:common). No Android dependency at all,
 * so their unit tests run in milliseconds and the layering boundary is enforced
 * by the compiler rather than by convention.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("org.jetbrains.kotlin.jvm")
        }

        configureKotlinJvm()

        dependencies {
            // sharedCompilerArgs opts every module into
            // kotlinx.coroutines.ExperimentalCoroutinesApi; compileOnly here just
            // makes the marker annotation resolvable for JVM modules (like
            // core:model) that don't otherwise depend on coroutines. Without it
            // the compiler emits an unresolved-opt-in warning that CI's
            // -PwarningsAsErrors turns into a build failure.
            add("compileOnly", libs.findLibrary("kotlinx-coroutines-core").get())
            add("testImplementation", libs.findLibrary("junit4").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
