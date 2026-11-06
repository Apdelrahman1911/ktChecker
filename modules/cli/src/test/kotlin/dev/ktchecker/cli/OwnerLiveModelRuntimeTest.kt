package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmCompilerProvider
import dev.ktchecker.compiler.KotlinJvmInputMode
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.live.CompilerApplicationNamespace
import dev.ktchecker.compiler.live.model.ModelCompilerApplicationOpenResult
import dev.ktchecker.compiler.live.model.ModelCompilerGenerationPermit
import dev.ktchecker.compiler.live.model.ModelCompilerGenerationRequest
import dev.ktchecker.compiler.live.model.ModelCompilerJobMaterialization
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.KernelException
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationOwner
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.*

/**
 * SYNTHETIC owner-routing controls, not compiler/Native execution, cleanup or qualification.
 * OwnerModelFixtures supplies real registered snapshots/configuration and importer capabilities,
 * but its exporter receipts/JDK/compiler bytes are nonexecuted unit data. Generation descriptors
 * name ABSENT roots and deliberately advertise the required, false-for-these-bytes compiler pin.
 * Every generation permit below has a mandatory throwing beforeStart hook, before capture or
 * process opening. No fake Ready/application/session, bindingControls or launcher is used.
 */
class OwnerLiveModelRuntimeTest {
    private data class LiveComposition(
        val plans: OwnerModelPlans,
        val runtime: OwnerLiveModelRuntime,
        val coldAttempts: ModelAttemptGrants,
    )

    private fun runtime(
        f: OwnerModelFixtures,
        reviewTimeoutMillis: Long = 120_000,
        maximumGenerations: Int = 32,
        maximumActiveAttempts: Int = 16,
        maximumRetainedAttempts: Int = 1024,
        ownerId: String = f.state.ownerId,
    ) = OwnerLiveModelRuntime(ownerId, f.installation, syntheticBridgeDigest(), reviewTimeoutMillis,
        maximumGenerations, maximumActiveAttempts, maximumRetainedAttempts)

    private fun live(
        f: OwnerModelFixtures,
        selectedRuntime: OwnerLiveModelRuntime = runtime(f),
        maximumPlans: Int = 1024,
        verifyCode: () -> Unit = {},
    ): LiveComposition {
        val attempts = ModelAttemptGrants()
        return LiveComposition(OwnerModelPlans(f.state, f.snapshots, f.configs, f.catalog, null, null,
            attempts, verifyCode, maxPlans = maximumPlans, liveRuntime = selectedRuntime), selectedRuntime, attempts)
    }

    private fun prepare(f: OwnerModelFixtures, c: LiveComposition): PreparedOwnerModelPlan {
        f.setEnabled()
        val intent = c.plans.begin(f.binding(), f.request(), true)
        val capture = f.capture()
        return c.plans.prepare(intent, null, f.import(capture, intent), capture)
    }

    private fun namespaceFiles(f: OwnerModelFixtures, name: String): List<Path> =
        Files.list(f.state.namespace(name)).use { it.toList() }

    private fun expectProblem(code: String, action: () -> Unit) =
        assertEquals(code, assertFailsWith<CliProblem>(block = action).code)

    private fun stop(pool: ExecutorService) {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Synthetic control threads must terminate")
    }

    /** Deadline spending only, not a latency measurement. No unbounded polling/sleep. */
    private fun expire(deadline: OwnedApplicationDeadline) {
        val bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!deadline.isExpired() && System.nanoTime() < bound) Thread.sleep(10)
        assertTrue(deadline.isExpired(), "Synthetic original clock did not expire within the control bound")
    }

    @Test fun explicitLiveAndColdModesAreMutuallyExclusiveAndRequireTheExactLocalOwner() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            assertFailsWith<IllegalArgumentException> {
                WorkbenchOptions(state = f.state.root, modelCompilerInstallation = f.installation,
                    liveModelRuntime = selected)
            }
            val cold = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
            assertFailsWith<IllegalArgumentException> {
                OwnerModelPlans(f.state, f.snapshots, f.configs, f.catalog, f.installation, cold,
                    ModelAttemptGrants(), {}, liveRuntime = selected)
            }
            val foreign = runtime(f, ownerId = "synthetic-foreign-owner")
            assertFailsWith<IllegalArgumentException> { live(f, foreign) }
            // This check occurs before CodeImage, provider selection and kernel worker creation.
            assertFailsWith<IllegalArgumentException> {
                Workbench(WorkbenchOptions(state = f.state.root, workerCount = 0, liveModelRuntime = foreign))
            }
            expectProblem("permission_denied") {
                live(f, selected).plans.begin(f.binding().copy(ownerId = "synthetic-foreign-owner"), f.request(), true)
            }
            assertEquals(0, selected.retainedGenerations())
            assertEquals(0, selected.retainedAttempts())
        }
    }

    @Test fun originalIntentClockStartsBeforeOwnerVerificationAndConfigurationCapture() {
        OwnerModelFixtures().use { f ->
            val verifications = AtomicInteger()
            val c = live(f, runtime(f, reviewTimeoutMillis = 20), verifyCode = {
                verifications.incrementAndGet()
                Thread.sleep(50) // Controlled pre-configuration delay, not importer/latency evidence.
            })
            expectProblem("model_live_original_deadline_expired") {
                c.plans.begin(f.binding(), f.request(), true)
            }
            assertTrue(namespaceFiles(f, "model-intents").isEmpty(),
                "An expired ingress must not persist a newly captured configuration")
            expectProblem("model_live_intent_pending_or_unavailable") {
                c.plans.begin(f.binding(), f.request(), true)
            }
            assertEquals(1, verifications.get(), "Retry must not rerun capture or start another original clock")
            assertTrue(namespaceFiles(f, "model-intents").isEmpty())
            assertEquals(0, c.runtime.retainedGenerations())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun originalClockIncludesTimeBeforeRegistrationAndRequestPreparation() {
        OwnerModelFixtures().use { f ->
            val verifications = AtomicInteger()
            val c = live(f, runtime(f, reviewTimeoutMillis = 20),
                verifyCode = { verifications.incrementAndGet() })
            val original = assertNotNull(c.plans.ingressDeadline())
            Thread.sleep(50) // Simulated owner registration IO, not a latency measurement.
            expectProblem("model_live_original_deadline_expired") {
                c.plans.begin(f.binding(), f.request(), true, original)
            }
            expectProblem("model_live_intent_pending_or_unavailable") {
                c.plans.begin(f.binding(), f.request(), true)
            }
            assertEquals(0, verifications.get())
            assertTrue(namespaceFiles(f, "model-intents").isEmpty())
        }
    }

    @Test fun ownerIngressCannotReplaceConfiguredDurationOrReuseANewClockOnDuplicate() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            assertFailsWith<IllegalArgumentException> {
                c.plans.begin(f.binding(), f.request(), true, OwnedApplicationDeadline.afterMillis(3_600_000))
            }
            val original = assertNotNull(c.plans.ingressDeadline())
            val intent = c.plans.begin(f.binding(), f.request(), true, original)
            assertSame(original, intent.originalDeadline)
            val replacement = assertNotNull(c.plans.ingressDeadline())
            assertNotSame(original, replacement)
            assertSame(intent, c.plans.begin(f.binding(), f.request(), true, replacement))
            assertSame(original, intent.originalDeadline)
        }
    }

    @Test fun exactIntentDuplicatesRetainOriginalObjectsCapturedPolicyConsentAndClock() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val verifications = AtomicInteger()
            val c = live(f, verifyCode = { verifications.incrementAndGet() })
            val first = c.plans.begin(f.binding(), f.request(), true)
            val deadline = assertNotNull(first.originalDeadline)
            val configuration = first.configuration
            val evidence = first.evidence
            f.setEnabled(false)
            assertNotEquals(configuration.revisionToken, f.configs.capture(modelConfigBinding(f.binding())).revisionToken)
            val duplicate = c.plans.begin(f.binding(), f.request(), true)
            assertSame(first, duplicate)
            assertSame(deadline, duplicate.originalDeadline)
            assertSame(configuration, duplicate.configuration)
            assertSame(evidence, duplicate.evidence)
            assertEquals(deadline.identity, evidence.originalDeadlineIdentity)
            assertEquals(deadline.durationMillis, evidence.originalDeadlineMillis)
            assertEquals("live-model-preview", evidence.executionMode)
            assertTrue(configuration.ruleSettings.getValue(f.rule).enabled)
            expectProblem("model_intent_conflict") { c.plans.begin(f.binding(), f.request(), false) }
            assertSame(first, c.plans.begin(f.binding(), f.request(), true))
            assertEquals(1, verifications.get(), "Completed duplicates must return before verification/config capture")
        }
    }

    @Test fun overlappingIntentIsPendingAndCannotReplaceTheOriginalClockOrConsent() {
        OwnerModelFixtures().use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val verifications = AtomicInteger()
            val c = live(f, maximumPlans = 1, verifyCode = {
                verifications.incrementAndGet()
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS), "Release the bounded synthetic verification hook")
            })
            val pool = Executors.newFixedThreadPool(2)
            try {
                val opening = pool.submit<OwnerModelIntent> { c.plans.begin(f.binding(), f.request(), true) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pool.submit<Unit> {
                    expectProblem("model_live_intent_pending_or_unavailable") {
                        c.plans.begin(f.binding(), f.request(), true)
                    }
                    expectProblem("model_live_intent_pending_or_unavailable") {
                        c.plans.begin(f.binding(), f.request(), false)
                    }
                    expectProblem("model_live_intent_capacity") {
                        c.plans.begin(f.binding(), f.request("second"), true)
                    }
                    assertEquals(1, verifications.get())
                }.get(5, TimeUnit.SECONDS)
                release.countDown()
                val original = opening.get(5, TimeUnit.SECONDS)
                assertSame(original, c.plans.begin(f.binding(), f.request(), true))
                assertSame(original.originalDeadline, c.plans.begin(f.binding(), f.request(), true).originalDeadline)
                expectProblem("model_intent_conflict") { c.plans.begin(f.binding(), f.request(), false) }
                assertEquals(1, verifications.get())
            } finally { release.countDown(); stop(pool) }
        }
    }

    @Test fun failedIntentStartsKeepBoundedTombstonesInsteadOfGrantingRetryBudgets() {
        OwnerModelFixtures().use { f ->
            val verifications = AtomicInteger()
            val failure = IllegalStateException("SYNTHETIC owner-code verification denied")
            val c = live(f, maximumPlans = 1, verifyCode = { verifications.incrementAndGet(); throw failure })
            assertSame(failure, assertFailsWith<IllegalStateException> {
                c.plans.begin(f.binding(), f.request(), true)
            })
            assertTrue(namespaceFiles(f, "model-intents").isEmpty())
            repeat(2) {
                expectProblem("model_live_intent_pending_or_unavailable") {
                    c.plans.begin(f.binding(), f.request(), true)
                }
                expectProblem("model_live_intent_capacity") {
                    c.plans.begin(f.binding(), f.request("another-$it"), true)
                }
            }
            assertEquals(1, verifications.get())
            assertEquals(0, c.runtime.retainedAttempts())
            assertEquals(0, c.runtime.retainedGenerations())
        }
    }

    @Test fun importerWorkAndExpiredPreparationCannotRefreshAnAlreadyCapturedOriginalClock() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = live(f, runtime(f, reviewTimeoutMillis = 2_000))
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val deadline = assertNotNull(intent.originalDeadline)
            val capture = f.capture()
            val imported = f.import(capture, intent)
            assertSame(deadline, c.plans.begin(f.binding(), f.request(), true).originalDeadline)
            expire(deadline) // Synthetic importer/queue delay; no compiler or timing objective claim.
            expectProblem("model_live_original_deadline_expired") {
                c.plans.prepare(intent, null, imported, capture)
            }
            val duplicate = c.plans.begin(f.binding(), f.request(), true)
            assertSame(intent, duplicate)
            assertSame(deadline, duplicate.originalDeadline)
            assertTrue(assertNotNull(duplicate.originalDeadline).isExpired())
            assertFalse(intent.used.get(), "An expired intent is denied before one-use preparation admission")
            assertNull(c.plans.resolve(f.binding(), f.request()))
            assertTrue(namespaceFiles(f, "model-plans").isEmpty())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun full1665CatalogLivePreparationKeepsAllContextsAndTruthfulEmptyColdProfiles() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = live(f)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val deadline = assertNotNull(intent.originalDeadline)
            val previous = f.capture()
            val before = f.import(previous, intent)
            val changed = "lib/src/main/kotlin/Api.kt"
            val capture = f.capture(f.files() + (changed to "fun apiValue() = 42\n"))
            val after = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, before, after, capture)
            assertEquals(1665, f.catalog.ids.size, "Catalog visibility is a required vector, not coverage credit")
            assertEquals(1665, f.catalog.records.size)
            assertTrue(OwnerFullCatalog1665.accepts(f.catalog.ids.sorted()))
            assertEquals(after.compilationIds.size, prepared.assembly.plan.slots.size)
            assertEquals(after.compilationIds.toSet(), prepared.assembly.provenance.slots.map { it.compilationId }.toSet())
            assertEquals(setOf(":lib", ":app"), prepared.assembly.plan.slots.map { it.input.context.scope.module }.toSet())
            assertEquals(listOf(changed), prepared.assembly.provenance.impact.changedFiles)
            assertTrue(prepared.preparationReasons.isEmpty())
            prepared.assembly.plan.slots.forEach { slot ->
                assertEquals("pending", slot.state)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                assertEquals(after.snapshot, slot.input.snapshot)
                assertEquals(before.snapshot.snapshotId, slot.input.baselineSnapshotId)
                assertEquals(listOf(changed), slot.input.changedPaths)
                assertTrue(slot.input.snapshot.files.size > assertNotNull(slot.input.context.compilationFiles).size)
                assertNull(slot.input.execution)
                assertEquals(emptyColdProfilesDigest(), slot.input.context.policies["ktchecker.execution-plan"])
                val evidence = assertNotNull(c.plans.evidence(slot.input))
                assertEquals(f.binding(), evidence.binding)
                assertEquals(emptyMap<String, TrustedExecution>(), evidence.profiles)
                assertEquals(listOf(f.rule), evidence.compilerRuleIds)
                assertNotNull(evidence.compilerBindingDigest)
                assertNotNull(evidence.approvalDigest)
                assertSame(intent.configuration, c.plans.policy(f.binding(), slot.input))
                assertNotEquals(f.installation.digest, slot.input.context.toolchainDigest)
                val activation = validatedActivation(slot.input, f.catalog)
                assertEquals(f.catalog.ids, activation.keys)
                assertEquals(setOf(f.rule), activation.values.filter { it.active }.map { it.ruleId }.toSet())
            }
            assertSame(deadline, c.plans.begin(f.binding(), f.request(), true).originalDeadline)
            assertEquals(prepared.assembly.plan, c.plans.resolve(f.binding(), f.request()))
            assertEquals(0, c.runtime.retainedGenerations(), "Preparing a model never opens an application")
            assertEquals(0, c.runtime.retainedAttempts(), "Whole-plan admission is not an archive-bound attempt")
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun removedOrRetargetedContextsStayFullCatalogUnsupportedObligationsInLiveMode() {
        for (kind in listOf("removed", "retargeted")) OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = live(f)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val previous = f.capture()
            val before = f.import(previous, intent)
            val capture = if (kind == "removed") f.capture(f.files().filterKeys { !it.startsWith("app/") }) else previous
            val graph = if (kind == "removed") f.graph(capture, listOf(":lib")) else f.graph(capture).let { graph ->
                graph.copy(compilations = graph.compilations.map { if (it.moduleId == ":lib") it.copy(target = "desktop") else it })
            }
            val after = f.import(capture, intent, graph)
            val prepared = c.plans.prepare(intent, before, after, capture)
            assertEquals(after.compilationIds.size + 1, prepared.assembly.plan.slots.size)
            val old = prepared.assembly.plan.slots.single { it.state == "unsupported" }
            assertContains(old.reasons, if (kind == "removed") "required_compilation_removed_unresolved" else "required_context_retargeted_unresolved")
            assertEquals(f.catalog.ids.sorted(), old.input.requiredRuleIds)
            assertTrue(assertNotNull(old.input.context.compilationFiles).isEmpty())
            assertEquals(after.snapshot, old.input.snapshot)
            prepared.assembly.plan.slots.forEach { slot ->
                assertTrue(assertNotNull(c.plans.evidence(slot.input)).profiles.isEmpty())
                assertNotNull(c.plans.policy(f.binding(), slot.input))
                assertNull(slot.input.execution)
            }
            assertEquals(after.compilationIds.size, prepared.assembly.plan.slots.count { it.state == "pending" })
            assertEquals(0, c.runtime.retainedGenerations())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun withheldExecutionConsentKeepsEveryLiveContextUnsupportedWithoutColdFallbackProfiles() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = live(f)
            val intent = c.plans.begin(f.binding(), f.request(), false)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val prepared = c.plans.prepare(intent, null, model, capture)
            assertEquals(model.compilationIds.toSet(), prepared.preparationReasons.keys)
            assertEquals(setOf("model_execution_not_authorized"), prepared.preparationReasons.values.toSet())
            prepared.assembly.plan.slots.forEach { slot ->
                assertEquals("unsupported", slot.state)
                assertEquals(f.catalog.ids.sorted(), slot.input.requiredRuleIds)
                val evidence = assertNotNull(c.plans.evidence(slot.input))
                assertFalse(evidence.executionAuthorized)
                assertTrue(evidence.profiles.isEmpty())
                assertTrue(evidence.compilerRuleIds.isEmpty())
                assertNull(slot.input.execution)
            }
            assertEquals(0, c.runtime.retainedAttempts())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun restartRestoresObligationsAndPolicyEvidenceButNotClocksPreparationsOrLiveAuthority() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val selected = runtime(f)
            val first = live(f, selected)
            val intent = first.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val prepared = first.plans.prepare(intent, null, model, capture)
            val file = namespaceFiles(f, "model-intents").single()
            val originalBytes = Files.readAllBytes(file)
            val second = live(f, selected)
            expectProblem("model_live_intent_not_restorable") {
                second.plans.begin(f.binding(), f.request(), true)
            }
            expectProblem("model_live_intent_pending_or_unavailable") {
                second.plans.begin(f.binding(), f.request(), true)
            }
            assertContentEquals(originalBytes, Files.readAllBytes(file))
            assertEquals("model_live_authority_unavailable", assertFailsWith<KernelException> {
                second.plans.resolve(f.binding(), f.request())
            }.code)
            expectProblem("model_intent_invalid_or_replayed") {
                second.plans.prepare(intent, null, model, capture)
            }
            prepared.assembly.plan.slots.forEachIndexed { index, slot ->
                assertEquals(first.plans.evidence(slot.input), second.plans.evidence(slot.input))
                assertEquals(intent.configuration.revisionToken, assertNotNull(second.plans.policy(f.binding(), slot.input)).revisionToken)
                val archive = f.archive(slot.input, "restart-$index")
                assertEquals("model_live_authority_unavailable", assertFailsWith<KernelException> {
                    second.plans.observe(slot.input, archive.effective, archive::verify)
                }.code)
                assertEquals(0, archive.verifications)
                assertFalse(selected.routes(archive.effective))
            }
            val coldRestart = f.composition(selected = null)
            expectProblem("model_live_intent_not_restorable") {
                coldRestart.plans.begin(f.binding(), f.request(), true)
            }
            assertContentEquals(originalBytes, Files.readAllBytes(file))
            assertEquals(0, selected.retainedAttempts())
            assertEquals(0, selected.retainedGenerations())
            assertEquals(0, second.coldAttempts.activeCount())
        }
    }

    @Test fun serializedOrForeignIntentCannotManufactureLivePreparationOrReplayOneUse() {
        OwnerModelFixtures().use { f ->
            f.setEnabled()
            val c = live(f)
            val intent = c.plans.begin(f.binding(), f.request(), true)
            val capture = f.capture()
            val model = f.import(capture, intent)
            val serializedEcho = OwnerModelIntent(Any(), intent.evidence.copy(), intent.configuration, intent.originalDeadline)
            expectProblem("model_intent_invalid_or_replayed") {
                c.plans.prepare(serializedEcho, null, model, capture)
            }
            assertFalse(intent.used.get())
            val prepared = c.plans.prepare(intent, null, model, capture)
            expectProblem("model_intent_invalid_or_replayed") {
                c.plans.prepare(intent, null, model, capture)
            }
            assertEquals(prepared.assembly.plan, c.plans.resolve(f.binding(), f.request()))
            assertEquals(0, c.runtime.retainedAttempts())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun missingGenerationLeavesStickyLiveRoutingAndCoordinatorNeverTriesALaterColdProvider() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val original = prepare(f, c).assembly.plan.slots.first().input
            val archive = f.archive(original, "missing-generation")
            expectProblem("model_live_generation_unavailable") {
                c.plans.observe(original, archive.effective, archive::verify)
            }
            assertEquals(0, archive.verifications, "Missing generation denies before archive hooks or grant binding")
            assertTrue(c.runtime.routes(archive.effective))
            assertFalse(c.runtime.routes(original))
            expectProblem("model_live_attempt_unavailable") { c.runtime.child(archive.effective, listOf(f.rule)) }
            val coldCalls = AtomicInteger()
            val profileCalls = AtomicInteger()
            val fallback = object : AnalysisProvider {
                override val id = "synthetic-cold-fallback"
                override val version = "unit/1"
                override val supportedRuleIds = setOf(f.rule)
                override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                    coldCalls.incrementAndGet()
                    throw AssertionError("SYNTHETIC cold fallback must never run")
                }
            }
            val liveRoute = ProviderRoute(c.runtime.provider, c.runtime::routes,
                implementationIdentity = c.runtime.implementationIdentity,
                preparedChild = c.runtime::child, finishAttempt = c.runtime::finish)
            // Deliberately applicable: route ordering, not a cooperative fallback predicate, must deny downgrade.
            val coldRoute = ProviderRoute(fallback, { true }, executionProfile = {
                profileCalls.incrementAndGet()
                throw AssertionError("SYNTHETIC cold profile must never be generated")
            })
            val coordinator = ReviewCoordinator(f.catalog, listOf(liveRoute, coldRoute), ExecutionGrants(),
                CodeImage.capture(listOf(ReviewCoordinator::class.java)))
            assertEquals(mapOf(liveRoute to listOf(f.rule)), coordinator.plannedRoutes(archive.effective))
            assertTrue(coordinator.profiles(archive.effective).isEmpty())
            val result = coordinator.analyze(archive.effective, CancellationToken.NONE)
            assertEquals("failed", result.coverage.single { it.ruleId == f.rule }.state)
            assertContains(result.reasons, "${c.runtime.provider.id}:provider_execution_or_authorization_failed")
            assertTrue(result.findings.isEmpty())
            assertFalse(result.coverage.any { it.qualified })
            assertEquals(0, coldCalls.get())
            assertEquals(0, profileCalls.get())
            assertEquals(0, c.coldAttempts.activeCount())
            assertTrue(c.runtime.routes(archive.effective), "Coordinator finally/finish cannot erase the live-route tombstone")
            val editedTags = archive.effective.copy(context = archive.effective.context.copy(
                policies = archive.effective.context.policies + ("synthetic-requested-mode" to "cold")))
            assertTrue(c.runtime.routes(editedTags), "Routing is by reserved output, not mutable model/profile labels")
            expectProblem("model_live_attempt_unavailable") { c.runtime.child(editedTags, listOf(f.rule)) }
        }
    }

    @Test fun failedObserverRouteMarkersAreBoundedAndFinishNeverEvictsOldOutputIdentity() {
        OwnerModelFixtures().use { f ->
            val c = live(f, runtime(f, maximumActiveAttempts = 1, maximumRetainedAttempts = 2))
            val original = prepare(f, c).assembly.plan.slots.first().input
            val one = f.archive(original, "tombstone-one")
            val two = f.archive(original, "tombstone-two")
            val three = f.archive(original, "tombstone-three")
            expectProblem("model_live_generation_unavailable") { c.plans.observe(original, one.effective, one::verify) }
            c.runtime.finish(one.effective)
            expectProblem("model_live_attempt_identity_conflict") { c.plans.observe(original, one.effective, one::verify) }
            expectProblem("model_live_generation_unavailable") { c.plans.observe(original, two.effective, two::verify) }
            repeat(2) { c.runtime.finish(one.effective); c.runtime.finish(two.effective) }
            expectProblem("model_live_attempt_capacity") { c.plans.observe(original, three.effective, three::verify) }
            assertEquals(2, c.runtime.retainedAttempts())
            assertTrue(c.runtime.routes(one.effective))
            assertTrue(c.runtime.routes(two.effective))
            assertFalse(c.runtime.routes(three.effective), "A denied third reservation cannot replace either retained marker")
            expectProblem("model_live_attempt_unavailable") { c.runtime.child(one.effective, listOf(f.rule)) }
            assertEquals(0, one.verifications + two.verifications + three.verifications)
            assertEquals(0, c.runtime.retainedGenerations())
            assertEquals(0, c.coldAttempts.activeCount())
        }
    }

    @Test fun failedGenerationCannotGrantAnArchiveAttemptOrLoseItsStickyLiveRoute() {
        OwnerModelFixtures().use { f ->
            val c = live(f)
            val generation = SyntheticGeneration(f, c.runtime, "failed-before-archive")
            val unavailable = generation.open()
            assertSyntheticUnavailable(unavailable)
            val original = prepare(f, c).assembly.plan.slots.first().input
            val archive = f.archive(original, "failed-generation")
            expectProblem("model_live_generation_unavailable") {
                c.plans.observe(original, archive.effective, archive::verify)
            }
            assertTrue(c.runtime.routes(archive.effective))
            expectProblem("model_live_attempt_unavailable") { c.runtime.child(archive.effective, listOf(f.rule)) }
            c.runtime.finish(archive.effective)
            assertTrue(c.runtime.routes(archive.effective))
            assertSame(unavailable.opened, generation.open().opened)
            assertEquals(1, generation.beforeStartCalls.get())
            assertEquals(0, archive.verifications)
            assertEquals(0, c.coldAttempts.activeCount())
            generation.assertNeverStarted()
        }
    }

    @Test fun generationOwnerNamespaceBridgeAndEqualDigestInstallationSubstitutionsRejectBeforeCallbacks() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            val equivalent = KotlinJvmToolchain(f.installation.javaExecutable, f.installation.compilerClasspath)
            assertEquals(f.installation.digest, equivalent.digest)
            assertNotSame(f.installation, equivalent)
            val variants = listOf(
                SyntheticGeneration(f, selected, "foreign-owner", selectedOwner = OwnedApplicationOwner()),
                SyntheticGeneration(f, selected, "foreign-namespace", selectedNamespace =
                    CompilerApplicationNamespace("synthetic-foreign-owner", f.namespace.workspaceId, f.namespace.worktreeId)),
                SyntheticGeneration(f, selected, "different-installation-object", selectedInstallation = equivalent),
                SyntheticGeneration(f, selected, "different-bridge", bridgeDigest = Digests.sha256("SYNTHETIC other bridge")),
            )
            variants.forEach { generation ->
                expectProblem("model_live_generation_owner_mismatch") { generation.open() }
                assertEquals(0, generation.beforeStartCalls.get())
                assertEquals(0, generation.cancellationChecks.get())
                generation.assertNeverStarted()
            }
            assertEquals(0, selected.retainedGenerations())
            val original = SyntheticGeneration(f, selected, "owner-original")
            assertSyntheticUnavailable(original.open())
            assertEquals(1, original.beforeStartCalls.get())
            assertEquals(1, selected.retainedGenerations())
            original.assertNeverStarted()
        }
    }

    @Test fun exactUnavailableGenerationDuplicatesKeepOriginalOpenObjectsAndNeverReopen() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            val generation = SyntheticGeneration(f, selected, "original")
            val first = generation.open()
            assertSyntheticUnavailable(first)
            val cancellationChecks = generation.cancellationChecks.get()
            repeat(3) {
                val duplicate = generation.open()
                assertEquals(first, duplicate)
                assertSame(first.opened, duplicate.opened)
                assertSame(generation.request, generation.permit.request)
                assertSame(generation.deadline, generation.permit.request.originalDeadline)
                assertSame(first.opened, selected.status(generation.request).opened)
            }
            assertEquals(1, generation.beforeStartCalls.get())
            assertEquals(cancellationChecks, generation.cancellationChecks.get())
            val retirement = selected.retire(generation.request)
            assertEquals("unavailable", retirement.state)
            assertNull(retirement.retirement, "A pre-capture denial is not fabricated CLOSED cleanup")
            assertSame(first.opened, retirement.opened)
            assertSame(first.opened, generation.open().opened)
            assertEquals(1, selected.retainedGenerations())
            generation.assertNeverStarted()
        }
    }

    @Test fun freshGenerationClockOrOwnerRestartCannotReplaceARetainedRequestCapability() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            val generation = SyntheticGeneration(f, selected, "retained-request")
            val original = generation.open()
            assertSyntheticUnavailable(original)
            val freshDeadline = OwnedApplicationDeadline.afterMillis(120_000)
            val replacement = ModelCompilerGenerationRequest(generation.request.owner, generation.request.namespace,
                generation.request.installation, generation.request.launch, freshDeadline)
            val replacementCalls = AtomicInteger()
            expectProblem("model_live_generation_conflict") {
                selected.open(replacement, throwingPermit(replacement, replacementCalls), generation.custody, generation.cancellation)
            }
            val restarted = runtime(f)
            assertEquals(selected.ownerId, restarted.ownerId)
            assertNotSame(selected.owner, restarted.owner)
            expectProblem("model_live_generation_owner_mismatch") {
                restarted.open(generation.request, generation.permit, generation.custody, generation.cancellation)
            }
            assertSame(generation.deadline, generation.request.originalDeadline)
            assertSame(original.opened, generation.open().opened)
            assertEquals(1, generation.beforeStartCalls.get())
            assertEquals(0, replacementCalls.get())
            assertEquals(1, selected.retainedGenerations())
            assertEquals(0, restarted.retainedGenerations())
            generation.assertNeverStarted()
        }
    }

    @Test fun openingDuplicateAndCallbackSubstitutionsCannotReplaceAPendingGenerationOrBlockStatus() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f, maximumGenerations = 1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val generation = SyntheticGeneration(f, selected, "opening", beforeStop = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS), "Release the bounded synthetic beforeStart hook")
            })
            val another = SyntheticGeneration(f, selected, "capacity-while-opening")
            val pool = Executors.newFixedThreadPool(2)
            try {
                val opening = pool.submit<OwnerLiveGenerationStatus> { generation.open() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                pool.submit<Unit> {
                    val pending = selected.status(generation.request)
                    assertEquals("opening", pending.state)
                    assertNull(pending.opened)
                    assertEquals(pending, generation.open())
                    val replacementCalls = AtomicInteger()
                    val clone = generation.cloneRequest()
                    val clonePermit = throwingPermit(clone, replacementCalls)
                    expectProblem("model_live_generation_conflict") {
                        selected.open(clone, clonePermit, generation.custody, generation.cancellation)
                    }
                    expectProblem("model_live_generation_permit_mismatch") {
                        selected.open(generation.request, clonePermit, generation.custody, generation.cancellation)
                    }
                    expectProblem("model_live_generation_conflict") {
                        selected.open(generation.request, throwingPermit(generation.request, replacementCalls),
                            generation.custody, generation.cancellation)
                    }
                    expectProblem("model_live_generation_conflict") {
                        selected.open(generation.request, generation.permit, NeverMaterializeCustody(), generation.cancellation)
                    }
                    val replacementCancellationChecks = AtomicInteger()
                    expectProblem("model_live_generation_conflict") {
                        selected.open(generation.request, generation.permit, generation.custody,
                            CancellationToken { replacementCancellationChecks.incrementAndGet(); false })
                    }
                    expectProblem("model_live_generation_capacity") { another.open() }
                    expectProblem("model_live_generation_not_registered") { selected.status(clone) }
                    expectProblem("model_live_generation_not_registered") { selected.retire(clone) }
                    assertEquals(0, replacementCalls.get())
                    assertEquals(0, replacementCancellationChecks.get())
                    assertEquals(0, another.beforeStartCalls.get())
                    assertEquals("opening", selected.status(generation.request).state)
                }.get(5, TimeUnit.SECONDS)
                release.countDown()
                val original = opening.get(5, TimeUnit.SECONDS)
                assertSyntheticUnavailable(original)
                assertSame(original.opened, generation.open().opened)
                assertEquals(1, generation.beforeStartCalls.get())
                assertEquals(1, selected.retainedGenerations())
                generation.assertNeverStarted()
                another.assertNeverStarted()
            } finally { release.countDown(); stop(pool) }
        }
    }

    @Test fun beforeStartCanReenterStatusDuplicateAndRetireFromAnotherThreadWithoutHoldingTheOwnerLock() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            val queried = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            lateinit var generation: SyntheticGeneration
            generation = SyntheticGeneration(f, selected, "cross-thread-reentrant", beforeStop = {
                val retired = pool.submit<OwnerLiveGenerationStatus> {
                    assertEquals("opening", selected.status(generation.request).state)
                    assertEquals("opening", generation.open().state)
                    selected.retire(generation.request).also { queried.countDown() }
                }.get(5, TimeUnit.SECONDS)
                assertTrue(queried.await(5, TimeUnit.SECONDS))
                assertEquals("opening", retired.state)
                assertNull(retired.opened)
                assertNull(retired.retirement)
            })
            try {
                val original = generation.open()
                assertSyntheticUnavailable(original)
                assertEquals(1, generation.beforeStartCalls.get())
                val duplicateRetirement = selected.retire(generation.request)
                assertEquals("unavailable", duplicateRetirement.state)
                assertSame(original.opened, duplicateRetirement.opened)
                assertNull(duplicateRetirement.retirement)
                generation.assertNeverStarted()
            } finally { stop(pool) }
        }
    }

    @Test fun unavailableNamespacesAndGenerationTombstonesCannotBeEvictedOrRelabeledAsClosed() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f, maximumGenerations = 2)
            val one = SyntheticGeneration(f, selected, "one")
            val original = one.open()
            assertSyntheticUnavailable(original)
            val sameNamespace = SyntheticGeneration(f, selected, "same-namespace")
            expectProblem("model_live_namespace_occupied") { sameNamespace.open() }
            assertEquals(0, sameNamespace.beforeStartCalls.get())
            assertEquals(1, selected.retainedGenerations())
            assertNull(selected.retire(one.request).retirement)
            expectProblem("model_live_namespace_occupied") { sameNamespace.open() }
            val otherNamespace = CompilerApplicationNamespace(f.state.ownerId, f.namespace.workspaceId, "synthetic-other-worktree")
            val two = SyntheticGeneration(f, selected, "two", selectedNamespace = otherNamespace)
            assertSyntheticUnavailable(two.open())
            val three = SyntheticGeneration(f, selected, "three", selectedNamespace =
                CompilerApplicationNamespace(f.state.ownerId, f.namespace.workspaceId, "synthetic-third-worktree"))
            repeat(2) {
                selected.retire(one.request)
                selected.retire(two.request)
                expectProblem("model_live_generation_capacity") { three.open() }
                assertSame(original.opened, one.open().opened)
                assertEquals(2, selected.retainedGenerations())
            }
            assertEquals(0, three.beforeStartCalls.get())
            listOf(one, two, sameNamespace, three).forEach { it.assertNeverStarted() }
        }
    }

    @Test fun spentGenerationClockAndPresentInterruptRejectBeforeReservationsOrOwnerCallbacks() {
        OwnerModelFixtures().use { f ->
            val selected = runtime(f)
            val expired = SyntheticGeneration(f, selected, "expired", deadline = OwnedApplicationDeadline.afterMillis(500))
            expire(expired.deadline)
            repeat(2) { expectProblem("model_live_original_deadline_expired") { expired.open() } }
            assertEquals(0, expired.beforeStartCalls.get())
            assertEquals(0, expired.cancellationChecks.get())
            assertEquals(0, selected.retainedGenerations())
            val interrupted = SyntheticGeneration(f, selected, "interrupted")
            try {
                Thread.currentThread().interrupt()
                expectProblem("model_live_interrupted") { interrupted.open() }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            assertEquals(0, interrupted.beforeStartCalls.get())
            assertEquals(0, interrupted.cancellationChecks.get())
            assertEquals(0, selected.retainedGenerations())
            expired.assertNeverStarted()
            interrupted.assertNeverStarted()
        }
    }

    private fun assertSyntheticUnavailable(status: OwnerLiveGenerationStatus) {
        assertEquals("unavailable", status.state)
        assertEquals("model_live_generation_unavailable", status.reason)
        val opened = assertIs<ModelCompilerApplicationOpenResult.Unavailable>(status.opened)
        assertEquals(listOf("compiler_model_live_generation_verification_failed"), opened.reasons)
        assertNull(opened.cleanup)
        assertNull(status.retirement)
    }

    private class SyntheticBeforeStartStop : IllegalStateException("SYNTHETIC beforeStart hard stop; never launch")

    /** Optional observer cannot replace the final throw: all paths stop before capture/process. */
    private fun throwingPermit(request: ModelCompilerGenerationRequest, starts: AtomicInteger,
        liveChecks: AtomicInteger = AtomicInteger(), beforeStop: () -> Unit = {}) =
        ModelCompilerGenerationPermit(request, verifyBeforeStart = {
            starts.incrementAndGet()
            beforeStop() // If this throws, that is also a pre-capture hard stop.
            throw SyntheticBeforeStartStop()
        }, verifyLive = {
            liveChecks.incrementAndGet()
            throw AssertionError("SYNTHETIC generation verifyLive must never be reached")
        })

    private class NeverMaterializeCustody : OwnerModelMaterializationCustody {
        val calls = AtomicInteger()
        override fun register(materialization: ModelCompilerJobMaterialization) {
            calls.incrementAndGet()
            throw AssertionError("SYNTHETIC controls must never create a job materialization")
        }
        override fun verify(materialization: ModelCompilerJobMaterialization) {
            calls.incrementAndGet()
            throw AssertionError("SYNTHETIC controls must never verify a runtime job materialization")
        }
    }

    private inner class SyntheticGeneration(
        f: OwnerModelFixtures,
        private val selectedRuntime: OwnerLiveModelRuntime,
        name: String,
        val deadline: OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(120_000),
        selectedOwner: OwnedApplicationOwner = selectedRuntime.owner,
        selectedNamespace: CompilerApplicationNamespace = CompilerApplicationNamespace(f.state.ownerId,
            f.namespace.workspaceId, f.namespace.worktreeId),
        selectedInstallation: KotlinJvmToolchain = f.installation,
        bridgeDigest: String = selectedRuntime.workerBridgeDigest,
        beforeStop: () -> Unit = {},
    ) {
        private val token = Digests.sha256("SYNTHETIC generation $name").removePrefix("sha256:").take(32)
        val generationRoot: Path = f.work.resolve("nonexecuted-generations/$token")
        val beforeStartCalls = AtomicInteger()
        val liveChecks = AtomicInteger()
        val cancellationChecks = AtomicInteger()
        val cancellation = CancellationToken { cancellationChecks.incrementAndGet(); false }
        val custody = NeverMaterializeCustody()
        val request: ModelCompilerGenerationRequest
        val permit: ModelCompilerGenerationPermit

        init {
            assertFalse(Files.exists(generationRoot, NOFOLLOW_LINKS), "Synthetic generation roots must be absent")
            val installed = selectedInstallation.installationView()
            val artifacts = installed.compilerClasspath.mapIndexed { index, artifact ->
                val fileName = Path.of(artifact.identity.path).fileName.toString()
                val digest = if (fileName == "kotlin-compiler-embeddable-2.4.21.jar") ModelWire.COMPILER_SHA256 else artifact.identity.digest
                ModelWire.Artifact(artifact.identity.coordinate, fileName, digest, artifact.size,
                    "synthetic-compiler-$token-$index")
            }
            val generation = ModelWire.Generation(ModelWire.PROFILE, ModelWire.API, ModelWire.COMPILER_VERSION,
                ModelWire.Jdk(Wire.Root(installed.runtime.root.toString(), "synthetic-jdk-home"),
                    installed.runtime.javaExecutable.toString(), installed.runtime.executableDigest, installed.runtime.digest, 21),
                ModelWire.Artifact("synthetic:model-bridge", "bridge.jar", bridgeDigest,
                    syntheticBridgeBytes().size.toLong(), "synthetic-bridge-$token"), artifacts)
            val root = Wire.Root(generationRoot.toString(), "synthetic-generation-root-$token")
            val identity = Wire.Identity(token, selectedOwner.identity, selectedNamespace.digest,
                Digests.sha256("SYNTHETIC owner CLI controls; NOT executed"), bridgeDigest,
                ModelWire.compilerClosureDigest(generation), ModelWire.toolchainDigest(generation), ModelWire.API, ModelWire.PROFILE)
            val launch = Wire.Launch(identity, Wire.Limits(4, 1_048_576, 1_048_576), root, generationRoot.toString(),
                ModelWire.command(root, generation), ModelWire.environment(root, generation), ModelWire.encodeGeneration(generation))
            request = ModelCompilerGenerationRequest(selectedOwner, selectedNamespace, selectedInstallation, launch, deadline)
            permit = throwingPermit(request, beforeStartCalls, liveChecks, beforeStop)
        }

        fun open(): OwnerLiveGenerationStatus = selectedRuntime.open(request, permit, custody, cancellation)
        fun cloneRequest(): ModelCompilerGenerationRequest = ModelCompilerGenerationRequest(request.owner,
            request.namespace, request.installation, request.launch, request.originalDeadline)
        fun assertNeverStarted() {
            assertFalse(Files.exists(generationRoot, NOFOLLOW_LINKS), "No generation files/copies/process output may be created")
            assertEquals(0, liveChecks.get())
            assertEquals(0, custody.calls.get())
        }
    }

    private fun syntheticBridgeBytes() = "SYNTHETIC model bridge; NEVER EXECUTE".toByteArray(Charsets.UTF_8)
    private fun syntheticBridgeDigest(): String = Digests.sha256(syntheticBridgeBytes())
    private fun emptyColdProfilesDigest(): String = Digests.sha256(Digests.canonical(
        ContractJson.encodeToJsonElement(emptyMap<String, TrustedExecution>())))
}
