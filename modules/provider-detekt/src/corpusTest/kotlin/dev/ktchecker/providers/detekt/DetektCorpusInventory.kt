package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.safeRelativePath
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.json.*

/** Pinned source-data loader, not an engine, execution authority, or historical stage importer. */
internal class DetektCorpusInventory private constructor(
    private val repository: Path,
    private val captured: Map<String, ByteArray>,
) {
    private val parsed = captured.filterKeys { it.endsWith(".json") }.mapValues { (_, bytes) ->
        // Bytes must match compiled immutable pins before any JSON decoder can accept them.
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
    val fixtures: List<JsonObject> = Collections.unmodifiableList(parsed.filterKeys {
        it.startsWith(BASELINE + "/") || it.startsWith(EXPANSION + "/fixtures/")
    }.values.toList())
    val expansionRegistry: JsonObject get() = parsed.getValue("$EXPANSION/cohorts.json")

    init {
        require(fixtures.size == DetektCorpusCohort.FULL_AUTHORED_RULES)
        val ids = fixtures.map { it.text("rule_id") }
        require(ids.size == ids.distinct().size && ids.toSet() == DetektCorpusCohort.authoredRuleIds)
        require(fixtures.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        require(fixtures.count { it.text("default_activation") == "advisory" } == 11)
        require(fixtures.count { it.text("default_activation") == "opt_in" } == 5)
        for (fixture in fixtures) {
            val mapping = DetektCatalog.byId.getValue(fixture.text("rule_id"))
            require(mapping.exact && mapping.depth == "syntax")
            require(fixture.integer("schema_version") == 1 && fixture.text("authoring_state") == "unexecuted")
            require(fixture.text("provider_id") == "detekt" && fixture.text("provider_version") == DETEKT_VERSION)
            require(fixture.text("embedded_kotlin_version") == DETEKT_EMBEDDED_KOTLIN)
            require(fixture.text("upstream_id") == mapping.upstream_id && fixture.text("relation") == mapping.relation)
            require(fixture.text("depth") == mapping.depth && fixture.text("default_activation") == mapping.default_activation)
            require(fixture.text("catalog_record_sha256") == mapping.catalog_record_sha256)
        }
        val baseline = fixtures.filter { it.text("corpus_id") == "detekt-syntax-corpus-v1" }
        require(baseline.size == DetektCorpusCohort.BASELINE_AUTHORED_RULES)
        require(baseline.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.BASELINE_AUTHORED_ANALYSES)
        validateExpansion()
    }

    /** The driver invokes this at START and with every later source/code witness verification. */
    fun verify() {
        verifyMembership(repository)
        for ((path, bytes) in captured) require(bytes.contentEquals(readPinned(repository, path))) {
            "Authored corpus data changed after loading: $path"
        }
    }

    fun matrix(cohort: DetektCorpusCohort): JsonObject {
        require(cohort.expansion)
        return parsed.getValue("$EXPANSION/cohorts/${cohort.name}.json")
    }

    private fun validateExpansion() {
        val registry = expansionRegistry
        require(registry.text("state") == "CONNECTED_SOURCE_CANDIDATE_UNCOMPILED_UNEXECUTED_NOT_ADMITTED")
        require(registry["default_cohort"] == JsonNull && !registry.flag("multi_cohort_or_all_selector_allowed"))
        require(registry.integer("actual_tests_executed") == 0 && registry.integer("actual_provider_invocations") == 0)
        require(registry.integer("historical_stage_transfer") == 0)
        val expanded = DetektCorpusCohort.all.filter { it.expansion }
        require(registry.getValue("allowed_cohort_names").jsonArray.map { it.jsonPrimitive.content } == expanded.map { it.name })
        val scope = parsed.getValue("$EXPANSION/scope.json")
        require(registry.text("scope_reference") == "$EXPANSION/scope.json")
        require(registry.text("scope_sha256") == hash(captured.getValue("$EXPANSION/scope.json")))
        require(registry.text("integration_contract_path") == "$EXPANSION/integration-contract.json")
        require(registry.text("integration_contract_sha256") == hash(captured.getValue("$EXPANSION/integration-contract.json")))
        val currentScope = scope.getValue("current_scope").jsonObject
        require(currentScope.integer("ids") == 1663 && currentScope.integer("contexts") == 8 && currentScope.integer("new_contexts") == 0)
        require(currentScope.integer("historical_stage_transfer") == 0 && currentScope["scope_exclusions"] == JsonArray(emptyList()))
        require(currentScope["changed_catalog_or_activation_files"] == JsonArray(emptyList()))
        val combined = scope.getValue("combined_proposed_authoring_scope").jsonObject
        require(combined.integer("rule_count") == DetektCorpusCohort.FULL_AUTHORED_RULES)
        require(combined.integer("authored_analyses") == DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        require(!combined.flag("scope_reduction") && !combined.flag("aggregate_execution_or_sidecar_created"))
        require(combined.integer("baseline_execution_stage_transfer") == 0)
        val included = DetektCatalog.mappings.filter { it.exact && it.depth == "syntax" }.map { it.rule_id }.sorted()
        require(included.size == 156 && scope.getValue("existing_detekt_mapping").jsonObject
            .getValue("included156_rule_ids").jsonArray.map { it.jsonPrimitive.content } == included)
        val oldPlans = scope.getValue("preserved_baseline").jsonObject.getValue("cohorts").jsonArray
        require(oldPlans.size == DetektCorpusCohort.baseline.size)
        for (old in DetektCorpusCohort.baseline) {
            val row = oldPlans.single { it.jsonObject.text("cohort_id") == old.name }.jsonObject
            require(row.text("plan_identity_sha256") == hash(old.planIdentity.toByteArray()))
            require(row.integer("planned_invocations") == old.plannedInvocations)
        }
        val plans = registry.getValue("cohorts").jsonArray
        require(plans.size == expanded.size)
        for ((cohort, value) in expanded.zip(plans)) {
            val plan = value.jsonObject
            require(plan.text("cohort_id") == cohort.name && !plan.flag("execution_admitted"))
            require(plan.text("runtime_registration_state") == "connected_source_candidate_uncompiled_unexecuted")
            require(plan.flag("requires_explicit_owner_opt_in") == (cohort === DetektCorpusCohort.PUBLIC_CONTRACT_POLICY))
            require(plan.text("proposed_sidecar") == "evidence/verified-jvm-${cohort.evidenceStem}-${cohort.name}-native-preview1.json")
            val matrixPath = "$EXPANSION/cohorts/${cohort.name}.json"
            require(plan.text("case_matrix_path") == matrixPath && plan.text("case_matrix_sha256") == hash(captured.getValue(matrixPath)))
            val identity = plan.getValue("plan_identity").jsonObject
            require(canonical(identity) == cohort.planIdentity)
            require(plan.text("plan_identity_sha256") == hash(cohort.planIdentity.toByteArray()))
            require(identity["selected_rules"] == plan["selected_rules"])
            require(identity["case_matrix_sha256"] == plan["case_matrix_sha256"])
            require(identity["scope_sha256"] == registry["scope_sha256"])
            require(identity["integration_contract_sha256"] == registry["integration_contract_sha256"])
            require(!identity.flag("previous_stages_accepted") && !identity.flag("aggregate_context_activation"))
            val limits = identity.getValue("limits").jsonObject
            require(limits.integer("max_invocations") == DetektCorpusCohort.MAX_INVOCATIONS)
            require(limits.integer("aggregate_millis").toLong() == DetektCorpusCohort.MAX_AGGREGATE_MILLIS)
            require(limits.integer("child_millis").toLong() == DetektCorpusCohort.MAX_CHILD_MILLIS)
            require(identity.getValue("planned_invocations").jsonObject.mapValues { it.value.jsonPrimitive.int } ==
                cohort.invocationsByKind.mapKeys { it.key.id })
            val selected = plan.getValue("selected_rules").jsonArray
            require(selected.map { it.jsonObject.text("rule_id") } == cohort.analysesByRule.keys.toList())
            val matrix = matrix(cohort)
            require(matrix.text("state") == "AUTHORED_NOT_RUN" && matrix.flag("candidate_only"))
            val analyses = matrix.getValue("analyses").jsonArray
            require(analyses.size == 27)
            selected.forEachIndexed { ruleIndex, item ->
                val selection = item.jsonObject
                val path = selection.text("fixture_path")
                require(path.startsWith("$EXPANSION/fixtures/") && selection.text("fixture_sha256") == hash(captured.getValue(path)))
                val fixture = parsed.getValue(path)
                require(fixture.text("corpus_id") == "detekt-syntax-expansion-v1")
                require(selection.text("rule_id") == fixture.text("rule_id") && selection.text("upstream_id") == fixture.text("upstream_id"))
                require(selection.text("default_activation") == fixture.text("default_activation") && selection.integer("analyses") == 9)
                val controls = fixture.getValue("controls").jsonArray
                require(controls.map { it.jsonObject.text("id") } == DetektCorpusExpansionPins.controlIds.getValue(fixture.text("rule_id")))
                repeat(9) { caseIndex -> validateAnalysis(analyses[ruleIndex * 9 + caseIndex].jsonObject, fixture, path, caseIndex) }
            }
            val schedule = matrix.getValue("invocation_schedule").jsonArray
            val expected = requireNotNull(cohort.expectedSchedule)
            require(schedule.size == expected.size && expected.size == 37)
            var analysisIndex = 0
            schedule.zip(expected).forEachIndexed { index, (value, invocation) ->
                val call = value.jsonObject
                require(call.integer("ordinal") == index + 1 && call.text("kind") == invocation.kind.id)
                require(call.text("rule_id") == invocation.ruleId && call.text("case_id") == invocation.caseId)
                val native = invocation.kind in setOf(CorpusInvocationKind.ANALYSIS, CorpusInvocationKind.CLI_PROBE)
                require(call.flag("native_launch_required") == native)
                require(call.flag("runtime_callback_required") == (invocation.kind != CorpusInvocationKind.ABSTENTION))
                if (invocation.kind == CorpusInvocationKind.ANALYSIS) require(call.integer("matrix_index") == analysisIndex++)
                if (invocation.kind == CorpusInvocationKind.CLI_PROBE) require(call.text("expected_version") == DETEKT_VERSION)
                if (invocation.kind == CorpusInvocationKind.ABSTENTION) require(call.text("request_target") == invocation.caseId.removePrefix("unsupported-"))
                if (invocation.kind == CorpusInvocationKind.AUTHORIZATION_DENIAL) require(call.text("required_no_execution_code") == "execution_not_authorized")
            }
            require(analysisIndex == 27)
            val counts = matrix.getValue("counts").jsonObject
            require(plan["counts"] == counts)
            require(counts.integer("authored_analyses") == 27 && counts.integer("all_calls") == cohort.plannedInvocations)
            require(counts.integer("native_launch_ceiling") == cohort.nativeLaunchCeiling)
            require(counts.integer("expected_runtime_callbacks") == cohort.expectedRuntimeCallbacks)
            require(counts.integer("planned_progress_events") == 2 + 3 * cohort.plannedInvocations)
            require(counts.integer("independent_controls") == 9 && counts.integer("actual_executions") == 0 && counts["actual_findings"] == JsonNull)
            require(counts.integer("authored_expected_finding_observations") == analyses.sumOf { it.jsonObject.integer("authored_expected_count") })
            require(counts.integer("authored_alert_bearing_analyses") == analyses.count { it.jsonObject.integer("authored_expected_count") > 0 })
            require(counts.integer("authored_zero_finding_analyses") == analyses.count { it.jsonObject.integer("authored_expected_count") == 0 })
            require(counts.integer("unique_rule_source_sets") == analyses.map {
                val row = it.jsonObject
                row.text("rule_id") to row.text("source_set_sha256")
            }.distinct().size)
            require(counts.integer("unique_rule_source_configuration_sets") == analyses.map {
                val row = it.jsonObject
                listOf(row.text("rule_id"), row.text("source_set_sha256"), canonical(row.getValue("upstream_options")),
                    row.flag("rule_disabled_for_this_configuration_control").toString())
            }.distinct().size)
            require(counts.integer("natural_project_observations") == 0 && counts.integer("precision_reviewed_natural_observations") == 0)
        }
    }

    private fun validateAnalysis(row: JsonObject, fixture: JsonObject, path: String, index: Int) {
        val standard = listOf("positive", "negative", "configuration", "version", "suppression", "regression")
        val id = standard.getOrNull(index) ?: fixture.getValue("controls").jsonArray[index - 6].jsonObject.text("id")
        val pointer = when (id) {
            "positive", "configuration", "version" -> "/positive"
            "negative", "regression" -> "/negative"
            "suppression" -> "/suppression"
            else -> "/controls/${index - 6}"
        }
        val node = if (index < 6) fixture.getValue(pointer.removePrefix("/")).jsonObject
            else fixture.getValue("controls").jsonArray[index - 6].jsonObject
        val disabled = id == "configuration"
        require(row.text("rule_id") == fixture.text("rule_id") && row.text("fixture_path") == path)
        require(row.text("case_id") == id && row.text("fixture_json_pointer") == pointer)
        require(row.text("case_kind") == if (index < 6) id else node.text("case_kind"))
        val standardAssessments = listOf("independent_positive", "independent_valid_alternative",
            "explicit_disabled_control_not_scope_reduction", "source_replay_for_pinned_engine_tuple_not_new_predicate",
            "real_upstream_suppression_with_unsuppressed_neighbor", "negative_child_snapshot_replay_not_new_predicate")
        require(row.text("assessment") == if (index < 6) standardAssessments[index] else node.text("assessment"))
        require(row.text("intent") == node.text("intent"))
        require(row.text("state") == "not_run" && !row.flag("attempted") && row["observed_findings"] == JsonNull)
        require(!row.flag("natural_project_observation") && row.flag("require_actual_native_launch"))
        require(row.flag("rule_disabled_for_this_configuration_control") == disabled)
        require(row.flag("require_active_upstream_descriptor") == !disabled && row.flag("selected_sarif_descriptor_enabled_required") == !disabled)
        require(row.text("selected_sarif_rule_id") == "detekt." + fixture.text("upstream_id").replace(':', '.'))
        require(row["upstream_options"] == (node["options"] ?: JsonObject(emptyMap())))
        val expected = if (disabled) JsonArray(emptyList()) else node.getValue("expected").jsonArray
        require(row["authored_expected_ranges"] == expected && row.integer("authored_expected_count") == expected.size)
        require(row["expected_override"] == if (disabled) JsonArray(emptyList()) else JsonNull)
        val sources = node.getValue("sources").jsonObject
        require(row.text("source_set_sha256") == hash(canonical(sources).toByteArray()))
        val sourceRows = sources.toSortedMap().map { (name, value) ->
            val source = value.jsonPrimitive.also { require(it.isString) }.content
            require(source.isNotEmpty() && source.endsWith('\n') && source.all { it.code in 1..127 && it != '\r' })
            safeRelativePath(name)
            require(name.endsWith(".kt"))
            buildJsonObject { put("path", name); put("sha256", hash(source.toByteArray())); put("bytes", source.toByteArray().size) }
        }
        require(row["source_files"] == JsonArray(sourceRows))
    }

    companion object {
        const val BASELINE = "providers/detekt-fixtures/corpus/syntax-v1"
        const val EXPANSION = DetektCorpusExpansionPins.ROOT

        fun load(repository: Path): DetektCorpusInventory {
            val root = repository.toAbsolutePath().normalize()
            verifyMembership(root)
            val bytes = DetektCorpusExpansionPins.files.keys.associateWith { readPinned(root, it) }
            return DetektCorpusInventory(root, bytes).also { it.verify() }
        }

        private fun verifyMembership(repository: Path) {
            for (root in listOf(BASELINE, EXPANSION)) {
                checkedPath(repository, root, directory = true)
                // Bound even a replaced tree before filtering it; unknown directories cannot hide inputs.
                val entries = Files.walk(repository.resolve(root), 3).use { it.limit(65).toList() }
                require(entries.size <= 64) { "Authored corpus membership exceeds the fixed bound" }
                val directories = if (root == BASELINE) setOf(root) else setOf(root, "$root/fixtures", "$root/cohorts")
                val actual = entries.filter { path ->
                    require(!Files.isSymbolicLink(path)) { "Corpus paths cannot be links" }
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                        require(repository.relativize(path).toString().replace('\\', '/') in directories)
                        false
                    } else {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                        root == EXPANSION || path.toString().endsWith(".json")
                    }
                }.map { repository.relativize(it).toString().replace('\\', '/') }.sorted()
                require(actual == DetektCorpusExpansionPins.files.keys.filter { it.startsWith("$root/") }.sorted()) {
                    "Closed corpus file membership changed: $root"
                }
            }
        }

        private fun checkedPath(repository: Path, relative: String, directory: Boolean = false): Path {
            require(Files.isDirectory(repository, NOFOLLOW_LINKS) && !Files.isSymbolicLink(repository))
            val path = repository.resolve(safeRelativePath(relative))
            var current = repository
            for (part in repository.relativize(path)) {
                current = current.resolve(part)
                require(!Files.isSymbolicLink(current))
                require(if (current == path && !directory) Files.isRegularFile(current, NOFOLLOW_LINKS) else Files.isDirectory(current, NOFOLLOW_LINKS))
            }
            return path
        }

        private fun readPinned(repository: Path, relative: String): ByteArray {
            val path = checkedPath(repository, relative)
            require(Files.size(path) in 1..1_048_576L)
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(1_048_577) }
            require(bytes.size in 1..1_048_576 && hash(bytes) == DetektCorpusExpansionPins.files.getValue(relative)) {
                "Pinned authored corpus bytes changed: $relative"
            }
            return bytes
        }

        internal fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, item) -> quoteAscii(key) + ":" + canonical(item) }
            is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
            is JsonPrimitive -> if (value.isString) quoteAscii(value.content) else value.toString()
        }
        private fun quoteAscii(value: String): String = buildString {
            append('"')
            for (character in value) when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character.code >= 0x7f)
                    append("\\u" + character.code.toString(16).padStart(4, '0')) else append(character)
            }
            append('"')
        }
        private fun hash(bytes: ByteArray): String = Digests.sha256(bytes).removePrefix("sha256:")
        private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
        private fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int
        private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
    }
}
