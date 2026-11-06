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
        it.startsWith(BASELINE + "/") || it.startsWith(EXPANSION + "/fixtures/") || it.startsWith(PROTOCOL + "/fixtures/") ||
            it.startsWith(OBSERVABILITY + "/fixtures/") || it.startsWith(LOCAL_SHAPES + "/fixtures/")
    }.values.toList())
    val expansionRegistry: JsonObject get() = parsed.getValue("$EXPANSION/cohorts.json")
    val protocolRegistry: JsonObject get() = parsed.getValue("$PROTOCOL/cohorts.json")
    val observabilityRegistry: JsonObject get() = parsed.getValue("$OBSERVABILITY/cohorts.json")
    val localShapesRegistry: JsonObject get() = parsed.getValue("$LOCAL_SHAPES/cohorts.json")

    init {
        require(fixtures.size == DetektCorpusCohort.FULL_AUTHORED_RULES)
        val ids = fixtures.map { it.text("rule_id") }
        require(ids.size == ids.distinct().size && ids.toSet() == DetektCorpusCohort.authoredRuleIds)
        require(fixtures.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        require(fixtures.count { it.text("default_activation") == "advisory" } == 15)
        require(fixtures.count { it.text("default_activation") == "opt_in" } == 16)
        for (fixture in fixtures) {
            val mapping = DetektCatalog.byId.getValue(fixture.text("rule_id"))
            require(mapping.exact && mapping.depth == "syntax")
            require(fixture.integer("schema_version") == 1 && fixture.text("authoring_state") == "unexecuted")
            require(fixture.text("provider_id") == "detekt" && fixture.text("provider_version") == DETEKT_VERSION)
            require(fixture.text("embedded_kotlin_version") == DETEKT_EMBEDDED_KOTLIN)
            require(fixture.text("upstream_id") == mapping.upstream_id && fixture.text("relation") == mapping.relation)
            require(fixture.text("depth") == mapping.depth && fixture.text("default_activation") == mapping.default_activation)
            require(fixture.text("catalog_record_sha256") == mapping.catalog_record_sha256)
            if (fixture.text("corpus_id") == "detekt-syntax-local-shapes-v1") {
                require(fixture.text("severity") == mapping.severity && fixture.text("kind") == mapping.kind && fixture.text("certainty") == mapping.certainty)
                require(mapping.certainty == "intent_dependent")
                require(mapping.severity == if (mapping.default_activation == "advisory") "warning" else "info")
            }
            if (fixture.text("corpus_id") == "detekt-syntax-exception-observability-v1") {
                require(mapping.default_activation == "opt_in" && mapping.severity == "info" && mapping.certainty == "intent_dependent")
                require(fixture.text("severity") == mapping.severity && fixture.text("kind") == mapping.kind && fixture.text("certainty") == mapping.certainty)
            }
        }
        val baseline = fixtures.filter { it.text("corpus_id") == "detekt-syntax-corpus-v1" }
        require(baseline.size == DetektCorpusCohort.BASELINE_AUTHORED_RULES)
        require(baseline.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.BASELINE_AUTHORED_ANALYSES)
        val previousIds = DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet()
        val previous = fixtures.filter { it.text("rule_id") in previousIds }
        require(previous.size == DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_RULES)
        require(previous.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_ANALYSES)
        val priorIds = DetektCorpusCohort.preObservability.flatMap { it.analysesByRule.keys }.toSet()
        val prior = fixtures.filter { it.text("rule_id") in priorIds }
        require(prior.size == DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_RULES)
        require(prior.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_ANALYSES)
        require(prior.count { it.text("default_activation") == "advisory" } == 13)
        require(prior.count { it.text("default_activation") == "opt_in" } == 6)
        val preLocalIds = DetektCorpusCohort.preLocalShapes.flatMap { it.analysesByRule.keys }.toSet()
        val preLocal = fixtures.filter { it.text("rule_id") in preLocalIds }
        require(preLocal.size == DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_RULES)
        require(preLocal.sumOf { 6 + it.getValue("controls").jsonArray.size } == DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_ANALYSES)
        require(preLocal.count { it.text("default_activation") == "advisory" } == 13)
        require(preLocal.count { it.text("default_activation") == "opt_in" } == 9)
        // Fixed compiled scopes, not bounds/cohort selection inferred from the data being validated.
        validateNamespace(EXPANSION, listOf(DetektCorpusCohort.PERFORMANCE_SHAPES, DetektCorpusCohort.PUBLIC_CONTRACT_POLICY),
            DetektCorpusCohort.baseline, DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_RULES, DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_ANALYSES)
        validateNamespace(PROTOCOL, listOf(DetektCorpusCohort.PROTOCOL_AND_CATCH_BOUNDARIES),
            DetektCorpusCohort.preProtocol, DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_RULES, DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_ANALYSES)
        validateNamespace(OBSERVABILITY, listOf(DetektCorpusCohort.EXCEPTION_OBSERVABILITY_POLICY),
            DetektCorpusCohort.preObservability, DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_RULES, DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_ANALYSES)
        validateNamespace(LOCAL_SHAPES, DetektCorpusCohort.localShapeCohorts,
            DetektCorpusCohort.preLocalShapes, DetektCorpusCohort.FULL_AUTHORED_RULES, DetektCorpusCohort.FULL_AUTHORED_ANALYSES)
        validateNamedGaps(PROTOCOL)
        validateNamedGaps(OBSERVABILITY)
        validateNamedGaps(LOCAL_SHAPES)
        validateBreadthInventory()
    }

    /** The driver invokes this at START and with every later source/code witness verification. */
    fun verify() {
        verifyMembership(repository)
        for ((path, bytes) in captured) require(bytes.contentEquals(readPinned(repository, path))) {
            "Authored corpus data changed after loading: $path"
        }
    }

    fun matrix(cohort: DetektCorpusCohort): JsonObject {
        require(cohort.expansion || cohort.protocol || cohort.observability || cohort.localShapes)
        return parsed.getValue("${requireNotNull(cohort.namespace)}/cohorts/${cohort.name}.json")
    }

    private fun validateNamespace(root: String, selectedCohorts: List<DetektCorpusCohort>,
        preservedCohorts: List<DetektCorpusCohort>, boundRuleCount: Int, boundAnalysisCount: Int) {
        require(root in setOf(EXPANSION, PROTOCOL, OBSERVABILITY, LOCAL_SHAPES) && selectedCohorts.all { it.namespace == root })
        val corpusId = when (root) {
            EXPANSION -> "detekt-syntax-expansion-v1"
            PROTOCOL -> "detekt-syntax-protocol-v1"
            OBSERVABILITY -> "detekt-syntax-exception-observability-v1"
            LOCAL_SHAPES -> "detekt-syntax-local-shapes-v1"
            else -> error("Unknown compiled corpus namespace")
        }
        val registry = parsed.getValue("$root/cohorts.json")
        require(registry.text("corpus_id") == corpusId)
        require(registry.text("state") == "CONNECTED_SOURCE_CANDIDATE_UNCOMPILED_UNEXECUTED_NOT_ADMITTED")
        require(registry["default_cohort"] == JsonNull && !registry.flag("multi_cohort_or_all_selector_allowed"))
        require(registry.integer("actual_tests_executed") == 0 && registry.integer("actual_provider_invocations") == 0)
        require(registry.integer("historical_stage_transfer") == 0)
        require(registry.getValue("allowed_cohort_names").jsonArray.map { it.jsonPrimitive.content } == selectedCohorts.map { it.name })
        val scope = parsed.getValue("$root/scope.json")
        require(registry.text("scope_reference") == "$root/scope.json")
        require(registry.text("scope_sha256") == hash(captured.getValue("$root/scope.json")))
        require(registry.text("integration_contract_path") == "$root/integration-contract.json")
        require(registry.text("integration_contract_sha256") == hash(captured.getValue("$root/integration-contract.json")))
        val currentScope = scope.getValue("current_scope").jsonObject
        require(currentScope.integer("ids") == 1663 && currentScope.integer("contexts") == 8 && currentScope.integer("new_contexts") == 0)
        require(currentScope.integer("historical_stage_transfer") == 0 && currentScope["scope_exclusions"] == JsonArray(emptyList()))
        require(currentScope["changed_catalog_or_activation_files"] == JsonArray(emptyList()))
        val combined = scope.getValue("combined_proposed_authoring_scope").jsonObject
        require(combined.integer("rule_count") == boundRuleCount)
        require(combined.integer("authored_analyses") == boundAnalysisCount)
        require(!combined.flag("scope_reduction") && !combined.flag("aggregate_execution_or_sidecar_created"))
        require(combined.integer("baseline_execution_stage_transfer") == 0)
        val included = DetektCatalog.mappings.filter { it.exact && it.depth == "syntax" }.map { it.rule_id }.sorted()
        require(included.size == 156 && scope.getValue("existing_detekt_mapping").jsonObject
            .getValue("included156_rule_ids").jsonArray.map { it.jsonPrimitive.content } == included)
        val preserved = scope.getValue("preserved_baseline").jsonObject
        val preservedRules = preservedCohorts.flatMap { it.analysesByRule.keys }
        require(preserved.integer("rule_count") == preservedRules.size)
        require(preserved.integer("authored_analyses") == preservedCohorts.sumOf { it.analysesByRule.values.sum() })
        require(preserved.integer("execution_stage_transfer") == 0)
        val oldFixtures = preserved.getValue("fixture_files").jsonArray
        require(oldFixtures.size == preservedRules.size && oldFixtures.map { it.jsonObject.text("rule_id") }.toSet() == preservedRules.toSet())
        for (value in oldFixtures) {
            val row = value.jsonObject
            val path = row.text("path")
            val fixture = parsed.getValue(path)
            require(row.text("sha256") == hash(captured.getValue(path)) && row.integer("bytes") == captured.getValue(path).size)
            require(row.text("rule_id") == fixture.text("rule_id"))
            require(row.integer("analyses") == 6 + fixture.getValue("controls").jsonArray.size)
            require(row.getValue("control_ids").jsonArray == JsonArray(fixture.getValue("controls").jsonArray.map { it.jsonObject.getValue("id") }))
        }
        val oldPlans = preserved.getValue("cohorts").jsonArray
        require(oldPlans.size == preservedCohorts.size)
        for (old in preservedCohorts) {
            val row = oldPlans.single { it.jsonObject.text("cohort_id") == old.name }.jsonObject
            require(row.text("plan_identity_sha256") == hash(old.planIdentity.toByteArray()))
            require(row.integer("planned_invocations") == old.plannedInvocations)
            require(row.getValue("analyses_by_rule").jsonObject.mapValues { it.value.jsonPrimitive.int } == old.analysesByRule)
            require(!row.flag("modified_by_this_packet"))
        }
        if (root in setOf(PROTOCOL, OBSERVABILITY, LOCAL_SHAPES)) {
            val oldFiles = preserved.getValue("preserved_expansion_namespace_files").jsonArray
            require(oldFiles.map { it.jsonObject.text("path") }.toSet() == DetektCorpusExpansionPins.files.keys.filter { it.startsWith("$EXPANSION/") }.toSet())
            require(oldFiles.size == 12)
            for (value in oldFiles) {
                val row = value.jsonObject
                val bytes = captured.getValue(row.text("path"))
                require(row.text("sha256") == hash(bytes) && row.integer("bytes") == bytes.size)
            }
        }
        if (root in setOf(OBSERVABILITY, LOCAL_SHAPES)) {
            val oldFiles = preserved.getValue("preserved_protocol_namespace_files").jsonArray
            require(oldFiles.map { it.jsonObject.text("path") }.toSet() == DetektCorpusProtocolPins.files.keys)
            require(oldFiles.size == 8)
            for (value in oldFiles) {
                val row = value.jsonObject
                val bytes = captured.getValue(row.text("path"))
                require(row.text("sha256") == hash(bytes) && row.integer("bytes") == bytes.size)
            }
        }
        if (root == LOCAL_SHAPES) {
            val oldFiles = preserved.getValue("preserved_observability_namespace_files").jsonArray
            require(oldFiles.map { it.jsonObject.text("path") }.toSet() == DetektCorpusObservabilityPins.files.keys)
            require(oldFiles.size == 8)
            for (value in oldFiles) {
                val row = value.jsonObject
                val bytes = captured.getValue(row.text("path"))
                require(row.text("sha256") == hash(bytes) && row.integer("bytes") == bytes.size)
            }
        }
        val plans = registry.getValue("cohorts").jsonArray
        require(plans.size == selectedCohorts.size)
        for ((cohort, value) in selectedCohorts.zip(plans)) {
            val plan = value.jsonObject
            require(plan.text("cohort_id") == cohort.name && !plan.flag("execution_admitted"))
            require(plan.text("runtime_registration_state") == "connected_source_candidate_uncompiled_unexecuted")
            require(plan.flag("requires_explicit_owner_opt_in") == cohort.requiresExplicitOwnerOptIn)
            require(plan.text("proposed_sidecar") == "evidence/verified-jvm-${cohort.evidenceStem}-${cohort.name}-native-preview1.json")
            val matrixPath = "$root/cohorts/${cohort.name}.json"
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
            if (cohort.protocol || cohort.observability || cohort.localShapes) {
                val policyRules = when {
                    cohort.protocol -> JsonArray(listOf(JsonPrimitive("ktc.kotlin.detekt.exceptions.too-generic-exception-caught")))
                    cohort.observability -> JsonArray(listOf(
                        JsonPrimitive("ktc.kotlin.detekt.exceptions.rethrow-caught-exception"),
                        JsonPrimitive("ktc.kotlin.detekt.exceptions.print-stack-trace"),
                        JsonPrimitive("ktc.kotlin.detekt.exceptions.throwing-exceptions-without-message-or-cause"),
                    ))
                    else -> JsonArray(cohort.analysesByRule.keys.filter {
                        DetektCatalog.byId.getValue(it).default_activation == "opt_in"
                    }.map { JsonPrimitive(it) })
                }
                require(identity.flag("requires_explicit_owner_opt_in"))
                require(identity["opt_in_rule_ids"] == policyRules && plan["opt_in_rule_ids"] == policyRules)
                require(matrix["explicit_opt_in_policy_rule_ids"] == policyRules)
            }
            require(matrix.text("state") == "AUTHORED_NOT_RUN" && matrix.flag("candidate_only"))
            val analyses = matrix.getValue("analyses").jsonArray
            val analysisCount = cohort.analysesByRule.values.sum()
            require(analyses.size == analysisCount)
            var analysisOffset = 0
            selected.forEach { item ->
                val selection = item.jsonObject
                val path = selection.text("fixture_path")
                require(path.startsWith("$root/fixtures/") && selection.text("fixture_sha256") == hash(captured.getValue(path)))
                val fixture = parsed.getValue(path)
                require(fixture.text("corpus_id") == corpusId)
                require(selection.text("rule_id") == fixture.text("rule_id") && selection.text("upstream_id") == fixture.text("upstream_id"))
                val ruleAnalysisCount = cohort.analysesByRule.getValue(fixture.text("rule_id"))
                require(selection.text("default_activation") == fixture.text("default_activation") && selection.integer("analyses") == ruleAnalysisCount)
                val controls = fixture.getValue("controls").jsonArray
                require(controls.map { it.jsonObject.text("id") } == cohort.independentControlIds.getValue(fixture.text("rule_id")))
                require(6 + controls.size == ruleAnalysisCount)
                repeat(ruleAnalysisCount) { caseIndex -> validateAnalysis(analyses[analysisOffset + caseIndex].jsonObject, fixture, path, caseIndex) }
                analysisOffset += ruleAnalysisCount
            }
            require(analysisOffset == analysisCount)
            val schedule = matrix.getValue("invocation_schedule").jsonArray
            val expected = requireNotNull(cohort.expectedSchedule)
            require(schedule.size == expected.size && expected.size == cohort.plannedInvocations)
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
            require(analysisIndex == analysisCount)
            val counts = matrix.getValue("counts").jsonObject
            require(plan["counts"] == counts)
            require(counts.integer("authored_analyses") == analysisCount && counts.integer("all_calls") == cohort.plannedInvocations)
            require(counts.integer("native_launch_ceiling") == cohort.nativeLaunchCeiling)
            require(counts.integer("expected_runtime_callbacks") == cohort.expectedRuntimeCallbacks)
            require(counts.integer("planned_progress_events") == 2 + 3 * cohort.plannedInvocations)
            require(counts.integer("independent_controls") == analysisCount - 6 * cohort.analysesByRule.size && counts.integer("actual_executions") == 0 && counts["actual_findings"] == JsonNull)
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

    private fun validateBreadthInventory() {
        val scope = parsed.getValue("$LOCAL_SHAPES/scope.json")
        val reference = scope.getValue("breadth_inventory").jsonObject
        val path = "$LOCAL_SHAPES/breadth-inventory.json"
        require(reference.text("path") == path && reference.text("sha256") == hash(captured.getValue(path)))
        val breadth = parsed.getValue(path)
        require(breadth.text("state") == "SOURCE_REVIEWED_SELECTION_NOT_EXECUTED" && breadth.flag("candidate_only"))
        require(breadth.flag("selection_is_not_execution_authority") && !breadth.flag("qualification_claimed"))
        require(breadth.integer("actual_new_analyses") == 0)
        val full = breadth.getValue("full_scope").jsonObject
        require(full.integer("ids") == 1663 && full.integer("contexts") == 8 && full.integer("cells") == 13304)
        require(full.integer("new_contexts") == 0 && full.integer("historical_stage_transfer") == 0)
        require(full["scope_exclusions"] == JsonArray(emptyList()))
        val counts = breadth.getValue("counts").jsonObject
        require(counts.integer("mapping_rows") == 276 && counts.integer("exact_syntax_rows") == 156)
        require(counts.integer("preserved_authored_rules") == 22 && counts.integer("new_source_authored_rules") == 9)
        require(counts.integer("deferred_exact_syntax_rules") == 125 && counts.integer("outside_exact_light_scope") == 120)
        val oldIds = DetektCorpusCohort.preLocalShapes.flatMap { it.analysesByRule.keys }.toSet()
        val newIds = DetektCorpusLocalShapesPins.controlIds.keys
        val syntax = DetektCatalog.mappings.filter { it.exact && it.depth == "syntax" }.map { it.rule_id }.sorted()
        val other = DetektCatalog.mappings.filterNot { it.exact && it.depth == "syntax" }.map { it.rule_id }.sorted()
        val rows = breadth.getValue("exact_syntax_rules").jsonArray.map { it.jsonObject }
        val otherRows = breadth.getValue("other_mapping_rows").jsonArray.map { it.jsonObject }
        require(rows.map { it.text("rule_id") } == syntax && otherRows.map { it.text("rule_id") } == other)
        for (row in rows + otherRows) {
            val mapping = DetektCatalog.byId.getValue(row.text("rule_id"))
            require(row.text("upstream_id") == mapping.upstream_id && row.text("relation") == mapping.relation)
            require(row.text("depth") == mapping.depth && row.text("default_activation") == mapping.default_activation)
            require(row.text("catalog_record_sha256") == mapping.catalog_record_sha256)
            require(!row.flag("scope_exclusion") && row.integer("execution_or_stage_credit") == 0)
        }
        for (row in rows) {
            val id = row.text("rule_id")
            val expected = when (id) {
                in oldIds -> "preserved_authored"
                in newIds -> "new_source_authored"
                else -> "deferred_no_new_execution"
            }
            require(row.text("disposition") == expected && row.text("rationale").isNotBlank())
            if (id in newIds) require(row.integer("analyses") == DetektCorpusCohort.localShapeCohorts
                .single { id in it.analysesByRule }.analysesByRule.getValue(id))
        }
        require(otherRows.all { it.text("disposition") == "retained_outside_this_exact_light_scope" })
        val categories = rows.filter { it.text("disposition") == "deferred_no_new_execution" }
            .groupingBy { it.text("reason_category") }.eachCount()
        require(breadth.getValue("deferred_category_counts").jsonObject.mapValues { it.value.jsonPrimitive.int } == categories)
    }

    private fun validateNamedGaps(root: String) {
        require(root in setOf(PROTOCOL, OBSERVABILITY, LOCAL_SHAPES))
        val scope = parsed.getValue("$root/scope.json")
        val expected = linkedMapOf(
            "ktc.coroutines.swallowed-cancellation" to ("requires_refinement" to "graph"),
            "ktc.coroutines.result-swallowing-cancellation" to ("requires_refinement" to "graph"),
            "ktc.coroutines.suspending-finally-cleanup" to ("requires_refinement" to "graph"),
            "ktc.resources.owned_autocloseable_exit" to ("requires_refinement" to "semantic"),
            "ktc.kotlin.detekt.potential-bugs.missing-use-call" to ("exact" to "semantic"),
        )
        val gaps = scope.getValue("named_coroutine_and_resource_gaps").jsonArray
        require(gaps.map { it.jsonObject.text("rule_id") } == expected.keys.toList())
        for (value in gaps) {
            val row = value.jsonObject
            val rule = row.text("rule_id")
            val mapping = DetektCatalog.byId.getValue(rule)
            require(rule !in DetektCorpusCohort.authoredRuleIds)
            require((row.text("relation") to row.text("depth")) == expected.getValue(rule))
            require(row.text("relation") == mapping.relation && row.text("depth") == mapping.depth)
            require(row.text("upstream_id") == mapping.upstream_id && row.text("catalog_record_sha256") == mapping.catalog_record_sha256)
            require(row.text("default_activation") == mapping.default_activation)
            require(!row.flag("delivered_by_this_cohort") && row.text("state") == "unavailable_in_this_light_context")
            require(listOf("attempted", "implemented", "tested", "qualified").none { row.flag(it) })
            require(row.flag("no_stage_transfer_from_broad_catch_or_other_syntax_rules"))
            if (root in setOf(OBSERVABILITY, LOCAL_SHAPES)) require(row.flag("no_stage_transfer_from_observability_or_other_syntax_rules"))
            if (root == LOCAL_SHAPES) require(row.flag("no_stage_transfer_from_local_shapes_or_other_syntax_rules"))
        }
        val next = scope.getValue("next_reusable_capability").jsonObject
        require(next.text("first_exact_rule_id") == "ktc.kotlin.detekt.potential-bugs.missing-use-call")
        require(!next.flag("semantic_execution_claimed") && !next.flag("qualification_claimed"))
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
        const val PROTOCOL = DetektCorpusProtocolPins.ROOT
        const val OBSERVABILITY = DetektCorpusObservabilityPins.ROOT
        const val LOCAL_SHAPES = DetektCorpusLocalShapesPins.ROOT
        val filePins: Map<String, String> = Collections.unmodifiableMap(DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files + DetektCorpusObservabilityPins.files + DetektCorpusLocalShapesPins.files).also {
            require(DetektCorpusExpansionPins.files.keys.intersect(DetektCorpusProtocolPins.files.keys).isEmpty())
            require((DetektCorpusExpansionPins.files.keys + DetektCorpusProtocolPins.files.keys).intersect(DetektCorpusObservabilityPins.files.keys).isEmpty())
            require((DetektCorpusExpansionPins.files.keys + DetektCorpusProtocolPins.files.keys + DetektCorpusObservabilityPins.files.keys)
                .intersect(DetektCorpusLocalShapesPins.files.keys).isEmpty())
            require(DetektCorpusExpansionPins.files.size == 22 && DetektCorpusProtocolPins.files.size == 8 && DetektCorpusObservabilityPins.files.size == 8)
            require(DetektCorpusLocalShapesPins.files.size == 17 && it.size == 55)
        }

        fun load(repository: Path): DetektCorpusInventory {
            val root = repository.toAbsolutePath().normalize()
            verifyMembership(root)
            val bytes = filePins.keys.associateWith { readPinned(root, it) }
            return DetektCorpusInventory(root, bytes).also { it.verify() }
        }

        private fun verifyMembership(repository: Path) {
            for (root in listOf(BASELINE, EXPANSION, PROTOCOL, OBSERVABILITY, LOCAL_SHAPES)) {
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
                        root != BASELINE || path.toString().endsWith(".json")
                    }
                }.map { repository.relativize(it).toString().replace('\\', '/') }.sorted()
                require(actual == filePins.keys.filter { it.startsWith("$root/") }.sorted()) {
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
            require(bytes.size in 1..1_048_576 && hash(bytes) == filePins.getValue(relative)) {
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
