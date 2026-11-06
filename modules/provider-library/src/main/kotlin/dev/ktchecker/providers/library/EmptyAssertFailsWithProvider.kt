package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.semantic.*
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.CancellationException

/**
 * Executable internal slice of the EXISTING included catalog rule. It consumes already-extracted
 * facts; it does not launch the compiler, discover a runner or self-authorize from DTOs. Only the
 * public reified Kotlin 2.4.21 Any?-block overload, a captured literally empty/comments-only lambda
 * and a complete single-call ordinary required-to-pass test body are recognized. Unit-only bodies,
 * KClass overloads, arbitrary effects/CFG, other backends and unenrolled test contexts abstain.
 *
 * This is an injectable host provider, not an autonomously wired project pipeline or qualified
 * catalog release. All result coverage remains partial/unqualified; no findings is never clean.
 */
class EmptyAssertFailsWithProvider(
    private val model: VerifiedProjectModel? = null,
    private val selection: EmptyAssertionFactSelection? = null,
    private val authority: EmptyAssertionAuthority = EmptyAssertionAuthority.DENY,
) : AnalysisProvider {
    override val id: String = EMPTY_ASSERTION_PROVIDER_ID
    override val version: String = EMPTY_ASSERTION_PROVIDER_VERSION
    override val supportedRuleIds: Set<String> = setOf(EMPTY_ASSERT_FAILS_WITH)

    /** Frozen owner-review request only, never an extraction/policy approval. */
    fun authorizationRequest(input: AnalysisInput): EmptyAssertionAuthorizationRequest = prepare(input).request

    /** Structural freeze for live owner dispatch, not an approval or replacement authority. */
    internal fun freezeForRuntime(input: AnalysisInput): Pair<AnalysisInput, AnalysisProvider> {
        val prepared = prepare(input)
        val bound = prepared.request
        return bound.input to EmptyAssertFailsWithProvider(model, EmptyAssertionFactSelection(bound.semanticRequest,
            prepared.shards, bound.compilerExitCode, bound.applicability), authority)
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        if (input.requiredRuleIds.size > 4096 || input.requiredRuleIds.any { it.length !in 1..4096 } ||
            with(input.context.scope) { listOf(target, sourceSet, module, compilation).any { it.length > 4096 } }) {
            return ProviderResult(id, "partial", reasons = libraryList(listOf("empty_assertion_input_bound_exceeded")), providerVersion = version)
        }
        val selectedInput = input.copy(requiredRuleIds = libraryList(input.requiredRuleIds))
        val requested = selectedInput.requiredRuleIds.distinct()
        fun unavailable(reason: String, cancelled: Boolean = false) = ProviderResult(id, if (cancelled) "cancelled" else "partial",
            coverage = libraryList(requested.map { rule -> Coverage(rule, selectedInput.context.scope,
                if (cancelled) "cancelled" else if (rule == EMPTY_ASSERT_FAILS_WITH) "unavailable" else "unsupported",
                if (rule == EMPTY_ASSERT_FAILS_WITH || cancelled) reason else "empty_assertion_rule_not_implemented", depth = "semantic") }),
            reasons = libraryList(listOf(reason)), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable("empty_assertion_cancelled", true)
        if (requested.isEmpty()) return unavailable("empty_assertion_no_explicit_rule_requested")
        if (requested.size != selectedInput.requiredRuleIds.size) return unavailable("empty_assertion_duplicate_rule_selection")
        if (EMPTY_ASSERT_FAILS_WITH !in requested) return unavailable("empty_assertion_requested_rule_not_implemented")
        return try {
            cancellation.throwIfCancelled()
            val prepared = prepare(selectedInput)
            cancellation.throwIfCancelled()
            val permit = authority.authorize(prepared.request) ?: return unavailable("empty_assertion_extraction_not_authorized")
            cancellation.throwIfCancelled()
            permit.verify(requireNotNull(model), prepared.request, cancellation)
            // No source reads/parsing/finding occur before EVERY extraction/ownership/policy receipt.
            val bytes = mutableMapOf<SemanticSource, ByteArray>()
            val facts = SemanticFactsValidation.assemble(prepared.request.semanticRequest, prepared.shards,
                prepared.request.compilerExitCode, executedSuccessfully = true, sourceBytes = { source ->
                    permit.bytes(source, cancellation).also { bytes[source] = it }
                })
            cancellation.throwIfCancelled()
            emptyAssertionRequire(facts.completeForSupportedVocabulary && facts.state == "extracted",
                "empty_assertion_snapshot_semantic_facts_unavailable")
            val selected = prepared.request.applicability.selectedSymbolIds.toSet()
            val entries = prepared.request.applicability.testEntries.associateBy { it.symbolId to it.source }
            val findings = mutableListOf<Finding>()
            val limits = mutableSetOf<String>()
            facts.shards.forEach { shard ->
                cancellation.throwIfCancelled()
                val syntax = EmptyAssertFailsWithSyntax(shard.input, bytes.getValue(shard.input))
                val symbols = shard.symbols.associateBy { it.id }
                val declarations = shard.declarations.toSet()
                shard.calls.forEach calls@ { call ->
                    cancellation.throwIfCancelled()
                    if (call.kind != "function_call") return@calls
                    val target = symbols.getValue(call.targetSymbolId)
                    if (target.id !in selected) {
                        if (target.qualifiedName == "kotlin.test.assertFailsWith") limits += "empty_assertion_callable_not_selected"
                        return@calls
                    }
                    val owner = call.containingSymbolId?.let(symbols::get)
                    if (owner == null || !capturedTestOwner(owner, declarations) || call.source.synthetic ||
                        !contains(requireNotNull(owner.source), call.source)) {
                        limits += "empty_assertion_direct_test_owner_unavailable"
                        return@calls
                    }
                    val entry = entries[owner.id to owner.source]
                    if (entry == null) {
                        limits += "empty_assertion_required_test_contract_unavailable"
                        return@calls
                    }
                    if (entry.disposition != EmptyAssertionTestDisposition.REQUIRED_TO_PASS) {
                        limits += "empty_assertion_owner_test_policy_excluded"
                        return@calls
                    }
                    val shape = syntax.function(requireNotNull(owner.source))
                    if (shape == null) {
                        limits += "empty_assertion_closed_ordinary_body_unavailable"
                        return@calls
                    }
                    if (!shape.matches(call.source) || call.dispatchReceiverType != null || call.extensionReceiverType != null ||
                        call.arguments != listOf(ArgumentBindingFact(shape.block, 1, "block")) ||
                        call.resultType.kind != "class" || call.resultType.nullable != false || !call.resultType.known ||
                        call.resultType.classifierId == "kotlin.Nothing") {
                        limits += "empty_assertion_resolved_argument_join_unavailable"
                        return@calls
                    }
                    val block = shard.symbols.singleOrNull { it.kind == "lambda" && it.source == shape.block && it.id in declarations }
                    if (block == null || block.origin != "Source" || !block.local || block.parameters.isNotEmpty() ||
                        block.contextParameters.isNotEmpty() || block.receiverType != null || block.dispatchReceiverType != null ||
                        "suspend" in block.flags || !plain(block.returnType, "kotlin.Unit") ||
                        shard.calls.any { it !== call && contains(shape.block, it.source) }) {
                        limits += "empty_assertion_captured_empty_lambda_unavailable"
                        return@calls
                    }
                    emptyAssertionRequire(findings.size < 256, "empty_assertion_finding_bound_exceeded")
                    findings += observation(prepared.request, permit.requestDigest, call, owner, target, block)
                }
            }
            cancellation.throwIfCancelled()
            val reason = "empty_assertion_direct_empty_slice_executed_unqualified"
            ProviderResult(id, "partial", findings = libraryList(findings.distinctBy { it.fingerprint }),
                coverage = libraryList(requested.map { rule ->
                    if (rule != EMPTY_ASSERT_FAILS_WITH) Coverage(rule, prepared.request.input.context.scope, "unsupported",
                        "empty_assertion_rule_not_implemented", depth = "semantic")
                    else Coverage(rule, prepared.request.input.context.scope, if (findings.isEmpty()) "unqualified" else "findings",
                        reason, depth = "semantic", provenance = "executed", certificateId = null,
                        implemented = false, tested = false, qualified = false)
                }), reasons = libraryList(listOf(reason) + limits.sorted()), providerVersion = version)
                .also { cancellation.throwIfCancelled() }
        } catch (_: CancellationException) {
            unavailable("empty_assertion_cancelled", true)
        } catch (problem: EmptyAssertionProblem) {
            unavailable(problem.code)
        } catch (problem: LibraryRuleProblem) {
            unavailable(problem.code)
        } catch (_: Exception) {
            unavailable("empty_assertion_owner_evidence_verification_failed")
        }
    }

    private data class Prepared(val request: EmptyAssertionAuthorizationRequest, val shards: List<SemanticFactShard>)

    private fun prepare(input: AnalysisInput): Prepared {
        val actualModel = model ?: throw EmptyAssertionProblem("empty_assertion_verified_model_required")
        val actualSelection = selection ?: throw EmptyAssertionProblem("empty_assertion_fact_selection_required")
        val request = freezeSemanticRequest(actualSelection.request)
        val api = freezeEmptyAssertionApplicability(actualSelection.applicability)
        val binding = emptyAssertionBinding(actualModel, input, request, api)
        emptyAssertionFactBounds(request, actualSelection.shards, api)
        val structural = SemanticFactsValidation.assemble(request, actualSelection.shards, actualSelection.compilerExitCode,
            executedSuccessfully = false)
        emptyAssertionRequire(structural.state != "failed" && structural.reasons.all {
            it in setOf("semantic_execution_unavailable", "semantic_source_bytes_unverified")
        }, "empty_assertion_structural_facts_unavailable")
        emptyAssertionRequire(actualSelection.compilerExitCode == 0, "empty_assertion_compiler_completion_required")
        val symbols = structural.shards.flatMap { it.symbols }
        api.selectedSymbolIds.forEach { symbolId ->
            val selectedFacts = symbols.filter { it.id == symbolId }
            emptyAssertionRequire(selectedFacts.isNotEmpty() && selectedFacts.all(::exactReifiedSignature),
                "empty_assertion_selected_signature_unsupported")
        }
        api.testEntries.forEach { entry ->
            emptyAssertionRequire(structural.shards.any { shard -> entry.symbolId in shard.declarations && shard.symbols.any {
                it.id == entry.symbolId && it.source == entry.source && it.origin == "Source" && it.kind == "function" && !it.local
            } }, "empty_assertion_test_policy_not_source_bound")
        }
        val contentDigest = Digests.sha256("ktchecker/empty-assertion-facts/1\u0000" +
            Digests.canonical(ContractJson.encodeToJsonElement(structural.shards)))
        return Prepared(freezeEmptyAssertionAuthorization(actualModel, EmptyAssertionAuthorizationRequest(actualModel.namespace,
            actualModel.ingressCertificate, binding.input, request, binding.runtimeArtifacts, 0, contentDigest, api)), structural.shards)
    }

    private fun capturedTestOwner(owner: SymbolFact, declared: Set<String>): Boolean = owner.id in declared &&
        owner.kind == "function" && owner.origin == "Source" && !owner.local && owner.source?.synthetic == false &&
        owner.parameters.isEmpty() && owner.contextParameters.isEmpty() && owner.receiverType == null &&
        owner.typeParameterCount == 0 && owner.flags.none { it in setOf("suspend", "inline", "external", "abstract", "expect") } &&
        plain(owner.returnType, "kotlin.Unit")

    private fun contains(outer: SemanticSpan, inner: SemanticSpan): Boolean = outer.path == inner.path &&
        outer.digest == inner.digest && outer.sourceSet == inner.sourceSet && outer.startByte <= inner.startByte && outer.endByte >= inner.endByte

    private fun plain(type: TypeFact?, classifier: String, nullable: Boolean = false): Boolean = type?.let {
        it.kind == "class" && it.classifierId == classifier && it.nullable == nullable && it.arguments.isEmpty() &&
            it.components.isEmpty() && it.known
    } == true

    /** Exact official v2.4.21 public reified overload. Hidden () -> Unit and KClass forms are OUT. */
    private fun exactReifiedSignature(symbol: SymbolFact): Boolean {
        if (symbol.kind != "function" || symbol.qualifiedName != "kotlin.test.assertFailsWith" || symbol.origin != "Library" ||
            symbol.local || symbol.source != null || symbol.receiverType != null || symbol.dispatchReceiverType != null ||
            symbol.contextParameters.isNotEmpty() || symbol.typeParameterCount != 1 || symbol.parameters.size != 2 ||
            "visibility:public" !in symbol.flags || "inline" !in symbol.flags || "suspend" in symbol.flags) return false
        val result = symbol.returnType ?: return false
        val message = symbol.parameters[0]
        val block = symbol.parameters[1]
        if (result.kind != "type_parameter" || result.parameterIndex != 0 || result.nullable != false || !result.known ||
            message.index != 0 || message.name != "message" || message.vararg || !plain(message.type, "kotlin.String", true) ||
            block.index != 1 || block.name != "block" || block.vararg) return false
        return block.type.kind == "class" && block.type.classifierId == "kotlin.Function0" && block.type.nullable == false &&
            block.type.known && block.type.arguments.size == 1 && block.type.arguments.single().variance == "invariant" &&
            plain(block.type.arguments.single().type, "kotlin.Any", true)
    }

    private fun observation(request: EmptyAssertionAuthorizationRequest, authorizationDigest: String, call: CallFact,
                            owner: SymbolFact, target: SymbolFact, block: SymbolFact): Finding {
        val span = call.source
        val fingerprint = Digests.sha256(listOf(EMPTY_ASSERT_FAILS_WITH, request.input.context.scope.id, owner.id, target.id,
            span.path, span.digest, span.startByte.toString(), span.endByte.toString()).joinToString("\u0000"))
        return Finding(EMPTY_ASSERT_FAILS_WITH, "Empty assertFailsWith block",
            "This resolved kotlin.test.assertFailsWith block is literally empty. It cannot throw the expected exception, so this ordinary required-to-pass test assertion fails.",
            "Put the intended throwing action inside the block. Intentionally failing assertion-calibration or expected-failure tests require their exact owner-configured test disposition, not a change that hides the failure.",
            SourceLocation(span.path, span.digest, span.startLine + 1, span.startColumn, span.endLine + 1, span.endColumn),
            fingerprint, id, request.input.context.scope, severity = "warning", kind = "correctness", certainty = "high",
            symbolId = owner.id, semanticDigest = request.factContentDigest,
            evidence = libraryList(listOf(
                Evidence("owner-extraction-receipt", "Exact successful extraction, immutable sources, binary ownership and test policy admitted before inspection; not tuple qualification.", digest = authorizationDigest),
                Evidence("verified-project-model", "Actual source membership, configuration, compiler options and ordered artifacts are bound.", digest = request.modelCertificateId),
                Evidence("resolved-callable", "Owner-confirmed genuine kotlin-test 2.4.21 public reified Any?-block overload.", digest = target.id),
                Evidence("captured-empty-lambda", "Exact resolved block argument and declared lambda share the empty/comments-only source range.", digest = block.id),
                Evidence("ordinary-test-contract", "Owner-enrolled required-to-pass test entry; complete single-call body excludes enclosing catches, expected-failure lambdas and conditional reachability.", digest = owner.id),
                Evidence("scope-limit", "Internal reified empty-body JVM slice only; Unit-only/KClass/general control-flow/unenrolled contexts remain unknown, not clean."),
                Evidence("api-contract", "Official Kotlin 2.4.21 assertFailsWith requires the block to throw the expected exception.",
                    artifactRef = "https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/libraries/kotlin.test/common/src/main/kotlin/kotlin/test/Assertions.kt"),
            )), verification = libraryList(listOf(Verification(expected =
                "Run the actual test with its configured runner after restoring the intended throwing operation; preserve legitimate calibration/expected-failure controls."))))
    }
}
