package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.semantic.*
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.CancellationException

/**
 * Bounded internal implementation slice of one EXISTING catalog rule, not a new catalog.
 * It consumes already-extracted facts and never launches/loads compiler, plugin or project code.
 * Only the exact immediate captured suspend function/lambda owner is recognized. Inherited inline
 * contexts, intervening properties/nonfunction declarations, wrappers and dynamic targets remain
 * outside this slice. Exact named target jvm, JVM platform/toolchain and untransformed verified
 * context options only: blank/desktop/other identities or owner-added effective options abstain.
 * No effect/dispatcher/lifetime/Flow/serializer rule is implemented here.
 * All observations are advisory, partial and unqualified; there is no clean coverage certificate.
 */
class RunBlockingInSuspendProvider(
    private val model: VerifiedProjectModel? = null,
    private val selection: RunBlockingFactSelection? = null,
    private val authority: LibraryFactAuthority = LibraryFactAuthority.DENY,
) : AnalysisProvider {
    override val id: String = LIBRARY_PROVIDER_ID
    override val version: String = LIBRARY_PROVIDER_VERSION
    override val supportedRuleIds: Set<String> = setOf(RUNBLOCKING_IN_SUSPEND)

    /** Owner review helper only. Frozen metadata is a request, never extraction authorization. */
    fun authorizationRequest(input: AnalysisInput): LibraryFactAuthorizationRequest = prepare(input).request

    /** Structural freeze for live owner dispatch, not an approval or replacement authority. */
    internal fun freezeForRuntime(input: AnalysisInput): Pair<AnalysisInput, AnalysisProvider> {
        val prepared = prepare(input)
        val bound = prepared.request
        return bound.input to RunBlockingInSuspendProvider(model, RunBlockingFactSelection(bound.semanticRequest,
            prepared.shards, bound.compilerExitCode, bound.applicability), authority)
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        // Do not expand an oversized malformed envelope into an unbounded coverage vector.
        // A partial result without rows/certificates cannot discharge the owner's external plan.
        if (input.requiredRuleIds.size > 4096 || input.requiredRuleIds.any { it.length !in 1..4096 } ||
            with(input.context.scope) { listOf(target, sourceSet, module, compilation).any { it.length > 4096 } }) {
            return ProviderResult(id, "partial", reasons = libraryList(listOf("library_analysis_envelope_bound_exceeded")),
                providerVersion = version)
        }
        // One detached vector governs preparation, authorization, dispatch and reported rows.
        // Cancellation/owner callbacks must not rebind a caller-owned mutable rule list.
        val selectedInput = input.copy(requiredRuleIds = libraryList(input.requiredRuleIds))
        val requested = selectedInput.requiredRuleIds.distinct()
        fun unavailable(reason: String, state: String = "unavailable"): ProviderResult = ProviderResult(id,
            if (state == "cancelled") "cancelled" else "partial",
            coverage = libraryList(requested.map { rule ->
                Coverage(rule, selectedInput.context.scope,
                    if (rule == RUNBLOCKING_IN_SUSPEND || state == "cancelled") state else "unsupported",
                    if (rule == RUNBLOCKING_IN_SUSPEND || state == "cancelled") reason else "library_rule_not_implemented",
                    depth = "semantic")
            }), reasons = libraryList(listOf(reason)), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable("library_analysis_cancelled", "cancelled")
        if (requested.isEmpty()) return unavailable("library_no_explicit_rules_requested")
        if (requested.size != selectedInput.requiredRuleIds.size) return unavailable("library_rule_selection_duplicate")
        if (RUNBLOCKING_IN_SUSPEND !in requested) return unavailable("library_requested_rules_not_implemented", "unsupported")
        return try {
            cancellation.throwIfCancelled()
            val prepared = prepare(selectedInput)
            cancellation.throwIfCancelled()
            val permit = authority.authorize(prepared.request)
                ?: return unavailable("library_extraction_not_authorized")
            cancellation.throwIfCancelled()
            permit.verify(requireNotNull(model), prepared.request, cancellation)
            // No source reader or observation is reached before all mandatory owner receipts.
            val facts = SemanticFactsValidation.assemble(prepared.request.semanticRequest, prepared.shards,
                prepared.request.compilerExitCode, executedSuccessfully = true,
                sourceBytes = { source -> permit.bytes(source, cancellation) })
            cancellation.throwIfCancelled()
            requireLibrary(facts.completeForSupportedVocabulary && facts.state == "extracted",
                "library_snapshot_semantic_facts_unavailable")
            val selected = prepared.request.applicability.selectedSymbolIds.toSet()
            val exemptions = prepared.request.applicability.blockingInteropExemptions.toSet()
            val findings = mutableListOf<Finding>()
            val limits = mutableSetOf<String>()
            facts.shards.forEach { shard ->
                cancellation.throwIfCancelled()
                val symbols = shard.symbols.associateBy { it.id }
                val declarations = shard.declarations.toSet()
                shard.calls.forEach calls@ { call ->
                    cancellation.throwIfCancelled()
                    val target = symbols.getValue(call.targetSymbolId)
                    if (call.kind != "function_call") return@calls
                    if (target.id !in selected) {
                        if (target.qualifiedName == "kotlinx.coroutines.runBlocking" && target.source == null &&
                            target.origin == "Library") limits += "library_runblocking_overload_not_selected"
                        return@calls
                    }
                    val owner = call.containingSymbolId?.let(symbols::get)
                    if (owner == null || owner.kind !in setOf("function", "lambda")) {
                        limits += "library_runblocking_direct_function_owner_unavailable"
                        return@calls
                    }
                    if ("suspend" !in owner.flags) return@calls
                    val ownerSource = owner.source
                    if (owner.id !in declarations || ownerSource == null || ownerSource.synthetic || call.source.synthetic ||
                        ownerSource.path != call.source.path || ownerSource.digest != call.source.digest ||
                        ownerSource.sourceSet != call.source.sourceSet || ownerSource.startByte > call.source.startByte ||
                        ownerSource.endByte < call.source.endByte || call.arguments.none { it.parameterIndex == 1 }) {
                        limits += "library_runblocking_captured_direct_owner_unavailable"
                        return@calls
                    }
                    if (BlockingInteropExemption(owner.id, ownerSource) in exemptions) return@calls
                    requireLibrary(findings.size < 256, "library_finding_bound_exceeded")
                    findings += observation(prepared.request, permit.requestDigest, call, owner, target)
                }
            }
            cancellation.throwIfCancelled()
            val reason = "library_direct_suspend_owner_slice_executed_unqualified"
            val result = ProviderResult(id, "partial", findings = libraryList(findings), coverage = libraryList(requested.map { rule ->
                if (rule != RUNBLOCKING_IN_SUSPEND) Coverage(rule, prepared.request.input.context.scope, "unsupported",
                    "library_rule_not_implemented", depth = "semantic")
                else Coverage(rule, prepared.request.input.context.scope, if (findings.isEmpty()) "unqualified" else "findings",
                    reason, depth = "semantic", provenance = "executed",
                    certificateId = null, implemented = false, tested = false, qualified = false)
            }), reasons = libraryList(listOf(reason) + limits.sorted()), providerVersion = version)
            cancellation.throwIfCancelled()
            result
        } catch (_: CancellationException) {
            unavailable("library_analysis_cancelled", "cancelled")
        } catch (problem: LibraryRuleProblem) {
            unavailable(problem.code)
        } catch (_: Exception) {
            // Receipt/source errors are noncertifying and never expose private exception text.
            unavailable("library_owner_evidence_verification_failed")
        }
    }

    private data class Prepared(val request: LibraryFactAuthorizationRequest, val shards: List<SemanticFactShard>)

    private fun prepare(input: AnalysisInput): Prepared {
        val actualModel = model ?: throw LibraryRuleProblem("library_verified_project_model_required")
        val actualSelection = selection ?: throw LibraryRuleProblem("library_semantic_fact_selection_required")
        val request = freezeSemanticRequest(actualSelection.request)
        val api = freezeApplicability(actualSelection.applicability)
        val frozenInput = libraryBinding(actualModel, input, request, api).first
        factBounds(request, actualSelection.shards, api)
        // This pass freezes and structurally validates raw DTOs without claiming execution or
        // invoking a byte reader. Neither these fields nor a complete-shaped bundle is authority.
        val structural = SemanticFactsValidation.assemble(request, actualSelection.shards,
            actualSelection.compilerExitCode, executedSuccessfully = false)
        requireLibrary(structural.state != "failed" && structural.reasons.all {
            it in setOf("semantic_execution_unavailable", "semantic_source_bytes_unverified")
        }, "library_structural_semantic_facts_unavailable")
        requireLibrary(actualSelection.compilerExitCode == 0, "library_compiler_completion_required")
        val symbols = structural.shards.flatMap { it.symbols }
        api.selectedSymbolIds.forEach { symbolId ->
            val selectedFacts = symbols.filter { it.id == symbolId }
            requireLibrary(selectedFacts.isNotEmpty(), "library_selected_callable_facts_missing")
            selectedFacts.forEach { symbol ->
                requireLibrary(exactRunBlockingSignature(symbol), "library_runblocking_signature_unsupported")
            }
        }
        api.blockingInteropExemptions.forEach { exemption ->
            requireLibrary(structural.shards.any { shard ->
                exemption.symbolId in shard.declarations && shard.symbols.any {
                    it.id == exemption.symbolId && it.source == exemption.source &&
                        it.kind in setOf("function", "lambda") && "suspend" in it.flags
                }
            }, "library_blocking_interop_exemption_not_source_bound")
        }
        val factsDigest = Digests.sha256("ktchecker/library-frozen-facts/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(structural.shards)))
        val authorization = freezeAuthorization(actualModel, LibraryFactAuthorizationRequest(actualModel.namespace,
            actualModel.ingressCertificate, frozenInput, request, 0, factsDigest, api))
        return Prepared(authorization, structural.shards)
    }

    /** Exact selected binary ID still needs the separate owner per-artifact ownership receipt. */
    private fun exactRunBlockingSignature(symbol: SymbolFact): Boolean {
        fun plainClass(type: TypeFact, classifier: String): Boolean = type.kind == "class" &&
            type.classifierId == classifier && type.nullable == false && type.arguments.isEmpty() && type.known
        if (symbol.kind != "function" || symbol.qualifiedName != "kotlinx.coroutines.runBlocking" || symbol.local ||
            symbol.source != null || symbol.origin != "Library" || "suspend" in symbol.flags ||
            symbol.receiverType != null || symbol.dispatchReceiverType != null || symbol.contextParameters.isNotEmpty() ||
            symbol.typeParameterCount != 1 || symbol.parameters.size != 2 || "visibility:public" !in symbol.flags) return false
        val context = symbol.parameters[0]
        val block = symbol.parameters[1]
        val result = symbol.returnType ?: return false
        if (context.index != 0 || context.name != "context" || context.vararg ||
            !plainClass(context.type, "kotlin.coroutines.CoroutineContext") ||
            block.index != 1 || block.name != "block" || block.vararg || result.kind != "type_parameter" ||
            result.parameterIndex != 0 || result.nullable != false || !result.known) return false
        val function = block.type
        return function.kind == "class" && function.classifierId == "kotlin.coroutines.SuspendFunction1" &&
            function.nullable == false && function.arguments.size == 2 && function.known &&
            function.arguments.all { it.variance == "invariant" } &&
            function.arguments[0].type?.let { plainClass(it, "kotlinx.coroutines.CoroutineScope") } == true &&
            // parameterOwner is the extractor's ownerKey, NOT resolved SymbolFact.id.
            function.arguments[1].type == result
    }

    private fun observation(request: LibraryFactAuthorizationRequest, authorizationDigest: String, call: CallFact, owner: SymbolFact,
                            target: SymbolFact): Finding {
        val span = call.source
        val location = SourceLocation(span.path, span.digest, span.startLine + 1, span.startColumn,
            span.endLine + 1, span.endColumn)
        val fingerprint = Digests.sha256(listOf(RUNBLOCKING_IN_SUSPEND, request.input.context.scope.id,
            owner.id, target.id, span.path, span.digest, span.startByte.toString(), span.endByte.toString()).joinToString("\u0000"))
        return Finding(RUNBLOCKING_IN_SUSPEND, "runBlocking in a direct suspend owner",
            "The selected kotlinx.coroutines.runBlocking call blocks its current thread inside an immediately containing captured suspend function or suspend lambda.",
            "runBlocking is a blocking bridge. A synchronous boundary or intentional blocking interop can be valid; this is an advisory observation, not proof of deadlock or a leak.",
            location, fingerprint, id, request.input.context.scope, severity = "warning", kind = "probable_defect",
            certainty = "medium", symbolId = owner.id, semanticDigest = request.factContentDigest,
            evidence = libraryList(listOf(
                Evidence("owner-extraction-receipt", "Mandatory owner receipts admitted the exact completed extraction and captured source bytes; no tuple qualification.", digest = authorizationDigest),
                Evidence("verified-project-model", "Exact owner/model/snapshot/configuration and ordered dependencies are bound.", digest = request.modelCertificateId),
                Evidence("resolved-callable", "Selected binary overload with separate owner-confirmed provenance in the pinned coroutines artifact.", digest = target.id),
                Evidence("direct-suspend-owner", "Immediate captured function/lambda owner only; no lexical-ancestor, dynamic-call or effect inference.", digest = owner.id),
                Evidence("scope-limit", "Internal direct-owner slice only. Other catalog rules, inherited inline contexts and opaque/nonfunction owners remain obligations/gaps."),
                Evidence("api-contract", "Official runBlocking documents blocking the current thread and warns against invocation from suspend functions.",
                    artifactRef = "https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html"),
            )), verification = libraryList(listOf(Verification(
                expected = "Review the actual blocking-interop contract and dispatcher/context requirements; only then choose a suspension-preserving alternative or an exact owner-approved exemption."))))
    }
}
