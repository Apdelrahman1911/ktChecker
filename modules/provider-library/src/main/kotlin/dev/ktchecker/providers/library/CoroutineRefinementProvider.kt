package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.semantic.*
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * Two narrow slices of EXISTING canonical IDs; not a Detekt alias/projection or catalog change.
 * No process/compiler/plugin/project loading, no regex semantics, no caller-asserted effect facts.
 * Real immutable FIR facts + mandatory live owner receipts feed a closed source grammar.
 * Sleep recognizes only an immediate captured suspend owner and positive literal Long overload.
 * Result recognizes only an immediately consumed runCatching { delay(positiveLong); constantInt }
 * in a zero-parameter named suspend expression body, with getOrElse { constantInt } or getOrThrow().
 * Catch flow, general inline contexts, cancellation effects, dispatchers, test-job ownership,
 * finally and resource lifetimes remain unsupported. No negative result certifies any whole rule.
 */
class CoroutineRefinementProvider(
    private val model: VerifiedProjectModel? = null,
    private val selection: CoroutineRefinementSelection? = null,
    private val authority: CoroutineRefinementAuthority = CoroutineRefinementAuthority.DENY,
) : AnalysisProvider {
    override val id: String = COROUTINE_REFINEMENT_PROVIDER_ID
    override val version: String = COROUTINE_REFINEMENT_VERSION
    override val supportedRuleIds: Set<String> = Collections.unmodifiableSet(
        linkedSetOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION))

    /** Owner inspection metadata only; neither this request nor a decoded copy authorizes facts. */
    fun authorizationRequest(input: AnalysisInput): CoroutineRefinementAuthorizationRequest = prepare(input).request

    /** Structural freeze for live owner dispatch, not an approval or replacement authority. */
    internal fun freezeForRuntime(input: AnalysisInput): Pair<AnalysisInput, AnalysisProvider> {
        val prepared = prepare(input)
        val bound = prepared.request
        return bound.input to CoroutineRefinementProvider(model, CoroutineRefinementSelection(bound.semanticRequest,
            prepared.shards, bound.compilerExitCode, bound.applicability), authority)
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        if (input.requiredRuleIds.size > 4096 || input.requiredRuleIds.any { it.length !in 1..4096 } ||
            with(input.context.scope) { listOf(target, sourceSet, module, compilation).any { it.length > 4096 } }) {
            return ProviderResult(id, "partial", reasons = listOf("coroutine_refinement_envelope_bound_exceeded"), providerVersion = version)
        }
        // One detached vector governs preparation, authorization, dispatch and reported rows.
        val selectedInput = input.copy(requiredRuleIds = libraryList(input.requiredRuleIds))
        val requested = selectedInput.requiredRuleIds.distinct()
        fun unavailable(reason: String, cancelled: Boolean = false): ProviderResult = ProviderResult(id,
            if (cancelled) "cancelled" else "partial", coverage = libraryList(requested.map { rule ->
                Coverage(rule, input.context.scope, when {
                    cancelled -> "cancelled"
                    rule !in supportedRuleIds -> "unsupported"
                    else -> "unavailable"
                }, if (rule in supportedRuleIds || cancelled) reason else "coroutine_refinement_rule_not_implemented",
                    depth = ruleDepth(rule))
            }), reasons = libraryList(listOf(reason)), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable("coroutine_refinement_cancelled", true)
        if (requested.isEmpty()) return unavailable("coroutine_refinement_explicit_rule_selection_required")
        if (requested.size != selectedInput.requiredRuleIds.size) return unavailable("coroutine_refinement_duplicate_rule_selection")
        if (requested.none { it in supportedRuleIds }) return unavailable("coroutine_refinement_requested_rules_unsupported")
        return try {
            cancellation.throwIfCancelled()
            val prepared = prepare(selectedInput)
            cancellation.throwIfCancelled()
            val permit = authority.authorize(prepared.request) ?: return unavailable("coroutine_refinement_extraction_not_authorized")
            cancellation.throwIfCancelled()
            permit.verify(requireNotNull(model), prepared.request, cancellation)
            // No reader/parser/finding is reached before EVERY mandatory live receipt succeeds.
            val bytes = linkedMapOf<String, ByteArray>()
            val facts = SemanticFactsValidation.assemble(prepared.request.semanticRequest, prepared.shards,
                prepared.request.compilerExitCode, executedSuccessfully = true, sourceBytes = { source ->
                    permit.bytes(source, cancellation).also { bytes[source.path] = it.copyOf() }
                })
            cancellation.throwIfCancelled()
            refinementRequire(facts.state == "extracted" && facts.completeForSupportedVocabulary,
                "coroutine_refinement_snapshot_facts_unavailable")
            val findings = mutableListOf<Finding>()
            val limits = linkedMapOf<String, MutableSet<String>>()
            fun limit(rule: String, reason: String) { limits.getOrPut(rule) { linkedSetOf() } += reason }
            val selected = prepared.request.applicability.callables.associateBy { it.symbolId }
            val exemptions = prepared.request.applicability.blockingBridgeExemptions.toSet()
            facts.shards.forEach { shard ->
                cancellation.throwIfCancelled()
                val symbols = shard.symbols.associateBy { it.id }
                val declared = shard.declarations.toSet()
                val syntax by lazy { CoroutineRefinementSyntax(shard.input, bytes.getValue(shard.input.path)) }
                shard.calls.forEach calls@ { call ->
                    cancellation.throwIfCancelled()
                    if (call.kind != "function_call") return@calls
                    val api = selected[call.targetSymbolId] ?: return@calls
                    val rule = when (api.role) {
                        CoroutineCallableRole.THREAD_SLEEP_LONG -> SLEEP_IN_COROUTINE
                        CoroutineCallableRole.RUN_CATCHING -> RESULT_SWALLOWING_CANCELLATION
                        else -> return@calls
                    }
                    if (rule !in requested) return@calls
                    val owner = call.containingSymbolId?.let(symbols::get)
                    if (!capturedOwner(call, owner, declared)) {
                        limit(rule, "coroutine_refinement_captured_direct_owner_unavailable"); return@calls
                    }
                    val captured = requireNotNull(owner)
                    if ("suspend" !in captured.flags) {
                        // No lexical-ancestor climb: this is not evidence about arbitrary inline contexts.
                        limit(rule, "coroutine_refinement_nonsuspend_owner_not_reinterpreted"); return@calls
                    }
                    val target = symbols.getValue(call.targetSymbolId)
                    if (rule == SLEEP_IN_COROUTINE) {
                        if (BlockingInteropExemption(captured.id, requireNotNull(captured.source)) in exemptions) return@calls
                        val parsed = syntax.simpleLongCall(call.source)
                        if (parsed == null || !argumentsMatch(call, parsed.call) ||
                            !CoroutineRefinementSignatures.plain(call.resultType, "kotlin.Unit")) {
                            limit(rule, "coroutine_refinement_sleep_literal_call_shape_unavailable"); return@calls
                        }
                        findings += observation(prepared.request, permit.requestDigest, rule, parsed.call.element, captured, target,
                            "Resolved Thread.sleep in an immediate suspend owner",
                            "The selected genuine JDK Thread.sleep(Long) call has a positive constant duration in an immediately containing captured suspend function or lambda, without an owner-approved blocking-bridge exemption.",
                            "Thread.sleep blocks its current thread. This advisory does not infer a shared/Main dispatcher, starvation, deadlock or a universal prohibition on intentional blocking interop.")
                    } else {
                        val issue = resultObservation(shard, syntax, call, captured, selected, prepared.request, permit.requestDigest)
                        issue.reason?.let { limit(rule, it) }
                        issue.finding?.let { findings += it }
                    }
                    refinementRequire(findings.size <= 256, "coroutine_refinement_finding_bound_exceeded")
                }
            }
            cancellation.throwIfCancelled()
            val reason = "coroutine_refinement_bounded_slice_executed_unqualified"
            ProviderResult(id, "partial", findings = libraryList(findings.distinctBy { it.fingerprint }),
                coverage = libraryList(requested.map { rule ->
                    if (rule !in supportedRuleIds) Coverage(rule, input.context.scope, "unsupported",
                        "coroutine_refinement_rule_not_implemented", depth = ruleDepth(rule))
                    else Coverage(rule, input.context.scope, when {
                        findings.any { it.ruleId == rule } -> "findings"
                        limits[rule]?.isNotEmpty() == true -> "unavailable"
                        else -> "unqualified"
                    }, reason, depth = ruleDepth(rule), provenance = "executed", certificateId = null,
                        implemented = false, tested = false, qualified = false)
                }), reasons = libraryList(listOf(reason) + limits.values.flatten().distinct().sorted()), providerVersion = version)
                .also { cancellation.throwIfCancelled() }
        } catch (_: CancellationException) {
            unavailable("coroutine_refinement_cancelled", true)
        } catch (problem: CoroutineRefinementProblem) {
            unavailable(problem.code)
        } catch (problem: LibraryRuleProblem) {
            unavailable(problem.code)
        } catch (_: Exception) {
            unavailable("coroutine_refinement_owner_evidence_verification_failed")
        }
    }

    private data class Prepared(val request: CoroutineRefinementAuthorizationRequest, val shards: List<SemanticFactShard>)

    private fun ruleDepth(rule: String): String = when (rule) {
        RESULT_SWALLOWING_CANCELLATION, "ktc.coroutines.swallowed-cancellation",
        "ktc.coroutines.suspending-finally-cleanup", "ktc.librarytest.test-coroutine-ownership-policy" -> "graph"
        SLEEP_IN_COROUTINE, "ktc.resources.owned_autocloseable_exit" -> "semantic"
        else -> "build" // Unsupported here; the external owner's catalog remains the scope authority.
    }

    private fun prepare(input: AnalysisInput): Prepared {
        val actualModel = model ?: throw CoroutineRefinementProblem("coroutine_refinement_verified_model_required")
        val actualSelection = selection ?: throw CoroutineRefinementProblem("coroutine_refinement_fact_selection_required")
        val request = freezeSemanticRequest(actualSelection.request)
        val api = freezeRefinementApplicability(actualSelection.applicability)
        val binding = refinementBinding(actualModel, input, request, api)
        refinementFactBounds(request, actualSelection.shards, api)
        val structure = SemanticFactsValidation.assemble(request, actualSelection.shards,
            actualSelection.compilerExitCode, executedSuccessfully = false)
        refinementRequire(structure.state != "failed" && structure.reasons.all {
            it in setOf("semantic_execution_unavailable", "semantic_source_bytes_unverified")
        }, "coroutine_refinement_structural_facts_unavailable")
        refinementRequire(actualSelection.compilerExitCode == 0, "coroutine_refinement_compiler_completion_required")
        val symbols = structure.shards.flatMap { it.symbols }
        api.callables.forEach { selected ->
            val matches = symbols.filter { it.id == selected.symbolId }
            refinementRequire(matches.isNotEmpty() && matches.all { CoroutineRefinementSignatures.matches(selected.role, it) },
                "coroutine_refinement_selected_signature_unsupported")
        }
        api.blockingBridgeExemptions.forEach { exemption ->
            refinementRequire(structure.shards.any { shard -> exemption.symbolId in shard.declarations && shard.symbols.any {
                it.id == exemption.symbolId && it.source == exemption.source && it.origin == "Source" &&
                    it.kind in setOf("function", "lambda") && "suspend" in it.flags
            } }, "coroutine_refinement_exemption_not_source_bound")
        }
        val contentDigest = Digests.sha256("ktchecker/coroutine-refinement-facts/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(structure.shards)))
        val authorization = freezeRefinementAuthorization(actualModel, CoroutineRefinementAuthorizationRequest(
            actualModel.namespace, actualModel.ingressCertificate, binding.input, request, binding.runtimeArtifacts, 0,
            contentDigest, api))
        return Prepared(authorization, structure.shards)
    }

    private fun capturedOwner(call: CallFact, owner: SymbolFact?, declared: Set<String>): Boolean {
        val span = owner?.source ?: return false
        return owner.id in declared && owner.kind in setOf("function", "lambda") && owner.origin == "Source" &&
            !span.synthetic && !call.source.synthetic && contains(span, call.source)
    }

    private fun contains(outer: SemanticSpan, inner: SemanticSpan): Boolean = outer.path == inner.path &&
        outer.digest == inner.digest && outer.sourceSet == inner.sourceSet &&
        outer.startByte <= inner.startByte && outer.endByte >= inner.endByte

    private fun argumentsMatch(call: CallFact, parsed: CoroutineRefinementSyntax.ParsedCall): Boolean =
        call.arguments.size == parsed.arguments.size && call.arguments.map { it.parameterIndex } == parsed.arguments.indices.toList() &&
            call.arguments.map { it.source } == parsed.arguments

    private data class ResultObservation(val finding: Finding? = null, val reason: String? = null)

    private fun resultObservation(shard: SemanticFactShard, syntax: CoroutineRefinementSyntax, call: CallFact, owner: SymbolFact,
                                  selected: Map<String, CoroutineCallableSelection>,
                                  request: CoroutineRefinementAuthorizationRequest, authorizationDigest: String): ResultObservation {
        fun unknown(reason: String) = ResultObservation(reason = reason)
        if (owner.kind != "function") return unknown("coroutine_refinement_result_named_owner_required")
        val parsed = syntax.resultFunction(requireNotNull(owner.source))
            ?: return unknown("coroutine_refinement_result_closed_grammar_unavailable")
        if (!parsed.runCatching.matches(call.source) || !argumentsMatch(call, parsed.runCatching) ||
            !CoroutineRefinementSignatures.intResult(call.resultType)) {
            return unknown("coroutine_refinement_result_receiver_argument_join_unavailable")
        }
        val symbols = shard.symbols.associateBy { it.id }
        val delay = shard.calls.singleOrNull { candidate -> candidate.kind == "function_call" &&
            selected[candidate.targetSymbolId]?.role == CoroutineCallableRole.DELAY_LONG && parsed.delay.call.matches(candidate.source) &&
            argumentsMatch(candidate, parsed.delay.call) && CoroutineRefinementSignatures.plain(candidate.resultType, "kotlin.Unit") }
            ?: return unknown("coroutine_refinement_selected_cancellable_delay_unavailable")
        val blockOwner = delay.containingSymbolId?.let(symbols::get)
        if (blockOwner == null || blockOwner.kind != "lambda" || blockOwner.origin != "Source" ||
            blockOwner.source != parsed.block || blockOwner.id !in shard.declarations ||
            !capturedOwner(delay, blockOwner, shard.declarations.toSet()) ||
            !CoroutineRefinementSignatures.plain(blockOwner.returnType, "kotlin.Int")) {
            return unknown("coroutine_refinement_result_inline_argument_owner_unavailable")
        }
        val terminalShape = parsed.terminal ?: return unknown("coroutine_refinement_result_escape_consumption_unknown")
        val terminal = shard.calls.singleOrNull { candidate -> candidate.kind == "function_call" &&
            selected[candidate.targetSymbolId]?.role in setOf(CoroutineCallableRole.GET_OR_ELSE, CoroutineCallableRole.GET_OR_THROW) &&
            candidate.containingSymbolId == owner.id && terminalShape.matches(candidate.source) &&
            !candidate.source.synthetic && argumentsMatch(candidate, terminalShape) }
            ?: return unknown("coroutine_refinement_result_terminal_resolution_unavailable")
        if (!CoroutineRefinementSignatures.plain(terminal.resultType, "kotlin.Int") ||
            terminal.extensionReceiverType?.let(CoroutineRefinementSignatures::intResult) != true ||
            !CoroutineRefinementSignatures.plain(owner.returnType, "kotlin.Int")) {
            return unknown("coroutine_refinement_result_terminal_instantiation_unavailable")
        }
        if (selected.getValue(terminal.targetSymbolId).role == CoroutineCallableRole.GET_OR_THROW) {
            return if (parsed.failureBlock == null) ResultObservation() else
                unknown("coroutine_refinement_result_terminal_shape_mismatch")
        }
        val failureBlock = parsed.failureBlock ?: return unknown("coroutine_refinement_result_failure_block_unavailable")
        if (shard.symbols.none { it.kind == "lambda" && it.origin == "Source" && it.source == failureBlock &&
                it.id in shard.declarations && CoroutineRefinementSignatures.plain(it.returnType, "kotlin.Int") }) {
            return unknown("coroutine_refinement_result_failure_block_owner_unavailable")
        }
        return ResultObservation(observation(request, authorizationDigest, RESULT_SWALLOWING_CANCELLATION,
            terminalShape.element, owner, symbols.getValue(call.targetSymbolId),
            "Immediate Result recovery can consume cancellation",
            "The selected kotlin.runCatching wraps a genuine cancellable delay with a positive constant duration; its Result is immediately consumed by the selected getOrElse with a constant fallback and no cancellation rethrow.",
            "If delay throws CancellationException, this exact recovery path replaces it with an ordinary value. This is a bounded source-path observation, not an interprocedural cancellation/effect proof.",
            listOf(Evidence("resolved-cancellable-operation", "Genuine selected delay(Long), positive literal and exact captured runCatching argument.", digest = delay.targetSymbolId),
                Evidence("resolved-result-consumer", "Genuine selected Result.getOrElse and a parsed constant-only onFailure body, not a name-based terminal heuristic.", digest = terminal.targetSymbolId))))
    }

    private fun observation(request: CoroutineRefinementAuthorizationRequest, authorizationDigest: String, rule: String,
                            span: SemanticSpan, owner: SymbolFact, target: SymbolFact, title: String, diagnosis: String,
                            why: String, extraEvidence: List<Evidence> = emptyList()): Finding = Finding(rule, title, diagnosis, why,
        SourceLocation(span.path, span.digest, span.startLine + 1, span.startColumn, span.endLine + 1, span.endColumn),
        Digests.sha256(listOf(rule, request.input.context.scope.id, owner.id, target.id, span.path, span.digest,
            span.startByte.toString(), span.endByte.toString()).joinToString("\u0000")), id, request.input.context.scope,
        severity = "warning", kind = "probable_defect", certainty = "medium", symbolId = owner.id,
        semanticDigest = request.factContentDigest, upstreamId = null, evidence = libraryList(listOf(
            Evidence("owner-extraction-receipt", "All mandatory live receipts admitted this exact successful frozen extraction; not tuple qualification.", digest = authorizationDigest),
            Evidence("verified-project-model", "Owner/model/snapshot/configuration, source affiliation, ordered dependencies and runtime image are bound.", digest = request.modelCertificateId),
            Evidence("resolved-callable", "Exact selected signature plus separate mandatory genuine-binary ownership receipt.", digest = target.id),
            Evidence("captured-suspend-owner", "Immediate source-bound suspend owner; no lexical dispatcher, general inline or dynamic-call inference.", digest = owner.id),
            Evidence("closed-syntax-shape", "Source grammar proves only literal/argument/receiver shape; selected resolved symbols, not syntax spellings, identify APIs."),
            Evidence("scope-limit", "Only the documented direct sleep/constant Result slices. Raw Detekt guidance stays separate, all other IDs/contexts remain obligations, and no clean coverage certificate is issued."),
        ) + extraEvidence), verification = libraryList(listOf(Verification(expected =
            "Review this exact owner contract and cancellation/blocking policy, then re-analyze a deliberate preserving alternative in the same bound compilation."))))
}
