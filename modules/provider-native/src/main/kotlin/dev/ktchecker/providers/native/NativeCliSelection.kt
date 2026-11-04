package dev.ktchecker.providers.native

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ExactCompilationSources
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.safeRelativePath
import java.nio.file.Path
import java.util.Collections

/**
 * Additional trusted-owner facts for the preview CLI route, not inferred from task options or names.
 * The importer must supply the complete ordered CLI members and common/refinement selection for
 * this exact descriptor. Existing ExactCompilationModel ownership alone does not supply that fact.
 * The key binds the snapshot identities, model/context, and original ordered execution argv.
 * This DTO/hash is NOT execution authority or a verified importer capability: ExecutionAuthority
 * and registered materialization/toolchain verification remain mandatory and separate.
 */
data class NativeCliSelection(
    val expectedAnalysisKey: String,
    val sourceFiles: List<String>,
    val commonSourceFiles: List<String>,
) {
    internal fun frozenCopy(): NativeCliSelection = copy(
        sourceFiles = Collections.unmodifiableList(sourceFiles.toList()),
        commonSourceFiles = Collections.unmodifiableList(commonSourceFiles.toList()),
    )
}

/**
 * Pure, fail-closed Kotlin/Native 2.4.21 library-only argument subset. No IO, rewriting, source scans,
 * shell, response files, aliases, fragments, or fallback. Unknown legitimate grammar is unavailable,
 * not silently omitted. Each scalar option is unique; -library is list-valued and must name each
 * selected classpath identity exactly once, in its imported order. Captured bytes and compiler
 * execution are still verified by the existing owner/runtime boundary, not by this structural gate.
 */
internal fun requireNativeCliSelection(plan: NativeRunPlan, input: AnalysisInput) {
    val selection = plan.cliSelection ?: reject("native_cli_selection_unavailable")
    if (plan.mode != "native_cli") reject("native_execution_mode_unsupported")
    if (plan.kotlinVersion != "2.4.21" || input.context.compilerVersion != "2.4.21") {
        reject("native_compiler_version_unavailable")
    }
    val target = when (plan.target) {
        "iosArm64" -> "ios_arm64"
        "iosSimulatorArm64" -> "ios_simulator_arm64"
        else -> reject("native_ios_target_model_required")
    }
    if (input.context.scope.target != plan.target) reject("native_ios_target_model_required")
    val arguments = input.execution?.arguments ?: reject("native_cli_arguments_unavailable")
    if (arguments.size !in 1..16_384 || arguments.any { argument ->
            argument.isEmpty() || argument.length > 8192 || argument.any { it.isISOControl() }
        }) {
        reject("native_cli_argument_form_unsupported")
    }
    if (!Digests.valid(selection.expectedAnalysisKey) || selection.expectedAnalysisKey != input.analysisKey()) {
        reject("native_cli_selection_binding_mismatch")
    }
    val sources = selection.sourceFiles
    if (sources.isEmpty() || sources.toSet().size != sources.size) reject("native_cli_source_selection_invalid")
    sources.forEach { if (!relativeOperand(it) || !it.endsWith(".kt")) reject("native_cli_source_path_unsupported") }
    val common = selection.commonSourceFiles
    if (common.isEmpty() || common.toSet().size != common.size || common.any { it !in sources }) {
        reject("native_cli_common_sources_invalid")
    }
    val imported = ExactCompilationSources(input, plan.compilationModel)
    if (sources.toSet() != input.context.compilationFiles!!.toSet()) reject("native_cli_source_selection_mismatch")
    sources.forEach { imported.sourceSet(it) }
    val captured = input.snapshot.files.associateBy { it.path }
    if (!Digests.valid(input.snapshot.snapshotId) || captured.size != input.snapshot.files.size || sources.any {
            val file = captured.getValue(it)
            !Digests.valid(file.digest) || file.size < 0
        }) reject("native_cli_manifest_invalid")
    // The runtime substitutes an absolute root into the comma-separated common operand. A comma
    // or placeholder in that root changes the effective selection even when the template matches.
    if (!absoluteOperand(input.materializedRoot)) reject("native_cli_snapshot_path_unsupported")
    val libraries = input.context.classpath
    val libraryPaths = libraries.map { it.path }
    if (libraryPaths.toSet().size != libraries.size || libraries.any {
            it.coordinate.isBlank() || !Digests.valid(it.digest) || !absoluteOperand(it.path)
        }) reject("native_cli_library_identity_invalid")

    val seen = mutableSetOf<String>()
    val parsedSources = mutableListOf<String>()
    val parsedLibraries = mutableListOf<String>()
    var index = 0
    fun unique(option: String) { if (!seen.add(option)) reject("native_cli_option_duplicate") }
    fun operand(): String = arguments.getOrNull(++index) ?: reject("native_cli_argument_form_unsupported")
    while (index < arguments.size) {
        val argument = arguments[index]
        when {
            argument == "-target" -> {
                unique(argument)
                if (operand() != target) reject("native_cli_target_binding_invalid")
            }
            argument == "-Xmulti-platform" || argument == "-Xrender-internal-diagnostic-names" -> unique(argument)
            argument.startsWith("-Xcommon-sources=") -> {
                unique("-Xcommon-sources")
                if (argument != "-Xcommon-sources=" + common.joinToString(",") { "{snapshot}/$it" }) {
                    reject("native_cli_common_sources_mismatch")
                }
            }
            argument == "-produce" -> {
                unique(argument)
                if (operand() != "library") reject("native_cli_argument_form_unsupported")
            }
            argument == "-o" -> {
                unique(argument)
                val output = operand()
                if (!output.startsWith("{output}/") || !relativeOperand(output.removePrefix("{output}/"))) {
                    reject("native_cli_output_binding_invalid")
                }
            }
            argument == "-library" -> {
                val library = operand()
                if (library in parsedLibraries) reject("native_cli_option_duplicate")
                if (library !in libraryPaths) reject("native_cli_library_selection_mismatch")
                parsedLibraries += library
            }
            argument.startsWith("{snapshot}/") -> {
                val source = argument.removePrefix("{snapshot}/")
                if (!relativeOperand(source) || !source.endsWith(".kt")) reject("native_cli_source_path_unsupported")
                parsedSources += source
            }
            else -> reject("native_cli_argument_form_unsupported")
        }
        index++
    }
    if (!seen.containsAll(setOf("-target", "-Xmulti-platform", "-Xrender-internal-diagnostic-names",
            "-Xcommon-sources", "-produce", "-o"))) reject("native_cli_required_option_missing")
    if (parsedSources != sources) reject("native_cli_source_selection_mismatch")
    if (parsedLibraries != libraryPaths) reject("native_cli_library_selection_mismatch")
}

private fun relativeOperand(value: String): Boolean {
    if (ambiguousPath(value) || '\\' in value || ':' in value) return false
    return try { safeRelativePath(value); true } catch (_: Exception) { false }
}

private fun absoluteOperand(value: String): Boolean {
    if (ambiguousPath(value)) return false
    return try {
        val path = Path.of(value)
        path.isAbsolute && path.normalize().toString() == value
    } catch (_: Exception) { false }
}

private fun ambiguousPath(value: String): Boolean = value.isBlank() ||
    value.any { it.isISOControl() || it in ",{}*?[]\"'" }

private fun reject(code: String): Nothing = throw ExecutionProblem(code,
    "Exact owner-bound Kotlin/Native 2.4.21 CLI selection is unavailable; no arguments were rewritten")
