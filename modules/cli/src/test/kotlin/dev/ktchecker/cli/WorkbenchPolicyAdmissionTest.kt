package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ClientBinding
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.*

/** Owner-registry and production-prepare controls; these do not execute a provider or qualify it. */
class WorkbenchPolicyAdmissionTest {
    private val rule = "ktc.compiler.common.abstract_class_member_not_implemented"
    private val image = Digests.sha256("synthetic-code-image-not-an-execution")

    private class NoExecutionProvider(override val supportedRuleIds: Set<String>, override val version: String = "fixture-1") : AnalysisProvider {
        override val id: String = "synthetic-admission-provider"
        var calls = 0
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls++
            error("Admission must not invoke a provider")
        }
    }

    private inner class Fixture {
        val state = PrivateState(Files.createTempDirectory("ktchecker-policy-admission-"))
        val catalog = Catalog()
        val configs = ConfigStore(state.namespace("configuration"), RuleAliases.identities(listOf(rule)))
        val binding = ClientBinding(state.ownerId, "project", "tree", "client", "lineage")
        val policy = configs.capture(ConfigBinding(state.ownerId, "project", "tree", "lineage"))
        val provider = NoExecutionProvider(setOf(rule))
        var profileCalls = 0
        val routes = listOf(ProviderRoute(provider, { true }, {
            profileCalls++
            error("Admission must not regenerate an execution profile")
        }, Digests.sha256("synthetic-route-implementation")))
        val store = WorkbenchPolicyAdmissions(state)
        val record: PreparedReview

        init {
            val source = "fun answer(): Int = 42\n"
            val root = Files.createDirectory(state.root.resolve("synthetic-source"))
            Files.writeString(root.resolve("Main.kt"), source)
            val activation = activationPlan(catalog, listOf(rule), true, emptyMap(), policy.policyDigest)
            val profiles = emptyMap<String, TrustedExecution>()
            val input = AnalysisInput(
                SnapshotManifest(Digests.sha256("synthetic-manifest-not-capture-certification"), "project", "tree",
                    listOf(SnapshotFile("Main.kt", Digests.sha256(source), source.toByteArray().size.toLong())), ownerId = state.ownerId),
                root.toString(), AnalysisContext(configurationDigest = policy.policyDigest, compilationFiles = listOf("Main.kt"),
                    fullProject = false, policies = mapOf("ktchecker.model" to "owner-declared-single-compilation",
                        "ktchecker.coverage" to "partial-project", ACTIVATION_POLICY_KEY to ContractJson.encodeToString(activation),
                        "ktchecker.execution-plan" to Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(profiles))))),
                requiredRuleIds = listOf(rule), changedPaths = listOf("Main.kt"),
            )
            record = PreparedReview(binding, "patch", "request", input, configs.exportSnapshot(policy), profiles, false,
                "explicit-rule-subset-not-full-release")
        }

        fun describe(record: PreparedReview = this.record, selectedRoutes: List<ProviderRoute> = routes,
                     codeImage: String = image) = describePolicyAdmission(record, policy, true, codeImage, catalog, selectedRoutes)
        fun path(namespace: String) = state.root.resolve(namespace)
            .resolve(Digests.sha256(record.input.analysisKey()).removePrefix("sha256:") + ".json")
        fun publish() = store.publishNew(record, describe())
    }

    @Test fun newAdmissionBindsAllPreparationBytesWithoutInvokingAnalysisOrProfiles() {
        val f = Fixture()
        val expected = f.describe()
        f.store.publishNew(f.record, expected)
        assertEquals(expected, f.store.requireRetained(f.record))
        assertEquals(f.record, f.state.read<PreparedReview>(f.path("prepared")))
        assertEquals(listOf(rule), expected.routes.single().selectedRuleIds)
        assertEquals(listOf(0), expected.routes.map { it.ordinal })
        assertTrue(expected.routes.single().hasProfileGenerator)
        assertEquals(f.record.configuration.registryDigest, expected.activation.registryDigest)
        assertEquals(f.policy.policyDigest, f.record.input.context.configurationDigest)
        assertFalse(f.record.executionAuthorized)
        assertFalse(f.record.input.context.fullProject)
        assertEquals(0, f.provider.calls)
        assertEquals(0, f.profileCalls)
    }

    @Test fun replayIsByteStableButAValidDifferentConsentBodyCannotReplaceTheSameInput() {
        val f = Fixture()
        f.publish()
        val before = Files.readAllBytes(f.path("prepared"))
        val descriptorBefore = Files.readAllBytes(f.path("policy-admissions"))
        f.publish()
        assertContentEquals(before, Files.readAllBytes(f.path("prepared")))
        assertContentEquals(descriptorBefore, Files.readAllBytes(f.path("policy-admissions")))
        val other = f.record.copy(executionAuthorized = true)
        val otherAdmission = f.describe(other)
        assertEquals(f.record.input.analysisKey(), other.input.analysisKey())
        assertNotEquals(f.describe().preparationDigest, otherAdmission.preparationDigest)
        assertEquals("prepared_binding_conflict", assertFailsWith<CliProblem> { f.store.publishNew(other, otherAdmission) }.code)
        assertContentEquals(before, Files.readAllBytes(f.path("prepared")))
        assertContentEquals(descriptorBefore, Files.readAllBytes(f.path("policy-admissions")))
        assertFalse(f.store.requireRetained(f.record).preparationDigest == otherAdmission.preparationDigest)
    }

    @Test fun matchingLegacyPreparationIsNeverBackfilledAndLookupDoesNotCreateARegistry() {
        val f = Fixture()
        val file = f.state.keyFile("prepared", f.record.input.analysisKey())
        f.state.write(file, f.record)
        val before = Files.readAllBytes(file)
        assertFalse(Files.exists(f.state.root.resolve("policy-admissions"), NOFOLLOW_LINKS))
        assertEquals("policy_admission_unavailable", assertFailsWith<CliProblem> { f.store.requireRetained(f.record) }.code)
        assertFalse(Files.exists(f.state.root.resolve("policy-admissions"), NOFOLLOW_LINKS))
        // Even an explicit owner retry with a freshly constructed descriptor cannot upgrade it.
        f.store.publishNew(f.record, f.describe())
        assertContentEquals(before, Files.readAllBytes(file))
        assertFalse(Files.exists(f.state.root.resolve("policy-admissions"), NOFOLLOW_LINKS))
        assertEquals("policy_admission_unavailable", assertFailsWith<CliProblem> { f.store.requireRetained(f.record) }.code)
    }

    @Test fun orphanDescriptorRefusesNewPreparationRatherThanRepairingHistory() {
        val f = Fixture()
        val descriptor = f.state.keyFile("policy-admissions", f.record.input.analysisKey())
        f.state.write(descriptor, f.describe())
        val before = Files.readAllBytes(descriptor)
        assertEquals("policy_admission_orphan", assertFailsWith<CliProblem> { f.publish() }.code)
        assertFalse(Files.exists(f.path("prepared"), NOFOLLOW_LINKS))
        assertContentEquals(before, Files.readAllBytes(descriptor))
    }

    @Test fun originalBodyAndCachedRecordCannotHideOnDiskIdentityChanges() {
        val f = Fixture()
        f.publish()
        val originalBytes = Files.readAllBytes(f.path("prepared"))
        val variants = listOf(
            f.record.copy(patchId = "another-patch"), f.record.copy(idempotencyKey = "another-request"),
            f.record.copy(binding = f.record.binding.copy(clientId = "other-client")),
            f.record.copy(binding = f.record.binding.copy(sessionId = "other-session")),
            f.record.copy(binding = f.record.binding.copy(generation = 2)),
            f.record.copy(executionAuthorized = true), f.record.copy(coverageScope = "changed-scope"),
            f.record.copy(contractVersion = "another-contract"),
            f.record.copy(configuration = f.record.configuration.copy(capturedAtEpochMillis = f.record.configuration.capturedAtEpochMillis + 1)),
        )
        for (changed in variants) {
            assertEquals(f.record.input.analysisKey(), changed.input.analysisKey())
            assertEquals("policy_admission_preparation_changed", assertFailsWith<CliProblem> { f.store.requireRetained(changed) }.code)
            assertContentEquals(originalBytes, Files.readAllBytes(f.path("prepared")))
        }
        f.state.write(f.path("prepared"), f.record.copy(executionAuthorized = true))
        assertEquals("policy_admission_preparation_changed", assertFailsWith<CliProblem> { f.store.requireRetained(f.record) }.code)
        // A newly supplied matching body still cannot inherit the old descriptor.
        assertEquals("policy_admission_invalid", assertFailsWith<CliProblem> {
            f.store.requireRetained(f.record.copy(executionAuthorized = true))
        }.code)
    }

    @Test fun schemaCodeRoutesAndUnknownOpaquePoliciesCannotBecomeEquivalentByName() {
        val f = Fixture()
        f.publish()
        val retained = f.store.requireRetained(f.record)
        assertEquals("policy_admission_invalid", assertFailsWith<CliProblem> {
            f.store.publishNew(f.record, retained.copy(format = "future-format"))
        }.code)
        val otherVersion = NoExecutionProvider(setOf(rule), "fixture-2")
        val otherRoutes = listOf(f.routes.single().copy(provider = otherVersion))
        assertNotEquals(retained, f.describe(selectedRoutes = otherRoutes))
        assertEquals("policy_admission_conflict", assertFailsWith<CliProblem> {
            f.store.publishNew(f.record, f.describe(selectedRoutes = otherRoutes))
        }.code)
        assertEquals("policy_admission_conflict", assertFailsWith<CliProblem> {
            f.store.publishNew(f.record, f.describe(codeImage = Digests.sha256("another-code-image")))
        }.code)
        val opaque = f.record.copy(input = f.record.input.copy(context = f.record.input.context.copy(
            policies = f.record.input.context.policies + ("unknown-policy" to "same-looking-value"))))
        assertEquals("policy_admission_model_unsupported", assertFailsWith<CliProblem> { f.describe(opaque) }.code)
        val live = ProviderRoute(f.provider, { true }, implementationIdentity = Digests.sha256("live-fixture"),
            preparedChild = { input, _ -> input }, finishAttempt = { })
        assertEquals("policy_admission_live_authority_unsupported", assertFailsWith<CliProblem> {
            f.describe(selectedRoutes = listOf(live))
        }.code)
        assertEquals(retained, f.store.requireRetained(f.record))
        assertEquals(0, f.provider.calls)
        assertEquals(0, f.profileCalls)
    }

    @Test fun returnedDescriptorCollectionsDoNotMutateTheRetainedOwnerRecord() {
        val f = Fixture()
        f.publish()
        val expected = f.store.requireRetained(f.record)
        val exposed = f.store.requireRetained(f.record)
        @Suppress("UNCHECKED_CAST")
        (exposed.routes as MutableList<PolicyRouteAuthority>).clear()
        assertEquals(expected, f.store.requireRetained(f.record))
        assertEquals(1, f.store.requireRetained(f.record).routes.size)
    }

    @Test fun realWorkbenchPreparationWritesAdmissionButItIsNeitherExecutionNorCompletion() {
        val options = WorkbenchOptions(Files.createTempDirectory("ktchecker-real-policy-admission-"), workerCount = 0)
        lateinit var record: PreparedReview
        Workbench(options).use { work ->
            val registration = work.register("project", "tree", "agent", "session", "lineage")
            val commit = work.overlay(OverlayDocument("project", "tree", "agent", "session", "patch",
                changes = listOf(OverlayChange("put", "Main.kt", content = "fun answer(): Int = 42\n"))))
            record = work.prepare(PreparationSpec("agent", commit.snapshotId, "patch", "request", listOf("Main.kt"),
                requiredRuleIds = listOf(rule), addKotlinStdlib = false), false)
            val admission = work.retainedPolicyAdmission(record)
            assertEquals(POLICY_ADMISSION_FORMAT, admission.format)
            assertFalse(record.executionAuthorized)
            assertFalse(record.input.context.fullProject)
            assertTrue(work.kernel.events(registration.client).events.isEmpty(), "Preparation cannot invent review execution or delivery")
            val status = work.submit(registration.client, record.patchId, record.idempotencyKey, record.input)
            assertEquals("queued", status.state)
            assertNull(status.result)
            assertFalse(work.kernel.prepareGate(registration.client).ready)
        }
        Workbench(options).use { work ->
            assertEquals(POLICY_ADMISSION_FORMAT, work.retainedPolicyAdmission(record).format)
            val file = work.state.keyFile("policy-admissions", record.input.analysisKey())
            val good = work.state.read<WorkbenchPolicyAdmission>(file)
            work.state.write(file, good.copy(codeImageDigest = Digests.sha256("changed-code")))
            assertEquals("policy_admission_authority_changed", assertFailsWith<CliProblem> { work.retainedPolicyAdmission(record) }.code)
        }
    }
}
