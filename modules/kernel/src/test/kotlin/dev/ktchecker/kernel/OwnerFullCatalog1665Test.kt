package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * Explicit1665 successor and synthetic legacy-catalog history controls only. Historical records below
 * are AUTHORED test DATA in a fresh temporary ledger, not old run evidence or execution authority.
 * No old submit path is enabled, no compiler/model/host runs, and no result is release-qualified.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OwnerFullCatalog1665Test {
    @TempDir lateinit var temp: Path
    private val added = listOf("ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier")
    private val unsupported = "produced_catalog_generation_unsupported"
    private val config = ProducedLane1665Fixture.CONFIG.copy(workerCount = 0)

    private fun vector(name: String, digest: String, count: Int): List<String> =
        assertNotNull(javaClass.getResourceAsStream(name)).use { stream ->
            val bytes = stream.readBytes(); assertEquals(digest, Digests.sha256(bytes))
            bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList().also {
                assertEquals(count, it.size); assertEquals(it.distinct().sorted(), it)
            }
        }
    private fun legacy() = vector("/joint-completion-full-catalog-1663.txt",
        "sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", 1663)
    private fun current() = vector("/joint-completion-full-catalog-1665.txt",
        "sha256:f7bc5eb975499c10ced7fc10ec7938984f98eb19079f9fcce32d451a8a5de431", 1665)
    private fun code(expected: String, action: () -> Unit) =
        assertEquals(expected, assertFailsWith<KernelException>(block = action).code)

    @Test fun currentContractIsExactVersioned1665AndOnlyTheTwoReviewedIdsWereAdded() {
        val old = legacy(); val ids = current()
        assertEquals("ktchecker/owner-full-catalog/1665-u2/1", OwnerFullCatalog1665.ID)
        assertEquals(1665, OwnerFullCatalog1665.RULE_COUNT)
        assertEquals("sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5", OwnerFullCatalog1665.RULE_IDS_DIGEST)
        assertEquals(OwnerFullCatalog1665.RULE_IDS_DIGEST, Digests.sha256(canonical(ids)))
        assertEquals("sha256:fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b", Digests.sha256(canonical(old)))
        assertEquals(added, ids - old.toSet()); assertEquals(old, ids - added.toSet())
        assertTrue(OwnerFullCatalog1665.accepts(ids)); assertFalse(OwnerFullCatalog1665.accepts(old))
        val substitutes = listOf(emptyList(), old, ids.reversed(), ids + ids.first(),
            (ids - added.first() + "ktc.compiler.common.unreviewed_same_count_substitute").sorted(),
            (ids - added.first() + ids.first()).sorted()) + added.map { ids - it }
        substitutes.forEach { assertFalse(OwnerFullCatalog1665.accepts(it), "No count-only gate or vector repair") }
    }

    @Test fun strictFresh1665AdmissionUsesTheSeparateFixtureWithoutGrantingAnyCoverageCredit() {
        val f = ProducedLane1665Fixture(temp.resolve("current-fixture")); val provider = f.Provider()
        KernelService.open(temp.resolve("current-ledger"), provider, config, planAuthority = f,
            preparedInputObserver = f.observer, resultPolicy = f.policy).use { service ->
            val client = service.registerClient(ProducedLane1665Fixture.binding("current"))
            val definition = f.definition(client.binding, "current")
            val accepted = service.submitPlan(client.client, definition.request)
            assertEquals(2, accepted.slots.size)
            assertTrue(accepted.slots.all { it.requiredRuleIds == current() && OwnerFullCatalog1665.accepts(it.requiredRuleIds) })
            assertEquals(1, f.producerPreparations.get()); assertEquals(0, provider.calls.get())
            assertEquals(0, f.resolutionCalls.getValue(definition.request.planRef).get()); assertTrue(f.typedAttempts.isEmpty())
            assertContains(accepted.reasons, "produced_plan_preview_unqualified")
            assertFalse(service.prepareGate(client.client).ready)
        }
    }

    /** Refuses every possible execution callback; the resolve hook supplies DATA only for fresh-denial controls. */
    private class Probe(ids: List<String>) : AnalysisProvider, ReviewPlanAuthority, PreparedInputObserver {
        override val id = ProducedLaneFixture.PROVIDER
        override val supportedRuleIds = ids.toSet()
        val resolveCalls = AtomicInteger(); val authorizeCalls = AtomicInteger()
        val providerCalls = AtomicInteger(); val observerCalls = AtomicInteger()
        var definition: RequiredReviewPlan? = null
        override fun resolve(binding: ClientBinding, request: SubmitPlanRequest): RequiredReviewPlan? {
            resolveCalls.incrementAndGet()
            return definition?.takeIf { it.binding == binding && it.request == request }
        }
        override fun authorizeProduced(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan? {
            authorizeCalls.incrementAndGet(); error("Synthetic legacy/current refusal DATA must not acquire a live grant")
        }
        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            providerCalls.incrementAndGet(); error("No provider invocation is authorized by recorded DATA")
        }
        override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
            observerCalls.incrementAndGet(); error("No scalar execution is authorized by recorded DATA")
        }
        override fun preparedProduced(attempt: ProducedExecutionAttempt) {
            observerCalls.incrementAndGet(); error("No typed execution is authorized by recorded DATA")
        }
        fun noExecution() {
            assertEquals(0, authorizeCalls.get()); assertEquals(0, providerCalls.get()); assertEquals(0, observerCalls.get())
        }
        fun noCallbacks() { assertEquals(0, resolveCalls.get()); noExecution() }
    }

    private fun rules(plan: RequiredReviewPlan, producer: List<String>, consumer: List<String> = producer): RequiredReviewPlan {
        val digest = Digests.sha256(canonical(producer))
        return plan.copy(jvmProduced = assertNotNull(plan.jvmProduced).copy(catalogDigest = digest, requiredRulesDigest = digest),
            slots = plan.slots.mapIndexed { index, slot -> slot.copy(input = slot.input.copy(
                requiredRuleIds = if (index == 0) producer else consumer,
                context = slot.input.context.copy(policies = slot.input.context.policies + ("ktchecker.catalog-inventory" to digest)))) })
    }

    @Test fun freshLegacyMissingAddedAndEqualCountSubstitutesFailBeforeGrantPreparationOrStaging() {
        val ids = current()
        val variants = listOf(legacy(), ids.reversed(),
            (ids - added.first() + "ktc.compiler.common.unreviewed_same_count_substitute").sorted(),
            (ids - added.first() + ids.first()).sorted()) + added.map { ids - it }
        variants.forEachIndexed { index, vector ->
            val f = ProducedLane1665Fixture(temp.resolve("fresh-fixture-$index")); val probe = Probe(ids)
            KernelService.open(temp.resolve("fresh-ledger-$index"), probe, config, planAuthority = probe,
                preparedInputObserver = probe).use { service ->
                val client = service.registerClient(ProducedLane1665Fixture.binding("fresh-$index"))
                probe.definition = rules(f.definition(client.binding, "fresh-$index"), vector)
                code("produced_plan_catalog_contract_mismatch") { service.submitPlan(client.client, probe.definition!!.request) }
                assertEquals(1, probe.resolveCalls.get()); probe.noExecution()
                assertEquals(0, f.producerPreparations.get()); assertTrue(f.admissions.isEmpty())
                service.ledger.read { c ->
                    listOf("parent_plans", "reviews", "plan_slots", "produced_plan_admissions", "work", "plan_slot_resolutions",
                        "plan_slot_resolution_claims", "work_publications", "work_certificates", "events").forEach {
                        assertEquals(0L, c.count("SELECT COUNT(*) FROM $it"), it)
                    }
                    assertEquals(1L, c.count("SELECT next_review FROM clients")); assertEquals(1L, c.count("SELECT next_event FROM clients"))
                }
                Files.list(service.root.resolve("inputs")).use { assertEquals(0L, it.count(), "Rejection precedes ALL archive staging") }
            }
        }
    }

    private enum class RecordedState(val producerComplete: Boolean, val consumerReady: Boolean, val cancelled: Boolean = false) {
        QUEUED_DEFERRED(false, false), COMPLETE_DEFERRED(true, false), COMPLETE_READY(true, true), CANCELLED_READY(true, true, true),
    }
    // Exactly the private recorded Resolution DATA shape, including defaults. No production deserializer bypass.
    @Serializable private data class RecordedResolution(
        val slotId: String, val reviewId: String, val state: String,
        val execution: ResolvedPlanExecution? = null, val reasons: List<String> = emptyList(),
    )
    private data class Seed(
        val service: KernelService, val client: ClientRegistration, val fixture: ProducedLaneFixture,
        val definition: RequiredReviewPlan, val id: String, val reviews: Map<String, String>,
        val executions: List<ResolvedPlanExecution>, val state: RecordedState,
    )

    private fun closedSeed(label: String, state: RecordedState, producer: List<String> = legacy(), consumer: List<String> = producer): Seed {
        val probe = Probe(legacy())
        val service = KernelService.open(temp.resolve("recorded-$label"), probe, config, planAuthority = probe, preparedInputObserver = probe)
        val client = service.registerClient(ProducedLaneFixture.binding(label))
        // Stop these NEW synthetic service threads BEFORE writing DATA. No historical disk/log is opened.
        service.close(); probe.noCallbacks()
        return seedRecords(service, client, label, state, producer, consumer)
    }

    /**
     * Authored legacy-schema DATA only. Bypasses submit deliberately, never validates or restores a
     * live grant. Pure certificates and a tiny non-classfile JAR model formerly accepted relations;
     * ALL source/archive/parent/review/publication fields stay bound exactly as in schema3.
     * The NEW fixture ledger is schema4: its synthetic provider ages are seeded explicitly below;
     * this is DATA construction, not migration or a claim to reconstruct historical provider turns.
     */
    private fun seedRecords(service: KernelService, client: ClientRegistration, label: String, state: RecordedState,
        producerRules: List<String> = legacy(), consumerRules: List<String> = producerRules): Seed {
        val f = ProducedLaneFixture(temp.resolve("recorded-fixture-$label"))
        val definition = rules(f.definition(client.binding, label), producerRules, consumerRules)
        val id = "documentary-parent-$label"; val origin = service.instanceId
        val admission = ProducedPlanAdmissionRequest(definition, id, Digests.sha256(canonical(definition)), service.providerKey, origin)
        val graph = admission.declaration; val authority = Digests.sha256("SYNTHETIC recorded authority, not restorable:$label")
        val reviews = definition.slots.associate { it.slotId to "documentary-review-$label-${it.slotId}" }
        val staged = mutableListOf<InputArchive.Staged>()
        try {
            val archives = definition.slots.map { slot ->
                val key = Digests.sha256(canonical(listOf(PRODUCED_JVM_PLAN_PROFILE, "declared-archive", id, slot.slotId, slot.input.analysisKey())))
                val held = service.archive.stage(key, slot.input); staged += held
                DeclaredPlanArchive(admission, DeclaredArchiveIdentity(slot.slotId, key, slot.input.analysisKey(), held.input, held.bytes),
                    held.readInput(), held::readInput) { held.verify(config.maxSnapshotBytes) }
            }
            val preparation = ProducedPlanPreparationRequest(admission, archives)
            fun certify(proposed: PreparedProducedExecution, producer: ResolvedPlanExecution? = null, publication: ProducedWorkPublication? = null): ResolvedPlanExecution {
                val slot = definition.slots.single { it.slotId == proposed.slotId }
                return ResolvedPlanExecution(proposed.input, PlanExecutionCertificate(id, admission.planDigest, slot.slotId,
                    reviews.getValue(slot.slotId), Digests.sha256(canonical(slot)), slot.input.analysisKey(), graph.graphDigest,
                    preparation.archive(slot.slotId).identity.digest(), proposed.input.analysisKey(), assertNotNull(contextIdentity(proposed.input)),
                    proposed.bindingDigest, admission.providerKey, origin, graph.originalDeadlineIdentity,
                    producer?.reviewId, producer?.workKey, publication?.digest(), proposed.producedManifest))
            }
            val declared = definition.slots.first().input
            val dependency = Digests.sha256("SYNTHETIC recorded producer dependency:$label")
            val p = declared.copy(context = declared.context.copy(dependencyDigest = dependency, policies = declared.context.policies + mapOf(
                "ktchecker.execution-plan" to Digests.sha256(canonical(emptyMap<String, TrustedExecution>())),
                "ktchecker.jvm-produced-preview" to graph.graphDigest, "ktchecker.jvm-produced-resolution" to dependency)))
            val producer = certify(PreparedProducedExecution(graph.producer.slotId, p, Digests.sha256("SYNTHETIC producer binding:$label")) { })
            val producerArchive = service.archive.stageResolvedFromDeclaration(producer.workKey, producer.input, preparation.archive(producer.slotId))
            staged += producerArchive
            fun effective(execution: ResolvedPlanExecution, archive: InputArchive.Staged) = archive.input.copy(outputRoot = service.root.resolve("outputs")
                .resolve(execution.workKey.removePrefix("sha256:")).resolve("attempt-1-$origin").toString())
            fun raw(input: AnalysisInput): ProviderResult {
                val base = f.partial(input)
                return base.copy(findings = base.findings.map { it.copy(ruleId = input.requiredRuleIds.first()) },
                    coverage = input.requiredRuleIds.map { Coverage(it, input.context.scope, "unknown", "synthetic_catalog_execution_not_qualified") })
            }
            val producerRaw = raw(effective(producer, producerArchive))
            val publication = ProducedWorkPublication(producer.workKey, 1, origin, producer.certificate.digest(), producer.input.analysisKey(),
                effective(producer, producerArchive).analysisKey(), admission.providerKey, Digests.sha256(canonical(producerRaw)), emptyList())
            val nonce = "documentary-consumer-$label"
            val consumer = if (state.consumerReady) {
                // Fixture-only tiny text JAR, not a genuine compiler output or Accepted capability.
                val candidate = f.consumer(ProducedSlotResolutionRequest(preparation, producer, publication, producerRaw,
                    service.root.resolve("produced-output").resolve(id).resolve(nonce)))
                certify(candidate, producer, publication)
            } else null
            val consumerArchive = consumer?.let {
                service.archive.stageResolvedFromDeclaration(it.workKey, it.input, preparation.archive(it.slotId),
                    assertNotNull(it.certificate.producedManifest).completeJar).also { archive -> staged += archive }
            }
            val executions = listOfNotNull(producer, consumer)
            val workArchives = listOfNotNull(producerArchive, consumerArchive)
            val rawResults = executions.zip(workArchives).associate { (execution, archive) -> execution.reviewId to raw(effective(execution, archive)) }
            val fair = Digests.sha256(canonical(listOf(client.binding.ownerId, client.binding.workspaceId, client.binding.worktreeId)))
            service.ledger.transaction { c ->
                c.execute("INSERT INTO fair_queues(fair_key,last_served) VALUES(?,0)", fair)
                c.execute("INSERT INTO fair_clients(client_id,fair_key,last_served) VALUES(?,?,0)", client.binding.clientId, fair)
                c.execute("INSERT INTO provider_fair_queues(provider_key,fair_key,last_served) VALUES(?,?,0)", admission.providerKey, fair)
                c.execute("INSERT INTO provider_fair_clients(provider_key,client_id,last_served) VALUES(?,?,0)", admission.providerKey, client.binding.clientId)
                c.execute("""INSERT INTO parent_plans(plan_id,client_id,patch_id,idempotency_key,request_hash,definition_json,plan_digest,review_frontier,cancelled)
                    VALUES(?,?,?,?,?,?,?,2,?)""", id, client.binding.clientId, definition.request.patchId, definition.request.idempotencyKey,
                    Digests.sha256(canonical(definition.request)), canonical(definition), admission.planDigest, if (state.cancelled) 1 else 0)
                val identities = archives.map { it.identity }
                val admissionDigest = Digests.sha256(canonical(listOf(id, admission.planDigest, origin, admission.providerKey, authority, canonical(identities))))
                c.execute("""INSERT INTO produced_plan_admissions(plan_id,origin_instance,authority_identity,provider_key,producer_slot_id,
                    consumer_slot_id,fair_key,archives_json,archive_bytes,admission_digest) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    id, origin, authority, admission.providerKey, graph.producer.slotId, graph.consumer.slotId, fair,
                    canonical(identities), identities.sumOf { it.byteCount }, admissionDigest)
                definition.slots.forEachIndexed { index, slot ->
                    val key = Digests.sha256(canonical(listOf(id, slot.slotId)))
                    val child = SubmitRequest("plan-child:$key", "plan-child:$key", slot.input)
                    val review = reviews.getValue(slot.slotId)
                    val completed = state.producerComplete && (index == 0 || state.consumerReady)
                    val rawText = rawResults[review]?.takeIf { completed }?.let { canonical(it) }
                    val policy = if (rawText == null) service.admittedPolicyCertificate else canonical(PolicyCertificate(
                        service.policyEvaluatorIdentity, PolicyCertificate.EVALUATED, PolicyCertificate.effectiveDigest(
                            service.policyEvaluatorIdentity, client.binding, slot.input, Digests.sha256(rawText), Digests.sha256(rawText))))
                    c.execute("""INSERT INTO reviews(review_id,client_id,patch_id,idempotency_key,request_hash,request_json,input_json,work_key,
                        kind,sequence,cancelled,effective_result_json,policy_certificate,created_at) VALUES(?,?,?,?,?,?,?,NULL,'produced',?,?,?,?,0)""",
                        review, client.binding.clientId, child.patchId, child.idempotencyKey, Digests.sha256(canonical(child)), canonical(child),
                        canonical(slot.input), index + 1, if (state.cancelled) 1 else 0, rawText, policy)
                    c.execute("INSERT INTO plan_slots(plan_id,slot_id,review_id) VALUES(?,?,?)", id, slot.slotId, review)
                }
                c.execute("UPDATE clients SET next_review=3,epoch=2 WHERE client_id=?", client.binding.clientId)
                if (consumer != null) c.execute("""INSERT INTO plan_slot_resolution_claims(plan_id,slot_id,origin_instance,claim_nonce,reserved_archive_bytes)
                    VALUES(?,?,?,?,?)""", id, consumer.slotId, origin, nonce, preparation.archive(consumer.slotId).identity.byteCount + 134_217_728L)
                executions.zip(workArchives).forEach { (execution, archive) ->
                    val complete = state.producerComplete
                    val result = rawResults.getValue(execution.reviewId); val rawText = canonical(result)
                    c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,owner_instance,attempt,result_json,archive_bytes,created_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,0)""", execution.workKey, canonical(execution.input), canonical(archive.input), admission.providerKey,
                        fair, if (complete) "complete" else "queued", if (complete) origin else null, if (complete) 1 else 0,
                        rawText.takeIf { complete }, archive.bytes)
                    val record = RecordedResolution(execution.slotId, execution.reviewId, "ready", execution)
                    c.execute("""INSERT INTO plan_slot_resolutions(plan_id,slot_id,review_id,state,record_json,record_digest,
                        certificate_id,resolved_input_json,work_key,reasons_json) VALUES(?,?,?,'ready',?,?,?,?,?,?)""",
                        id, execution.slotId, execution.reviewId, canonical(record), Digests.sha256(canonical(record)),
                        execution.certificate.digest(), canonical(execution.input), execution.workKey, canonical(emptyList<String>()))
                    if (complete) {
                        val originalKey = execution.input.analysisKey(); val effectiveKey = effective(execution, archive).analysisKey()
                        val rawDigest = Digests.sha256(rawText)
                        val certificate = Digests.sha256(canonical(listOf(originalKey, effectiveKey, rawDigest, admission.providerKey)))
                        c.execute("""INSERT INTO work_certificates(work_key,input_key,effective_key,result_digest,provider_key,certificate_id)
                            VALUES(?,?,?,?,?,?)""", execution.workKey, originalKey, effectiveKey, rawDigest, admission.providerKey, certificate)
                        val published = ProducedWorkPublication(execution.workKey, 1, origin, execution.certificate.digest(), originalKey,
                            effectiveKey, admission.providerKey, rawDigest, emptyList())
                        c.execute("INSERT INTO work_publications(work_key,attempt,origin_instance,record_json,certificate_id) VALUES(?,1,?,?,?)",
                            execution.workKey, origin, canonical(published), published.digest())
                    } else c.execute("INSERT INTO work_progress(work_key,payload_digest,payload_json,sequence,byte_count) VALUES(?,?,?,1,?)",
                        execution.workKey, Digests.sha256(rawText), rawText, rawText.toByteArray().size)
                }
                // Literal pre-upgrade documentary outbox. Current status projection must NOT rewrite it.
                val oldParent = ParentPlanStatus(id, client.binding.clientId, definition.request.patchId, declared.snapshot.snapshotId,
                    admission.planDigest, "pending", definition.slots.map { slot -> PlanSlotStatus(slot.slotId, reviews.getValue(slot.slotId),
                        slot.input.context.scope, slot.input.requiredRuleIds, if (slot.slotId == producer.slotId) "queued" else "deferred", slot.reasons) },
                    listOf("produced_plan_preview_unqualified"))
                val acceptedRows = definition.slots.mapIndexed { index, slot ->
                    val key = Digests.sha256(canonical(listOf(id, slot.slotId)))
                    ReviewStatus(reviews.getValue(slot.slotId), client.binding.clientId, "plan-child:$key", index + 1L, declared.snapshot.snapshotId, "accepted")
                }
                acceptedRows.forEach { service.appendEvent(c, client.binding, it, "accepted") }
                service.appendEvent(c, client.binding, acceptedRows.last().copy(state = "plan_accepted", parentPlan = oldParent), "plan_accepted")
                acceptedRows.forEach { accepted ->
                    rawResults[accepted.reviewId]?.let { result ->
                        val complete = state.producerComplete
                        val terminal = accepted.copy(state = if (complete) "incomplete" else "running", result = result)
                        service.appendEvent(c, client.binding, terminal, if (complete) "result" else "progress")
                        if (state.cancelled) service.appendEvent(c, client.binding, terminal.copy(state = "cancelled", cancelled = true), "cancelled")
                    }
                }
                c.query("SELECT event_id,payload FROM events ORDER BY sequence") { it.getString(1) to it.getString(2) }.forEach { (event, payload) ->
                    val digest = Digests.sha256("synthetic-context:$payload")
                    val ack = ContextReceipt("ack-$event", digest, "item-$event", Instant.EPOCH.toString())
                    val observation = ObservationReceipt("observe-$event", "request-$event", listOf(digest))
                    c.execute("INSERT INTO receipts(client_id,kind,receipt_id,request_json) VALUES(?,'ack',?,?)", client.binding.clientId,
                        ack.receiptId, canonical(listOf(canonical(listOf(event)), canonical(ack))))
                    c.execute("INSERT INTO receipts(client_id,kind,receipt_id,request_json) VALUES(?,'observe',?,?)", client.binding.clientId,
                        observation.observationId, canonical(listOf(canonical(listOf(event)), canonical(observation))))
                    c.execute("""INSERT INTO acknowledgements(event_id,client_id,receipt_id,context_digest,context_item_id,inserted_at,receipt_json)
                        VALUES(?,?,?,?,?,?,?)""", event, client.binding.clientId, ack.receiptId, digest, ack.contextItemId, ack.insertedAt, canonical(ack))
                    c.execute("""INSERT INTO observations(event_id,client_id,observation_id,model_request_id,context_digest,receipt_json)
                        VALUES(?,?,?,?,?,?)""", event, client.binding.clientId, observation.observationId, observation.modelRequestId, digest, canonical(observation))
                }
                staged.forEach { it.install() }
            }
            assertTrue(f.admissions.isEmpty()); assertTrue(f.typedAttempts.isEmpty()); assertEquals(0, f.producerPreparations.get())
            assertTrue(f.resolutionCalls.values.all { it.get() == 0 }) // f.consumer above was a DATA builder, never an authority callback.
            return Seed(service, client, f, definition, id, reviews, executions, state)
        } finally { staged.forEach { it.close() } }
    }

    private val immutableQueries = linkedMapOf(
        "parents" to "SELECT * FROM parent_plans ORDER BY plan_id",
        "admissions" to "SELECT * FROM produced_plan_admissions ORDER BY plan_id",
        "declarations" to "SELECT review_id,client_id,patch_id,idempotency_key,request_hash,request_json,input_json,work_key,kind,sequence,cancelled,created_at FROM reviews ORDER BY review_id",
        "slots" to "SELECT * FROM plan_slots ORDER BY plan_id,slot_id",
        "resolutions" to "SELECT * FROM plan_slot_resolutions ORDER BY plan_id,slot_id",
        "work-inputs" to "SELECT work_key,input_json,frozen_json,provider_key,fair_key,archive_bytes,created_at FROM work ORDER BY work_key",
        "raw-results" to "SELECT work_key,result_json FROM work WHERE result_json IS NOT NULL ORDER BY work_key",
        "effective-results" to "SELECT review_id,effective_result_json,policy_certificate,policy_validation_json FROM reviews WHERE effective_result_json IS NOT NULL ORDER BY review_id",
        "publications" to "SELECT * FROM work_publications ORDER BY work_key",
        "certificates" to "SELECT * FROM work_certificates ORDER BY work_key",
        "progress" to "SELECT * FROM work_progress ORDER BY work_key,sequence",
        "claims" to "SELECT * FROM plan_slot_resolution_claims ORDER BY plan_id,slot_id",
        "events" to "SELECT * FROM events ORDER BY client_id,sequence",
        "receipts" to "SELECT * FROM receipts ORDER BY client_id,kind,receipt_id",
        "acknowledgements" to "SELECT * FROM acknowledgements ORDER BY event_id",
        "observations" to "SELECT * FROM observations ORDER BY event_id,observation_id",
    )
    private data class LiteralHistory(val rows: Map<String, List<List<String?>>>, val files: Map<String, ByteArray>)
    private fun rows(c: Connection, sql: String): List<List<String?>> = c.query(sql) { r ->
        (1..r.metaData.columnCount).map { r.getString(it) }
    }
    private fun archiveFiles(service: KernelService): Map<String, ByteArray> = listOf("inputs", "produced-output").flatMap { name ->
        val root = service.root.resolve(name)
        if (!Files.exists(root)) emptyList() else Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }.associate { service.root.relativize(it).toString() to Files.readAllBytes(it) }
    private fun history(service: KernelService) = LiteralHistory(service.ledger.read { c -> immutableQueries.mapValues { rows(c, it.value) } }, archiveFiles(service))
    private fun assertLiteralHistory(before: LiteralHistory, service: KernelService) {
        val after = history(service)
        before.rows.forEach { (name, oldRows) -> oldRows.forEach { row ->
            assertTrue(row in after.rows.getValue(name), "Previously recorded $name row disappeared or its literal fields changed")
        } }
        assertEquals(before.files.keys, after.files.keys, "Read/status/delivery must not stage new input or produced-output archives")
        before.files.forEach { (name, bytes) -> assertContentEquals(bytes, after.files.getValue(name), name) }
    }
    private fun assertLegacyStatus(service: KernelService, seed: Seed): ParentPlanStatus {
        val status = service.planStatus(seed.client.client, seed.id)
        assertEquals(seed.id, status.planId); assertEquals(seed.definition.request.patchId, status.patchId)
        assertEquals(Digests.sha256(canonical(seed.definition)), status.planDigest)
        assertEquals(seed.definition.slots.map { seed.reviews.getValue(it.slotId) }, status.slots.map { it.reviewId })
        assertEquals(seed.definition.slots.map { it.input.requiredRuleIds }, status.slots.map { it.requiredRuleIds })
        assertTrue(status.slots.all { it.requiredRuleIds.size == 1663 && unsupported in it.reasons })
        assertContains(status.reasons, unsupported)
        assertEquals(if (seed.state.cancelled) "cancelled" else "incomplete", status.state)
        status.slots.forEach { slot ->
            val review = service.status(seed.client.client, slot.reviewId)
            assertEquals(if (seed.state.cancelled) "cancelled" else "incomplete", review.state)
            assertEquals(if (seed.state.cancelled) "cancelled" else "unavailable", slot.state)
            assertContains(review.reasons, unsupported)
        }
        service.ledger.transaction { c -> seed.executions.forEach { execution ->
            assertFalse(service.producedPlans.executionCertificateValid(c, execution.reviewId, execution.input))
            assertFalse(service.producedPlans.mayClaim(c, execution.workKey), "A reopened broker cannot claim recorded legacy work")
        } }
        return status
    }

    @Test fun workerlessReopenPreservesLegacyQueuedCompletedDeferredReadyAndCancelledHistoriesAfterDelivery() {
        RecordedState.entries.forEach { state ->
            val seed = closedSeed(state.name.lowercase(), state); val before = history(seed.service)
            val probe = Probe(current()); val fence = ProducedLaneFixture.Fence().also { it.install(seed.definition) }
            KernelService.open(seed.service.root, probe, config, snapshotGate = fence, planAuthority = probe,
                preparedInputObserver = probe).use { service ->
                service.registerClient(seed.client)
                val parent = assertLegacyStatus(service, seed)
                assertLiteralHistory(before, service)
                // Lost-response recovery is a READ of the same exact request, not a fresh1663 admission.
                assertEquals(parent, service.submitPlan(seed.client.client, seed.definition.request))
                code("idempotency_conflict") { service.submitPlan(seed.client.client, seed.definition.request.copy(planRef = "different-request-hash")) }
                probe.noCallbacks()
                seed.executions.forEach { execution ->
                    assertNull(service.producedPlans.clock(execution.workKey)); assertFalse(service.producedPlans.isCurrent(execution.workKey))
                    code("produced_plan_live_authority_unavailable") {
                        service.producedPlans.attempt(execution.workKey, execution.input, execution.input, 1) { error("No archive verifier callback") }
                    }
                }
                seed.fixture.deliver(service, seed.client) // Exact old receipts are idempotent; only new unavailability events are added.
                val cut = service.prepareGate(seed.client.client)
                assertFalse(cut.ready)
                assertContains(cut.reasons, "parent_plan_${if (state.cancelled) "cancelled" else "incomplete"}:${seed.id}")
                assertTrue(cut.reasons.none { it.startsWith("insertion_missing:") || it.startsWith("model_observation_missing:") || it == "delivery_frontier_changed" })
                val effects = AtomicInteger()
                val denied = service.completeGate(seed.client.client, cut.cutId) { effects.incrementAndGet() }
                assertFalse(denied.allowed); assertFalse(denied.actionPerformed); assertEquals(0, effects.get())
                assertLegacyStatus(service, seed); probe.noCallbacks(); assertLiteralHistory(before, service)
                service.ledger.read { c ->
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM parent_plans")); assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                    assertEquals(seed.executions.size.toLong(), c.count("SELECT COUNT(*) FROM work"))
                    assertEquals(if (state.consumerReady) 1L else 0L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM work WHERE state IN ('queued','running')"))
                    seed.executions.forEach { execution -> assertEquals(if (state.producerComplete) 1L else 0L,
                        c.count("SELECT attempt FROM work WHERE work_key=?", execution.workKey)) }
                }
                if (state.producerComplete) seed.executions.forEach { execution ->
                    val result = assertNotNull(service.status(seed.client.client, execution.reviewId).result)
                    assertEquals(seed.definition.slots.single { it.slotId == execution.slotId }.input.requiredRuleIds, result.coverage.map { it.ruleId })
                    assertEquals(1, result.findings.size); assertFalse(result.complete())
                    assertContains(cut.reasons, "open_finding:${result.findings.single().fingerprint}")
                }
            }
        }
    }

    @Test fun aDifferentIdempotencyKeyCannotTurnALegacyRequestIntoFreshAdmissionOrOverwriteItsObligations() {
        val seed = closedSeed("legacy-new-key", RecordedState.COMPLETE_READY)
        val before = history(seed.service); val probe = Probe(current())
        KernelService.open(seed.service.root, probe, config, planAuthority = probe, preparedInputObserver = probe).use { service ->
            service.registerClient(seed.client)
            val original = assertLegacyStatus(service, seed)
            assertEquals(original, service.submitPlan(seed.client.client, seed.definition.request)); probe.noCallbacks()
            val fresh = seed.definition.copy(request = seed.definition.request.copy(idempotencyKey = "different-new-key"))
            probe.definition = fresh
            code("produced_plan_catalog_contract_mismatch") { service.submitPlan(seed.client.client, fresh.request) }
            assertEquals(1, probe.resolveCalls.get()); probe.noExecution()
            assertEquals(original, assertLegacyStatus(service, seed)); assertLiteralHistory(before, service)
            service.ledger.read { c ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM parent_plans")); assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                assertEquals(2L, c.count("SELECT COUNT(*) FROM work")); assertEquals(1L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
            }
        }
    }

    @Test fun oldSortedDistinct1663ShapeRemainsReadableWithoutRetrospectivelyInventingAnExactLegacyIdOracle() {
        val alternative = (legacy().drop(1) + "ktc.synthetic.formerly_accepted_1663_shape").sorted()
        assertEquals(1663, alternative.size); assertEquals(alternative.distinct().sorted(), alternative)
        assertNotEquals(Digests.sha256(canonical(legacy())), Digests.sha256(canonical(alternative)))
        val seed = closedSeed("legacy-prior-shape", RecordedState.COMPLETE_READY, alternative)
        val before = history(seed.service); val probe = Probe(current())
        KernelService.open(seed.service.root, probe, config, planAuthority = probe, preparedInputObserver = probe).use { service ->
            service.registerClient(seed.client)
            val status = assertLegacyStatus(service, seed)
            assertTrue(status.slots.all { it.requiredRuleIds == alternative })
            assertEquals(status, service.submitPlan(seed.client.client, seed.definition.request))
            assertFalse(service.prepareGate(seed.client.client).ready); probe.noCallbacks(); assertLiteralHistory(before, service)
        }
    }

    @Test fun malformedPersistedCurrentOrLegacyVectorsStillFailEveryRelationReadRatherThanBeingUpgraded() {
        val old = legacy(); val ids = current()
        data class Case(val name: String, val producer: List<String>, val consumer: List<String> = producer, val code: String)
        val cases = listOf(
            Case("current-substitute", (ids - added.first() + "ktc.synthetic.same_count_not_reviewed").sorted(), code = "produced_plan_corrupt"),
            Case("legacy-duplicate", (old.drop(1) + old.last()).sorted(), code = "produced_plan_declaration_mismatch"),
            Case("legacy-unsorted", old.reversed(), code = "produced_plan_declaration_mismatch"),
            Case("count1664", ids - added.first(), code = "produced_plan_corrupt"),
            Case("mixed-slots", old, ids, "produced_plan_declaration_mismatch"),
        )
        cases.forEach { case ->
            val seed = closedSeed(case.name, RecordedState.COMPLETE_READY, case.producer, case.consumer)
            val before = history(seed.service); val probe = Probe(current())
            KernelService.open(seed.service.root, probe, config, planAuthority = probe, preparedInputObserver = probe).use { service ->
                service.registerClient(seed.client)
                code(case.code) { service.planStatus(seed.client.client, seed.id) }
                seed.reviews.values.forEach { review -> code(case.code) { service.status(seed.client.client, review) } }
                code(case.code) { service.submitPlan(seed.client.client, seed.definition.request) }
                code(case.code) { service.prepareGate(seed.client.client) }
                service.ledger.read { c -> seed.executions.forEach { execution ->
                    assertFalse(service.producedPlans.executionCertificateValid(c, execution.reviewId, execution.input))
                } }
                probe.noCallbacks(); assertLiteralHistory(before, service)
            }
        }
    }

    @Test fun sameInstanceDefensiveClaimAndResolutionChecksNeverCreateALegacyWorkGrantOrConsumerReservation() {
        for (state in listOf(RecordedState.QUEUED_DEFERRED, RecordedState.COMPLETE_DEFERRED)) {
            val label = "same-instance-${state.name.lowercase()}"; val probe = Probe(current())
            KernelService.open(temp.resolve(label), probe, config, planAuthority = probe, preparedInputObserver = probe).use { service ->
                val client = service.registerClient(ProducedLaneFixture.binding(label))
                val seed = seedRecords(service, client, label, state)
                val producer = seed.executions.single()
                service.ledger.transaction { c -> assertFalse(service.producedPlans.mayClaim(c, producer.workKey)) }
                service.producedPlans.resolveNext() // May race the one normal resolver; both must produce only unavailability.
                seed.fixture.await("Legacy consumer remains visible unavailable, never resolved or admitted") {
                    seed.fixture.resolved(service, seed.reviews.getValue(ProducedLaneFixture.CONSUMER)) == "unavailable"
                }
                assertLegacyStatus(service, seed)
                service.ledger.read { c ->
                    assertEquals(0L, c.count("SELECT COUNT(*) FROM plan_slot_resolution_claims"))
                    assertEquals(1L, c.count("SELECT COUNT(*) FROM work")); assertEquals(2L, c.count("SELECT COUNT(*) FROM reviews"))
                    assertEquals(0L, service.queuedCapacityUsed(c), "Terminal unavailability releases queue bookkeeping, not coverage obligations")
                    val consumer = c.one("SELECT reasons_json FROM plan_slot_resolutions WHERE plan_id=? AND slot_id=?",
                        seed.id, ProducedLaneFixture.CONSUMER) { it.getString(1) }
                    assertEquals(canonical(listOf(unsupported)), consumer)
                    assertEquals(if (state.producerComplete) 1L else 0L, c.count("SELECT attempt FROM work WHERE work_key=?", producer.workKey))
                }
                probe.noCallbacks(); assertTrue(seed.fixture.resolutionCalls.values.all { it.get() == 0 })
                assertTrue(seed.fixture.typedAttempts.isEmpty()); assertFalse(service.prepareGate(client.client).ready)
            }
        }
    }
}
