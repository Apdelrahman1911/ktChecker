package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.strictUtf8
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Separate ordinary fixture successor; neither old443 selectors nor current445 observations are aliases. */
internal data class CompilerOrdinary445Settings(
    val repositoryDirectory: String, val cohort: String, val bindingSha256: String,
    val sourceSnapshotPath: String, val sourceSnapshotSha256: String,
    val overallMillis: Long = 1_200_000, val perJobMillis: Long = 90_000,
    val evidenceBytes: Long = 268_435_456,
) {
    init {
        require(repositoryDirectory.isNotBlank() && '\u0000' !in repositoryDirectory)
        val path = Path.of(repositoryDirectory)
        require(path.isAbsolute && path.normalize() == path) { "Explicit normalized repository required" }
        require(cohort in CompilerOrdinary445FixtureContract.SELECTORS) { "Select exactly one of all25 fixed cohorts" }
        require(listOf(bindingSha256, sourceSnapshotSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
        CompilerOrdinary445FixtureContract.relativePath(sourceSnapshotPath)
        require(sourceSnapshotPath.startsWith("build/evidence/") && sourceSnapshotPath.endsWith(".json"))
        require(overallMillis in 1..1_200_000 && perJobMillis in 1..90_000)
        require(evidenceBytes in 67_108_864..268_435_456)
    }
    companion object {
        fun parse(args: Array<String>): CompilerOrdinary445Settings {
            val options = linkedMapOf<String, String>()
            val roots = mutableListOf<String>()
            for (arg in args) {
                if (!arg.startsWith("--")) { roots += arg; continue }
                val at = arg.indexOf('=')
                require(at > 2 && at < arg.lastIndex) { "Use closed --name=value options" }
                val name = arg.substring(2, at)
                require(name in setOf("cohort", "consent", "binding-sha256", "source-snapshot", "source-snapshot-sha256",
                    "overall-seconds", "per-job-seconds", "evidence-mib")) { "Unknown ordinary445 option" }
                require(options.put(name, arg.substring(at + 1)) == null) { "Duplicate ordinary445 option" }
            }
            require(roots.size == 1)
            require(options["consent"] == CompilerOrdinary445FixtureContract.CONSENT) { "Explicit owner fixture consent required" }
            fun number(name: String, default: Long, maximum: Long): Long = options[name]?.let {
                require(it.matches(Regex("[1-9][0-9]*")))
                val n = requireNotNull(it.toLongOrNull()); require(n in 1..maximum); n
            } ?: default
            return CompilerOrdinary445Settings(roots.single(), requireNotNull(options["cohort"]),
                requireNotNull(options["binding-sha256"]), requireNotNull(options["source-snapshot"]),
                requireNotNull(options["source-snapshot-sha256"]), number("overall-seconds", 1200, 1200) * 1000,
                number("per-job-seconds", 90, 90) * 1000, number("evidence-mib", 256, 256) * 1_048_576)
        }
    }
}

internal data class CompilerOrdinary445Pin(val path: String, val sha256: String, val size: Int) {
    fun description(): Map<String, Any?> = mapOf("path" to path, "sha256" to sha256, "bytes" to size)
}
internal data class CompilerOrdinary445Fixture(
    val ruleId: String, val upstreamId: String, val severity: String, val flags: List<String>,
    val positive: CompilerOrdinary445Pin, val negative: CompilerOrdinary445Pin, val authoredStatus: String,
)
internal data class CompilerOrdinary445Plan(val fixtures: List<CompilerOrdinary445Fixture>) {
    fun selected(cohort: String): List<CompilerOrdinary445Fixture> {
        val index = CompilerOrdinary445FixtureContract.SELECTORS.indexOf(cohort)
        require(index >= 0)
        return fixtures.drop(index * 4).take(4)
    }
    val sourcePins: List<CompilerOrdinary445Pin> get() = fixtures.flatMap { listOf(it.positive, it.negative) }
}
internal data class CompilerOrdinary445Job(
    val id: String, val kind: String, val control: String, val fixture: CompilerOrdinary445Fixture?,
    val ruleIds: List<String>, val flags: List<String>, val sources: Map<String, String>,
    val sourceOrigins: Map<String, Map<String, String>>, val primaryPath: String,
    val unsuppressedPath: String? = null, val requestedVersion: String = "2.4.21",
) {
    val refusal: Boolean get() = control == "requested_version_refusal"
    val hardErrorSuppression: Boolean get() = kind == "suppression" && fixture?.severity == "error"
}
internal data class CompilerOrdinary445SourceSnapshot(
    val head: String, val dirty: Boolean, val aggregate: String, val fileCount: Int,
) {
    fun description(): Map<String, Any?> = mapOf("head" to head, "dirty" to dirty,
        "owner_snapshot_aggregate_sha256" to aggregate, "owner_snapshot_file_count" to fileCount,
        "selected_binding_and_source_subset_matches" to true, "whole_tree_recomputed" to false,
        "aggregate_is_current_tree_claim" to false, "source_to_bytecode_or_execution_attestation" to false)
}

internal object CompilerOrdinary445FixtureContract {
    const val ROUTE = "compiler-ordinary445-six-kind-v1"
    const val CONSENT = "ordinary445-serial-fixtures-not-qualification-v1"
    const val REFUSAL = "compiler_ordinary445_fixture_binding_mismatch"
    const val BINDING_PATH = "providers/compiler-fixtures/cohort-plans/ordinary445-execution-v1.json"
    const val PLAN_PATH = "providers/compiler-fixtures/cohort-plans/ordinary445-source-v1.json"
    const val MANIFEST_PATH = "providers/compiler-fixtures/manifest.json"
    const val KMP_PATH = "providers/compiler-fixtures/planned-kmp-manifest.json"
    const val MAP_PATH = "modules/provider-compiler/src/main/resources/compiler-rule-map.json"
    const val MAX_INPUT_BYTES = 8_388_608
    const val MAX_CAPTURE_BYTES = 25_165_824
    const val TERMINAL_RESERVE_BYTES = 8_388_608L
    const val JOB_RESERVE_BYTES = 16_777_216L
    val CURRENT = CompilerMappingIdentity("kotlin-compiler-2.4.21-common445-u2-v1",
        "sha256:f8566bded002ebeb09a047924036770dbefaa687950355cb08d7174e5f82a2de")
    val SELECTORS = (1..25).map { "ordinary445-" + it.toString().padStart(2, '0') }
    val CASE_KINDS = listOf("positive", "negative", "configuration", "version", "suppression", "regression")
    val CATALOG_COUNTS = linkedMapOf("data/rules-core.json" to 799, "data/rules-ecosystem.json" to 25,
        "data/rules-libraries.json" to 125, "data/rules-platform.json" to 716)
    val FIXED_DIGESTS = linkedMapOf(
        MAP_PATH to CURRENT.resourceDigest.removePrefix("sha256:"),
        PLAN_PATH to "63b9d371a014792794c6bb2bcd1b69a988df89d92a0645abfdc265540772b245",
        MANIFEST_PATH to "4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774",
        KMP_PATH to "804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128",
        "data/rules-core.json" to "28860cb987390ff1f604488aba023fab08b614f497155b71a9760f326c009694",
        "data/rules-ecosystem.json" to "261fb5c1fadbed7b9ae3b7768cba19e9716ba5067c4932e671183d13513fd2ac",
        "data/rules-libraries.json" to "3e671d030bb1734242c4f51c2de8723c11abb002b9c8a743176e7cc957592c9e",
        "data/rules-platform.json" to "0bfffdd0749353cb5b9b41485e29456037a5c669e232c6915fa5ca91aa6127af",
    )
    val ROUTE_SOURCES = listOf(
        "modules/provider-compiler/build.gradle.kts",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureMappingContract.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControls.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureEvidence.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureProvenance.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureContract.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureOracle.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureLedger.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureRunner.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureContractTest.kt",
        "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerOrdinary445FixtureOracleTest.kt",
        "providers/compiler-fixtures/ordinary445.init.gradle.kts",
        "providers/compiler-fixtures/cohort-plans/ORDINARY445_EXECUTION.md",
    )
    fun requireMapping(actual: CompilerMappingIdentity?) { check(actual == CURRENT) { REFUSAL } }
    fun relativePath(path: String): String {
        require(path.length in 1..4096 && path.none { it == '\\' || it == '\u0000' || it == '\r' || it == '\n' || it == ':' })
        require(!Path.of(path).isAbsolute && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        return path
    }
    private fun pin(value: JsonElement): CompilerOrdinary445Pin {
        val p = value.jsonObject
        require(p.keys == setOf("path", "sha256", "bytes"))
        return CompilerOrdinary445Pin(relativePath(p.string("path")), p.string("sha256"), p.getValue("bytes").jsonPrimitive.int).also {
            require(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size in 1..MAX_INPUT_BYTES)
        }
    }
    fun requireBytes(pin: CompilerOrdinary445Pin, bytes: ByteArray) {
        require(bytes.size == pin.size && Digests.sha256(bytes) == "sha256:" + pin.sha256) { "Pinned ordinary445 bytes changed" }
    }
    fun plan(bytes: ByteArray, manifestBytes: ByteArray, registry: CompilerRuleRegistry): CompilerOrdinary445Plan {
        requireMapping(registry.mappingIdentity)
        require(Digests.sha256(bytes) == "sha256:" + FIXED_DIGESTS.getValue(PLAN_PATH))
        require(Digests.sha256(manifestBytes) == "sha256:" + FIXED_DIGESTS.getValue(MANIFEST_PATH))
        val root = Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(root.getValue("schema_version").jsonPrimitive.int == 1 && root.string("route_id") == ROUTE)
        require(root.string("mapping_contract") == CURRENT.contractId && root.string("mapping_sha256") == FIXED_DIGESTS.getValue(MAP_PATH))
        require(root.string("manifest_sha256") == FIXED_DIGESTS.getValue(MANIFEST_PATH) && root.string("compiler_version") == "2.4.21")
        require(root.string("status") == "authored_schedule_not_execution_evidence" && root.string("qualification") == "none")
        require(root.strings("case_kinds") == CASE_KINDS && root.string("fixture_order") == "lexicographic_rule_id")
        val authored = Json.parseToJsonElement(strictUtf8(manifestBytes)).jsonObject
        require(authored.getValue("schema_version").jsonPrimitive.int == 1 && authored.string("kotlin_version") == "2.4.21")
        val original = authored.getValue("fixtures").jsonArray.map { it.jsonObject }
        require(original.size == 98 && original.map { it.string("rule_id") }.toSet().size == 98)
        val byRule = original.associateBy { it.string("rule_id") }
        val fixtures = root.getValue("fixtures").jsonArray.map { row ->
            val r = row.jsonObject
            require(r.keys == setOf("rule_id", "upstream_id", "declared_severity", "flags", "authored_status_not_execution", "positive", "negative"))
            val f = CompilerOrdinary445Fixture(r.string("rule_id"), r.string("upstream_id"), r.string("declared_severity"),
                r.strings("flags"), pin(r.getValue("positive")), pin(r.getValue("negative")), r.string("authored_status_not_execution"))
            val source = byRule.getValue(f.ruleId)
            val mapping = registry.byRuleId.getValue(f.ruleId)
            require(mapping.jvmApplicable && mapping.backend in setOf("common", "jvm") &&
                mapping.upstreamId == f.upstreamId && mapping.declaredSeverity.lowercase() == f.severity)
            require(f.upstreamId == source.string("upstream_id") && f.severity == source.string("declared_severity") &&
                f.authoredStatus == source.string("status") && f.flags == source.strings("flags"))
            require(f.positive.path == "providers/compiler-fixtures/" + relativePath(source.string("positive")) &&
                f.negative.path == "providers/compiler-fixtures/" + relativePath(source.string("negative")))
            require(f.flags == listOf("-jvm-target", "17") || f.flags == listOf("-jvm-target", "17", "-Wextra") ||
                f.flags == listOf("-jvm-target", "17", "-Xuse-fir-experimental-checkers"))
            f
        }
        require(fixtures.size == 98 && fixtures.map { it.ruleId } == byRule.keys.sorted())
        require(fixtures.groupingBy { it.severity }.eachCount() == mapOf("error" to 79, "warning" to 19))
        val plan = CompilerOrdinary445Plan(fixtures.toList())
        require(plan.sourcePins.map { it.path }.toSet().size == 196)
        val cohorts = root.getValue("cohorts").jsonArray.map { it.jsonObject }
        require(cohorts.map { it.string("selector") } == SELECTORS)
        for (c in cohorts) {
            val selected = plan.selected(c.string("selector"))
            val compilerCalls = 6 * selected.size + selected.count { it.severity == "warning" }
            require(c.strings("rule_ids") == selected.map { it.ruleId } && c.getValue("compiler_calls").jsonPrimitive.int == compilerCalls &&
                c.getValue("pre_runtime_refusals").jsonPrimitive.int == 1 && c.getValue("provider_calls").jsonPrimitive.int == compilerCalls + 1)
        }
        return plan
    }
    /** The host's explicit hash is the binding authority; project JSON is not self-authorizing. */
    fun binding(bytes: ByteArray, expectedSha256: String, plan: CompilerOrdinary445Plan): List<CompilerOrdinary445Pin> {
        check(expectedSha256.matches(Regex("[0-9a-f]{64}")) && Digests.sha256(bytes) == "sha256:" + expectedSha256) { REFUSAL }
        require(bytes.size <= 131_072)
        val root = Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(root.keys == setOf("schema_version", "execution_binding_id", "mapping_contract", "mapping_sha256",
            "source_plan_sha256", "ordered_selectors", "ordered_rule_ids", "execution_policy", "pins"))
        require(root.getValue("schema_version").jsonPrimitive.int == 1 && root.string("execution_binding_id") == ROUTE)
        require(root.string("mapping_contract") == CURRENT.contractId && root.string("mapping_sha256") == FIXED_DIGESTS.getValue(MAP_PATH))
        require(root.string("source_plan_sha256") == FIXED_DIGESTS.getValue(PLAN_PATH) && root.string("execution_policy") == CONSENT)
        require(root.strings("ordered_selectors") == SELECTORS && root.strings("ordered_rule_ids") == plan.fixtures.map { it.ruleId })
        val pins = root.getValue("pins").jsonArray.map(::pin)
        val required = (FIXED_DIGESTS.keys + ROUTE_SOURCES + plan.sourcePins.map { it.path }).toSet()
        require(pins.size == required.size && pins.map { it.path }.toSet() == required && pins.sumOf { it.size.toLong() } <= MAX_CAPTURE_BYTES)
        val byPath = pins.associateBy { it.path }
        FIXED_DIGESTS.forEach { (path, digest) -> require(byPath.getValue(path).sha256 == digest) }
        plan.sourcePins.forEach { require(byPath.getValue(it.path) == it) }
        return pins.toList()
    }
    /** Read only the owner-selected snapshot bytes; never follow its unselected files or .git refs. */
    fun sourceSnapshot(bytes: ByteArray, expectedSha256: String, pins: List<CompilerOrdinary445Pin>,
        bindingBytes: ByteArray, bindingSha256: String): CompilerOrdinary445SourceSnapshot {
        require(bindingSha256.matches(Regex("[0-9a-f]{64}")) && Digests.sha256(bindingBytes) == "sha256:" + bindingSha256)
        require(bytes.size in 1..MAX_INPUT_BYTES && expectedSha256.matches(Regex("[0-9a-f]{64}")) &&
            Digests.sha256(bytes) == "sha256:" + expectedSha256)
        val root = Json.parseToJsonElement(strictUtf8(bytes)).jsonObject
        require(root.keys == setOf("capturedAt", "head", "dirty", "files", "aggregate")) { "Exact owner snapshot schema required" }
        val head = root.string("head"); val aggregate = root.string("aggregate")
        require(head.matches(Regex("[0-9a-f]{40}")) && aggregate.matches(Regex("[0-9a-f]{64}")))
        val dirty = root.getValue("dirty").jsonPrimitive
        require(!dirty.isString && dirty.booleanOrNull != null)
        val files = root.getValue("files").jsonArray
        require(files.size in 1..100_000 && root.string("capturedAt").isNotBlank())
        val byPath = linkedMapOf<String, JsonObject>()
        files.forEach { row ->
            val f = row.jsonObject
            require(f.keys == setOf("path", "bytes", "sha256"))
            val path = relativePath(f.string("path"))
            require(byPath.put(path, f) == null) { "Duplicate owner snapshot path" }
        }
        val all = pins + CompilerOrdinary445Pin(BINDING_PATH, bindingSha256, bindingBytes.size)
        all.forEach { selected ->
            val f = byPath.getValue(selected.path)
            require(f.string("sha256") == selected.sha256 && f.getValue("bytes").jsonPrimitive.long == selected.size.toLong()) {
                "Selected source or binding not in exact owner snapshot"
            }
        }
        return CompilerOrdinary445SourceSnapshot(head, checkNotNull(dirty.booleanOrNull), aggregate, files.size)
    }
    fun schedule(plan: CompilerOrdinary445Plan, cohort: String, sourceBytes: (String) -> ByteArray): List<CompilerOrdinary445Job> {
        val selected = plan.selected(cohort)
        require(selected.isNotEmpty() && selected.size <= 4)
        val jobs = mutableListOf<CompilerOrdinary445Job>()
        selected.forEach { f ->
            fun text(pin: CompilerOrdinary445Pin): String = sourceBytes(pin.path).also { requireBytes(pin, it) }.let(::strictUtf8)
            val positive = text(f.positive); val negative = text(f.negative)
            val slug = f.upstreamId.lowercase(); val ordinaryPath = "cases/$slug.kt"
            fun add(kind: String, control: String, text: String, flags: List<String> = f.flags,
                path: String = ordinaryPath, origin: CompilerOrdinary445Pin = f.positive, adjacent: Pair<String, String>? = null) {
                val sources = linkedMapOf(path to text); adjacent?.let { sources[it.first] = it.second }
                val origins = sources.keys.associateWith { mapOf("manifest_source_path" to origin.path,
                    "manifest_source_sha256" to origin.sha256, "transformation" to if (it == adjacent?.first) "repackaged_unsuppressed_control" else control) }
                jobs += CompilerOrdinary445Job("$slug-$control", kind, control, f, listOf(f.ruleId), flags.toList(),
                    sources.toMap(), origins, path, adjacent?.first)
            }
            add("positive", "positive", positive)
            add("negative", "negative", negative, origin = f.negative)
            val configured = if (f.severity == "warning") f.flags + "-Xwarning-level=${f.upstreamId}:error"
                else f.flags.toMutableList().also { it[it.indexOf("-jvm-target") + 1] = "21" }
            add("configuration", "configuration", positive, configured)
            if (f.severity == "warning") add("configuration", "warning_disabled", positive, f.flags + "-Xwarning-level=${f.upstreamId}:disabled")
            add("version", "version", positive, f.flags + listOf("-language-version", "2.4", "-api-version", "2.4"))
            val adjacent = if (f.severity == "warning") {
                val pattern = Regex("(?m)^package ([A-Za-z_][A-Za-z0-9_.]*)[ \\t]*;?[ \\t]*$")
                require(pattern.findAll(positive).count() == 1) { "Exactly one package required for scoped control" }
                "unsuppressed/$slug.kt" to pattern.replace(positive) { "package " + it.groupValues[1] + ".ordinary445control" }
            } else null
            add("suppression", "suppression", "@file:Suppress(\"${f.upstreamId}\")\n" + positive, adjacent = adjacent)
            add("regression", "regression", "\n\n" + positive, path = "moved/$slug.kt")
        }
        val path = "version-control.kt"
        jobs += CompilerOrdinary445Job("wrong-requested-compiler-version", "version", "requested_version_refusal", null,
            selected.map { it.ruleId }, listOf("-jvm-target", "17"), mapOf(path to "fun versionControl() = 1\n"),
            mapOf(path to mapOf("transformation" to "fixed_authored_refusal_input_no_compiler_execution")), path, requestedVersion = "2.4.20")
        require(jobs.size == 6 * selected.size + selected.count { it.severity == "warning" } + 1)
        require(jobs.map { it.id }.toSet().size == jobs.size && jobs.count { it.refusal } == 1 && jobs.last().refusal)
        jobs.forEach { CompilerFlags.validate(it.flags) }
        return jobs.toList()
    }
    internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    internal fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}

/** Every call comes from the complete selected schedule, not an arbitrary fixed attempt cap. */
internal class CompilerOrdinary445Budget(
    val settings: CompilerOrdinary445Settings, private val orderedJobs: List<String>,
    private val startedNanos: Long = System.nanoTime(), private val nanoTime: () -> Long = System::nanoTime,
    private val ownerStop: () -> Boolean = { false },
) {
    init { require(orderedJobs.isNotEmpty() && orderedJobs.size == orderedJobs.toSet().size) }
    var reserved = 0
        private set
    private var stopped: String? = null
    fun elapsedMillis(): Long = (nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000
    fun remainingMillis(): Long = (settings.overallMillis - elapsedMillis()).coerceAtLeast(0)
    fun stop(reason: String): Nothing { if (stopped == null) stopped = reason; error(reason) }
    fun requireActive(checkOwner: Boolean = true) {
        stopped?.let { error(it) }
        if (remainingMillis() == 0L) stop("ordinary445_overall_deadline")
        if (checkOwner) {
            val requested = try { ownerStop() } catch (_: Exception) { stop("ordinary445_unsafe_owner_control") }
            if (requested) stop("ordinary445_owner_stop")
        }
    }
    fun reserve(id: String) {
        requireActive()
        check(orderedJobs.getOrNull(reserved) == id) { "No retry, skip, reorder, fallback or regroup" }
        reserved++
    }
    fun timeoutMillis(): Long { requireActive(false); return minOf(settings.perJobMillis, remainingMillis()) }
    fun description(): Map<String, Any?> = mapOf("maximum_provider_calls_derived_from_schedule" to orderedJobs.size,
        "reserved_provider_calls" to reserved, "ordered_job_ids" to orderedJobs, "elapsed_millis" to elapsedMillis(),
        "remaining_millis" to remainingMillis(), "stop_reason" to stopped, "overall_limit_millis" to settings.overallMillis,
        "per_job_limit_millis" to settings.perJobMillis, "evidence_limit_bytes" to settings.evidenceBytes,
        "compiler_heap_mib" to 512, "cooperative_deadline_not_os_hard_kill" to true, "resume_or_merge" to false)
}

internal class CompilerOrdinary445Publication(private val checkpoint: () -> Unit) {
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
internal fun ordinary445Element(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to ordinary445Element(it.value) })
    is Iterable<*> -> JsonArray(value.map(::ordinary445Element))
    else -> error("Unsupported ordinary445 receipt value")
}
internal fun ordinary445Bytes(value: Any?): ByteArray = (ordinary445Element(value).toString() + "\n").toByteArray(Charsets.UTF_8)
