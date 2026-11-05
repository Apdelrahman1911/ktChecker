package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Fresh synthetic kernel SPI only; no compiler/model execution or qualified rule evidence. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KernelProducedAdmissionRetentionTest {
    @TempDir lateinit var temp: Path

    /** Unlike the older fixture's ref-keyed authority, simultaneous exact requests keep separate
     * kernel-created request identities. No loser can replace the winner's live preparation. */
    private class Authority(private val f: ProducedLaneFixture) : ReviewPlanAuthority {
        val admissions = ConcurrentHashMap<String, ProducedPlanAdmissionRequest>()
        val preparations = ConcurrentHashMap<String, ProducedPlanPreparationRequest>()
        val producerCalls = ConcurrentHashMap<String, AtomicInteger>()
        val consumerCalls = ConcurrentHashMap<String, AtomicInteger>()
        val attempts = ConcurrentLinkedQueue<ProducedExecutionAttempt>()
        val providerCalls = AtomicInteger()
        @Volatile var beforeProducerReturn: (ProducedPlanPreparationRequest) -> Unit = { }
        @Volatile var beforeProvider: (ProducedExecutionAttempt) -> Unit = { }

        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest) = f.resolve(binding, request)
        override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan {
            assertEquals(f.definitions.getValue(request.request.planRef), request.definition)
            assertNull(admissions.putIfAbsent(request.planId, request))
            return AuthorizedProducedPlan(request, f.clocks.getValue(request.request.planRef),
                Digests.sha256("synthetic-admission-authority:${request.planId}"), ProducedPlanValidity(),
                verifyForAdmission = { assertSame(request, admissions.getValue(request.planId)) },
                prepareProducer = { preparation ->
                    assertSame(request, preparation.admission)
                    assertNull(preparations.putIfAbsent(request.planId, preparation))
                    assertEquals(1, producerCalls.computeIfAbsent(request.planId) { AtomicInteger() }.incrementAndGet())
                    preparation.archives.forEach { archive ->
                        assertSame(request, archive.admission); archive.verify()
                        assertFalse(Files.exists(Path.of(archive.frozen.materializedRoot)))
                        assertEquals(archive.held, archive.readInput())
                    }
                    val archive = preparation.archive(ProducedLaneFixture.PRODUCER)
                    val original = archive.original
                    val dependency = Digests.sha256("synthetic-producer:${request.planId}:${original.analysisKey()}")
                    val input = original.copy(context = original.context.copy(dependencyDigest = dependency,
                        policies = original.context.policies + mapOf(
                            "ktchecker.execution-plan" to Digests.sha256(canonical(emptyMap<String, TrustedExecution>())),
                            ProducedLaneFixture.PREVIEW to request.declaration.graphDigest,
                            "ktchecker.jvm-produced-resolution" to dependency)))
                    beforeProducerReturn(preparation)
                    PreparedProducedExecution(ProducedLaneFixture.PRODUCER, input,
                        Digests.sha256("synthetic-producer-binding:${request.planId}")) { archive.verify() }
                }, operationalFaults = { check ->
                    assertSame(request, check.admission)
                    if (check.original == check.execution.input && check.result == f.partial(check.effective)) emptyList()
                    else listOf("synthetic_admission_operational_shape_mismatch")
                }, resolveConsumer = { resolution ->
                    assertSame(request, resolution.admission)
                    assertSame(preparations.getValue(request.planId), resolution.preparation)
                    assertEquals(1, consumerCalls.computeIfAbsent(request.planId) { AtomicInteger() }.incrementAndGet())
                    ProducedSlotResolution.Ready(f.consumer(resolution))
                })
        }
        val observer = object : PreparedInputObserver {
            override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
                fail("This test admits only exact typed executions")
            }
            override fun preparedProduced(attempt: ProducedExecutionAttempt) {
                assertSame(admissions.getValue(attempt.admission.planId), attempt.admission)
                assertSame(preparations.getValue(attempt.admission.planId), attempt.preparation)
                assertEquals(1L, attempt.attempt)
                assertEquals(f.rules, attempt.original.requiredRuleIds); assertEquals(f.rules, attempt.effective.requiredRuleIds)
                assertFalse(attempts.any { it.workKey == attempt.workKey }, "Typed work must not replay")
                attempt.verifyFrozen(); attempts += attempt
                beforeProvider(attempt)
            }
        }
        val provider = object : AnalysisProvider {
            override val id = ProducedLaneFixture.PROVIDER
            override val supportedRuleIds = f.rules.toSet()
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
                cancellation.throwIfCancelled()
                assertEquals(1, attempts.count { it.effective == input })
                providerCalls.incrementAndGet()
                return f.partial(input)
            }
        }
    }

    private fun assertNoAcceptedRows(service: KernelService) = service.ledger.read { c ->
        for (table in listOf("parent_plans", "produced_plan_admissions", "reviews", "plan_slots", "work",
                "plan_slot_resolutions", "plan_slot_resolution_claims", "work_publications", "work_certificates",
                "work_progress", "events", "fair_queues", "fair_clients")) {
            assertEquals(0L, c.count("SELECT COUNT(*) FROM $table"), table)
        }
        assertEquals(0L, c.count("SELECT COUNT(*) FROM clients WHERE next_review<>1 OR next_event<>1 OR epoch<>0"))
        assertEquals(0L, service.queuedCapacityUsed(c))
    }
    private fun assertDeclarations(service: KernelService, client: ClientRegistration, plan: ParentPlanStatus,
        definition: RequiredReviewPlan) = service.ledger.read { c ->
        val rows = c.query("""SELECT r.review_id,r.sequence,r.input_json FROM reviews r JOIN plan_slots s ON s.review_id=r.review_id
            WHERE s.plan_id=? ORDER BY s.slot_id""", plan.planId) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
        assertEquals(plan.slots.map { it.reviewId }, rows.map { it.first })
        assertEquals(listOf(1L, 2L), rows.map { it.second })
        assertEquals(definition.slots.map { canonical(it.input) }, rows.map { it.third })
        assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND kind='produced' AND work_key IS NULL", client.binding.clientId))
        assertEquals(3L, c.count("SELECT next_review FROM clients WHERE client_id=?", client.binding.clientId))
        assertEquals(2L, c.count("SELECT review_frontier FROM parent_plans WHERE plan_id=?", plan.planId))
        assertEquals(definition.slots.map { it.input.requiredRuleIds }, plan.slots.map { it.requiredRuleIds })
        assertTrue(plan.slots.all { it.requiredRuleIds.size == 1663 })
    }
    private fun archiveInventory(service: KernelService): Map<String, String> {
        val root = service.root.resolve("inputs")
        return Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList()
            .associate { root.relativize(it).toString() to Digests.sha256(Files.readAllBytes(it)) } }
    }
    private fun assertSqlMessage(failure: SQLException, expected: String) = assertTrue(
        generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains(expected) },
        "Unexpected SQL fault")

    @Test fun twoProvenPreInstallRollbacksReleaseLiveCapacityForAnUnrelatedValidClientWithoutRestart() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val authority = Authority(f)
        val config = ProducedLaneFixture.CONFIG.copy(workerCount = 0, maxRetainedReviews = 2)
        KernelService.open(temp.resolve("ledger"), authority.provider, config, planAuthority = authority,
            preparedInputObserver = authority.observer, resultPolicy = f.policy).use { service ->
            val rejected = service.registerClient(ProducedLaneFixture.binding("rejected"))
            val valid = service.registerClient(ProducedLaneFixture.binding("valid"))
            service.ledger.transaction { c ->
                c.execute("""CREATE TRIGGER synthetic_admission_preinstall_abort BEFORE INSERT ON parent_plans
                    BEGIN SELECT RAISE(ABORT,'synthetic_admission_preinstall_abort'); END""")
            }
            try {
                repeat(2) { index ->
                    val definition = f.definition(rejected.binding, "rejected-$index")
                    assertSqlMessage(assertFailsWith<SQLException> { service.submitPlan(rejected.client, definition.request) },
                        "synthetic_admission_preinstall_abort")
                    assertEquals(index + 1, authority.admissions.size)
                    assertEquals(index + 1, authority.preparations.size)
                    assertEquals(index + 1, authority.producerCalls.values.sumOf { it.get() })
                    assertNoAcceptedRows(service)
                    assertTrue(archiveInventory(service).isEmpty())
                    Files.list(service.root.resolve("inputs")).use { assertEquals(0L, it.count(), "Owned staging must be gone") }
                    authority.preparations.values.forEach { preparation -> preparation.archives.forEach { archive ->
                        assertFalse(Files.exists(Path.of(archive.readInput().materializedRoot)))
                        assertFalse(Files.exists(Path.of(archive.frozen.materializedRoot)))
                    } }
                    service.ledger.read { c -> assertEquals(0L, service.archiveBytesUsed(c)) }
                    assertEquals(0, authority.providerCalls.get()); assertTrue(authority.attempts.isEmpty())
                    assertTrue(authority.consumerCalls.isEmpty())
                }
            } finally { service.ledger.transaction { it.execute("DROP TRIGGER synthetic_admission_preinstall_abort") } }

            val definition = f.definition(valid.binding, "accepted")
            val accepted = service.submitPlan(valid.client, definition.request)
            assertDeclarations(service, valid, accepted, definition)
            assertEquals(3, authority.admissions.size); assertEquals(3, authority.producerCalls.values.sumOf { it.get() })
            assertEquals(setOf(1), authority.producerCalls.values.map { it.get() }.toSet())
            assertEquals(0, authority.providerCalls.get()); assertTrue(authority.attempts.isEmpty()); assertTrue(authority.consumerCalls.isEmpty())
            assertEquals(listOf("accepted", "accepted", "plan_accepted"), f.events(service, valid).map { it.kind })
            assertTrue(f.events(service, rejected).isEmpty())
            service.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM parent_plans")); assertEquals(1L, c.count("SELECT COUNT(*) FROM produced_plan_admissions"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(2L, c.count("SELECT COUNT(*) FROM plan_slots"))
                assertEquals(1L, c.count("SELECT COUNT(*) FROM work")); assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims")); assertEquals(0L, c.count("SELECT COUNT(*) FROM work_publications"))
                assertEquals(2L, service.queuedCapacityUsed(c)); assertEquals(2L, service.pendingReviews(c, valid.binding.clientId))
                val sourceBytes = definition.slots.first().input.snapshot.files.sumOf { it.size }
                assertEquals(sourceBytes * 3, service.archiveBytesUsed(c))
            }
            assertEquals(accepted.planId, service.submitPlan(valid.client, definition.request).planId)
            assertEquals(3, authority.admissions.size); assertEquals(3, authority.preparations.size)
            assertTrue("produced_plan_preview_unqualified" in service.planStatus(valid.client, accepted.planId).reasons)
        }
    }

    @Test fun concurrentFinalIdempotencyLosersNeverConsumeTheWinnersOrAnotherClientsLiveCapacity() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val authority = Authority(f)
        val prepared = CountDownLatch(4); val releasePreparation = CountDownLatch(1)
        val executionEntered = CountDownLatch(1); val releaseExecution = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val config = ProducedLaneFixture.CONFIG.copy(workerCount = 4, globalParallelism = 1,
            perWorktreeParallelism = 1, maxRetainedReviews = 4)
        try {
            KernelService.open(temp.resolve("ledger"), authority.provider, config, planAuthority = authority,
                preparedInputObserver = authority.observer, resultPolicy = f.policy).use { service ->
                try {
                    val duplicateClient = service.registerClient(ProducedLaneFixture.binding("duplicate"))
                    val otherClient = service.registerClient(ProducedLaneFixture.binding("other"))
                    val definition = f.definition(duplicateClient.binding, "duplicate")
                    authority.beforeProducerReturn = { preparation ->
                        if (preparation.admission.request == definition.request) {
                            prepared.countDown(); assertTrue(releasePreparation.await(10, TimeUnit.SECONDS))
                        }
                    }
                    authority.beforeProvider = { attempt ->
                        if (attempt.admission.request == definition.request && attempt.execution.slotId == ProducedLaneFixture.PRODUCER) {
                            executionEntered.countDown(); assertTrue(releaseExecution.await(10, TimeUnit.SECONDS))
                        }
                    }
                    val submissions = (1..4).map { pool.submit(Callable { service.submitPlan(duplicateClient.client, definition.request) }) }
                    assertTrue(prepared.await(10, TimeUnit.SECONDS), "All four exact requests must pass early idempotency before a winner commits")
                    assertEquals(4, authority.admissions.size); assertEquals(4, authority.preparations.size)
                    assertNoAcceptedRows(service)
                    releasePreparation.countDown()
                    val results = submissions.map { it.get(10, TimeUnit.SECONDS) }
                    assertEquals(1, results.map { it.planId }.distinct().size)
                    assertEquals(1, results.map { it.slots.map { slot -> slot.reviewId } }.distinct().size)
                    val winner = results.first()
                    assertTrue(executionEntered.await(5, TimeUnit.SECONDS))
                    val losers = authority.admissions.keys.toSet() - winner.planId
                    assertEquals(3, losers.size)
                    losers.forEach { id ->
                        val preparation = authority.preparations.getValue(id)
                        preparation.archives.forEach { archive ->
                            assertFalse(Files.exists(Path.of(archive.readInput().materializedRoot)))
                            assertFalse(Files.exists(Path.of(archive.frozen.materializedRoot)))
                        }
                        service.ledger.read { c -> assertEquals(0L, c.count("SELECT COUNT(*) FROM parent_plans WHERE plan_id=?", id)) }
                    }
                    assertDeclarations(service, duplicateClient, winner, definition)
                    assertEquals(0, authority.providerCalls.get()); assertEquals(1, authority.attempts.size)
                    assertTrue(authority.consumerCalls.isEmpty())

                    // Four duplicate preparations would exhaust the old live map, despite only
                    // two retained reviews. This other client's two obligations still fit exactly.
                    val otherDefinition = f.definition(otherClient.binding, "other")
                    val other = service.submitPlan(otherClient.client, otherDefinition.request)
                    assertDeclarations(service, otherClient, other, otherDefinition)
                    assertEquals(5, authority.admissions.size); assertEquals(5, authority.producerCalls.values.sumOf { it.get() })
                    assertEquals(setOf(1), authority.producerCalls.values.map { it.get() }.toSet())
                    service.ledger.read { c ->
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM parent_plans")); assertEquals(2L, c.count("SELECT COUNT(*) FROM produced_plan_admissions"))
                        assertEquals(4L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(4L, c.count("SELECT COUNT(*) FROM plan_slots"))
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM work")); assertEquals(2L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                        assertEquals(0L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims")); assertEquals(0L, c.count("SELECT COUNT(*) FROM work_publications"))
                    }
                    for ((client, plan) in listOf(duplicateClient to winner, otherClient to other)) {
                        assertEquals(listOf("accepted", "accepted", "plan_accepted"), f.events(service, client).map { it.kind })
                        assertEquals(plan.planId, service.planStatus(client.client, plan.planId).planId)
                    }
                    repeat(3) { assertEquals(winner.planId, service.submitPlan(duplicateClient.client, definition.request).planId) }
                    assertEquals(5, authority.admissions.size); assertEquals(5, authority.preparations.size)
                    assertEquals(0, authority.providerCalls.get())
                    releaseExecution.countDown()
                    for ((client, plan) in listOf(duplicateClient to winner, otherClient to other)) {
                        plan.slots.forEach { assertEquals("incomplete", f.finished(service, client, it.reviewId).state) }
                        assertEquals("incomplete", service.planStatus(client.client, plan.planId).state)
                        assertTrue("produced_plan_preview_unqualified" in service.planStatus(client.client, plan.planId).reasons)
                        assertEquals(1, authority.consumerCalls.getValue(plan.planId).get())
                        val events = f.events(service, client)
                        assertEquals(2, events.count { it.kind == "accepted" }); assertEquals(1, events.count { it.kind == "plan_accepted" })
                        assertEquals(1, events.count { it.kind == "plan_resolved" }); assertEquals(2, events.count { it.kind == "result" })
                    }
                    assertEquals(4, authority.providerCalls.get()); assertEquals(4, authority.attempts.size)
                    assertEquals(setOf(winner.planId, other.planId), authority.attempts.map { it.admission.planId }.toSet())
                    assertEquals(4, authority.attempts.map { it.workKey }.distinct().size)
                    assertTrue(authority.attempts.all { it.attempt == 1L })
                    assertDeclarations(service, duplicateClient, winner, definition); assertDeclarations(service, otherClient, other, otherDefinition)
                    service.ledger.read { c ->
                        assertEquals(4L, c.count("SELECT COUNT(*) FROM work")); assertEquals(4L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                        assertEquals(2L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims")); assertEquals(4L, c.count("SELECT COUNT(*) FROM work_publications"))
                        assertEquals(4L, c.count("SELECT COUNT(*) FROM work WHERE attempt=1 AND state='complete'"))
                        assertEquals(0L, service.queuedCapacityUsed(c)); assertEquals(0L, service.pendingReviews(c, duplicateClient.binding.clientId))
                        assertEquals(0L, service.pendingReviews(c, otherClient.binding.clientId))
                    }
                } finally { releasePreparation.countDown(); releaseExecution.countDown() }
            }
        } finally {
            releasePreparation.countDown(); releaseExecution.countDown(); pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun postInstallCommitFailuresKeepTheirOwnedArchiveQuarantineRatherThanMasqueradingAsCleanPreInstallAbort() {
        val f = ProducedLaneFixture(temp.resolve("fixture")); val authority = Authority(f)
        val config = ProducedLaneFixture.CONFIG.copy(workerCount = 0, maxRetainedReviews = 2)
        KernelService.open(temp.resolve("ledger"), authority.provider, config, planAuthority = authority,
            preparedInputObserver = authority.observer, resultPolicy = f.policy).use { service ->
            val rejected = service.registerClient(ProducedLaneFixture.binding("post-install"))
            val unrelated = service.registerClient(ProducedLaneFixture.binding("unrelated"))
            service.ledger.transaction { c ->
                c.execute("CREATE TABLE synthetic_admission_fk_target(id TEXT PRIMARY KEY)")
                c.execute("""CREATE TABLE synthetic_admission_deferred_guard(id TEXT PRIMARY KEY,missing_id TEXT NOT NULL,
                    FOREIGN KEY(missing_id) REFERENCES synthetic_admission_fk_target(id) DEFERRABLE INITIALLY DEFERRED)""")
                c.execute("""CREATE TRIGGER synthetic_admission_abort_commit AFTER INSERT ON parent_plans BEGIN
                    INSERT INTO synthetic_admission_deferred_guard(id,missing_id) VALUES(NEW.plan_id,NEW.plan_id); END""")
            }
            try {
                repeat(2) { index ->
                    val definition = f.definition(rejected.binding, "post-install-$index")
                    // A real deferred FK fails COMMIT only after all three owned archives install.
                    assertSqlMessage(assertFailsWith<SQLException> { service.submitPlan(rejected.client, definition.request) },
                        "FOREIGN KEY constraint failed")
                    assertNoAcceptedRows(service)
                    Files.list(service.root.resolve("inputs")).use { paths ->
                        val directories = paths.toList()
                        assertEquals((index + 1) * 3, directories.size)
                        assertTrue(directories.none { it.fileName.toString().startsWith(".ingest-") })
                    }
                }
            } finally { service.ledger.transaction { c ->
                c.execute("DROP TRIGGER synthetic_admission_abort_commit")
                c.execute("DROP TABLE synthetic_admission_deferred_guard"); c.execute("DROP TABLE synthetic_admission_fk_target")
            } }
            val residue = archiveInventory(service); assertTrue(residue.isNotEmpty())
            val next = f.definition(unrelated.binding, "not-safe-to-retire")
            assertEquals("produced_plan_capacity", assertFailsWith<KernelException> { service.submitPlan(unrelated.client, next.request) }.code)
            assertNoAcceptedRows(service); assertEquals(residue, archiveInventory(service))
            assertEquals(3, authority.admissions.size); assertEquals(3, authority.producerCalls.values.sumOf { it.get() })
            assertEquals(0, authority.providerCalls.get()); assertTrue(authority.attempts.isEmpty()); assertTrue(authority.consumerCalls.isEmpty())
            // This retained refusal is an unresolved safe-retirement/byte-accounting limit, NOT
            // successful capacity recovery. No restart, implicit orphan deletion or replay fixes it.
        }
    }
}
