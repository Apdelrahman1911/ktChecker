package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.AnalysisContext
import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.contracts.SnapshotManifest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest

/** Verified owner-export ingress. The COMPLETE permit contract and returned nominal type stay strict. */
class ProjectModelImporter(private val authority: ModelIngressAuthority = ModelIngressAuthority.DENY) {
    fun import(request: ModelImportRequest, rawExport: ByteArray): VerifiedProjectModel =
        importSelected(request, rawExport, ModelExporterAdapter.LEGACY_2_4_21)

    /** Explicit owner-selected experiment; never inferred from versions, JSON or a digest.
     * A COMPLETE owner ingress permit is still mandatory. This grants no compiler/FIR roles. */
    fun importKgp2410(request: ModelImportRequest, rawExport: ByteArray): VerifiedProjectModel =
        importSelected(request, rawExport, ModelExporterAdapter.EXPERIMENTAL_2_4_10)

    private fun importSelected(request: ModelImportRequest, rawExport: ByteArray,
                               adapter: ModelExporterAdapter): VerifiedProjectModel {
        val input = prepareModelImportBytes(request, rawExport, adapter)
        val permit = authority.authorize(input.request)
            ?: throw ProjectModelException("model_ingress_not_authorized", "An exact verified owner Gradle export receipt is required.")
        permit.consume(input.request)
        val facts = readModelImportFacts(input, ModelImportOrigin.Complete(permit))
        return VerifiedProjectModel(input.request.namespace, input.request.snapshot, facts.graph, facts.identity,
            immutable(facts.issues.distinct().sortedWith(compareBy<ModelIssue> { it.scopeId.orEmpty() }.thenBy { it.kind }.thenBy { it.detail })),
            facts.contentEvidenceDigest, facts.roleLayouts, facts.roleRejections,
            verifiedJvmProducedGraph(input.request, facts.graph, facts.identity.modelDigest, facts.issues,
                facts.roleLayouts, facts.producedOrigins))
    }
}

/** Frozen byte/structural facts only. Neither this record nor its digest is importer authority. */
internal class ModelImportBytes(val request: ModelImportRequest, val bytes: ByteArray, val adapter: ModelExporterAdapter)
internal class ModelImportFacts(val graph: GradleProjectExport, val identity: ProjectModelIdentity,
    val issues: List<ModelIssue>, val contentEvidenceDigest: String,
    val roleLayouts: Map<String, VerifiedJvmArgumentLayout>, val roleRejections: Map<String, String>,
    val producedOrigins: Map<String, ModelJvmProducedOutputOrigin>)

internal fun prepareModelImportBytes(request: ModelImportRequest, rawExport: ByteArray,
    adapter: ModelExporterAdapter = ModelExporterAdapter.LEGACY_2_4_21): ModelImportBytes {
    val frozen = freezeRequest(request)
    val bytes = rawExport.copyOf()
    if (bytes.size !in 1..ModelImportValidation.MAX_EXPORT_BYTES || Digests.sha256(bytes) != frozen.exportDigest) {
        throw ProjectModelException("model_export_digest_or_size", "Model export bytes differ from the authorized bounded report.")
    }
    ModelImportValidation.validateRequest(frozen, adapter)
    return ModelImportBytes(frozen, bytes, adapter)
}

/** Shared validation produces only facts. The two public importers choose DISTINCT nominal
 * capabilities after entering through their exact sealed Complete/Observed origin branch. */
internal fun readModelImportFacts(input: ModelImportBytes, origin: ModelImportOrigin): ModelImportFacts {
    input.adapter.requireOrigin(origin)
    val frozenRequest = input.request
    val bytes = input.bytes
    origin.verifySnapshot()
    origin.verifyExecutedExport()
    val text = bytes.toString(Charsets.UTF_8)
    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw ProjectModelException("model_export_utf8", "Invalid model UTF-8.")
    val parsed = try { ContractJson.decodeFromString<GradleProjectExport>(text) }
    catch (e: Exception) { throw ProjectModelException("model_export_schema", "Malformed/unknown model report, not an empty graph.", e) }
    val graph = freezeExport(parsed)
    input.adapter.requirePayload(graph)
    origin.verifyInputs(graph)
    val issues = ModelImportValidation.validateExport(frozenRequest, graph, input.adapter)
    val roleCompilations = graph.compilations.filter { it.jvmArgumentRoles != null }
    val roleOrigins = roleCompilations.associate { it.id to origin.verifyJvmArgumentOrigin(it) }
    val producedCompilations = graph.compilations.filter { it.deferredJvmOutputs.isNotEmpty() }
    val producedOrigins = producedCompilations.associate { it.id to origin.verifyJvmProducedOutputOrigin(it) }
    producedCompilations.forEach { compilation -> producedOrigins[compilation.id]?.let { origin ->
        verifyDeferredJvmOrigin(frozenRequest, graph, compilation, origin, roleOrigins)
    } }
    // verifyDeferredJvmOrigin has checked the ENTIRE graph and rejected every occurrence
    // outside the one consumer classpath/friend role, including same-path digested artifacts.
    val deferredArtifactPaths = producedCompilations.filter { producedOrigins[it.id] != null }
        .flatMap { it.deferredJvmOutputs }.map { it.outputPath }.toSet()
    val roleLayouts = linkedMapOf<String, VerifiedJvmArgumentLayout>()
    val roleRejections = linkedMapOf<String, String>()
    roleCompilations.forEach { compilation ->
        val origin = roleOrigins[compilation.id]
        if (origin == null) roleRejections[compilation.id] = "model_jvm_argument_origin_unavailable"
        else try { roleLayouts[compilation.id] = classifyJvmArgumentLayout(frozenRequest, compilation, origin, graph.generatedInputs, producedOrigins[compilation.id]) }
        catch (error: ProjectModelException) {
            if (error.code != "model_jvm_arguments_unsupported") throw error
            // Retain every raw fact and every preexisting global issue. An unselected empty
            // source/cp or unsupported executable layout is NOT erased or a new graph gap.
            // The selected compiler binding separately requires its live checked layout.
            roleRejections[compilation.id] = error.code
        }
    }
    val artifacts = graph.buildscriptArtifacts + graph.externalConfigurationArtifacts + graph.compilations.flatMap { compilation ->
        compilation.classpath + compilation.friendArtifacts + compilation.auxiliaryArtifacts + compilation.toolchain?.let {
            it.compilerArtifacts + it.runtimeArtifacts + it.compilerPlugins
        }.orEmpty()
    }
    artifacts.distinctBy { it.path to it.digest }.forEach { artifact ->
        if (artifact.digest == null && artifact.path in deferredArtifactPaths) {
            // No byte claim is made. The exact typed pending obligation remains in issues.
            // Old artifact_unavailable/exporter gaps are never filtered or reinterpreted.
        } else if (artifact.digest == null) {
            issues += ModelIssue(if (artifact in graph.buildscriptArtifacts || artifact in graph.externalConfigurationArtifacts) "buildscript_artifact_unavailable" else "artifact_unavailable",
                ModelImportValidation.artifactScope(graph, artifact), "No immutable bytes for ${artifact.coordinate}.")
        } else {
            origin.verifyArtifact(artifact)
            if (hashFile(Path.of(artifact.path), ModelImportValidation.MAX_ARTIFACT_BYTES, artifact.size) != artifact.digest) {
                throw ProjectModelException("model_artifact_changed", "Resolved classpath/compiler/plugin/runtime bytes changed during ingress.")
            }
        }
    }
    // Registered capture/origin and external immutable artifact authority remain required;
    // these byte checks are additional drift detection, not a before/after coherence proof.
    origin.verifySnapshot()
    origin.verifyExecutedExport()
    origin.verifyInputs(graph)
    roleCompilations.forEach { compilation ->
        if (origin.verifyJvmArgumentOrigin(compilation) !== roleOrigins[compilation.id]) {
            throw ProjectModelException("model_jvm_argument_origin_changed", "Original argument-role owner authority changed during ingress.")
        }
    }
    producedCompilations.forEach { compilation ->
        if (origin.verifyJvmProducedOutputOrigin(compilation) !== producedOrigins[compilation.id]) {
            throw ProjectModelException("model_jvm_produced_output_origin_changed", "Deferred owner-edge authority changed during ingress.")
        }
    }
    artifacts.filter { it.digest != null }.distinctBy { it.path to it.digest }.forEach(origin::verifyArtifact)
    val sourceDigest = canonicalDigest(ContractJson.encodeToJsonElement(frozenRequest.snapshot))
    val modelDigest = canonicalDigest(ContractJson.encodeToJsonElement(graph))
    val dependencyDigest = canonicalDigest(ContractJson.encodeToJsonElement(graph.compilations.map {
        it.id to mapOf("classpath" to it.classpath, "friends" to it.friendArtifacts, "auxiliary" to it.auxiliaryArtifacts)
    }))
    val toolchainDigest = canonicalDigest(ContractJson.encodeToJsonElement(graph.compilations.map { it.id to it.toolchain }))
    val identity = ProjectModelIdentity(frozenRequest.snapshot.snapshotId, sourceDigest, modelDigest, dependencyDigest,
        toolchainDigest, frozenRequest.exporterDigest, origin.gradleRuntimeDigest, frozenRequest.configurationDigest)
    val certificate = canonicalDigest(ContractJson.encodeToJsonElement(listOf(frozenRequest.digest(), origin.gradleRuntimeDigest,
        sourceDigest, modelDigest, dependencyDigest, toolchainDigest)))
    return ModelImportFacts(graph, identity, immutable(issues), certificate, immutableMap(roleLayouts),
        immutableMap(roleRejections), immutableMap(producedOrigins.mapNotNull { (id, value) -> value?.let { id to it } }.toMap()))
}

/** Authority-free bounded schema, structural and byte validators shared by both nominal paths. */
internal object ModelImportValidation {
    fun validateRequest(request: ModelImportRequest, adapter: ModelExporterAdapter = ModelExporterAdapter.LEGACY_2_4_21) {
        listOf(request.namespace.ownerId, request.namespace.workspaceId, request.namespace.worktreeId, request.namespace.lineageId,
            request.executionId).forEach { identifier(it) }
        listOf(request.snapshot.snapshotId, request.exportDigest, request.exporterDigest, request.configurationDigest).forEach { digest(it) }
        request.snapshot.parentSnapshotId?.let(::digest)
        identifier(request.snapshot.captureKind)
        if (request.exporterDigest != adapter.scriptDigest()) {
            throw ProjectModelException("model_exporter_unavailable", "The authorized exporter is not this version-pinned adapter.")
        }
        if (request.snapshot.ownerId != request.namespace.ownerId || request.snapshot.workspaceId != request.namespace.workspaceId ||
            request.snapshot.worktreeId != request.namespace.worktreeId) throw ProjectModelException("model_namespace_mismatch", "Model capture belongs to another registered namespace.")
        val root = Path.of(request.materializedRoot)
        if (!root.isAbsolute || root.normalize() != root || Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw ProjectModelException("model_materialization_invalid", "A registered immutable real materialization is required.")
        }
        unique(request.snapshot.files.map { it.path }, "snapshot paths")
        requireBound(request.snapshot.files.map { it.path } == request.snapshot.files.map { it.path }.sorted(), "canonical snapshot path ordering")
        requireBound(request.snapshot.files.size <= 50_000, "snapshot file count")
        request.snapshot.declaredRoots.forEach(::identifier)
        request.snapshot.files.forEach { file -> logicalPath(file.path); digest(file.digest); requireBound(file.size >= 0, "snapshot size") }
    }

    fun validateExport(request: ModelImportRequest, graph: GradleProjectExport,
        adapter: ModelExporterAdapter = ModelExporterAdapter.LEGACY_2_4_21): MutableList<ModelIssue> {
        if (graph.schemaVersion != 1 || graph.exporterId != adapter.exporterId) throw ProjectModelException("model_export_schema", "Unknown Gradle model schema/adapter.")
        identifier(graph.gradleVersion)
        requireBound(graph.modules.size in 1..1024 && graph.sourceSets.size <= 4096 && graph.compilations.size <= 4096, "model graph size")
        unique(graph.modules.map { it.id }, "modules"); unique(graph.sourceSets.map { it.id }, "source sets"); unique(graph.compilations.map { it.id }, "compilations")
        requireBound(graph.compilations.map { listOf(it.moduleId, it.target, it.name, it.defaultSourceSetId) }.distinct().size == graph.compilations.size, "unique compilation context identities")
        unique(graph.configurationInputs.map { it.path }, "configuration inputs")
        val modules = graph.modules.associateBy { it.id }
        val sets = graph.sourceSets.associateBy { it.id }
        val compilations = graph.compilations.associateBy { it.id }
        val moduleCompilations = graph.compilations.groupBy { it.moduleId }
        val issues = graph.issues.toMutableList()
        issues += adapter.additionalIssues(graph)
        if (graph.compilations.isEmpty()) issues += ModelIssue("compilation_model_empty", null,
            "No actual compilation was exported; graph completeness and admission are nonvacuous.")
        val scopeIds = modules.keys + sets.keys + compilations.keys + graph.generatedInputs.map { it.taskPath }
        graph.issues.forEach { issue ->
            identifier(issue.kind); requireBound(issue.detail.length in 1..8192, "model issue detail")
            if (issue.scopeId != null && issue.scopeId !in scopeIds) throw ProjectModelException("model_issue_scope_invalid", "An unknown issue scope cannot conceal a model gap.")
        }
        graph.includedBuilds.forEach { identifier(it); issues += ModelIssue("included_build_unmodeled", null, "Included/composite build $it is not silently omitted.") }
        request.snapshot.unknownInputs.forEach { issues += ModelIssue("snapshot_input_unknown", null, it) }
        request.snapshot.scopeExclusions.forEach { issues += ModelIssue("snapshot_scope_excluded", null, it) }
        if (request.snapshot.captureKind !in setOf("immutable_overlay", "coordinated_capture")) issues += ModelIssue("snapshot_capture_unverified", null,
            "Only a registered coherent immutable overlay/coordinated capture can establish compilation completeness.")
        graph.modules.groupBy { it.directory }.filterValues { it.size > 1 }.forEach { (_, shared) ->
            issues += ModelIssue("module_directory_overlap", null, "Different actual modules share a directory: ${shared.map { it.id }.sorted().joinToString()}.")
        }
        graph.modules.forEach { module ->
            identifier(module.id); logicalPath(module.directory, true); module.buildFile?.let { logicalPath(it) }
            module.pluginIds.forEach(::identifier)
            if (module.buildFile != null && graph.configurationInputs.none { it.path == module.buildFile }) {
                issues += ModelIssue("build_configuration_missing", module.id, "Build file is absent from captured configuration inputs.")
            }
        }
        val capturedFiles = request.snapshot.files.associateBy { it.path }
        val verifiedFiles = mutableSetOf<ModelFile>()
        fun verifyFile(file: ModelFile) {
            logicalPath(file.path); digest(file.digest); requireBound(file.size in 0..MAX_SOURCE_BYTES, "model input size")
            val captured = capturedFiles[file.path]
            if (captured == null || captured.digest != file.digest || captured.size != file.size) {
                throw ProjectModelException("model_input_not_captured", "Model file ${file.path} is not the exact authorized captured byte identity.")
            }
            if (verifiedFiles.add(file)) {
                val path = Path.of(request.materializedRoot).resolve(file.path)
                if (hashFile(path, MAX_SOURCE_BYTES, file.size) != file.digest) throw ProjectModelException("model_source_changed", "Captured model input bytes changed.")
            }
        }
        graph.configurationInputs.forEach(::verifyFile)
        // Conservatively bind every recognizable captured settings/build/configuration input;
        // the trusted origin callback additionally verifies executable/dynamic build inputs.
        request.snapshot.files.filter { configurationPath(it.path) }.forEach { file ->
            if (graph.configurationInputs.none { it.path == file.path }) issues += ModelIssue("build_configuration_missing", null, "Captured configuration ${file.path} was not visited by the exporter.")
        }
        graph.sourceSets.forEach { sourceSet ->
            identifier(sourceSet.id); identifier(sourceSet.name)
            if (sourceSet.moduleId !in modules || sourceSet.dependsOn.any { it !in sets || sets[it]?.moduleId != sourceSet.moduleId }) {
                throw ProjectModelException("model_reference_invalid", "Source-set owner/refinement references are unknown or cross-module.")
            }
            unique(sourceSet.dependsOn, "refinement edges"); unique(sourceSet.sources.map { it.path }, "source-set source paths")
            unique(sourceSet.resources.map { it.path }, "source-set resources")
            sourceSet.sourceRoots.forEach { logicalPath(it, true) }; sourceSet.resourceRoots.forEach { logicalPath(it, true) }
            unique(sourceSet.sourceRoots, "source roots"); unique(sourceSet.resourceRoots, "resource roots")
            if (sourceSet.sources.any { file -> sourceSet.sourceRoots.none { root -> memberOfRoot(file.path, root) } } ||
                sourceSet.resources.any { file -> sourceSet.resourceRoots.none { root -> memberOfRoot(file.path, root) } }) {
                throw ProjectModelException("model_membership_invalid", "Actual source/resource members must belong to their exported source roots.")
            }
            (sourceSet.sources + sourceSet.resources).forEach(::verifyFile)
        }
        graph.sourceSets.flatMap { sourceSet -> sourceSet.sources.map { it.path to sourceSet.id } }.groupBy { it.first }
            .filterValues { values -> values.map { it.second }.distinct().size > 1 }.forEach { (path, _) ->
                issues += ModelIssue("source_set_membership_ambiguous", null, "Captured source $path belongs to more than one actual source set; ownership is not inferred.")
            }
        ensureRefinementDag(sets)
        val consumedSets = graph.compilations.flatMap { it.sourceSetIds }.toSet()
        graph.sourceSets.filter { it.id !in consumedSets && (it.sources.isNotEmpty() || it.resources.isNotEmpty()) }.forEach { sourceSet ->
            issues += ModelIssue("source_set_unconsumed", sourceSet.id, "Observed nonempty source set has no actual compilation; unvisited sources are not silently retired.")
        }
        graph.compilations.forEach { compilation ->
            listOf(compilation.id, compilation.platform, compilation.name, compilation.compileTaskPath,
                compilation.dependencyConfiguration).forEach(::identifier)
            if (compilation.moduleId !in modules || compilation.defaultSourceSetId !in sets ||
                sets[compilation.defaultSourceSetId]?.moduleId != compilation.moduleId || compilation.sourceSetIds.any {
                    it !in sets || sets[it]?.moduleId != compilation.moduleId
                }) throw ProjectModelException("model_reference_invalid", "Compilation references an unknown module/source set.")
            if (compilation.target.isEmpty()) {
                val module = modules.getValue(compilation.moduleId)
                // KotlinJvmTarget.name is exactly "" in the pinned JVM-only plugin. This
                // is an actual target identity, not a platform alias or a null wildcard.
                // A bare empty string cannot grant this domain to MPP/Android/mixed modules.
                requireBound("org.jetbrains.kotlin.jvm" in module.pluginIds && module.pluginIds.none {
                    it in setOf("org.jetbrains.kotlin.multiplatform", "org.jetbrains.kotlin.android") || it.startsWith("com.android.")
                } && moduleCompilations.getValue(module.id).all { it.target.isEmpty() && it.platform == "jvm" },
                    "unnamed JVM-only target domain")
            } else identifier(compilation.target)
            unique(compilation.sourceSetIds, "compilation source sets"); unique(compilation.sourceFiles.map { it.path }, "compilation sources")
            val closure = refinementClosure(compilation.sourceSetIds, sets)
            if (!compilation.sourceSetIds.containsAll(closure) || compilation.defaultSourceSetId !in compilation.sourceSetIds) {
                issues += ModelIssue("refinement_membership_incomplete", compilation.id, "Compilation omits default/refined source-set members.")
            }
            val expectedSources = compilation.sourceSetIds.flatMap { sets.getValue(it).sources }.distinctBy { it.path }.sortedBy { it.path }
            if (compilation.sourceFiles.sortedBy { it.path } != expectedSources) issues += ModelIssue("compilation_members_incomplete", compilation.id,
                "Actual compile-task members differ from the complete declared source-set union; no text/path inference repairs it.")
            compilation.sourceFiles.forEach(::verifyFile)
            unique(compilation.auxiliaryInputFiles.map { it.path }, "auxiliary compilation inputs")
            compilation.auxiliaryInputFiles.forEach(::verifyFile)
            compilation.projectDependencies.forEach { dependency ->
                if (dependency.moduleId !in modules || dependency.compilationId != null && dependency.compilationId !in compilations) {
                    throw ProjectModelException("model_reference_invalid", "Resolved project producer is outside the exported model.")
                }
                dependency.compilationId?.let { producerId ->
                    val producer = compilations.getValue(producerId)
                    if (producer.moduleId != dependency.moduleId || dependency.selectedVariant !in producer.producedVariants) {
                        throw ProjectModelException("model_producer_variant_invalid", "The exact producer compilation must own the resolved module and exported variant.")
                    }
                }
                if (dependency.compilationId == null) issues += ModelIssue("project_variant_unmapped", compilation.id,
                    "Resolved ${dependency.moduleId}/${dependency.selectedVariant} producer compilation is unknown.")
                identifier(dependency.selectedVariant)
                dependency.variantAttributes.forEach { (key, value) -> identifier(key); identifier(value) }
            }
            unique(compilation.producedVariants, "produced variant identities"); compilation.producedVariants.forEach(::identifier)
            unique(compilation.associatedCompilationIds, "associated compilations")
            if (compilation.associatedCompilationIds.any { it !in compilations || it == compilation.id || compilations[it]?.moduleId != compilation.moduleId }) {
                throw ProjectModelException("model_reference_invalid", "Associated compilations must name actual distinct compilations in this module.")
            }
            if (compilation.toolchain == null || compilation.toolchain.compilerVersion == null || compilation.toolchain.compilerArtifacts.isEmpty() ||
                compilation.toolchain.runtimeArtifacts.isEmpty()) issues += ModelIssue("toolchain_unavailable", compilation.id, "Complete compiler/runtime identity is unavailable.")
            requireBound(compilation.compilerArguments.size <= 16_384, "compiler argument count")
            compilation.compilerArguments.forEach { requireBound(it.length <= 8192 && '\u0000' !in it, "compiler argument") }
            requireBound(compilation.deferredJvmOutputs.size <= 1, "bounded preview producer roles")
            compilation.deferredJvmOutputs.forEach { ref ->
                identifier(ref.producerCompilationId); identifier(ref.producerCompileTaskPath); identifier(ref.outputPath)
                requireBound(ref.producerCompilationId in compilations && ref.producerCompilationId != compilation.id &&
                    ref.classpathIndex in compilation.classpath.indices && Path.of(ref.outputPath).isAbsolute &&
                    Path.of(ref.outputPath).normalize().toString() == ref.outputPath &&
                    (!ref.originalFriendPresent || ref.friend) &&
                    ref.originalFriendPresent == ref.declaredFriendPresent, "deferred output role")
                issues += deferredJvmIssue(compilation.id, ref)
            }
            compilation.jvmArgumentRoles?.let { roles ->
                requireBound(roles.schemaVersion == 1 && roles.adapterId == JVM_ARGUMENT_ROLES_ADAPTER, "original JVM role schema")
                requireBound(roles.sourcePaths.size <= 50_000, "original JVM source role count")
                roles.sourcePaths.forEach { logicalPath(it) }
                requireBound(roles.friendPathOperands.size <= 1, "bounded original friend operands")
                roles.friendPathOperands.forEach(::identifier)
                listOf(roles.compileTaskPath, roles.destinationPath, roles.selectedJdkHome, roles.pluginDataState).forEach(::identifier)
                listOfNotNull(roles.classpathOperand, roles.jdkHomeOperand).forEach {
                    requireBound(it.length <= 8192 && '\u0000' !in it, "original nullable JVM path operand")
                }
            }
        }
        graph.generatedInputs.forEach { generated ->
            identifier(generated.taskPath); generated.sourceSetIds.forEach { if (it !in sets) throw ProjectModelException("model_reference_invalid", "Unknown generated-source membership.") }
            unique(generated.sourceSetIds, "generated-source memberships")
            unique(generated.inputFiles.map { it.path }, "generator input paths"); unique(generated.outputFiles.map { it.path }, "generator output paths")
            generated.inputProperties.forEach { (key, value) -> identifier(key); requireBound(value.length <= 8192 && '\u0000' !in value, "generated input property") }
            (generated.inputFiles + generated.outputFiles).forEach(::verifyFile)
            generated.outputRoots.forEach { logicalPath(it, true) }
            if (generated.outputFiles.any { file -> generated.outputRoots.none { root -> memberOfRoot(file.path, root) || file.path == root } }) {
                throw ProjectModelException("model_membership_invalid", "Generated output files must be in their actual output roots.")
            }
            if (generated.state != "captured" || generated.outputFiles.isEmpty()) issues += ModelIssue("generated_input_unavailable", generated.taskPath,
                "Generated inputs/outputs are not coherently captured; generation is not implicitly executed.")
        }
        unique(graph.generatedInputs.map { it.taskPath }, "generation task identities")
        val artifacts = graph.buildscriptArtifacts + graph.externalConfigurationArtifacts + graph.compilations.flatMap { it.classpath + it.friendArtifacts + it.auxiliaryArtifacts +
            it.toolchain?.let { toolchain -> toolchain.compilerArtifacts + toolchain.runtimeArtifacts + toolchain.compilerPlugins }.orEmpty() }
        artifacts.forEach { artifact ->
            identifier(artifact.coordinate); requireBound(Path.of(artifact.path).isAbsolute && Path.of(artifact.path).normalize().toString() == artifact.path, "normalized absolute artifact path")
            artifact.digest?.let(::digest); artifact.size?.let { requireBound(it in 0..MAX_ARTIFACT_BYTES, "artifact size") }
            requireBound((artifact.digest == null) == (artifact.size == null), "artifact byte identity and size")
        }
        requireBound(artifacts.size <= 16_384, "artifact count")
        return issues
    }

    fun artifactScope(graph: GradleProjectExport, artifact: ModelArtifact): String? = graph.compilations.singleOrNull {
        artifact in it.classpath || artifact in it.friendArtifacts || artifact in it.auxiliaryArtifacts || it.toolchain?.let { chain ->
            artifact in chain.compilerArtifacts || artifact in chain.runtimeArtifacts || artifact in chain.compilerPlugins
        } == true
    }?.id

    const val MAX_EXPORT_BYTES = 16 * 1024 * 1024
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
}

internal fun compilationContext(model: VerifiedProjectModel, scope: RuleContext, configurationDigest: String): VerifiedCompilationContext {
    digest(configurationDigest)
    if (configurationDigest != model.identity.configurationDigest) throw ProjectModelException("model_configuration_mismatch", "Compilation context must retain the exact owner policy identity bound at model ingress.")
    val matches = model.export.compilations.filter { it.moduleId == scope.module && it.target == scope.target && it.name == scope.compilation &&
        model.sourceSet(it.defaultSourceSetId)?.name == scope.sourceSet }
    if (matches.size != 1) return VerifiedCompilationContext(null, null, ModelCompleteness.UNSUPPORTED,
        listOf(ModelIssue("compilation_context_unknown", null, "No unique actual exported target/compilation/default source set matches this context.")), null)
    val compilation = matches.single()
    // Full-project completeness needs the entire observed model. Do not hide another target's
    // gap while returning a context marked incomplete for an unexplained reason.
    val issues = model.issues
    val fullyModeled = issues.isEmpty()
    val chain = compilation.toolchain
    val dependencyDigest = canonicalDigest(ContractJson.encodeToJsonElement(mapOf("classpath" to compilation.classpath, "friends" to compilation.friendArtifacts, "auxiliary" to compilation.auxiliaryArtifacts)))
    val toolchainDigest = chain?.takeIf { (it.compilerArtifacts + it.runtimeArtifacts + it.compilerPlugins).all { artifact -> artifact.digest != null } }
        ?.let { canonicalDigest(ContractJson.encodeToJsonElement(it)) }
    val context = AnalysisContext(scope = scope, compilerVersion = chain?.compilerVersion,
        classpath = immutable(compilation.classpath.mapNotNull { artifact -> artifact.digest?.let { ArtifactIdentity(artifact.coordinate, it, artifact.path) } }),
        compilerArguments = immutable(compilation.compilerArguments), modelDigest = model.identity.modelDigest, dependencyDigest = dependencyDigest,
        toolchainDigest = toolchainDigest, configurationDigest = configurationDigest, fullProject = fullyModeled,
        policies = immutableMap(mapOf("ktchecker.model" to model.export.exporterId, "ktchecker.model-certificate" to model.ingressCertificate)),
        compilationFiles = immutable(compilation.sourceFiles.map { it.path }))
    val certificate = if (fullyModeled) canonicalDigest(ContractJson.encodeToJsonElement(listOf(model.ingressCertificate, compilation.id,
        context.modelDigest.orEmpty(), dependencyDigest, toolchainDigest.orEmpty(), configurationDigest))) else null
    return VerifiedCompilationContext(compilation, context, if (fullyModeled) ModelCompleteness.COMPLETE else ModelCompleteness.UNAVAILABLE, issues, certificate)
}

internal fun selectedGraphIssues(model: VerifiedProjectModel, ids: Collection<String>): List<ModelIssue> {
    if (ids.isEmpty() || ids.distinct().size != ids.size || ids.any { model.compilation(it) == null }) {
        return listOf(ModelIssue("graph_selection_invalid", null, "An explicit nonempty set of actual compilation identities is required."))
    }
    val nonGraphKinds = setOf("artifact_unavailable", "toolchain_unavailable", "native_toolchain_unmodeled", "compiler_arguments_unmodeled")
    // An unselected unresolved module may close a path/SCC into the selected graph.
    // Scope names are not evidence that its unknown edges are irrelevant.
    return immutable(model.issues.filter { it.kind !in nonGraphKinds })
}

internal fun freezeRequest(request: ModelImportRequest): ModelImportRequest {
    val detached = ContractJson.decodeFromString<ModelImportRequest>(ContractJson.encodeToString(request))
    return detached.copy(snapshot = detached.snapshot.copy(files = immutable(detached.snapshot.files), declaredRoots = immutable(detached.snapshot.declaredRoots),
        scopeExclusions = immutable(detached.snapshot.scopeExclusions), unknownInputs = immutable(detached.snapshot.unknownInputs)))
}

internal fun freezeExport(graph: GradleProjectExport): GradleProjectExport = graph.copy(
    modules = immutable(graph.modules.map { it.copy(pluginIds = immutable(it.pluginIds)) }),
    sourceSets = immutable(graph.sourceSets.map { it.copy(dependsOn = immutable(it.dependsOn), sourceRoots = immutable(it.sourceRoots), resourceRoots = immutable(it.resourceRoots),
        sources = immutable(it.sources), resources = immutable(it.resources)) }),
    compilations = immutable(graph.compilations.map { compilation -> compilation.copy(sourceSetIds = immutable(compilation.sourceSetIds), sourceFiles = immutable(compilation.sourceFiles),
        classpath = immutable(compilation.classpath), projectDependencies = immutable(compilation.projectDependencies.map { it.copy(variantAttributes = immutableMap(it.variantAttributes)) }),
        toolchain = compilation.toolchain?.let { it.copy(compilerArtifacts = immutable(it.compilerArtifacts), runtimeArtifacts = immutable(it.runtimeArtifacts), compilerPlugins = immutable(it.compilerPlugins)) },
        compilerArguments = immutable(compilation.compilerArguments), friendArtifacts = immutable(compilation.friendArtifacts), producedVariants = immutable(compilation.producedVariants),
        associatedCompilationIds = immutable(compilation.associatedCompilationIds), auxiliaryInputFiles = immutable(compilation.auxiliaryInputFiles),
        auxiliaryArtifacts = immutable(compilation.auxiliaryArtifacts),
        jvmArgumentRoles = compilation.jvmArgumentRoles?.let { it.copy(sourcePaths = immutable(it.sourcePaths),
            friendPathOperands = immutable(it.friendPathOperands)) }, deferredJvmOutputs = immutable(compilation.deferredJvmOutputs)) }),
    configurationInputs = immutable(graph.configurationInputs),
    generatedInputs = immutable(graph.generatedInputs.map { it.copy(sourceSetIds = immutable(it.sourceSetIds), inputFiles = immutable(it.inputFiles),
        outputFiles = immutable(it.outputFiles), outputRoots = immutable(it.outputRoots), inputProperties = immutableMap(it.inputProperties)) }),
    buildscriptArtifacts = immutable(graph.buildscriptArtifacts), externalConfigurationArtifacts = immutable(graph.externalConfigurationArtifacts),
    includedBuilds = immutable(graph.includedBuilds), issues = immutable(graph.issues),
)

internal fun configurationPath(path: String): Boolean = path.endsWith(".gradle") || path.endsWith(".gradle.kts") || path.endsWith("gradle.properties") ||
    path.endsWith(".toml") || path.split('/').any { it in setOf("buildSrc", "dependency-locks", "gradle-wrapper.properties") }

internal fun refinementClosure(ids: Collection<String>, sets: Map<String, ModelSourceSet>): Set<String> {
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(ids)
    while (queue.isNotEmpty()) { val id = queue.removeFirst(); if (result.add(id)) queue.addAll(sets[id]?.dependsOn.orEmpty()) }
    return result
}

internal fun ensureRefinementDag(sets: Map<String, ModelSourceSet>) {
    val pending = sets.mapValues { it.value.dependsOn.size }.toMutableMap()
    val reverse = mutableMapOf<String, MutableList<String>>()
    sets.values.forEach { sourceSet -> sourceSet.dependsOn.forEach { reverse.getOrPut(it) { mutableListOf() } += sourceSet.id } }
    val queue = ArrayDeque(pending.filterValues { it == 0 }.keys)
    var visited = 0
    while (queue.isNotEmpty()) { val id = queue.removeFirst(); visited++; reverse[id].orEmpty().forEach { dependant ->
        pending[dependant] = pending.getValue(dependant) - 1; if (pending[dependant] == 0) queue.addLast(dependant)
    } }
    if (visited != sets.size) throw ProjectModelException("model_refinement_cycle", "Actual source-set refinement model must be a DAG; no inferred repair.")
}

internal fun identifier(value: String) { requireBound(value.length in 1..4096 && !value.isBlank() && value.none { it.isISOControl() }, "identifier") }
internal fun digest(value: String) { requireBound(Digests.valid(value), "SHA-256 identity") }
internal fun unique(values: List<String>, description: String) { requireBound(values.size == values.distinct().size, "unique $description") }
internal fun requireBound(valid: Boolean, description: String) { if (!valid) throw ProjectModelException("model_invalid", "Invalid $description.") }
internal fun logicalPath(path: String, allowEmpty: Boolean = false) {
    if (allowEmpty && path.isEmpty()) return
    requireBound(path.length in 1..4096 && !path.startsWith('/') && '\\' !in path && path.none { it.isISOControl() } &&
        path.split('/').none { it.isEmpty() || it in setOf(".", "..") } && !Regex("^[A-Za-z]:").containsMatchIn(path), "relative logical model path")
}

private fun memberOfRoot(path: String, root: String): Boolean = root.isEmpty() || path.startsWith("$root/")

internal fun hashFile(path: Path, limit: Long, expectedSize: Long? = null): String {
    if (!path.isAbsolute || path.normalize() != path || path.nameCount == 0) throw ProjectModelException("model_file_unsafe", "An anchored normalized absolute input is required.")
    try {
        Files.newDirectoryStream(path.root).use { directory ->
            val secure = directory as? SecureDirectoryStream<Path>
                ?: throw ProjectModelException("model_secure_io_unavailable", "This host cannot anchor no-follow model input reads.")
            fun read(parent: SecureDirectoryStream<Path>, index: Int): String {
                val name = path.getName(index)
                if (index < path.nameCount - 1) return parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { read(it, index + 1) }
                val before = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                if (!before.isRegularFile || before.isSymbolicLink) throw ProjectModelException("model_file_unsafe", "Immutable input must be a regular no-follow file.")
                requireBound(before.size() in 0..limit && (expectedSize == null || expectedSize == before.size()), "bounded exact model file size")
                val sha = MessageDigest.getInstance("SHA-256")
                parent.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    var count = 0L
                    while (true) { buffer.clear(); val amount = channel.read(buffer); if (amount < 0) break; count += amount
                        if (count > before.size()) throw ProjectModelException("model_file_changed", "Model file grew while reading.")
                        buffer.flip(); sha.update(buffer)
                    }
                    if (count != before.size() || channel.size() != before.size()) throw ProjectModelException("model_file_changed", "Model file changed while reading.")
                }
                val after = parent.getFileAttributeView(name, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
                if (after.fileKey() != before.fileKey() || after.size() != before.size() || after.lastModifiedTime() != before.lastModifiedTime() || !after.isRegularFile) {
                    throw ProjectModelException("model_file_changed", "Model input was replaced or changed while reading.")
                }
                return "sha256:" + sha.digest().joinToString("") { "%02x".format(it) }
            }
            return read(secure, 0)
        }
    } catch (e: ProjectModelException) { throw e }
    catch (e: Exception) { throw ProjectModelException("model_file_unsafe", "Model input requires existing no-follow directories and regular bytes.", e) }
}
