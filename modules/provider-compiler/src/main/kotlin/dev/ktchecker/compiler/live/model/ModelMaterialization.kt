package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.KotlinJvmModelBinding
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

/** Whole kernel archive receipts. Original model-worktree source/dependency paths are never reread. */
internal class ModelArchiveCapture private constructor(
    val input: AnalysisInput,
    private val snapshotPin: CompilerRootPin,
    private val classpathPin: CompilerRootPin?,
    private val originalOutputPin: CompilerRootPin,
    private val capturedSnapshotTree: CompilerTree,
    val files: List<CompilerFilePin>,
    val dependencies: List<CompilerFilePin>,
    val sourceTexts: Map<String, String>,
) {
    val snapshotRoot: Wire.Root get() = snapshotPin.root
    val classpathRoot: Wire.Root? get() = classpathPin?.root
    val originalOutputRoot: Wire.Root get() = originalOutputPin.root
    val snapshotBytes: Long get() = capturedSnapshotTree.bytes
    val dependencyBytes: Long get() = dependencies.sumOf { it.size }

    fun verify(checkpoint: () -> Unit) {
        checkpoint(); snapshotPin.verify(); classpathPin?.verify(); originalOutputPin.verify()
        liveCheck(capturedSnapshotTree == snapshotTree(input, checkpoint), "compiler_model_live_archive_drift")
        if (dependencies.isNotEmpty()) {
            liveCheck(liveNames(livePath(requireNotNull(classpathRoot).path()), ModelLiveLimits.DEPENDENCIES) ==
                dependencies.indices.map { "artifact-$it.jar" }.toSet(), "compiler_model_live_archive_classpath_drift")
        }
        files.forEach { it.verify(checkpoint) }
        dependencies.forEach { it.verify(checkpoint) }
        snapshotPin.verify(); classpathPin?.verify(); originalOutputPin.verify(); checkpoint()
    }

    companion object {
        fun capture(input: AnalysisInput, binding: KotlinJvmModelBinding? = null,
            checkpoint: () -> Unit): ModelArchiveCapture {
            checkpoint(); requireModelLiveShape(input, binding)
            val root = livePath(input.materializedRoot)
            val snapshot = CompilerRootPin.capture(root)
            val classpath = if (input.context.classpath.isEmpty()) null else CompilerRootPin.capture(root.parent.resolve("classpath"))
            val output = CompilerRootPin.capture(livePath(requireNotNull(input.outputRoot)))
            val tree = snapshotTree(input, checkpoint)
            val files = input.snapshot.files.map { file ->
                CompilerFilePin.capture(root.resolve(file.path), file.digest, file.size, checkpoint = checkpoint)
            }
            var dependencyBytes = 0L
            val dependencies = input.context.classpath.mapIndexed { index, artifact ->
                checkpoint()
                val path = livePath(artifact.path)
                liveCheck(path == root.parent.resolve("classpath/artifact-$index.jar"), "compiler_model_live_archive_classpath_drift")
                val size = Files.size(path)
                liveCheck(size in 1..ModelLiveLimits.DEPENDENCY_BYTES &&
                    size <= ModelLiveLimits.DEPENDENCY_TOTAL_BYTES - dependencyBytes, "compiler_model_live_dependency_limit")
                dependencyBytes += size
                CompilerFilePin.capture(path, artifact.digest, size, checkpoint = checkpoint)
            }
            val allKeys = files.map { it.fileKey } + dependencies.map { it.fileKey } +
                listOfNotNull(snapshot.root.fileKey(), classpath?.root?.fileKey(), output.root.fileKey())
            liveCheck(allKeys.distinct().size == allKeys.size, "compiler_model_live_archive_alias")
            val byPath = input.snapshot.files.mapIndexed { index, file -> file.path to files[index] }.toMap()
            val texts = requireNotNull(input.context.compilationFiles).associateWith { path ->
                val file = byPath.getValue(path)
                checkpoint()
                val bytes = Files.newInputStream(file.path, READ, NOFOLLOW_LINKS).use {
                    it.readNBytes(ModelLiveLimits.SOURCE_BYTES.toInt() + 1)
                }
                liveCheck(bytes.size.toLong() == file.size && Digests.sha256(bytes) == file.digest, "compiler_model_live_archive_drift")
                val text = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                catch (_: Exception) { liveFailure("compiler_model_live_source_not_utf8") }
                requireModelSelectedText(text, checkpoint)
                checkpoint(); text
            }
            return ModelArchiveCapture(input, snapshot, classpath, output, tree,
                Collections.unmodifiableList(files), Collections.unmodifiableList(dependencies),
                Collections.unmodifiableMap(texts)).also { it.verify(checkpoint) }
        }
    }
}

/** Additional preview source restriction is explicit, never a rewrite of preserved snapshot bytes. */
internal fun requireModelSelectedText(text: String, checkpoint: () -> Unit) {
    liveCheck('\u0000' !in text && !text.startsWith('\uFEFF'), "compiler_model_live_source_encoding_unsupported")
    text.forEachIndexed { index, character ->
        if ((index and 16_383) == 0) checkpoint()
        liveCheck(character != '\r' || text.getOrNull(index + 1) == '\n', "compiler_model_live_bare_cr_unsupported")
    }
    checkpoint()
}

private fun snapshotTree(input: AnalysisInput, checkpoint: () -> Unit): CompilerTree {
    val tree = CompilerTree.capture(livePath(input.materializedRoot), ModelLiveLimits.SNAPSHOT_ENTRIES,
        ModelLiveLimits.SNAPSHOT_BYTES, false, checkpoint)
    val expectedFiles = input.snapshot.files.associateBy { it.path }
    val expectedDirectories = modelDirectories(expectedFiles.keys)
    liveCheck(tree.entries.keys == expectedFiles.keys + expectedDirectories, "compiler_model_live_snapshot_member_drift")
    tree.entries.forEach { (path, entry) ->
        val source = expectedFiles[path]
        liveCheck(if (source == null) entry.directory && path in expectedDirectories else
            !entry.directory && entry.digest == source.digest && entry.size == source.size,
            "compiler_model_live_snapshot_member_drift")
    }
    return tree
}

internal fun modelDirectories(files: Collection<String>): Set<String> = buildSet {
    files.forEach { file ->
        var parent = Path.of(file).parent
        while (parent != null) {
            add(parent.toString().replace(java.io.File.separatorChar, '/'))
            parent = parent.parent
        }
    }
}

/**
 * Exact P/K/C/E detached identities and physical roots. E retains the WHOLE snapshot and model;
 * only actual job source/classpath/output paths differ from C. This receipt is not an owner grant.
 */
class ModelCompilerJobMaterialization internal constructor(
    val original: AnalysisInput,
    val archived: AnalysisInput,
    val input: AnalysisInput,
    val effective: AnalysisInput,
    internal val wireRequest: ModelWire.Request,
    private val jobPin: CompilerRootPin,
    private val sourcePin: CompilerRootPin,
    private val classpathPin: CompilerRootPin,
    private val outputPin: CompilerRootPin,
    private val sourceTree: CompilerTree,
    private val files: List<CompilerFilePin>,
    private val dependencies: List<CompilerFilePin>,
) {
    val snapshotRoot: Wire.Root get() = wireRequest.snapshotRoot()
    val originalClasspathRoot: Wire.Root? get() = wireRequest.originalClasspathRoot()
    val originalOutputRoot: Wire.Root get() = wireRequest.originalOutputRoot()
    val jobRoot: Wire.Root get() = wireRequest.jobRoot()
    val sourceRoot: Wire.Root get() = wireRequest.sourceRoot()
    val classpathRoot: Wire.Root get() = wireRequest.classpathRoot()
    val outputRoot: Wire.Root get() = wireRequest.outputRoot()
    val snapshotMembers: List<ModelWire.Member> get() = wireRequest.files()
    val compilationPaths: List<String> get() = wireRequest.compilationPaths()
    val dependencyMembers: List<ModelWire.Dependency> get() = wireRequest.dependencies()
    internal val snapshotBytes: Long get() = sourceTree.bytes
    internal val dependencyBytes: Long get() = dependencies.sumOf { it.size }
    private val output = AtomicReference<CompilerTree?>(null)

    internal fun verifyInputs(checkpoint: () -> Unit) {
        checkpoint(); jobPin.verify(); sourcePin.verify(); classpathPin.verify(); outputPin.verify()
        liveCheck(liveNames(livePath(jobRoot.path()), 3) == setOf("sources", "classpath", "classes"), "compiler_model_live_job_root_drift")
        liveCheck(sourceTree == snapshotTree(effective, checkpoint), "compiler_model_live_materialized_source_drift")
        liveCheck(liveNames(livePath(classpathRoot.path()), ModelLiveLimits.DEPENDENCIES) ==
            dependencies.indices.map { "artifact-$it.jar" }.toSet(), "compiler_model_live_materialized_classpath_drift")
        files.forEach { it.verify(checkpoint) }; dependencies.forEach { it.verify(checkpoint) }
        jobPin.verify(); sourcePin.verify(); classpathPin.verify(); outputPin.verify(); checkpoint()
    }

    internal fun sealOutput(checkpoint: () -> Unit) {
        checkpoint(); outputPin.verify()
        val observed = CompilerTree.capture(livePath(outputRoot.path()), ModelWire.MAX_JOB_OUTPUT_FILES,
            ModelWire.MAX_JOB_OUTPUT_BYTES, true, checkpoint)
        liveCheck(output.compareAndSet(null, observed), "compiler_model_live_result_replayed")
        outputPin.verify(); checkpoint()
    }

    internal fun verifyOutput(sealed: Boolean, checkpoint: () -> Unit) {
        checkpoint(); outputPin.verify()
        if (sealed) {
            val expected = output.get() ?: liveFailure("compiler_model_live_output_not_sealed")
            liveCheck(expected == CompilerTree.capture(livePath(outputRoot.path()), ModelWire.MAX_JOB_OUTPUT_FILES,
                ModelWire.MAX_JOB_OUTPUT_BYTES, true, checkpoint), "compiler_model_live_output_drift")
        } else {
            liveCheck(output.get() == null && liveNames(livePath(outputRoot.path()), 0).isEmpty(), "compiler_model_live_output_not_fresh")
        }
        outputPin.verify(); checkpoint()
    }

    internal fun outputSealed(): Boolean = output.get() != null
    internal fun outputBytes(): Long = output.get()?.bytes ?: 0L
    internal fun outputEntries(): Int = output.get()?.entries?.size ?: 0

    companion object {
        internal fun create(request: ModelCompilerGenerationRequest, permit: ModelCompilerAttemptPermit,
            sequence: Int, checkpoint: () -> Unit): ModelCompilerJobMaterialization {
            liveCheck(permit.application.request === request, "compiler_model_live_application_binding_mismatch")
            permit.application.requireActiveAttempt(permit)
            permit.checkpoint(); checkpoint(); permit.checkpoint()
            liveCheck(sequence in 1..request.launch.limits().maxJobs(), "compiler_model_live_job_limit")
            val generationRoot = livePath(request.launch.generationRoot().path())
            val job = generationRoot.resolve("jobs/$sequence")
            checkpoint(); modelPrivateDirectory(job)
            val source = job.resolve("sources"); checkpoint(); modelPrivateDirectory(source)
            val classpath = job.resolve("classpath"); checkpoint(); modelPrivateDirectory(classpath)
            val output = job.resolve("classes"); checkpoint(); modelPrivateDirectory(output)
            modelDirectories(permit.input.snapshot.files.map { it.path }).sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
                .forEach { relative -> checkpoint(); modelPrivateDirectory(source.resolve(relative)) }
            val files = permit.capture.files.mapIndexed { index, original ->
                val path = permit.input.snapshot.files[index].path
                copyModelFile(original, source.resolve(path), checkpoint)
            }
            val dependencies = permit.capture.dependencies.mapIndexed { index, original ->
                copyModelFile(original, classpath.resolve("artifact-$index.jar"), checkpoint)
            }
            val copiedKeys = files.map { it.fileKey } + dependencies.map { it.fileKey }
            val originalKeys = permit.capture.files.map { it.fileKey } + permit.capture.dependencies.map { it.fileKey }
            liveCheck(copiedKeys.distinct().size == copiedKeys.size && copiedKeys.none { it in originalKeys },
                "compiler_model_live_materialized_alias")
            val effective = freezeModelLiveInput(permit.input.copy(materializedRoot = source.toString(),
                context = permit.input.context.copy(classpath = permit.input.context.classpath.mapIndexed { index, artifact ->
                    artifact.copy(path = classpath.resolve("artifact-$index.jar").toString())
                }), outputRoot = output.toString()), ModelLiveLimits.COMPILER_RULES)
            requireModelEffectiveRelation(permit.input, effective, source, classpath, output)
            requireModelLiveShape(permit.input, permit.prepared.binding)
            requireModelLiveShape(effective, permit.prepared.binding)
            val jobPin = CompilerRootPin.capture(job)
            val sourcePin = CompilerRootPin.capture(source)
            val classpathPin = CompilerRootPin.capture(classpath)
            val outputPin = CompilerRootPin.capture(output)
            val members = permit.input.snapshot.files.mapIndexed { index, file ->
                ModelWire.Member(file.path, file.digest, file.size, files[index].fileKey)
            }
            val dependencyMembers = permit.input.context.classpath.mapIndexed { index, artifact ->
                ModelWire.Dependency(artifact.coordinate, artifact.digest, dependencies[index].size, dependencies[index].fileKey)
            }
            val membersDigest = ModelWire.membersDigest(members)
            val body = ModelWire.Request(ModelWire.PROFILE,
                permit.prepared.originalAnalysisKey, permit.archivedInputKey, permit.prepared.bindingIdentity.digest,
                modelInputIdentity(permit.input, membersDigest), modelInputIdentity(effective, membersDigest),
                permit.capture.snapshotRoot, permit.capture.classpathRoot, permit.capture.originalOutputRoot,
                jobPin.root, sourcePin.root, classpathPin.root, outputPin.root,
                // These wire-only fields are the live importer's checked projection, not edited
                // AnalysisContext fields. InputIdentity still hashes each complete raw C/E input.
                members, permit.prepared.executableCompilationFiles, dependencyMembers,
                permit.prepared.normalizedCompilerOptions, permit.prepared.normalizedCompilerOptions,
                ModelWire.profileDigest(request.generation), permit.originalDeadline.identity)
            return ModelCompilerJobMaterialization(permit.original, permit.archived, permit.input, effective, body,
                jobPin, sourcePin, classpathPin, outputPin, snapshotTree(effective, checkpoint),
                Collections.unmodifiableList(files), Collections.unmodifiableList(dependencies)).also {
                it.verifyInputs(checkpoint); it.verifyOutput(false, checkpoint)
            }
        }
    }
}

internal fun requireModelEffectiveRelation(input: AnalysisInput, effective: AnalysisInput, source: Path, classpath: Path, output: Path) {
    val expected = input.copy(materializedRoot = source.toString(), context = input.context.copy(
        classpath = input.context.classpath.mapIndexed { index, artifact ->
            artifact.copy(path = classpath.resolve("artifact-$index.jar").toString())
        }), outputRoot = output.toString())
    liveCheck(effective == expected && effective.execution == null, "compiler_model_live_effective_mismatch")
}

private fun modelInputIdentity(input: AnalysisInput, members: String) = ModelWire.InputIdentity(input.analysisKey(),
    input.snapshot.snapshotId, members, requireNotNull(input.context.modelDigest), requireNotNull(input.context.dependencyDigest),
    requireNotNull(input.context.configurationDigest), requireNotNull(input.context.toolchainDigest))

internal fun modelPrivateDirectory(path: Path) {
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else Files.createDirectory(path)
}

private fun copyModelFile(original: CompilerFilePin, destination: Path, checkpoint: () -> Unit): CompilerFilePin {
    checkpoint(); original.verify(checkpoint)
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    Files.newInputStream(original.path, READ, NOFOLLOW_LINKS).use { input ->
        Files.newOutputStream(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            val buffer = ByteArray(65_536)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                liveCheck(count.toLong() <= original.size - total, "compiler_model_live_copy_drift")
                total += count; digest.update(buffer, 0, count); output.write(buffer, 0, count)
            }
        }
    }
    liveCheck(total == original.size && "sha256:" + HexFormat.of().formatHex(digest.digest()) == original.digest,
        "compiler_model_live_copy_drift")
    if (destination.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r--------"))
    }
    checkpoint(); original.verify(checkpoint)
    return CompilerFilePin.capture(destination, original.digest, original.size, checkpoint = checkpoint).also {
        liveCheck(it.fileKey != original.fileKey, "compiler_model_live_materialized_alias")
    }
}
