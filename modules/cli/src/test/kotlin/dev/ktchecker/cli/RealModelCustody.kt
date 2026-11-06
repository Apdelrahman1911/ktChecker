package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmModelBinding
import dev.ktchecker.compiler.live.model.ModelCompilerJobMaterialization
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Independently retains genuine pre-submit parent/binding facts, not no-op custody callbacks.
 * The original producer source/dependency paths are captured BEFORE kernel submission and are
 * never reread by materialization hooks after acceptance. Whole K and E bytes are checked instead.
 */
internal class RealModelCustody(private val generation: () -> RealModelGeneration,
    private val brokerRoot: Path, private val evidenceRoot: Path) : OwnerModelMaterializationCustody {
    private class Expected(val phase: Int, val original: AnalysisInput, val binding: KotlinJvmModelBinding,
        val compilerRules: List<String>, val deadline: OwnedApplicationDeadline,
        val originalFileKeys: Set<String>, val dependencySizes: List<Long>) {
        var materialization: ModelCompilerJobMaterialization? = null
        var pins: List<RealModelFilePin> = emptyList()
        var rootKeys: Map<Path, String> = emptyMap()
        var expectedRequestDigest: String? = null
        var registrationNanos: Long? = null
        val verificationCalls = AtomicInteger()
    }
    private val lock = Any()
    private val expected = linkedMapOf<String, Expected>()

    /** Binding is independently importer-created and bound to the same installation object.
     * Identity equality with the actual worker request is checked below; it is not a new route grant.
     */
    fun beforeSubmit(phase: Int, prepared: PreparedOwnerModelPlan, model: VerifiedProjectModel, deadline: OwnedApplicationDeadline) {
        val app = generation()
        val plan = prepared.assembly.plan
        realExpect(phase in 1..2 && plan.slots.size == 1 && prepared.preparationReasons.isEmpty(), "real_model_custody_plan_not_executable")
        val original = plan.slots.single().input
        val catalog = Catalog()
        realExpect(original.requiredRuleIds == catalog.ids.sorted() && OwnerFullCatalog1665.accepts(original.requiredRuleIds),
            "real_model_full_catalog_not_preserved")
        val active = validatedActivation(original, catalog)
        val rules = original.requiredRuleIds.filter { active[it]?.active == true && it in app.runtime.provider.supportedRuleIds }
        realExpect(RealModelFixture.RULE in rules && rules.isNotEmpty(), "real_model_unresolved_rule_inactive")
        val binding = KotlinJvmModelBinding.verifyAndBind(model, RealModelFixture.COMPILATION, app.runtime.installation)
        realExpect(binding.argumentLayoutDigest != null, "real_model_genuine_original_argv_layout_required")
        realExpect(original.context.compilerArguments == model.compilation(RealModelFixture.COMPILATION)?.compilerArguments,
            "real_model_original_argv_changed")
        val sourceKeys = original.snapshot.files.map { file ->
            RealModelFilePin.capture(realPath(original.materializedRoot).resolve(file.path), file.digest, file.size).fileKey
        }
        val dependencies = original.context.classpath.map { artifact ->
            RealModelFilePin.capture(realPath(artifact.path), artifact.digest, Files.size(realPath(artifact.path)))
        }
        val entry = Expected(phase, original, binding, rules, deadline, (sourceKeys + dependencies.map { it.fileKey }).toSet(), dependencies.map { it.bytes })
        realExpect(entry.originalFileKeys.size == sourceKeys.size + dependencies.size, "real_model_original_files_alias")
        synchronized(lock) {
            realExpect(expected.size == phase - 1 && original.analysisKey() !in expected, "real_model_custody_registration_replayed")
            expected[original.analysisKey()] = entry
        }
    }
    override fun register(materialization: ModelCompilerJobMaterialization) {
        val entry = synchronized(lock) {
            val selected = expected[materialization.original.analysisKey()] ?: throw RealModelProblem("real_model_custody_unregistered_parent")
            realExpect(selected.materialization == null, "real_model_custody_materialization_replayed")
            selected.materialization = materialization
            selected.registrationNanos = System.nanoTime()
            selected
        }
        validateRelation(entry, materialization)
        val k = materialization.archived; val e = materialization.effective
        val paths = listOf(realPath(k.materializedRoot), realPath(e.materializedRoot))
        val files = paths.flatMap { root -> k.snapshot.files.map { file -> RealModelFilePin.capture(root.resolve(file.path), file.digest, file.size) } } +
            listOf(k, e).flatMap { input -> input.context.classpath.mapIndexed { index, artifact ->
                RealModelFilePin.capture(realPath(artifact.path), artifact.digest, entry.dependencySizes[index])
            } }
        realExpect(files.map { it.fileKey }.distinct().size == files.size && files.none { it.fileKey in entry.originalFileKeys },
            "real_model_custody_copy_not_independent")
        val roots = listOf(materialization.snapshotRoot, materialization.originalClasspathRoot, materialization.originalOutputRoot,
            materialization.jobRoot, materialization.sourceRoot, materialization.classpathRoot, materialization.outputRoot).filterNotNull()
        entry.rootKeys = roots.associate { realPath(it.path()) to it.fileKey() }
        realExpect(entry.rootKeys.values.distinct().size == roots.size, "real_model_custody_root_alias")
        entry.pins = files
        realExpect(Files.list(realPath(materialization.outputRoot.path())).use { !it.findAny().isPresent }, "real_model_job_output_not_fresh")
        entry.expectedRequestDigest = reconstructRequest(entry, materialization)
        verify(materialization)
        realWriteJson(evidenceRoot.resolve("phase-${entry.phase}-materialization-registered.json"), evidence(entry))
    }
    override fun verify(materialization: ModelCompilerJobMaterialization) {
        val entry = synchronized(lock) { expected[materialization.original.analysisKey()] }
            ?: throw RealModelProblem("real_model_custody_unregistered_parent")
        realExpect(entry.materialization === materialization, "real_model_custody_object_changed")
        validateRelation(entry, materialization)
        entry.binding.verifyInstallation()
        entry.rootKeys.forEach { (path, key) -> realExpect(realKey(path, true) == key, "real_model_custody_root_changed") }
        entry.pins.forEach { it.verify() }
        listOf(materialization.archived, materialization.effective).forEach { input ->
            val root = realPath(input.materializedRoot)
            realExpect(realRegularFiles(root).map { root.relativize(it).toString() } == input.snapshot.files.map { it.path },
                "real_model_custody_whole_snapshot_changed")
        }
        realExpect(entry.expectedRequestDigest == reconstructRequest(entry, materialization), "real_model_custody_request_changed")
        entry.verificationCalls.incrementAndGet()
    }
    private fun validateRelation(entry: Expected, value: ModelCompilerJobMaterialization) {
        val p = entry.original; val k = value.archived; val c = value.input; val e = value.effective
        realExpect(value.original == p && OwnerFullCatalog1665.accepts(p.requiredRuleIds) && p.execution == null && p.outputRoot == null,
            "real_model_custody_parent_changed")
        val archiveSource = realPath(k.materializedRoot)
        realExpect(archiveSource.fileName.toString() == "sources" && archiveSource.parent.parent == brokerRoot.resolve("inputs") &&
            archiveSource.parent.fileName.toString().matches(Regex("[0-9a-f]{64}")), "real_model_custody_not_kernel_archive")
        val kOutput = realPath(requireNotNull(k.outputRoot))
        realExpect(kOutput.parent == brokerRoot.resolve("outputs").resolve(archiveSource.parent.fileName) &&
            kOutput.fileName.toString().matches(Regex("attempt-[0-9]+-[A-Za-z0-9-]+")), "real_model_custody_not_kernel_attempt")
        val expectedClasspath = p.context.classpath.mapIndexed { index, artifact ->
            artifact.copy(path = archiveSource.parent.resolve("classpath/artifact-$index.jar").toString())
        }
        realExpect(k == p.copy(materializedRoot = archiveSource.toString(), context = p.context.copy(classpath = expectedClasspath),
            outputRoot = kOutput.toString()), "real_model_custody_p_to_k_changed")
        val archivedMetadata = ContractJson.decodeFromString<AnalysisInput>(strictJsonDocument(
            realRead(archiveSource.parent.resolve("input.json"), 2 * 1024 * 1024), "real_model_kernel_metadata_json"))
        realExpect(archivedMetadata == k.copy(outputRoot = null), "real_model_custody_kernel_metadata_changed")
        realExpect(c == k.copy(requiredRuleIds = entry.compilerRules, execution = null), "real_model_custody_k_to_c_changed")
        val job = generation().root.resolve("jobs/${entry.phase}")
        realExpect(value.jobRoot.path() == job.toString() && value.sourceRoot.path() == job.resolve("sources").toString() &&
            value.classpathRoot.path() == job.resolve("classpath").toString() && value.outputRoot.path() == job.resolve("classes").toString() &&
            value.snapshotRoot.path() == k.materializedRoot && value.originalOutputRoot.path() == k.outputRoot &&
            value.originalClasspathRoot?.path() == archiveSource.parent.resolve("classpath").toString(), "real_model_custody_job_roots_changed")
        realExpect(e == c.copy(materializedRoot = value.sourceRoot.path(), context = c.context.copy(
            classpath = c.context.classpath.mapIndexed { index, artifact -> artifact.copy(path = job.resolve("classpath/artifact-$index.jar").toString()) }),
            outputRoot = value.outputRoot.path()), "real_model_custody_c_to_e_changed")
        realExpect(listOf(p, k, c, e).all { it.context.compilerArguments == p.context.compilerArguments &&
            it.context.compilationFiles == p.context.compilationFiles && it.snapshot == p.snapshot }, "real_model_custody_raw_model_changed")
        realExpect(value.compilationPaths == entry.binding.executableCompilationFiles && value.snapshotMembers.map { it.logicalPath() } == p.snapshot.files.map { it.path },
            "real_model_custody_member_or_execution_order_changed")
        value.snapshotMembers.forEachIndexed { index, member ->
            val file = p.snapshot.files[index]
            realExpect(member.digest() == file.digest && member.size() == file.size &&
                realKey(realPath(value.sourceRoot.path()).resolve(member.logicalPath())) == member.fileKey(), "real_model_custody_snapshot_member_changed")
        }
        value.dependencyMembers.forEachIndexed { index, member ->
            val artifact = c.context.classpath[index]
            realExpect(member.coordinate() == artifact.coordinate && member.digest() == artifact.digest && member.size() == entry.dependencySizes[index] &&
                realKey(realPath(e.context.classpath[index].path)) == member.fileKey(), "real_model_custody_dependency_member_changed")
        }
        realExpect(value.dependencyMembers.size == p.context.classpath.size, "real_model_custody_dependency_order_changed")
    }
    /** Reconstructed from actual callback/hello + retained importer binding. DATA, not worker receipt. */
    private fun reconstructRequest(entry: Expected, value: ModelCompilerJobMaterialization): String {
        val app = generation(); val memberDigest = ModelWire.membersDigest(value.snapshotMembers)
        fun identity(input: AnalysisInput) = ModelWire.InputIdentity(input.analysisKey(), input.snapshot.snapshotId, memberDigest,
            requireNotNull(input.context.modelDigest), requireNotNull(input.context.dependencyDigest),
            requireNotNull(input.context.configurationDigest), requireNotNull(input.context.toolchainDigest))
        val body = ModelWire.Request(ModelWire.PROFILE, entry.original.analysisKey(), value.archived.analysisKey(), entry.binding.identity.digest,
            identity(value.input), identity(value.effective), value.snapshotRoot, value.originalClasspathRoot, value.originalOutputRoot,
            value.jobRoot, value.sourceRoot, value.classpathRoot, value.outputRoot, value.snapshotMembers,
            entry.binding.executableCompilationFiles, value.dependencyMembers, entry.binding.normalizedCompilerOptions, entry.binding.normalizedCompilerOptions,
            ModelWire.profileDigest(app.request.generation), entry.deadline.identity)
        val job = Wire.Job(app.request.launch.identity().generation(), Wire.helloDigest(app.hello), entry.phase,
            Wire.jobId(app.request.launch.identity().generation(), entry.phase), value.input.analysisKey(), value.effective.analysisKey(),
            entry.deadline.identity, ModelWire.encodeRequest(body))
        Wire.matchJob(app.request.launch, app.hello, job, entry.phase)
        ModelWire.matchRequest(app.request.launch, app.request.generation, job, body)
        return Wire.requestDigest(job)
    }
    fun complete(phase: Int, result: ProviderResult): JsonObject {
        val entry = synchronized(lock) { expected.values.single { it.phase == phase } }
        val value = entry.materialization ?: throw RealModelProblem("real_model_actual_materialization_missing")
        verify(value)
        realExpect(entry.verificationCalls.get() >= 3, "real_model_post_worker_custody_missing")
        val executed = result.coverage.singleOrNull { it.ruleId == RealModelFixture.RULE }
        realExpect(executed?.provenance == "executed" && !executed.qualified && executed.certificateId == null &&
            result.status != "complete" && !result.complete(), "real_model_unqualified_execution_not_verified")
        val matches = result.findings.filter { it.ruleId == RealModelFixture.RULE }
        if (phase == 1) {
            realExpect(matches.isNotEmpty() && matches.all { it.providerId == generation().runtime.provider.id &&
                it.location.path == RealModelFixture.USE && it.location.fileDigest == Digests.sha256(RealModelFixture.USE_TEXT) &&
                it.upstreamId == "UNRESOLVED_REFERENCE" && it.diagnosis.contains("declaredAcrossFiles") &&
                it.evidence.singleOrNull { proof -> proof.kind == "application-request" }?.digest == entry.expectedRequestDigest &&
                it.evidence.singleOrNull { proof -> proof.kind == "model-binding" }?.digest == entry.binding.identity.digest },
                "real_model_actual_unresolved_finding_not_bound")
        } else {
            realExpect(matches.isEmpty() && result.findings.isEmpty() && result.rawDiagnostics.none { it.severity == "error" } &&
                result.reasons.none { it.endsWith(":compiler_model_live_api_diagnostic_failure") }, "real_model_cross_file_fix_not_observed")
        }
        val output = realPath(value.outputRoot.path())
        val outputFiles = realRegularFiles(output)
        if (phase == 2) realExpect(outputFiles.any { it.fileName.toString().endsWith(".class") }, "real_model_fixed_output_missing")
        return buildJsonObject {
            evidence(entry).forEach { (key, data) -> put(key, data) }
            put("genuineKernelResultDigest", Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(result))))
            put("actualFindingRequestDigestMatched", phase == 1)
            put("cleanRequestDigestDirectlyExposed", false)
            put("cleanEvidenceBasis", "Existing renderer's executed/unqualified rows plus genuine owner callback/whole materialization; reconstructed request is DATA, not an invented worker receipt")
            putJsonArray("actualOutputFiles") { outputFiles.forEach { path -> add(buildJsonObject {
                put("path", output.relativize(path).toString()); put("bytes", Files.size(path))
                put("digest", realHashFile(path, ModelWire.MAX_JOB_OUTPUT_BYTES.toLong())); put("fileKey", realKey(path))
            }) } }
            put("qualified", false); put("usefulClean", false); put("fullProjectProductAcceptance", false)
            put("cpuMillis", JsonNull); put("peakRssBytes", JsonNull)
        }.also { realWriteJson(evidenceRoot.resolve("phase-$phase-materialization-complete.json"), it) }
    }
    private fun evidence(entry: Expected): JsonObject = buildJsonObject {
        val value = requireNotNull(entry.materialization)
        put("phase", entry.phase); put("generationId", generation().request.launch.identity().generation())
        put("actualHelloDigest", Wire.helloDigest(generation().hello)); put("actualLoaderToken", generation().hello.loaderToken())
        put("requestDigestReconstructedAsData", requireNotNull(entry.expectedRequestDigest))
        put("bindingDigest", entry.binding.identity.digest); put("argumentLayoutDigest", requireNotNull(entry.binding.argumentLayoutDigest))
        put("originalDeadlineIdentity", entry.deadline.identity); put("originalDeadlineMillis", entry.deadline.durationMillis)
        put("P", ContractJson.encodeToJsonElement(value.original)); put("K", ContractJson.encodeToJsonElement(value.archived))
        put("C", ContractJson.encodeToJsonElement(value.input)); put("E", ContractJson.encodeToJsonElement(value.effective))
        put("executableCompilationFiles", JsonArray(entry.binding.executableCompilationFiles.map(::JsonPrimitive)))
        put("normalizedCompilerOptions", JsonArray(entry.binding.normalizedCompilerOptions.map(::JsonPrimitive)))
        put("retainedRawArgvUnchanged", true); put("wholeParentRequiredRules", value.original.requiredRuleIds.size)
        put("verificationCalls", entry.verificationCalls.get()); put("registeredMonotonicNanos", entry.registrationNanos?.let(::JsonPrimitive) ?: JsonNull)
        put("executionAuthority", "existing Workbench owner seam only; this receipt is private evidence, not a grant")
    }
}
