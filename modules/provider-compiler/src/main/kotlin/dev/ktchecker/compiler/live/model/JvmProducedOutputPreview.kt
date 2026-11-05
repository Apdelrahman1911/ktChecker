package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/**
 * Explicit owner-only same-generation execution preview. This is not a flag, imported-model
 * replacement, cache capability, or ordinary route activation. Capture originalDeadline BEFORE
 * export/preparation. Both independent compilation attempts MUST retain that SAME clock object.
 * Imported context/identity/issues remain unchanged and incomplete throughout this preview.
 */
class KotlinJvmProducedOutputPreview private constructor(
    internal val origin: JvmCompilerModelOrigin,
    internal val installation: KotlinJvmToolchain,
    val originalDeadline: OwnedApplicationDeadline,
) {
    /** Strict compatibility views. Neither getter can launder an observed origin as verified. */
    val model: VerifiedProjectModel get() = origin.verified
    val observedModel: ObservedProjectModel? get() = origin.observed
    val graph: VerifiedJvmProducedGraph get() = requireNotNull(origin.verified.jvmProducedGraph)
    val observedGraph: ObservedJvmProducedGraph? get() = origin.observed?.jvmProducedGraph
    val graphIdentity: JvmProducedPreviewGraphIdentity = origin.graphIdentity()
        ?: liveFailure("compiler_model_produced_graph_unavailable")
    private val application = AtomicReference<KotlinJvmModelCompilerApplication?>(null)
    private val declarations = ConcurrentHashMap<String, JvmProducedDeclaredArchive>()
    private val declarationAdmission = AtomicReference<Any?>(null)

    /** Owner-only initial capture, before producer work exists. These exact D objects, not their
     * digests or paths, remain the authority across the held→installed archive transition. */
    fun retainDeclaredArchive(admissionIdentity: Any, archiveIdentity: Any, compilationId: String, archiveKey: String,
        original: AnalysisInput, frozen: AnalysisInput, held: AnalysisInput,
        currentRead: () -> AnalysisInput, verifyFrozen: () -> Unit): JvmProducedDeclaredArchive {
        checkpoint()
        liveCheck(compilationId in graphIdentity.compilationIds, "compiler_model_produced_obligation_unfulfilled")
        val raw = requireNotNull(origin.context(compilationId).context)
        val detached = freezeModelLiveInput(original)
        val layout = requireNotNull(origin.jvmArgumentLayout(compilationId))
        origin.requireObservedPolicies(detached.context)
        val controllerPolicies = when (origin) {
            is JvmCompilerModelOrigin.VerifiedOrigin -> setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan")
            is JvmCompilerModelOrigin.ObservedOneShotOrigin -> {
                liveCheck(detached.context.policies[OBSERVED_PLAN_EVIDENCE_POLICY]?.let(Digests::valid) == true,
                    "compiler_model_observed_evidence_mismatch")
                setOf("ktchecker.catalog-inventory", "ktchecker.activation-plan", OBSERVED_PLAN_EVIDENCE_POLICY)
            }
        }
        val additions = detached.context.policies.keys - raw.policies.keys
        liveCheck(detached.snapshot == origin.snapshot && detached.materializedRoot == layout.originalSourceRoot &&
            detached.context == raw.copy(policies = detached.context.policies) &&
            raw.policies.all { detached.context.policies[it.key] == it.value } &&
            additions == controllerPolicies &&
            detached.context.policies["ktchecker.catalog-inventory"]?.let(Digests::valid) == true &&
            !detached.context.policies["ktchecker.activation-plan"].isNullOrBlank() &&
            detached.outputRoot == null && detached.execution == null,
            "compiler_model_produced_declaration_mismatch")
        val priorAdmission = declarationAdmission.get()
        liveCheck(priorAdmission === admissionIdentity || priorAdmission == null && declarationAdmission.compareAndSet(null, admissionIdentity),
            "compiler_model_produced_archive_foreign_identity")
        val reads = ProducedDeclaredArchiveReads(this, admissionIdentity, archiveIdentity, archiveKey,
            detached, frozen, held, currentRead, verifyFrozen)
        val value = JvmProducedDeclaredArchive(this, compilationId, reads)
        // No re-enrollment of a fresh/equal D after failure, supersession, or original expiry.
        liveCheck(declarations.putIfAbsent(compilationId, value) == null, "compiler_model_produced_declaration_replayed")
        value.verify(::checkpoint)
        return value
    }

    fun bindCompilationFromDeclaration(declaration: JvmProducedDeclaredArchive, admissionIdentity: Any,
        archiveIdentity: Any, producer: ModelProducedOutputArtifact? = null): JvmProducedPreviewCompilation {
        declaration.requireIdentity(this, admissionIdentity, archiveIdentity)
        if (origin is JvmCompilerModelOrigin.ObservedOneShotOrigin) liveCheck(
            declarations.keys == graphIdentity.compilationIds.toSet(), "compiler_model_observed_whole_declaration_required")
        liveCheck(declarations[declaration.compilationId] === declaration, "compiler_model_produced_archive_foreign_identity")
        return bindCompilationChecked(declaration.compilationId, producer, declaration)
    }

    /** The result's context is a NEW explicit resolution, before P, never a rewritten import. */
    fun bindCompilation(compilationId: String, producer: ModelProducedOutputArtifact? = null): JvmProducedPreviewCompilation {
        liveCheck(origin is JvmCompilerModelOrigin.VerifiedOrigin, "compiler_model_observed_declared_archive_required")
        liveCheck(declarations.isEmpty(), "compiler_model_produced_archive_capability_required")
        return bindCompilationChecked(compilationId, producer, null)
    }

    private fun bindCompilationChecked(compilationId: String, producer: ModelProducedOutputArtifact?,
        declaration: JvmProducedDeclaredArchive?): JvmProducedPreviewCompilation {
        checkpoint()
        liveCheck(compilationId in graphIdentity.compilationIds && (producer != null) == (compilationId == graphIdentity.consumerCompilationId),
            "compiler_model_produced_obligation_unfulfilled")
        liveCheck(producer == null || producer.success.preview === this, "compiler_model_produced_foreign_identity")
        // Foreign capabilities fail by object identity before touching their old clocks/custody.
        // Even a still-live, byte-identical previous import is not this resolution's producer.
        producer?.verify(::checkpoint)
        val raw = requireNotNull(origin.context(compilationId).context)
        val compilation = requireNotNull(origin.compilation(compilationId))
        val artifacts = compilation.classpath.mapIndexed { index, artifact ->
            if (producer != null && index == graphIdentity.reference.classpathIndex) producer.artifact else artifact
        }
        val lineage = producer?.success?.lineage(graphIdentity.reference)?.let(::listOf).orEmpty()
        val resolutionDigest = producedJsonDigest(buildJsonObject {
            put("schema", JVM_PRODUCED_OUTPUT_PREVIEW); put("graph", graphIdentity.digest)
            put("rawContext", producedJsonDigest(ContractJson.encodeToJsonElement(raw)))
            put("originalObligations", ContractJson.encodeToJsonElement(origin.issues))
            put("resolvedArtifacts", ContractJson.encodeToJsonElement(artifacts))
            producer?.let {
                put("successfulProducerResult", it.success.resultDigest); put("completeOutput", it.success.outputDigest)
                put("producerBinding", it.success.bindingDigest); put("producerRequest", it.success.requestDigest)
                put("producerGeneration", it.success.generation); put("producerSequence", it.success.sequence)
            }
        })
        val context = raw.copy(classpath = Collections.unmodifiableList(artifacts.map { artifact ->
            ArtifactIdentity(artifact.coordinate, requireNotNull(artifact.digest), artifact.path)
        }), dependencyDigest = resolutionDigest, fullProject = false,
            policies = Collections.unmodifiableMap(raw.policies + mapOf(
                "ktchecker.jvm-produced-preview" to graphIdentity.digest,
                "ktchecker.jvm-produced-resolution" to resolutionDigest)))
        val projection = ModelWire.ProducedExecution(graphIdentity.digest, compilationId,
            producedJsonDigest(ContractJson.encodeToJsonElement(raw)), resolutionDigest, lineage)
        val resolution = JvmProducedCompilationResolution(this, compilationId, raw, context,
            Collections.unmodifiableList(artifacts), producer, projection, declaration)
        val binding = KotlinJvmModelBinding.verifyAndBindProducedPreview(origin, compilationId, installation, resolution, ::checkpoint)
        return JvmProducedPreviewCompilation(binding, context)
    }

    internal fun checkpoint() {
        origin.requireOriginalDeadline(originalDeadline)
        modelDeadlineCheckpoint(originalDeadline)
    }
    internal fun bindApplication(value: KotlinJvmModelCompilerApplication) {
        checkpoint()
        origin.requireOriginalDeadline(value.request.originalDeadline)
        liveCheck(value.request.installation === installation && value.request.generation.profile() == ModelWire.PRODUCED_PROFILE &&
            value.request.namespace.ownerId == origin.namespace.ownerId && value.request.namespace.workspaceId == origin.namespace.workspaceId &&
            value.request.namespace.worktreeId == origin.namespace.worktreeId, "compiler_model_produced_generation_mismatch")
        val old = application.get()
        liveCheck(old === value || (old == null && application.compareAndSet(null, value)), "compiler_model_produced_generation_mismatch")
        value.checkLive(); checkpoint()
    }
    internal fun matchesApplication(value: KotlinJvmModelCompilerApplication): Boolean = application.get() === value

    companion object {
        /** Existing verified factory remains statically strict and unchanged in assurance. */
        fun create(model: VerifiedProjectModel, installation: KotlinJvmToolchain,
            originalDeadline: OwnedApplicationDeadline): KotlinJvmProducedOutputPreview {
            modelDeadlineCheckpoint(originalDeadline)
            val graph = model.jvmProducedGraph ?: liveFailure("compiler_model_produced_graph_unavailable")
            liveCheck(graph.modelDigest == model.identity.modelDigest && !model.context(graph.consumerCompilationId,
                model.identity.configurationDigest).context!!.fullProject, "compiler_model_produced_identity_mismatch")
            return KotlinJvmProducedOutputPreview(JvmCompilerModelOrigin.VerifiedOrigin(model), installation, originalDeadline)
        }

        internal fun createObserved(model: ObservedProjectModel, installation: KotlinJvmToolchain,
            originalDeadline: OwnedApplicationDeadline): KotlinJvmProducedOutputPreview {
            val origin = JvmCompilerModelOrigin.ObservedOneShotOrigin(model)
            origin.requireOriginalDeadline(originalDeadline)
            val graph = model.jvmProducedGraph ?: liveFailure("compiler_model_observed_graph_unavailable")
            liveCheck(graph.modelDigest == model.identity.modelDigest && graph.evidenceIdentity == model.evidenceIdentity &&
                graph.invocationIdentity == model.invocation.identity && model.issues.any {
                    it.kind == CONFIGURATION_INPUT_CLOSURE_UNPROVEN && it.scopeId == null
                }, "compiler_model_observed_evidence_mismatch")
            graph.compilationIds.forEach { origin.requireObservedPolicies(requireNotNull(origin.context(it).context)) }
            return KotlinJvmProducedOutputPreview(origin, installation, originalDeadline)
        }
    }
}

/** Distinct owner-only observed factory. No verified model/graph or ordinary admission is created. */
object ObservedJvmProducedOutputPreview {
    fun create(model: ObservedProjectModel, installation: KotlinJvmToolchain,
        originalDeadline: OwnedApplicationDeadline): KotlinJvmProducedOutputPreview =
        KotlinJvmProducedOutputPreview.createObserved(model, installation, originalDeadline)
}

class JvmProducedPreviewCompilation internal constructor(val binding: KotlinJvmModelBinding, val context: AnalysisContext)

/** Complete original→resolved dependency relation retained by the binding, not by serialized policies. */
internal class JvmProducedCompilationResolution(
    val preview: KotlinJvmProducedOutputPreview,
    val compilationId: String,
    val rawContext: AnalysisContext,
    val context: AnalysisContext,
    val artifacts: List<ModelArtifact>,
    val producer: ModelProducedOutputArtifact?,
    val wire: ModelWire.ProducedExecution,
    val declarationArchive: JvmProducedDeclaredArchive? = null,
) {
    /** Actual read locations only. Original model artifact/path identities stay in P/binding. */
    fun dependencyReadArtifacts(checkpoint: () -> Unit): List<ArtifactIdentity> {
        val declaration = declarationArchive ?: return context.classpath
        val retained = declaration.verify(checkpoint).context.classpath.iterator()
        val index = preview.graphIdentity.reference.classpathIndex
        val result = context.classpath.mapIndexed { ordinal, artifact ->
            if (producer != null && ordinal == index) artifact else retained.next()
        }
        liveCheck(!retained.hasNext(), "compiler_model_produced_archive_classpath_mismatch")
        return result
    }

    fun verify(origin: JvmCompilerModelOrigin, installation: KotlinJvmToolchain, compilationId: String, checkpoint: () -> Unit) {
        checkpoint(); preview.checkpoint()
        liveCheck(preview.origin === origin && preview.installation === installation && this.compilationId == compilationId &&
            rawContext == origin.context(compilationId).context &&
            !context.fullProject && context.compilerArguments == rawContext.compilerArguments &&
            context.modelDigest == rawContext.modelDigest && context.configurationDigest == rawContext.configurationDigest &&
            context.toolchainDigest == rawContext.toolchainDigest && context.compilationFiles == rawContext.compilationFiles,
            "compiler_model_produced_identity_mismatch")
        origin.requireObservedPolicies(rawContext)
        origin.requireObservedPolicies(context)
        if (origin is JvmCompilerModelOrigin.ObservedOneShotOrigin) liveCheck(declarationArchive != null,
            "compiler_model_observed_declared_archive_required")
        declarationArchive?.let { archive ->
            liveCheck(archive.preview === preview && archive.compilationId == compilationId && archive.original.context.copy(
                policies = rawContext.policies) == rawContext, "compiler_model_produced_archive_foreign_identity")
            archive.verify(checkpoint)
        }
        producer?.verify(checkpoint)
        checkpoint(); preview.checkpoint()
    }
}

/**
 * Nonserialized accepted output capability, constructed under ARM and published ONLY after the
 * runtime returned Accepted and finishAccepted passed. A sealed DTO/code1/output directory alone
 * cannot obtain this object from an application. Success means compiler code0, NOT rule coverage.
 */
class ModelSuccessfulJvmOutput internal constructor(
    internal val permit: ModelCompilerAttemptPermit,
    private val materialized: ModelCompilerJobMaterialization,
    result: Wire.Result,
    observation: ModelWire.Observation,
    acceptedProviderResult: ProviderResult,
    members: List<ModelWire.ProducedMember>,
) {
    internal val preview: KotlinJvmProducedOutputPreview = requireNotNull(permit.prepared.binding.producedResolution).preview
    val compilationId: String = permit.prepared.binding.compilationId
    val bindingDigest: String = permit.prepared.bindingIdentity.digest
    val generation: String = result.generation()
    val sequence: Int = result.sequence()
    val requestDigest: String = result.requestDigest()
    val resultDigest: String = Wire.sha256(Wire.encode(result))
    /** Captured from the complete detached compiler child UNDER ARM, never recomputed after acceptance. */
    val acceptedProviderResultDigest: String = producedProviderResultDigest(acceptedProviderResult)
    val members: List<ModelWire.ProducedMember> = Collections.unmodifiableList(members.toList())
    val outputDigest: String = ModelWire.producedOutputDigest(this.members)
    init {
        ModelWire.requireSealed(observation)
        liveCheck(observation.compilerCode() == 0 && compilationId == preview.graphIdentity.producerCompilationId &&
            materialized.wireRequest.producedExecution()?.producers()?.isEmpty() == true &&
            result.originalKey() == permit.providerInputKey && result.effectiveKey() == materialized.effective.analysisKey(),
            "compiler_model_produced_success_required")
    }
    internal fun verify(checkpoint: () -> Unit) {
        checkpoint(); preview.checkpoint()
        liveCheck(permit.retainsSuccessfulOutput(this) && preview.matchesApplication(permit.application),
            "compiler_model_produced_not_accepted")
        permit.verifySuccessfulCustody(this, materialized, checkpoint)
        permit.application.verifyProducedOutput(materialized, checkpoint)
        liveCheck(materialized.producedMembers() == members, "compiler_model_produced_output_drift")
        checkpoint(); preview.checkpoint()
    }
    internal fun lineage(ref: ModelJvmProducedOutputReference) = ModelWire.ProducedDependency(ref.classpathIndex,
        compilationId, ref.digest(), generation, sequence, bindingDigest, requestDigest, resultDigest, outputDigest, ref.friend)

    /**
     * Owner chooses a NEW retained private directory, outside all compiler/original/JDK roots.
     * Every output entry is copied; no ABI filtering, main/test merging or stale Gradle output.
     * Returned bytes must remain in custody until all dependent attempts finish. No auto-cleanup.
     */
    fun materializeJar(directory: Path): ModelProducedOutputArtifact {
        val checkpoint = { preview.checkpoint() }
        verify(checkpoint)
        val destination = livePath(directory.toString())
        val roots = listOf(livePath(permit.application.request.launch.generationRoot().path()),
            livePath(permit.original.materializedRoot), livePath(permit.archived.materializedRoot).parent,
            livePath(requireNotNull(permit.input.outputRoot)), preview.installation.installationView().runtime.root)
        liveCheck(roots.none { destination.startsWith(it) || it.startsWith(destination) }, "compiler_model_produced_archive_overlap")
        CompilerRootPin.capture(requireNotNull(destination.parent)).verify()
        modelPrivateDirectory(destination)
        val root = CompilerRootPin.capture(destination)
        val jar = destination.resolve("complete-output.jar")
        // Stored entries + fixed DOS timestamp: deterministic, no timezone/deflater differences,
        // manifest Class-Path, implicit source files, index, symlinks or truncated output subsets.
        liveCheck(members.sumOf { it.size() + 128L + 2L * it.path().toByteArray(Charsets.UTF_8).size } <=
            ModelLiveLimits.DEPENDENCY_BYTES - 128, "compiler_model_produced_archive_limit")
        val archiveHash = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(DigestOutputStream(Files.newOutputStream(jar, CREATE_NEW, WRITE, NOFOLLOW_LINKS), archiveHash)).use { output ->
            members.forEach { member ->
                checkpoint()
                val data = if (member.directory()) byteArrayOf() else {
                    val path = livePath(materialized.outputRoot.path()).resolve(member.path())
                    val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use {
                        it.readNBytes((member.size() + 1).toInt())
                    }
                    liveCheck(bytes.size.toLong() == member.size() && Digests.sha256(bytes) == member.digest(),
                        "compiler_model_produced_output_drift")
                    bytes
                }
                val entry = ZipEntry(member.path() + if (member.directory()) "/" else "")
                entry.method = ZipEntry.STORED; entry.size = data.size.toLong(); entry.compressedSize = entry.size
                entry.crc = CRC32().apply { update(data) }.value
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                output.putNextEntry(entry); output.write(data); output.closeEntry()
            }
        }
        verify(checkpoint); root.verify()
        val size = Files.size(jar)
        liveCheck(size in 1..ModelLiveLimits.DEPENDENCY_BYTES, "compiler_model_produced_archive_limit")
        if (jar.fileSystem.supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("r--------"))
        val pin = CompilerFilePin.capture(jar, "sha256:" + HexFormat.of().formatHex(archiveHash.digest()), size, checkpoint = checkpoint)
        return ModelProducedOutputArtifact(this, root, pin).also { it.verify(checkpoint) }
    }
}

/** Byte capsule, not re-enrollment of imported ModelArtifact paths or a cross-generation cache. */
class ModelProducedOutputArtifact internal constructor(
    internal val success: ModelSuccessfulJvmOutput,
    private val root: CompilerRootPin,
    private val pin: CompilerFilePin,
) {
    val artifact = ModelArtifact(ModelWire.producedArtifactCoordinate(success.compilationId), pin.path.toString(), pin.digest, pin.size)
    internal fun verify(checkpoint: () -> Unit) {
        success.verify(checkpoint); root.verify()
        liveCheck(liveNames(livePath(root.root.path()), 1) == setOf("complete-output.jar"), "compiler_model_produced_archive_drift")
        pin.verify(checkpoint); root.verify(); success.verify(checkpoint)
    }
}

internal fun producedJsonDigest(value: JsonElement): String = Digests.sha256(Digests.canonical(value))

/** Exact full detached child identity, separate from coverage qualification or compiler exit code. */
internal fun producedProviderResultDigest(result: ProviderResult): String =
    producedJsonDigest(ContractJson.encodeToJsonElement(result))
