package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmModelBinding
import dev.ktchecker.compiler.live.model.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.PreparedInputObserver
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

internal class RealProducedJvmEntry(
    val phase: Int,
    val sequence: Int,
    val original: AnalysisInput,
    val model: VerifiedProjectModel,
    val preview: KotlinJvmProducedOutputPreview,
    val binding: KotlinJvmModelBinding,
    val provider: KotlinJvmModelLiveCompilerProvider,
    val compilerRules: List<String>,
    val prepared: PreparedJvmLiveModelInput,
    val producerSuccess: ModelSuccessfulJvmOutput?,
    val originalFileKeys: Set<String>,
    val dependencySizes: List<Long>,
) {
    val producer = binding.compilationId == preview.graph.producerCompilationId
    val label = "phase-$phase-${if (producer) "producer" else "consumer"}"
    val deadline: OwnedApplicationDeadline get() = preview.originalDeadline
    @Volatile var kernelEffective: AnalysisInput? = null
    @Volatile var permit: ModelCompilerAttemptPermit? = null
    @Volatile var materialization: ModelCompilerJobMaterialization? = null
    @Volatile var requestDigest: String? = null
    @Volatile var reconstructed: ModelWire.Request? = null
    @Volatile var pins: List<RealModelFilePin> = emptyList()
    @Volatile var roots: Map<Path, String> = emptyMap()
    val verificationCalls = AtomicInteger()
    @Volatile var childResult: ProviderResult? = null
    @Volatile var fullResult: ProviderResult? = null
    @Volatile var observerFinished = false
    @Volatile var checked = false
    @Volatile var success: ModelSuccessfulJvmOutput? = null
}

/** Test-only full1663 aggregator plus genuine KernelService observer. No fake K, manual compile,
 * narrow parent, dropped diagnostics, ordinary activation or serialized execution grants.
 */
internal class RealProducedJvmCustody(
    private val generation: RealProducedJvmGeneration,
    private val broker: Path,
    private val evidence: Path,
) : AnalysisProvider, PreparedInputObserver {
    override val id = "real-produced-jvm-full-catalog-test-owner"
    override val version = "1"
    private val catalog = Catalog()
    override val supportedRuleIds: Set<String> = catalog.ids
    private val lock = Any()
    private val expected = linkedMapOf<String, RealProducedJvmEntry>()
    private val effectiveAttempts = IdentityHashMap<AnalysisInput, RealProducedJvmEntry>()

    fun retain(phase: Int, original: AnalysisInput, model: VerifiedProjectModel,
        preview: KotlinJvmProducedOutputPreview, resolved: JvmProducedPreviewCompilation,
        provider: KotlinJvmModelLiveCompilerProvider, producerSuccess: ModelSuccessfulJvmOutput?): RealProducedJvmEntry {
        realExpect(original.requiredRuleIds == catalog.ids.sorted() && original.requiredRuleIds.size == 1663 &&
            original.execution == null && original.outputRoot == null && preview.model === model && resolved.binding.model === model &&
            original.context.compilerArguments == model.compilation(resolved.binding.compilationId)?.compilerArguments &&
            !original.context.fullProject && model.issues.isNotEmpty(), "real_produced_original_parent_identity")
        val active = validatedActivation(original, catalog)
        val rules = original.requiredRuleIds.filter { active[it]?.active == true && it in provider.supportedRuleIds }
        realExpect(rules.isNotEmpty() && rules.size <= 1024 && "ktc.compiler.common.unresolved_reference" in rules,
            "real_produced_compiler_projection_changed")
        val prepared = resolved.binding.bindPreparedLiveInput(original, rules, provider, preview.originalDeadline)
        val sourceKeys = original.snapshot.files.map { file ->
            RealModelFilePin.capture(realPath(original.materializedRoot).resolve(file.path), file.digest, file.size).fileKey
        }
        val dependencies = original.context.classpath.map { artifact ->
            RealModelFilePin.capture(realPath(artifact.path), artifact.digest, Files.size(realPath(artifact.path)))
        }
        val keys = sourceKeys + dependencies.map { it.fileKey }
        realExpect(keys.distinct().size == keys.size, "real_produced_original_files_alias")
        val entry = synchronized(lock) {
            realExpect(expected.size < 4 && original.analysisKey() !in expected && expected.values.all { it.checked },
                "real_produced_previous_attempt_unsettled_or_replay")
            RealProducedJvmEntry(phase, expected.size + 1, original, model, preview, resolved.binding, provider, rules,
                prepared, producerSuccess, keys.toSet(), dependencies.map { it.bytes }).also { expected[original.analysisKey()] = it }
        }
        realWriteJson(evidence.resolve("${entry.label}-P.json"), original)
        return entry
    }

    override fun prepared(original: AnalysisInput, effective: AnalysisInput, verifyFrozen: () -> Unit) {
        val entry = synchronized(lock) {
            val found = expected[original.analysisKey()] ?: throw RealModelProblem("real_produced_unregistered_P")
            realExpect(found.original == original && found.kernelEffective == null && !effectiveAttempts.containsKey(effective),
                "real_produced_observer_attempt_replayed")
            found.kernelEffective = effective
            effectiveAttempts[effective] = found
            found
        }
        val child = effective.copy(requiredRuleIds = entry.compilerRules, execution = null)
        val permit = entry.prepared.bindAttempt(original, effective, child, generation.application, verifyFrozen,
            registerMaterialization = { value -> register(entry, value) },
            verifyMaterialization = { value -> verify(entry, value) })
        synchronized(lock) {
            realExpect(entry.permit == null && !entry.observerFinished, "real_produced_permit_replayed")
            entry.permit = permit
        }
        generation.authority.register(permit)
        realExpect(permit.successfulProducedOutput() == null, "real_produced_unexecuted_output_minted")
    }

    override fun finished(effective: AnalysisInput) {
        val entry = synchronized(lock) { effectiveAttempts[effective] }
            ?: throw RealModelProblem("real_produced_finished_foreign_attempt")
        synchronized(lock) {
            realExpect(entry.kernelEffective === effective, "real_produced_finished_attempt_identity")
            if (entry.observerFinished) return
            entry.observerFinished = true
        }
        // This exact ticket only: COMPLETE close cannot revoke the next compilation/generation.
        entry.permit?.close()
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val entry = synchronized(lock) { effectiveAttempts[input] }
            ?: throw RealModelProblem("real_produced_analyze_outside_observer")
        val permit = requireNotNull(entry.permit)
        realExpect(input === entry.kernelEffective && input.requiredRuleIds.size == 1663 && !entry.observerFinished,
            "real_produced_kernel_parent_mismatch")
        val result = entry.provider.analyze(permit.input, cancellation)
        // Preserve every child row/finding/raw diagnostic/reason exactly. Other parent rows remain
        // explicit unavailable, even when the actual compiler returned code0. No usefulness claim.
        val remaining = input.requiredRuleIds.filter { it !in entry.compilerRules }.map { rule ->
            Coverage(rule, input.context.scope, "unavailable", "real_produced_other_analyzers_not_executed",
                implemented = false, tested = false, qualified = false)
        }
        val full = ProviderResult(id, "incomplete", result.findings, result.coverage + remaining,
            result.reasons + "real_produced_test_owner_not_production_activation", result.rawDiagnostics, result.metrics, version)
        synchronized(lock) {
            realExpect(entry.childResult == null && entry.fullResult == null, "real_produced_provider_replayed")
            entry.childResult = result; entry.fullResult = full
        }
        realWriteJson(evidence.resolve("${entry.label}-actual-child-result.json"), result)
        return full
    }

    private fun register(entry: RealProducedJvmEntry, value: ModelCompilerJobMaterialization) {
        synchronized(lock) {
            realExpect(entry.materialization == null, "real_produced_materialization_replayed")
            entry.materialization = value
        }
        relation(entry, value)
        val k = value.archived; val e = value.effective
        val files = listOf(k, e).flatMap { input -> input.snapshot.files.map { file ->
            RealModelFilePin.capture(realPath(input.materializedRoot).resolve(file.path), file.digest, file.size)
        } + input.context.classpath.mapIndexed { index, artifact ->
            RealModelFilePin.capture(realPath(artifact.path), artifact.digest, entry.dependencySizes[index])
        } }
        realExpect(files.map { it.fileKey }.distinct().size == files.size && files.none { it.fileKey in entry.originalFileKeys },
            "real_produced_archive_or_job_not_independent")
        val roots = listOfNotNull(value.snapshotRoot, value.originalClasspathRoot, value.originalOutputRoot,
            value.jobRoot, value.sourceRoot, value.classpathRoot, value.outputRoot)
        entry.roots = roots.associate { realPath(it.path()) to it.fileKey() }
        realExpect(entry.roots.values.distinct().size == roots.size, "real_produced_root_alias")
        entry.pins = files
        realExpect(Files.list(realPath(value.outputRoot.path())).use { !it.findAny().isPresent }, "real_produced_output_not_fresh")
        entry.reconstructed = reconstruct(entry, value)
        entry.requestDigest = requestDigest(entry, requireNotNull(entry.reconstructed))
        verify(entry, value)
        realWriteJson(evidence.resolve("${entry.label}-P-K-C-E.json"), relationEvidence(entry))
    }

    private fun verify(entry: RealProducedJvmEntry, value: ModelCompilerJobMaterialization) {
        realExpect(entry.materialization === value, "real_produced_materialization_object_changed")
        relation(entry, value); entry.binding.verifyInstallation()
        entry.roots.forEach { (path, key) -> realExpect(realKey(path, true) == key, "real_produced_retained_root_changed") }
        entry.pins.forEach { it.verify() }
        listOf(value.archived, value.effective).forEach { input ->
            val root = realPath(input.materializedRoot)
            realExpect(realRegularFiles(root).map { root.relativize(it).toString() } == input.snapshot.files.map { it.path },
                "real_produced_whole_snapshot_changed")
        }
        val body = reconstruct(entry, value)
        realExpect(body == entry.reconstructed && requestDigest(entry, body) == entry.requestDigest, "real_produced_request_changed")
        entry.verificationCalls.incrementAndGet()
    }

    private fun relation(entry: RealProducedJvmEntry, value: ModelCompilerJobMaterialization) {
        val p = entry.original; val k = value.archived; val c = value.input; val e = value.effective
        realExpect(value.original == p && k == entry.kernelEffective && p.requiredRuleIds == catalog.ids.sorted(),
            "real_produced_parent_changed")
        val archiveSource = realPath(k.materializedRoot)
        realExpect(archiveSource.fileName.toString() == "sources" && archiveSource.parent.parent == broker.resolve("inputs") &&
            archiveSource.parent.fileName.toString().matches(Regex("[0-9a-f]{64}")), "real_produced_not_kernel_archive")
        val output = realPath(requireNotNull(k.outputRoot))
        realExpect(output.parent == broker.resolve("outputs").resolve(archiveSource.parent.fileName) &&
            output.fileName.toString().matches(Regex("attempt-[0-9]+-[A-Za-z0-9-]+")), "real_produced_not_kernel_attempt")
        val classpath = p.context.classpath.mapIndexed { index, artifact ->
            artifact.copy(path = archiveSource.parent.resolve("classpath/artifact-$index.jar").toString())
        }
        realExpect(k == p.copy(materializedRoot = archiveSource.toString(), context = p.context.copy(classpath = classpath),
            outputRoot = output.toString()), "real_produced_P_to_K_changed")
        val metadata = ContractJson.decodeFromString<AnalysisInput>(strictJsonDocument(
            realRead(archiveSource.parent.resolve("input.json"), 2 * 1024 * 1024), "real_produced_archive_metadata"))
        realExpect(metadata == k.copy(outputRoot = null), "real_produced_archive_metadata_changed")
        realExpect(c == k.copy(requiredRuleIds = entry.compilerRules, execution = null) && c.requiredRuleIds.size <= 1024,
            "real_produced_K_to_C_changed")
        val job = generation.root.resolve("jobs/${entry.sequence}")
        realExpect(value.jobRoot.path() == job.toString() && value.sourceRoot.path() == job.resolve("sources").toString() &&
            value.classpathRoot.path() == job.resolve("classpath").toString() && value.outputRoot.path() == job.resolve("classes").toString() &&
            value.snapshotRoot.path() == k.materializedRoot && value.originalOutputRoot.path() == k.outputRoot &&
            value.originalClasspathRoot?.path() == archiveSource.parent.resolve("classpath").toString(), "real_produced_job_roots_changed")
        realExpect(e == c.copy(materializedRoot = value.sourceRoot.path(), context = c.context.copy(classpath =
            c.context.classpath.mapIndexed { index, artifact -> artifact.copy(path = job.resolve("classpath/artifact-$index.jar").toString()) }),
            outputRoot = value.outputRoot.path()), "real_produced_C_to_E_changed")
        realExpect(listOf(p, k, c, e).all { it.snapshot == entry.model.snapshot &&
            it.context.compilerArguments == entry.model.compilation(entry.binding.compilationId)?.compilerArguments &&
            it.context.compilationFiles == p.context.compilationFiles && !it.context.fullProject }, "real_produced_raw_import_changed")
        realExpect(entry.binding.model === entry.model && entry.preview.model === entry.model &&
            value.compilationPaths == entry.binding.executableCompilationFiles && value.snapshotMembers.map { it.logicalPath() } == p.snapshot.files.map { it.path },
            "real_produced_genuine_object_or_member_order_changed")
        value.snapshotMembers.forEachIndexed { index, member ->
            val file = p.snapshot.files[index]
            realExpect(member.digest() == file.digest && member.size() == file.size &&
                realKey(realPath(e.materializedRoot).resolve(member.logicalPath())) == member.fileKey(), "real_produced_source_member_changed")
        }
        realExpect(value.dependencyMembers.size == c.context.classpath.size, "real_produced_dependency_domain_changed")
        value.dependencyMembers.forEachIndexed { index, member ->
            val artifact = c.context.classpath[index]
            realExpect(member.coordinate() == artifact.coordinate && member.digest() == artifact.digest && member.size() == entry.dependencySizes[index] &&
                realKey(realPath(e.context.classpath[index].path)) == member.fileKey(), "real_produced_dependency_order_changed")
        }
    }

    /** Independent DATA reconstruction from genuine callback, SAME binding, graph and real hello.
     * Never submitted or used to authorize execution. Successful producer exposes its real digest;
     * failure findings expose theirs. Clean consumer reconstruction alone is not a worker receipt.
     */
    private fun reconstruct(entry: RealProducedJvmEntry, value: ModelCompilerJobMaterialization): ModelWire.Request {
        val members = ModelWire.membersDigest(value.snapshotMembers)
        fun identity(input: AnalysisInput) = ModelWire.InputIdentity(input.analysisKey(), input.snapshot.snapshotId, members,
            requireNotNull(input.context.modelDigest), requireNotNull(input.context.dependencyDigest),
            requireNotNull(input.context.configurationDigest), requireNotNull(input.context.toolchainDigest))
        val ref = entry.preview.graph.reference
        val dependencies = entry.producerSuccess?.let { success -> listOf(ModelWire.ProducedDependency(ref.classpathIndex,
            success.compilationId, ref.digest(), success.generation, success.sequence, success.bindingDigest,
            success.requestDigest, success.resultDigest, success.outputDigest, ref.friend)) }.orEmpty()
        val raw = requireNotNull(entry.model.context(entry.binding.compilationId, entry.model.identity.configurationDigest).context)
        val projection = ModelWire.ProducedExecution(entry.preview.graph.digest, entry.binding.compilationId,
            Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(raw))), requireNotNull(entry.original.context.dependencyDigest), dependencies)
        val request = ModelWire.Request(ModelWire.PRODUCED_PROFILE, entry.original.analysisKey(), value.archived.analysisKey(), entry.binding.identity.digest,
            identity(value.input), identity(value.effective), value.snapshotRoot, value.originalClasspathRoot, value.originalOutputRoot,
            value.jobRoot, value.sourceRoot, value.classpathRoot, value.outputRoot, value.snapshotMembers, entry.binding.executableCompilationFiles,
            value.dependencyMembers, entry.binding.normalizedCompilerOptions, entry.binding.normalizedCompilerOptions,
            ModelWire.profileDigest(generation.request.generation), entry.deadline.identity, projection)
        // Independently expected execution DATA from the actual E paths, not raw argv rewriting.
        // A friend MUST use the same complete JAR occurrence as classpath; nonfriend adds nothing.
        val friendArguments = if (!entry.producer && ref.friend)
            listOf("-Xfriend-paths=${value.effective.context.classpath[ref.classpathIndex].path}") else emptyList()
        val expectedArguments = listOf("-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names") +
            entry.binding.normalizedCompilerOptions + listOf("-jdk-home", generation.request.generation.jdk().home().path(),
                "-d", value.outputRoot.path(), "-classpath", value.effective.context.classpath.joinToString(java.io.File.pathSeparator) { it.path }) +
            friendArguments + entry.binding.executableCompilationFiles.map { realPath(value.sourceRoot.path()).resolve(it).toString() }
        realExpect(ModelWire.arguments(generation.request.launch, generation.request.generation, request) == expectedArguments,
            "real_produced_execution_projection_not_exact")
        return request
    }
    private fun requestDigest(entry: RealProducedJvmEntry, body: ModelWire.Request): String {
        val launch = generation.request.launch
        val job = Wire.Job(launch.identity().generation(), Wire.helloDigest(generation.hello), entry.sequence,
            Wire.jobId(launch.identity().generation(), entry.sequence), body.original().analysisKey(), body.effective().analysisKey(),
            entry.deadline.identity, ModelWire.encodeRequest(body))
        Wire.matchJob(launch, generation.hello, job, entry.sequence)
        ModelWire.matchRequest(launch, generation.request.generation, job, body)
        return Wire.requestDigest(job)
    }

    fun complete(entry: RealProducedJvmEntry, result: ProviderResult): ProviderResult {
        val value = requireNotNull(entry.materialization)
        verify(entry, value)
        val child = requireNotNull(entry.childResult)
        realExpect(entry.observerFinished && entry.verificationCalls.get() >= 3 && result == entry.fullResult &&
            result.coverage.size == 1663 && result.coverage.map { it.ruleId }.toSet() == catalog.ids && !result.complete() &&
            child.coverage.map { it.ruleId } == entry.compilerRules && child.coverage.all { it.provenance == "executed" && !it.qualified && it.certificateId == null } &&
            child.status in setOf("failed", "partial") && result.findings == child.findings && result.rawDiagnostics == child.rawDiagnostics,
            "real_produced_full_unqualified_result_not_verified")
        child.findings.forEach { finding ->
            realExpect(finding.evidence.singleOrNull { it.kind == "application-request" }?.digest == entry.requestDigest &&
                finding.evidence.singleOrNull { it.kind == "model-binding" }?.digest == entry.binding.identity.digest,
                "real_produced_actual_finding_identity_unbound")
        }
        val success = requireNotNull(entry.permit).successfulProducedOutput()
        realExpect((success != null) == (entry.producer && child.status == "partial"), "real_produced_failed_or_consumer_output_minted")
        if (success != null) {
            realExpect(success.generation == generation.request.launch.identity().generation() && success.sequence == entry.sequence &&
                success.compilationId == entry.binding.compilationId && success.bindingDigest == entry.binding.identity.digest &&
                success.requestDigest == entry.requestDigest && success.members == completeTree(realPath(value.outputRoot.path())),
                "real_produced_accepted_output_identity_mismatch")
        }
        entry.success = success
        realWriteJson(evidence.resolve("${entry.label}-actual-completion.json"), buildJsonObject {
            relationEvidence(entry).forEach { (key, item) -> put(key, item) }
            put("kernelResult", ContractJson.encodeToJsonElement(result)); put("successfulOutputMinted", success != null)
            success?.let { put("actualProducerResultDigest", it.resultDigest); put("completeOutputDigest", it.outputDigest) }
            put("observerFinished", entry.observerFinished); put("verificationCalls", entry.verificationCalls.get())
            put("consumerCleanEvidence", "Actual executed/unqualified renderer plus real K/E custody; reconstructed request is DATA, not a fabricated clean worker receipt")
            put("qualified", false); put("usefulClean", false)
        })
        return child
    }

    fun verifyJar(entry: RealProducedJvmEntry, artifact: ModelProducedOutputArtifact) {
        val success = requireNotNull(entry.success)
        val expectedCoordinate = "produced-jvm:" + Digests.sha256(success.compilationId.toByteArray(Charsets.UTF_8)).removePrefix("sha256:")
        realExpect(artifact.artifact.coordinate == expectedCoordinate, "real_produced_jar_coordinate_changed")
        val pin = RealModelFilePin.capture(realPath(artifact.artifact.path), requireNotNull(artifact.artifact.digest), requireNotNull(artifact.artifact.size))
        val actual = mutableListOf<ModelWire.ProducedMember>(); var total = 0L
        ZipFile(pin.path.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entryValue = entries.nextElement()
                realExpect(actual.size < ModelWire.MAX_JOB_OUTPUT_FILES && entryValue.method == ZipEntry.STORED &&
                    entryValue.size >= 0 && entryValue.size <= ModelWire.MAX_JOB_OUTPUT_BYTES - total, "real_produced_complete_jar_bound")
                val hash = MessageDigest.getInstance("SHA-256"); val crc = CRC32(); var size = 0L
                zip.getInputStream(entryValue).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        realExpect(count > 0 && count <= entryValue.size - size, "real_produced_jar_stream_bound")
                        size += count; hash.update(buffer, 0, count); crc.update(buffer, 0, count)
                    }
                }
                realExpect(size == entryValue.size && crc.value == entryValue.crc, "real_produced_jar_entry_changed")
                total += size
                actual += ModelWire.ProducedMember(entryValue.name.removeSuffix(if (entryValue.isDirectory) "/" else ""),
                    entryValue.isDirectory, size, if (entryValue.isDirectory) null else "sha256:" + java.util.HexFormat.of().formatHex(hash.digest()))
            }
        }
        pin.verify()
        realExpect(actual == success.members && ModelWire.producedOutputDigest(actual) == success.outputDigest &&
            completeTree(realPath(requireNotNull(entry.materialization).outputRoot.path())) == actual, "real_produced_jar_not_entire_sealed_output")
        realWriteJson(evidence.resolve("${entry.label}-complete-output-jar.json"), buildJsonObject {
            put("artifact", ContractJson.encodeToJsonElement(artifact.artifact)); put("completeOutputDigest", success.outputDigest)
            put("entries", actual.size); put("entryBytes", total); put("allDirectoriesClassesAndKotlinModulesIncluded", true)
            put("abiFiltering", false); put("sourceMerging", false); put("actualProducerResultDigest", success.resultDigest)
            put("qualified", false)
        })
    }

    private fun completeTree(output: Path): List<ModelWire.ProducedMember> {
        val result = mutableListOf<ModelWire.ProducedMember>(); val key = realKey(output, true); var bytes = 0L
        Files.walk(output).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next(); if (path == output) continue
                realExpect(result.size < ModelWire.MAX_JOB_OUTPUT_FILES, "real_produced_tree_entry_bound")
                val relative = output.relativize(path).toString(); realLogical(relative)
                val directory = Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                realKey(path, directory)
                val size = if (directory) 0 else Files.size(path)
                realExpect(size <= ModelWire.MAX_JOB_OUTPUT_BYTES - bytes, "real_produced_tree_byte_bound"); bytes += size
                result += ModelWire.ProducedMember(relative, directory, size,
                    if (directory) null else realHashFile(path, ModelWire.MAX_JOB_OUTPUT_BYTES.toLong(), size))
            }
        }
        realExpect(realKey(output, true) == key, "real_produced_tree_root_changed")
        return result.sortedBy { it.path() }
    }
    private fun relationEvidence(entry: RealProducedJvmEntry): JsonObject = buildJsonObject {
        val value = requireNotNull(entry.materialization); val body = requireNotNull(entry.reconstructed)
        put("phase", entry.phase); put("sequence", entry.sequence); put("compilation", entry.binding.compilationId)
        put("P", ContractJson.encodeToJsonElement(value.original)); put("K", ContractJson.encodeToJsonElement(value.archived))
        put("C", ContractJson.encodeToJsonElement(value.input)); put("E", ContractJson.encodeToJsonElement(value.effective))
        put("rawArgvUnchanged", true); put("sameGenuineImportedObject", true)
        put("bindingDigest", entry.binding.identity.digest); put("argumentLayoutDigest", entry.binding.argumentLayoutDigest)
        put("originalDeadlineIdentity", entry.deadline.identity); put("originalDeadlineMillis", entry.deadline.durationMillis)
        put("generationId", generation.request.launch.identity().generation()); put("actualHelloDigest", Wire.helloDigest(generation.hello))
        put("requestDigestReconstructedAsData", entry.requestDigest); put("producedPlanDigest", entry.preview.graph.digest)
        put("effectiveArgvReconstructedAsData", JsonArray(ModelWire.arguments(generation.request.launch, generation.request.generation, body).map(::JsonPrimitive)))
        put("fullParentRules", value.original.requiredRuleIds.size); put("compilerChildRules", value.input.requiredRuleIds.size)
        put("unresolvedOriginalModelIssues", ContractJson.encodeToJsonElement(entry.model.issues))
        put("qualified", false); put("productionWorkbenchActivated", false)
    }
}
