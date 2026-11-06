package dev.ktchecker.compiler.live

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

sealed interface CompilerApplicationOpenResult {
    class Ready internal constructor(val application: KotlinJvmCompilerApplication) : CompilerApplicationOpenResult
    class Unavailable internal constructor(reasons: List<String>, val cleanup: OwnedApplicationRetirement? = null) : CompilerApplicationOpenResult {
        val reasons: List<String> = Collections.unmodifiableList(reasons.distinct())
    }
}

/**
 * The dormant real compiler application core. All transport state/wire drains remain runtime-owned.
 * Only detached inputs, filesystem receipts and results cross this seam; no compiler classes do.
 */
class KotlinJvmCompilerApplication private constructor(
    internal val request: CompilerGenerationRequest,
    private val generationPermit: CompilerGenerationPermit,
    private val session: OwnedApplicationSession,
    private val installationCapture: CompilerGenerationCapture?,
) {
    /** This is the validated detached application HELLO, not native BOUND or cleanup evidence. */
    val hello: Wire.Hello = session.hello
    private val lifecycle = CompilerApplicationLifecycle(request.originalDeadline, generationPermit::revoke)
    private val sequence = AtomicInteger(1)
    private val materializations = ConcurrentHashMap<Int, CompilerJobMaterialization>()

    internal fun checkLive() {
        lifecycle.checkOpen()
        generationPermit.checkLive()
    }

    internal fun newAttemptTicket(originalDeadline: OwnedApplicationDeadline): CompilerApplicationLifecycle.Ticket =
        lifecycle.newTicket(originalDeadline)
    internal fun isAttemptLive(permit: CompilerAttemptPermit): Boolean =
        permit.application === this && lifecycle.isLive(permit.ticket)
    internal fun checkAttemptLive(permit: CompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.checkTicketLive(permit.ticket) }
    internal fun requireActiveAttempt(permit: CompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.requireActive(permit.ticket) }
    internal fun admitAttempt(permit: CompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.admit(permit.ticket) }
    internal fun revokeAttempt(permit: CompilerAttemptPermit) { requireBoundAttempt(permit); lifecycle.revoke(permit.ticket) }

    private fun requireBoundAttempt(permit: CompilerAttemptPermit) {
        liveCheck(permit.application === this && permit.owner === request.owner && permit.provider.uses(this),
            "compiler_live_attempt_binding_mismatch")
    }

    fun cancel() {
        lifecycle.cancel() // Closes host admission BEFORE generation revocation and runtime cancel.
    }

    /** Bound only to this actual app before readiness; no callback or transport I/O under the gate. */
    private fun cancelRevokedGeneration() {
        lifecycle.closeForGenerationRevocation()
        session.cancel()
    }

    /** Healthy idle EOF; the returned disposition is generation cleanup ONLY, never job evidence. */
    fun retire(): OwnedApplicationRetirement {
        lifecycle.closeForRetirement()
        return try { session.retire() } finally { generationPermit.invalidate() }
    }

    /**
     * Mandatory publication boundary: the ENTIRE ProviderResult is created by validateResult while
     * the original request epoch remains ARMED. Accepted.value must be returned unchanged by analyze.
     * Rejected admission throws only fixed codes and cannot cancel another active attempt. Once
     * admitted, failure cancels only this exact active scope; accepted/revoked terminal states detach.
     */
    internal fun compile(permit: CompilerAttemptPermit, cancellation: CancellationToken): OwnedApplicationJobResult<ProviderResult> {
        var admitted = false
        try {
            requireBoundAttempt(permit)
            permit.checkpoint()
            permit.consume() // No directories, materialization hooks or application writes before this.
            admitted = true
            val installation = installationCapture ?: liveFailure("compiler_live_binding_control_no_execution")
            val checkpoint = {
                checkLive(); permit.checkpoint()
                if (Thread.currentThread().isInterrupted) liveFailure("compiler_live_interrupted")
                if (cancellation.isCancelled()) throw CancellationException("compiler_live_cancelled")
            }
            checkpoint()
            val next = sequence.getAndIncrement()
            liveCheck(next <= request.launch.limits().maxJobs(), "compiler_live_job_limit")
            verifyRetained(installation, checkpoint)
            val retainedBytes = materializations.values.sumOf { it.outputBytes() }
            liveCheck(retainedBytes <= CompilerWire.MAX_GENERATION_OUTPUT_BYTES - CompilerWire.MAX_JOB_OUTPUT_BYTES,
                "compiler_live_generation_output_limit")
            permit.verifyOriginal(checkpoint)
            val materialized = materialize(permit, next, checkpoint)
            liveCheck(materializations.putIfAbsent(next, materialized) == null, "compiler_live_materialization_replayed")
            permit.register(materialized, checkpoint)
            val body = materialized.wireRequest
            val job = Wire.Job(request.launch.identity().generation(), Wire.helloDigest(hello), next,
                Wire.jobId(request.launch.identity().generation(), next), permit.originalKey, materialized.effective.analysisKey(),
                permit.originalDeadline.identity, CompilerWire.encodeRequest(body))
            Wire.matchJob(request.launch, hello, job, next)
            CompilerWire.matchRequest(request.launch, request.generation, job, body)

            fun verifyBound(sealedOutput: Boolean) {
                checkpoint()
                validateLiveEffective(request, permit.original, materialized.effective,
                    livePath(materialized.sourceRoot.path()), livePath(materialized.outputRoot.path()))
                permit.verifyOriginal(checkpoint)
                materialized.verifyInputs(checkpoint)
                materialized.verifyOutput(sealedOutput, checkpoint)
                permit.verifyRegistered(materialized, checkpoint)
                // Owner callbacks cannot mutate a frozen input/root/output and then pass on a stale check.
                permit.verifyOriginal(checkpoint)
                materialized.verifyInputs(checkpoint)
                materialized.verifyOutput(sealedOutput, checkpoint)
                verifyRetained(installation, checkpoint)
                generationPermit.verify()
                checkpoint()
            }

            val runtimePermit = OwnedApplicationJobPermit<ProviderResult>(session, job, permit.originalDeadline,
                verifyBeforeWrite = { verifyBound(false) },
                validateResult = { result ->
                    checkpoint()
                    Wire.matchResult(request.launch, hello, job, result)
                    val observed = CompilerWire.decodeObservation(result.payload())
                    CompilerWire.matchObservation(request.launch, request.generation, job, body, result, observed)
                    // Actual input/installation/output checks precede rendering, not an owner-selected renderer.
                    permit.verifyOriginal(checkpoint)
                    materialized.verifyInputs(checkpoint)
                    materialized.sealOutput(checkpoint)
                    verifyBound(true)
                    val completeDetachedResult = renderLiveObservation(permit.original, permit.capture.text,
                        materialized.sourceRoot, request.installation.digest, result, observed)
                    checkpoint()
                    completeDetachedResult
                },
                verifyBeforePublication = { verifyBound(true) })
            val result = session.submit(runtimePermit, cancellation)
            when (result) {
                is OwnedApplicationJobResult.Accepted -> {
                    checkpoint()
                    // State-only terminal commit. No parsing/rendering or value changes after ARM.
                    lifecycle.finishAccepted(permit.ticket)
                }
                is OwnedApplicationJobResult.Unavailable -> lifecycle.failActive(permit.ticket)
            }
            return result
        } catch (failure: CancellationException) {
            if (admitted) lifecycle.failActive(permit.ticket)
            throw failure
        } catch (failure: CompilerApplicationProblem) {
            if (admitted) lifecycle.failActive(permit.ticket)
            throw failure
        } catch (_: Throwable) {
            if (admitted) lifecycle.failActive(permit.ticket)
            liveFailure("compiler_live_application_failed")
        }
    }

    private fun verifyRetained(capture: CompilerGenerationCapture, checkpoint: () -> Unit) {
        checkpoint()
        capture.verify(checkpoint)
        liveNames(livePath(request.launch.generationRoot().path()).resolve("jobs"), request.launch.limits().maxJobs()).also { names ->
            liveCheck(names == materializations.keys.map { it.toString() }.toSet(), "compiler_live_generation_root_drift")
        }
        var outputBytes = 0L
        materializations.toSortedMap().values.forEach { value ->
            checkpoint()
            value.verifyInputs(checkpoint)
            if (value.outputSealed()) value.verifyOutput(true, checkpoint)
            outputBytes += value.outputBytes()
            liveCheck(outputBytes <= CompilerWire.MAX_GENERATION_OUTPUT_BYTES, "compiler_live_generation_output_limit")
        }
        CompilerTree.capture(livePath(request.launch.generationRoot().path()).resolve("tmp"),
            CompilerWire.MAX_TEMP_FILES, CompilerWire.MAX_TEMP_BYTES, false, checkpoint)
        checkpoint()
    }

    private fun materialize(permit: CompilerAttemptPermit, next: Int, checkpoint: () -> Unit): CompilerJobMaterialization {
        val root = livePath(request.launch.generationRoot().path())
        val job = root.resolve("jobs").resolve(next.toString())
        checkpoint(); privateLiveDirectory(job)
        val source = job.resolve("sources")
        checkpoint(); privateLiveDirectory(source)
        val output = job.resolve("classes")
        checkpoint(); privateLiveDirectory(output)
        val sourcePath = source.resolve("Fixture.kt")
        checkpoint()
        Files.newOutputStream(sourcePath, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(permit.capture.bytes()) }
        if (sourcePath.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(sourcePath, PosixFilePermissions.fromString("r--------"))
        }
        checkpoint()
        val originalSource = permit.original.snapshot.files.single()
        val copy = CompilerFilePin.capture(sourcePath, originalSource.digest, originalSource.size, checkpoint = checkpoint)
        liveCheck(copy.fileKey != permit.capture.fileKey, "compiler_live_source_alias")
        val sourceDescriptor = CompilerWire.Source(originalSource.path, originalSource.digest, originalSource.size, copy.fileKey)
        val members = CompilerWire.membersDigest(listOf(sourceDescriptor))
        val effective = liveEffective(request, permit.original, source, output)
        validateLiveEffective(request, permit.original, effective, source, output)
        val jobRoot = CompilerRootPin.capture(job)
        val sourceRoot = CompilerRootPin.capture(source)
        val outputRoot = CompilerRootPin.capture(output)
        val body = CompilerWire.Request(CompilerWire.PROFILE, liveInputIdentity(permit.original, members),
            liveInputIdentity(effective, members), permit.capture.root, jobRoot.root, sourceRoot.root, outputRoot.root,
            listOf(sourceDescriptor), permit.original.context.compilerArguments, effective.context.compilerArguments,
            request.configurationDigest, permit.originalDeadline.identity)
        return CompilerJobMaterialization(permit.original, effective, body, jobRoot, sourceRoot, outputRoot, copy)
    }

    companion object {
        fun open(request: CompilerGenerationRequest, authority: KotlinJvmApplicationAuthority = KotlinJvmApplicationAuthority.DENY,
            cancellation: CancellationToken = CancellationToken.NONE): CompilerApplicationOpenResult {
            fun unavailable(code: String) = CompilerApplicationOpenResult.Unavailable(listOf(code))
            val permit = try { authority.authorizeGeneration(request) } catch (_: Exception) {
                return unavailable("compiler_live_generation_authority_failed")
            } ?: return unavailable("compiler_live_generation_not_authorized")
            var admitted = false
            try {
                permit.consume(request)
                admitted = true // Only this successfully admitted generation may be revoked below.
                val checkpoint = {
                    permit.checkLive()
                    if (Thread.currentThread().isInterrupted) liveFailure("compiler_live_interrupted")
                    if (cancellation.isCancelled()) throw CancellationException("compiler_live_cancelled")
                }
                checkpoint()
                permit.beforeStart()
                checkpoint()
                val captured = CompilerGenerationCapture.capture(request, checkpoint)
                val runtimePermit = OwnedApplicationGenerationPermit(request.owner, request.launch, request.originalDeadline,
                    verifyBeforeStart = { checkpoint(); permit.beforeStart(); captured.verify(checkpoint); checkpoint() },
                    validateHello = { hello ->
                        checkpoint()
                        CompilerWire.matchHello(request.launch, request.generation, hello)
                        captured.verify(checkpoint); permit.verify(); checkpoint()
                    },
                    verifyLive = { checkpoint(); permit.verify(); captured.verify(checkpoint); checkpoint() })
                return when (val opened = OwnedApplicationSessions.open(runtimePermit, cancellation)) {
                    is OwnedApplicationOpenResult.Unavailable -> {
                        permit.invalidate()
                        CompilerApplicationOpenResult.Unavailable(opened.reasons.map { it.code }, opened.cleanup)
                    }
                    is OwnedApplicationOpenResult.Ready -> {
                        try {
                            val application = KotlinJvmCompilerApplication(request, permit, opened.session, captured)
                            val ready = CompilerApplicationOpenResult.Ready(application)
                            permit.bindCancellation(application::cancelRevokedGeneration)
                            checkpoint()
                            application.lifecycle.openAdmission()
                            ready
                        } catch (_: Exception) {
                            permit.invalidate()
                            opened.session.cancel()
                            CompilerApplicationOpenResult.Unavailable(listOf("compiler_live_open_acceptance_failed"), opened.session.retire())
                        }
                    }
                }
            } catch (_: CancellationException) {
                if (admitted) permit.revoke()
                return unavailable("compiler_live_cancelled")
            } catch (failure: CompilerApplicationProblem) {
                if (admitted) permit.revoke()
                return unavailable(failure.code)
            } catch (_: Exception) {
                if (admitted) permit.revoke()
                return unavailable("compiler_live_generation_verification_failed")
            }
        }

        /** Pure module tests only. HARD-DISABLED compile; cannot turn a fake session into a result. */
        internal fun bindingControls(request: CompilerGenerationRequest, permit: CompilerGenerationPermit,
            neverSubmitSession: OwnedApplicationSession): KotlinJvmCompilerApplication {
            liveCheck(permit.request === request, "compiler_live_generation_binding_mismatch")
            val application = KotlinJvmCompilerApplication(request, permit, neverSubmitSession, null)
            permit.bindCancellation(application::cancelRevokedGeneration)
            permit.checkLive()
            application.lifecycle.openAdmission()
            return application
        }
    }
}

private val LIVE_NO_MODEL = Wire.digest("ktchecker/compiler-live-no-model-authority/1", byteArrayOf())
private fun liveInputIdentity(input: AnalysisInput, members: String) = CompilerWire.InputIdentity(input.analysisKey(),
    input.snapshot.snapshotId, members, input.context.modelDigest ?: LIVE_NO_MODEL,
    checkNotNull(input.context.dependencyDigest), checkNotNull(input.context.configurationDigest), checkNotNull(input.context.toolchainDigest))

/** Owner registers these exact roots after CREATE_NEW; every publicly reachable field is detached. */
class CompilerJobMaterialization internal constructor(
    val original: AnalysisInput,
    val effective: AnalysisInput,
    internal val wireRequest: CompilerWire.Request,
    private val jobPin: CompilerRootPin,
    private val sourcePin: CompilerRootPin,
    private val outputPin: CompilerRootPin,
    private val sourceFile: CompilerFilePin,
) {
    val snapshotRoot: Wire.Root get() = wireRequest.snapshotRoot()
    val jobRoot: Wire.Root get() = wireRequest.jobRoot()
    val sourceRoot: Wire.Root get() = wireRequest.sourceRoot()
    val outputRoot: Wire.Root get() = wireRequest.outputRoot()
    val sourceIdentity: CompilerWire.Source get() = wireRequest.sources().single()
    private val output = AtomicReference<CompilerTree?>(null)

    internal fun verifyInputs(checkpoint: () -> Unit) {
        checkpoint(); jobPin.verify(); sourcePin.verify(); outputPin.verify()
        liveCheck(liveNames(livePath(jobRoot.path()), 2) == setOf("sources", "classes") &&
            liveNames(livePath(sourceRoot.path()), 1) == setOf("Fixture.kt"), "compiler_live_materialized_member_drift")
        sourceFile.verify(checkpoint)
        checkpoint()
    }

    internal fun sealOutput(checkpoint: () -> Unit) {
        outputPin.verify()
        val value = CompilerTree.capture(livePath(outputRoot.path()), CompilerWire.MAX_JOB_OUTPUT_FILES,
            CompilerWire.MAX_JOB_OUTPUT_BYTES, true, checkpoint)
        liveCheck(output.compareAndSet(null, value), "compiler_live_result_replayed")
        outputPin.verify(); checkpoint()
    }

    internal fun verifyOutput(sealed: Boolean, checkpoint: () -> Unit) {
        checkpoint(); outputPin.verify()
        if (sealed) {
            val expected = output.get() ?: liveFailure("compiler_live_output_not_sealed")
            liveCheck(expected == CompilerTree.capture(livePath(outputRoot.path()), CompilerWire.MAX_JOB_OUTPUT_FILES,
                CompilerWire.MAX_JOB_OUTPUT_BYTES, true, checkpoint), "compiler_live_output_drift")
        } else {
            liveCheck(output.get() == null && liveNames(livePath(outputRoot.path()), 0).isEmpty(), "compiler_live_output_not_fresh")
        }
        outputPin.verify(); checkpoint()
    }

    internal fun outputSealed(): Boolean = output.get() != null
    internal fun outputBytes(): Long = output.get()?.bytes ?: 0L
}

/** Cooperative filesystem receipts, not an OS sandbox or protection against fully restored races. */
internal class CompilerCapturedSource private constructor(val input: AnalysisInput, private val rootPin: CompilerRootPin,
    private val file: CompilerFilePin, private val content: ByteArray, val text: String) {
    val root: Wire.Root get() = rootPin.root
    val fileKey: String get() = file.fileKey
    fun bytes(): ByteArray = content.clone()

    fun verify(checkpoint: () -> Unit) {
        checkpoint(); rootPin.verify()
        liveCheck(liveNames(livePath(root.path()), 1) == setOf("Fixture.kt"), "compiler_live_original_member_drift")
        file.verify(checkpoint)
        rootPin.verify(); checkpoint()
    }

    companion object {
        fun capture(input: AnalysisInput, checkpoint: () -> Unit): CompilerCapturedSource {
            checkpoint()
            val root = CompilerRootPin.capture(livePath(input.materializedRoot))
            liveCheck(liveNames(livePath(root.root.path()), 1) == setOf("Fixture.kt"), "compiler_live_original_member_drift")
            val source = input.snapshot.files.single()
            val file = CompilerFilePin.capture(livePath(input.materializedRoot).resolve("Fixture.kt"), source.digest, source.size,
                checkpoint = checkpoint)
            val content = Files.newInputStream(file.path, READ, NOFOLLOW_LINKS).use { it.readNBytes(CompilerWire.MAX_SOURCE_BYTES + 1) }
            checkpoint()
            liveCheck(content.size.toLong() == source.size && Digests.sha256(content) == source.digest,
                "compiler_live_original_source_drift")
            val text = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString() }
            catch (_: Exception) { liveFailure("compiler_live_source_not_utf8") }
            val captured = CompilerCapturedSource(input, root, file, content.clone(), text)
            captured.verify(checkpoint)
            return captured
        }
    }
}

internal class CompilerGenerationCapture private constructor(private val request: CompilerGenerationRequest,
    private val roots: List<CompilerRootPin>, private val files: List<CompilerFilePin>) {
    fun verify(checkpoint: () -> Unit) {
        checkpoint()
        request.installation.verify(checkpoint)
        roots.forEach { checkpoint(); it.verify() }
        liveCheck(liveNames(livePath(request.launch.generationRoot().path()), 5) ==
            setOf("bridge.jar", "launch.bin", "compiler", "jobs", "tmp"), "compiler_live_generation_root_drift")
        liveCheck(liveNames(livePath(request.launch.generationRoot().path()).resolve("compiler"), CompilerWire.MAX_ARTIFACTS) ==
            request.generation.compilerArtifacts().indices.map { "$it.jar" }.toSet(), "compiler_live_compiler_member_drift")
        files.forEach { it.verify(checkpoint) }
        roots.forEach { checkpoint(); it.verify() }
        checkpoint()
    }

    companion object {
        fun capture(request: CompilerGenerationRequest, checkpoint: () -> Unit): CompilerGenerationCapture {
            checkpoint()
            CompilerWire.matchGeneration(request.launch, request.generation)
            val installed = request.installation.installationView()
            val generation = request.generation
            liveCheck(installed.runtime.root.toString() == generation.jdk().home().path() &&
                installed.runtime.javaExecutable.toString() == generation.jdk().executable() &&
                installed.runtime.executableDigest == generation.jdk().executableDigest() &&
                installed.runtime.digest == generation.jdk().runtimeDigest() && installed.toolchainDigest == request.installation.digest,
                "compiler_live_installation_mismatch")
            liveCheck(installed.compilerClasspath.size == generation.compilerArtifacts().size, "compiler_live_installation_mismatch")
            val root = livePath(request.launch.generationRoot().path())
            val roots = listOf(CompilerRootPin.capture(root, request.launch.generationRoot().fileKey()),
                CompilerRootPin.capture(livePath(generation.jdk().home().path()), generation.jdk().home().fileKey()),
                CompilerRootPin.capture(root.resolve("compiler")), CompilerRootPin.capture(root.resolve("jobs")),
                CompilerRootPin.capture(root.resolve("tmp")))
            liveCheck(roots.map { it.root.fileKey() }.distinct().size == roots.size, "compiler_live_root_alias")
            liveCheck(liveNames(root.resolve("jobs"), 0).isEmpty(), "compiler_live_generation_not_fresh")
            val files = ArrayList<CompilerFilePin>()
            installed.compilerClasspath.forEachIndexed { index, installedArtifact ->
                checkpoint()
                val admitted = generation.compilerArtifacts()[index]
                val original = installedArtifact.identity
                liveCheck(installedArtifact.ordinal == index && original.coordinate == admitted.coordinate() &&
                    livePath(original.path).fileName.toString() == admitted.fileName() && original.digest == admitted.digest() &&
                    installedArtifact.size == admitted.size(), "compiler_live_installation_mismatch")
                files += CompilerFilePin.capture(livePath(original.path), original.digest, installedArtifact.size, checkpoint = checkpoint)
                files += CompilerFilePin.capture(root.resolve("compiler/$index.jar"), admitted.digest(), admitted.size(),
                    admitted.fileKey(), checkpoint)
            }
            files += CompilerFilePin.capture(root.resolve("bridge.jar"), generation.bridge().digest(), generation.bridge().size(),
                generation.bridge().fileKey(), checkpoint)
            val encodedLaunch = Wire.encodeLaunch(request.launch)
            files += CompilerFilePin.capture(root.resolve("launch.bin"), Wire.sha256(encodedLaunch), encodedLaunch.size.toLong(),
                checkpoint = checkpoint)
            liveCheck(files.map { it.fileKey }.distinct().size == files.size, "compiler_live_artifact_alias")
            return CompilerGenerationCapture(request, Collections.unmodifiableList(roots), Collections.unmodifiableList(files)).also {
                it.verify(checkpoint)
            }
        }
    }
}

internal data class CompilerNodePin(val path: Path, val fileKey: String, val directory: Boolean) {
    fun verify(): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        liveCheck(!attributes.isSymbolicLink && !attributes.isOther && attributes.fileKey()?.toString() == fileKey &&
            (if (directory) attributes.isDirectory else attributes.isRegularFile), "compiler_live_path_drift")
        if (!directory && path.fileSystem.supportedFileAttributeViews().contains("unix")) {
            liveCheck((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L, "compiler_live_hardlink_unsupported")
        }
        return attributes
    }
}

internal class CompilerRootPin private constructor(val root: Wire.Root, private val nodes: List<CompilerNodePin>) {
    fun verify() { nodes.forEach { it.verify() } }
    companion object {
        fun capture(path: Path, expectedKey: String? = null): CompilerRootPin {
            val nodes = captureLiveNodes(path, true)
            val root = Wire.Root(path.toString(), nodes.last().fileKey)
            liveCheck(expectedKey == null || root.fileKey() == expectedKey, "compiler_live_root_drift")
            return CompilerRootPin(root, nodes).also { it.verify() }
        }
    }
}

internal class CompilerFilePin private constructor(val path: Path, val digest: String, val size: Long,
    private val nodes: List<CompilerNodePin>) {
    val fileKey: String get() = nodes.last().fileKey
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); nodes.forEach { it.verify() }
        liveCheck(nodes.last().verify().size() == size, "compiler_live_file_drift")
        liveCheck(hashLiveFile(path, size, checkpoint) == digest, "compiler_live_file_drift")
        nodes.forEach { it.verify() }
        liveCheck(nodes.last().verify().size() == size, "compiler_live_file_drift")
        checkpoint()
    }
    companion object {
        fun capture(path: Path, digest: String, size: Long, expectedKey: String? = null, checkpoint: () -> Unit): CompilerFilePin {
            checkpoint()
            liveCheck(Digests.valid(digest) && size in 0..CompilerWire.MAX_ARTIFACT_BYTES, "compiler_live_file_identity_invalid")
            val nodes = captureLiveNodes(path, false)
            liveCheck(expectedKey == null || nodes.last().fileKey == expectedKey, "compiler_live_file_drift")
            return CompilerFilePin(path, digest, size, nodes).also { it.verify(checkpoint) }
        }
    }
}

private fun captureLiveNodes(path: Path, directory: Boolean): List<CompilerNodePin> {
    livePath(path.toString())
    val paths = ArrayList<Path>()
    var current = path.root ?: liveFailure("compiler_live_path_invalid")
    paths.add(current)
    path.forEach { component -> current = current.resolve(component); paths.add(current) }
    return Collections.unmodifiableList(paths.mapIndexed { index, part ->
        val attributes = Files.readAttributes(part, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val key = attributes.fileKey()?.toString()?.takeIf { it.isNotBlank() } ?: liveFailure("compiler_live_file_key_unavailable")
        CompilerNodePin(part, key, index != paths.lastIndex || directory).also { it.verify() }
    })
}

private fun hashLiveFile(path: Path, size: Long, checkpoint: () -> Unit): String {
    val hash = MessageDigest.getInstance("SHA-256")
    var count = 0L
    Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            liveCheck(read.toLong() <= size - count, "compiler_live_file_drift")
            count += read; hash.update(buffer, 0, read)
        }
    }
    liveCheck(count == size, "compiler_live_file_drift")
    return "sha256:" + HexFormat.of().formatHex(hash.digest())
}

internal fun liveNames(path: Path, limit: Int): Set<String> {
    val names = LinkedHashSet<String>()
    Files.newDirectoryStream(path).use { entries ->
        entries.forEach { entry ->
            liveCheck(names.size < limit, "compiler_live_directory_limit")
            val name = entry.fileName.toString()
            liveCheck(name.length <= 256 && name.none { it == '\u0000' || it == '\r' || it == '\n' }, "compiler_live_path_invalid")
            names += name
        }
    }
    return Collections.unmodifiableSet(names)
}

private fun privateLiveDirectory(path: Path) {
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else Files.createDirectory(path)
}

internal data class CompilerTreeEntry(val fileKey: String, val directory: Boolean, val size: Long, val digest: String?)
internal data class CompilerTree(val entries: Map<String, CompilerTreeEntry>, val bytes: Long) {
    companion object {
        fun capture(root: Path, maxEntries: Int, maxBytes: Long, compilerOutputOnly: Boolean, checkpoint: () -> Unit): CompilerTree {
            val rootPin = CompilerRootPin.capture(root)
            var bytes = 0L
            val entries = LinkedHashMap<String, CompilerTreeEntry>()
            Files.walk(root).use { stream ->
                val paths = stream.iterator()
                while (paths.hasNext()) {
                    checkpoint()
                    val path = paths.next()
                    if (path == root) continue
                    liveCheck(entries.size < maxEntries && path.startsWith(root), "compiler_live_output_limit")
                    val relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                    liveCheck(relative.length <= 4096 && relative.none { it == '\u0000' || it == '\r' || it == '\n' }, "compiler_live_path_invalid")
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    liveCheck(!attributes.isSymbolicLink && !attributes.isOther && (attributes.isDirectory || attributes.isRegularFile),
                        "compiler_live_output_unsafe")
                    val key = attributes.fileKey()?.toString() ?: liveFailure("compiler_live_file_key_unavailable")
                    val node = CompilerNodePin(path, key, attributes.isDirectory)
                    node.verify()
                    val size = if (attributes.isDirectory) 0L else attributes.size()
                    liveCheck(size >= 0 && size <= maxBytes - bytes, "compiler_live_output_limit")
                    bytes += size
                    if (compilerOutputOnly && !attributes.isDirectory) liveCheck(relative.endsWith(".class") ||
                        relative.startsWith("META-INF/") && relative.endsWith(".kotlin_module"), "compiler_live_output_member_unsupported")
                    val digest = if (attributes.isDirectory) null else hashLiveFile(path, size, checkpoint)
                    liveCheck(node.verify().let { it.isDirectory || it.size() == size }, "compiler_live_output_drift")
                    entries[relative] = CompilerTreeEntry(key, attributes.isDirectory, size, digest)
                }
            }
            liveCheck(entries.values.map { it.fileKey }.distinct().size == entries.size, "compiler_live_output_alias")
            rootPin.verify(); checkpoint()
            return CompilerTree(Collections.unmodifiableMap(entries), bytes)
        }
    }
}
