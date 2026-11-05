package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.liveCheck
import dev.ktchecker.contracts.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A same-preview, same-admission retained D capability, NOT an executable input or an archive
 * identity reconstructed from JSON. Only the owner holding this preview can enroll it. D retains
 * raw argv/context and original path identities. Frozen D is its immutable final archive identity;
 * the explicit held-read projection exists only for precommit IO and never becomes P/K or a key.
 */
class JvmProducedDeclaredArchive internal constructor(
    internal val preview: KotlinJvmProducedOutputPreview,
    val compilationId: String,
    internal val reads: ProducedDeclaredArchiveReads,
) {
    val original: AnalysisInput get() = reads.original
    val frozen: AnalysisInput get() = reads.frozen
    val archiveKey: String get() = reads.archiveKey

    internal fun requireIdentity(expectedPreview: KotlinJvmProducedOutputPreview, admissionIdentity: Any, archiveIdentity: Any) {
        liveCheck(preview === expectedPreview, "compiler_model_produced_archive_foreign_identity")
        reads.requireIdentity(expectedPreview, admissionIdentity, archiveIdentity)
    }

    internal fun verify(checkpoint: () -> Unit): AnalysisInput {
        preview.checkpoint()
        return reads.read(preview, reads.admissionIdentity, reads.archiveIdentity, checkpoint).also { preview.checkpoint() }
    }

    internal fun requireResolvedInput(input: AnalysisInput, context: AnalysisContext) {
        val additions = input.context.policies.keys - original.context.policies.keys
        liveCheck(input.copy(context = original.context) == original &&
            input.context == context.copy(policies = input.context.policies) &&
            original.context.policies.all { input.context.policies[it.key] == it.value } &&
            additions == setOf("ktchecker.execution-plan", "ktchecker.jvm-produced-preview", "ktchecker.jvm-produced-resolution") &&
            input.context.policies["ktchecker.execution-plan"] == emptyModelColdProfilesDigest(),
            "compiler_model_produced_archive_input_mismatch")
    }

    internal fun requireKernelArchive(input: AnalysisInput, workKey: String) {
        reads.requireKernelArchive(input, workKey)
    }
}

/**
 * Pure location/capability gate used by the real preview. Tests use OPAQUE identities and ordinary
 * fixture bytes here; this helper cannot create a model, binding, attempt or successful output.
 * Callbacks/IO are deliberately never inside a synchronized block. All supplied collections are
 * detached before retention, and final→held rollback is sticky-denied after any final read.
 */
internal class ProducedDeclaredArchiveReads(
    private val previewIdentity: Any,
    internal val admissionIdentity: Any,
    internal val archiveIdentity: Any,
    val archiveKey: String,
    original: AnalysisInput,
    frozen: AnalysisInput,
    held: AnalysisInput,
    private val currentRead: () -> AnalysisInput,
    private val verifyFrozen: () -> Unit,
) {
    val original = freezeModelLiveInput(original)
    val frozen = freezeModelLiveInput(frozen)
    private val held = freezeModelLiveInput(held)
    private val finalObserved = AtomicBoolean(false)

    init {
        liveCheck(Digests.valid(archiveKey) && this.original.outputRoot == null && this.original.execution == null,
            "compiler_model_produced_archive_identity_invalid")
        requireProjection(this.frozen)
        requireProjection(this.held)
        val destination = modelArchivePath(this.frozen.materializedRoot).parent
        val temporary = modelArchivePath(this.held.materializedRoot).parent
        liveCheck(destination.fileName.toString() == archiveKey.removePrefix("sha256:") &&
            destination.parent != null && destination.parent == temporary.parent &&
            destination != modelArchivePath(this.original.materializedRoot) &&
            !modelArchivePath(this.original.materializedRoot).startsWith(destination),
            "compiler_model_produced_archive_layout_mismatch")
    }

    fun requireIdentity(preview: Any, admission: Any, archive: Any) {
        liveCheck(preview === previewIdentity && admission === admissionIdentity && archive === archiveIdentity,
            "compiler_model_produced_archive_foreign_identity")
    }

    fun read(preview: Any, admission: Any, archive: Any, checkpoint: () -> Unit = {}): AnalysisInput {
        requireIdentity(preview, admission, archive) // Before even a foreign clock/verifier/read callback.
        checkpoint()
        val before = checkedRead()
        verifyFrozen()
        checkpoint()
        val after = checkedRead()
        liveCheck(before == after, "compiler_model_produced_archive_read_changed")
        return before
    }

    fun requireKernelArchive(input: AnalysisInput, workKey: String) {
        liveCheck(Digests.valid(workKey) && workKey != archiveKey, "compiler_model_produced_work_identity_invalid")
        val expected = Path.of(frozen.materializedRoot).parent.parent.resolve(workKey.removePrefix("sha256:")).resolve("sources")
        liveCheck(input.materializedRoot == expected.toString(), "compiler_model_produced_work_archive_mismatch")
    }

    private fun checkedRead(): AnalysisInput {
        val value = freezeModelLiveInput(currentRead())
        liveCheck(value == frozen || value == held, "compiler_model_produced_archive_read_mismatch")
        if (value == frozen) finalObserved.set(true)
        else liveCheck(!finalObserved.get(), "compiler_model_produced_archive_read_rollback")
        return value
    }

    private fun requireProjection(input: AnalysisInput) {
        val root = modelArchivePath(input.materializedRoot)
        liveCheck(root.fileName?.toString() == "sources" && root.parent?.parent != null && input.outputRoot == null &&
            input.copy(materializedRoot = original.materializedRoot,
                context = input.context.copy(classpath = original.context.classpath)) == original &&
            input.context.classpath.size == original.context.classpath.size,
            "compiler_model_produced_archive_layout_mismatch")
        original.context.classpath.zip(input.context.classpath).forEachIndexed { index, (before, after) ->
            liveCheck(after == before.copy(path = root.parent.resolve("classpath/artifact-$index.jar").toString()),
                "compiler_model_produced_archive_classpath_mismatch")
        }
    }
}

private fun modelArchivePath(value: String): Path {
    val path = Path.of(value)
    liveCheck(path.isAbsolute && path.normalize() == path && path.toString() == value,
        "compiler_model_produced_archive_path_invalid")
    return path
}
