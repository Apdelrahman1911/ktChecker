package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerSnapshot
import dev.ktchecker.compiler.live.model.JvmProducedPreviewCompilation
import dev.ktchecker.compiler.live.model.ModelCompilerGenerationRequest
import dev.ktchecker.compiler.live.model.ModelCompilerJobMaterialization
import dev.ktchecker.compiler.live.model.PreparedJvmLiveModelInput
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.reviewcontroller.exactPlanContextIdentity
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.*

/** Fixed production owner scope. None of these checks mint a model/input-closure capability. */
internal fun ownerModelRequire(value: Boolean, code: String = "owner_model_custody_mismatch") {
    if (!value) throw CliProblem(code,
        "The exact bounded owner model operation is unavailable; no fallback, new clock or complete analysis is claimed.")
}

internal fun ownerModelCheckpoint(deadline: OwnedApplicationDeadline) {
    ownerModelRequire(!Thread.currentThread().isInterrupted, "owner_model_interrupted")
    ownerModelRequire(!deadline.isExpired(), "owner_model_original_deadline_expired")
}

/** Absolute spelling and every existing ancestor are checked; no alias repair or shared chmod. */
internal fun ownerModelPath(path: Path, mustExist: Boolean = true): Path {
    ownerModelRequire(path.isAbsolute && path.normalize() == path && path.parent != null, "owner_model_path_invalid")
    var existing: Path? = path
    while (existing != null && !Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
    val anchor = requireNotNull(existing)
    ownerModelRequire(anchor.toRealPath() == anchor && !Files.isSymbolicLink(anchor), "owner_model_path_alias")
    ownerModelRequire(!mustExist || existing == path, "owner_model_path_missing")
    return path
}

internal fun ownerModelFreshDirectory(path: Path): OwnerModelRootPin {
    ownerModelPath(path, false)
    ownerModelRequire(!Files.exists(path, NOFOLLOW_LINKS), "owner_model_directory_in_use")
    // This preview needs file-key and hardlink observations. Do not downgrade on another FS.
    ownerModelRequire(path.fileSystem.supportedFileAttributeViews().contains("unix"), "owner_model_filesystem_unsupported")
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    return OwnerModelRootPin.capture(path)
}

internal data class OwnerModelRootPin private constructor(val path: Path, val fileKey: String) {
    val wire: Wire.Root get() = Wire.Root(path.toString(), fileKey)
    fun verify() { ownerModelRequire(capture(path) == this, "owner_model_root_changed") }
    companion object {
        fun capture(path: Path): OwnerModelRootPin {
            ownerModelPath(path)
            val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            ownerModelRequire(a.isDirectory && !a.isSymbolicLink && a.fileKey() != null, "owner_model_root_invalid")
            return OwnerModelRootPin(path, a.fileKey().toString())
        }
    }
}

/** Stable immutable bytes, file identity and metadata, independently captured by the owner. */
internal data class OwnerModelFilePin private constructor(
    val path: Path, val digest: String, val size: Long, val fileKey: String,
    private val modified: FileTime, private val executable: Boolean,
) {
    fun verify(checkpoint: () -> Unit) {
        ownerModelRequire(capture(path, size, checkpoint) == this, "owner_model_file_changed")
    }
    fun read(maximum: Int, checkpoint: () -> Unit): ByteArray {
        ownerModelRequire(size <= maximum, "owner_model_file_limit")
        verify(checkpoint)
        val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        ownerModelRequire(bytes.size.toLong() == size && Digests.sha256(bytes) == digest, "owner_model_file_changed")
        verify(checkpoint)
        return bytes
    }
    fun copyTo(destination: Path, checkpoint: () -> Unit): OwnerModelFilePin {
        verify(checkpoint)
        ownerModelPath(destination, false)
        ownerModelRequire(!Files.exists(destination, NOFOLLOW_LINKS), "owner_model_file_in_use")
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            FileChannel.open(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                privatePermissions(destination, false)
                val buffer = ByteArray(65_536)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    ownerModelRequire(count.toLong() <= size - total, "owner_model_copy_changed")
                    total += count; hash.update(buffer, 0, count)
                    val block = ByteBuffer.wrap(buffer, 0, count)
                    while (block.hasRemaining()) { checkpoint(); output.write(block) }
                }
                output.force(true)
            }
        }
        ownerModelRequire(total == size && digest == ownerModelHex(hash.digest()), "owner_model_copy_changed")
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString(if (executable) "r-x------" else "r--------"))
        verify(checkpoint)
        return capture(destination, size, checkpoint).also {
            ownerModelRequire(it.digest == digest && it.size == size && it.fileKey != fileKey, "owner_model_copy_alias")
        }
    }
    companion object {
        fun capture(path: Path, maximum: Long = CompilerSnapshot.MAX_ARTIFACT_BYTES,
                    checkpoint: () -> Unit): OwnerModelFilePin {
            checkpoint(); ownerModelPath(path)
            fun attributes(): BasicFileAttributes {
                val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                ownerModelRequire(a.isRegularFile && !a.isSymbolicLink && a.fileKey() != null && a.size() in 0..maximum,
                    "owner_model_file_invalid_or_large")
                ownerModelRequire(path.fileSystem.supportedFileAttributeViews().contains("unix") &&
                    (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() == 1L,
                    "owner_model_file_alias")
                return a
            }
            val before = attributes()
            val executable = Files.isExecutable(path)
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    checkpoint()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    ownerModelRequire(count.toLong() <= before.size() - total, "owner_model_file_changed")
                    total += count; hash.update(buffer, 0, count)
                }
            }
            val after = attributes()
            ownerModelRequire(total == before.size() && after.size() == before.size() &&
                after.fileKey() == before.fileKey() && after.lastModifiedTime() == before.lastModifiedTime() &&
                executable == Files.isExecutable(path), "owner_model_file_changed")
            checkpoint()
            return OwnerModelFilePin(path, ownerModelHex(hash.digest()), total, before.fileKey().toString(),
                before.lastModifiedTime(), executable)
        }
    }
}

private fun ownerModelHex(bytes: ByteArray): String = "sha256:" + java.util.HexFormat.of().formatHex(bytes)

internal fun ownerModelWrite(path: Path, bytes: ByteArray, maximum: Int, checkpoint: () -> Unit): OwnerModelFilePin {
    checkpoint(); ownerModelRequire(bytes.size <= maximum, "owner_model_file_limit")
    ownerModelPath(path, false)
    FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
        privatePermissions(path, false)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) { checkpoint(); output.write(buffer) }
        output.force(true)
    }
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
    return OwnerModelFilePin.capture(path, maximum.toLong(), checkpoint).also {
        ownerModelRequire(it.digest == Digests.sha256(bytes) && it.size == bytes.size.toLong())
    }
}

internal fun ownerModelNames(root: Path, maximum: Int): Set<String> {
    ownerModelPath(root)
    val names = linkedSetOf<String>()
    Files.newDirectoryStream(root).use { entries -> entries.forEach { entry ->
        ownerModelRequire(names.size < maximum && !Files.isSymbolicLink(entry), "owner_model_tree_limit_or_alias")
        names.add(entry.fileName.toString())
    } }
    return names
}

/** Complete bounded membership, not a claim to have observed arbitrary Gradle configuration reads. */
internal class OwnerModelTreePin private constructor(
    val root: OwnerModelRootPin,
    val directories: Map<String, OwnerModelRootPin>,
    val files: Map<String, OwnerModelFilePin>,
    private val maximumEntries: Int,
    private val maximumBytes: Long,
    private val maximumFileBytes: Long,
) {
    val bytes: Long get() = files.values.sumOf { it.size }
    val digest: String = ownerProducedDigest(buildJsonObject {
        put("root", root.path.toString()); put("rootKey", root.fileKey)
        putJsonArray("directories") { directories.forEach { (name, pin) -> add(buildJsonObject {
            put("path", name); put("key", pin.fileKey)
        }) } }
        putJsonArray("files") { files.forEach { (name, pin) -> add(buildJsonObject {
            put("path", name); put("key", pin.fileKey); put("digest", pin.digest); put("size", pin.size)
        }) } }
    })
    fun verify(checkpoint: () -> Unit) {
        val actual = capture(root.path, maximumEntries, maximumBytes, maximumFileBytes, checkpoint)
        ownerModelRequire(root == actual.root && directories == actual.directories && files == actual.files,
            "owner_model_tree_changed")
    }
    companion object {
        fun capture(path: Path, maximumEntries: Int, maximumBytes: Long,
                    maximumFileBytes: Long = CompilerSnapshot.MAX_ARTIFACT_BYTES,
                    checkpoint: () -> Unit): OwnerModelTreePin {
            checkpoint()
            val root = OwnerModelRootPin.capture(path)
            val directories = sortedMapOf<String, OwnerModelRootPin>()
            val files = sortedMapOf<String, OwnerModelFilePin>()
            var bytes = 0L
            val keys = mutableSetOf(root.fileKey)
            Files.walk(path).use { stream -> stream.forEach { member ->
                checkpoint()
                if (member != path) {
                    ownerModelRequire(directories.size + files.size < maximumEntries, "owner_model_tree_limit")
                    val name = path.relativize(member).toString()
                    if (Files.isDirectory(member, NOFOLLOW_LINKS)) {
                        val pin = OwnerModelRootPin.capture(member)
                        ownerModelRequire(keys.add(pin.fileKey), "owner_model_tree_alias")
                        directories[name] = pin
                    } else {
                        val pin = OwnerModelFilePin.capture(member, minOf(maximumFileBytes, maximumBytes - bytes), checkpoint)
                        ownerModelRequire(keys.add(pin.fileKey), "owner_model_tree_alias")
                        bytes += pin.size; files[name] = pin
                    }
                }
            } }
            root.verify(); checkpoint()
            return OwnerModelTreePin(root, Collections.unmodifiableMap(directories), Collections.unmodifiableMap(files),
                maximumEntries, maximumBytes, maximumFileBytes)
        }
    }
}

/**
 * Production command's independent whole D/P/K/C/E custody. The live model, assembled whole plan,
 * typed kernel admission/preparation/attempt and exact materialization OBJECTS are retained. No
 * serialized declaration, source scan, fixture assertion or input digest enrolls an attempt.
 * Completed producer records are deliberately retained: token custody is not an active ticket.
 */
internal class OwnerModelCommandCustody(
    private val workbench: Workbench,
    private val runtime: OwnerLiveModelRuntime,
    private val clock: OwnerModelCommandClock,
) : OwnerModelMaterializationCustody {
    private val originalDeadline: OwnedApplicationDeadline get() = clock.originalDeadline
    private val lock = Any()
    private var generation: ModelCompilerGenerationRequest? = null
    private var retainedPlan: PreparedOwnerModelPlan? = null
    private var retainedModel: ObservedProjectModel? = null
    private var admission: ProducedPlanAdmissionRequest? = null
    private var preparation: ProducedPlanPreparationRequest? = null
    private var declaredCaptureAttempted = false
    private var declaredCaptures: List<Declaration>? = null
    private val attempts = linkedMapOf<String, Attempt>()
    private val materializations = java.util.IdentityHashMap<ModelCompilerJobMaterialization, Materialization>()
    private val jobs = mutableSetOf<Int>()
    private val materializedKeys = mutableSetOf<String>()

    private class Attempt(val attempt: ProducedExecutionAttempt, val compilation: JvmProducedPreviewCompilation,
        val prepared: PreparedJvmLiveModelInput) { var registered = false; var materializing = false }
    private class Materialization(val attempt: Attempt, val value: ModelCompilerJobMaterialization,
        val roots: List<OwnerModelRootPin>, val source: OwnerModelTreePin, val dependencies: OwnerModelTreePin,
        val archive: OwnerModelTreePin, val archivedDependencies: OwnerModelTreePin?)
    private class Declaration(val value: DeclaredPlanArchive, val root: OwnerModelRootPin,
        val metadata: OwnerModelFilePin, val source: OwnerModelTreePin, val dependencies: OwnerModelTreePin?)

    fun bindGeneration(request: ModelCompilerGenerationRequest) {
        ownerModelRequire(request.owner === runtime.owner && request.installation === runtime.installation &&
            request.originalDeadline === originalDeadline && request.namespace.ownerId == workbench.state.ownerId &&
            request.generation.profile() == ModelWire.PRODUCED_PROFILE)
        synchronized(lock) { ownerModelRequire(generation == null); generation = request }
    }

    /** Called before whole-parent submission; no per-slot path or requested rule subset is accepted. */
    fun retain(plan: PreparedOwnerModelPlan, model: ObservedProjectModel) {
        checkpoint()
        ownerModelRequire(model.invocation.originalDeadline === originalDeadline &&
            model.assuranceProfile == OBSERVED_EXPORT_PROFILE && model.issues.any {
                it.kind == CONFIGURATION_INPUT_CLOSURE_UNPROVEN && it.scopeId == null
            }, "owner_model_observed_assurance_mismatch")
        model.invocation.verifyLive()
        val definition = plan.assembly.plan
        val graph = definition.jvmProduced ?: throw CliProblem("owner_model_declared_plan_unavailable",
            "The complete observed parent has no executable declared preview; its unsupported obligations must be retained.")
        val imported = requireNotNull(model.jvmProducedGraph)
        val observedEvidence = ObservedExportPlanEvidence(definition.binding, model.invocation.identity, model.evidenceIdentity)
        ownerModelRequire(plan.assembly.provenance.observedAfter === model && definition.binding.ownerId == runtime.ownerId &&
            definition.binding.ownerId == model.namespace.ownerId && definition.binding.workspaceId == model.namespace.workspaceId &&
            definition.binding.worktreeId == model.namespace.worktreeId && definition.slots.size == 2 &&
            definition.requiredRecipientClientIds == listOf(definition.binding.clientId) &&
            graph.graphDigest == imported.digest && graph.modelDigest == model.identity.modelDigest &&
            graph.originalDeadlineIdentity == originalDeadline.identity && graph.originalDeadlineMillis == originalDeadline.durationMillis &&
            graph.observedExport == observedEvidence)
        val ids = Catalog().ids.sorted()
        ownerModelRequire(OwnerFullCatalog1665.accepts(ids) && definition.slots.all { slot ->
            slot.input.snapshot == model.snapshot && slot.input.requiredRuleIds == ids && !slot.input.context.fullProject &&
                slot.input.execution == null && slot.input.outputRoot == null &&
                slot.input.context.policies[OBSERVED_ASSURANCE_POLICY] == OBSERVED_EXPORT_PROFILE &&
                slot.input.context.policies[OBSERVED_INVOCATION_POLICY] == model.invocation.identity &&
                slot.input.context.policies[OBSERVED_MODEL_EVIDENCE_POLICY] == model.evidenceIdentity &&
                slot.input.context.policies[OBSERVED_CLOSURE_POLICY] == CONFIGURATION_INPUT_CLOSURE_UNPROVEN &&
                observedEvidence.policies().all { slot.input.context.policies[it.key] == it.value } &&
                CONFIGURATION_INPUT_CLOSURE_UNPROVEN in slot.reasons
        } && definition.slots.map { it.slotId }.toSet() == setOf(graph.producer.slotId, graph.consumer.slotId),
            "owner_model_whole_declaration_mismatch")
        ownerModelRequire(graph.producer.compilationId == imported.producerCompilationId &&
            graph.consumer.compilationId == imported.consumerCompilationId &&
            graph.requiredRulesDigest == ownerProducedDigest(ContractJson.encodeToJsonElement(ids)))
        synchronized(lock) {
            ownerModelRequire(retainedPlan == null && retainedModel == null && generation != null)
            retainedPlan = plan; retainedModel = model
        }
        checkpoint()
    }

    override fun registerProduced(attempt: ProducedExecutionAttempt, compilation: JvmProducedPreviewCompilation,
                                  prepared: PreparedJvmLiveModelInput) {
        // Reserve before IO. Failed callbacks are sticky tombstones, not a route for replay.
        val value = synchronized(lock) {
            ownerModelRequire(retainedModel === compilation.binding.observedModel && retainedPlan != null &&
                prepared.originalDeadline === originalDeadline && attempts.size < 2 &&
                attempt.execution.slotId !in attempts, "owner_model_foreign_or_replayed_attempt")
            if (admission == null) { admission = attempt.admission; preparation = attempt.preparation }
            ownerModelRequire(admission === attempt.admission && preparation === attempt.preparation)
            Attempt(attempt, compilation, prepared).also { attempts[attempt.execution.slotId] = it }
        }
        verifyAttempt(value)
        retainDeclaredArchives(attempt.preparation)
        synchronized(lock) { ownerModelRequire(attempts[attempt.execution.slotId] === value); value.registered = true }
    }

    override fun register(materialization: ModelCompilerJobMaterialization) {
        val value = synchronized(lock) {
            val found = attempts.values.singleOrNull { it.prepared.original == materialization.original &&
                it.attempt.effective == materialization.archived } ?: throw CliProblem("owner_model_attempt_unregistered",
                "A materialization requires the command's exact typed declared attempt.")
            ownerModelRequire(found.registered && !found.materializing && !materializations.containsKey(materialization))
            found.materializing = true
            found
        }
        verifyAttempt(value)
        verifyRelations(value, materialization)
        val roots = listOf(materialization.jobRoot, materialization.sourceRoot, materialization.classpathRoot,
            materialization.outputRoot, materialization.snapshotRoot, materialization.originalOutputRoot)
            .map { wire -> OwnerModelRootPin.capture(Path.of(wire.path())).also { ownerModelRequire(it.wire == wire) } }
        val source = snapshotTree(materialization.effective)
        val archive = snapshotTree(materialization.archived)
        val dependencies = dependencyTree(materialization.effective, Path.of(materialization.classpathRoot.path()))
        val archivedDependencies = materialization.originalClasspathRoot?.let { wire ->
            dependencyTree(materialization.archived, Path.of(wire.path())).also { ownerModelRequire(it.root.wire == wire) }
        }
        ownerModelRequire((archivedDependencies == null) == materialization.archived.context.classpath.isEmpty())
        ownerModelRequire(ownerModelNames(Path.of(materialization.outputRoot.path()), 0).isEmpty(), "owner_model_output_not_fresh")
        val originalKeys = archive.files.values.map { it.fileKey } + archivedDependencies?.files.orEmpty().values.map { it.fileKey }
        val copiedKeys = source.files.values.map { it.fileKey } + dependencies.files.values.map { it.fileKey } +
            roots.take(4).map { it.fileKey }
        ownerModelRequire(copiedKeys.distinct().size == copiedKeys.size && copiedKeys.none { it in originalKeys },
            "owner_model_materialization_alias")
        val retained = Materialization(value, materialization, roots, source, dependencies, archive, archivedDependencies)
        checkpoint()
        synchronized(lock) {
            ownerModelRequire(copiedKeys.none { it in materializedKeys } && materializations.size < 2)
            materializedKeys.addAll(copiedKeys)
            materializations[materialization] = retained
        }
        verify(materialization)
    }

    override fun verify(materialization: ModelCompilerJobMaterialization) {
        // Deliberately no active child/ticket lookup: SAME finished producer/token may reverify K.
        val retained = synchronized(lock) { materializations[materialization] }
            ?: throw CliProblem("owner_model_materialization_unregistered", "Only this command's retained materialization can be reverified.")
        ownerModelRequire(retained.value === materialization)
        verifyAttempt(retained.attempt)
        verifyRelations(retained.attempt, materialization, reserveJob = false)
        retained.roots.forEach { it.verify() }
        retained.source.verify(::checkpoint); retained.dependencies.verify(::checkpoint)
        retained.archive.verify(::checkpoint); retained.archivedDependencies?.verify(::checkpoint)
        verifyMembers(retained)
        ownerModelRequire(ownerModelNames(Path.of(materialization.jobRoot.path()), 3) == setOf("sources", "classpath", "classes"))
        retained.roots.forEach { it.verify() }
        checkpoint()
    }

    private fun verifyAttempt(value: Attempt) {
        checkpoint(); workbench.verifyOwnerCode(); checkpoint()
        val plan = synchronized(lock) { requireNotNull(retainedPlan) }
        val model = synchronized(lock) { requireNotNull(retainedModel) }
        ownerModelRequire(model.invocation.originalDeadline === originalDeadline &&
            model.assuranceProfile == OBSERVED_EXPORT_PROFILE)
        model.invocation.verifyLive()
        val attempt = value.attempt
        val graph = requireNotNull(plan.assembly.plan.jvmProduced)
        val slot = plan.assembly.plan.slots.single { it.slotId == attempt.execution.slotId }
        val declared = if (slot.slotId == graph.producer.slotId) graph.producer else graph.consumer
        val archive = attempt.preparation.archive(slot.slotId)
        val p = value.prepared.original
        val certificate = attempt.execution.certificate
        ownerModelRequire(attempt.admission === admission && attempt.preparation === preparation &&
            attempt.preparation.admission === attempt.admission && attempt.admission.definition == plan.assembly.plan &&
            attempt.admission.planDigest == plan.assembly.planDigest && attempt.preparation.archives.size == 2 &&
            attempt.preparation.archives.map { it.identity.slotId } == plan.assembly.plan.slots.map { it.slotId } &&
            attempt.preparation.archives.all { it.admission === attempt.admission } &&
            archive.original == slot.input && archive.identity.declarationInputKey == slot.input.analysisKey() &&
            value.compilation.binding.observedModel === model && value.compilation.binding.compilationId == declared.compilationId &&
            value.prepared.bindingIdentity == value.compilation.binding.identity &&
            value.prepared.argumentLayoutDigest == declared.argumentLayoutDigest &&
            value.prepared.originalDeadline === originalDeadline && attempt.original == p && attempt.execution.input == p &&
            p.copy(context = slot.input.context) == slot.input && p.context == value.compilation.context.copy(policies = p.context.policies) &&
            slot.input.context.policies.all { p.context.policies[it.key] == it.value } &&
            p.context.compilerArguments == slot.input.context.compilerArguments &&
            p.context.compilationFiles == slot.input.context.compilationFiles && OwnerFullCatalog1665.accepts(p.requiredRuleIds) &&
            p.execution == null && p.outputRoot == null && !p.context.fullProject)
        val producer = if (slot.slotId == graph.consumer.slotId) synchronized(lock) { attempts[graph.producer.slotId] }
            ?: throw CliProblem("owner_model_producer_custody_unavailable", "Consumer requires this exact retained producer.") else null
        val expected = PlanExecutionCertificate(attempt.admission.planId, attempt.admission.planDigest, slot.slotId,
            certificate.reviewId, ownerProducedDigest(ContractJson.encodeToJsonElement(slot)), slot.input.analysisKey(),
            graph.graphDigest, archive.identity.digest(), p.analysisKey(), requireNotNull(exactPlanContextIdentity(p)),
            value.compilation.binding.identity.digest, attempt.admission.providerKey, attempt.admission.originInstance,
            originalDeadline.identity, producer?.attempt?.execution?.reviewId, producer?.attempt?.workKey,
            if (producer == null) null else certificate.producerPublicationCertificate,
            if (producer == null) null else certificate.producedManifest, observedExport = graph.observedExport)
        ownerModelRequire(certificate == expected && certificate.reviewId.isNotBlank() && certificate.reviewId.length <= 256 &&
            attempt.workKey == certificate.workKey() && attempt.resolutionCertificateId == expected.digest() && attempt.attempt == 1L &&
            attempt.originInstance == attempt.admission.originInstance && value.prepared.compilerRuleIds.isNotEmpty() &&
            value.prepared.compilerRuleIds == value.prepared.compilerRuleIds.distinct().sorted() &&
            value.prepared.compilerRuleIds.all { it in p.requiredRuleIds && it in runtime.provider.supportedRuleIds })
        if (producer != null) {
            val manifest = certificate.producedManifest
            val produced = requireNotNull(manifest)
            val generation = synchronized(lock) { requireNotNull(generation) }
            ownerModelRequire(producer.registered && Digests.valid(requireNotNull(certificate.producerPublicationCertificate)) &&
                produced.graphDigest == graph.graphDigest && produced.snapshotId == model.snapshot.snapshotId &&
                produced.moduleId == graph.moduleId && produced.producerCompilationId == graph.producer.compilationId &&
                produced.consumerCompilationId == graph.consumer.compilationId && produced.toolchainDigest == graph.toolchainDigest &&
                produced.producerArgumentLayoutDigest == graph.producer.argumentLayoutDigest &&
                produced.consumerArgumentLayoutDigest == graph.consumer.argumentLayoutDigest &&
                produced.producerBindingDigest == producer.compilation.binding.identity.digest &&
                produced.applicationGeneration == generation.launch.identity().generation() && produced.applicationSequence == 1 &&
                p.context.classpath.getOrNull(graph.classpathIndex) == produced.completeJar)
            // Serialized publication data is corroboration only. The bridge/compiler alone own
            // the real accepted-success capability; this custody hook cannot manufacture one.
        }
        val source = Path.of(archive.frozen.materializedRoot)
        val work = source.parent.parent.resolve(attempt.workKey.removePrefix("sha256:"))
        val expectedOutput = source.parent.parent.parent.resolve("outputs").resolve(attempt.workKey.removePrefix("sha256:"))
            .resolve("attempt-${attempt.attempt}-${attempt.admission.originInstance}")
        val k = attempt.effective
        ownerModelRequire(k.materializedRoot == work.resolve("sources").toString() && k.outputRoot == expectedOutput.toString() &&
            k.copy(materializedRoot = p.materializedRoot, outputRoot = p.outputRoot, context = k.context.copy(classpath = p.context.classpath)) == p &&
            k.context.classpath == p.context.classpath.mapIndexed { index, artifact -> artifact.copy(path = work.resolve("classpath/artifact-$index.jar").toString()) })
        attempt.preparation.archives.zip(plan.assembly.plan.slots).forEach { (declaredArchive, declaredSlot) ->
            val key = ownerProducedDigest(ContractJson.encodeToJsonElement(listOf(PRODUCED_JVM_PLAN_PROFILE,
                "declared-archive", attempt.admission.planId, declaredSlot.slotId, declaredSlot.input.analysisKey())))
            val root = workbench.state.root.resolve("broker/inputs").resolve(key.removePrefix("sha256:"))
            val frozen = declaredSlot.input.copy(materializedRoot = root.resolve("sources").toString(),
                context = declaredSlot.input.context.copy(classpath = declaredSlot.input.context.classpath.mapIndexed { index, artifact ->
                    artifact.copy(path = root.resolve("classpath/artifact-$index.jar").toString())
                }))
            ownerModelRequire(declaredArchive.admission === attempt.admission && declaredArchive.original == declaredSlot.input &&
                declaredArchive.identity.slotId == declaredSlot.slotId && declaredArchive.identity.archiveKey == key &&
                declaredArchive.identity.declarationInputKey == declaredSlot.input.analysisKey() && declaredArchive.frozen == frozen &&
                declaredArchive.readInput() == frozen, "owner_model_whole_declared_archive_mismatch")
            declaredArchive.verify()
        }
        synchronized(lock) { declaredCaptures }?.forEach(::verifyDeclaration)
        attempt.verifyFrozen() // Retained kernel archives ONLY; no old model-source/artifact rereads.
        checkpoint()
    }

    private fun retainDeclaredArchives(request: ProducedPlanPreparationRequest) {
        val capture = synchronized(lock) {
            ownerModelRequire(preparation === request)
            if (declaredCaptures != null) false else {
                ownerModelRequire(!declaredCaptureAttempted, "owner_model_declared_capture_replayed")
                declaredCaptureAttempted = true
                true
            }
        }
        if (!capture) return
        val values = request.archives.map { archive ->
            checkpoint()
            val root = OwnerModelRootPin.capture(Path.of(archive.frozen.materializedRoot).parent)
            val metadata = OwnerModelFilePin.capture(root.path.resolve("input.json"), 8_388_608, ::checkpoint)
            val decoded = ContractJson.decodeFromString<AnalysisInput>(strictJsonDocument(metadata.read(8_388_608, ::checkpoint),
                "owner_model_declared_metadata_invalid"))
            ownerModelRequire(decoded == archive.frozen, "owner_model_declared_metadata_mismatch")
            val source = snapshotTree(archive.frozen)
            val dependencies = if (archive.frozen.context.classpath.isEmpty()) null else
                dependencyTree(archive.frozen, root.path.resolve("classpath"))
            ownerModelRequire(archive.identity.byteCount == source.bytes + (dependencies?.bytes ?: 0L),
                "owner_model_declared_bytes_mismatch")
            Declaration(archive, root, metadata, source, dependencies).also(::verifyDeclaration)
        }
        val keys = values.flatMap { value -> listOf(value.root.fileKey, value.metadata.fileKey, value.source.root.fileKey) +
            value.source.files.values.map { it.fileKey } + value.dependencies?.let { tree ->
                listOf(tree.root.fileKey) + tree.files.values.map { it.fileKey }
            }.orEmpty() }
        ownerModelRequire(keys.distinct().size == keys.size, "owner_model_declared_archive_alias")
        synchronized(lock) {
            ownerModelRequire(declaredCaptures == null && preparation === request)
            declaredCaptures = Collections.unmodifiableList(values)
        }
    }

    private fun verifyDeclaration(value: Declaration) {
        checkpoint()
        ownerModelRequire(synchronized(lock) { preparation?.archives?.any { it === value.value } } == true)
        value.value.verify(); value.root.verify(); value.metadata.verify(::checkpoint)
        value.source.verify(::checkpoint); value.dependencies?.verify(::checkpoint)
        ownerModelRequire(ownerModelNames(value.root.path, 3) ==
            (setOf("sources", "input.json") + if (value.dependencies == null) emptySet() else setOf("classpath")),
            "owner_model_declared_topology_changed")
        value.root.verify(); checkpoint()
    }

    private fun verifyRelations(value: Attempt, m: ModelCompilerJobMaterialization, reserveJob: Boolean = true) {
        checkpoint()
        val request = synchronized(lock) { requireNotNull(generation) }
        val root = Path.of(request.launch.generationRoot().path())
        val job = Path.of(m.jobRoot.path())
        val number = job.fileName.toString().toIntOrNull()
        val graph = requireNotNull(synchronized(lock) { requireNotNull(retainedPlan) }.assembly.plan.jvmProduced)
        val expectedNumber = if (value.attempt.execution.slotId == graph.producer.slotId) 1 else 2
        ownerModelRequire(number == expectedNumber && job == root.resolve("jobs/$number") &&
            m.sourceRoot.path() == job.resolve("sources").toString() && m.classpathRoot.path() == job.resolve("classpath").toString() &&
            m.outputRoot.path() == job.resolve("classes").toString() && m.original === value.prepared.original &&
            m.original == value.attempt.original && m.archived == value.attempt.effective &&
            m.input == m.archived.copy(requiredRuleIds = value.prepared.compilerRuleIds, execution = null) &&
            m.snapshotRoot.path() == m.archived.materializedRoot && m.originalOutputRoot.path() == m.archived.outputRoot &&
            m.effective == m.input.copy(materializedRoot = m.sourceRoot.path(), outputRoot = m.outputRoot.path(),
                context = m.input.context.copy(classpath = m.input.context.classpath.mapIndexed { index, artifact ->
                    artifact.copy(path = job.resolve("classpath/artifact-$index.jar").toString())
                })) && m.compilationPaths == value.prepared.executableCompilationFiles &&
            m.compilationPaths == value.compilation.binding.executableCompilationFiles &&
            m.effective.context.compilerArguments == value.attempt.original.context.compilerArguments &&
            m.effective.snapshot == value.attempt.original.snapshot && !m.effective.context.fullProject &&
            m.originalClasspathRoot?.path() == (if (m.archived.context.classpath.isEmpty()) null else
                Path.of(m.archived.materializedRoot).parent.resolve("classpath").toString()))
        if (reserveJob) synchronized(lock) { ownerModelRequire(jobs.add(expectedNumber), "owner_model_job_replayed") }
    }

    private fun snapshotTree(input: AnalysisInput): OwnerModelTreePin {
        ownerModelRequire(input.snapshot.files.size <= ModelWire.MAX_SNAPSHOT_FILES)
        val tree = OwnerModelTreePin.capture(Path.of(input.materializedRoot),
            ModelWire.MAX_SNAPSHOT_FILES * ModelWire.MAX_PATH_DEPTH, ModelWire.MAX_SNAPSHOT_BYTES,
            ModelWire.MAX_SOURCE_BYTES.toLong(), ::checkpoint)
        val expected = input.snapshot.files.associateBy { it.path }
        ownerModelRequire(tree.files.keys == expected.keys && tree.directories.keys == ownerModelDirectories(expected.keys) &&
            tree.files.all { (path, pin) -> expected.getValue(path).let { it.digest == pin.digest && it.size == pin.size } },
            "owner_model_whole_snapshot_changed")
        return tree
    }

    private fun dependencyTree(input: AnalysisInput, root: Path): OwnerModelTreePin {
        ownerModelRequire(input.context.classpath.size <= ModelWire.MAX_DEPENDENCIES)
        val tree = OwnerModelTreePin.capture(root, ModelWire.MAX_DEPENDENCIES, ModelWire.MAX_DEPENDENCY_TOTAL_BYTES,
            ModelWire.MAX_DEPENDENCY_BYTES, ::checkpoint)
        ownerModelRequire(tree.directories.isEmpty() && tree.files.keys == input.context.classpath.indices.map { "artifact-$it.jar" }.toSet() &&
            input.context.classpath.withIndex().all { (index, artifact) ->
                artifact.path == root.resolve("artifact-$index.jar").toString() && tree.files.getValue("artifact-$index.jar").digest == artifact.digest
            }, "owner_model_whole_classpath_changed")
        return tree
    }

    private fun verifyMembers(value: Materialization) {
        val m = value.value
        ownerModelRequire(m.snapshotMembers == m.effective.snapshot.files.map { member ->
            val pin = value.source.files.getValue(member.path)
            ModelWire.Member(member.path, member.digest, member.size, pin.fileKey)
        } && m.dependencyMembers == m.effective.context.classpath.mapIndexed { index, artifact ->
            val pin = value.dependencies.files.getValue("artifact-$index.jar")
            ModelWire.Dependency(artifact.coordinate, artifact.digest, pin.size, pin.fileKey)
        })
    }

    /** Detached exact callback evidence; it does not mint publication, success or receipt authority. */
    fun evidence(): JsonObject = synchronized(lock) {
        buildJsonObject {
            put("schema", "ktchecker/owner-model-command-custody/1")
            put("originalDeadlineIdentity", originalDeadline.identity)
            put("retainedDeclaredSlots", retainedPlan?.assembly?.plan?.slots?.size ?: 0)
            put("registeredAttempts", attempts.size); put("registeredMaterializations", materializations.size)
            put("wholeDeclaredArchives", ContractJson.encodeToJsonElement(declaredCaptures.orEmpty().map { it.value.identity }))
            putJsonArray("executions") { attempts.values.forEach { value -> add(buildJsonObject {
                put("slotId", value.attempt.execution.slotId)
                put("registered", value.registered); put("materializationAttempted", value.materializing)
                put("declarationToExecution", ContractJson.encodeToJsonElement(value.attempt.execution.certificate))
                put("wholeKernelInputKey", value.attempt.effective.analysisKey())
                put("compilerRuleIds", ContractJson.encodeToJsonElement(value.prepared.compilerRuleIds))
                put("materializationCount", materializations.values.count { it.attempt === value })
            }) } }
            put("qualified", false); put("usefulComplete", false)
        }
    }

    private fun checkpoint() { clock.checkpoint() }
}

internal fun ownerModelDirectories(files: Collection<String>): Set<String> = buildSet {
    files.forEach { file ->
        var parent = Path.of(file).parent
        while (parent != null) { add(parent.toString()); parent = parent.parent }
    }
}
