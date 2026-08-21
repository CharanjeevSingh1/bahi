import com.android.build.api.dsl.LibraryExtension
import dev.charanjeev.bahi.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * For library modules that render UI but aren't features -- :core:designsystem
 * and :core:ui. Without this they'd apply the Compose compiler plugin but never
 * flip buildFeatures.compose, which fails in a confusing way.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("bahi.android.library")
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        extensions.configure<LibraryExtension> {
            configureAndroidCompose(this)
            buildFeatures {
                androidResources = true
            }
        }
    }
}
