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
// ./gradlew unitTests
//
// `testDebugUnitTest` only exists on Android modules -- :core:model and
// :core:common apply org.jetbrains.kotlin.jvm and only have a `test` task, so
// CI's `./gradlew testDebugUnitTest` silently never runs them. This aggregate
// depends on the right task per module, found via plugins.withId so it reacts
// as each subproject applies its plugin instead of reading subproject state
// during root configuration (which isn't populated yet, and would make this
// task a silent no-op the same way checkModuleBoundaries once was).
// ---------------------------------------------------------------------------
val unitTests = tasks.register("unitTests") {
    group = "verification"
    description = "Runs unit tests in every module, including pure-JVM ones"
}

subprojects {
    // Captured here, not inlined into the nested closures below: `configure {}`
    // on the unitTests TaskProvider makes the Task -- not this Project -- the
    // implicit receiver, so a bare `path` there resolves to the unitTests
    // task's own path (":unitTests") rather than this subproject's.
    val subprojectPath = path

    listOf("com.android.application", "com.android.library").forEach { id ->
        plugins.withId(id) { unitTests.configure { dependsOn("$subprojectPath:testDebugUnitTest") } }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        unitTests.configure { dependsOn("$subprojectPath:test") }
    }
}

// ---------------------------------------------------------------------------
// ./gradlew moduleGraph
//
// Emits two Mermaid diagrams to build/reports/module-graph.md, read from the
// build itself rather than hand-drawn:
//
//  1. A simplified, tier-level architecture diagram -- meant for the README.
//  2. The full 50-edge dependency graph -- every edge, for when someone
//     actually needs to trace one.
//
// The full graph is complete but, at 50 edges through 15 nodes, unreadable as
// an "explain the architecture" picture: Mermaid has no idea :core:model and
// :core:common are foundational rather than architecturally interesting, so
// it draws all twelve edges into them along with everything else. The
// simplified diagram fixes this by construction, not by asking Mermaid to lay
// the hairball out better -- it omits :core:model, :core:common and
// :core:testing as edge targets (see excludedFromSimplified below) and
// collapses the feature tier to one node per diagram instead of four.
// ---------------------------------------------------------------------------
// A function, not a val: subproject build scripts haven't run yet at the point
// this file is evaluated, so their `dependencies {}` blocks -- and therefore
// their ProjectDependency entries -- don't exist. Called from inside doLast,
// by which point every project is configured and the edges are real.
fun moduleEdges(): List<Pair<String, String>> = subprojects.flatMap { sub ->
    listOf("implementation", "api", "compileOnly").flatMap { configName ->
        sub.configurations.findByName(configName)
            ?.dependencies
            ?.filterIsInstance<ProjectDependency>()
            ?.map { sub.path to it.path }
            .orEmpty()
    }
}.distinct()

fun nodeId(path: String) = path.removePrefix(":").replace(":", "_")
fun tierOf(path: String) = when {
    path.startsWith(":app") -> "app"
    path.startsWith(":feature") -> "feature"
    path.startsWith(":core") -> "core"
    else -> "other"
}

// Renders the full, unfiltered dependency graph: every node, every edge,
// grouped into tier subgraphs so Mermaid has a layering hint (see the comment
// on the "app -> feature -> core" ordering below). This is the diagram the
// task has always emitted -- kept as-is, just factored out of doLast.
fun renderFullGraph(edges: List<Pair<String, String>>): String {
    val nodes = edges.flatMap { listOf(it.first, it.second) }.distinct().sorted()
    val sb = StringBuilder()
    sb.appendLine("```mermaid")
    sb.appendLine("graph LR")

    // Subgraphs give Mermaid a layering hint: without them `graph TD`/`LR`
    // has no notion of tiers and routes edges across the whole diagram,
    // rendering as an unreadable hairball. Grouping by the same prefix
    // tierOf() uses turns each tier into its own column, listed in
    // dependency order (app -> feature -> core). Subgraph ids are prefixed
    // with "tier_" -- Mermaid shares one id namespace between subgraphs and
    // nodes, and the :app module's node id is bare "app", which would
    // collide with a same-named subgraph id.
    listOf("app" to "App", "feature" to "Feature", "core" to "Core").forEach { (cls, title) ->
        sb.appendLine("    subgraph tier_$cls[$title]")
        nodes.filter { tierOf(it) == cls }.forEach { sb.appendLine("        ${nodeId(it)}[\"$it\"]:::${tierOf(it)}") }
        sb.appendLine("    end")
    }
    sb.appendLine()
    edges.sortedBy { it.first }.forEach { (from, to) ->
        sb.appendLine("    ${nodeId(from)} --> ${nodeId(to)}")
    }
    sb.appendLine()
    sb.appendLine("    classDef app fill:#f9a825,stroke:#333,color:#000")
    sb.appendLine("    classDef feature fill:#42a5f5,stroke:#333,color:#000")
    sb.appendLine("    classDef core fill:#66bb6a,stroke:#333,color:#000")
    sb.appendLine("```")
    return sb.toString()
}

// Renders the tier-level architecture diagram: what a reader needs to
// understand the shape of the system, not every edge that exists.
//
// :core:model, :core:common and :core:testing are dropped as edge targets --
// almost every module depends on them, so drawing those edges is 15+ lines
// that say "this is a normal module" and nothing else. They're surfaced once,
// as a labelled foundation layer with no incoming edges, instead of at every
// node that touches them.
//
// The feature tier is collapsed the same way: Mermaid can target a subgraph
// id directly, so "app -> feature" and "feature -> core_x" are drawn once
// each rather than once per feature module, without losing which feature
// modules exist (they're still listed inside the subgraph).
fun renderSimplifiedGraph(edges: List<Pair<String, String>>): String {
    val excludedCoreTargets = setOf(":core:model", ":core:common", ":core:testing")

    val featureNodes = edges.filter { it.first.startsWith(":feature") }
        .map { it.first }.distinct().sorted()

    val appToCore = edges.filter {
        it.first.startsWith(":app") && it.second.startsWith(":core") && it.second !in excludedCoreTargets
    }.map { it.second }.distinct().sorted()

    val featureToCore = edges.filter {
        it.first.startsWith(":feature") && it.second.startsWith(":core") && it.second !in excludedCoreTargets
    }.map { it.second }.distinct().sorted()

    val coreInternal = edges.filter {
        it.first.startsWith(":core") && it.second.startsWith(":core") && it.second !in excludedCoreTargets
    }.distinct().sortedBy { it.first }

    val appToFeature = edges.any { it.first.startsWith(":app") && it.second.startsWith(":feature") }

    val coreNodes = (appToCore + featureToCore + coreInternal.map { it.first } + coreInternal.map { it.second })
        .distinct().sorted()

    val sb = StringBuilder()
    sb.appendLine("```mermaid")
    sb.appendLine("graph LR")
    sb.appendLine("    subgraph tier_app[App]")
    sb.appendLine("        app[\":app\"]:::app")
    sb.appendLine("    end")
    sb.appendLine("    subgraph tier_feature[Feature]")
    featureNodes.forEach { sb.appendLine("        ${nodeId(it)}[\"$it\"]:::feature") }
    sb.appendLine("    end")
    sb.appendLine("    subgraph tier_core[Core]")
    coreNodes.forEach { sb.appendLine("        ${nodeId(it)}[\"$it\"]:::core") }
    sb.appendLine("    end")
    // No edges point at this subgraph -- that's the point. :core:model and
    // :core:common sit under everything above; drawing that would mean
    // drawing it from every single node.
    sb.appendLine("    subgraph tier_foundation[\"Foundation (relied on by nearly every module)\"]")
    sb.appendLine("        core_model[\":core:model\"]:::foundation")
    sb.appendLine("        core_common[\":core:common\"]:::foundation")
    sb.appendLine("    end")
    sb.appendLine()
    if (appToFeature) sb.appendLine("    app --> tier_feature")
    appToCore.forEach { sb.appendLine("    app --> ${nodeId(it)}") }
    featureToCore.forEach { sb.appendLine("    tier_feature --> ${nodeId(it)}") }
    coreInternal.forEach { (from, to) -> sb.appendLine("    ${nodeId(from)} --> ${nodeId(to)}") }
    sb.appendLine()
    sb.appendLine("    classDef app fill:#f9a825,stroke:#333,color:#000")
    sb.appendLine("    classDef feature fill:#42a5f5,stroke:#333,color:#000")
    sb.appendLine("    classDef core fill:#66bb6a,stroke:#333,color:#000")
    sb.appendLine("    classDef foundation fill:#e0e0e0,stroke:#333,color:#000")
    sb.appendLine("```")
    return sb.toString()
}

tasks.register("moduleGraph") {
    group = "reporting"
    description = "Generates Mermaid module dependency graphs at build/reports/module-graph.md"

    val outputFile = layout.buildDirectory.file("reports/module-graph.md")

    // moduleEdges() walks live Project/Configuration objects, which the
    // configuration cache can't serialize as task state. The task is a cheap
    // reporting/verification one-off, not something on the hot assemble/test
    // path, so it isn't worth reshaping into cache-safe Provider inputs.
    notCompatibleWithConfigurationCache("reads project configurations directly in doLast")

    doLast {
        val edges = moduleEdges()

        val sb = StringBuilder()
        sb.appendLine("# Module graph")
        sb.appendLine()
        sb.appendLine("## Architecture diagram (for README)")
        sb.appendLine()
        sb.appendLine(
            "Tier-level structure plus :core's internal layering. :core:model, " +
                ":core:common and :core:testing are omitted as edge targets -- " +
                "almost every module depends on them, so drawing those edges adds " +
                "no information. See the full graph below for every edge.",
        )
        sb.appendLine()
        sb.append(renderSimplifiedGraph(edges))
        sb.appendLine()
        sb.appendLine("## Full dependency graph")
        sb.appendLine()
        sb.appendLine("Every module, every edge (${edges.size} total), generated from the build.")
        sb.appendLine()
        sb.append(renderFullGraph(edges))

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

    // See moduleGraph above -- same reason.
    notCompatibleWithConfigurationCache("reads project configurations directly in doLast")

    doLast {
        val edges = moduleEdges()
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
