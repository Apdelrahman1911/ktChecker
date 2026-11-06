package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*

/** Owner-installed code chooses profiles; project JSON cannot install a provider or a generator. */
internal data class ProviderRoute(
    val provider: AnalysisProvider,
    val applicable: (AnalysisInput) -> Boolean,
    val executionProfile: ((AnalysisInput) -> TrustedExecution)? = null,
    /** Actual adapter + selected upstream byte identity, not a catalog or qualification claim. */
    val implementationIdentity: String? = null,
    /** Owner-only LIVE attempt projection; never regenerate a model profile from archived DTOs. */
    val preparedChild: ((AnalysisInput, List<String>) -> AnalysisInput)? = null,
    val finishAttempt: ((AnalysisInput) -> Unit)? = null,
) {
    init {
        require((preparedChild == null) == (finishAttempt == null))
        require(preparedChild == null || executionProfile == null) { "A retained model route cannot fall back to a profile generator" }
    }
}

internal data class AuthorizedAttempt(
    val original: AnalysisInput,
    val effective: AnalysisInput,
    val originalProfiles: Map<String, TrustedExecution>,
    val verifyFrozen: () -> Unit,
)

/** Ephemeral capabilities are reconstructed only by the trusted durable input observer. */
internal class ExecutionGrants : ExecutionAuthority {
    private val attempts = ConcurrentHashMap<String, AuthorizedAttempt>()
    private val permits = ConcurrentHashMap<String, ExecutionPermit>()

    fun prepared(value: AuthorizedAttempt) { attempts[value.effective.analysisKey()] = value }
    fun attempt(input: AnalysisInput): AuthorizedAttempt? = attempts[input.analysisKey()]

    fun grant(full: AnalysisInput, child: AnalysisInput, route: ProviderRoute): AnalysisInput {
        val attempt = attempt(full) ?: return child
        val registered = attempt.originalProfiles[route.provider.id] ?: return child
        val originalChild = attempt.original.copy(requiredRuleIds = child.requiredRuleIds, execution = null)
        val originalProfile = route.executionProfile?.invoke(originalChild) ?: registered
        if (originalProfile != registered) throw CliProblem("provider_profile_changed", "Installed provider no longer matches the owner-approved profile.")
        val profile = route.executionProfile?.invoke(child.copy(execution = null)) ?: registered
        val prepared = child.copy(execution = profile)
        val key = prepared.analysisKey()
        permits[key] = ExecutionPermit(profile, prepared.snapshot.snapshotId, Path.of(prepared.materializedRoot), key, attempt.verifyFrozen)
        return prepared
    }

    override fun authorize(input: AnalysisInput): ExecutionPermit? = permits[input.analysisKey()]?.takeIf {
        it.execution == input.execution && it.snapshotId == input.snapshot.snapshotId &&
            it.materializedRoot == Path.of(input.materializedRoot)
    }

    fun revokeChild(input: AnalysisInput) { permits.remove(input.analysisKey()) }
    fun finish(input: AnalysisInput) { attempts.remove(input.analysisKey()) }
}

/** Conservative whole declared-compilation scheduling; no unsound edited-line-only shortcut. */
internal class ReviewCoordinator(
    private val catalog: Catalog,
    routes: List<ProviderRoute>,
    private val grants: ExecutionGrants,
    private val codeImage: CodeImage,
) : ProgressiveAnalysisProvider {
    val routes: List<ProviderRoute> = Collections.unmodifiableList(routes.toList())
    override val id: String = "ktchecker-coordinator"
    override val version: String = coordinatorVersion(codeImage.digest, this.routes)
    override val supportedRuleIds: Set<String> = catalog.ids
    val findingProviders: Map<String, Set<String>> = supportedRuleIds.associateWith { rule ->
        routes.filter { rule in it.provider.supportedRuleIds }.map { it.provider.id }.toSet()
    }

    fun plannedRoutes(input: AnalysisInput): Map<ProviderRoute, List<String>> {
        val activation = validatedActivation(input, catalog)
        val remaining = input.requiredRuleIds.filter { activation[it]?.active == true }.toMutableSet()
        val planned = linkedMapOf<ProviderRoute, List<String>>()
        routes.filter { it.applicable(input) }.forEach { route ->
            val ids = remaining.intersect(route.provider.supportedRuleIds).sorted()
            if (ids.isNotEmpty()) { planned[route] = ids; remaining.removeAll(ids.toSet()) }
        }
        return planned
    }

    fun profiles(input: AnalysisInput): Map<String, TrustedExecution> = plannedRoutes(input).mapNotNull { (route, ids) ->
        route.executionProfile?.let { generator ->
            runCatching { route.provider.id to generator(input.copy(requiredRuleIds = ids, execution = null)) }.getOrNull()
        }
    }.toMap()

    override fun analyzeProgressive(input: AnalysisInput, cancellation: CancellationToken, onProgress: (ProviderResult) -> Unit): ProviderResult {
        val started = System.nanoTime()
        val all = mutableListOf<ProviderResult>()
        val claimed = mutableSetOf<String>()
        try {
            // The version covers actual selected adapter/coordinator code and resource bytes,
            // not merely upstream labels. In-place upgrades are unsupported; persistent drift
            // invalidates this execution rather than reusing a cached implementation identity.
            codeImage.verify()
            val activation = validatedActivation(input, catalog)
            for ((route, ids) in plannedRoutes(input)) {
                if (cancellation.isCancelled()) break
                var child = input.copy(requiredRuleIds = ids, execution = null)
                val observed = try {
                    val retained = route.preparedChild
                    child = if (retained == null) grants.grant(input, child, route) else retained(input, ids)
                    route.provider.analyze(child, cancellation)
                } catch (_: Exception) {
                    ProviderResult(route.provider.id, "failed", coverage = ids.map {
                        Coverage(it, input.context.scope, "failed", "provider_execution_or_authorization_failed")
                    }, reasons = listOf("provider_execution_or_authorization_failed"), providerVersion = route.provider.version)
                } finally { grants.revokeChild(child) }
                val verified = normalizeChild(observed, route, child)
                all += verified
                claimed += ids
                // A partial result is intentionally incomplete and must not satisfy a final barrier.
                onProgress(verified.copy(providerId = id, providerVersion = version, status = "partial"))
            }
            codeImage.verify()
            val missing = (input.requiredRuleIds.toSet() - claimed).sorted().map { rule ->
                val decision = activation[rule]
                Coverage(rule, input.context.scope, when {
                    cancellation.isCancelled() -> "cancelled"
                    decision?.reason == "disabled_by_explicit_owner_policy" -> "disabled"
                    else -> "unavailable"
                }, when {
                    cancellation.isCancelled() -> "required_analysis_cancelled"
                    decision == null -> "trusted_activation_plan_missing"
                    !decision.active -> decision.reason
                    else -> "no_compatible_installed_rule_implementation"
                },
                    depth = catalog.records.firstOrNull { it["id"]?.toString()?.trim('"') == rule }
                        ?.get("depth")?.toString()?.trim('"') ?: "semantic")
            }
            // Each canonical rule is assigned to one route. Per-child normalization already
            // coalesces equivalent aliases; a display span/message cannot erase material siblings.
            val findings = all.flatMap { it.findings }
            val coverage = all.flatMap { it.coverage } + missing
            val structurallyComplete = input.requiredRuleIds.isNotEmpty() && missing.isEmpty() &&
                all.all { it.status == "complete" } && coverage.all { it.successful() }
            val status = when {
                cancellation.isCancelled() -> "cancelled"
                structurallyComplete -> "complete"
                all.isEmpty() -> "unavailable"
                else -> "partial"
            }
            return ProviderResult(id, status, findings, coverage.sortedBy { it.ruleId },
                reasons = (all.flatMap { result -> result.reasons.map { "${result.providerId}:$it" } } +
                    (if (missing.isEmpty()) emptyList() else listOf("required_rule_coverage_unavailable")) +
                    listOf("preview_not_release_qualified")).distinct(),
                rawDiagnostics = all.flatMap { it.rawDiagnostics },
                metrics = AnalysisMetrics(elapsedMillis = (System.nanoTime() - started) / 1_000_000,
                    cpuMillis = all.mapNotNull { it.metrics.cpuMillis }.takeIf { it.size == all.size && it.isNotEmpty() }?.sum(),
                    peakRssBytes = null, outputBytes = all.sumOf { it.metrics.outputBytes }),
                providerVersion = version)
        } finally {
            grants.finish(input)
            routes.forEach { it.finishAttempt?.invoke(input) }
        }
    }

    private fun normalizeChild(result: ProviderResult, route: ProviderRoute, input: AnalysisInput): ProviderResult {
        fun failed(reason: String) = ProviderResult(route.provider.id, "failed", coverage = coordinatorList(input.requiredRuleIds.map {
            Coverage(it, input.context.scope, "failed", reason)
        }), reasons = coordinatorList(listOf(reason)), providerVersion = route.provider.version)
        // Detach every provider-owned result vector before callbacks. Nested finding vectors are
        // captured below; coverage, reasons and raw diagnostics must not alias progress/final either.
        val captured = try {
            result.copy(findings = coordinatorList(result.findings), coverage = coordinatorList(result.coverage),
                reasons = coordinatorList(result.reasons), rawDiagnostics = coordinatorList(result.rawDiagnostics))
        } catch (problem: Throwable) {
            if (problem !is Exception && problem !is AssertionError) throw problem
            return failed("coordinator_finding_capture_failed")
        }
        val rows = captured.coverage.groupBy { it.ruleId }
        val exactIdentity = captured.providerId == route.provider.id && captured.providerVersion == route.provider.version
        val validFindings = if (!exactIdentity) emptyList() else captured.findings.filter { finding ->
            finding.providerId == route.provider.id && finding.ruleId in input.requiredRuleIds && finding.context == input.context.scope &&
                input.snapshot.files.any { it.path == finding.location.path && it.digest == finding.location.fileDigest } &&
                finding.disposition == "open" && Digests.valid(finding.fingerprint)
        }
        val valid = exactIdentity && validFindings.size == captured.findings.size &&
            captured.coverage.all { it.ruleId in input.requiredRuleIds && it.context == input.context.scope } &&
            rows.values.all { it.size == 1 }
        if (!valid) return failed("provider_diagnostic_identity_mismatch")
        val findings = try { normalizeCoordinatorFindings(validFindings) } catch (problem: Throwable) {
            if (problem !is Exception && problem !is AssertionError) throw problem
            return failed((problem as? CoordinatorFindingIdentityProblem)?.code ?: "coordinator_finding_capture_failed")
        }
        return captured.copy(findings = findings, coverage = coordinatorList(input.requiredRuleIds.map { rule ->
            rows[rule]?.single() ?: Coverage(rule, input.context.scope, "incomplete", "provider_omitted_required_rule_context")
        }))
    }
}

/** Raw coordinator identity, not a policy anchor, semantic equivalence or qualification proof.
 * Callers first validate the raw provider/rule/source identity and fingerprint format.
 * A coarse fingerprint/alias label cannot merge different complete diagnostics. Always derive
 * the same per-finding key, including when a co-located sibling disappears. Only digest-linked
 * retrieval locators are non-material; retain their deterministic union and one actually emitted
 * alias label. Ordinary evidence refs, all source spans and classification remain bound.
 * Malformed UTF-16 is refused before UTF-8 hashing, not replaced or normalized. Even a digest
 * collision must not merge distinct exact descriptors. One invalid row fails the entire child.
 * Returned vectors are detached and recursively immutable across progress/final publication.
 */
internal fun normalizeCoordinatorFindings(findings: List<Finding>): List<Finding> {
    class Aliases(var representative: Finding, val retrieval: MutableSet<EvidenceRetrievalAttachment>)
    val unique = linkedMapOf<String, Aliases>()
    val descriptors = CoordinatorFindingDescriptors()
    for (original in findings.toList()) {
        val encoded = ContractJson.encodeToJsonElement(original)
        requireCoordinatorFindingText(encoded)
        // Use the SAME captured JSON for the material key and returned diagnostic. Copying only
        // the outer Finding would retain caller-owned evidence, related ranges and command lists.
        val detached = ContractJson.decodeFromJsonElement<Finding>(encoded)
        val finding = detached.copy(evidence = coordinatorList(detached.evidence),
            verification = coordinatorList(detached.verification.map { it.copy(command = coordinatorList(it.command)) }),
            relatedLocations = coordinatorList(detached.relatedLocations),
            retrievalAttachments = coordinatorList(detached.retrievalAttachments))
        val descriptor = JsonObject(encoded.jsonObject.filterKeys {
            it !in setOf("fingerprint", "upstreamId", "retrievalAttachments")
        })
        val canonical = Digests.canonical(buildJsonObject {
            put("format", "ktchecker/coordinator-finding/1")
            put("diagnostic", descriptor)
        })
        val identity = Digests.sha256(canonical)
        descriptors.verifyAndRemember(identity, canonical)
        val previous = unique[identity]
        if (previous == null) {
            unique[identity] = Aliases(finding.copy(fingerprint = identity), finding.retrievalAttachments.toMutableSet())
        } else {
            if (compareValues(finding.upstreamId, previous.representative.upstreamId) < 0) {
                previous.representative = finding.copy(fingerprint = identity)
            }
            previous.retrieval.addAll(finding.retrievalAttachments)
        }
    }
    // Union locators while scanning; encode each sort key and sort each final union only once.
    // Re-sorting a growing union after every alias would make alias-heavy inputs quadratic.
    return coordinatorList(unique.values.map { aliases ->
        val retrieval = aliases.retrieval.map { Digests.canonical(ContractJson.encodeToJsonElement(it)) to it }
            .sortedBy { it.first }.map { it.second }
        aliases.representative.copy(retrievalAttachments = coordinatorList(retrieval))
    })
}

internal class CoordinatorFindingIdentityProblem(val code: String) : IllegalArgumentException(code)

/** Exact DATA collision fence. No production digest override or external identity enrollment. */
internal class CoordinatorFindingDescriptors {
    private val byDigest = hashMapOf<String, String>()
    fun verifyAndRemember(identity: String, canonical: String) {
        val previous = byDigest[identity]
        if (previous != null && previous != canonical) {
            throw CoordinatorFindingIdentityProblem("coordinator_finding_identity_collision")
        }
        byDigest[identity] = canonical
    }
}

private fun <T> coordinatorList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())

/** Validate decoded string contents, not JSON escape spelling; escaped lone surrogates count. */
private fun requireCoordinatorFindingText(value: JsonElement) {
    fun text(value: String) {
        var index = 0
        while (index < value.length) {
            val unit = value[index++]
            if (Character.isHighSurrogate(unit)) {
                if (index == value.length || !Character.isLowSurrogate(value[index])) {
                    throw CoordinatorFindingIdentityProblem("coordinator_finding_text_invalid")
                }
                index++
            } else if (Character.isLowSurrogate(unit)) {
                throw CoordinatorFindingIdentityProblem("coordinator_finding_text_invalid")
            }
        }
    }
    when (value) {
        is JsonObject -> value.forEach { (key, child) -> text(key); requireCoordinatorFindingText(child) }
        is JsonArray -> value.forEach(::requireCoordinatorFindingText)
        is JsonPrimitive -> if (value.isString) text(value.content)
    }
}

/** Ordered routing is semantic: changing precedence, installation bytes or normalization breaks reuse. */
internal fun coordinatorVersion(codeDigest: String, routes: List<ProviderRoute>): String {
    require(Digests.valid(codeDigest))
    val descriptor = buildJsonObject {
        put("schema", "ktchecker-coordinator-identity/1")
        put("codeImage", codeDigest)
        putJsonArray("routes") {
            routes.forEach { route -> add(buildJsonObject {
                put("id", route.provider.id)
                put("version", route.provider.version)
                put("implementation", route.implementationIdentity?.let(::JsonPrimitive) ?: JsonNull)
                put("authorization", if (route.preparedChild == null) "ordinary-owner-profile" else "live-retained-model-attempt")
                put("supportedRuleIds", ContractJson.encodeToJsonElement(route.provider.supportedRuleIds.sorted()))
            }) }
        }
    }
    return "0.1.0-dev.1/" + Digests.sha256(Digests.canonical(descriptor)).removePrefix("sha256:")
}
