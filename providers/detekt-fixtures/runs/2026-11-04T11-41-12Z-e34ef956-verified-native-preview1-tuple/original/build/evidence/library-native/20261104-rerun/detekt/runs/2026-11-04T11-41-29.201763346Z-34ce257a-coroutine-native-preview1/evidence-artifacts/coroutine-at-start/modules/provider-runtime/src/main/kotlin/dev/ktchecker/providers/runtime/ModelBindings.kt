package dev.ktchecker.providers.runtime

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext

/** Imported by the trusted host, never inferred from a directory prefix or a task basename. */
data class ExactCompilationModel(
    val scope: RuleContext,
    val modelDigest: String,
    val sourceSetByFile: Map<String, String>,
)

/** Structural binding only; the execution authority still verifies the registered materialization. */
class ExactCompilationSources(input: AnalysisInput, model: ExactCompilationModel?) {
    private val sourceSets: Map<String, String>

    init {
        val imported = model ?: throw ExecutionProblem("compilation_model_unavailable", "Exact imported compilation ownership is required")
        if (!Digests.valid(imported.modelDigest) || imported.modelDigest != input.context.modelDigest ||
            imported.scope != input.context.scope) {
            throw ExecutionProblem("compilation_model_binding_mismatch", "Imported model names a different compilation context")
        }
        val members = input.context.compilationFiles
            ?: throw ExecutionProblem("compilation_members_unavailable", "Captured files are not an imported compilation member list")
        if (members.size != members.toSet().size) {
            throw ExecutionProblem("compilation_members_duplicate", "Duplicate imported compilation members")
        }
        members.forEach { safeRelativePath(it) }
        val captured = input.snapshot.files.map { it.path }.toSet()
        if (members.any { it !in captured }) {
            throw ExecutionProblem("compilation_member_not_in_manifest", "Imported member is absent from captured scope")
        }
        sourceSets = imported.sourceSetByFile.toMap()
        if (sourceSets.keys != members.toSet() || sourceSets.values.any { it.isBlank() }) {
            throw ExecutionProblem("compilation_source_ownership_unavailable", "Each exact member needs imported source-set ownership")
        }
    }

    fun sourceSet(path: String): String = sourceSets[path]
        ?: throw ExecutionProblem("location_outside_compilation", "Captured file is not a member of the selected compilation")
}

data class GradleTaskObservation(val path: String, val outcome: String) {
    val name: String get() = path.substringAfterLast(':')
    val actuallyExecuted: Boolean get() = outcome in setOf("EXECUTED", "FAILED")
}

/** Exact, module-qualified task proof. Cached/skipped/ambiguous observations are not execution. */
object ExactGradleTaskProof {
    private val qualifiedPath = Regex("^:(?:[A-Za-z0-9_.$-]+:)*[A-Za-z0-9_.$-]+$")
    private val taskLine = Regex("^> Task (:[^ ]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\\s*$")

    fun requireSelection(arguments: List<String>, requiredPaths: Set<String>, module: String) {
        if (requiredPaths.isEmpty() || requiredPaths.any { !qualifiedPath.matches(it) } ||
            !(module == ":" || qualifiedPath.matches(module))) {
            throw ExecutionProblem("gradle_exact_task_model_required", "Fully qualified imported task paths are required")
        }
        if (requiredPaths.any { it.substringBeforeLast(':').ifEmpty { ":" } != module }) {
            throw ExecutionProblem("gradle_task_module_mismatch", "Selected task belongs to another module")
        }
        if (requiredPaths.any { it !in arguments }) {
            throw ExecutionProblem("gradle_exact_task_not_selected", "Exact model task paths must be explicitly selected")
        }
    }

    fun parseLine(line: String): GradleTaskObservation? = taskLine.matchEntire(line)?.let {
        GradleTaskObservation(it.groupValues[1], it.groupValues[2].ifBlank { "EXECUTED" })
    }

    fun missingExecution(requiredPaths: Set<String>, observations: List<GradleTaskObservation>): List<String> =
        requiredPaths.filter { path ->
            val matched = observations.filter { it.path == path }
            matched.size != 1 || !matched.single().actuallyExecuted
        }

    fun hasUnrelatedFailure(requiredPaths: Set<String>, observations: List<GradleTaskObservation>): Boolean =
        observations.any { it.outcome == "FAILED" && it.path !in requiredPaths }
}
