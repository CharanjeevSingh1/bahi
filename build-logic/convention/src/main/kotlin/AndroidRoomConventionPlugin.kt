import androidx.room.gradle.RoomExtension
import dev.charanjeev.finflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Exports Room schemas to core/database/schemas/ and commits them to git.
 * Those JSON files are what MigrationTest reads -- without them you cannot
 * write a real migration test, only a hopeful one.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("androidx.room")
            apply("com.google.devtools.ksp")
        }

        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }

        dependencies {
            add("implementation", libs.findLibrary("room-runtime").get())
            add("implementation", libs.findLibrary("room-ktx").get())
            add("ksp", libs.findLibrary("room-compiler").get())
            add("androidTestImplementation", libs.findLibrary("room-testing").get())
        }
    }
}
