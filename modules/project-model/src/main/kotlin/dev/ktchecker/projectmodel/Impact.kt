package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class CompilationObligation(
    val compilationId: String,
    /** Exported audit/routing label; unsupported/removed labels are not verified analysis or execution authority. */
    val context: RuleContext?,
    val compilationFilesDigest: String?,
    val modelDigest: String?,
    val dependencyDigest: String?,
    val toolchainDigest: String?,
    val configurationDigest: String,
    /** removed_unresolved stays a blocker. Deletion is not authenticated retirement. */
    val state: String,
    val reasons: List<String>,
)

/** Produced from verified OLD + NEW captures, not caller-supplied changedPaths/API flags. */
class ProjectImpactPlan internal constructor(
    val before: ProjectModelIdentity?,
    val after: ProjectModelIdentity,
    changedFiles: List<String>,
    affectedSourceSetIds: List<String>,
    obligations: List<CompilationObligation>,
    reasons: List<String>,
    val certificateId: String,
) {
    val changedFiles: List<String> = immutable(changedFiles)
    val affectedSourceSetIds: List<String> = immutable(affectedSourceSetIds)
    val obligations: List<CompilationObligation> = immutable(obligations.map { it.copy(reasons = immutable(it.reasons)) })
    val reasons: List<String> = immutable(reasons)
}

/** No body-only/negative-lookup optimization without an independent semantic equivalence proof. */
class ProjectImpactPlanner {
    fun impact(before: VerifiedProjectModel?, after: VerifiedProjectModel): ProjectImpactPlan {
        if (before != null && before.namespace != after.namespace) throw ProjectModelException("impact_namespace_mismatch", "Do not splice impact graphs from another owner/worktree/lineage.")
        val oldFiles = before?.snapshot?.files.orEmpty().associateBy { it.path }
        val newFiles = after.snapshot.files.associateBy { it.path }
        val changed = (oldFiles.keys + newFiles.keys).filter { oldFiles[it] != newFiles[it] }.sorted()
        val reasons = mutableListOf<String>()
        if (before == null) reasons += "initial_model_requires_all_consumers"
        if (before != null && before.identity.configurationDigest != after.identity.configurationDigest) reasons += "owner_configuration_changed"
        if (before != null && before.identity.exporterDigest != after.identity.exporterDigest) reasons += "model_exporter_identity_changed"
        if (before != null && before.identity.gradleRuntimeDigest != after.identity.gradleRuntimeDigest) reasons += "gradle_runtime_identity_changed"
        if (before != null && captureDigest(before) != captureDigest(after)) reasons += "capture_scope_roots_or_provenance_changed"
        if (before != null && before.identity.dependencyDigest != after.identity.dependencyDigest) reasons += "resolved_dependency_identity_changed"
        if (before != null && before.identity.toolchainDigest != after.identity.toolchainDigest) reasons += "compiler_runtime_or_plugin_identity_changed"
        if (before != null && structuralDigest(before) != structuralDigest(after)) reasons += "model_topology_membership_or_arguments_changed"
        if (changed.any(::configurationPath)) reasons += "executable_build_configuration_changed"
        if (after.issues.isNotEmpty() || before?.issues?.isNotEmpty() == true) reasons += "unknown_model_inputs_require_full_relevant_graph"
        // A captured path may occupy several roles. Source-set ownership does not discharge
        // auxiliary/configuration/generator effects, and no precise consumer equivalence exists.
        val changedPaths = changed.toSet()
        val exports = listOfNotNull(before?.export, after.export)
        if (exports.any { model -> model.compilations.any { compilation -> compilation.auxiliaryInputFiles.any { it.path in changedPaths } } }) {
            reasons += "auxiliary_input_changed_requires_full_relevant_graph"
        }
        if (exports.any { model -> model.configurationInputs.any { it.path in changedPaths } }) {
            reasons += "configuration_input_changed_requires_full_relevant_graph"
        }
        if (exports.any { model -> model.generatedInputs.any { generated -> generated.inputFiles.any { it.path in changedPaths } } }) {
            reasons += "generated_input_changed_requires_full_relevant_graph"
        }
        if (exports.any { model -> model.generatedInputs.any { generated -> generated.outputFiles.any { it.path in changedPaths } } }) {
            reasons += "generated_output_changed_requires_full_relevant_graph"
        }
        val allCompilations = (before?.export?.compilations.orEmpty() + after.export.compilations).groupBy { it.id }
        val allSets = (before?.export?.sourceSets.orEmpty() + after.export.sourceSets).groupBy { it.id }
        val affectedSets = allSets.filterValues { versions -> versions.any { sourceSet ->
            (sourceSet.sources + sourceSet.resources).any { it.path in changed }
        } }.keys.toMutableSet()
        val reverseRefinement = mutableMapOf<String, MutableSet<String>>()
        allSets.values.flatten().forEach { sourceSet -> sourceSet.dependsOn.forEach { base ->
            reverseRefinement.getOrPut(base) { mutableSetOf() } += sourceSet.id
        } }
        expand(affectedSets, reverseRefinement)
        // An unowned changed input may be a resource/generator/configuration/negative lookup.
        // Names/extensions/public flags are never enough to shrink its impact.
        if (changed.any { path -> allSets.values.flatten().none { set -> (set.sources + set.resources).any { it.path == path } } }) {
            reasons += "changed_input_membership_unknown"
        }
        val affected = allCompilations.filterValues { versions -> versions.any { it.sourceSetIds.any(affectedSets::contains) } }.keys.toMutableSet()
        val reverseCompilations = mutableMapOf<String, MutableSet<String>>()
        allCompilations.values.flatten().forEach { consumer -> consumer.projectDependencies.forEach { dependency ->
            val producers = dependency.compilationId?.let(::listOf) ?: allCompilations.values.flatten()
                .filter { it.moduleId == dependency.moduleId }.map { it.id }
            if (dependency.compilationId == null) reasons += "unknown_producer_variant_requires_full_relevant_graph"
            producers.forEach { producer -> reverseCompilations.getOrPut(producer) { mutableSetOf() } += consumer.id }
        }; consumer.associatedCompilationIds.forEach { producer -> reverseCompilations.getOrPut(producer) { mutableSetOf() } += consumer.id } }
        expand(affected, reverseCompilations)
        if (reasons.isNotEmpty()) { affected += allCompilations.keys; affectedSets += allSets.keys }
        val obligations = affected.sorted().flatMap { id ->
            val current = after.compilation(id)
            val previous = before?.compilation(id)
            val oldVerification = before?.let { old -> previous?.let { old.context(it.id, old.identity.configurationDigest) } }
            val oldContext = oldVerification?.context
            val oldScope = if (before != null && previous != null) oldContext?.scope ?: exportedScope(before, previous) else null
            val oldLabelReasons = if (previous != null && oldContext == null) {
                oldVerification?.issues.orEmpty().map { it.kind } + "exported_context_label_only"
            } else emptyList()
            if (current == null) {
                listOf(CompilationObligation(id, oldScope, previous?.let { canonicalDigest(ContractJson.encodeToJsonElement(it.sourceFiles)) },
                    before?.identity?.modelDigest, oldContext?.dependencyDigest, oldContext?.toolchainDigest, after.identity.configurationDigest,
                    "removed_unresolved", immutable((reasons + oldLabelReasons + "consumer_removed_without_retirement_evidence").distinct())))
            } else {
                val context = after.context(id, after.identity.configurationDigest)
                val scope = context.context?.scope ?: exportedScope(after, current)
                val labelReasons = if (context.context == null) listOf("exported_context_label_only") else emptyList()
                val currentObligation = CompilationObligation(id, scope, canonicalDigest(ContractJson.encodeToJsonElement(current.sourceFiles)),
                    after.identity.modelDigest, context.context?.dependencyDigest, context.context?.toolchainDigest, after.identity.configurationDigest,
                    if (context.completeness == ModelCompleteness.COMPLETE) "pending" else "unsupported",
                    immutable((reasons + context.issues.map { it.kind } + labelReasons).distinct()))
                // Stable IDs cannot retire an old actual scope, including when ambiguity leaves
                // both verified AnalysisContexts absent. Labels never fill missing certificates.
                val previousObligation = if (before != null && previous != null && oldScope != scope) {
                    CompilationObligation(id, oldScope, canonicalDigest(ContractJson.encodeToJsonElement(previous.sourceFiles)),
                        before.identity.modelDigest, oldContext?.dependencyDigest, oldContext?.toolchainDigest, after.identity.configurationDigest,
                        "removed_unresolved", immutable((reasons + oldLabelReasons + listOf("required_context_retargeted_unresolved",
                            "consumer_context_changed_without_retirement_evidence")).distinct()))
                } else null
                listOf(currentObligation) + listOfNotNull(previousObligation)
            }
        }
        val certificate = canonicalDigest(ContractJson.encodeToJsonElement(listOf(before?.ingressCertificate.orEmpty(), after.ingressCertificate,
            Digests.canonical(ContractJson.encodeToJsonElement(changed)), Digests.canonical(ContractJson.encodeToJsonElement(obligations)))))
        return ProjectImpactPlan(before?.identity, after.identity, changed, affectedSets.sorted(), obligations, reasons.distinct().sorted(), certificate)
    }

    /** Importer-admitted tuple only: no AnalysisContext, semantic certificate or executable input is fabricated. */
    private fun exportedScope(model: VerifiedProjectModel, compilation: ModelCompilation): RuleContext = RuleContext(
        compilation.target, requireNotNull(model.sourceSet(compilation.defaultSourceSetId)).name, compilation.moduleId, compilation.name,
    )

    private fun captureDigest(model: VerifiedProjectModel): String = canonicalDigest(ContractJson.encodeToJsonElement(
        listOf(model.snapshot.ownerId, model.snapshot.workspaceId, model.snapshot.worktreeId, model.snapshot.captureKind,
            Digests.canonical(ContractJson.encodeToJsonElement(model.snapshot.declaredRoots)),
            Digests.canonical(ContractJson.encodeToJsonElement(model.snapshot.scopeExclusions)),
            Digests.canonical(ContractJson.encodeToJsonElement(model.snapshot.unknownInputs)))))

    private fun structuralDigest(model: VerifiedProjectModel): String = canonicalDigest(ContractJson.encodeToJsonElement(model.export.copy(
        sourceSets = model.export.sourceSets.map { it.copy(sources = it.sources.map(::membership), resources = it.resources.map(::membership)) },
        compilations = model.export.compilations.map { it.copy(sourceFiles = it.sourceFiles.map(::membership), auxiliaryInputFiles = it.auxiliaryInputFiles.map(::membership)) },
        configurationInputs = model.export.configurationInputs.map(::membership), generatedInputs = model.export.generatedInputs.map {
            it.copy(inputFiles = it.inputFiles.map(::membership), outputFiles = it.outputFiles.map(::membership))
        },
    )))

    private fun membership(file: ModelFile): ModelFile = file.copy(digest = "sha256:" + "0".repeat(64), size = 0)

    private fun expand(values: MutableSet<String>, reverse: Map<String, Set<String>>) {
        val queue = ArrayDeque(values)
        while (queue.isNotEmpty()) reverse[queue.removeFirst()].orEmpty().forEach { if (values.add(it)) queue.addLast(it) }
    }
}
