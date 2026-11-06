package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ClientRegistration
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.kernel.SubmitPlanRequest
import dev.ktchecker.snapshot.SnapshotIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Real owner/config HMAC mutations and captured audit/source data, with Workbench workerCount=0
 * and NO model/compiler installation. No export, provider, generation, host or final gate runs.
 * Explicit synthetic TEST owner choices are not defaults, production approval or qualification.
 */
class OwnerModelCompilerPolicyTest {
    @TempDir lateinit var temporary: Path
    private val catalog = Catalog()
    private val registry = CompilerRuleRegistry.load()
    private val supported = registry.jvmRuleIds
    private val rules: List<String> get() = supported.intersect(catalog.ids).filter { id ->
        catalog.records.single { it.getValue("id").jsonPrimitive.content == id }.getValue("default_activation").jsonPrimitive.content != "excluded"
    }.sorted().take(3)
    private fun selection(ids: List<String> = rules.take(2), consent: Boolean = true, reason: String = "Explicit SYNTHETIC TEST owner preview policy; NO execution") =
        OwnerModelCompilerPolicySelection(ids, reason, consent)

    private inline fun withOwner(action: (Workbench, ClientRegistration, OwnerModelRootPin, OwnerModelCommandClock, OwnerModelCompilerPolicy) -> Unit) {
        val root = temporary.toRealPath()
        Workbench(WorkbenchOptions(state = root.resolve("state"), compilerDirectory = root.resolve("no-compiler-installation"),
            detektDirectory = root.resolve("no-detekt-installation"), ktlintDirectory = root.resolve("no-ktlint-installation"), workerCount = 0)).use { work ->
            val registration = work.register("workspace", "worktree", "client", "session", "lineage")
            work.snapshots.registerWorktree(SnapshotIdentity(registration.binding.ownerId, registration.binding.workspaceId, registration.binding.worktreeId))
            val command = ownerModelFreshDirectory(work.state.root.resolve("policy-command-test"))
            val clock = commandTestClock()
            action(work, registration, command, clock, OwnerModelCompilerPolicy(work, registration, supported, command, clock))
        }
    }

    @Test fun stableIdsOnlyWithNoAliasUnknownDuplicateUnsupportedOrImplicitRuleSelection() {
        val known = rules.first()
        val alias = registry.byRuleId.getValue(known).upstreamId
        val invalid = listOf(selection(emptyList()), selection(listOf(known, known)), selection(rules.take(2).reversed()),
            selection(listOf(alias)), selection(listOf("compiler:$alias")), selection(listOf("ktc.compiler.common.unknown_unit_rule")),
            selection(listOf(registry.mappings.first { !it.jvmApplicable }.ruleId)), selection(listOf("ktc.kotlin.detekt.style.magic-number")))
        invalid.forEach { value -> assertEquals("owner_model_policy_rule_selection_invalid", assertFailsWith<CliProblem> {
            ownerModelFreezeCompilerPolicy(value, supported, catalog)
        }.code) }
        assertEquals("owner_model_explicit_policy_consent_required", assertFailsWith<CliProblem> {
            ownerModelFreezeCompilerPolicy(selection(consent = false), supported, catalog)
        }.code)
        assertFailsWith<Exception> { ContractJson.decodeFromString<OwnerModelCompilerPolicySelection>(
            """{"compilerRuleIds":["$known"],"reason":"not owner authorization"}""") }
        assertEquals(0L, Files.list(temporary).use { it.count() })
    }

    @Test fun reasonIsExplicitBoundedValidUnicodeAndSelectionFreezesTheCallerOrder() {
        for (reason in listOf("", " \t", "x\n", "x\u0085", "é".repeat(513), "x\uD800")) {
            assertFailsWith<CliProblem> { ownerModelFreezeCompilerPolicy(selection(reason = reason), supported, catalog) }
        }
        val caller = rules.take(2).toMutableList()
        val frozen = ownerModelFreezeCompilerPolicy(selection(caller, reason = "é".repeat(512)), supported, catalog)
        caller.clear()
        assertEquals(rules.take(2), frozen.compilerRuleIds)
        assertFailsWith<UnsupportedOperationException> { (frozen.compilerRuleIds as MutableList<*>).clear() }
        assertEquals("\uFEFF", ownerModelFreezeCompilerPolicy(selection(reason = "\uFEFF"), supported, catalog).reason)
    }

    @Test fun missingPolicyAndExecutionConsentDoNotEnableAnyCandidateOrWriteRuleSettings() = withOwner { work, registration, command, _, policy ->
        val binding = modelConfigBinding(registration.binding)
        val before = work.configs.capture(binding)
        assertFailsWith<CliProblem> { policy.requireConfigured() }
        // Independent Workbench intent control, not a command submit (which requires policy first).
        val intent = work.beginModelReview(registration.binding.clientId, SubmitPlanRequest("unit", "unit", "unit"), true)
        assertTrue(intent.evidence.executionAuthorized)
        assertTrue(intent.configuration.ruleSettings.isEmpty())
        assertFalse(activationPlan(catalog, catalog.ids.sorted(), false, emptyMap(), intent.configuration.policyDigest).decisions.any { it.active })
        assertFailsWith<CliProblem> { policy.verifyIntent(intent) }
        assertEquals(before.revisionToken, work.configs.capture(binding).revisionToken)
        assertTrue(ownerModelNames(command.path, 0).isEmpty())
        assertTrue(work.kernel.events(registration.client).events.isEmpty())
    }

    @Test fun reviewRpcCannotInvokeExplicitOwnerPolicyOperationEvenWithConsentData() = withOwner { work, registration, command, _, policy ->
        val before = work.configs.capture(modelConfigBinding(registration.binding)).revisionToken
        for (method in listOf("config.mutate", "owner.configureCompilerPolicy", "review.configureCompilerPolicy")) {
            assertEquals("method_not_found", assertFailsWith<CliProblem> {
                work.call(method, ContractJson.encodeToJsonElement(selection()).jsonObject)
            }.code)
        }
        assertFalse(policy.configured)
        assertEquals(before, work.configs.capture(modelConfigBinding(registration.binding)).revisionToken)
        assertTrue(ownerModelNames(command.path, 0).isEmpty())
    }

    @Test fun authorizedSeparateSelectionUsesActualLocalAuditsAndExactRequestHashChain() = withOwner { work, registration, command, clock, policy ->
        val selected = selection()
        val data = policy.configure(selected)
        assertTrue(policy.configured); assertFalse(policy.historyUncertain)
        assertEquals(selected.compilerRuleIds, policy.compilerRuleIds)
        assertEquals(ContractJson.encodeToJsonElement(registration.binding), data["binding"])
        assertEquals(ContractJson.encodeToJsonElement(selected), data["selection"])
        assertEquals(JsonPrimitive(Digests.sha256(ownerModelStrictBytes(ContractJson.encodeToJsonElement(selected)))), data["selectionDigest"])
        val before = ContractJson.decodeFromJsonElement<ConfigRevisionToken>(data.getValue("beforeRevision"))
        val after = ContractJson.decodeFromJsonElement<ConfigRevisionToken>(data.getValue("afterRevision"))
        val current = work.configs.capture(modelConfigBinding(registration.binding))
        assertEquals(0L, before.local.revision); assertEquals(0L, before.shared.revision)
        assertEquals(before.shared, after.shared); assertEquals(2L, after.local.revision)
        assertEquals(current.revisionToken, after)
        assertEquals(current.local, ContractJson.decodeFromJsonElement<LayerInspection>(data.getValue("afterLocal")))
        assertEquals(current.local.auditEvents, ContractJson.decodeFromJsonElement<List<ConfigAuditEvent>>(data.getValue("auditEvents")))
        var expected = before
        var prior: ConfigAuditEvent? = null
        data.getValue("mutationSteps").jsonArray.forEachIndexed { index, value ->
            val step = value.jsonObject
            val request = ContractJson.decodeFromJsonElement<ConfigMutationRequest>(step.getValue("request"))
            val event = ContractJson.decodeFromJsonElement<ConfigAuditEvent>(step.getValue("auditEvent"))
            assertEquals(expected, request.expected)
            assertEquals(ConfigLayer.LOCAL, request.layer)
            assertEquals(ConfigAction.SetRuleEnabled(selected.compilerRuleIds[index], true), request.action)
            assertEquals(request.requestId, event.requestId); assertEquals(request.digest(), event.requestDigest)
            assertEquals("explicit-owner-cli", event.actor.clientId); assertEquals(registration.binding.ownerId, event.actor.ownerId)
            assertEquals(prior?.let { Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(it))) }, event.previousEventDigest)
            assertEquals(ContractJson.encodeToJsonElement(expected), step["beforeRevision"])
            expected = ContractJson.decodeFromJsonElement(step.getValue("afterRevision")); prior = event
        }
        assertEquals(after, expected)
        assertEquals(selected.compilerRuleIds.map { RuleSetting(it, true) }, current.local.ruleSettings)
        assertTrue(current.shared.ruleSettings.isEmpty() && current.suppressions.isEmpty() && current.baselines.isEmpty())
        assertEquals(setOf("owner-policy-selection.json", "owner-policy-step-1.json", "owner-policy-step-2.json", "owner-policy-witness.json"),
            ownerModelNames(command.path, 4))
        assertEquals(data, ContractJson.parseToJsonElement(Files.readString(command.path.resolve("owner-policy-witness.json"))))
        policy.verifyCurrent(clock::checkpoint)
        assertFailsWith<CliProblem> { policy.configure(selected) }
        assertEquals(after, work.configs.capture(modelConfigBinding(registration.binding)).revisionToken)
    }

    @Test fun capturedPolicyAppliesToAll1665AndFreshPatchChecksNeverInvokeOldClock() = withOwner { work, registration, _, oldClock, policy ->
        val selected = selection()
        val audit = policy.configure(selected)
        oldClock.deny()
        val fresh = commandTestClock()
        policy.verifyCurrent(fresh::checkpoint) // Retained policy/artifact data, NOT old export authority.
        val intent = work.beginModelReview(registration.binding.clientId, SubmitPlanRequest("patch", "request", "ref"), true)
        policy.verifyIntent(intent)
        val commit = work.overlay(OverlayDocument(registration.binding.workspaceId, registration.binding.worktreeId,
            registration.binding.clientId, registration.binding.sessionId, "patch", changes = listOf(OverlayChange("put", "Main.kt", content = "// not executed\n"))))
        val all = catalog.ids.sorted()
        val activation = activationPlan(catalog, all, false, intent.configuration.ruleSettings.mapValues { it.value.enabled },
            intent.configuration.policyDigest)
        val input = AnalysisInput(commit.toContractManifest(), "/NON-EXECUTED-source-data", AnalysisContext(
            configurationDigest = intent.configuration.policyDigest, policies = mapOf(ACTIVATION_POLICY_KEY to
                ContractJson.encodeToString(ActivationPlan.serializer(), activation))), all)
        assertEquals(1665, input.requiredRuleIds.size)
        assertTrue(OwnerFullCatalog1665.accepts(input.requiredRuleIds))
        assertEquals(selected.compilerRuleIds, validatedActivation(input, catalog).values.filter { it.active }.map { it.ruleId }.sorted())
        assertEquals(audit["policyDigest"], JsonPrimitive(input.context.configurationDigest))
        assertFalse(input.context.fullProject); assertNull(input.execution)
        assertTrue(work.kernel.events(registration.client).events.isEmpty()) // No invented parent/admission.
    }

    @Test fun foreignPolicyRevisionCannotBeBorrowedForANewIntentOrReplaceRetainedSelection() = withOwner { work, registration, _, _, policy ->
        val selected = selection(rules.take(1))
        val audit = policy.configure(selected)
        val old = work.beginModelReview(registration.binding.clientId, SubmitPlanRequest("one", "one", "one"), true)
        policy.verifyIntent(old)
        val token = work.configs.capture(modelConfigBinding(registration.binding)).revisionToken
        work.mutateConfig(registration.binding.clientId, ConfigLayer.LOCAL, token, ConfigAction.SetRuleEnabled(rules[1], true))
        assertFailsWith<CliProblem> { policy.verifyCurrent(commandTestClock()::checkpoint) }
        val newer = work.beginModelReview(registration.binding.clientId, SubmitPlanRequest("two", "two", "two"), true)
        assertFailsWith<CliProblem> { policy.verifyIntent(newer) }
        assertEquals(selected.compilerRuleIds, policy.compilerRuleIds)
        assertEquals(audit, policy.evidence()["witness"])
    }

    @Test fun configurationRequiresAnActuallyEmptySourceLineageAndDoesNotRetryAfterRejection() = withOwner { work, registration, command, _, policy ->
        work.overlay(OverlayDocument(registration.binding.workspaceId, registration.binding.worktreeId, registration.binding.clientId,
            registration.binding.sessionId, "already-captured", changes = listOf(OverlayChange("put", "Main.kt", content = "data"))))
        assertEquals("owner_model_policy_requires_fresh_lineage", assertFailsWith<CliProblem> { policy.configure(selection()) }.code)
        assertFalse(policy.configured)
        assertTrue(work.configs.capture(modelConfigBinding(registration.binding)).ruleSettings.isEmpty())
        assertTrue(ownerModelNames(command.path, 0).isEmpty())
        assertEquals("owner_model_policy_selection_replayed", assertFailsWith<CliProblem> { policy.configure(selection()) }.code)
    }
}
