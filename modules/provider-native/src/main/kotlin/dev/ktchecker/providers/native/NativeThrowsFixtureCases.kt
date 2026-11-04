package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Serializable internal data class NativeThrowsFixtureAnchor(val line: Int, val firstColumn: Int, val lastColumn: Int)

@Serializable internal data class NativeThrowsFixtureCase(
    val name: String,
    val ruleId: String,
    val upstreamId: String,
    val sourcePath: String,
    val expectation: String,
    val anchors: List<NativeThrowsFixtureAnchor>,
    val compilerArguments: List<String>,
    val intent: String,
)

@Serializable internal data class NativeThrowsFixtureRule(
    val ruleId: String,
    val upstreamId: String,
    val factory: String,
    val factoryLine: Int,
    val declaredSeverity: String,
)

@Serializable internal data class NativeThrowsFixtureCohort(
    val schemaVersion: Int,
    val cohortId: String,
    val kotlinVersion: String,
    val targets: List<String>,
    val sourceProvenance: String,
    val rules: List<NativeThrowsFixtureRule>,
    val cases: List<NativeThrowsFixtureCase>,
    val upstream: JsonObject,
    val ownerDisposition: JsonObject,
    val limitations: List<String>,
)

@Serializable internal data class NativeThrowsSourceFile(val path: String, val digest: String, val size: Long)

@Serializable internal data class NativeThrowsSourceManifest(
    val schemaVersion: Int,
    val cohortId: String,
    val kotlinVersion: String,
    val targets: List<String>,
    val sourceProvenance: String,
    val files: List<NativeThrowsSourceFile>,
)

@Serializable internal data class NativeThrowsSupervisorReceipt(
    val protocolVersion: String,
    val target: String,
    val sourceDigest: String,
    val binaryDigest: String,
)

/** Private witness: stdout/stderr and command are deliberately never printed by the driver. */
@Serializable internal data class NativeThrowsProcessReceipt(
    val phase: String,
    val snapshotId: String,
    val analysisKey: String,
    val status: String,
    val reasons: List<String>,
    val exitCode: Int?,
    val executionConfirmed: Boolean,
    val supervisorIdentity: NativeThrowsSupervisorReceipt?,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val truncated: Boolean,
    val metrics: AnalysisMetrics,
    val command: List<String>,
    val outputDirectory: String?,
    val stdoutDigest: String,
    val stderrDigest: String,
    val stdout: String,
    val stderr: String,
) {
    val successfulProcessEvidence: Boolean get() = status == "executed" && executionConfirmed &&
        !cancelled && !timedOut && !truncated && supervisorIdentity?.let(NativeThrowsFixtureCases::validSupervisor) == true
}

internal data class NativeThrowsFixtureAssertions(
    val passed: Boolean,
    val processEvidenceConfirmed: Boolean,
    val nativeTasksObserved: Boolean,
    val failures: List<String>,
)

/** Fixed authored fixture import only. The manifest cannot grant arbitrary execution authority. */
internal object NativeThrowsFixtureCases {
    const val COHORT = "native-throws-2.4.21"
    const val VERSION = "2.4.21"
    const val COMMON_SOURCE = "src/commonMain/kotlin/CommonBoundary.kt"
    const val SOURCE_MANIFEST = "source-manifest.json"
    const val SOURCE_MANIFEST_DIGEST = "sha256:eec299987a5b35c8d4ae65c69317c66c4702b1227428231c41546aeccb13071f"
    const val QUALIFICATION_REASON = "native_per_id_apple_precision_qualification_pending"
    val targets = setOf("iosArm64", "iosSimulatorArm64")
    val rules = linkedMapOf(
        "THROWS_LIST_EMPTY" to "ktc.compiler.native.throws_list_empty",
        "INCOMPATIBLE_THROWS_OVERRIDE" to "ktc.compiler.native.incompatible_throws_override",
        "INCOMPATIBLE_THROWS_INHERITED" to "ktc.compiler.native.incompatible_throws_inherited",
        "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND" to "ktc.compiler.native.missing_exception_in_throws_on_suspend",
    )
    val caseNames = setOf(
        "empty-list", "empty-list-nonempty", "empty-list-omitted",
        "override-filter", "override-filter-matching", "override-filter-inherited", "override-filter-reordered", "override-filter-subtype",
        "inherited-filter", "inherited-filter-matching", "inherited-filter-diamond",
        "suspend-cancellation", "suspend-cancellation-exact", "suspend-cancellation-superclass", "suspend-cancellation-nonsuspend",
        "suspend-implicit-override", "suspend-implicit-inherited",
        "override-filter-nowarn", "override-filter-suppression", "override-filter-demotion-denied",
    )

    fun parseOptions(args: Array<String>): Map<String, String> {
        val options = parseFixtureArguments(args)
        val required = setOf("fixture", "gradle", "target", "case", "result", "native-home", "java-home")
        if (options.keys != required || options.values.any { it.isBlank() } ||
            options["target"] !in targets || options["case"] !in caseNames) {
            throw ExecutionProblem("native_throws_arguments_invalid", "Explicit fixed fixture arguments are required")
        }
        return options
    }

    fun load(frozen: FixtureMaterialization): NativeThrowsFixtureCohort {
        val witness = frozen.manifest.files.singleOrNull { it.path == SOURCE_MANIFEST }
            ?: fixtureProblem("native_throws_source_manifest_missing")
        if (witness.digest != SOURCE_MANIFEST_DIGEST || witness.size !in 1L..1_048_576L) {
            fixtureProblem("native_throws_source_manifest_mismatch")
        }
        val manifest = ContractJson.decodeFromString<NativeThrowsSourceManifest>(
            Files.readString(frozen.root.resolve(SOURCE_MANIFEST)))
        if (manifest.schemaVersion != 1 || manifest.cohortId != COHORT || manifest.kotlinVersion != VERSION ||
            manifest.targets.toSet() != targets || manifest.targets.size != targets.size ||
            manifest.sourceProvenance != "authored_unexecuted" ||
            manifest.files.size != manifest.files.map { it.path }.toSet().size) {
            fixtureProblem("native_throws_source_manifest_invalid")
        }
        val captured = frozen.manifest.files.filter { it.path != SOURCE_MANIFEST }.associateBy { it.path }
        if (manifest.files.map { it.path }.toSet() != captured.keys || manifest.files.any { file ->
                !Digests.valid(file.digest) || file.size < 0 || captured[file.path]?.let {
                    it.digest != file.digest || it.size != file.size
                } != false
            }) fixtureProblem("native_throws_authored_sources_changed")
        val cohort = ContractJson.decodeFromString<NativeThrowsFixtureCohort>(Files.readString(frozen.root.resolve("cohort.json")))
        validateCohort(cohort)
        if (captured.keys.filter { it.endsWith(".kt") }.toSet() !=
            cohort.cases.map { it.sourcePath }.toSet() + COMMON_SOURCE) fixtureProblem("native_throws_source_members_invalid")
        frozen.verify()
        return cohort
    }

    fun validateCohort(cohort: NativeThrowsFixtureCohort) {
        if (cohort.schemaVersion != 1 || cohort.cohortId != COHORT || cohort.kotlinVersion != VERSION ||
            cohort.targets.toSet() != targets || cohort.targets.size != targets.size || cohort.sourceProvenance != "authored_unexecuted" ||
            cohort.rules.size != rules.size || cohort.rules.associate { it.upstreamId to it.ruleId } != rules ||
            cohort.rules.any { it.factory != "FirNativeErrors" || it.declaredSeverity != "ERROR" ||
                it.factoryLine != when (it.upstreamId) {
                    "THROWS_LIST_EMPTY" -> 34
                    "INCOMPATIBLE_THROWS_OVERRIDE" -> 35
                    "INCOMPATIBLE_THROWS_INHERITED" -> 36
                    "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND" -> 37
                    else -> -1
                } ||
                NativeCatalog.byName[it.upstreamId]?.ruleId != it.ruleId } ||
            cohort.cases.size != caseNames.size || cohort.cases.map { it.name }.toSet() != caseNames ||
            cohort.cases.any { case ->
                !validCase(case) ||
                    (case.expectation == "named_error" && case.anchors.size != 1) ||
                    (case.expectation == "compatible" && case.anchors.isNotEmpty()) ||
                    case.anchors.any { it.line < 1 || it.firstColumn < 0 || it.lastColumn < it.firstColumn }
            }) fixtureProblem("native_throws_cohort_invalid")
    }

    fun requiredTasks(target: String): Set<String> {
        if (target !in targets) fixtureProblem("native_ios_target_model_required")
        return linkedSetOf(":compileCommonMainKotlinMetadata",
            if (target == "iosArm64") ":compileKotlinIosArm64" else ":compileKotlinIosSimulatorArm64")
    }

    fun compilerArguments(case: NativeThrowsFixtureCase): List<String> = listOf(
        "-language-version", "2.4", "-api-version", "2.4", "-Xrender-internal-diagnostic-names") + case.compilerArguments

    fun configurationDigest(case: NativeThrowsFixtureCase, target: String): String = Digests.sha256(Digests.canonical(buildJsonObject {
        put("producer", "authored-native-throws-configuration/v1")
        put("sourceManifestDigest", SOURCE_MANIFEST_DIGEST)
        put("target", target)
        put("compilerVersion", VERSION)
        put("compilerArguments", ContractJson.encodeToJsonElement(compilerArguments(case)))
        put("case", ContractJson.encodeToJsonElement(case))
    }))

    fun modelBinding(snapshot: SnapshotManifest, case: NativeThrowsFixtureCase, target: String): FixtureModelBinding {
        if (!validCase(case) || !Digests.valid(snapshot.snapshotId) ||
            snapshot.unknownInputs.isNotEmpty()) fixtureProblem("native_throws_model_invalid")
        val scope = RuleContext(target, "iosMain", ":", "main")
        val sources = linkedMapOf(COMMON_SOURCE to "commonMain", case.sourcePath to "iosMain")
        val members = sources.keys.map { path ->
            snapshot.files.singleOrNull { it.path == path } ?: fixtureProblem("native_throws_model_member_missing")
        }
        if (members.any { !Digests.valid(it.digest) }) fixtureProblem("native_throws_model_member_invalid")
        val tasks = requiredTasks(target).sorted()
        val digest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("producer", "authored-native-throws-model/v1")
            put("snapshotId", snapshot.snapshotId)
            put("sourceManifestDigest", SOURCE_MANIFEST_DIGEST)
            put("scope", ContractJson.encodeToJsonElement(scope))
            put("case", ContractJson.encodeToJsonElement(case))
            put("members", ContractJson.encodeToJsonElement(members))
            put("sourceSetByFile", ContractJson.encodeToJsonElement(sources))
            put("requiredTaskPaths", ContractJson.encodeToJsonElement(tasks))
        }))
        return FixtureModelBinding(scope, digest, sources, tasks)
    }

    fun supervisor(identity: OwnedSupervisorIdentity): NativeThrowsSupervisorReceipt = NativeThrowsSupervisorReceipt(
        identity.protocolVersion, identity.target, identity.sourceDigest, identity.binaryDigest)

    fun validSupervisor(identity: NativeThrowsSupervisorReceipt): Boolean =
        identity.protocolVersion == "ktchecker-owned-child-supervisor/preview-1" &&
            identity.target in setOf("macos-x86_64", "macos-aarch64") &&
            Digests.valid(identity.sourceDigest) && Digests.valid(identity.binaryDigest)

    fun receipt(phase: String, input: AnalysisInput, outcome: ProcessOutcome): NativeThrowsProcessReceipt =
        NativeThrowsProcessReceipt(phase, input.snapshot.snapshotId, input.analysisKey(), outcome.status, outcome.reasons,
            outcome.exitCode, outcome.executionConfirmed, outcome.supervisorIdentity?.let(::supervisor),
            outcome.cancelled, outcome.timedOut, outcome.truncated, outcome.metrics, outcome.command,
            outcome.outputDirectory?.toString(), Digests.sha256(outcome.stdout), Digests.sha256(outcome.stderr), outcome.stdout, outcome.stderr)

    fun acceptsJdkVersion(output: String): Boolean = Regex("""(?:openjdk|java) version "25(?:[."+]|-)""").containsMatchIn(output)
    fun acceptsNativeVersion(output: String): Boolean = Regex(
        "(?:kotlinc-native|Kotlin/Native(?: compiler)?(?: version)?)\\s+2\\.4\\.21(?:\\s|$|\\()",
        RegexOption.IGNORE_CASE).containsMatchIn(output)

    /** Assertions consume real receipts in production. Test-created receipts remain synthetic. */
    fun assertions(case: NativeThrowsFixtureCase, input: AnalysisInput, provider: ProviderResult,
                   receipts: List<NativeThrowsProcessReceipt>, inspected: NativeThrowsSupervisorReceipt?): NativeThrowsFixtureAssertions {
        val failures = mutableListOf<String>()
        val phases = listOf("jdk-version", "native-version", "provider")
        val exactPhases = receipts.size == phases.size && receipts.map { it.phase } == phases
        val process = receipts.singleOrNull { it.phase == "provider" }
        val confirmed = exactPhases && inspected?.let(::validSupervisor) == true && receipts.all {
            it.successfulProcessEvidence && it.supervisorIdentity == inspected && it.snapshotId == input.snapshot.snapshotId &&
                Digests.valid(it.analysisKey) && it.stdoutDigest == Digests.sha256(it.stdout) && it.stderrDigest == Digests.sha256(it.stderr)
        } && receipts.take(2).all { it.exitCode == 0 } && process?.analysisKey == input.analysisKey()
        if (!confirmed) failures += "native_throws_process_evidence_unconfirmed"
        if (receipts.singleOrNull { it.phase == "jdk-version" }?.let { acceptsJdkVersion(it.stdout + "\n" + it.stderr) } != true)
            failures += "native_throws_jdk25_unverified"
        if (receipts.singleOrNull { it.phase == "native-version" }?.let { acceptsNativeVersion(it.stdout + "\n" + it.stderr) } != true)
            failures += "native_throws_compiler_version_unverified"
        val parsed = try { process?.let { NativeDiagnosticParser.parse(it.stdout, it.stderr) } }
            catch (_: Exception) { failures += "native_throws_output_invalid"; null }
        val required = requiredTasks(input.context.scope.target)
        val targetTask = required.single { it != ":compileCommonMainKotlinMetadata" }
        val tasksObserved = parsed != null && ExactGradleTaskProof.missingExecution(required, parsed.tasks).isEmpty()
        if (!tasksObserved) failures += "native_throws_required_task_execution_missing"
        if (parsed?.let { ExactGradleTaskProof.hasUnrelatedFailure(required, it.tasks) } == true)
            failures += "native_throws_unrelated_task_failure"
        val expectedScope = RuleContext(input.context.scope.target, "iosMain", ":", "main")
        val binding = try { modelBinding(input.snapshot, case, input.context.scope.target) } catch (_: Exception) { null }
        val executionArguments = input.execution?.arguments.orEmpty()
        if (binding == null || input.context.modelDigest != binding.modelDigest || !input.context.fullProject ||
            input.context.compilationFiles != binding.sourceSetByFile.keys.sorted() ||
            input.context.configurationDigest != configurationDigest(case, input.context.scope.target) ||
            input.context.compilerArguments != compilerArguments(case) ||
            input.context.toolchainDigest?.let(Digests::valid) != true || input.context.dependencyDigest != input.context.toolchainDigest ||
            input.context.classpath.isEmpty() || input.context.classpath.any { !Digests.valid(it.digest) } ||
            input.context.libraries["kotlin-stdlib-native"] != VERSION ||
            !executionArguments.containsAll(required) ||
            "-PfixtureNegativeCase=${case.name}" !in executionArguments || process?.command?.containsAll(required) != true)
            failures += "native_throws_analysis_model_unverified"
        val row = provider.coverage.singleOrNull()
        if (provider.providerId != "kotlin-native" || provider.providerVersion != VERSION || provider.status != "incomplete" ||
            input.context.compilerVersion != VERSION || input.context.scope != expectedScope || input.requiredRuleIds != listOf(case.ruleId) ||
            row == null || row.ruleId != case.ruleId || row.context != expectedScope || row.state != "incomplete" || row.provenance != "executed" ||
            !row.required || !row.implemented || row.tested || row.qualified || row.certificateId?.let(Digests::valid) != true ||
            provider.rawDiagnostics.isNotEmpty() || provider.reasons.any { it != QUALIFICATION_REASON })
            failures += "native_throws_provider_has_gaps"
        val source = input.snapshot.files.singleOrNull { it.path == case.sourcePath }
        val anchored = provider.findings.all { finding ->
            finding.ruleId == case.ruleId && finding.upstreamId == case.upstreamId && finding.providerId == "kotlin-native" &&
                finding.context == expectedScope && finding.semanticDigest == input.analysisKey() && finding.severity == "error" &&
                finding.location.path == case.sourcePath && finding.location.fileDigest == source?.digest && case.anchors.any {
                    finding.location.line == it.line && finding.location.column in it.firstColumn..it.lastColumn &&
                        finding.location.endLine == it.line && finding.location.endColumn in it.firstColumn..it.lastColumn
                }
        }
        when (case.expectation) {
            "named_error" -> if (process?.exitCode != 1 || parsed?.buildSuccessful != false ||
                parsed?.tasks?.singleOrNull { it.path == targetTask }?.outcome != "FAILED" ||
                provider.findings.size != 1 || !anchored ||
                parsed?.diagnostics?.singleOrNull()?.let { diagnostic ->
                    val finding = provider.findings.single()
                    diagnostic.name != case.upstreamId || diagnostic.severity != finding.severity || diagnostic.message != finding.diagnosis ||
                        diagnostic.line != finding.location.line || diagnostic.column != finding.location.column ||
                        diagnostic.path?.let { reportedPath(it, input.materializedRoot) } != case.sourcePath
                } != false)
                failures += "native_throws_expected_named_error_missing"
            "compatible" -> if (process?.exitCode != 0 || parsed?.buildSuccessful != true || provider.findings.isNotEmpty() ||
                parsed?.diagnostics?.isNotEmpty() == true)
                failures += "native_throws_compatible_result_unverified"
            "observation_only" -> failures += "native_throws_observation_only_not_a_passing_control"
            else -> failures += "native_throws_expectation_invalid"
        }
        return NativeThrowsFixtureAssertions(failures.isEmpty(), confirmed, tasksObserved, failures.distinct())
    }

    fun ruleForCase(name: String): String {
        val upstream = upstreamForCase(name) ?: fixtureProblem("native_throws_case_unknown")
        return rules[upstream] ?: fixtureProblem("native_throws_case_unknown")
    }

    private fun upstreamForCase(name: String): String? = when (name) {
            "empty-list", "empty-list-nonempty", "empty-list-omitted" -> "THROWS_LIST_EMPTY"
            "override-filter", "override-filter-matching", "override-filter-inherited", "override-filter-reordered", "override-filter-subtype",
            "suspend-implicit-override", "suspend-implicit-inherited", "override-filter-nowarn", "override-filter-suppression", "override-filter-demotion-denied" ->
                "INCOMPATIBLE_THROWS_OVERRIDE"
            "inherited-filter", "inherited-filter-matching", "inherited-filter-diamond" -> "INCOMPATIBLE_THROWS_INHERITED"
            "suspend-cancellation", "suspend-cancellation-exact", "suspend-cancellation-superclass", "suspend-cancellation-nonsuspend" ->
                "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND"
            else -> null
    }

    private fun validCase(case: NativeThrowsFixtureCase): Boolean {
        val upstream = upstreamForCase(case.name) ?: return false
        val expectation = when (case.name) {
            "empty-list", "override-filter", "override-filter-subtype", "inherited-filter", "suspend-cancellation",
            "suspend-implicit-override", "override-filter-nowarn" -> "named_error"
            "override-filter-suppression", "override-filter-demotion-denied" -> "observation_only"
            else -> "compatible"
        }
        val arguments = when (case.name) {
            "override-filter-nowarn" -> listOf("-nowarn")
            "override-filter-demotion-denied" -> listOf("-Xwarning-level=INCOMPATIBLE_THROWS_OVERRIDE:warning")
            else -> emptyList()
        }
        return case.sourcePath == "cases/${case.name}/iosMain/Contract.kt" && case.upstreamId == upstream &&
            rules[upstream] == case.ruleId && case.expectation == expectation && case.compilerArguments == arguments
    }

    private fun reportedPath(path: String, root: String): String? = try {
        val reported = if (path.startsWith("file:")) Path.of(java.net.URI(path)) else Path.of(path)
        val base = Path.of(root).toAbsolutePath().normalize()
        if (reported.isAbsolute) reported.normalize().takeIf { it.startsWith(base) }?.let { base.relativize(it).toString().replace('\\', '/') }
        else safeRelativePath(path).toString().replace('\\', '/')
    } catch (_: Exception) { null }

    private fun fixtureProblem(code: String): Nothing = throw ExecutionProblem(code, "Authored Native throws fixture binding is unavailable")
}
