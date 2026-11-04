package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.Serializable

/** Atomic local/routed fanout. Admission evidence is never a joint finalization authority. */
internal class ParentPlans(
    private val service: KernelService,
    private val authority: ReviewPlanAuthority,
    private val routingAuthority: ReviewPlanRoutingAuthority,
) {
    private val ledger get() = service.ledger
    private val config get() = service.config
    /** Internal fault-injection seam for transaction/crash tests; no public/RPC controller setter. */
    internal var afterSlotInsert: (Int) -> Unit = { }

    internal data class Stored(val id: String, val definition: RequiredReviewPlan, val frontier: Long)
    private data class Child(val slot: RequiredPlanSlot, val request: SubmitRequest, val key: String)
    private data class Admission(
        val id: String,
        val definition: RequiredReviewPlan,
        val encoded: String,
        val digest: String,
        val children: List<Child>,
        val recipient: AuthorizedPlanRecipient? = null,
    ) {
        val binding get() = definition.binding
        val request get() = definition.request
        val requestHash get() = Digests.sha256(canonical(request))
    }
    // Private ledger records. In particular, neither these records nor their whole-route digest
    // are ever placed in a recipient's status, event, error or caller-facing request/response.
    @Serializable private data class DurableRouteMember(
        val planId: String, val plan: RequiredReviewPlan, val preparation: PlanRecipientPreparation,
    )
    @Serializable private data class DurableRoute(
        val origin: RequiredReviewPlan, val providerKey: String, val members: List<DurableRouteMember>,
    )
    private data class Batch(val admissions: List<Admission>, val route: DurableRoute? = null) {
        val routingId: String? = route?.let { UUID.randomUUID().toString() }
        val encodedRoute: String? = route?.let { canonical(it) }
    }

    fun submit(credential: ClientCredential, request: SubmitPlanRequest): ParentPlanStatus {
        service.ensureOpen()
        validateRequest(request)
        val hash = Digests.sha256(canonical(request))
        val binding = ledger.read { service.authenticate(it, credential) }
        ledger.read { c -> prior(c, binding.clientId, request.idempotencyKey, hash) }?.let { return it }
        if (!service.ingestion.tryAcquire()) fail("ingestion_busy", "Bounded input ingestion is busy; retry before acceptance")
        val staged = linkedMapOf<String, InputArchive.Staged>()
        try {
            val batch = resolveBatch(binding, request)
            val children = batch.admissions.flatMap { it.children }
            ledger.read { c -> authenticateRecipients(c, batch); preflight(c, batch.admissions) }
            verifyPreparations(batch)
            val stageBudget = ledger.read { c -> config.maxArchiveBytes - c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work") }
            var stagedBytes = 0L
            children.distinctBy { it.key }.forEach { child ->
                val exists = ledger.read { c -> c.count("SELECT COUNT(*) FROM work WHERE work_key=?", child.key) != 0L }
                if (!exists) {
                    val ready = service.archive.stage(child.key, child.slot.input)
                    if (ready.bytes > stageBudget - stagedBytes) {
                        ready.close()
                        fail("archive_full", "Whole parent plan exceeds bounded archive staging")
                    }
                    staged[child.key] = ready
                    stagedBytes += ready.bytes
                }
            }
            // Source/model and captured recipient approvals are reverified after staging, outside
            // all DB/config/source locks. This is admission-time evidence, NOT a current joint
            // fence spanning finalization; routed lineages retain a permanent gate denial below.
            verifyPreparations(batch)
            return ledger.transaction { c ->
                service.ensureOpen()
                if (service.authenticate(c, credential) != binding) fail("unauthorized", "Client enrollment changed before admission")
                prior(c, binding.clientId, request.idempotencyKey, hash)?.let { return@transaction it }
                authenticateRecipients(c, batch)
                batch.admissions.forEach { admission -> rejectRequestCollision(c, admission, binding.clientId) }
                preflight(c, batch.admissions)
                val missing = children.distinctBy { it.key }.filter { c.count("SELECT COUNT(*) FROM work WHERE work_key=?", it.key) == 0L }
                var bytes = 0L
                val available = config.maxArchiveBytes - c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work")
                missing.forEach { child ->
                    val ready = staged[child.key] ?: fail("archive_missing", "Prepared plan input is unavailable")
                    if (ready.bytes > available - bytes) fail("archive_full", "Whole parent plan exceeds durable archive quota")
                    bytes += ready.bytes
                }
                // Every recipient's complete local plan, child obligations, route identity and
                // outbox publish in this ONE transaction. Never attach an unrelated old parent.
                var inserted = 0
                batch.admissions.forEach { admission ->
                    c.execute("""INSERT INTO parent_plans(plan_id,client_id,patch_id,idempotency_key,request_hash,definition_json,plan_digest,review_frontier,routing_id)
                        VALUES(?,?,?,?,?,?,?,0,?)""", admission.id, admission.binding.clientId, admission.request.patchId,
                        admission.request.idempotencyKey, admission.requestHash, admission.encoded, admission.digest, batch.routingId)
                    admission.children.forEach { child ->
                        val review = acceptChild(c, admission.binding, child, staged[child.key])
                        c.execute("INSERT INTO plan_slots(plan_id,slot_id,review_id) VALUES(?,?,?)", admission.id, child.slot.slotId, review.reviewId)
                        afterSlotInsert(++inserted)
                    }
                    c.execute("UPDATE parent_plans SET review_frontier=(SELECT next_review-1 FROM clients WHERE client_id=?) WHERE plan_id=?",
                        admission.binding.clientId, admission.id)
                }
                val origin = batch.admissions.single { it.binding.clientId == binding.clientId }
                if (batch.route != null) {
                    c.execute("""INSERT INTO routing_batches(routing_id,origin_client_id,origin_plan_id,definition_json,routing_digest)
                        VALUES(?,?,?,?,?)""", batch.routingId, binding.clientId, origin.id, batch.encodedRoute, Digests.sha256(batch.encodedRoute!!))
                    batch.admissions.forEach { admission ->
                        c.execute("INSERT INTO routing_members(routing_id,client_id,plan_id) VALUES(?,?,?)",
                            batch.routingId, admission.binding.clientId, admission.id)
                        // Never reset by completion, cancellation, delivery, or later local/scalar
                        // admission. No existing local SnapshotGate can authorize this lineage.
                        c.execute("UPDATE clients SET routed_obligations=1 WHERE client_id=?", admission.binding.clientId)
                    }
                }
                var originStatus: ParentPlanStatus? = null
                // Construct only local payloads after all members exist; validate the private
                // whole batch once (including every outbox) before committing, not once per member.
                batch.admissions.forEach { admission ->
                    val status = localStatus(c, parent(c, admission.binding.clientId, admission.id))
                    val anchor = service.readStatus(c, status.slots.last().reviewId, admission.binding.clientId)
                    val payload = anchor.copy(state = "plan_accepted", result = null, reasons = emptyList(), parentPlan = status)
                    if (canonical(payload).toByteArray().size > config.maxResultBytes) fail("input_limit", "Complete parent acceptance event exceeds its payload bound")
                    service.appendEvent(c, admission.binding, payload, "plan_accepted")
                    if (admission.id == origin.id) originStatus = status
                }
                validateRouting(c, parent(c, binding.clientId, origin.id))
                originStatus!!
            }
        } finally {
            staged.values.forEach { it.close() }
            service.ingestion.release()
        }
    }

    private fun validateRequest(request: SubmitPlanRequest) {
        listOf(request.patchId, request.idempotencyKey, request.planRef).forEach { identifier(it, "parent plan request") }
    }

    private fun freeze(definition: RequiredReviewPlan): RequiredReviewPlan {
        val encoded = canonical(definition)
        if (encoded.toByteArray().size > config.maxInputBytes) fail("input_limit", "Parent plan exceeds the bounded metadata size")
        return ContractJson.decodeFromString(encoded)
    }

    private fun resolveBatch(binding: ClientBinding, request: SubmitPlanRequest): Batch {
        val declared = freeze(authority.resolve(binding, request)
            ?: fail("plan_authority_unavailable", "Only the trusted controller can resolve a complete registered review plan"))
        val definition = normalize(binding, request, declared, allowRouting = true)
        if (declared.requiredRecipientClientIds == listOf(binding.clientId)) return Batch(listOf(admission(definition)))
        if (definition != declared) fail("routing_plan_not_canonical", "Routed owner-prepared plans must already have canonical slot and input ordering")
        val grant = try { routingAuthority.authorize(binding, request, freeze(declared)) }
            catch (_: Exception) { fail("cross_client_routing_unavailable", "Trusted whole-route authorization is unavailable; no prefix was accepted") }
            catch (_: AssertionError) { fail("cross_client_routing_unavailable", "Trusted whole-route authorization is unavailable; no prefix was accepted") }
            ?: fail("cross_client_routing_unavailable", "An exact trusted whole-route owner grant is required; no prefix was accepted")
        if (freeze(grant.origin) != declared) fail("routing_origin_mismatch", "Route must retain the complete exact originating plan")
        val recipients = grant.recipients
        if (recipients.size !in 2..64 || recipients.map { it.plan.binding.clientId }.distinct().size != recipients.size ||
            recipients.map { it.plan.binding.clientId }.toSet() != declared.requiredRecipientClientIds.toSet()) {
            fail("routing_recipients_invalid", "Route requires exactly every distinct declared recipient, including the initiator")
        }
        if (recipients.sumOf { it.plan.slots.size.toLong() } > 512) fail("plan_slots_invalid", "The whole route exceeds its bounded required slot vector")
        val admissions = recipients.map { recipient ->
            val local = freeze(recipient.plan)
            if (local.binding.ownerId != binding.ownerId || local.binding.workspaceId != binding.workspaceId) {
                fail("routing_namespace_mismatch", "Routing across owners or workspaces is not supported")
            }
            val normalized = normalize(local.binding, local.request, local)
            if (normalized != local) fail("routing_plan_not_canonical", "Each recipient must prepare its exact canonical local plan")
            if (local.binding.clientId == binding.clientId && local != declared.copy(requiredRecipientClientIds = listOf(binding.clientId))) {
                fail("routing_origin_mismatch", "The initiating recipient must retain its entire required local vector")
            }
            val frozen = AuthorizedPlanRecipient(local, recipient.adapter.copy(), recipient.preparation.copy(), recipient.verifyForAdmission)
            admission(local, frozen).also { requirePreparation(it.digest, frozen.preparation) }
        }.sortedBy { it.binding.clientId }
        if (admissions.map { it.recipient!!.preparation.capturedPolicyDigest }.distinct().size != admissions.size ||
            admissions.map { it.recipient!!.preparation.executionApprovalDigest }.distinct().size != admissions.size) {
            fail("routing_preparation_invalid", "Captured policy and execution approval identities must independently bind each recipient")
        }
        return Batch(admissions, DurableRoute(declared, service.providerKey, admissions.map {
            DurableRouteMember(it.id, it.definition, it.recipient!!.preparation)
        })).also {
            if (it.encodedRoute!!.toByteArray().size > config.maxInputBytes) fail("input_limit", "The complete private route exceeds its bounded metadata size")
        }
    }

    private fun admission(definition: RequiredReviewPlan, recipient: AuthorizedPlanRecipient? = null): Admission {
        val encoded = canonical(definition)
        if (encoded.toByteArray().size > config.maxInputBytes) fail("input_limit", "Expanded plan exceeds the bounded metadata size")
        val digest = Digests.sha256(encoded)
        val id = UUID.randomUUID().toString()
        return Admission(id, definition, encoded, digest, children(id, definition, digest, service.providerKey), recipient)
    }

    private fun children(id: String, definition: RequiredReviewPlan, digest: String, providerKey: String): List<Child> = definition.slots.map { slot ->
        val identity = Digests.sha256(canonical(listOf(id, slot.slotId)))
        val request = SubmitRequest("plan-child:$identity", "plan-child:$identity", slot.input)
        val key = if (slot.state == "pending") Digests.sha256(canonical(listOf(providerKey, slot.input.analysisKey())))
            else Digests.sha256(canonical(listOf("administrative-unsupported-plan-slot", providerKey, digest, slot.slotId)))
        Child(slot, request, key)
    }

    private fun requirePreparation(digest: String, preparation: PlanRecipientPreparation) {
        if (preparation.planDigest != digest || !Digests.valid(preparation.capturedPolicyDigest) || !Digests.valid(preparation.executionApprovalDigest)) {
            fail("routing_preparation_invalid", "Each recipient needs an exact prepared plan, captured policy and execution approval identity")
        }
    }

    private fun authenticateRecipients(c: Connection, batch: Batch) {
        batch.admissions.forEach { admission -> admission.recipient?.let { recipient ->
            val authenticated = try { service.authenticate(c, recipient.adapter) }
                catch (_: Exception) { fail("routing_recipient_unauthorized", "A required recipient's actual enrollment is unavailable") }
            if (authenticated != admission.binding) fail("routing_recipient_unauthorized", "A required recipient's exact enrollment is unavailable")
        } }
    }

    private fun verifyPreparations(batch: Batch) {
        batch.admissions.forEach { admission -> admission.recipient?.let { recipient ->
            val verified = try { recipient.verifyForAdmission() }
                catch (_: Exception) { fail("routing_preparation_unavailable", "A required recipient's prepared model, policy or execution approval is unavailable") }
                catch (_: AssertionError) { fail("routing_preparation_unavailable", "A required recipient's prepared model, policy or execution approval is unavailable") }
            if (verified != recipient.preparation) fail("routing_preparation_unavailable", "A required recipient's prepared model, policy or execution approval changed")
        } }
    }

    private fun rejectRequestCollision(c: Connection, admission: Admission, originClient: String) {
        val client = admission.binding.clientId
        val request = admission.request
        if (c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND idempotency_key=?", client, request.idempotencyKey) != 0L ||
            c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND idempotency_key=?", client, request.idempotencyKey) != 0L) {
            fail(if (client == originClient) "idempotency_conflict" else "routing_request_conflict", "A required request tuple already has a separate accepted obligation")
        }
        if (c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND patch_id=?", client, request.patchId) != 0L ||
            c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND patch_id=?", client, request.patchId) != 0L) {
            fail(if (client == originClient) "patch_id_conflict" else "routing_request_conflict", "A required patch ID already has a separate accepted obligation")
        }
    }

    private fun normalize(binding: ClientBinding, request: SubmitPlanRequest, definition: RequiredReviewPlan, allowRouting: Boolean = false): RequiredReviewPlan {
        validateRequest(request)
        if (definition.binding != binding) fail("plan_binding_mismatch", "Plan belongs to another client, namespace or generation")
        if (definition.request != request) fail("plan_request_mismatch", "Registered plan is bound to another exact patch, idempotency key or reference")
        if (definition.requiredRecipientClientIds.size !in 1..64 || definition.requiredRecipientClientIds.distinct().size != definition.requiredRecipientClientIds.size ||
            binding.clientId !in definition.requiredRecipientClientIds) {
            fail("plan_recipients_invalid", "A complete nonempty controller-declared recipient set is required")
        }
        definition.requiredRecipientClientIds.forEach { identifier(it, "plan recipient") }
        if (!allowRouting && definition.requiredRecipientClientIds != listOf(binding.clientId)) {
            fail("routing_recipients_invalid", "A routed recipient must have its own complete same-client local plan")
        }
        if (definition.slots.size !in 1..512 || definition.slots.map { it.slotId }.distinct().size != definition.slots.size) {
            fail("plan_slots_invalid", "An explicit bounded distinct nonempty scope vector is required")
        }
        listOf(definition.sourceBindingDigest, definition.generatedInputsDigest, definition.modelCertificateId, definition.impactCertificateId)
            .forEach { if (!Digests.valid(it)) fail("plan_identity_invalid", "Verified model/source/impact identities are required") }
        val snapshot = definition.slots.first().input.snapshot
        val root = definition.slots.first().input.materializedRoot
        if (snapshot.files != snapshot.files.sortedBy { it.path }) fail("plan_snapshot_not_canonical", "Registered plan manifest must retain canonical captured file ordering")
        if (definition.sourceBindingDigest != Digests.sha256(canonical(snapshot))) fail("plan_source_binding_mismatch", "Plan must bind the complete sealed manifest, including roots and scope")
        val slots = definition.slots.map { slot ->
            identifier(slot.slotId, "plan slot")
            if (slot.input.snapshot != snapshot || slot.input.materializedRoot != root) fail("plan_snapshot_mismatch", "Every context must use the exact same sealed snapshot and namespace")
            if (slot.input.requiredRuleIds.isEmpty()) fail("plan_rule_inventory_empty", "Controller must enumerate every required rule; installed provider support cannot define the plan")
            val input = slot.input.copy(snapshot = slot.input.snapshot.copy(files = slot.input.snapshot.files.sortedBy { it.path }),
                requiredRuleIds = slot.input.requiredRuleIds.distinct().sorted(),
                changedPaths = slot.input.changedPaths.distinct().sorted())
            service.validateInput(binding, SubmitRequest("plan-validation", "plan-validation", input))
            input.requiredRuleIds.forEach { identifier(it, "plan rule") }
            if (slot.state !in setOf("pending", "unsupported") || (slot.state == "unsupported" && slot.reasons.isEmpty()) ||
                (slot.state == "pending" && slot.reasons.isNotEmpty())) fail("plan_slot_state_invalid", "Each slot must be executable or explicitly unsupported")
            slot.reasons.forEach { identifier(it, "unsupported scope reason") }
            if (slot.state == "pending") {
                if (!input.context.fullProject || input.snapshot.scopeExclusions.isNotEmpty() || input.snapshot.unknownInputs.isNotEmpty() ||
                    slot.identity == null || contextIdentity(input) != slot.identity) {
                    fail("plan_context_identity_mismatch", "Executable slot lacks exact complete model/compiler/membership/config identity")
                }
            }
            slot.copy(input = input)
        }.sortedBy { it.slotId } // One deterministic child/frontier order, never caller-selected scalar anchoring.
        val contexts = slots.filter { it.state == "pending" }.map { it.identity!!.context }
        if (contexts.distinct().size != contexts.size) fail("plan_context_duplicate", "Each actual compilation context has one complete rule slot")
        return definition.copy(slots = slots)
    }

    private fun preflight(c: Connection, admissions: List<Admission>) {
        admissions.forEach { service.preflightAdmission(c, it.binding.clientId) }
        val children = admissions.flatMap { it.children }
        if (children.size > config.maxRetainedReviews - c.count("SELECT COUNT(*) FROM reviews")) fail("ledger_full", "Whole parent plan exceeds retained obligations quota")
        val queued = children.distinctBy { it.key }.count { it.slot.state == "pending" && c.count("SELECT COUNT(*) FROM work WHERE work_key=?", it.key) == 0L }
        if (queued > config.maxQueuedWork - c.count("SELECT COUNT(*) FROM work WHERE state='queued'")) fail("queue_full", "Whole parent plan exceeds the durable work queue")
        admissions.forEach { admission ->
            val pending = c.count("""SELECT COUNT(*) FROM reviews r JOIN work w ON w.work_key=r.work_key
                WHERE r.client_id=? AND w.state IN ('queued','running')""", admission.binding.clientId)
            val added = admission.children.count { it.slot.state == "pending" && c.count("SELECT COUNT(*) FROM work WHERE work_key=? AND state='complete'", it.key) == 0L }
            if (added > config.maxPendingPerClient - pending) fail("client_queue_full", "Whole parent plan exceeds a required client's pending obligation limit")
        }
    }

    private fun acceptChild(c: Connection, binding: ClientBinding, child: Child, staged: InputArchive.Staged?): ReviewStatus {
        val input = child.slot.input
        if (c.count("SELECT COUNT(*) FROM work WHERE work_key=?", child.key) == 0L) {
            val ready = staged ?: fail("archive_missing", "Prepared parent input is unavailable")
            val fairKey = Digests.sha256(canonical(listOf(binding.ownerId, binding.workspaceId, binding.worktreeId)))
            ready.install()
            c.execute("""INSERT OR IGNORE INTO fair_queues(fair_key,last_served)
                VALUES(?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", fairKey)
            c.execute("""INSERT INTO work(work_key,input_json,frozen_json,provider_key,fair_key,state,archive_bytes,created_at)
                VALUES(?,?,?,?,?,?,?,?)""", child.key, canonical(input), canonical(ready.input), service.providerKey, fairKey,
                if (child.slot.state == "pending") "queued" else "complete", ready.bytes, System.currentTimeMillis())
            if (child.slot.state == "unsupported") {
                val reasons = (child.slot.reasons + "required_plan_scope_unsupported").distinct()
                // Administrative failure only. No provider call or work/coverage certificate is
                // fabricated for a missing capability, and it never shares an executable key.
                val failure = ProviderResult(service.providerId, "incomplete", reasons = reasons, providerVersion = service.providerVersion)
                c.execute("UPDATE work SET result_json=?,validation_json=? WHERE work_key=?", canonical(failure), canonical(reasons), child.key)
            }
        }
        val sequence = c.count("SELECT next_review FROM clients WHERE client_id=?", binding.clientId)
        val review = UUID.randomUUID().toString()
        val requestJson = canonical(child.request)
        c.execute("""INSERT INTO reviews(review_id,client_id,patch_id,idempotency_key,request_hash,request_json,input_json,work_key,sequence,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?)""", review, binding.clientId, child.request.patchId, child.request.idempotencyKey,
            Digests.sha256(requestJson), requestJson, canonical(input), child.key, sequence, System.currentTimeMillis())
        c.execute("""INSERT OR IGNORE INTO fair_clients(client_id,fair_key,last_served)
            SELECT ?,fair_key,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter') FROM work WHERE work_key=?""", binding.clientId, child.key)
        c.execute("UPDATE clients SET next_review=next_review+1,epoch=epoch+1 WHERE client_id=?", binding.clientId)
        val raw = c.one("SELECT result_json FROM work WHERE work_key=? AND state='complete'", child.key) {
            it.getString(1)?.let { json -> ContractJson.decodeFromString<ProviderResult>(json) }
        }
        if (raw != null) service.applyResultPolicy(c, binding, review, input, raw)
        val status = service.readStatus(c, review, binding.clientId)
        service.appendEvent(c, binding, status.copy(state = "accepted", result = null, reasons = emptyList()), "accepted")
        c.query("SELECT payload_json FROM work_progress WHERE work_key=? ORDER BY sequence", child.key) {
            ContractJson.decodeFromString<ProviderResult>(it.getString(1))
        }.forEach { partial ->
            val (classified, issues) = service.classifyResult(binding, input, partial)
            service.appendEvent(c, binding, status.copy(state = "running", result = classified, reasons = issues), "progress")
        }
        if (status.result != null) service.appendEvent(c, binding, status, "result")
        return status
    }

    fun status(credential: ClientCredential, id: String): ParentPlanStatus {
        service.ensureOpen()
        return ledger.read { c -> status(c, service.authenticate(c, credential).clientId, id) }
    }

    private data class ParentRow(
        val id: String, val client: String, val patch: String, val key: String, val requestHash: String,
        val encoded: String, val digest: String, val frontier: Long, val cancelled: Boolean, val routingId: String?,
    )

    private fun parent(c: Connection, client: String, id: String): ParentRow = c.one(
        "SELECT * FROM parent_plans WHERE plan_id=? AND client_id=?", id, client,
    ) { ParentRow(it.getString("plan_id"), it.getString("client_id"), it.getString("patch_id"), it.getString("idempotency_key"),
        it.getString("request_hash"), it.getString("definition_json"), it.getString("plan_digest"),
        it.getLong("review_frontier"), it.getInt("cancelled") != 0, it.getString("routing_id")) }
        ?: fail("plan_not_found", "Parent plan is not available to this client")

    internal fun status(c: Connection, client: String, id: String): ParentPlanStatus {
        val row = parent(c, client, id)
        validateRouting(c, row)
        return localStatus(c, row)
    }

    private fun localStatus(c: Connection, row: ParentRow): ParentPlanStatus {
        val definition = storedDefinition(row.encoded, row.digest)
        val children = c.query("SELECT slot_id,review_id FROM plan_slots WHERE plan_id=?", row.id) { it.getString(1) to it.getString(2) }.toMap()
        val slots = definition.slots.map { slot ->
            val child = children[slot.slotId]?.let { service.readStatus(c, it, row.client) }
            PlanSlotStatus(slot.slotId, child?.reviewId.orEmpty(), slot.input.context.scope, slot.input.requiredRuleIds,
                when {
                    child == null -> "incomplete"
                    child.cancelled -> "cancelled"
                    slot.state == "unsupported" -> "unsupported"
                    else -> child.state
                },
                (slot.reasons + child?.reasons.orEmpty() + if (child == null) listOf("required_plan_slot_missing") else emptyList()).distinct())
        }
        val cancelled = row.cancelled || slots.any { it.state == "cancelled" }
        val state = when {
            cancelled -> "cancelled"
            slots.any { it.state in setOf("incomplete", "unsupported") } -> "incomplete"
            slots.any { it.state in setOf("queued", "running") } -> "pending"
            slots.all { it.state == "complete" } -> "complete"
            else -> "incomplete"
        }
        return ParentPlanStatus(row.id, row.client, row.patch, definition.slots.first().input.snapshot.snapshotId, row.digest, state, slots,
            if (cancelled) listOf("parent_cancelled_obligations_retained") else emptyList())
    }

    private data class RoutedChild(
        val slot: String, val review: String, val client: String, val patch: String, val key: String,
        val requestHash: String, val requestJson: String, val inputJson: String, val work: String,
        val sequence: Long, val provider: String, val workInput: String,
    )

    private fun routingCorrupt(): Nothing = fail("routing_plan_corrupt", "The complete durable route is unavailable or inconsistent; no local prefix is authoritative")

    /** No source/authority callbacks and no recursive status calls. Every member is checked. */
    private fun validateRouting(c: Connection, requested: ParentRow) {
        try {
            val membership = c.one("SELECT routing_id FROM routing_members WHERE plan_id=?", requested.id) { it.getString(1) }
            val originLink = c.one("SELECT routing_id FROM routing_batches WHERE origin_plan_id=?", requested.id) { it.getString(1) }
            if (requested.routingId == null && membership == null && originLink == null) return
            val id = requested.routingId ?: routingCorrupt()
            if (membership != id || (originLink != null && originLink != id)) routingCorrupt()
            val stored = c.one("SELECT origin_client_id,origin_plan_id,definition_json,routing_digest FROM routing_batches WHERE routing_id=?", id) {
                listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
            } ?: routingCorrupt()
            if (stored[2].toByteArray().size > config.maxInputBytes || Digests.sha256(stored[2]) != stored[3]) routingCorrupt()
            val route = ContractJson.decodeFromString<DurableRoute>(stored[2])
            if (canonical(route) != stored[2] || !Digests.valid(route.providerKey)) routingCorrupt()
            val members = route.members
            if (members.size !in 2..64 || members.sumOf { it.plan.slots.size.toLong() } > 512 ||
                members.map { it.planId }.distinct().size != members.size ||
                members.map { it.plan.binding.clientId }.distinct().size != members.size ||
                members != members.sortedBy { it.plan.binding.clientId }) routingCorrupt()
            val declared = route.origin
            if (normalize(declared.binding, declared.request, declared, allowRouting = true) != declared ||
                declared.requiredRecipientClientIds.toSet() != members.map { it.plan.binding.clientId }.toSet() ||
                stored[0] != declared.binding.clientId) routingCorrupt()
            val origin = members.single { it.plan.binding.clientId == declared.binding.clientId }
            if (origin.planId != stored[1] || origin.plan != declared.copy(requiredRecipientClientIds = listOf(declared.binding.clientId))) routingCorrupt()
            val expectedMembers = members.map { it.plan.binding.clientId to it.planId }.toSet()
            val linked = c.query("SELECT client_id,plan_id FROM routing_members WHERE routing_id=?", id) { it.getString(1) to it.getString(2) }
            if (linked.size != members.size || linked.toSet() != expectedMembers) routingCorrupt()
            val marked = c.query("SELECT client_id,plan_id FROM parent_plans WHERE routing_id=?", id) { it.getString(1) to it.getString(2) }
            if (marked.size != members.size || marked.toSet() != expectedMembers || (requested.client to requested.id) !in expectedMembers) routingCorrupt()
            if (members.map { it.preparation.capturedPolicyDigest }.distinct().size != members.size ||
                members.map { it.preparation.executionApprovalDigest }.distinct().size != members.size) routingCorrupt()
            members.forEach { member ->
                val plan = member.plan
                val binding = plan.binding
                if (binding.ownerId != declared.binding.ownerId || binding.workspaceId != declared.binding.workspaceId ||
                    normalize(binding, plan.request, plan) != plan) routingCorrupt()
                val encoded = canonical(plan)
                val digest = Digests.sha256(encoded)
                requirePreparation(digest, member.preparation)
                val enrollment = c.one("SELECT binding_json,routed_obligations,next_review FROM clients WHERE client_id=?", binding.clientId) {
                    Triple(it.getString(1), it.getInt(2), it.getLong(3))
                } ?: routingCorrupt()
                if (enrollment.first != canonical(binding) || enrollment.second != 1) routingCorrupt()
                val row = parent(c, binding.clientId, member.planId)
                if (row.encoded != encoded || row.digest != digest || row.patch != plan.request.patchId ||
                    row.key != plan.request.idempotencyKey || row.requestHash != Digests.sha256(canonical(plan.request)) ||
                    row.routingId != id || row.frontier < plan.slots.size || enrollment.third <= row.frontier) routingCorrupt()
                val expected = children(member.planId, plan, digest, route.providerKey)
                val actual = c.query("""SELECT s.slot_id,r.review_id,r.client_id,r.patch_id,r.idempotency_key,r.request_hash,
                    r.request_json,r.input_json,r.work_key,r.sequence,w.provider_key,w.input_json AS work_input
                    FROM plan_slots s JOIN reviews r ON r.review_id=s.review_id JOIN work w ON w.work_key=r.work_key
                    WHERE s.plan_id=? ORDER BY r.sequence""", member.planId) {
                    RoutedChild(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5),
                        it.getString(6), it.getString(7), it.getString(8), it.getString(9), it.getLong(10), it.getString(11), it.getString(12))
                }
                if (actual.size != expected.size) routingCorrupt()
                expected.zip(actual).forEachIndexed { index, (child, saved) ->
                    val requestJson = canonical(child.request)
                    val inputJson = canonical(child.slot.input)
                    if (saved.slot != child.slot.slotId || saved.client != binding.clientId || saved.patch != child.request.patchId ||
                        saved.key != child.request.idempotencyKey || saved.requestHash != Digests.sha256(requestJson) ||
                        saved.requestJson != requestJson || saved.inputJson != inputJson || saved.work != child.key ||
                        saved.provider != route.providerKey || saved.workInput != inputJson ||
                        saved.sequence != row.frontier - expected.size + index + 1) routingCorrupt()
                }
                validateAcceptanceEvents(c, member, actual)
            }
        } catch (_: Exception) { routingCorrupt() }
    }

    private fun validateAcceptanceEvents(c: Connection, member: DurableRouteMember, children: List<RoutedChild>) {
        val plan = member.plan
        val events = c.query("""SELECT e.* FROM events e JOIN plan_slots s ON s.review_id=e.review_id
            WHERE s.plan_id=? AND e.kind IN ('accepted','plan_accepted')""", member.planId) {
            ReviewEvent(it.getString("event_id"), it.getString("client_id"), it.getLong("sequence"), it.getString("review_id"),
                it.getString("patch_id"), it.getString("snapshot_id"), it.getString("kind"), it.getInt("delivery_required") != 0,
                it.getString("payload_digest"), it.getString("payload"), it.getLong("generation"))
        }
        if (events.size != children.size + 1 || events.count { it.kind == "plan_accepted" } != 1) routingCorrupt()
        val snapshot = plan.slots.first().input.snapshot.snapshotId
        val byReview = children.associateBy { it.review }
        events.forEach { event ->
            val child = byReview[event.reviewId] ?: routingCorrupt()
            if (event.clientId != plan.binding.clientId || event.generation != plan.binding.generation || !event.deliveryRequired ||
                event.patchId != child.patch || event.sourceSnapshotId != snapshot || event.payload.toByteArray().size > config.maxResultBytes ||
                Digests.sha256(event.payload) != event.payloadDigest) routingCorrupt()
            val expected = ReviewStatus(child.review, child.client, child.patch, child.sequence, snapshot, event.kind)
            if (event.kind == "accepted") {
                if (event.payload != canonical(expected) || events.count { it.kind == "accepted" && it.reviewId == child.review } != 1) routingCorrupt()
            } else {
                val payload = ContractJson.decodeFromString<ReviewStatus>(event.payload)
                val parent = payload.parentPlan ?: routingCorrupt()
                if (child != children.last() || payload.copy(parentPlan = null) != expected ||
                    parent.planId != member.planId || parent.clientId != plan.binding.clientId || parent.patchId != plan.request.patchId ||
                    parent.sourceSnapshotId != snapshot || parent.planDigest != member.preparation.planDigest ||
                    parent.slots.size != children.size || parent.contractVersion != PREVIEW_CONTRACT) routingCorrupt()
                parent.slots.forEachIndexed { index, slot ->
                    val required = plan.slots[index]
                    if (slot.slotId != required.slotId || slot.reviewId != children[index].review || slot.context != required.input.context.scope ||
                        slot.requiredRuleIds != required.input.requiredRuleIds) routingCorrupt()
                }
            }
        }
    }

    fun cancel(credential: ClientCredential, id: String): ParentPlanStatus {
        service.ensureOpen()
        return ledger.transaction { c ->
            val binding = service.authenticate(c, credential)
            val old = status(c, binding.clientId, id)
            if (c.one("SELECT gate_state FROM clients WHERE client_id=?", binding.clientId) { it.getString(1) } != "open") fail("lineage_finalized", "Client generation already claimed finalization")
            old.slots.forEach { slot ->
                if (slot.reviewId.isNotEmpty() && c.count("SELECT cancelled FROM reviews WHERE review_id=?", slot.reviewId) == 0L) {
                    c.execute("UPDATE reviews SET cancelled=1 WHERE review_id=?", slot.reviewId)
                    c.execute("UPDATE clients SET epoch=epoch+1 WHERE client_id=?", binding.clientId)
                    service.appendEvent(c, binding, service.readStatus(c, slot.reviewId, binding.clientId), "cancelled")
                }
            }
            c.execute("UPDATE parent_plans SET cancelled=1 WHERE plan_id=?", id)
            status(c, binding.clientId, id)
        }
    }

    internal fun latest(c: Connection, client: String): Stored? {
        val id = c.one("SELECT plan_id FROM parent_plans WHERE client_id=? ORDER BY rowid DESC LIMIT 1", client) { it.getString(1) } ?: return null
        val row = parent(c, client, id)
        validateRouting(c, row)
        return Stored(row.id, storedDefinition(row.encoded, row.digest), row.frontier)
    }

    private fun storedDefinition(encoded: String, digest: String): RequiredReviewPlan {
        if (encoded.toByteArray().size > config.maxInputBytes || Digests.sha256(encoded) != digest) {
            fail("plan_corrupt", "Stored parent definition failed its immutable integrity check")
        }
        return try { ContractJson.decodeFromString<RequiredReviewPlan>(encoded) }
        catch (_: Exception) { fail("plan_corrupt", "Stored parent definition is unavailable or malformed") }
    }

    internal fun lineageReasons(c: Connection, client: String): List<String> = buildList {
        if (c.count("SELECT routed_obligations FROM clients WHERE client_id=?", client) != 0L ||
            c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND routing_id IS NOT NULL", client) != 0L ||
            c.count("SELECT COUNT(*) FROM routing_members WHERE client_id=?", client) != 0L) {
            add("cross_client_finalization_unavailable")
        }
        c.query("SELECT plan_id FROM parent_plans WHERE client_id=? ORDER BY rowid", client) { it.getString(1) }.forEach { id ->
            val status = status(c, client, id)
            if (status.state != "complete") add("parent_plan_${status.state}:$id")
        }
    }

    private fun prior(c: Connection, client: String, key: String, hash: String): ParentPlanStatus? {
        if (c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND idempotency_key=?", client, key) != 0L) {
            fail("idempotency_conflict", "Idempotency key already belongs to a manual review")
        }
        val previous = c.one("SELECT plan_id,request_hash FROM parent_plans WHERE client_id=? AND idempotency_key=?", client, key) {
            it.getString(1) to it.getString(2)
        } ?: return null
        if (previous.second != hash) fail("idempotency_conflict", "Parent idempotency key is bound to another request")
        return status(c, client, previous.first)
    }
}

/** Exact comparison only; this helper computes identity, never grants model authority. */
internal fun contextIdentity(input: AnalysisInput): PlanContextIdentity? {
    val context = input.context
    val compilerVersion = context.compilerVersion ?: return null
    val digests = listOf(context.modelDigest, context.dependencyDigest, context.toolchainDigest, context.configurationDigest)
    if (digests.any { it == null || !Digests.valid(it) } || compilerVersion.isBlank()) return null
    val paths = context.compilationFiles ?: return null
    if (paths.distinct().size != paths.size) return null
    if (context.classpath.any { !Digests.valid(it.digest) }) return null
    val files = input.snapshot.files.associateBy { it.path }
    if (paths.any { it !in files }) return null
    return PlanContextIdentity(context = context.scope, modelDigest = context.modelDigest!!,
        dependencyDigest = context.dependencyDigest!!, toolchainDigest = context.toolchainDigest!!,
        compilerVersion = compilerVersion, compilerArgumentsDigest = Digests.sha256(canonical(context.compilerArguments)),
        classpathDigest = Digests.sha256(canonical(context.classpath)), librariesDigest = Digests.sha256(canonical(context.libraries)),
        policiesDigest = Digests.sha256(canonical(context.policies)),
        compilationFilesDigest = Digests.sha256(canonical(paths.map { files.getValue(it) })), configurationDigest = context.configurationDigest!!)
}
