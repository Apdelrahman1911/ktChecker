package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.RuleContext
import kotlinx.serialization.json.encodeToJsonElement

/** Full conservative obligations for observed imports. Not an equivalence or COMPLETE certificate. */
class ObservedProjectImpactPlan internal constructor(
    val before: ProjectModelIdentity?, val after: ProjectModelIdentity,
    changedFiles: List<String>, affectedSourceSetIds: List<String>, obligations: List<CompilationObligation>,
    val evidenceIdentity: String,
) {
    val changedFiles: List<String> = immutable(changedFiles)
    val affectedSourceSetIds: List<String> = immutable(affectedSourceSetIds)
    val obligations: List<CompilationObligation> = immutable(obligations.map { it.copy(reasons = immutable(it.reasons)) })
    val reasons: List<String> = immutable(listOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN,
        "observed_export_requires_all_before_after_contexts"))
}

/** Never infers unaffected reuse, retirement, or a narrowed impact set from a conditional export. */
class ObservedProjectImpactPlanner {
    fun impact(before: ObservedProjectModel?, after: ObservedProjectModel): ObservedProjectImpactPlan {
        if (before != null && before.namespace != after.namespace) throw ProjectModelException("impact_namespace_mismatch",
            "Observed impact must retain the same exact owner/worktree/lineage.")
        val oldFiles = before?.snapshot?.files.orEmpty().associateBy { it.path }
        val newFiles = after.snapshot.files.associateBy { it.path }
        val changed = (oldFiles.keys + newFiles.keys).filter { oldFiles[it] != newFiles[it] }.sorted()
        val ids = (before?.compilationIds.orEmpty() + after.compilationIds).distinct().sorted()
        fun obligation(selected: ObservedProjectModel, id: String, removed: Boolean, retargeted: Boolean): CompilationObligation {
            val compilation = requireNotNull(selected.compilation(id))
            val context = selected.context(id, selected.identity.configurationDigest)
            // An admitted tuple is an audit label even when ambiguity prevents AnalysisContext
            // resolution. Keep every observed assurance gap; no certificate or execution follows.
            val scope = context.context?.scope ?: RuleContext(compilation.target,
                requireNotNull(selected.sourceSet(compilation.defaultSourceSetId)).name, compilation.moduleId, compilation.name)
            val labelReasons = if (context.context == null) listOf("exported_context_label_only") else emptyList()
            val unresolved = when {
                retargeted -> listOf("required_context_retargeted_unresolved", "consumer_context_changed_without_retirement_evidence")
                removed -> listOf("consumer_removed_without_retirement_evidence")
                else -> emptyList()
            }
            return CompilationObligation(id, scope,
                canonicalDigest(ContractJson.encodeToJsonElement(compilation.sourceFiles)),
                selected.identity.modelDigest, context.context?.dependencyDigest, context.context?.toolchainDigest,
                after.identity.configurationDigest, if (removed) "removed_unresolved" else "unsupported",
                immutable((listOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN, "observed_export_requires_all_before_after_contexts") +
                    context.issues.map { it.kind } + labelReasons + unresolved).distinct()))
        }
        val obligations = ids.flatMap { id ->
            val current = after.compilation(id)
            val old = before?.compilation(id)
            val selected = if (current != null) after else requireNotNull(before)
            val currentObligation = obligation(selected, id, removed = current == null, retargeted = false)
            // A reused compilation ID does not authenticate retirement of the previous rule
            // context. Both scopes stay in the public conservative impact vector, not just in
            // the assembler's separately retained historical selections.
            val previousObligation = if (current != null && old != null && before != null) {
                obligation(before, id, removed = true, retargeted = true).takeIf { it.context != currentObligation.context }
            } else null
            listOf(currentObligation) + listOfNotNull(previousObligation)
        }
        val identity = canonicalDigest(ContractJson.encodeToJsonElement(listOf(OBSERVED_EXPORT_PROFILE,
            before?.evidenceIdentity.orEmpty(), after.evidenceIdentity, CONFIGURATION_INPUT_CLOSURE_UNPROVEN,
            canonicalDigest(ContractJson.encodeToJsonElement(changed)), canonicalDigest(ContractJson.encodeToJsonElement(obligations)))))
        return ObservedProjectImpactPlan(before?.identity, after.identity, changed,
            (before?.export?.sourceSets.orEmpty() + after.export.sourceSets).map { it.id }.distinct().sorted(), obligations, identity)
    }
}
