package dev.ktchecker.cli

import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.reviewcontroller.AssembledReviewPlan
import dev.ktchecker.reviewcontroller.exactPlanContextIdentity
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/**
 * One live owner intent -> one ENTIRE declared parent -> its two archive-bound resolutions.
 * Nothing in this registry is restored from evidence. All executable P evidence stays separate
 * from immutable D policy/history, and cannot be borrowed by the ordinary input-only observer.
 */
internal class OwnerProducedPlan(
    private val assembly: AssembledReviewPlan,
    private val runtime: OwnerLiveModelRuntime,
    private val model: OwnerProducedModel,
    private val catalog: Catalog,
    private val coordinator: AnalysisProvider,
    private val verifyCode: () -> Unit,
) {
    private val admission = AtomicReference<ProducedPlanAdmissionRequest?>()
    private val preparation = AtomicReference<ProducedPlanPreparationRequest?>()
    private val declarations = ConcurrentHashMap<String, JvmProducedDeclaredArchive>()
    private val slots = ConcurrentHashMap<String, Slot>()
    private val consumerReserved = AtomicBoolean()
    private val producerPublication = AtomicReference<ProducedWorkPublication?>()
    private val graph get() = requireNotNull(assembly.plan.jvmProduced)
    private val observedEvidence get() = model.imported.observedModel?.let {
        ObservedExportPlanEvidence(model.imported.intent.binding, it.invocation.identity, it.evidenceIdentity)
    }
    private fun sameModelOrigin(): Boolean = model.imported.origin.matches(model.preview) && when (val origin = model.imported.origin) {
        is OwnerModelOrigin.Verified -> assembly.provenance.observedAfter == null && assembly.provenance.after === origin.value
        is OwnerModelOrigin.Observed -> assembly.provenance.observedAfter === origin.value
    }

    private class Slot(val archive: DeclaredPlanArchive, val declaration: JvmProducedDeclaredArchive,
        val compilation: JvmProducedPreviewCompilation, val prepared: PreparedJvmLiveModelInput,
        val manifest: ProducedByteManifest?) {
        val attempt = AtomicReference<ProducedExecutionAttempt?>()
        val checked = AtomicReference<Checked?>()
    }
    private class Checked(val attempt: ProducedExecutionAttempt, val result: ProviderResult,
        val observation: OwnerProducedObservation)

    init {
        require(sameModelOrigin() && graph.graphDigest == model.preview.graphIdentity.digest && graph.observedExport == observedEvidence)
        require(assembly.plan.binding == model.imported.intent.binding && assembly.plan.request == model.imported.intent.request)
        require(graph.intentDigest == ownerProducedDigest(ContractJson.encodeToJsonElement(model.imported.intent.evidence)))
        require(model.imported.intent.evidence.executionAuthorized && coordinator.id == "ktchecker-coordinator")
    }

    fun authorize(request: ProducedPlanAdmissionRequest): AuthorizedProducedPlan? {
        if (request.definition != assembly.plan || request.planDigest != assembly.planDigest ||
            request.binding != model.imported.intent.binding || request.request != model.imported.intent.request) return null
        if (!admission.compareAndSet(null, request)) return null // An equal DTO is not a new admission.
        verifyCurrent()
        val deadline = model.deadline
        val clock = ProducedPlanClock(deadline, deadline.identity, deadline.startedAtNanos, deadline.durationMillis)
        val identity = ownerProducedDigest(buildJsonObject {
            put("schema", "ktchecker/owner-produced-parent/1"); put("runtime", runtime.owner.identity)
            put("implementation", runtime.implementationIdentity); put("intent", graph.intentDigest)
            put("plan", request.planDigest); put("planId", request.planId); put("origin", request.originInstance)
            put("provider", request.providerKey); put("originalClock", deadline.identity)
        })
        return AuthorizedProducedPlan(request, clock, identity, model.validity, ::verifyCurrent,
            ::prepareProducer, ::operationalFaults, ::resolveConsumer)
    }

    private fun verifyCurrent() {
        verifyCode()
        runtime.verifyProduced(model)
        if (!sameModelOrigin() || graph.observedExport != observedEvidence ||
            model.deadline !== model.imported.intent.originalDeadline || !model.imported.intent.evidence.executionAuthorized) {
            fail("owner_produced_import_changed")
        }
    }

    private fun prepareProducer(request: ProducedPlanPreparationRequest): PreparedProducedExecution {
        val admitted = admission.get() ?: fail("owner_produced_admission_unavailable")
        if (request.admission !== admitted || !preparation.compareAndSet(null, request) || request.archives.size != 2 ||
            request.archives.map { it.identity.slotId } != assembly.plan.slots.map { it.slotId }) fail("owner_produced_archive_mismatch")
        verifyCurrent()
        // BOTH complete D archives already exist. Never bind the producer against mutable old
        // input paths first and then retrofit the archive relation after the binding was issued.
        request.archives.forEach { archive ->
            val declared = when (archive.identity.slotId) {
                graph.producer.slotId -> graph.producer
                graph.consumer.slotId -> graph.consumer
                else -> fail("owner_produced_archive_mismatch")
            }
            val original = assembly.plan.slots.single { it.slotId == declared.slotId }.input
            if (archive.admission !== admitted || archive.original != original ||
                archive.identity.declarationInputKey != original.analysisKey()) fail("owner_produced_archive_mismatch")
            archive.verify()
            val retained = model.preview.retainDeclaredArchive(admitted, archive, declared.compilationId,
                archive.identity.archiveKey, original, archive.frozen, archive.held, archive::readInput, archive::verify)
            if (declarations.putIfAbsent(declared.slotId, retained) != null) fail("owner_produced_archive_replayed")
        }
        verifyCurrent()
        return prepareSlot(graph.producer.slotId, null, null)
    }

    private fun prepareSlot(slotId: String, artifact: ModelProducedOutputArtifact?, manifest: ProducedByteManifest?): PreparedProducedExecution {
        val request = preparation.get() ?: fail("owner_produced_archive_unavailable")
        val admitted = admission.get() ?: fail("owner_produced_admission_unavailable")
        val archive = request.archive(slotId)
        val declaration = declarations[slotId] ?: fail("owner_produced_archive_unavailable")
        verifyCurrent()
        request.archives.forEach { it.verify() }
        val compilation = model.preview.bindCompilationFromDeclaration(declaration, admitted, archive, artifact)
        val original = archive.original
        val policies = compilation.context.policies + original.context.policies +
            ("ktchecker.execution-plan" to ownerProducedDigest(ContractJson.encodeToJsonElement(emptyMap<String, TrustedExecution>())))
        val input = freezeModelInput(original.copy(context = compilation.context.copy(policies = policies)))
        val activation = validatedActivation(original, catalog)
        val rules = original.requiredRuleIds.filter { activation[it]?.active == true && it in model.provider.supportedRuleIds }.sorted()
        if (rules.isEmpty()) fail("owner_produced_compiler_rules_inactive")
        val prepared = compilation.binding.bindPreparedDeclaredLiveInput(declaration, admitted, archive,
            input, rules, model.provider, model.deadline)
        val slot = Slot(archive, declaration, compilation, prepared, manifest)
        if (slots.putIfAbsent(slotId, slot) != null) fail("owner_produced_resolution_replayed")
        return PreparedProducedExecution(slotId, input, compilation.binding.identity.digest, manifest) {
            verifyCurrent()
            request.archives.forEach { it.verify() }
            compilation.binding.verifyInstallation { runtime.verifyProduced(model) }
            verifyCurrent()
        }
    }

    /** Sole typed observer; ModelPlans' ordinary observe cannot find any of these P keys. */
    fun observe(attempt: ProducedExecutionAttempt) {
        val slot = slots[attempt.execution.slotId] ?: fail("owner_produced_resolution_unavailable")
        validateAttempt(attempt, slot)
        if (!slot.attempt.compareAndSet(null, attempt)) fail("owner_produced_attempt_replayed")
        verifyCurrent()
        attempt.verifyFrozen()
        runtime.preparedDeclared(model, slot.compilation, slot.prepared, slot.declaration, attempt)
        verifyCurrent()
    }

    private fun validateAttempt(attempt: ProducedExecutionAttempt, slot: Slot) {
        val admitted = admission.get() ?: fail("owner_produced_admission_unavailable")
        val retained = preparation.get() ?: fail("owner_produced_archive_unavailable")
        val execution = attempt.execution
        val certificate = execution.certificate
        val planSlot = assembly.plan.slots.singleOrNull { it.slotId == execution.slotId } ?: fail("owner_produced_slot_mismatch")
        val producer = if (execution.slotId == graph.consumer.slotId)
            slots[graph.producer.slotId]?.attempt?.get()?.execution ?: fail("owner_produced_producer_unavailable") else null
        val publication = if (producer != null) producerPublication.get() ?: fail("owner_produced_publication_unavailable") else null
        val expected = PlanExecutionCertificate(admitted.planId, admitted.planDigest, planSlot.slotId, certificate.reviewId,
            ownerProducedDigest(ContractJson.encodeToJsonElement(planSlot)), planSlot.input.analysisKey(), graph.graphDigest,
            slot.archive.identity.digest(), slot.prepared.original.analysisKey(),
            exactPlanContextIdentity(slot.prepared.original) ?: fail("owner_produced_context_unavailable"),
            slot.compilation.binding.identity.digest, admitted.providerKey, admitted.originInstance,
            model.deadline.identity, producer?.reviewId, producer?.workKey, publication?.digest(), slot.manifest,
            observedExport = graph.observedExport)
        val source = Path.of(slot.archive.frozen.materializedRoot)
        val work = source.parent.parent.resolve(execution.workKey.removePrefix("sha256:"))
        val output = source.parent.parent.parent.resolve("outputs").resolve(execution.workKey.removePrefix("sha256:"))
            .resolve("attempt-${attempt.attempt}-${admitted.originInstance}")
        val p = slot.prepared.original
        val k = attempt.effective
        if (attempt.admission !== admitted || attempt.preparation !== retained || slot.archive !== retained.archive(planSlot.slotId) ||
            attempt.original != p || execution.input != p || certificate != expected || certificate.reviewId.isBlank() ||
            certificate.reviewId.length > 256 || !Digests.valid(attempt.workKey) || attempt.resolutionCertificateId != expected.digest() ||
            attempt.attempt < 1 || graph.observedExport != null && attempt.attempt != 1L || attempt.originInstance != admitted.originInstance ||
            k.materializedRoot != work.resolve("sources").toString() || k.outputRoot != output.toString() ||
            k.copy(materializedRoot = p.materializedRoot, outputRoot = p.outputRoot,
                context = k.context.copy(classpath = p.context.classpath)) != p ||
            k.context.classpath != p.context.classpath.mapIndexed { index, value ->
                value.copy(path = work.resolve("classpath/artifact-$index.jar").toString())
            }) fail("owner_produced_attempt_tuple_mismatch")
    }

    /** Captures successful classification, but NEVER marks the producer published/promotable. */
    private fun operationalFaults(check: ProducedPublicationCheck): List<String> {
        val slot = slots[check.execution.slotId] ?: return listOf("owner_produced_resolution_unavailable")
        val attempt = slot.attempt.get() ?: return listOf("owner_produced_attempt_unavailable")
        validateAttempt(attempt, slot)
        verifyCurrent()
        if (check.admission !== admission.get() || check.execution != attempt.execution ||
            check.original != attempt.original || check.effective != attempt.effective) return listOf("owner_produced_publication_tuple_mismatch")
        val observed = runtime.producedObservation(model, attempt)
        if (check.execution.slotId == graph.producer.slotId) {
            val success = observed.successfulOutput ?: return listOf("owner_produced_compiler_success_unavailable")
            if (success.compilationId != graph.producer.compilationId || success.bindingDigest != slot.compilation.binding.identity.digest ||
                success.acceptedProviderResultDigest != ownerProducedDigest(ContractJson.encodeToJsonElement(observed.result))) {
                return listOf("owner_produced_compiler_return_substituted")
            }
        } else if (observed.successfulOutput != null) return listOf("owner_produced_unexpected_output_authority")
        val faults = ownerProducedParentFaults(catalog, coordinator, model.provider, attempt.effective, observed.child, observed.result,
            observed.callElapsedMillis, model.deadline, check.result, model.imported.observedModel, graph.observedExport)
        if (faults.isNotEmpty()) return faults
        val detached = ContractJson.decodeFromString<ProviderResult>(Digests.canonical(ContractJson.encodeToJsonElement(check.result)))
        if (!slot.checked.compareAndSet(null, Checked(attempt, detached, observed))) return listOf("owner_produced_classifier_replayed")
        return emptyList()
    }

    /** Kernel calls ONLY after durable clean producer publication and consumer D+128MiB charge. */
    private fun resolveConsumer(request: ProducedSlotResolutionRequest): ProducedSlotResolution {
        val admitted = admission.get() ?: fail("owner_produced_admission_unavailable")
        val retained = preparation.get() ?: fail("owner_produced_archive_unavailable")
        val slot = slots[graph.producer.slotId] ?: fail("owner_produced_producer_unavailable")
        val checked = slot.checked.get() ?: fail("owner_produced_unchecked_producer")
        val attempt = checked.attempt
        val publication = request.publication
        if (request.preparation !== retained || request.admission !== admitted || request.producer != attempt.execution ||
            request.producerResult != checked.result || publication.workKey != attempt.workKey || publication.attempt != attempt.attempt ||
            publication.originInstance != attempt.originInstance || publication.executionCertificate != attempt.resolutionCertificateId ||
            publication.originalInputKey != attempt.original.analysisKey() || publication.effectiveInputKey != attempt.effective.analysisKey() ||
            publication.providerKey != admitted.providerKey || publication.operationalFaults.isNotEmpty() ||
            publication.rawResultDigest != ownerProducedDigest(ContractJson.encodeToJsonElement(checked.result)) ||
            publication.profile != "ktchecker/produced-work-publication/1" || publication.observedExport != graph.observedExport) fail("owner_produced_durable_publication_mismatch")
        validateAttempt(attempt, slot)
        verifyCurrent()
        if (!consumerReserved.compareAndSet(false, true) || !producerPublication.compareAndSet(null, publication)) {
            fail("owner_produced_consumer_replayed")
        }
        val observed = runtime.producedObservation(model, attempt)
        val success = checked.observation.successfulOutput ?: fail("owner_produced_compiler_success_unavailable")
        if (observed.successfulOutput !== success || observed.result != checked.observation.result) fail("owner_produced_finished_custody_changed")
        // This is the FIRST output promotion/IO. finish() only retained the candidate. The
        // compiler rechecks SAME finished token/materialization/whole K without reopening a ticket.
        val artifact = success.materializeJar(request.completeJarDirectory)
        verifyCurrent()
        val actual = artifact.artifact
        val membersDigest = ownerProducedDigest(JsonArray(success.members.map { member -> buildJsonObject {
            put("path", member.path()); put("directory", member.directory()); put("size", member.size()); put("digest", member.digest())
        } }))
        val manifest = ProducedByteManifest(model.imported.origin.snapshot.snapshotId, graph.moduleId,
            graph.producer.compilationId, graph.consumer.compilationId, graph.graphDigest, graph.toolchainDigest,
            graph.producer.argumentLayoutDigest, graph.consumer.argumentLayoutDigest, success.bindingDigest,
            success.generation, success.sequence, success.requestDigest, success.resultDigest, success.acceptedProviderResultDigest,
            success.outputDigest, membersDigest, success.members.size,
            ArtifactIdentity(actual.coordinate, requireNotNull(actual.digest), actual.path), requireNotNull(actual.size))
        return ProducedSlotResolution.Ready(prepareSlot(graph.consumer.slotId, artifact, manifest))
    }

    private fun fail(code: String): Nothing = throw CliProblem(code,
        "The exact live declared owner plan is unavailable; no source narrowing, replay or ordinary fallback is authorized.")
}

internal fun ownerProducedDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))
