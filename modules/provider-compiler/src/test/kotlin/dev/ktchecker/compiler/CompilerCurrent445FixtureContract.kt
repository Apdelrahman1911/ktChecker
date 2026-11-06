package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.strictUtf8
import java.nio.file.Path
import kotlinx.serialization.json.*

/** A new owner-selected schedule, never a selector/override of the immutable legacy443 runner. */
internal data class CompilerCurrent445Settings(
    val repositoryDirectory: String,
    val bindingSha256: String,
    val overallMillis: Long = 1_200_000,
    val perJobMillis: Long = 90_000,
    val evidenceBytes: Long = 268_435_456,
) {
    init {
        require(repositoryDirectory.isNotBlank() && '\u0000' !in repositoryDirectory)
        val path = Path.of(repositoryDirectory)
        require(path.isAbsolute && path.normalize() == path) { "Explicit normalized repository path required" }
        require(bindingSha256.matches(Regex("[0-9a-f]{64}"))) { "Explicit owner-reviewed binding SHA256 required" }
        require(overallMillis in 1..1_200_000 && perJobMillis in 1..90_000)
        require(evidenceBytes in 25_165_824..268_435_456)
    }

    companion object {
        fun parse(args: Array<String>): CompilerCurrent445Settings {
            val options = linkedMapOf<String, String>()
            val roots = mutableListOf<String>()
            for (arg in args) {
                if (!arg.startsWith("--")) { roots += arg; continue }
                val at = arg.indexOf('=')
                require(at > 2 && at < arg.lastIndex) { "Use closed --name=value options" }
                val name = arg.substring(2, at)
                require(name in setOf("cohort", "consent", "binding-sha256", "overall-seconds", "per-job-seconds", "evidence-mib"))
                require(options.put(name, arg.substring(at + 1)) == null) { "Duplicate current445 option" }
            }
            require(roots.size == 1) { "Exactly one explicit repository root required" }
            require(options["cohort"] == CompilerCurrent445FixtureContract.SELECTOR) { "Explicit current445 cohort required" }
            require(options["consent"] == CompilerCurrent445FixtureContract.CONSENT) { "Explicit bounded observation consent required" }
            fun number(name: String, default: Long, maximum: Long): Long = options[name]?.let {
                require(it.matches(Regex("[1-9][0-9]*")))
                val value = requireNotNull(it.toLongOrNull())
                require(value in 1..maximum) { "Current445 limit cannot be widened" }
                value
            } ?: default
            return CompilerCurrent445Settings(roots.single(), requireNotNull(options["binding-sha256"]),
                number("overall-seconds", 1_200, 1_200) * 1_000,
                number("per-job-seconds", 90, 90) * 1_000,
                number("evidence-mib", 256, 256) * 1_048_576)
        }
    }
}

internal data class CompilerCurrent445Pin(val path: String, val sha256: String, val size: Int)
internal data class CompilerCurrent445Region(val line: Int, val start: Int, val endExclusive: Int, val token: String)
internal data class CompilerCurrent445Expectation(
    val upstreamId: String, val ruleId: String, val severity: String, val count: Int?,
    val regions: List<CompilerCurrent445Region>,
)
internal data class CompilerCurrent445Case(
    val id: String, val path: String, val sourceSha256: String, val sourceBytes: Int,
    val flags: List<String>, val expectations: List<CompilerCurrent445Expectation>,
    val exitExpectation: String, val statusExpectation: String, val observationOnly: Boolean,
)

internal object CompilerCurrent445FixtureContract {
    const val SELECTOR = "diagnostic-gaps-current445-v1"
    const val CONSENT = "current445-nine-observations-not-qualification-v1"
    const val REFUSAL = "compiler_current445_fixture_binding_mismatch"
    const val BINDING_PATH = "providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-execution-v1.json"
    const val PLAN_PATH = "providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-source-v1.json"
    const val MAP_PATH = "modules/provider-compiler/src/main/resources/compiler-rule-map.json"
    const val CASE_PREFIX = "providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/"
    const val MAX_INPUT_BYTES = 8_388_608
    const val MAX_CAPTURE_BYTES = 25_165_824
    const val TERMINAL_RESERVE_BYTES = 8_388_608L
    const val JOB_RESERVE_BYTES = 16_777_216L
    val CURRENT = CompilerMappingIdentity("kotlin-compiler-2.4.21-common445-u2-v1",
        "sha256:f8566bded002ebeb09a047924036770dbefaa687950355cb08d7174e5f82a2de")
    val RULE_IDS = listOf("ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier")
    val UPSTREAM_IDS = listOf("ERROR_SUPPRESSION", "INAPPLICABLE_OPERATOR_MODIFIER")
    val CASE_IDS = listOf(
        "error_suppression_positive_valid_body", "error_suppression_negative_warning_name",
        "error_suppression_self_suppression_resists", "error_suppression_resolved_annotation_negative",
        "error_suppression_option_off_same_positive_bytes", "inapplicable_operator_positive_no_loop",
        "inapplicable_operator_negative_boolean", "inapplicable_operator_negative_no_operator",
        "inapplicable_operator_suppression_unknown",
    )
    val CASE_PATHS = listOf(
        "error_suppression/positive.kt", "error_suppression/negative_warning_name.kt",
        "error_suppression/self_suppression.kt", "error_suppression/negative_resolved_annotation.kt",
        "inapplicable_operator_modifier/positive.kt", "inapplicable_operator_modifier/negative_boolean.kt",
        "inapplicable_operator_modifier/negative_no_operator.kt", "inapplicable_operator_modifier/suppression_observation.kt",
    ).map { CASE_PREFIX + it }
    val CATALOG_COUNTS = linkedMapOf("data/rules-core.json" to 799, "data/rules-ecosystem.json" to 25,
        "data/rules-libraries.json" to 125, "data/rules-platform.json" to 716)
    val FIXED_DIGESTS = linkedMapOf(
        MAP_PATH to CURRENT.resourceDigest.removePrefix("sha256:"),
        PLAN_PATH to "ac16e9b94c9bfe50b6d1c1799c7c216ed907219d272e88383192dc3a7f0bea5a",
        "data/rules-core.json" to "28860cb987390ff1f604488aba023fab08b614f497155b71a9760f326c009694",
        "data/rules-ecosystem.json" to "261fb5c1fadbed7b9ae3b7768cba19e9716ba5067c4932e671183d13513fd2ac",
        "data/rules-libraries.json" to "3e671d030bb1734242c4f51c2de8723c11abb002b9c8a743176e7cc957592c9e",
        "data/rules-platform.json" to "0bfffdd0749353cb5b9b41485e29456037a5c669e232c6915fa5ca91aa6127af",
        "providers/compiler-fixtures/manifest.json" to "4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774",
        "providers/compiler-fixtures/planned-kmp-manifest.json" to "804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128",
    )
    val ROUTE_SOURCES = listOf(
        "modules/provider-compiler/build.gradle.kts",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCurrent445FixtureContract.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCurrent445FixtureOracle.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCurrent445FixtureRunner.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCurrent445FixtureContractTest.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCurrent445FixtureIntegrationTest.kt",
        "providers/compiler-fixtures/test_current445_route.py",
        "providers/compiler-fixtures/cohort-plans/CURRENT445_EXECUTION.md",
    )
    val CAPTURE_PATHS = (FIXED_DIGESTS.keys + CASE_PATHS + ROUTE_SOURCES).toSet()

    fun requireMapping(actual: CompilerMappingIdentity?) { check(actual == CURRENT) { REFUSAL } }

    /** Digest comes from the explicitly trusted host invocation, not a default read out of project JSON. */
    fun binding(bytes: ByteArray, expectedSha256: String): List<CompilerCurrent445Pin> {
        check(expectedSha256.matches(Regex("[0-9a-f]{64}")) &&
            Digests.sha256(bytes) == "sha256:" + expectedSha256) { REFUSAL }
        require(bytes.size <= 131_072)
        val root = Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(root.keys == setOf("schema_version", "execution_binding_id", "mapping_contract", "mapping_sha256",
            "source_plan_sha256", "ordered_case_ids", "selected_rule_ids", "execution_policy", "pins"))
        require(root.getValue("schema_version").jsonPrimitive.int == 1)
        require(root.string("execution_binding_id") == SELECTOR)
        require(root.string("mapping_contract") == CURRENT.contractId &&
            root.string("mapping_sha256") == FIXED_DIGESTS.getValue(MAP_PATH))
        require(root.string("source_plan_sha256") == FIXED_DIGESTS.getValue(PLAN_PATH))
        require(root.strings("ordered_case_ids") == CASE_IDS && root.strings("selected_rule_ids") == RULE_IDS)
        require(root.string("execution_policy") == CONSENT)
        val pins = root.getValue("pins").jsonArray.map {
            val p = it.jsonObject
            require(p.keys == setOf("path", "sha256", "bytes"))
            CompilerCurrent445Pin(p.string("path"), p.string("sha256"), p.getValue("bytes").jsonPrimitive.int).also { pin ->
                require(pin.path in CAPTURE_PATHS && pin.sha256.matches(Regex("[0-9a-f]{64}")))
                require(pin.size in 1..MAX_INPUT_BYTES)
                FIXED_DIGESTS[pin.path]?.let { digest -> require(digest == pin.sha256) }
            }
        }
        require(pins.map { it.path }.toSet() == CAPTURE_PATHS && pins.size == CAPTURE_PATHS.size)
        require(pins.sumOf { it.size.toLong() } <= MAX_CAPTURE_BYTES)
        return pins.toList()
    }

    fun cases(bytes: ByteArray): List<CompilerCurrent445Case> {
        require(Digests.sha256(bytes) == "sha256:" + FIXED_DIGESTS.getValue(PLAN_PATH))
        val plan = Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(plan.string("status") == "private_candidate_unadmitted_unrun")
        require(plan.getValue("ordered_cases_are_execution_authority").jsonPrimitive.boolean == false)
        require(plan.getValue("ordinary_six_kind_assessment").jsonPrimitive.boolean == false)
        require(plan.getValue("mapping_contract_requirement").jsonObject.getValue("expected_resource_sha256") == JsonNull)
        val cases = plan.getValue("cases").jsonArray.map { row ->
            val c = row.jsonObject
            require(c.strings("selected_rule_ids") == RULE_IDS)
            require(c.getValue("expected_outcome_matched") == JsonNull && c.string("execution_state") == "not_run")
            require(c.string("qualification") == "none" && !c.getValue("counts_as_current_test_or_implementation_evidence").jsonPrimitive.boolean)
            CompilerCurrent445Case(c.string("case_id"), c.string("path"), c.string("source_sha256"),
                c.getValue("source_bytes").jsonPrimitive.int, c.strings("compiler_flags"),
                c.getValue("source_derived_expectations").jsonArray.map { expectation ->
                    val e = expectation.jsonObject
                    val count = e.getValue("expected_full_parser_count").let { if (it == JsonNull) null else it.jsonPrimitive.int }
                    require(e.getValue("expected_selected_finding_count") == e.getValue("expected_full_parser_count"))
                    CompilerCurrent445Expectation(e.string("upstream_id"), e.string("stable_id"), e.string("severity"), count,
                        e.getValue("point_regions").jsonArray.map {
                            val p = it.jsonObject
                            CompilerCurrent445Region(p.getValue("line").jsonPrimitive.int,
                                p.getValue("start_column").jsonPrimitive.int, p.getValue("end_column_exclusive").jsonPrimitive.int,
                                p.string("source_token"))
                        })
                }, c.string("compiler_exit_expectation"), c.string("provider_status_expectation"),
                c.getValue("hard_error_suppression_observation_only").jsonPrimitive.boolean)
        }
        require(cases.map { it.id } == CASE_IDS && cases.map { it.path }.toSet() == CASE_PATHS.toSet())
        require(cases.count { it.observationOnly } == 1 && cases.last().observationOnly)
        val flags = listOf("-jvm-target", "17", "-language-version", "2.4", "-api-version", "2.4")
        for ((index, c) in cases.withIndex()) {
            require(c.flags == flags + if (index == 4) listOf("-Xwarning-level=ERROR_SUPPRESSION:disabled") else emptyList())
            require(c.expectations.map { it.ruleId } == RULE_IDS && c.expectations.map { it.upstreamId } == UPSTREAM_IDS)
            require(c.expectations.map { it.severity } == listOf("warning", "error"))
            require(c.expectations.all { it.count == null || it.count in 0..1 })
            require((c.expectations.any { it.count == null }) == c.observationOnly)
            require((c.exitExpectation == "unknown" && c.statusExpectation == "unknown") == c.observationOnly)
        }
        return cases.toList()
    }

    fun requireSource(c: CompilerCurrent445Case, bytes: ByteArray) {
        require(bytes.size == c.sourceBytes && Digests.sha256(bytes) == "sha256:" + c.sourceSha256)
        val lines = strictUtf8(bytes).split('\n')
        for (expectation in c.expectations) for (region in expectation.regions) {
            val line = lines.getOrNull(region.line - 1)?.removeSuffix("\r") ?: error("Region outside source")
            require(region.start in 0..line.length && region.endExclusive in (region.start + 1)..line.length)
            require(line.substring(region.start, region.endExclusive) == region.token) { "UTF16 source token region changed" }
        }
    }

    internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    internal fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}

/** Small independent finite budget: no fabricated legacy cohort/settings are passed to old controls. */
internal class CompilerCurrent445Budget(
    val settings: CompilerCurrent445Settings, private val startedNanos: Long = System.nanoTime(),
    private val nanoTime: () -> Long = System::nanoTime, private val ownerStop: () -> Boolean = { false },
) {
    var reserved = 0
        private set
    private var stopped: String? = null
    fun elapsedMillis(): Long = (nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000
    fun remainingMillis(): Long = (settings.overallMillis - elapsedMillis()).coerceAtLeast(0)
    fun stop(reason: String): Nothing { if (stopped == null) stopped = reason; error(reason) }
    fun requireActive(checkOwner: Boolean = true) {
        stopped?.let { error(it) }
        if (remainingMillis() == 0L) stop("current445_overall_deadline")
        if (checkOwner) {
            val requested = try { ownerStop() } catch (_: Exception) { stop("current445_unsafe_owner_control") }
            if (requested) stop("current445_owner_stop")
        }
    }
    fun reserve(caseId: String) {
        requireActive()
        check(reserved < CompilerCurrent445FixtureContract.CASE_IDS.size &&
            CompilerCurrent445FixtureContract.CASE_IDS[reserved] == caseId) { "No retry, omission, reorder or tenth call" }
        reserved++
    }
    fun timeoutMillis(): Long { requireActive(false); return minOf(settings.perJobMillis, remainingMillis()) }
    fun description(): Map<String, Any?> = mapOf("maximum_attempts" to 9, "reserved_attempts" to reserved,
        "elapsed_millis" to elapsedMillis(), "remaining_millis" to remainingMillis(), "stop_reason" to stopped,
        "overall_limit_millis" to settings.overallMillis, "per_job_limit_millis" to settings.perJobMillis,
        "evidence_limit_bytes" to settings.evidenceBytes, "compiler_heap_mib" to 512,
        "cooperative_deadline_not_os_hard_kill" to true, "resume_or_merge" to false)
}

/** Only documentary observation publication can settle here; run_complete remains false for this plan. */
internal class CompilerCurrent445Publication(private val checkpoint: () -> Unit) {
    private var attempted = false
    fun publish(eligible: Boolean, collected: () -> Unit, incomplete: (Throwable?) -> Unit): Boolean {
        check(!attempted) { "One-shot terminal publication; no success retry" }; attempted = true
        if (!eligible) { incomplete(null); return false }
        try { checkpoint(); collected(); checkpoint(); return true }
        catch (failure: Throwable) {
            try { incomplete(failure) } catch (correction: Throwable) { preserveCompilerFixtureFailure(failure, correction) }
            throw failure
        }
    }
}

internal fun current445Element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to current445Element(it.value) })
    is Iterable<*> -> JsonArray(value.map(::current445Element))
    else -> error("Unsupported current445 receipt value")
}
internal fun current445Bytes(value: Any?): ByteArray = (current445Element(value).toString() + "\n").toByteArray(Charsets.UTF_8)
