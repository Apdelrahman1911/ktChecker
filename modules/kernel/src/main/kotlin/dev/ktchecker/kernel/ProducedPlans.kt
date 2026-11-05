package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

/** One immutable whole parent, two original declaration reviews, append-only D→P executions.
 * Only this broker's retained live grant can execute; no resolved work is shared or replayed.
 * All source/owner operations are outside ledger/config locks. This is never a final gate grant. */
internal class ProducedPlans(private val service: KernelService, private val authority: ReviewPlanAuthority) {
    private val ledger get() = service.ledger
    private val config get() = service.config

    @Serializable private data class Resolution(
        val slotId: String, val reviewId: String, val state: String,
        val execution: ResolvedPlanExecution? = null, val reasons: List<String> = emptyList(),
    )
    private class Live(
        val request: ProducedPlanAdmissionRequest,
        val grant: AuthorizedProducedPlan,
        val preparation: ProducedPlanPreparationRequest,
        val reviewIds: Map<String, String>,
    ) {
        val executions = ConcurrentHashMap<String, ResolvedPlanExecution>()
    }
    private data class Stored(
        val request: ProducedPlanAdmissionRequest, val authorityIdentity: String,
        val archives: List<DeclaredArchiveIdentity>, val reviews: Map<String, String>,
        val cancelled: Boolean, val frontier: Long,
    )
    private val retained = ConcurrentHashMap<String, Live>()
    private val executions = ConcurrentHashMap<String, Pair<Live, ResolvedPlanExecution>>()

    fun submit(credential: ClientCredential, binding: ClientBinding, request: SubmitPlanRequest,
        definition: RequiredReviewPlan): ParentPlanStatus {
        validateDeclaration(binding, request, definition)
        ledger.read { c -> service.plans.prior(c, binding.clientId, request.idempotencyKey, Digests.sha256(canonical(request))) }?.let { return it }
        val id = UUID.randomUUID().toString()
        val admitted = ProducedPlanAdmissionRequest(definition, id, Digests.sha256(canonical(definition)), service.providerKey, service.instanceId)
        val grant = authority.authorizeProduced(admitted)
            ?: fail("produced_plan_authority_unavailable", "Only an exact live owner may authorize declared-slot execution")
        if (grant.request !== admitted || !Digests.valid(grant.authorityIdentity) ||
            grant.clock.identity != admitted.declaration.originalDeadlineIdentity || grant.clock.durationMillis != admitted.declaration.originalDeadlineMillis) {
            fail("produced_plan_grant_mismatch", "Live grant must retain this exact request and original budget")
        }
        requireLive(grant)
        grant.verifyForAdmission()
        val preflightResult = ledger.read { c ->
            val previous = service.plans.prior(c, binding.clientId, request.idempotencyKey, Digests.sha256(canonical(request)))
            if (previous != null) previous to 0L else {
                preflight(c, admitted)
                null to (config.maxArchiveBytes - service.archiveBytesUsed(c))
            }
        }
        preflightResult.first?.let { return it } // Exact concurrent acceptance is idempotent at EVERY preflight.
        val stageBudget = preflightResult.second
        val staged = linkedMapOf<String, InputArchive.Staged>()
        var admissionLive: Live? = null
        var installationStarted = false
        try {
            var bytes = 0L
            val archives = definition.slots.map { slot ->
                requireLive(grant)
                val key = Digests.sha256(canonical(listOf(PRODUCED_JVM_PLAN_PROFILE, "declared-archive", id, slot.slotId, slot.input.analysisKey())))
                val ready = service.archive.stage(key, slot.input)
                staged[key] = ready
                bytes = charge(stageBudget, bytes, ready.bytes)
                DeclaredPlanArchive(admitted, DeclaredArchiveIdentity(slot.slotId, key, slot.input.analysisKey(), ready.input, ready.bytes),
                    ready.readInput(), ready::readInput) {
                    ready.verify(config.maxSnapshotBytes)
                }
            }
            val preparation = ProducedPlanPreparationRequest(admitted, archives)
            val reviews = definition.slots.associate { it.slotId to UUID.randomUUID().toString() }
            val live = Live(admitted, grant, preparation, reviews)
            val proposed = grant.prepareProducer(preparation)
            val producer = certify(live, proposed, null, null)
            requireLive(grant); proposed.verifyForAdmission(); archives.forEach { it.verify() }
            val ready = service.archive.stageResolvedFromDeclaration(producer.workKey, producer.input,
                preparation.archive(admitted.declaration.producer.slotId))
            staged[producer.workKey] = ready
            bytes = charge(stageBudget, bytes, ready.bytes)
            requireLive(grant); proposed.verifyForAdmission(); archives.forEach { it.verify() }; grant.verifyForAdmission()
            return ledger.transaction { c ->
                service.ensureOpen()
                if (service.authenticate(c, credential) != binding) fail("unauthorized", "Client enrollment changed")
                service.plans.prior(c, binding.clientId, request.idempotencyKey, Digests.sha256(canonical(request)))?.let { return@transaction it }
                preflight(c, admitted)
                requireLive(grant) // Final concrete clock/atomic validity only: no owner callback.
                if (bytes > config.maxArchiveBytes - service.archiveBytesUsed(c)) fail("archive_full", "Whole declaration and execution archives exceed quota")
                // Serialize capacity registration with the final idempotency/preflight decision.
                // A concurrent accepted duplicate never owns a second retained live grant.
                if (retained.size >= minOf(config.maxRetainedReviews, 4096L)) fail("produced_plan_capacity", "Live plan tombstones cannot be evicted")
                if (retained.putIfAbsent(id, live) != null) fail("produced_plan_identity_conflict", "Live plan identity cannot be replaced")
                admissionLive = live
                retainExecution(live, producer)
                c.execute("""INSERT INTO parent_plans(plan_id,client_id,patch_id,idempotency_key,request_hash,definition_json,plan_digest,review_frontier)
                    VALUES(?,?,?,?,?,?,?,0)""", id, binding.clientId, request.patchId, request.idempotencyKey,
                    Digests.sha256(canonical(request)), canonical(definition), admitted.planDigest)
                val archiveIdentities = archives.map { it.identity }
                val archiveBytes = archiveIdentities.sumOf { it.byteCount }
                val fair = fairKey(binding)
                c.execute("INSERT OR IGNORE INTO fair_queues(fair_key,last_served) VALUES(?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))", fair)
                c.execute("""INSERT INTO produced_plan_admissions(plan_id,origin_instance,authority_identity,provider_key,
                    producer_slot_id,consumer_slot_id,fair_key,archives_json,archive_bytes,admission_digest) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    id, service.instanceId, grant.authorityIdentity, service.providerKey, admitted.declaration.producer.slotId,
                    admitted.declaration.consumer.slotId, fair, canonical(archiveIdentities), archiveBytes,
                    admissionDigest(admitted, grant.authorityIdentity, archiveIdentities))
                // ALL immutable reviews/sequence numbers/plan_slots precede any executable work.
                definition.slots.forEach { slot ->
                    val review = reviews.getValue(slot.slotId)
                    val child = childRequest(id, slot)
                    val sequence = c.count("SELECT next_review FROM clients WHERE client_id=?", binding.clientId)
                    c.execute("""INSERT INTO reviews(review_id,client_id,patch_id,idempotency_key,request_hash,request_json,input_json,
                        work_key,kind,sequence,policy_certificate,created_at) VALUES(?,?,?,?,?,?,?,NULL,'produced',?,?,?)""",
                        review, binding.clientId, child.patchId, child.idempotencyKey, Digests.sha256(canonical(child)), canonical(child),
                        canonical(slot.input), sequence, service.admittedPolicyCertificate, System.currentTimeMillis())
                    c.execute("INSERT INTO plan_slots(plan_id,slot_id,review_id) VALUES(?,?,?)", id, slot.slotId, review)
                    c.execute("UPDATE clients SET next_review=next_review+1,epoch=epoch+1 WHERE client_id=?", binding.clientId)
                    service.appendEvent(c, binding, ReviewStatus(review, binding.clientId, child.patchId, sequence,
                        slot.input.snapshot.snapshotId, "accepted"), "accepted")
                }
                c.execute("UPDATE parent_plans SET review_frontier=(SELECT next_review-1 FROM clients WHERE client_id=?) WHERE plan_id=?", binding.clientId, id)
                insertReady(c, live, producer, ready)
                val status = localStatus(c, binding.clientId, id)
                appendPlanEvent(c, binding, status, "plan_accepted")
                if (service.pendingReviews(c, binding.clientId) > config.maxPendingPerClient) fail("client_queue_full", "Whole declared vector exceeds pending quota")
                requireLive(grant)
                // From this point, a failed install/fsync or uncertain COMMIT can leave owned
                // final archives. Their existing quarantine is NOT a proven pre-install abort.
                installationStarted = true
                staged.values.forEach { it.install() }
                status
            }
        } finally {
            staged.values.forEach { it.close() }
            // A newly registered plan cannot reach COMMIT before install starts. Only after
            // every temporary archive closes successfully may its unaccepted capability retire.
            // Accepted/post-install/unknown outcomes and cleanup failures keep their quarantine;
            // safe retirement/accounting for those residues remains an unresolved capacity limit.
            if (!installationStarted) admissionLive?.let { live ->
                live.executions.values.forEach { execution -> executions.remove(execution.workKey, live to execution) }
                retained.remove(id, live)
            }
        }
    }

    private fun preflight(c: Connection, request: ProducedPlanAdmissionRequest) {
        val definition = request.definition
        val binding = definition.binding
        service.preflightAdmission(c, binding.clientId)
        if (c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND (patch_id=? OR idempotency_key=?)", binding.clientId,
                definition.request.patchId, definition.request.idempotencyKey) != 0L ||
            c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND (patch_id=? OR idempotency_key=?)", binding.clientId,
                definition.request.patchId, definition.request.idempotencyKey) != 0L) fail("produced_plan_request_conflict", "An existing obligation cannot be replaced")
        if (2 > config.maxRetainedReviews - c.count("SELECT COUNT(*) FROM reviews")) fail("ledger_full", "Both declared reviews must fit before acceptance")
        if (2 > config.maxPendingPerClient - service.pendingReviews(c, binding.clientId)) fail("client_queue_full", "Both declared obligations require pending capacity")
        if (2 > config.maxQueuedWork - service.queuedCapacityUsed(c)) fail("queue_full", "Reserve producer and unresolved consumer queue capacity together")
    }

    private fun charge(budget: Long, used: Long, addition: Long): Long {
        if (addition < 0 || addition > budget - used) fail("archive_full", "Complete declared archives exceed staging quota")
        return used + addition
    }

    private fun validateDeclaration(binding: ClientBinding, request: SubmitPlanRequest, plan: RequiredReviewPlan) {
        val graph = plan.jvmProduced ?: fail("produced_plan_declaration_missing", "Typed graph declaration is required")
        if (plan.binding != binding || plan.request != request || plan.requiredRecipientClientIds != listOf(binding.clientId) ||
            plan.slots.size != 2 || plan.slots != plan.slots.sortedBy { it.slotId } ||
            plan.slots.map { it.slotId }.toSet() != setOf(graph.producer.slotId, graph.consumer.slotId) ||
            graph.profile != PRODUCED_JVM_PLAN_PROFILE || !graph.friend || graph.producer.compilationId == graph.consumer.compilationId ||
            graph.originalDeadlineMillis !in 1..3_600_000 || graph.classpathIndex !in 0..15) {
            fail("produced_plan_scope_unsupported", "Only one complete same-client two-slot main/test friend graph is supported")
        }
        listOf(graph.graphDigest, graph.importRequestDigest, graph.modelDigest, graph.toolchainDigest, graph.referenceDigest,
            graph.catalogDigest, graph.requiredRulesDigest, graph.intentDigest, graph.producer.rawContextDigest,
            graph.consumer.rawContextDigest, graph.producer.argumentLayoutDigest, graph.consumer.argumentLayoutDigest,
            plan.sourceBindingDigest, plan.generatedInputsDigest, plan.modelCertificateId, plan.impactCertificateId).forEach {
            if (!Digests.valid(it)) fail("produced_plan_identity_invalid", "Complete declared graph identity is required")
        }
        identifier(graph.originalDeadlineIdentity, "original deadline")
        val snapshot = plan.slots.first().input.snapshot
        val source = plan.slots.first().input.materializedRoot
        if (plan.sourceBindingDigest != Digests.sha256(canonical(snapshot)) || snapshot.files != snapshot.files.sortedBy { it.path } ||
            snapshot.scopeExclusions.isNotEmpty() || snapshot.unknownInputs.isNotEmpty()) fail("produced_plan_source_incomplete", "Whole canonical source scope is required")
        plan.slots.forEach { slot ->
            val input = slot.input
            service.validateInput(binding, SubmitRequest("produced-validation", "produced-validation", input))
            val declared = if (slot.slotId == graph.producer.slotId) graph.producer else graph.consumer
            val rawContext = input.context.copy(policies = input.context.policies - setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan"))
            val expectedName = if (slot.slotId == graph.producer.slotId) "main" else "test"
            if (slot.state != "declared" || slot.identity != null || slot.reasons.isEmpty() ||
                slot.reasons.any { it !in setOf("verified_compilation_context_unavailable", "jvm_produced_output_deferred", "friend_artifact_rewrite_unavailable", "compiler_arguments_require_verified_rewrite") } ||
                input.snapshot != snapshot || input.materializedRoot != source || input.execution != null || input.outputRoot != null ||
                input.requiredRuleIds.size != 1663 || input.requiredRuleIds != input.requiredRuleIds.distinct().sorted() ||
                input.requiredRuleIds != plan.slots.first().input.requiredRuleIds ||
                Digests.sha256(canonical(input.requiredRuleIds)) != graph.requiredRulesDigest ||
                input.context.policies["ktchecker.catalog-inventory"] != graph.catalogDigest ||
                input.context.policies["ktchecker.activation-plan"].isNullOrBlank() ||
                input.context.fullProject || input.context.scope.target != "" || input.context.scope.module != graph.moduleId ||
                input.context.scope.compilation != expectedName || input.context.modelDigest != graph.modelDigest ||
                input.context.toolchainDigest != graph.toolchainDigest || input.context.compilerVersion != "2.4.21" ||
                input.context.classpath.size > 16 || input.context.classpath.any { !it.path.endsWith(".jar") } ||
                input.context.compilationFiles.isNullOrEmpty() || input.context.compilationFiles.orEmpty().any { !it.endsWith(".kt") } ||
                Digests.sha256(canonical(rawContext)) != declared.rawContextDigest || contextIdentity(input) == null) {
                fail("produced_plan_declaration_mismatch", "Declared raw model/source/config/1663 obligations cannot be narrowed or relabeled")
            }
            identifier(slot.slotId, "produced slot")
            slot.reasons.forEach { identifier(it, "declared reason") }
        }
        val consumer = plan.slots.single { it.slotId == graph.consumer.slotId }
        if (graph.classpathIndex > consumer.input.context.classpath.size) fail("produced_plan_ordinal_invalid", "Deferred ordinal is outside the declared known classpath")
        if (canonical(plan).toByteArray().size > config.maxInputBytes) fail("input_limit", "Whole produced declaration exceeds metadata quota")
    }

    private fun certify(live: Live, proposed: PreparedProducedExecution, producer: ResolvedPlanExecution?,
        publication: ProducedWorkPublication?): ResolvedPlanExecution {
        val request = live.request
        val slotId = if (producer == null) request.declaration.producer.slotId else request.declaration.consumer.slotId
        if (proposed.slotId != slotId) fail("produced_plan_resolution_mismatch", "Only the exact next declared slot can resolve")
        val slot = request.definition.slots.single { it.slotId == slotId }
        val input = ContractJson.decodeFromString<AnalysisInput>(canonical(proposed.input))
        validateResolvedInput(request, slot, input, proposed.producedManifest)
        if (!Digests.valid(proposed.bindingDigest)) fail("produced_plan_binding_invalid", "Actual compiler binding identity is required")
        if ((producer == null) != (proposed.producedManifest == null) || (producer == null) != (publication == null)) {
            fail("produced_plan_output_identity_invalid", "Consumer requires exact producer publication and complete output evidence")
        }
        proposed.producedManifest?.let { manifest ->
            val p = requireNotNull(producer); val pub = requireNotNull(publication)
            if (pub.workKey != p.workKey || pub.operationalFaults.isNotEmpty() ||
                manifest.producerBindingDigest != p.certificate.compilerBindingDigest) fail("produced_plan_output_identity_invalid", "Produced bytes belong to another or failed producer")
        }
        val certificate = PlanExecutionCertificate(request.planId, request.planDigest, slotId, live.reviewIds.getValue(slotId),
            Digests.sha256(canonical(slot)), slot.input.analysisKey(), request.declaration.graphDigest,
            live.preparation.archive(slotId).identity.digest(), input.analysisKey(), requireNotNull(contextIdentity(input)),
            proposed.bindingDigest, request.providerKey, request.originInstance, request.declaration.originalDeadlineIdentity,
            producer?.reviewId, producer?.workKey, publication?.digest(), proposed.producedManifest)
        val result = ResolvedPlanExecution(input, certificate)
        if (canonical(result).toByteArray().size > config.maxInputBytes) fail("input_limit", "Complete D-to-P relation exceeds metadata quota")
        return result
    }

    private fun validateResolvedInput(request: ProducedPlanAdmissionRequest, slot: RequiredPlanSlot, input: AnalysisInput,
        manifest: ProducedByteManifest?) {
        val graph = request.declaration
        val original = slot.input
        val addedPolicies = input.context.policies.keys - original.context.policies.keys
        val known = input.context.classpath.filterIndexed { index, _ -> manifest == null || index != graph.classpathIndex }
        if (input.copy(context = input.context.copy(classpath = original.context.classpath,
                dependencyDigest = original.context.dependencyDigest, policies = original.context.policies)) != original ||
            known != original.context.classpath || !Digests.valid(input.context.dependencyDigest.orEmpty()) ||
            !original.context.policies.all { input.context.policies[it.key] == it.value } ||
            addedPolicies != setOf("ktchecker.execution-plan", "ktchecker.jvm-produced-preview", "ktchecker.jvm-produced-resolution") ||
            input.context.policies["ktchecker.execution-plan"] != Digests.sha256(canonical(emptyMap<String, TrustedExecution>())) ||
            input.context.policies["ktchecker.jvm-produced-preview"] != graph.graphDigest ||
            input.context.policies["ktchecker.jvm-produced-resolution"] != input.context.dependencyDigest) {
            fail("produced_plan_resolved_input_mutation", "Resolution may add only the certified output and explicit execution identity, never rewrite D")
        }
        if (manifest != null) {
            val digests = listOf(manifest.snapshotId, manifest.graphDigest, manifest.toolchainDigest,
                manifest.producerArgumentLayoutDigest, manifest.consumerArgumentLayoutDigest, manifest.producerBindingDigest,
                manifest.compilerRequestDigest, manifest.compilerResultDigest, manifest.acceptedProviderResultDigest,
                manifest.completeTreeDigest, manifest.completeTreeMembersDigest, manifest.completeJar.digest)
            if (digests.any { !Digests.valid(it) } || manifest.snapshotId != original.snapshot.snapshotId ||
                manifest.moduleId != graph.moduleId || manifest.producerCompilationId != graph.producer.compilationId ||
                manifest.consumerCompilationId != graph.consumer.compilationId || manifest.graphDigest != graph.graphDigest ||
                manifest.toolchainDigest != graph.toolchainDigest || manifest.producerArgumentLayoutDigest != graph.producer.argumentLayoutDigest ||
                manifest.consumerArgumentLayoutDigest != graph.consumer.argumentLayoutDigest || manifest.applicationSequence < 1 ||
                manifest.applicationGeneration.isBlank() || manifest.completeTreeMemberCount !in 1..4096 ||
                manifest.completeJarSize !in 1..67_108_864 || manifest.format != "complete-sorted-stored-jar-dos1980/1" ||
                input.context.classpath.getOrNull(graph.classpathIndex) != manifest.completeJar ||
                input.context.classpath.size != original.context.classpath.size + 1) {
                fail("produced_plan_manifest_invalid", "Exact complete accepted producer output manifest is required")
            }
        }
        service.validateInput(request.binding, SubmitRequest("produced-execution-validation", "produced-execution-validation", input))
    }

    private fun retainExecution(live: Live, execution: ResolvedPlanExecution) {
        if (live.executions.putIfAbsent(execution.slotId, execution) != null || executions.putIfAbsent(execution.workKey, live to execution) != null) {
            fail("produced_plan_execution_replayed", "A resolution/work identity is one-use and cannot be replaced")
        }
    }

    private fun insertReady(c: Connection, live: Live, execution: ResolvedPlanExecution, staged: InputArchive.Staged) {
        val binding = live.request.binding
        val key = execution.workKey
        c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,archive_bytes,created_at)
            VALUES(?,?,?,?,?,'queued',?,?)""", key, canonical(execution.input), canonical(staged.input), execution.certificate.providerKey,
            fairKey(binding), staged.bytes, System.currentTimeMillis())
        insertResolution(c, live.request.planId, Resolution(execution.slotId, execution.reviewId, "ready", execution))
        c.execute("""INSERT OR IGNORE INTO fair_clients(client_id,fair_key,last_served)
            VALUES(?,?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", binding.clientId, fairKey(binding))
    }

    private fun insertResolution(c: Connection, planId: String, value: Resolution) {
        val encoded = canonical(value)
        if (encoded.toByteArray().size > config.maxInputBytes) fail("input_limit", "Complete resolution record exceeds metadata quota")
        c.execute("""INSERT INTO plan_slot_resolutions(plan_id,slot_id,review_id,state,record_json,record_digest,
            certificate_id,resolved_input_json,work_key,reasons_json) VALUES(?,?,?,?,?,?,?,?,?,?)""",
            planId, value.slotId, value.reviewId, value.state, encoded, Digests.sha256(encoded), value.execution?.certificate?.digest(),
            value.execution?.input?.let { canonical(it) }, value.execution?.workKey, canonical(value.reasons))
    }

    /** State-only exact-origin check used under the writer, before an execution claim is issued. */
    fun mayClaim(c: Connection, key: String): Boolean {
        val row = c.one("""SELECT r.plan_id,a.origin_instance FROM plan_slot_resolutions r
            JOIN produced_plan_admissions a ON a.plan_id=r.plan_id WHERE r.work_key=?""", key) {
            it.getString(1) to it.getString(2)
        } ?: return true
        // Another broker cannot discard/replay this still-live origin's execution authority.
        if (row.second != service.instanceId) return false
        val pair = executions[key]
        if (pair == null || pair.first.request.planId != row.first || pair.first.request.originInstance != service.instanceId ||
            !pair.first.grant.validity.isCurrent() || pair.first.grant.clock.isExpired()) {
            failWorkWithoutExecution(c, key, "produced_live_origin_or_budget_unavailable")
            return false
        }
        val stored = load(c, row.first)
        resolution(c, stored, pair.second.slotId) ?: corrupt()
        if (stored.cancelled || c.count("SELECT cancelled FROM reviews WHERE review_id=?", pair.second.reviewId) != 0L) {
            failWorkWithoutExecution(c, key, "produced_execution_cancelled_before_claim")
            return false
        }
        return true
    }

    fun clock(key: String): ProducedPlanClock? = executions[key]?.first?.grant?.clock
    fun isCurrent(key: String): Boolean = executions[key]?.first?.grant?.let { it.validity.isCurrent() && !it.clock.isExpired() } == true
    fun isProduced(c: Connection, key: String): Boolean = c.count("SELECT COUNT(*) FROM plan_slot_resolutions WHERE work_key=?", key) != 0L

    fun attempt(key: String, original: AnalysisInput, effective: AnalysisInput, attempt: Long,
        verifyFrozen: () -> Unit): ProducedExecutionAttempt {
        val (live, execution) = executions[key] ?: fail("produced_plan_live_authority_unavailable", "Restart cannot reconstruct a produced execution grant")
        requireLive(live.grant)
        if (live.request.originInstance != service.instanceId || execution.input != original || key != execution.workKey) corrupt()
        live.grant.verifyForAdmission()
        live.preparation.archives.forEach { it.verify() }
        requireLive(live.grant)
        return ProducedExecutionAttempt(live.request, live.preparation, execution, original, effective, attempt, service.instanceId, verifyFrozen)
    }

    /** Called after exact teardown and frozen result detach, outside the writer; unknown errors deny. */
    fun operationalFaults(key: String, original: AnalysisInput, effective: AnalysisInput, result: ProviderResult): List<String> {
        val (live, execution) = executions[key] ?: return listOf("produced_live_publication_authority_unavailable")
        return try {
            requireLive(live.grant)
            if (original != execution.input) corrupt()
            val faults = live.grant.operationalFaults(ProducedPublicationCheck(live.request, execution, original, effective, result))
            if (faults.size > 128 || faults.any { it.isBlank() || it.length > 256 || it.any { c -> c in "\u0000\r\n" } }) {
                listOf("produced_publication_classifier_invalid")
            } else faults.distinct().sorted()
        } catch (_: Exception) { listOf("produced_publication_classifier_unavailable") }
          catch (_: AssertionError) { listOf("produced_publication_classifier_unavailable") }
    }

    fun publish(c: Connection, key: String, attempt: Long, original: AnalysisInput, effective: AnalysisInput,
        rawDigest: String, operationalFaults: List<String>) {
        val (live, execution) = executions[key] ?: corrupt()
        val faults = operationalFaults.toMutableList()
        if (live.grant.clock.isExpired()) faults += "produced_original_deadline_exceeded"
        if (!live.grant.validity.isCurrent()) faults += "produced_import_superseded_or_revoked"
        if (Thread.currentThread().isInterrupted) faults += "analysis_interrupted"
        if (cancelled(c, load(c, live.request.planId))) faults += "produced_dependency_cancelled"
        if (service.isStopped() || c.count("SELECT COUNT(*) FROM instances WHERE instance_id=? AND closed=0", service.instanceId) != 1L) faults += "produced_broker_closed"
        val publication = ProducedWorkPublication(key, attempt, service.instanceId, execution.certificate.digest(), original.analysisKey(),
            effective.analysisKey(), service.providerKey, rawDigest, faults.distinct().sorted())
        c.execute("INSERT INTO work_publications(work_key,attempt,origin_instance,record_json,certificate_id) VALUES(?,?,?,?,?)",
            key, attempt, service.instanceId, canonical(publication), publication.digest())
    }

    private data class Selected(val stored: Stored, val nonce: String, val reservedBytes: Long)
    private data class PendingPublication(
        val selected: Selected, val live: Live?, val execution: ResolvedPlanExecution?,
        val staged: InputArchive.Staged?, val unavailable: List<String>, val publication: ProducedWorkPublication?,
    )
    private val resolverBusy = AtomicBoolean(false)
    @Volatile private var pendingPublication: PendingPublication? = null

    /** One bounded/fair resolver lane. A SQL retry keeps the SAME prepared/staged result; it never
     * calls the owner, resolves output, allocates a review, or runs producer work again. */
    fun resolveNext(): Boolean {
        if (!resolverBusy.compareAndSet(false, true)) return false
        try {
            val pending = pendingPublication ?: run {
                val selected = selectResolution() ?: return false
                prepareResolution(selected).also { pendingPublication = it }
            }
            publishResolution(pending) // On SQLException, retain exact pending+staging for publication-only retry.
            pendingPublication = null
            pending.staged?.close()
            return true
        } finally { resolverBusy.set(false) }
    }

    private fun selectResolution(): Selected? = ledger.transaction { c ->
        if (service.isStopped() || c.count("SELECT COUNT(*) FROM instances WHERE instance_id=? AND closed=0", service.instanceId) != 1L) return@transaction null
        val id = c.one("""SELECT a.plan_id FROM produced_plan_admissions a JOIN parent_plans p ON p.plan_id=a.plan_id
            JOIN fair_queues f ON f.fair_key=a.fair_key JOIN plan_slot_resolutions x ON x.plan_id=a.plan_id AND x.slot_id=a.producer_slot_id
            JOIN work w ON w.work_key=x.work_key JOIN plan_slots cs ON cs.plan_id=a.plan_id AND cs.slot_id=a.consumer_slot_id
            JOIN reviews cr ON cr.review_id=cs.review_id
            WHERE a.origin_instance=? AND (w.state='complete' OR p.cancelled<>0 OR cr.cancelled<>0)
            AND NOT EXISTS(SELECT 1 FROM plan_slot_resolutions done WHERE done.plan_id=a.plan_id AND done.slot_id=a.consumer_slot_id)
            AND NOT EXISTS(SELECT 1 FROM plan_slot_resolution_claims claim WHERE claim.plan_id=a.plan_id AND claim.slot_id=a.consumer_slot_id)
            ORDER BY f.produced_last_served,p.rowid LIMIT 1""", service.instanceId) { it.getString(1) } ?: return@transaction null
        val stored = load(c, id)
        val nonce = UUID.randomUUID().toString()
        val declaredBytes = stored.archives.single { it.slotId == stored.request.declaration.consumer.slotId }.byteCount
        // Explicit first-profile capacity gap: full output JAR + copied K are conservatively
        // charged BEFORE any output IO, including failed/unknown callbacks and retained staging.
        val bound = if (declaredBytes in 0..Long.MAX_VALUE - 134_217_728L) declaredBytes + 134_217_728L else Long.MAX_VALUE
        val available = config.maxArchiveBytes - service.archiveBytesUsed(c)
        val reserved = if (!cancelled(c, stored) && bound <= available) bound else 0L
        c.execute("""INSERT INTO plan_slot_resolution_claims(plan_id,slot_id,origin_instance,claim_nonce,reserved_archive_bytes)
            VALUES(?,?,?,?,?)""", id, stored.request.declaration.consumer.slotId, service.instanceId, nonce, reserved)
        c.execute("UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='dispatch_counter'")
        c.execute("UPDATE fair_queues SET produced_last_served=(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter') WHERE fair_key=?", fairKey(stored.request.binding))
        Selected(stored, nonce, reserved)
    }

    private fun prepareResolution(selected: Selected): PendingPublication {
        val stored = selected.stored
        val id = stored.request.planId
        val consumerId = stored.request.declaration.consumer.slotId
        val live = retained[id]
        var staged: InputArchive.Staged? = null
        var execution: ResolvedPlanExecution? = null
        var unavailable: List<String> = emptyList()
        var publication: ProducedWorkPublication? = null
        try {
            if (selected.reservedBytes == 0L) fail("produced_consumer_archive_capacity_unavailable", "The conservative retained-output bound does not fit; all obligations remain, no consumer callback runs")
            if (live == null) fail("produced_live_origin_unavailable", "Accepted authority is non-restorable")
            requireLive(live.grant)
            val producer = ledger.read { c -> resolution(c, load(c, id), stored.request.declaration.producer.slotId)?.execution } ?: corrupt()
            val evidence = ledger.read { c ->
                if (cancelled(c, stored)) fail("produced_dependency_cancelled", "Cancelled declarations cannot promote a consumer")
                val p = publication(c, producer)
                    ?: fail("produced_operational_publication_missing", "Producer lacks a durable operational publication")
                if (p.operationalFaults.isNotEmpty()) fail("produced_operational_publication_failed", "Producer publication failed; no consumer is invoked")
                val raw = c.one("SELECT result_json FROM work WHERE work_key=? AND state='complete'", producer.workKey) {
                    ContractJson.decodeFromString<ProviderResult>(it.getString(1))
                } ?: corrupt()
                p to raw
            }
            publication = evidence.first
            live.grant.verifyForAdmission(); live.preparation.archives.forEach { it.verify() }
            requireLive(live.grant)
            val directory = service.root.resolve("produced-output").resolve(id).resolve(selected.nonce)
            privateDirectory(directory.parent) // NEW owned directory only; reservation already durable.
            val request = ProducedSlotResolutionRequest(live.preparation, producer, evidence.first, evidence.second, directory)
            when (val resolved = live.grant.resolveConsumer(request)) {
                is ProducedSlotResolution.Unavailable -> unavailable = checkedReasons(resolved.reasons)
                is ProducedSlotResolution.Ready -> {
                    requireLive(live.grant); resolved.execution.verifyForAdmission()
                    execution = certify(live, resolved.execution, producer, evidence.first)
                    val manifest = requireNotNull(resolved.execution.producedManifest)
                    if (manifest.completeJar.path != directory.resolve("complete-output.jar").toString()) {
                        fail("produced_output_location_mismatch", "Only the exact reserved complete-output directory may enter P")
                    }
                    if (java.nio.file.Files.size(directory.resolve("complete-output.jar")) != manifest.completeJarSize) {
                        fail("produced_output_size_mismatch", "The complete JAR must match the declared bounded byte size")
                    }
                    staged = service.archive.stageResolvedFromDeclaration(requireNotNull(execution).workKey, requireNotNull(execution).input,
                        live.preparation.archive(consumerId), manifest.completeJar)
                    if (staged.bytes > selected.reservedBytes - manifest.completeJarSize) fail("produced_consumer_archive_reservation_exceeded", "Complete output and copied archive exceeded retained capacity")
                    live.preparation.archives.forEach { it.verify() }; resolved.execution.verifyForAdmission(); live.grant.verifyForAdmission()
                    requireLive(live.grant)
                    retainExecution(live, requireNotNull(execution))
                }
            }
        } catch (failure: Exception) {
            unavailable = listOf(if (failure is KernelException) failure.code else "produced_consumer_resolution_unavailable")
            execution = null
        } catch (_: AssertionError) { unavailable = listOf("produced_consumer_resolution_unavailable"); execution = null }
        return PendingPublication(selected, live, execution, staged, unavailable, publication)
    }

    private fun publishResolution(pending: PendingPublication) = ledger.transaction { c ->
        val selected = pending.selected
        val id = selected.stored.request.planId
        val consumerId = selected.stored.request.declaration.consumer.slotId
        val current = load(c, id)
        if (c.count("""SELECT COUNT(*) FROM plan_slot_resolution_claims WHERE plan_id=? AND slot_id=? AND origin_instance=?
                AND claim_nonce=? AND reserved_archive_bytes=?""", id, consumerId, service.instanceId, selected.nonce, selected.reservedBytes) != 1L) corrupt()
        if (resolution(c, current, consumerId) != null) return@transaction // An uncertain COMMIT or proven close already terminalized it.
        var ready = pending.execution
        var reasons = pending.unavailable
        val live = pending.live
        if (cancelled(c, current)) { ready = null; reasons = listOf("produced_dependency_cancelled") }
        if (live == null || live.grant.clock.isExpired() || !live.grant.validity.isCurrent()) {
            ready = null; reasons = listOf("produced_live_origin_or_budget_unavailable")
        }
        if (service.isStopped() || c.count("SELECT COUNT(*) FROM instances WHERE instance_id=? AND closed=0", service.instanceId) != 1L) {
            ready = null; reasons = listOf("produced_broker_closed")
        }
        if (Thread.currentThread().isInterrupted) { ready = null; reasons = listOf("produced_resolution_interrupted") }
        if (ready != null) {
            val producer = resolution(c, current, current.request.declaration.producer.slotId)?.execution ?: corrupt()
            if (publication(c, producer) != pending.publication || pending.publication?.operationalFaults?.isNotEmpty() != false) {
                ready = null; reasons = listOf("produced_publication_identity_changed")
            }
        }
        // Deliberate conservative overcharge: the retained reservation is never silently released
        // by a claimed/corrupt/failed Ready relation. Reclaim is a separate future design.
        if (ready != null && requireNotNull(pending.staged).bytes > config.maxArchiveBytes - service.archiveBytesUsed(c)) {
            ready = null; reasons = listOf("produced_consumer_archive_full")
        }
        if (ready != null) insertReady(c, requireNotNull(live), ready, requireNotNull(pending.staged))
        else insertResolution(c, id, Resolution(consumerId, current.reviews.getValue(consumerId), "unavailable", reasons = checkedReasons(reasons)))
        c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", current.request.binding.clientId)
        appendPlanEvent(c, current.request.binding, localStatus(c, current.request.binding.clientId, id), "plan_resolved")
        if (ready != null) requireNotNull(pending.staged).install()
    }

    /** Proven closed/dead origin only (scheduler owns OS liveness proof). Heartbeat expiry is NOT proof.
     * Never requeue a produced execution or rerun a once-claimed resolution. */
    internal fun closedOriginCandidateIds(c: Connection): List<String> = c.query(
        """SELECT a.plan_id FROM produced_plan_admissions a JOIN instances i ON i.instance_id=a.origin_instance
            WHERE i.closed=1 AND (EXISTS(SELECT 1 FROM plan_slots s WHERE s.plan_id=a.plan_id
                AND NOT EXISTS(SELECT 1 FROM plan_slot_resolutions r WHERE r.plan_id=s.plan_id AND r.slot_id=s.slot_id)) OR
                EXISTS(SELECT 1 FROM plan_slot_resolutions r JOIN work w ON w.work_key=r.work_key
                    WHERE r.plan_id=a.plan_id AND w.state IN ('queued','running'))) ORDER BY a.rowid LIMIT ?""",
        minOf(config.maxRetainedReviews, 4096L),
    ) { it.getString(1) }

    fun retireClosedOrigins(c: Connection) {
        val ids = closedOriginCandidateIds(c) // Terminal prefixes never consume this bounded batch.
        ids.forEach { id ->
            val stored = load(c, id)
            stored.request.definition.slots.forEach { slot ->
                val resolved = resolution(c, stored, slot.slotId)
                if (resolved == null) {
                    if (slot.slotId == stored.request.declaration.producer.slotId) corrupt()
                    insertResolution(c, id, Resolution(slot.slotId, stored.reviews.getValue(slot.slotId), "unavailable", reasons = listOf("produced_origin_closed_authority_not_restorable")))
                    c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", stored.request.binding.clientId)
                    appendPlanEvent(c, stored.request.binding, localStatus(c, stored.request.binding.clientId, id), "plan_unavailable")
                } else resolved.execution?.let { failWorkWithoutExecution(c, it.workKey, "produced_origin_closed_no_replay", allowRunning = true) }
            }
        }
    }

    fun failWorkWithoutExecution(c: Connection, key: String, reason: String, allowRunning: Boolean = false) {
        val failure = ProviderResult(service.providerId, "incomplete", reasons = listOf(reason), providerVersion = service.providerVersion)
        if (c.execute("""UPDATE work SET state='complete',result_json=?,validation_json=?,owner_instance=NULL,lease_until=NULL,deadline=NULL
            WHERE work_key=? AND (state='queued' OR (?=1 AND state='running'))""", canonical(failure), canonical(listOf(reason)), key,
            if (allowRunning) 1 else 0) == 0) return
        c.query("SELECT r.review_id,cl.binding_json FROM review_executions r JOIN clients cl ON cl.client_id=r.client_id WHERE r.work_key=?", key) {
            it.getString(1) to ContractJson.decodeFromString<ClientBinding>(it.getString(2))
        }.forEach { (review, binding) ->
            c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
            service.appendEvent(c, binding, service.readStatus(c, review, binding.clientId), "result")
        }
    }

    fun localStatus(c: Connection, clientId: String, id: String): ParentPlanStatus {
        val stored = load(c, id)
        if (stored.request.binding.clientId != clientId) fail("plan_not_found", "Parent is not available to this client")
        val slots = stored.request.definition.slots.map { slot ->
            val relation = resolution(c, stored, slot.slotId)
            val review = service.readStatus(c, stored.reviews.getValue(slot.slotId), clientId)
            val current = retained[id]
            val extra = if (relation == null && (current == null || !current.grant.validity.isCurrent()))
                listOf("produced_live_authority_not_restored_or_revoked") else emptyList()
            PlanSlotStatus(slot.slotId, review.reviewId, slot.input.context.scope, slot.input.requiredRuleIds,
                if (review.cancelled) "cancelled" else if (relation?.state == "unavailable") "unavailable" else review.state,
                (slot.reasons + review.reasons + extra).distinct())
        }
        val cancelled = stored.cancelled || slots.any { it.state == "cancelled" }
        val state = if (cancelled) "cancelled" else if (slots.any { it.state in setOf("incomplete", "unavailable") }) "incomplete" else "pending"
        return ParentPlanStatus(id, clientId, stored.request.request.patchId, stored.request.definition.slots.first().input.snapshot.snapshotId,
            stored.request.planDigest, state, slots, listOf("produced_plan_preview_unqualified") +
                if (cancelled) listOf("parent_cancelled_obligations_retained") else emptyList())
    }

    fun requireReviewRelation(c: Connection, reviewId: String) {
        val review = c.one("SELECT kind,work_key,input_json FROM reviews WHERE review_id=?", reviewId) {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        } ?: return
        val inbound = c.query("SELECT plan_id,slot_id FROM plan_slot_resolutions WHERE review_id=?", reviewId) {
            it.getString(1) to it.getString(2)
        }
        if (review.first != "produced") {
            val workInput = review.second?.let { key ->
                c.one("SELECT input_json FROM work WHERE work_key=?", key) { it.getString(1) }
            }
            if (review.first != "ordinary" || workInput == null || workInput != review.third || inbound.isNotEmpty() ||
                c.count("""SELECT COUNT(*) FROM plan_slots s JOIN produced_plan_admissions a ON a.plan_id=s.plan_id
                    WHERE s.review_id=?""", reviewId) != 0L) {
                fail("ledger_corrupt", "Ordinary review lacks its exact direct work/input relation")
            }
            return
        }
        if (review.second != null) corrupt()
        val link = c.one("SELECT plan_id,slot_id FROM plan_slots WHERE review_id=?", reviewId) { it.getString(1) to it.getString(2) } ?: corrupt()
        // The guarded view intentionally hides foreign inbound grafts. Check those rows explicitly
        // before accepting a genuinely absent consumer relation as deferred.
        if (inbound.isNotEmpty() && inbound != listOf(link)) corrupt()
        val stored = load(c, link.first)
        if (stored.reviews[link.second] != reviewId) corrupt()
        if (resolution(c, stored, link.second) == null && link.second == stored.request.declaration.producer.slotId) corrupt()
    }

    fun executionCertificateValid(c: Connection, reviewId: String, executionInput: AnalysisInput): Boolean {
        return try {
        requireReviewRelation(c, reviewId)
        val link = c.one("SELECT plan_id,slot_id FROM plan_slots WHERE review_id=?", reviewId) { it.getString(1) to it.getString(2) } ?: return false
        val saved = resolution(c, load(c, link.first), link.second)?.execution ?: return false
        saved.input == executionInput
        } catch (_: Exception) { false }
    }

    private fun load(c: Connection, id: String): Stored {
        val row = c.one("""SELECT p.*,a.origin_instance,a.authority_identity,a.provider_key,a.archives_json,a.archive_bytes,
            a.admission_digest,a.producer_slot_id,a.consumer_slot_id,a.fair_key FROM parent_plans p
            JOIN produced_plan_admissions a ON a.plan_id=p.plan_id WHERE p.plan_id=?""", id) { r ->
            val encoded = r.getString("definition_json")
            if (encoded.toByteArray().size > config.maxInputBytes || Digests.sha256(encoded) != r.getString("plan_digest")) corrupt()
            val plan = ContractJson.decodeFromString<RequiredReviewPlan>(encoded)
            if (canonical(plan) != encoded || r.getString("routing_id") != null || r.getString("client_id") != plan.binding.clientId ||
                r.getString("request_hash") != Digests.sha256(canonical(plan.request)) || r.getString("patch_id") != plan.request.patchId ||
                r.getString("idempotency_key") != plan.request.idempotencyKey) corrupt()
            validateDeclaration(plan.binding, plan.request, plan)
            val request = ProducedPlanAdmissionRequest(plan, id, r.getString("plan_digest"), r.getString("provider_key"), r.getString("origin_instance"))
            val encodedArchives = r.getString("archives_json")
            if (encodedArchives.toByteArray().size > config.maxInputBytes) corrupt()
            val archives = ContractJson.decodeFromString<List<DeclaredArchiveIdentity>>(encodedArchives)
            if (canonical(archives) != encodedArchives || archives.size != 2 || archives.map { it.slotId } != plan.slots.map { it.slotId } ||
                archives.any { it.byteCount < 0 || it.byteCount > config.maxSnapshotBytes || it.byteCount > Long.MAX_VALUE / 2 } ||
                archives.sumOf { it.byteCount } != r.getLong("archive_bytes") ||
                admissionDigest(request, r.getString("authority_identity"), archives) != r.getString("admission_digest") ||
                r.getString("producer_slot_id") != request.declaration.producer.slotId || r.getString("consumer_slot_id") != request.declaration.consumer.slotId ||
                r.getString("fair_key") != fairKey(plan.binding)) corrupt()
            val reviews = c.query("SELECT slot_id,review_id FROM plan_slots WHERE plan_id=?", id) { it.getString(1) to it.getString(2) }.toMap()
            if (reviews.keys != plan.slots.map { it.slotId }.toSet()) corrupt()
            val frontier = r.getLong("review_frontier")
            plan.slots.forEachIndexed { index, slot ->
                val saved = c.one("SELECT * FROM reviews WHERE review_id=?", reviews.getValue(slot.slotId)) { it ->
                    val child = childRequest(id, slot)
                    it.getString("kind") == "produced" && it.getString("work_key") == null && it.getString("input_json") == canonical(slot.input) &&
                        it.getString("client_id") == plan.binding.clientId && it.getString("request_json") == canonical(child) &&
                        it.getString("request_hash") == Digests.sha256(canonical(child)) && it.getString("patch_id") == child.patchId &&
                        it.getString("idempotency_key") == child.idempotencyKey && it.getLong("sequence") == frontier - 1 + index
                } ?: false
                if (!saved) corrupt()
                val archive = archives[index]
                val expectedKey = Digests.sha256(canonical(listOf(PRODUCED_JVM_PLAN_PROFILE, "declared-archive", id, slot.slotId, slot.input.analysisKey())))
                if (archive.archiveKey != expectedKey || archive.declarationInputKey != slot.input.analysisKey()) corrupt()
                requireArchivedRelation(slot.input, archive.frozenInput, archive.archiveKey)
            }
            Stored(request, r.getString("authority_identity"), archives, reviews, r.getInt("cancelled") != 0, frontier)
        } ?: corrupt()
        if (resolution(c, row, row.request.declaration.producer.slotId)?.state != "ready") corrupt()
        return row
    }

    private fun resolution(c: Connection, stored: Stored, slotId: String): Resolution? = c.one(
        "SELECT * FROM plan_slot_resolutions WHERE plan_id=? AND slot_id=?", stored.request.planId, slotId,
    ) { row ->
        val encoded = row.getString("record_json")
        if (encoded.toByteArray().size > config.maxInputBytes || Digests.sha256(encoded) != row.getString("record_digest")) corrupt()
        val value = ContractJson.decodeFromString<Resolution>(encoded)
        if (canonical(value) != encoded || value.slotId != slotId || value.reviewId != stored.reviews[slotId] ||
            row.getString("review_id") != value.reviewId || row.getString("state") != value.state || row.getString("reasons_json") != canonical(value.reasons)) corrupt()
        val execution = value.execution
        if (value.state == "unavailable") {
            if (slotId == stored.request.declaration.producer.slotId || execution != null || value.reasons.isEmpty() ||
                value.reasons != checkedReasons(value.reasons) || row.getString("work_key") != null ||
                row.getString("certificate_id") != null || row.getString("resolved_input_json") != null) corrupt()
        } else {
            if (value.state != "ready" || execution == null || value.reasons.isNotEmpty()) corrupt()
            val cert = execution.certificate
            val slot = stored.request.definition.slots.single { it.slotId == slotId }
            validateResolvedInput(stored.request, slot, execution.input, cert.producedManifest)
            if (cert.profile != PRODUCED_JVM_PLAN_PROFILE || cert.planId != stored.request.planId || cert.planDigest != stored.request.planDigest ||
                cert.slotId != slotId || cert.reviewId != value.reviewId || cert.declarationDigest != Digests.sha256(canonical(slot)) ||
                cert.declarationInputKey != slot.input.analysisKey() || cert.graphDigest != stored.request.declaration.graphDigest ||
                cert.declaredArchiveDigest != stored.archives.single { it.slotId == slotId }.digest() || cert.resolvedInputKey != execution.input.analysisKey() ||
                cert.context != contextIdentity(execution.input) || cert.providerKey != stored.request.providerKey || cert.originInstance != stored.request.originInstance ||
                cert.originalDeadlineIdentity != stored.request.declaration.originalDeadlineIdentity || !Digests.valid(cert.compilerBindingDigest) ||
                row.getString("certificate_id") != cert.digest() || row.getString("work_key") != execution.workKey || row.getString("resolved_input_json") != canonical(execution.input)) corrupt()
            val producer = slotId == stored.request.declaration.producer.slotId
            if (producer) {
                if (cert.producedManifest != null || cert.producerReviewId != null || cert.producerWorkKey != null || cert.producerPublicationCertificate != null) corrupt()
            } else {
                val reserved = c.one("SELECT origin_instance,claim_nonce,reserved_archive_bytes FROM plan_slot_resolution_claims WHERE plan_id=? AND slot_id=?",
                    stored.request.planId, slotId) {
                    if (it.getString(1) != stored.request.originInstance || it.getLong(3) <= 0L) corrupt()
                    val expected = service.root.resolve("produced-output").resolve(stored.request.planId).resolve(it.getString(2)).resolve("complete-output.jar")
                    if (cert.producedManifest?.completeJar?.path != expected.toString()) corrupt()
                    it.getLong(3)
                } ?: corrupt()
                val declaredBytes = stored.archives.single { it.slotId == slotId }.byteCount
                if (declaredBytes > Long.MAX_VALUE - 134_217_728L || reserved != declaredBytes + 134_217_728L) corrupt()
                val prior = resolution(c, stored, stored.request.declaration.producer.slotId)?.execution ?: corrupt()
                val published = publication(c, prior) ?: corrupt()
                if (cert.producedManifest == null || cert.producerReviewId != prior.reviewId || cert.producerWorkKey != prior.workKey ||
                    cert.producerPublicationCertificate != published.digest() || published.operationalFaults.isNotEmpty() ||
                    cert.producedManifest.producerBindingDigest != prior.certificate.compilerBindingDigest) corrupt()
            }
            val work = c.one("SELECT input_json,frozen_json,provider_key FROM work WHERE work_key=?", execution.workKey) {
                if (it.getString(1) != canonical(execution.input) || it.getString(3) != cert.providerKey) corrupt()
                val frozen = ContractJson.decodeFromString<AnalysisInput>(it.getString(2))
                requireArchivedRelation(execution.input, frozen, execution.workKey)
                true
            } ?: false
            if (!work) corrupt()
        }
        value
    }

    private fun publication(c: Connection, execution: ResolvedPlanExecution): ProducedWorkPublication? = c.one(
        "SELECT p.*,w.input_json,w.frozen_json,w.result_json,w.attempt work_attempt,w.state,w.provider_key work_provider FROM work_publications p JOIN work w ON w.work_key=p.work_key WHERE p.work_key=?", execution.workKey,
    ) { row ->
        val encoded = row.getString("record_json")
        if (encoded.toByteArray().size > config.maxInputBytes) corrupt()
        val value = ContractJson.decodeFromString<ProducedWorkPublication>(encoded)
        if (value.profile != "ktchecker/produced-work-publication/1" || canonical(value) != encoded ||
            value.digest() != row.getString("certificate_id") || value.workKey != execution.workKey ||
            value.operationalFaults != value.operationalFaults.distinct().sorted() || value.operationalFaults.size > 256 ||
            value.operationalFaults.any { it.isBlank() || it.length > 256 || it.any { ch -> ch in "\u0000\r\n" } } ||
            value.attempt != row.getLong("attempt") || value.attempt != row.getLong("work_attempt") || value.attempt != 1L ||
            value.originInstance != execution.certificate.originInstance || row.getString("origin_instance") != value.originInstance ||
            value.executionCertificate != execution.certificate.digest() || value.originalInputKey != execution.input.analysisKey() ||
            value.providerKey != execution.certificate.providerKey || value.providerKey != row.getString("work_provider") || row.getString("state") != "complete" ||
            row.getString("input_json") != canonical(execution.input) || row.getString("result_json") == null ||
            value.rawResultDigest != Digests.sha256(row.getString("result_json"))) corrupt()
        val frozen = ContractJson.decodeFromString<AnalysisInput>(row.getString("frozen_json"))
        requireArchivedRelation(execution.input, frozen, execution.workKey)
        val output = service.root.resolve("outputs").resolve(execution.workKey.removePrefix("sha256:"))
            .resolve("attempt-${value.attempt}-${value.originInstance}")
        if (value.effectiveInputKey != frozen.copy(outputRoot = output.toString()).analysisKey()) corrupt()
        val work = c.one("SELECT input_key,effective_key,result_digest,provider_key,certificate_id FROM work_certificates WHERE work_key=?", execution.workKey) {
            val expected = Digests.sha256(canonical(listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))))
            it.getString(1) == value.originalInputKey && it.getString(2) == value.effectiveInputKey && it.getString(3) == value.rawResultDigest &&
                it.getString(4) == value.providerKey && it.getString(5) == expected
        } ?: false
        if (!work) corrupt()
        value
    }

    private fun requireArchivedRelation(original: AnalysisInput, frozen: AnalysisInput, key: String) {
        val root = service.root.resolve("inputs").resolve(key.removePrefix("sha256:"))
        if (frozen.materializedRoot != root.resolve("sources").toString() || frozen.outputRoot != null ||
            frozen.copy(materializedRoot = original.materializedRoot, context = frozen.context.copy(classpath = original.context.classpath)) != original ||
            frozen.context.classpath.size != original.context.classpath.size) corrupt()
        original.context.classpath.zip(frozen.context.classpath).forEachIndexed { index, (from, to) ->
            if (to != from.copy(path = root.resolve("classpath/artifact-$index.jar").toString())) corrupt()
        }
    }

    private fun cancelled(c: Connection, stored: Stored): Boolean =
        c.count("SELECT cancelled FROM parent_plans WHERE plan_id=?", stored.request.planId) != 0L ||
            c.count("SELECT COUNT(*) FROM reviews r JOIN plan_slots s ON s.review_id=r.review_id WHERE s.plan_id=? AND r.cancelled<>0", stored.request.planId) != 0L

    private fun appendPlanEvent(c: Connection, binding: ClientBinding, status: ParentPlanStatus, kind: String) {
        val anchor = service.readStatus(c, status.slots.last().reviewId, binding.clientId)
        val payload = anchor.copy(state = kind, result = null, reasons = emptyList(), parentPlan = status)
        if (canonical(payload).toByteArray().size > config.maxResultBytes) fail("input_limit", "Complete plan event exceeds payload quota")
        service.appendEvent(c, binding, payload, kind)
    }
    private fun requireLive(grant: AuthorizedProducedPlan) {
        if (service.isStopped() || grant.request.originInstance != service.instanceId || !grant.validity.isCurrent() || grant.clock.isExpired() || Thread.currentThread().isInterrupted) {
            fail("produced_live_origin_or_budget_unavailable", "Exact original live import/broker/clock is unavailable; no replay or renewal")
        }
    }
    private fun checkedReasons(reasons: List<String>): List<String> {
        if (reasons.isEmpty() || reasons.size > 128) return listOf("produced_consumer_resolution_unavailable")
        reasons.forEach { identifier(it, "produced unavailability") }
        return reasons.distinct().sorted()
    }
    private fun childRequest(id: String, slot: RequiredPlanSlot): SubmitRequest {
        val key = Digests.sha256(canonical(listOf(id, slot.slotId)))
        return SubmitRequest("plan-child:$key", "plan-child:$key", slot.input)
    }
    private fun fairKey(binding: ClientBinding): String = Digests.sha256(canonical(listOf(binding.ownerId, binding.workspaceId, binding.worktreeId)))
    private fun admissionDigest(request: ProducedPlanAdmissionRequest, authority: String, archives: List<DeclaredArchiveIdentity>): String =
        Digests.sha256(canonical(listOf(request.planId, request.planDigest, request.originInstance, request.providerKey, authority, canonical(archives))))
    private fun corrupt(): Nothing = fail("produced_plan_corrupt", "The immutable declared/executed relation is missing or inconsistent")
}
