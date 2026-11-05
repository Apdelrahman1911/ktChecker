package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.CompilerRuleMapping
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationRetirement
import dev.ktchecker.providers.runtime.OwnedApplicationCleanupDisposition
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Fixed owner-facing codes only. Never put an exception message, cause or stack in a receipt. */
internal enum class HarnessCode {
    CONSENT_TEXT, CONSENT_BINDING, CONSENT_USED, INGRESS, DEADLINE, CANCELLED,
    CANCELLATION_CHECK, INTERRUPTED, MANIFEST, EXPECTED_PIN, UNSUPPORTED_FILESYSTEM,
    PATH, ROOT_DRIFT, FILE_DRIFT, MEMBER_DRIFT, ALIAS, LIMIT, ARCHIVE, HOST_CLOSURE,
    TOOLCHAIN, OPEN_UNAVAILABLE, ATTEMPT_BINDING, MATERIALIZATION, RESULT_PROFILE,
    EVIDENCE_WRITE, CUSTODY, UNEXPECTED,
}

internal class HarnessProblem(val code: HarnessCode) : IllegalStateException("compiler_application_harness_" + code.name.lowercase())
internal fun harnessCheck(value: Boolean, code: HarnessCode) { if (!value) throw HarnessProblem(code) }
internal fun harnessCode(failure: Throwable): HarnessCode = (failure as? HarnessProblem)?.code ?: HarnessCode.UNEXPECTED
internal fun <T> harnessList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

/** Scalar ingress only: constructing this does not read a path, capture a source or start a clock. */
class CompilerApplicationHarnessIngress(
    val manifestPath: String,
    val manifestDigest: String,
    val generationMillis: Long,
    val secondRequestMillis: Long,
) {
    init {
        harnessPath(manifestPath)
        harnessCheck(Digests.valid(manifestDigest) && generationMillis in 1L..3_600_000L &&
            secondRequestMillis in 1L..3_600_000L, HarnessCode.INGRESS)
    }
}

/** Nonserialized object-bound owner consent, not a flag in a project file or a reusable grant. */
class CompilerApplicationHarnessConsent private constructor(private val ingress: CompilerApplicationHarnessIngress) {
    private val used = AtomicBoolean()
    internal fun consume(expected: CompilerApplicationHarnessIngress) {
        harnessCheck(expected === ingress, HarnessCode.CONSENT_BINDING)
        harnessCheck(used.compareAndSet(false, true), HarnessCode.CONSENT_USED)
    }
    companion object {
        const val CONFIRMATION = "run-two-real-k2-2.4.21-jobs-once-retain-all-roots"
        fun confirm(ingress: CompilerApplicationHarnessIngress, confirmation: String): CompilerApplicationHarnessConsent {
            harnessCheck(confirmation == CONFIRMATION, HarnessCode.CONSENT_TEXT)
            return CompilerApplicationHarnessConsent(ingress)
        }
    }
}

/** Both original clocks remain authoritative; cleanup never borrows or refreshes either clock. */
internal class HarnessClock(val generation: OwnedApplicationDeadline, private val cancellation: CancellationToken) {
    private val observedStart = System.nanoTime()
    fun elapsedMillis(): Long = (System.nanoTime() - observedStart).coerceAtLeast(0) / 1_000_000
    fun check(request: OwnedApplicationDeadline? = null) {
        harnessCheck(!Thread.currentThread().isInterrupted, HarnessCode.INTERRUPTED)
        val cancelled = try { cancellation.isCancelled() } catch (_: Throwable) { throw HarnessProblem(HarnessCode.CANCELLATION_CHECK) }
        // The owner callback may set interruption while returning false. Inspect present state
        // again before this checkpoint can pass; never clear another operation's interrupt.
        harnessCheck(!Thread.currentThread().isInterrupted, HarnessCode.INTERRUPTED)
        harnessCheck(!cancelled, HarnessCode.CANCELLED)
        harnessCheck(!generation.isExpired() && request?.isExpired() != true, HarnessCode.DEADLINE)
    }
}

/** Pure ordering control. It contains no execution backend and cannot supply provider results. */
internal class HarnessOrder {
    private var next = 1
    private var entered: Int? = null
    private var returned: Int? = null
    fun begin(sequence: Int) {
        harnessCheck(sequence == next && sequence in 1..2 && entered == null, HarnessCode.ATTEMPT_BINDING)
        entered = sequence
    }
    fun returned(sequence: Int) {
        harnessCheck(entered == sequence && returned == null, HarnessCode.ATTEMPT_BINDING)
        returned = sequence
    }
    fun passed(sequence: Int) {
        harnessCheck(entered == sequence && returned == sequence, HarnessCode.RESULT_PROFILE)
        entered = null; returned = null; next++
    }
    fun mayConstructSecond(): Boolean = next == 2 && entered == null
}

/** Actual public objects retained as received. No Accepted/binding DTO is reconstructed here. */
class CompilerApplicationHarnessAttempt internal constructor(
    val sequence: Int,
    val originalDeadline: OwnedApplicationDeadline?,
    val providerInput: AnalysisInput?,
    val permit: CompilerAttemptPermit?,
    val materialization: CompilerJobMaterialization?,
    val providerCallEntered: Boolean,
    val providerResult: ProviderResult?,
    val publicProfileVerified: Boolean,
    val retainedOutput: JsonObject?,
    val originalCapture: JsonObject? = null,
)

/** An in-memory owner receipt; capability objects deliberately have no serializer. */
class CompilerApplicationHarnessReport internal constructor(
    val ingress: CompilerApplicationHarnessIngress,
    val retainedRoot: Path?,
    val generationDeadline: OwnedApplicationDeadline,
    val installation: KotlinJvmToolchain?,
    val generationRequest: CompilerGenerationRequest?,
    val generationPermit: CompilerGenerationPermit?,
    val openResult: CompilerApplicationOpenResult?,
    val application: KotlinJvmCompilerApplication?,
    val provider: KotlinJvmLiveCompilerProvider?,
    attempts: List<CompilerApplicationHarnessAttempt>,
    val retirement: OwnedApplicationRetirement?,
    val retirementCallEntered: Boolean,
    failures: Collection<HarnessCode>,
    val journalHealthy: Boolean,
) {
    val attempts: List<CompilerApplicationHarnessAttempt> = harnessList(attempts)
    // These codes describe THIS harness. Actual open/provider/native reasons remain separately intact.
    val failureCodes: List<String> = harnessList(failures.distinct().map { it.name.lowercase() })
    val verified: Boolean get() = failureCodes.isEmpty() && journalHealthy && application != null && provider != null &&
        generationRequest != null && generationPermit?.request === generationRequest &&
        installation != null && generationRequest.installation === installation && generationRequest.originalDeadline === generationDeadline &&
        openResult is CompilerApplicationOpenResult.Ready && openResult.application === application &&
        attempts.map { it.sequence } == listOf(1, 2) &&
        attempts[0].originalDeadline === generationDeadline && attempts[1].originalDeadline != null &&
        attempts[1].originalDeadline !== generationDeadline &&
        attempts.all { it.providerCallEntered && it.providerInput != null && it.permit != null &&
            it.permit.application === application && it.permit.provider === provider && it.permit.owner === generationRequest.owner &&
            it.permit.original == it.providerInput && it.permit.originalDeadline === it.originalDeadline &&
            it.materialization != null && it.materialization.original === it.permit.original &&
            it.providerResult != null && it.publicProfileVerified } && retirementCallEntered &&
        retirement?.disposition == OwnedApplicationCleanupDisposition.CLOSED

    internal fun json(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/compiler-application-harness-public-observation/1")
        put("verified", verified); put("qualification", "none"); put("default_route", "dormant_default_deny")
        put("observation_scope", "actual app.hello, owner permits/materializations, unchanged analyze results; NOT raw runtime Accepted or wire-result receipts")
        put("cleanup_scope", "generation-native-custody-only; not per-job compilation or universal descendant proof")
        put("retention", "all roots retained, including failed/unverified roots; no automatic deletion or retry")
        put("manifest_path", ingress.manifestPath); put("manifest_digest", ingress.manifestDigest)
        put("retained_root", retainedRoot?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("generation_deadline", deadlineJson(generationDeadline))
        put("journal_healthy", journalHealthy); put("harness_failures", stringsJson(failureCodes))
        put("launch", generationRequest?.let { launchJson(it) } ?: JsonNull)
        put("hello", application?.let { helloJson(it.hello) } ?: JsonNull)
        put("open_unavailable", (openResult as? CompilerApplicationOpenResult.Unavailable)?.let {
            buildJsonObject { put("reasons", stringsJson(it.reasons)); put("cleanup", retirementJson(it.cleanup)) }
        } ?: JsonNull)
        put("attempts", JsonArray(attempts.map { row -> buildJsonObject {
            put("sequence", row.sequence)
            put("obligation", if (row.originalDeadline == null) "planned_not_constructed_unresolved" else if (row.providerInput == null)
                "ingress_started_input_not_constructed_unresolved" else if (row.publicProfileVerified)
                "public_profile_verified_unqualified" else "constructed_unresolved")
            put("original_deadline", row.originalDeadline?.let(::deadlineJson) ?: JsonNull)
            put("first_deadline_is_generation_object", row.sequence == 1 && row.originalDeadline === generationDeadline)
            put("provider_input", row.providerInput?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
            put("original_capture", row.originalCapture ?: JsonNull)
            put("permit_original", row.permit?.let { ContractJson.encodeToJsonElement(it.original) } ?: JsonNull)
            put("permit_original_key", row.permit?.originalKey?.let(::JsonPrimitive) ?: JsonNull)
            put("provider_call_entered", row.providerCallEntered); put("provider_call_returned", row.providerResult != null)
            put("public_profile_verified", row.publicProfileVerified)
            put("materialization", row.materialization?.let(::materializationJson) ?: JsonNull)
            // Evidence serialization only. The report's providerResult is still the exact returned object.
            put("provider_result", row.providerResult?.let { ContractJson.encodeToJsonElement(it) } ?: JsonNull)
            put("retained_output", row.retainedOutput ?: JsonNull)
        } }))
        put("native_retirement", retirementJson(retirement))
        put("retirement_call_entered", retirementCallEntered)
    }
}

internal fun deadlineJson(value: OwnedApplicationDeadline): JsonObject = buildJsonObject {
    put("identity", value.identity); put("original_duration_millis", value.durationMillis)
}
internal fun stringsJson(values: Collection<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
internal fun rootJson(value: Wire.Root): JsonObject = buildJsonObject { put("path", value.path()); put("file_key", value.fileKey()) }
internal fun helloJson(value: Wire.Hello): JsonObject = buildJsonObject {
    put("provenance", "actual_public_app_hello")
    // Re-encoding an actual public object is NOT labelled a captured byte stream.
    put("decoded_value_encoding_base64", Base64.getEncoder().encodeToString(Wire.encode(value)))
    put("hello_digest", Wire.helloDigest(value)); put("loader_token", value.loaderToken())
    put("observed_version", value.observedVersion()); put("origins_verified", value.originsVerified())
    put("initial_reset_observed", value.initialResetObserved())
}
internal fun launchJson(value: CompilerGenerationRequest): JsonObject = buildJsonObject {
    put("provenance", "owner_retained_launch_descriptor_not_launch_or_wire_acceptance_proof")
    put("descriptor_encoding_base64", Base64.getEncoder().encodeToString(Wire.encodeLaunch(value.launch)))
    put("launch_digest", value.launchDigest); put("owner_identity", value.owner.identity)
    put("namespace_digest", value.namespace.digest); put("generation_deadline", deadlineJson(value.originalDeadline))
    put("dependency_classpath", ContractJson.encodeToJsonElement(value.dependencyClasspath))
    put("dependency_digest", value.dependencyDigest); put("configuration_digest", value.configurationDigest)
    put("installation_digest", value.installation.digest)
}
internal fun materializationJson(value: CompilerJobMaterialization): JsonObject = buildJsonObject {
    put("provenance", "actual_registered_public_materialization_not_reconstructed_wire_request")
    put("original", ContractJson.encodeToJsonElement(value.original)); put("effective", ContractJson.encodeToJsonElement(value.effective))
    put("snapshot_root", rootJson(value.snapshotRoot)); put("job_root", rootJson(value.jobRoot))
    put("source_root", rootJson(value.sourceRoot)); put("output_root", rootJson(value.outputRoot))
    put("source", buildJsonObject {
        put("logical_path", value.sourceIdentity.logicalPath()); put("digest", value.sourceIdentity.digest())
        put("size", value.sourceIdentity.size()); put("file_key", value.sourceIdentity.fileKey())
    })
}
internal fun retirementJson(value: OwnedApplicationRetirement?): JsonElement = value?.let { buildJsonObject {
    put("disposition", it.disposition.name); put("reasons", stringsJson(it.reasons.map { r -> r.code }))
    put("transport_reason_codes", stringsJson(it.transportReasonCodes))
} } ?: JsonNull

/** Pure public-profile assertion, not a renderer and not transport acceptance or qualification. */
internal fun verifyHarnessPublicProfile(sequence: Int, input: AnalysisInput, result: ProviderResult, mapping: CompilerRuleMapping) {
    harnessCheck(sequence in 1..2 && mapping.jvmApplicable && mapping.upstreamId == "UNRESOLVED_REFERENCE" &&
        input.requiredRuleIds == listOf(mapping.ruleId), HarnessCode.RESULT_PROFILE)
    harnessCheck(result.providerId == KotlinJvmLiveCompilerProvider.PROVIDER_ID &&
        result.providerVersion == KotlinJvmLiveCompilerProvider.VERSION && !result.complete() &&
        result.status == (if (sequence == 1) "failed" else "partial"), HarnessCode.RESULT_PROFILE)
    harnessCheck(result.coverage.size == 1 && result.coverage.single().let {
        it.ruleId == mapping.ruleId && it.context == input.context.scope && it.required &&
            it.state == "unqualified" && it.reason == "compiler_live_preview_not_qualified" &&
            it.provenance == "executed" && !it.tested && !it.qualified && it.certificateId == null &&
            it.implemented == (sequence == 1)
    }, HarnessCode.RESULT_PROFILE)
    val requiredReasons = setOf("compiler_live_preview_not_qualified", "compiler_live_semantic_extraction_not_authorized") +
        if (sequence == 1) setOf("compiler_live_api_diagnostic_failure") else emptySet()
    val informationalReasons = setOf("compiler_live_informational_logging_redacted", "compiler_live_unmapped_diagnostic",
        "compiler_live_unlocated_or_informational_diagnostic", "compiler_live_raw_diagnostics_unresolved")
    harnessCheck(result.reasons.containsAll(requiredReasons) && result.reasons.all { it in requiredReasons + informationalReasons } &&
        result.rawDiagnostics.all { it.severity == "info" }, HarnessCode.RESULT_PROFILE)
    if (sequence == 1) {
        harnessCheck(result.findings.size == 1, HarnessCode.RESULT_PROFILE)
        val finding = result.findings.single()
        val location = finding.location
        harnessCheck(finding.ruleId == mapping.ruleId && finding.upstreamId == mapping.upstreamId &&
            finding.providerId == result.providerId && finding.context == input.context.scope && finding.severity == "error" &&
            finding.semanticDigest == input.analysisKey() && location.path == "Fixture.kt" &&
            location.fileDigest == input.snapshot.files.single().digest && location.line == 2 && location.column == 15 &&
            location.endLine == location.line && location.endColumn == location.column, HarnessCode.RESULT_PROFILE)
        val requestEvidence = finding.evidence.filter { it.kind == "application-request" }
        harnessCheck(requestEvidence.size == 1 && requestEvidence.single().digest?.let(Digests::valid) == true &&
            finding.evidence.any { it.kind == "compiler-application-emission" && it.digest?.let(Digests::valid) == true },
            HarnessCode.RESULT_PROFILE)
    } else {
        harnessCheck(result.findings.isEmpty(), HarnessCode.RESULT_PROFILE)
        // No fake application-request digest is created for the clean result's empty findings.
    }
}

/** Private CREATE_NEW journal. A failed write disables this instance permanently; never retry it. */
internal class HarnessJournal private constructor(private val root: HarnessRootPin) {
    private var sequence = 0
    private var totalBytes = 0L
    private val written = ArrayList<HarnessFilePin>()
    var healthy: Boolean = true
        private set
    fun append(event: String, value: JsonElement, checkpoint: () -> Unit = {}) {
        harnessCheck(healthy, HarnessCode.EVIDENCE_WRITE)
        try {
            harnessCheck(event.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && sequence < 64, HarnessCode.LIMIT)
            checkpoint(); root.verify(); written.forEach { it.verify(checkpoint) }
            harnessCheck(harnessNames(root.path, written.size, checkpoint) == written.map { it.path.fileName.toString() }.toSet(), HarnessCode.EVIDENCE_WRITE)
            val bytes = Digests.canonical(value).toByteArray(Charsets.UTF_8)
            harnessCheck(bytes.size <= 16 * 1_024 * 1_024 && totalBytes + bytes.size <= 32L * 1_024 * 1_024, HarnessCode.LIMIT)
            checkpoint()
            val path = root.path.resolve("%03d-%s.json".format(++sequence, event))
            Files.newOutputStream(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
            val pin = HarnessFilePin.capture(path, bytes.size.toLong(), Digests.sha256(bytes), checkpoint)
            pin.verify(checkpoint); root.verify(); checkpoint()
            written += pin
            totalBytes += bytes.size
        } catch (failure: Throwable) {
            healthy = false
            throw HarnessProblem(if (failure is HarnessProblem && failure.code == HarnessCode.DEADLINE)
                HarnessCode.DEADLINE else HarnessCode.EVIDENCE_WRITE)
        }
    }
    companion object {
        fun create(path: Path): HarnessJournal {
            privateHarnessDirectory(path)
            return HarnessJournal(HarnessRootPin.capture(path, expectedPermissions = "rwx------"))
        }
    }
}
