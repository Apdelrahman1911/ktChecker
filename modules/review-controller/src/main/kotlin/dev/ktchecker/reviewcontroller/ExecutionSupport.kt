package dev.ktchecker.reviewcontroller

import dev.ktchecker.projectmodel.ModelCompilation

/** No heuristic path discovery, response-file expansion, plugin interpretation or guessed rewrite. */
internal fun unsupportedExecutionInputs(compilation: ModelCompilation): List<String> = buildList {
    if (compilation.friendArtifacts.isNotEmpty()) add("friend_artifact_rewrite_unavailable")
    if (compilation.auxiliaryArtifacts.isNotEmpty()) add("auxiliary_artifact_rewrite_unavailable")
    if (compilation.auxiliaryInputFiles.isNotEmpty()) add("auxiliary_input_rewrite_unavailable")
    if (compilation.toolchain?.compilerPlugins?.isNotEmpty() == true) add("compiler_plugin_rewrite_unavailable")
    if (!pathFreeArguments(compilation.compilerArguments)) add("compiler_arguments_require_verified_rewrite")
}

/** Finite known path-free grammar; every unknown option/value is unsupported, never stripped. */
private fun pathFreeArguments(arguments: List<String>): Boolean {
    val flags = setOf("-progressive", "-no-stdlib", "-no-reflect", "-Werror", "-nowarn", "-java-parameters")
    val version = Regex("[0-9]+(?:\\.[0-9]+){1,2}")
    val jvmTarget = Regex("(?:1\\.8|[1-9][0-9]?)")
    val module = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,255}")
    val values = mapOf("-language-version" to version, "-api-version" to version, "-jvm-target" to jvmTarget, "-module-name" to module)
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index++]
        if (argument in flags) continue
        val option = argument.substringBefore('=')
        val validator = values[option] ?: return false
        val value = if ('=' in argument) argument.substringAfter('=') else arguments.getOrNull(index++) ?: return false
        if (!validator.matches(value)) return false
    }
    return true
}
