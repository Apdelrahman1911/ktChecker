package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted host integration only. A model/project request cannot enroll itself through this API. */
fun interface ModelIngressAuthority {
    fun authorize(request: ModelImportRequest): ModelIngressPermit?
    companion object { val DENY = ModelIngressAuthority { null } }
}

/**
 * Supplied only by an authenticated owner/controller after an exact authorized Gradle export.
 * Callbacks must validate registered coherent capture, the actual successful/quiescent invocation
 * of the pinned exporter/Gradle runtime, and immutable external artifacts. Echoing JSON digests or
 * scanning a mutable worktree before/after is NOT that proof. Configuration runs arbitrary project
 * code: this cooperative permit is not a same-UID process/sandbox security boundary.
 * verifyModelInputs must additionally attest the complete bounded observed source/resource roots,
 * configuration read set and generated/external inputs. Coherent but intentionally partial roots
 * or a report's empty source lists are not proof of complete project capture.
 */
class ModelIngressPermit(
    request: ModelImportRequest,
    val gradleRuntimeDigest: String,
    val verifySnapshot: () -> Unit,
    val verifyExecutedExport: () -> Unit,
    val verifyArtifact: (ModelArtifact) -> Unit,
    val verifyModelInputs: (GradleProjectExport) -> Unit,
    /** Optional trusted origin enrollment, DENY by default. Return a preselected owner object,
     * never derive roots/specs from report claims. Reverify registered source/JDK/output/task
     * origin on BOTH calls and return the SAME instance. This grants no execution permission. */
    val verifyJvmArgumentOrigin: (ModelCompilation) -> ModelJvmArgumentOrigin? = { null },
    /** Separate owner-enrolled deferred edge; SAME preselected instance on both calls.
     * No existing directory or report-declared producer grants this preview capability. */
    val verifyJvmProducedOutputOrigin: (ModelCompilation) -> ModelJvmProducedOutputOrigin? = { null },
) {
    val request: ModelImportRequest = freezeRequest(request)
    val requestDigest: String = this.request.digest()
    private val consumed = AtomicBoolean()

    init {
        require(Digests.valid(gradleRuntimeDigest)) { "An actually verified Gradle runtime identity is required." }
    }

    internal fun consume(actual: ModelImportRequest) {
        if (actual.digest() != requestDigest || !consumed.compareAndSet(false, true)) {
            throw ProjectModelException("model_ingress_mismatch_or_replay", "Model permit is single-use and bound to one exact namespace/snapshot/execution/export.")
        }
    }
}

/** This object and the receipt callbacks are host capabilities; never expose them as RPC arguments. */
class ExactModelIngressAuthority(private val permit: ModelIngressPermit) : ModelIngressAuthority {
    override fun authorize(request: ModelImportRequest): ModelIngressPermit? = permit.takeIf { request.digest() == it.requestDigest }
}
