// Plugins are resolved once here; build-logic references them compileOnly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.wire) apply false
}

// Allowed internal edges (main configurations only). Source of truth: docs/architecture/02-modules.md
// and .claude/rules/architecture-boundaries.md. Features may use any core module; app may use anything.
val coreModules = listOf("common", "model", "format", "storage", "ink", "text", "render", "pdf", "designsystem", "testing")
    .map { ":core:$it" }.toSet()
val allowedEdges: Map<String, Set<String>> = mapOf(
    ":core:common" to emptySet(),
    ":core:model" to setOf(":core:common"),
    ":core:format" to setOf(":core:model", ":core:common"),
    ":core:storage" to setOf(":core:format", ":core:model", ":core:common"),
    ":core:ink" to setOf(":core:model", ":core:common"),
    ":core:text" to setOf(":core:model", ":core:common"),
    ":core:render" to setOf(":core:ink", ":core:text", ":core:model", ":core:common"),
    ":core:pdf" to setOf(":core:render", ":core:format", ":core:model", ":core:common"),
    ":core:designsystem" to setOf(":core:common"),
    ":core:testing" to setOf(":core:model", ":core:common"),
    ":feature:library" to coreModules - ":core:testing",
    ":feature:editor" to coreModules - ":core:testing",
    ":feature:settings" to coreModules - ":core:testing",
    ":tools:icongen" to emptySet(),
    ":app" to coreModules - ":core:testing" + setOf(":feature:library", ":feature:editor", ":feature:settings"),
)
val mainConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")
val actualEdges = provider {
    subprojects.filter { it.buildFile.exists() }.associate { p ->
        p.path to p.configurations.filter { it.name in mainConfigurations }
            .flatMap { c -> c.dependencies.withType<ProjectDependency>().map { it.path } }
            .toSet()
    }
}

tasks.register("verifyModuleGraph") {
    description = "Fails if a module depends on a module outside docs/architecture/02-modules.md."
    group = "verification"
    val edges = actualEdges
    val allowed = allowedEdges
    doLast {
        val violations = edges.get().flatMap { (module, deps) ->
            val ok = allowed[module] ?: error("$module is not listed in allowedEdges (root build.gradle.kts)")
            (deps - ok).map { "$module -> $it" }
        }
        check(violations.isEmpty()) { "Module graph violations:\n" + violations.joinToString("\n") }
    }
}
