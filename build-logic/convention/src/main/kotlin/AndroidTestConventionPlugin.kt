import com.android.build.api.dsl.TestExtension
import dev.charanjeev.bahi.configureKotlinAndroid
import dev.charanjeev.bahi.intVersion
import dev.charanjeev.bahi.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

// For `com.android.test` modules (:benchmark). The module names its target in its
// own build script via `targetProjectPath`; everything else is shared SDK and
// Kotlin configuration, same as the other convention plugins.
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.test")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<TestExtension> {
            configureKotlinAndroid(this)

            defaultConfig {
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }
}
