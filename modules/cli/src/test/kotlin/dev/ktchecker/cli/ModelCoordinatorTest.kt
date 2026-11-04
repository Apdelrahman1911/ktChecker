package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import kotlin.test.*

/** Coordinator contract tests with a nonexecuting synthetic provider, not compiler evidence. */
class ModelCoordinatorTest {
    private val catalog = Catalog()
    private val rule = "ktc.compiler.common.unresolved_reference"
    private val profile = TrustedExecution("/nonexecuted-unit-owner-profile", listOf("unit-original-ordered-arguments"), true,
        executableDigest = Digests.sha256("nonexecuted-synthetic-profile"))

    private fun input(): AnalysisInput {
        val configuration = Digests.sha256("unit captured configuration")
        return AnalysisInput(SnapshotManifest(Digests.sha256("unit snapshot"), "workspace", "worktree", emptyList()),
            "/nonexecuted-unit-archive/sources", AnalysisContext(configurationDigest = configuration,
                policies = mapOf(ACTIVATION_POLICY_KEY to Digests.canonical(ContractJson.encodeToJsonElement(
                    activationPlan(catalog, listOf(rule), false, mapOf(rule to true), configuration))))), listOf(rule),
            outputRoot = "/nonexecuted-unit-output")
    }

    private fun provider(calls: AtomicInteger, expected: AnalysisInput? = null) = object : AnalysisProvider {
        override val id = "synthetic-retained-route"
        override val version = "unit/1"
        override val supportedRuleIds = setOf(rule)
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            calls.incrementAndGet()
            assertEquals(expected, input)
            return ProviderResult(id, "incomplete", coverage = listOf(Coverage(rule, input.context.scope,
                "unavailable", "synthetic_coordinator_only")), reasons = listOf("synthetic_coordinator_only"), providerVersion = version)
        }
    }

    @Test fun retainedRouteReceivesExactOwnerChildAndCannotInstallARegenerationFallback() {
        val input = input()
        val retained = input.copy(execution = profile)
        val calls = AtomicInteger()
        val cleanup = AtomicInteger()
        val selected = provider(calls, retained)
        assertFailsWith<IllegalArgumentException> {
            ProviderRoute(selected, { true }, executionProfile = { error("A model route must never regenerate this profile") },
                preparedChild = { _, _ -> retained }, finishAttempt = {})
        }
        val route = ProviderRoute(selected, { true }, preparedChild = { actual, ids ->
            assertEquals(input, actual); assertEquals(listOf(rule), ids); retained
        }, finishAttempt = { actual -> assertEquals(input, actual); cleanup.incrementAndGet() })
        val coordinator = ReviewCoordinator(catalog, listOf(route), ExecutionGrants(), CodeImage.capture(listOf(ReviewCoordinator::class.java)))
        assertTrue(coordinator.profiles(input).isEmpty(), "Serialized profile generation is not available for this owner route")
        val result = coordinator.analyze(input, CancellationToken.NONE)
        assertEquals(1, calls.get())
        assertEquals(1, cleanup.get())
        assertTrue(result.reasons.any { it.endsWith(":synthetic_coordinator_only") })
        assertFalse(result.coverage.any { it.qualified })
    }

    @Test fun failedRetainedProjectionDoesNotDowngradeToALaterLegacyProvider() {
        val calls = AtomicInteger()
        val legacyCalls = AtomicInteger()
        val cleanup = AtomicInteger()
        val first = ProviderRoute(provider(calls), { true }, preparedChild = { _, _ ->
            throw CliProblem("model_live_attempt_unavailable", "Synthetic unavailable owner attempt")
        }, finishAttempt = { cleanup.incrementAndGet() })
        val fallback = ProviderRoute(provider(legacyCalls), { true }, executionProfile = { profile })
        val coordinator = ReviewCoordinator(catalog, listOf(first, fallback), ExecutionGrants(),
            CodeImage.capture(listOf(ReviewCoordinator::class.java)))
        val result = coordinator.analyze(input(), CancellationToken.NONE)
        assertEquals(0, calls.get())
        assertEquals(0, legacyCalls.get())
        assertEquals(1, cleanup.get())
        assertEquals("failed", result.coverage.single().state)
        assertContains(result.reasons, "synthetic-retained-route:provider_execution_or_authorization_failed")
    }

    @Test fun fixedModelModeAndExactAlternativeInstallationChangeCoordinatorCacheIdentity() {
        OwnerModelFixtures().use { f ->
            val alternative = f.alternativeInstallation(reverseOrder = true)
            val selected = provider(AtomicInteger())
            val implementation = Digests.sha256("unit adapter code")
            val legacy = ProviderRoute(selected, { true }, executionProfile = { profile }, implementationIdentity = f.installation.digest)
            val model = ProviderRoute(selected, { true }, implementationIdentity = f.installation.digest,
                preparedChild = { actual, _ -> actual.copy(execution = profile) }, finishAttempt = {})
            val another = model.copy(implementationIdentity = alternative.digest)
            assertNotEquals(f.installation.digest, alternative.digest)
            assertNotEquals(coordinatorVersion(implementation, listOf(legacy)), coordinatorVersion(implementation, listOf(model)))
            assertNotEquals(coordinatorVersion(implementation, listOf(model)), coordinatorVersion(implementation, listOf(another)))
            assertEquals(coordinatorVersion(implementation, listOf(model)), coordinatorVersion(implementation, listOf(model.copy())))
        }
    }
}
