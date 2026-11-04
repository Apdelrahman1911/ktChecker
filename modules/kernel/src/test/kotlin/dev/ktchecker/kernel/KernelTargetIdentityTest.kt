package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Syntax/ledger tests only. No model, compiler, execution or finalization authority is certified. */
class KernelTargetIdentityTest {
    @TempDir lateinit var temp: Path
    private val binding = ClientBinding("owner", "workspace", "tree", "client", "lineage")
    private val config = KernelConfig(workerCount = 0)
    private val rule = "test/target-identity"
    private val provider = object : AnalysisProvider {
        override val id = "target-identity-never-executed"
        override val supportedRuleIds = setOf(rule)
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
            error("Target identity tests must not execute a provider")
    }

    private fun input(): AnalysisInput {
        val root = temp.resolve("source")
        Files.createDirectories(root)
        val text = "val fixture = 1\n"
        Files.writeString(root.resolve("Main.kt"), text)
        val file = SnapshotFile("Main.kt", Digests.sha256(text), text.toByteArray().size.toLong())
        return AnalysisInput(
            SnapshotManifest(Digests.sha256("target-identity-snapshot"), binding.workspaceId, binding.worktreeId,
                listOf(file), ownerId = binding.ownerId, declaredRoots = listOf(".")),
            root.toString(), AnalysisContext(
                scope = RuleContext(target = "", module = ":app"), compilerVersion = "2.4.21",
                modelDigest = Digests.sha256("synthetic-model"), dependencyDigest = Digests.sha256("synthetic-dependencies"),
                toolchainDigest = Digests.sha256("synthetic-toolchain"), configurationDigest = Digests.sha256("synthetic-config"),
                fullProject = true, compilationFiles = listOf(file.path)), listOf(rule),
        )
    }

    private fun plan(request: SubmitPlanRequest, inputs: List<AnalysisInput>, unsupported: Boolean = false) = RequiredReviewPlan(
        binding, request, Digests.sha256(canonical(inputs.first().snapshot)),
        Digests.sha256("synthetic-generated-inputs"), Digests.sha256("synthetic-model-certificate"),
        Digests.sha256("synthetic-impact-certificate"), inputs.mapIndexed { index, input ->
            if (unsupported) RequiredPlanSlot("slot-$index", input, state = "unsupported", reasons = listOf("typed_execution_unavailable"))
            else RequiredPlanSlot("slot-$index", input, assertNotNull(contextIdentity(input)))
        }, listOf(binding.clientId),
    )

    @Test fun emptyAndNamedTargetsKeepDistinctParentSlotsKeysEventsAndRestartIdentity() {
        val empty = input()
        val named = empty.copy(context = empty.context.copy(scope = empty.context.scope.copy(target = "jvm")))
        assertNotEquals(empty.context.scope.id, named.context.scope.id)
        assertNotEquals(empty.analysisKey(), named.analysisKey())
        val request = SubmitPlanRequest("patch", "key", "ref")
        val definition = plan(request, listOf(empty, named))
        var resolutions = 0
        val authority = ReviewPlanAuthority { actualBinding, actualRequest ->
            resolutions++
            assertEquals(binding, actualBinding)
            assertEquals(request, actualRequest)
            definition
        }
        val database = temp.resolve("ledger")
        lateinit var client: ClientRegistration
        lateinit var accepted: ParentPlanStatus
        KernelService.open(database, provider, config, planAuthority = authority).use { service ->
            client = service.registerClient(binding)
            accepted = service.submitPlan(client.client, request)
            assertEquals(listOf("", "jvm"), accepted.slots.map { it.context.target })
            assertEquals(accepted, service.submitPlan(client.client, request))
            assertEquals(1, resolutions)
            val event = service.events(client.client).events.single { it.kind == "plan_accepted" }
            assertEquals(accepted, ContractJson.decodeFromString<ReviewStatus>(event.payload).parentPlan)
            service.ledger.read { c ->
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work"))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM work_certificates"))
                val targets = c.query("SELECT frozen_json FROM work") {
                    ContractJson.decodeFromString<AnalysisInput>(it.getString(1)).context.scope.target
                }
                assertEquals(setOf("", "jvm"), targets.toSet())
            }
            assertFalse(service.prepareGate(client.client).ready)
        }
        // Durable exact retries need no replacement model authority and must not normalize a name.
        KernelService.open(database, provider, config).use { recovered ->
            assertEquals(accepted, recovered.submitPlan(client.client, request))
            assertEquals(accepted, recovered.planStatus(client.client, accepted.planId))
            assertFalse(recovered.prepareGate(client.client).ready)
        }
    }

    @Test fun unsupportedEmptyTargetMainAndTestContextsRemainUncertifiedObligations() {
        val base = input()
        val inputs = listOf(":consumer", ":lib").flatMap { module -> listOf("main", "test").map { compilation ->
            base.copy(context = base.context.copy(fullProject = false,
                scope = RuleContext(target = "", module = module, sourceSet = compilation, compilation = compilation)))
        } }
        val request = SubmitPlanRequest("unsupported-patch", "unsupported-key", "unsupported-ref")
        val definition = plan(request, inputs, unsupported = true)
        KernelService.open(temp.resolve("ledger"), provider, config,
            planAuthority = ReviewPlanAuthority { _, _ -> definition }).use { service ->
            val client = service.registerClient(binding)
            val accepted = service.submitPlan(client.client, request)
            assertEquals("incomplete", accepted.state)
            assertEquals(inputs.map { it.context.scope }, accepted.slots.map { it.context })
            assertEquals(4, accepted.slots.size)
            accepted.slots.forEach { slot ->
                assertEquals("", slot.context.target)
                assertEquals("unsupported", slot.state)
                assertEquals(listOf(rule), slot.requiredRuleIds)
                assertTrue("typed_execution_unavailable" in slot.reasons)
                val result = assertNotNull(service.status(client.client, slot.reviewId).result)
                assertEquals("incomplete", result.status)
                assertTrue(result.coverage.isEmpty())
            }
            service.ledger.read { c -> assertEquals(0L, c.count("SELECT COUNT(*) FROM work_certificates")) }
            assertFalse(service.prepareGate(client.client).ready)
        }
    }

    @Test fun onlyExactEmptyTargetBypassesTheExistingNonblankIdentifierCheck() {
        val base = input()
        KernelService.open(temp.resolve("ledger"), provider, config).use { service ->
            fun validate(scope: RuleContext) = service.validateInput(binding,
                SubmitRequest("patch", "key", base.copy(context = base.context.copy(scope = scope))))
            listOf("", "jvm", "x".repeat(512)).forEach { validate(base.context.scope.copy(target = it)) }
            listOf(" ", "\t", "\n", "\u2003", "\u0000", "jvm\u007f", "\u0085", "x".repeat(513)).forEach { target ->
                assertEquals("invalid_identifier", assertFailsWith<KernelException> {
                    validate(base.context.scope.copy(target = target))
                }.code)
            }
            listOf(base.context.scope.copy(module = ""), base.context.scope.copy(sourceSet = ""),
                base.context.scope.copy(compilation = "")).forEach { scope ->
                assertEquals("invalid_identifier", assertFailsWith<KernelException> { validate(scope) }.code)
            }
            service.ledger.read { c -> assertEquals(0L, c.count("SELECT COUNT(*) FROM reviews")) }
        }
    }

    @Test fun scalarIdempotencyCannotTreatEmptyTargetAsTheJvmDefault() {
        val empty = input()
        val named = empty.copy(context = empty.context.copy(scope = empty.context.scope.copy(target = "jvm")))
        KernelService.open(temp.resolve("ledger"), provider, config).use { service ->
            val client = service.registerClient(binding)
            val request = SubmitRequest("empty-patch", "empty-key", empty)
            val accepted = service.submit(client.client, request)
            assertEquals(accepted, service.submit(client.client, request))
            assertEquals("idempotency_conflict", assertFailsWith<KernelException> {
                service.submit(client.client, request.copy(input = named))
            }.code)
            val other = service.submit(client.client, SubmitRequest("named-patch", "named-key", named))
            assertNotEquals(accepted.reviewId, other.reviewId)
            service.ledger.read { c -> assertEquals(2L, c.count("SELECT COUNT(*) FROM work")) }
        }
    }
}
