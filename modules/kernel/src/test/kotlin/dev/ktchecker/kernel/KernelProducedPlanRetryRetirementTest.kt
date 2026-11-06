package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Supplements sealed kernel8; synthetic kernel SPI only, never compiler/model success authority. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class KernelProducedPlanRetryRetirementTest {
    @TempDir lateinit var temp: Path

    private data class Claim(val origin: String, val nonce: String, val reserved: Long)
    private data class Charge(val declarations: Long, val work: Long, val reservations: Long) {
        val total: Long get() = declarations + work + reservations
    }
    private fun claim(service: KernelService, planId: String): Claim = service.ledger.read { c ->
        val rows = c.query("""SELECT origin_instance,claim_nonce,reserved_archive_bytes FROM plan_slot_resolution_claims
            WHERE plan_id=? AND slot_id=?""", planId, ProducedLane1665Fixture.CONSUMER) {
            Claim(it.getString(1), it.getString(2), it.getLong(3))
        }
        assertEquals(1, rows.size)
        rows.single().also { row ->
            val archives = assertNotNull(c.one("SELECT archives_json FROM produced_plan_admissions WHERE plan_id=?", planId) {
                ContractJson.decodeFromString<List<DeclaredArchiveIdentity>>(it.getString(1)) })
            assertEquals(archives.single { it.slotId == ProducedLane1665Fixture.CONSUMER }.byteCount + 134_217_728L, row.reserved)
        }
    }
    private fun charge(service: KernelService): Charge = service.ledger.read { c ->
        Charge(c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM produced_plan_admissions"),
            c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work"),
            c.count("SELECT COALESCE(SUM(reserved_archive_bytes),0) FROM plan_slot_resolution_claims")).also {
            assertEquals(it.total, service.archiveBytesUsed(c)); assertTrue(it.total <= service.config.maxArchiveBytes)
        }
    }
    private fun declarations(service: KernelService, plan: ParentPlanStatus) = service.ledger.read { c ->
        c.query("""SELECT r.review_id,r.sequence,r.input_json FROM plan_slots s JOIN reviews r ON r.review_id=s.review_id
            WHERE s.plan_id=? ORDER BY s.slot_id""", plan.planId) { Triple(it.getString(1), it.getLong(2), it.getString(3)) }
    }
    private fun producerEvidence(service: KernelService, key: String): List<String> = service.ledger.read { c ->
        assertNotNull(c.one("""SELECT w.result_json,w.validation_json,w.progress_faults_json,p.record_json,p.certificate_id
            FROM work w JOIN work_publications p ON p.work_key=w.work_key WHERE w.work_key=?""", key) { row ->
            (1..5).map { assertNotNull(row.getString(it)) }
        })
    }
    private fun unchangedObligations(service: KernelService, client: ClientRegistration, plan: ParentPlanStatus,
        before: List<Triple<String, Long, String>>, expectClosed: Boolean = false) {
        assertEquals(before, declarations(service, plan))
        if (expectClosed) {
            // Closing the broker denies public operations, not durable obligation inspection.
            assertEquals("service_closed", assertFailsWith<KernelException> {
                service.planStatus(client.client, plan.planId)
            }.code)
        } else {
            assertEquals(plan.slots.map { it.reviewId }, service.planStatus(client.client, plan.planId).slots.map { it.reviewId })
        }
        service.ledger.read { c ->
            assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
            assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews WHERE kind='produced' AND work_key IS NULL"))
            assertEquals(3L, c.count("SELECT next_review FROM clients WHERE client_id=?", client.binding.clientId))
            assertEquals(2L, c.count("SELECT review_frontier FROM parent_plans WHERE plan_id=?", plan.planId))
        }
    }

    private class ReadyBarrier(private val fixture: ProducedLane1665Fixture) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = AtomicReference<Pair<ProducedSlotResolutionRequest, PreparedProducedExecution>>()
        fun resolve(request: ProducedSlotResolutionRequest): ProducedSlotResolution {
            val prepared = fixture.consumer(request)
            assertTrue(captured.compareAndSet(null, request to prepared), "Owner preparation must be one-use")
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS), "Test did not release the prepared synthetic output")
            return ProducedSlotResolution.Ready(prepared)
        }
    }
    private fun rejectReadyPublication(service: KernelService) = service.ledger.transaction { c ->
        // Only this fresh test ledger's consumer Ready INSERT fails; close/unavailable can still publish.
        c.execute("""CREATE TRIGGER synthetic_ready_publication_abort BEFORE INSERT ON plan_slot_resolutions
            WHEN NEW.slot_id='slot-001-test' AND NEW.state='ready'
            BEGIN SELECT RAISE(ABORT,'synthetic_ready_publication_retry'); END""")
    }
    private fun removePublicationFault(service: KernelService) = service.ledger.transaction { c ->
        c.execute("DROP TRIGGER IF EXISTS synthetic_ready_publication_abort")
    }
    private fun twoFailedPublications(service: KernelService, f: ProducedLane1665Fixture) {
        var failures = 0
        f.await("Two real SQL publication rollbacks were not observed") {
            try { service.producedPlans.resolveNext() }
            catch (fault: SQLException) {
                assertTrue(generateSequence<Throwable>(fault) { it.cause }.any {
                    it.message.orEmpty().contains("synthetic_ready_publication_retry") }, "Unexpected SQL fault")
                failures++
            }
            failures >= 2 // Background retries may also fail; their count is deliberately not guessed.
        }
    }
    private fun ownedJar(service: KernelService, planId: String, selected: Claim, prepared: PreparedProducedExecution): Path {
        val manifest = assertNotNull(prepared.producedManifest)
        val expected = service.root.resolve("produced-output").resolve(planId).resolve(selected.nonce).resolve("complete-output.jar")
        assertEquals(expected.toString(), manifest.completeJar.path)
        assertEquals(manifest.completeJarSize, Files.size(expected))
        assertEquals(manifest.completeJar.digest, Digests.sha256(Files.readAllBytes(expected)))
        Files.list(expected.parent.parent).use { paths ->
            assertEquals(listOf(selected.nonce), paths.map { it.fileName.toString() }.sorted().toList())
        }
        return expected
    }

    @Test fun sqlPublicationRetriesKeepOnePreparedReadyNonceOutputAndChargeUntilTheSameConsumerPublishes() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val provider = f.Provider(); val barrier = ReadyBarrier(f)
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            try {
                val client = service.registerClient(ProducedLane1665Fixture.binding("retry")); val definition = f.definition(client.binding, "retry")
                f.resolver[definition.request.planRef] = barrier::resolve
                val plan = service.submitPlan(client.client, definition.request)
                assertTrue(barrier.entered.await(5, TimeUnit.SECONDS))
                val (request, prepared) = assertNotNull(barrier.captured.get())
                val rows = declarations(service, plan); assertEquals(listOf(1L, 2L), rows.map { it.second })
                assertEquals(definition.slots.map { canonical(it.input) }, rows.map { it.third })
                f.finished(service, client, plan.slots.first().reviewId)
                val evidence = producerEvidence(service, request.producer.workKey)
                assertEquals(canonical(request.publication), evidence[3]); assertTrue(request.publication.operationalFaults.isEmpty())
                val producerAttempt = f.typedAttempts.single()
                assertEquals(canonical(f.partial(producerAttempt.effective)), evidence[0])
                val selected = claim(service, plan.planId); val before = charge(service)
                assertEquals(service.instanceId, selected.origin); ownedJar(service, plan.planId, selected, prepared)
                rejectReadyPublication(service); barrier.release.countDown(); twoFailedPublications(service, f)

                assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())
                assertEquals(1, provider.calls.get()); assertEquals(1, f.typedAttempts.size)
                assertNull(f.resolved(service, plan.slots.last().reviewId)); assertNull(service.status(client.client, plan.slots.last().reviewId).result)
                assertEquals(selected, claim(service, plan.planId)); assertEquals(before, charge(service))
                assertEquals(evidence, producerEvidence(service, request.producer.workKey))
                unchangedObligations(service, client, plan, rows); ownedJar(service, plan.planId, selected, prepared)
                service.ledger.read { c ->
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work"))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                    assertEquals(1L, service.queuedCapacityUsed(c))
                    assertEquals(1L, service.pendingReviews(c, client.binding.clientId))
                }
                assertEquals(0, f.events(service, client).count { it.kind == "plan_resolved" })

                removePublicationFault(service)
                val result = f.finished(service, client, plan.slots.last().reviewId)
                val consumer = f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.CONSUMER }
                assertSame(request.admission, consumer.admission)
                assertEquals(prepared.input, consumer.original)
                assertEquals(prepared.bindingDigest, consumer.execution.certificate.compilerBindingDigest)
                assertEquals(prepared.producedManifest, consumer.execution.certificate.producedManifest)
                assertEquals(request.publication.digest(), consumer.execution.certificate.producerPublicationCertificate)
                assertEquals(f.partial(consumer.effective), result.result); assertEquals("incomplete", result.state)
                assertEquals(selected, claim(service, plan.planId)); ownedJar(service, plan.planId, selected, prepared)
                val after = charge(service)
                assertEquals(before.declarations, after.declarations); assertEquals(before.reservations, after.reservations)
                assertTrue(after.work > before.work, "Actual Ready K is charged in addition to the immutable reservation")
                assertEquals(evidence, producerEvidence(service, request.producer.workKey))
                unchangedObligations(service, client, plan, rows)
                service.ledger.read { c ->
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM work")); assertEquals(2L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
                    assertEquals(consumer.workKey, c.one("SELECT work_key FROM plan_slot_resolutions WHERE review_id=?", result.reviewId) { it.getString(1) })
                    assertEquals(0L, service.queuedCapacityUsed(c)); assertEquals(0L, service.pendingReviews(c, client.binding.clientId))
                }
                assertEquals(1, f.events(service, client).count { it.kind == "plan_resolved" })
                assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get()); assertEquals(2, provider.calls.get())
                assertEquals(2, f.typedAttempts.size); assertTrue(f.typedAttempts.all { it.attempt == 1L })
            } finally { barrier.release.countDown(); removePublicationFault(service) }
        }
    }

    @Test fun preparedButUnpublishedResidueRemainsChargedAndCannotReplayAfterOwnedCloseAndDenyRestart() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val provider = f.Provider(); val barrier = ReadyBarrier(f)
        val root = temp.resolve("ledger")
        val origin = KernelService.open(root, provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy)
        try {
            val client = origin.registerClient(ProducedLane1665Fixture.binding("residue")); val definition = f.definition(client.binding, "residue")
            f.resolver[definition.request.planRef] = barrier::resolve
            val plan = origin.submitPlan(client.client, definition.request)
            assertTrue(barrier.entered.await(5, TimeUnit.SECONDS))
            val (request, prepared) = assertNotNull(barrier.captured.get())
            f.finished(origin, client, plan.slots.first().reviewId)
            val rows = declarations(origin, plan); val selected = claim(origin, plan.planId); val before = charge(origin)
            val evidence = producerEvidence(origin, request.producer.workKey)
            val jar = ownedJar(origin, plan.planId, selected, prepared); val jarDigest = Digests.sha256(Files.readAllBytes(jar))
            rejectReadyPublication(origin); barrier.release.countDown(); twoFailedPublications(origin, f)
            assertNull(f.resolved(origin, plan.slots.last().reviewId)); assertEquals(before, charge(origin))
            assertEquals(1, provider.calls.get()); assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get())

            origin.close() // ONLY this newly owned synthetic service; not compiler/owner CLOSED evidence.
            f.await("Owned origin did not reach its real scheduler close fence") { origin.ledger.read { c ->
                c.count("SELECT closed FROM instances WHERE instance_id=?", origin.instanceId) == 1L
            } }
            assertEquals("unavailable", f.resolved(origin, plan.slots.last().reviewId))
            val terminalReasons = origin.ledger.read { c -> assertNotNull(c.one(
                "SELECT reasons_json FROM plan_slot_resolutions WHERE review_id=?", plan.slots.last().reviewId) {
                ContractJson.decodeFromString<List<String>>(it.getString(1)) }) }
            // A retry racing close may hit its own close/interrupt fence before retirement. All are explicit denials.
            assertEquals(1, terminalReasons.size)
            assertTrue(terminalReasons.single() in setOf("produced_origin_closed_authority_not_restorable",
                "produced_broker_closed", "produced_resolution_interrupted"), terminalReasons.toString())
            unchangedObligations(origin, client, plan, rows, expectClosed = true)
            assertEquals(selected, claim(origin, plan.planId)); assertEquals(before, charge(origin))
            assertEquals(evidence, producerEvidence(origin, request.producer.workKey))
            assertEquals(jarDigest, Digests.sha256(Files.readAllBytes(jar)))
            removePublicationFault(origin)

            val replay = f.Provider()
            KernelService.open(root, replay, ProducedLane1665Fixture.CONFIG, planAuthority = ReviewPlanAuthority.DENY,
                preparedInputObserver = f.observer, resultPolicy = f.policy).use { restarted ->
                restarted.registerClient(client); assertNotEquals(origin.instanceId, restarted.instanceId)
                val retained = restarted.planStatus(client.client, plan.planId)
                assertEquals("incomplete", retained.state); assertEquals("unavailable", retained.slots.last().state)
                assertTrue(retained.slots.last().reasons.containsAll(terminalReasons))
                assertTrue("produced_plan_preview_unqualified" in retained.reasons)
                unchangedObligations(restarted, client, plan, rows)
                assertEquals(selected, claim(restarted, plan.planId)); assertEquals(before, charge(restarted))
                assertEquals(evidence, producerEvidence(restarted, request.producer.workKey))
                assertEquals(jarDigest, Digests.sha256(Files.readAllBytes(ownedJar(restarted, plan.planId, selected, prepared))))
                restarted.ledger.read { c ->
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work")); assertEquals(1L, c.count("SELECT attempt FROM work"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state IN ('queued','running')"))
                    assertEquals(2L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
                    assertEquals(0L, restarted.queuedCapacityUsed(c)); assertEquals(0L, restarted.pendingReviews(c, client.binding.clientId))
                }
                assertEquals(0, replay.calls.get()); assertEquals(1, provider.calls.get())
                assertEquals(1, f.resolutionCalls.getValue(definition.request.planRef).get()); assertEquals(1, f.typedAttempts.size)
            }
        } finally {
            barrier.release.countDown()
            try { removePublicationFault(origin) } finally { origin.close() }
        }
    }

    @Test fun failedChargedOutputSpendsOnlyItsResolverTurnAndTheOtherReadyQueuePrecedesMoreSameQueueWork() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val provider = f.Provider()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val order = ConcurrentLinkedQueue<String>()
        val residue = AtomicReference<PreparedProducedExecution>()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            try {
                val a = service.registerClient(ProducedLane1665Fixture.binding("fair-a")); val b = service.registerClient(ProducedLane1665Fixture.binding("fair-b"))
                val a1 = f.definition(a.binding, "a1")
                f.resolver[a1.request.planRef] = { request ->
                    order += "a1"; residue.set(f.consumer(request)); entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    throw IllegalStateException("Synthetic failure after owned output creation; bytes and charge must remain")
                }
                val first = service.submitPlan(a.client, a1.request)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val plans = mutableListOf(a to first)
                listOf(a to "a2", a to "a3", b to "b1").forEach { (client, id) ->
                    val definition = f.definition(client.binding, id)
                    f.resolver[definition.request.planRef] = { request -> order += id; ProducedSlotResolution.Ready(f.consumer(request)) }
                    plans += client to service.submitPlan(client.client, definition.request)
                }
                // Establish eligibility of EVERY producer, not parent success (all parents remain unqualified).
                plans.forEach { (client, plan) -> f.finished(service, client, plan.slots.first().reviewId) }
                val declaredRowsByPlan = plans.associate { (_, plan) -> plan.planId to declarations(service, plan) }
                val selected = claim(service, first.planId); val before = charge(service)
                val prepared = assertNotNull(residue.get()); ownedJar(service, first.planId, selected, prepared)
                assertEquals(listOf("a1"), order.toList()); assertEquals(selected.reserved, before.reservations)
                service.ledger.read { c -> assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims")) }
                release.countDown()
                f.await("Failed A1 output did not become retained unavailability") { f.resolved(service, first.slots.last().reviewId) == "unavailable" }
                plans.drop(1).forEach { (client, plan) -> f.finished(service, client, plan.slots.last().reviewId) }

                assertEquals(listOf("a1", "b1", "a2", "a3"), order.toList())
                assertTrue("produced_consumer_resolution_unavailable" in service.planStatus(a.client, first.planId).slots.last().reasons)
                assertEquals(selected, claim(service, first.planId)); ownedJar(service, first.planId, selected, prepared)
                assertFalse(f.typedAttempts.any { it.admission.planId == first.planId && it.execution.slotId == ProducedLane1665Fixture.CONSUMER })
                plans.forEach { (client, plan) ->
                    assertEquals(declaredRowsByPlan.getValue(plan.planId), declarations(service, plan))
                    val retained = service.planStatus(client.client, plan.planId)
                    assertEquals("incomplete", retained.state); assertTrue("produced_plan_preview_unqualified" in retained.reasons)
                    retained.slots.forEach { assertEquals(f.rules, it.requiredRuleIds) }
                }
                val after = charge(service)
                assertEquals(before.declarations, after.declarations); assertTrue(after.work > before.work)
                assertEquals(plans.sumOf { (_, plan) -> claim(service, plan.planId).reserved }, after.reservations)
                assertTrue(after.reservations > before.reservations)
                service.ledger.read { c ->
                    assertEquals(8L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(7L, c.count("SELECT COUNT(*) FROM work"))
                    assertEquals(8L, c.count("SELECT COUNT(*) FROM plan_slot_resolutions"))
                    assertEquals(4L, c.count("SELECT COUNT(DISTINCT claim_nonce) FROM plan_slot_resolution_claims"))
                    assertEquals(0L, service.queuedCapacityUsed(c)); assertEquals(0L, service.pendingReviews(c, a.binding.clientId))
                    assertEquals(0L, service.pendingReviews(c, b.binding.clientId))
                }
                assertEquals(7, provider.calls.get()); assertEquals(7, f.typedAttempts.size)
                assertTrue(f.resolutionCalls.values.all { it.get() == 1 }); assertTrue(f.typedAttempts.all { it.attempt == 1L })
            } finally { release.countDown() }
        }
    }

    @Test fun realRetirementSelectorSkipsMoreThan4096TerminalAdmissionsBeforeItsBoundedActionableBatch() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG.copy(workerCount = 0)).use { service ->
            // DISJOINT selector-only metadata: no valid declarations/certificates or admitted authority.
            // Never pass these rows to load/retire/admit; the other tests exercise real full1665 histories.
            DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
                c.execute("CREATE TABLE instances(instance_id TEXT PRIMARY KEY,closed INTEGER NOT NULL)")
                c.execute("CREATE TABLE produced_plan_admissions(plan_id TEXT PRIMARY KEY,origin_instance TEXT NOT NULL)")
                c.execute("CREATE TABLE plan_slots(plan_id TEXT,slot_id TEXT,PRIMARY KEY(plan_id,slot_id))")
                c.execute("CREATE TABLE plan_slot_resolutions(plan_id TEXT,slot_id TEXT,state TEXT,work_key TEXT,PRIMARY KEY(plan_id,slot_id))")
                c.execute("CREATE TABLE work(work_key TEXT PRIMARY KEY,state TEXT NOT NULL)")
                c.autoCommit = false
                c.execute("INSERT INTO instances(instance_id,closed) VALUES('closed',1),('live',0)")
                repeat(4097) { index -> selectorMetadata(c, "terminal-$index", "closed", if (index % 2 == 0) "complete" else "unavailable") }
                selectorMetadata(c, "late-unresolved", "closed", null)
                selectorMetadata(c, "late-queued", "closed", "queued")
                selectorMetadata(c, "late-running", "closed", "running")
                selectorMetadata(c, "live-unresolved", "live", null)
                selectorMetadata(c, "live-queued", "live", "queued")
                selectorMetadata(c, "live-running", "live", "running")
                selectorMetadata(c, "terminal-tail", "closed", "complete")
                c.commit()
                // Calls the factored PRODUCTION selector, not a test copy of its SQL.
                assertEquals(listOf("late-unresolved", "late-queued", "late-running"), service.producedPlans.closedOriginCandidateIds(c))
                c.execute("INSERT INTO plan_slot_resolutions(plan_id,slot_id,state,work_key) VALUES(?,?,'unavailable',NULL)",
                    "late-unresolved", ProducedLane1665Fixture.CONSUMER)
                c.execute("UPDATE work SET state='complete' WHERE work_key IN (?,?)", "late-queued:consumer", "late-running:consumer")
                c.commit()
                assertEquals(emptyList(), service.producedPlans.closedOriginCandidateIds(c))
            }
            service.ledger.read { c ->
                assertEquals(0L, c.count("SELECT COUNT(*) FROM produced_plan_admissions"))
                assertEquals(0L, c.count("SELECT COUNT(*) FROM reviews")); assertEquals(0L, c.count("SELECT COUNT(*) FROM work"))
            }
            assertEquals(0, provider.calls.get()); assertEquals(0, f.typedAttempts.size)
        }
    }

    @Test fun caughtBadProgressPublicationRollbackCannotEraseItsIntrinsicFaultBeforeAnExactPartialFinalResult() {
        val f = ProducedLane1665Fixture(temp.resolve("fixture")); val swallowed = AtomicInteger()
        val provider = f.Provider { input, emit ->
            if (input.context.scope.compilation == "main") {
                try { emit(f.partial(input).copy(providerId = "foreign-provider")) }
                catch (fault: SQLException) {
                    assertTrue(generateSequence<Throwable>(fault) { it.cause }.any {
                        it.message.orEmpty().contains("synthetic_progress_insert_rollback") }, "Unexpected progress SQL fault")
                    swallowed.incrementAndGet() // Deliberately return the exact good partial after swallowing emit's failure.
                }
            }
        }
        KernelService.open(temp.resolve("ledger"), provider, ProducedLane1665Fixture.CONFIG, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            try {
                service.ledger.transaction { c ->
                    // This INSERT follows the original sticky UPDATE, so its failure rolls BOTH back.
                    c.execute("""CREATE TRIGGER synthetic_progress_insert_abort BEFORE INSERT ON work_progress
                        BEGIN SELECT RAISE(ABORT,'synthetic_progress_insert_rollback'); END""")
                }
                val client = service.registerClient(ProducedLane1665Fixture.binding("progress-rollback"))
                val definition = f.definition(client.binding, "progress-rollback")
                val plan = service.submitPlan(client.client, definition.request)
                val producerResult = f.finished(service, client, plan.slots.first().reviewId)
                assertEquals(1, swallowed.get(), "The provider must actually catch the failed progress INSERT")
                val producer = f.typedAttempts.single { it.execution.slotId == ProducedLane1665Fixture.PRODUCER }
                val expectedRaw = f.partial(producer.effective)
                val required = listOf("progress_provider_identity_mismatch", "progress_publication_failed").sorted()
                assertEquals(expectedRaw, producerResult.result); assertEquals("incomplete", producerResult.state)
                assertEquals(f.rules, expectedRaw.coverage.map { it.ruleId }); assertTrue(expectedRaw.coverage.all { !it.qualified })
                service.ledger.read { c ->
                    assertNotNull(c.one("SELECT result_json,progress_faults_json,validation_json FROM work WHERE work_key=?", producer.workKey) { row ->
                        assertEquals(canonical(expectedRaw), row.getString(1), "Bad progress must not replace the exact final raw result")
                        assertEquals(required, ContractJson.decodeFromString<List<String>>(row.getString(2)))
                        assertTrue(ContractJson.decodeFromString<List<String>>(row.getString(3)).containsAll(required))
                        true
                    })
                    val publication = assertNotNull(c.one("SELECT record_json FROM work_publications WHERE work_key=?", producer.workKey) {
                        ContractJson.decodeFromString<ProducedWorkPublication>(it.getString(1)) })
                    assertTrue(publication.operationalFaults.containsAll(required), publication.operationalFaults.toString())
                    assertEquals(Digests.sha256(canonical(expectedRaw)), publication.rawResultDigest)
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work_progress"), "The deliberately failed INSERT was not committed")
                }
                f.await("Failed producer publication did not retain consumer unavailability") {
                    f.resolved(service, plan.slots.last().reviewId) != null
                }
                assertEquals("unavailable", f.resolved(service, plan.slots.last().reviewId))
                val retained = service.planStatus(client.client, plan.planId)
                assertTrue("produced_operational_publication_failed" in retained.slots.last().reasons)
                assertEquals("incomplete", retained.state); assertTrue("produced_plan_preview_unqualified" in retained.reasons)
                retained.slots.forEach { assertEquals(f.rules, it.requiredRuleIds) }
                assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get())
                assertEquals(1, provider.calls.get()); assertEquals(1, f.typedAttempts.size)
                assertFalse(Files.exists(service.root.resolve("produced-output").resolve(plan.planId)))
                service.ledger.read { c ->
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work")); assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                }
                assertTrue(f.events(service, client).none { it.kind == "progress" })
            } finally { service.ledger.transaction { c -> c.execute("DROP TRIGGER IF EXISTS synthetic_progress_insert_abort") } }
        }
    }

    private fun selectorMetadata(c: Connection, id: String, origin: String, consumer: String?) {
        c.execute("INSERT INTO produced_plan_admissions(plan_id,origin_instance) VALUES(?,?)", id, origin)
        listOf(ProducedLane1665Fixture.PRODUCER, ProducedLane1665Fixture.CONSUMER).forEach { slot ->
            c.execute("INSERT INTO plan_slots(plan_id,slot_id) VALUES(?,?)", id, slot)
        }
        c.execute("INSERT INTO work(work_key,state) VALUES(?,'complete')", "$id:producer")
        c.execute("INSERT INTO plan_slot_resolutions(plan_id,slot_id,state,work_key) VALUES(?,?,'ready',?)",
            id, ProducedLane1665Fixture.PRODUCER, "$id:producer")
        if (consumer == null) return
        if (consumer != "unavailable") c.execute("INSERT INTO work(work_key,state) VALUES(?,?)", "$id:consumer", consumer)
        c.execute("INSERT INTO plan_slot_resolutions(plan_id,slot_id,state,work_key) VALUES(?,?,?,?)", id, ProducedLane1665Fixture.CONSUMER,
            if (consumer == "unavailable") "unavailable" else "ready", if (consumer == "unavailable") null else "$id:consumer")
    }
}
