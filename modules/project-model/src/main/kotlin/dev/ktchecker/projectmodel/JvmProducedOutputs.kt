package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/** Deferred task-output roles, NOT an artifact hash or evidence that a producer has run. */
@Serializable
data class ModelJvmProducedOutputReference(
    val producerCompilationId: String,
    val producerCompileTaskPath: String,
    val outputPath: String,
    val classpathIndex: Int,
    val originalClasspathPresent: Boolean,
    val friend: Boolean,
    val originalFriendPresent: Boolean,
    val declaredFriendPresent: Boolean,
) {
    fun digest(): String = canonicalDigest(ContractJson.encodeToJsonElement(this))
}

const val JVM_PRODUCED_OUTPUT_PREVIEW = "ktchecker/jvm-produced-output/preview-1"

/**
 * Trusted owner-only enrollment of ONE observed task-output edge. All arguments must come from
 * the owner's registered actual task/association observations, not from export claims. Ordinary
 * names such as main/test, an existing build directory, or a serialized hash grant no authority.
 * Both role-origin objects must be the SAME objects returned by verifyJvmArgumentOrigin. The
 * original presence facts are retained even when KGP omitted an as-yet-missing output from argv.
 */
class ModelJvmProducedOutputOrigin(
    request: ModelImportRequest,
    val producer: ModelJvmArgumentOrigin,
    val consumer: ModelJvmArgumentOrigin,
    val classpathIndex: Int,
    val originalClasspathPresent: Boolean,
    val friend: Boolean,
    val originalFriendPresent: Boolean,
    val declaredFriendPresent: Boolean,
) {
    val requestDigest: String = freezeRequest(request).digest()
    init {
        requireBound(producer.requestDigest == requestDigest && consumer.requestDigest == requestDigest &&
            producer !== consumer && producer.compilationId != consumer.compilationId &&
            producer.moduleId == consumer.moduleId && producer.sourceRoot == consumer.sourceRoot &&
            producer.ownerExporterOutput == consumer.ownerExporterOutput && producer.selectedJdkHome == consumer.selectedJdkHome &&
            classpathIndex in 0..15 && (!originalFriendPresent || friend) &&
            originalFriendPresent == declaredFriendPresent,
            "exact independently observed JVM producer edge")
    }
    internal fun reference() = ModelJvmProducedOutputReference(producer.compilationId, producer.compileTaskPath,
        producer.destinationPath.toString(), classpathIndex, originalClasspathPresent, friend,
        originalFriendPresent, declaredFriendPresent)
}

/**
 * Importer-only executable-preview graph capability. The original model remains UNAVAILABLE
 * with its explicit deferred issue and fullProject=false, before AND after producer execution.
 * This capability only says those obligations are modeled. It never says they are fulfilled.
 * First slice: two nonempty Kotlin-only ordinary JVM compilations and one exact output edge.
 */
class VerifiedJvmProducedGraph internal constructor(
    val requestDigest: String,
    val modelDigest: String,
    val producerCompilationId: String,
    val consumerCompilationId: String,
    val reference: ModelJvmProducedOutputReference,
    val digest: String,
) {
    val compilationIds: List<String> = immutable(listOf(producerCompilationId, consumerCompilationId))
}

internal fun deferredJvmIssue(consumer: String, reference: ModelJvmProducedOutputReference) = ModelIssue(
    "jvm_produced_output_deferred", consumer,
    "Deferred JVM output from ${reference.producerCompilationId} at classpath ordinal ${reference.classpathIndex}.")

internal fun verifyDeferredJvmOrigin(request: ModelImportRequest, graph: GradleProjectExport,
    consumer: ModelCompilation, origin: ModelJvmProducedOutputOrigin,
    roleOrigins: Map<String, ModelJvmArgumentOrigin?>) {
    val ref = consumer.deferredJvmOutputs.singleOrNull()
        ?: throw ProjectModelException("model_jvm_produced_output_unsupported", "Exactly one declared produced-output role is supported.")
    val producer = graph.compilations.singleOrNull { it.id == ref.producerCompilationId }
    requireBound(origin.requestDigest == request.digest() && ref == origin.reference() &&
        origin.consumer === roleOrigins[consumer.id] && origin.producer === roleOrigins[ref.producerCompilationId] &&
        origin.consumer.compilationId == consumer.id && producer != null &&
        producer.compileTaskPath == ref.producerCompileTaskPath && producer.moduleId == consumer.moduleId &&
        consumer.id != producer.id, "same independently registered producer/consumer origins")
    requireBound(consumer.associatedCompilationIds == if (ref.friend) listOf(ref.producerCompilationId) else emptyList<String>(),
        "complete actual JVM association edge")
    val artifact = consumer.classpath.getOrNull(ref.classpathIndex)
    requireBound(artifact != null && artifact.path == ref.outputPath && artifact.digest == null && artifact.size == null &&
        consumer.classpath.count { it.path == ref.outputPath } == 1, "one deferred classpath occurrence")
    requireBound(if (ref.declaredFriendPresent) consumer.friendArtifacts.size == 1 &&
        consumer.friendArtifacts.single().let { it.path == ref.outputPath && it.digest == null && it.size == null }
        else consumer.friendArtifacts.isEmpty(), "complete declared friend role")
    requireBound(consumer.jvmArgumentRoles?.friendPathOperands == if (ref.originalFriendPresent) listOf(ref.outputPath) else emptyList<String>(),
        "original friend operands, including absence")
    // The missing-byte exception is bound to these exact occurrences, never just to a pathname.
    // An identical pathname in a buildscript/compiler/plugin/runtime/other compilation remains
    // foreign even if it carries a digest or happens to equal this deferred artifact DTO.
    val foreignArtifacts = graph.buildscriptArtifacts + graph.externalConfigurationArtifacts +
        graph.compilations.flatMap { compilation ->
            val consumerRole = compilation.id == consumer.id
            compilation.classpath.filterIndexed { index, _ -> !consumerRole || index != ref.classpathIndex } +
                compilation.friendArtifacts.filterIndexed { index, _ ->
                    !consumerRole || !ref.declaredFriendPresent || index != 0
                } + compilation.auxiliaryArtifacts + compilation.toolchain?.let {
                    it.compilerArtifacts + it.runtimeArtifacts + it.compilerPlugins
                }.orEmpty()
        }
    requireBound(foreignArtifacts.none { it.path == ref.outputPath }, "no foreign use of deferred artifact path")
}

internal fun verifiedJvmProducedGraph(request: ModelImportRequest, graph: GradleProjectExport, modelDigest: String,
    issues: List<ModelIssue>, layouts: Map<String, VerifiedJvmArgumentLayout>,
    origins: Map<String, ModelJvmProducedOutputOrigin>): VerifiedJvmProducedGraph? {
    val shape = jvmProducedGraphShape(graph, layouts, origins) ?: return null
    val producer = shape.producer
    val consumer = shape.consumer
    val ref = shape.reference
    // The ordinary verified branch retains its EXACT prior issue gate. Observed closure gaps
    // are not excluded/allowed here, even if their serialized metadata otherwise looks equal.
    if (issues.distinct() != listOf(deferredJvmIssue(consumer.id, ref))) return null
    val identity = canonicalDigest(ContractJson.encodeToJsonElement(listOf(JVM_PRODUCED_OUTPUT_PREVIEW,
        request.digest(), modelDigest, producer.id, consumer.id, ref.digest(),
        layouts.getValue(producer.id).digest, layouts.getValue(consumer.id).digest)))
    return VerifiedJvmProducedGraph(request.digest(), modelDigest, producer.id, consumer.id, ref, identity)
}

/** Pure structural projection, not a permit/graph capability or an issue allowlist. */
internal class JvmProducedGraphShape(val producer: ModelCompilation, val consumer: ModelCompilation,
    val reference: ModelJvmProducedOutputReference)

internal fun jvmProducedGraphShape(graph: GradleProjectExport, layouts: Map<String, VerifiedJvmArgumentLayout>,
    origins: Map<String, ModelJvmProducedOutputOrigin>): JvmProducedGraphShape? {
    if (origins.size != 1) return null
    val consumerId = origins.keys.single()
    val consumer = graph.compilations.single { it.id == consumerId }
    val ref = consumer.deferredJvmOutputs.single()
    val producer = graph.compilations.single { it.id == ref.producerCompilationId }
    if (graph.compilations.size != 2 || graph.modules.size != 1 || graph.generatedInputs.isNotEmpty() ||
        graph.sourceSets.any { it.resources.isNotEmpty() } ||
        graph.includedBuilds.isNotEmpty() || producer.deferredJvmOutputs.isNotEmpty() ||
        layouts.keys != setOf(producer.id, consumer.id) || graph.compilations.any { c ->
            c.target.isNotEmpty() || c.platform != "jvm" || c.sourceFiles.isEmpty() || c.sourceFiles.any { !it.path.endsWith(".kt") } ||
                c.projectDependencies.isNotEmpty() || c.auxiliaryArtifacts.isNotEmpty() || c.auxiliaryInputFiles.isNotEmpty() ||
                c.toolchain?.compilerPlugins?.isEmpty() != true || c.sourceSetIds.any { id ->
                    graph.sourceSets.single { it.id == id }.dependsOn.isNotEmpty()
                }
        } || producer.associatedCompilationIds.isNotEmpty() || producer.friendArtifacts.isNotEmpty() ||
        producer.sourceFiles.any { p -> consumer.sourceFiles.any { it.path == p.path } } ||
        producer.toolchain != consumer.toolchain) return null
    return JvmProducedGraphShape(producer, consumer, ref)
}
