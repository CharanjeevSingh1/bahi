import com.android.build.api.dsl.ApplicationExtension
import dev.charanjeev.bahi.configureAndroidCompose
import dev.charanjeev.bahi.configureKotlinAndroid
import dev.charanjeev.bahi.intVersion
import dev.charanjeev.bahi.libs
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
                    // Debug signing, and staying that way: this project doesn't ship to
                    // Play Store, a real keystore can't be committed to a public repo,
                    // and CI has to be able to run `assembleRelease` on every push
                    // without a secret it would need to be handed. The R8 build this
                    // produces is real -- minification and resource shrinking are on,
                    // same as a shipping build -- only the signature is a placeholder.
                    // If that changes (an actual release channel), the keystore comes
                    // from CI secrets, not from committing one here.
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
