import org.gradle.api.artifacts.ProjectDependency

plugins {
    // Declared but not applied at the root: this registers the plugin versions
    // for subprojects without putting them on the root classpath.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// ---------------------------------------------------------------------------
// ./gradlew moduleGraph
//
// Emits a Mermaid diagram of the real inter-module dependency graph, read from
// the build itself rather than hand-drawn. Paste the output straight into
// README.md -- GitHub renders Mermaid natively, so the graph in the README can
// never drift from the actual build.
// ---------------------------------------------------------------------------
val moduleEdges: List<Pair<String, String>> = subprojects.flatMap { sub ->
    listOf("implementation", "api", "compileOnly").flatMap { configName ->
        sub.configurations.findByName(configName)
            ?.dependencies
            ?.filterIsInstance<ProjectDependency>()
            ?.map { sub.path to it.path }
            .orEmpty()
    }
}.distinct()

tasks.register("moduleGraph") {
    group = "reporting"
    description = "Generates a Mermaid module dependency graph at build/reports/module-graph.md"

    val edges = moduleEdges
    val outputFile = layout.buildDirectory.file("reports/module-graph.md")

    doLast {
        fun nodeId(path: String) = path.removePrefix(":").replace(":", "_")
        fun styleClass(path: String) = when {
            path.startsWith(":app") -> "app"
            path.startsWith(":feature") -> "feature"
            path.startsWith(":core") -> "core"
            else -> "other"
        }

        val nodes = edges.flatMap { listOf(it.first, it.second) }.distinct().sorted()
        val sb = StringBuilder()
        sb.appendLine("```mermaid")
        sb.appendLine("graph TD")
        nodes.forEach { sb.appendLine("    ${nodeId(it)}[\"$it\"]:::${styleClass(it)}") }
        sb.appendLine()
        edges.sortedBy { it.first }.forEach { (from, to) ->
            sb.appendLine("    ${nodeId(from)} --> ${nodeId(to)}")
        }
        sb.appendLine()
        sb.appendLine("    classDef app fill:#f9a825,stroke:#333,color:#000")
        sb.appendLine("    classDef feature fill:#42a5f5,stroke:#333,color:#000")
        sb.appendLine("    classDef core fill:#66bb6a,stroke:#333,color:#000")
        sb.appendLine("```")

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(sb.toString())
        logger.lifecycle("Module graph written to ${file.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// ./gradlew checkModuleBoundaries
//
// Fails the build if a :feature module depends on another :feature module.
// This is the architectural rule the README claims -- CI enforcing it is the
// difference between a stated convention and a real one.
// ---------------------------------------------------------------------------
tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if any feature module depends on another feature module"

    val edges = moduleEdges

    doLast {
        val violations = edges.filter { (from, to) ->
            from.startsWith(":feature") && to.startsWith(":feature")
        }
        if (violations.isNotEmpty()) {
            val detail = violations.joinToString("\n") { "  ${it.first} -> ${it.second}" }
            throw GradleException(
                "Feature-to-feature dependencies are not allowed.\n$detail\n" +
                    "Share code through a :core module instead.",
            )
        }
        logger.lifecycle("Module boundaries OK (${edges.size} project dependencies checked).")
    }
}
