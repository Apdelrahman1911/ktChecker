package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import java.sql.Connection
import java.util.UUID

/** Atomic same-client fanout. Controller-origin evidence is not an independently proved model. */
internal class ParentPlans(private val service: KernelService, private val authority: ReviewPlanAuthority) {
    private val ledger get() = service.ledger
    private val config get() = service.config
    /** Internal fault-injection seam for transaction/crash tests; no public/RPC controller setter. */
    internal var afterSlotInsert: (Int) -> Unit = { }

    internal data class Stored(val id: String, val definition: RequiredReviewPlan, val frontier: Long)
    private data class Child(val slot: RequiredPlanSlot, val request: SubmitRequest, val key: String)

    fun submit(credential: ClientCredential, request: SubmitPlanRequest): ParentPlanStatus {
        service.ensureOpen()
        listOf(request.patchId, request.idempotencyKey, request.planRef).forEach { identifier(it, "parent plan request") }
        val hash = Digests.sha256(canonical(request))
        val binding = ledger.read { service.authenticate(it, credential) }
        ledger.read { c -> prior(c, binding.clientId, request.idempotencyKey, hash) }?.let { return it }
        if (!service.ingestion.tryAcquire()) fail("ingestion_busy", "Bounded input ingestion is busy; retry before acceptance")
        val staged = linkedMapOf<String, InputArchive.Staged>()
        try {
            val resolved = authority.resolve(binding, request)
                ?: fail("plan_authority_unavailable", "Only the trusted controller can resolve a complete registered review plan")
            val encoded = canonical(resolved)
            if (encoded.toByteArray().size > config.maxInputBytes) fail("input_limit", "Parent plan exceeds the bounded metadata size")
            val definition = normalize(binding, request, ContractJson.decodeFromString<RequiredReviewPlan>(encoded))
            val definitionJson = canonical(definition)
            if (definitionJson.toByteArray().size > config.maxInputBytes) fail("input_limit", "Expanded plan exceeds the bounded metadata size")
            val digest = Digests.sha256(definitionJson)
            val planId = UUID.randomUUID().toString()
            val children = definition.slots.map { slot ->
                val identity = Digests.sha256(canonical(listOf(planId, slot.slotId)))
                val child = SubmitRequest("plan-child:$identity", "plan-child:$identity", slot.input)
                val key = if (slot.state == "pending") Digests.sha256(canonical(listOf(service.providerKey, slot.input.analysisKey())))
                    else Digests.sha256(canonical(listOf("administrative-unsupported-plan-slot", service.providerKey, digest, slot.slotId)))
                Child(slot, child, key)
            }
            ledger.read { c -> preflight(c, binding.clientId, children) }
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
            return ledger.transaction { c ->
                service.ensureOpen()
                service.authenticate(c, credential)
                prior(c, binding.clientId, request.idempotencyKey, hash)?.let { return@transaction it }
                if (c.count("SELECT COUNT(*) FROM parent_plans WHERE client_id=? AND patch_id=?", binding.clientId, request.patchId) != 0L ||
                    c.count("SELECT COUNT(*) FROM reviews WHERE client_id=? AND patch_id=?", binding.clientId, request.patchId) != 0L) {
                    fail("patch_id_conflict", "Patch ID already has a distinct accepted request in this client lineage")
                }
                preflight(c, binding.clientId, children)
                val missing = children.distinctBy { it.key }.filter { c.count("SELECT COUNT(*) FROM work WHERE work_key=?", it.key) == 0L }
                var bytes = 0L
                val available = config.maxArchiveBytes - c.count("SELECT COALESCE(SUM(archive_bytes),0) FROM work")
                missing.forEach { child ->
                    val ready = staged[child.key] ?: fail("archive_missing", "Prepared plan input is unavailable")
                    if (ready.bytes > available - bytes) fail("archive_full", "Whole parent plan exceeds durable archive quota")
                    bytes += ready.bytes
                }
                // Neither another connection nor a worker can see this parent or any child until
                // the complete vector, private inputs and every required event commit together.
                c.execute("""INSERT INTO parent_plans(plan_id,client_id,patch_id,idempotency_key,request_hash,definition_json,plan_digest,review_frontier)
                    VALUES(?,?,?,?,?,?,?,0)""", planId, binding.clientId, request.patchId, request.idempotencyKey, hash, definitionJson, digest)
                children.forEachIndexed { index, child ->
                    val review = acceptChild(c, binding, child, staged[child.key])
                    c.execute("INSERT INTO plan_slots(plan_id,slot_id,review_id) VALUES(?,?,?)", planId, child.slot.slotId, review.reviewId)
                    afterSlotInsert(index + 1)
                }
                c.execute("UPDATE parent_plans SET review_frontier=(SELECT next_review-1 FROM clients WHERE client_id=?) WHERE plan_id=?", binding.clientId, planId)
                val status = status(c, binding.clientId, planId)
                val anchor = service.readStatus(c, status.slots.last().reviewId, binding.clientId)
                val payload = anchor.copy(state = "plan_accepted", result = null, reasons = emptyList(), parentPlan = status)
                if (canonical(payload).toByteArray().size > config.maxResultBytes) fail("input_limit", "Complete parent acceptance event exceeds its payload bound")
                service.appendEvent(c, binding, payload, "plan_accepted")
                status
            }
        } finally {
            staged.values.forEach { it.close() }
            service.ingestion.release()
        }
    }

    private fun normalize(binding: ClientBinding, request: SubmitPlanRequest, definition: RequiredReviewPlan): RequiredReviewPlan {
        if (definition.binding != binding) fail("plan_binding_mismatch", "Plan belongs to another client, namespace or generation")
        if (definition.request != request) fail("plan_request_mismatch", "Registered plan is bound to another exact patch, idempotency key or reference")
        if (definition.requiredRecipientClientIds.isEmpty() || definition.requiredRecipientClientIds.distinct().size != definition.requiredRecipientClientIds.size) {
            fail("plan_recipients_invalid", "A complete nonempty controller-declared recipient set is required")
        }
        if (definition.requiredRecipientClientIds != listOf(binding.clientId)) {
            fail("cross_client_routing_unavailable", "Required cross-client RoutingGrant subscriptions are not implemented; no prefix was accepted")
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

    private fun preflight(c: Connection, clientId: String, children: List<Child>) {
        service.preflightAdmission(c, clientId)
        if (children.size > config.maxRetainedReviews - c.count("SELECT COUNT(*) FROM reviews")) fail("ledger_full", "Whole parent plan exceeds retained obligations quota")
        val queued = children.distinctBy { it.key }.count { it.slot.state == "pending" && c.count("SELECT COUNT(*) FROM work WHERE work_key=?", it.key) == 0L }
        if (queued > config.maxQueuedWork - c.count("SELECT COUNT(*) FROM work WHERE state='queued'")) fail("queue_full", "Whole parent plan exceeds the durable work queue")
        val pending = c.count("""SELECT COUNT(*) FROM reviews r JOIN work w ON w.work_key=r.work_key
            WHERE r.client_id=? AND w.state IN ('queued','running')""", clientId)
        val added = children.count { it.slot.state == "pending" && c.count("SELECT COUNT(*) FROM work WHERE work_key=? AND state='complete'", it.key) == 0L }
        if (added > config.maxPendingPerClient - pending) fail("client_queue_full", "Whole parent plan exceeds this client's pending obligation limit")
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

    internal fun status(c: Connection, client: String, id: String): ParentPlanStatus {
        val row = c.one("SELECT definition_json,patch_id,plan_digest,cancelled FROM parent_plans WHERE plan_id=? AND client_id=?", id, client) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        } ?: fail("plan_not_found", "Parent plan is not available to this client")
        val definition = storedDefinition(row[0], row[2])
        val children = c.query("SELECT slot_id,review_id FROM plan_slots WHERE plan_id=?", id) { it.getString(1) to it.getString(2) }.toMap()
        val slots = definition.slots.map { slot ->
            val child = children[slot.slotId]?.let { service.readStatus(c, it, client) }
            PlanSlotStatus(slot.slotId, child?.reviewId.orEmpty(), slot.input.context.scope, slot.input.requiredRuleIds,
                when {
                    child == null -> "incomplete"
                    child.cancelled -> "cancelled"
                    slot.state == "unsupported" -> "unsupported"
                    else -> child.state
                },
                (slot.reasons + child?.reasons.orEmpty() + if (child == null) listOf("required_plan_slot_missing") else emptyList()).distinct())
        }
        val cancelled = row[3] != "0" || slots.any { it.state == "cancelled" }
        val state = when {
            cancelled -> "cancelled"
            slots.any { it.state in setOf("incomplete", "unsupported") } -> "incomplete"
            slots.any { it.state in setOf("queued", "running") } -> "pending"
            slots.all { it.state == "complete" } -> "complete"
            else -> "incomplete"
        }
        return ParentPlanStatus(id, client, row[1], definition.slots.first().input.snapshot.snapshotId, row[2], state, slots,
            if (cancelled) listOf("parent_cancelled_obligations_retained") else emptyList())
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

    internal fun latest(c: Connection, client: String): Stored? = c.one(
        "SELECT plan_id,definition_json,review_frontier,plan_digest FROM parent_plans WHERE client_id=? ORDER BY rowid DESC LIMIT 1", client,
    ) { Stored(it.getString(1), storedDefinition(it.getString(2), it.getString(4)), it.getLong(3)) }

    private fun storedDefinition(encoded: String, digest: String): RequiredReviewPlan {
        if (encoded.toByteArray().size > config.maxInputBytes || Digests.sha256(encoded) != digest) {
            fail("plan_corrupt", "Stored parent definition failed its immutable integrity check")
        }
        return try { ContractJson.decodeFromString<RequiredReviewPlan>(encoded) }
        catch (_: Exception) { fail("plan_corrupt", "Stored parent definition is unavailable or malformed") }
    }

    internal fun lineageReasons(c: Connection, client: String): List<String> = buildList {
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
