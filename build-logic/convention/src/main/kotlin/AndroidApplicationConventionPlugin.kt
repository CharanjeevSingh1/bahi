import com.android.build.api.dsl.ApplicationExtension
import dev.charanjeev.finflow.configureAndroidCompose
import dev.charanjeev.finflow.configureKotlinAndroid
import dev.charanjeev.finflow.intVersion
import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.android")
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureAndroidCompose(this)

            defaultConfig {
                targetSdk = libs.intVersion("targetSdk")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    isMinifyEnabled = false
                }
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                    // Debug signing so `assembleRelease` works on a fresh clone and in
                    // CI. Swap for a real keystore before publishing.
                    signingConfig = signingConfigs.getByName("debug")
                }
            }

            testOptions {
                unitTests {
                    isIncludeAndroidResources = true
                    isReturnDefaultValues = true
                }
            }
        }
    }
}
