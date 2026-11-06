package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Inert source/data and synthetic budget controls, never a provider, engine, process or model run. */
class DetektCorpusProtocolTest {
    @TempDir lateinit var temporary: Path
    private val repository: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val root = DetektCorpusInventory.PROTOCOL
    private val cohort = DetektCorpusCohort.PROTOCOL_AND_CATCH_BOUNDARIES
    private var copies = 0
    private fun load() = DetektCorpusInventory.load(repository)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun read(path: String) = Json.parseToJsonElement(Files.readString(repository.resolve(path))).jsonObject
    private fun hash(bytes: ByteArray) = Digests.sha256(bytes).removePrefix("sha256:")
    private fun fixture(inventory: DetektCorpusInventory, slug: String) = inventory.fixtures.single { it.text("rule_id").endsWith(".$slug") }
    private fun controls(item: JsonObject) = item.getValue("controls").jsonArray.map { it.jsonObject }
    private fun sources(item: JsonObject) = item.getValue("sources").jsonObject
    private fun code(item: JsonObject) = sources(item).getValue("Case.kt").jsonPrimitive.content
    private fun expected(item: JsonObject) = item.getValue("expected").jsonArray.map { it.jsonObject }
    private fun spans(item: JsonObject) = expected(item).map { it.text("span") }
    private fun protocolFixtures(inventory: DetektCorpusInventory) = inventory.fixtures.filter { it.text("corpus_id") == "detekt-syntax-protocol-v1" }

    /** Copy thirty-eight bounded authored-data files only, not installed tools, caches or evidence. */
    private fun copyData(): Path {
        val copy = Files.createDirectory(temporary.resolve("protocol-${++copies}"))
        for (relative in DetektCorpusInventory.filePins.keys) {
            val path = copy.resolve(relative)
            Files.createDirectories(path.parent)
            Files.copy(repository.resolve(relative), path)
        }
        return copy
    }

    @Test fun `nineteen exact syntax rules retain every prior sixteen fixture byte and activation`() {
        val inventory = load()
        inventory.verify()
        val preservedIds = DetektCorpusCohort.preObservability.flatMap { it.analysesByRule.keys }.toSet()
        val preserved = inventory.fixtures.filter { it.text("rule_id") in preservedIds }
        assertEquals(19, preserved.size)
        assertEquals(172, preserved.sumOf { 6 + controls(it).size })
        assertEquals(13, preserved.count { it.text("default_activation") == "advisory" })
        assertEquals(6, preserved.count { it.text("default_activation") == "opt_in" })
        assertEquals(DetektCorpusCohort.FULL_AUTHORED_RULES, inventory.fixtures.size)
        assertEquals(DetektCorpusCohort.FULL_AUTHORED_ANALYSES, inventory.fixtures.sumOf { 6 + controls(it).size })
        val priorIds = DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet()
        val prior = inventory.fixtures.filter { it.text("rule_id") in priorIds }
        assertEquals(16, prior.size)
        assertEquals(145, prior.sumOf { 6 + controls(it).size })
        assertEquals(DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet(), prior.map { it.text("rule_id") }.toSet())
        assertEquals(22, DetektCorpusExpansionPins.files.size)
        for ((path, digest) in DetektCorpusExpansionPins.files) assertEquals(digest, hash(Files.readAllBytes(repository.resolve(path))))
        val added = protocolFixtures(inventory)
        assertEquals(cohort.analysesByRule.keys, added.map { it.text("rule_id") }.toSet())
        assertEquals(3, added.size)
        for (item in added) {
            val mapping = DetektCatalog.byId.getValue(item.text("rule_id"))
            assertTrue(mapping.exact)
            assertEquals("syntax", mapping.depth)
            assertEquals(mapping.catalog_record_sha256, item.text("catalog_record_sha256"))
            assertEquals(mapping.default_activation, item.text("default_activation"))
            assertEquals("unexecuted", item.text("authoring_state"))
            assertFalse(item.getValue("light_eligibility").jsonObject.flag("requires_analysis_api"))
        }
    }

    @Test fun `mixed cohort explicitly opts into style policy and preserves all six old identities`() {
        assertSame(cohort, DetektCorpusCohort.selected("protocol-and-catch-boundaries-v1"))
        assertTrue(cohort.protocol)
        assertFalse(cohort.expansion)
        assertFalse(cohort.observability)
        assertEquals("syntax-protocol", cohort.evidenceStem)
        assertEquals(root, cohort.namespace)
        assertTrue(cohort.requiresExplicitOwnerOptIn)
        assertEquals(listOf(9, 9, 9), cohort.analysesByRule.values.toList())
        assertEquals(listOf(27, 1, 6, 3), cohort.invocationsByKind.values.toList())
        assertEquals(listOf(37, 28, 31), listOf(cohort.plannedInvocations, cohort.nativeLaunchCeiling, cohort.expectedRuntimeCallbacks))
        val selected = cohort.analysesByRule.keys.map { DetektCatalog.byId.getValue(it) }
        assertEquals(listOf("advisory", "advisory", "opt_in"), selected.map { it.default_activation })
        assertEquals(listOf("probable_defect", "probable_defect", "style"), selected.map { it.kind })
        assertEquals("info", selected.last().severity)
        assertEquals("intent_dependent", selected.last().certainty)
        val registry = load().protocolRegistry
        val plan = registry.getValue("cohorts").jsonArray.single().jsonObject
        assertTrue(plan.flag("requires_explicit_owner_opt_in"))
        assertEquals(cohort.planIdentity, DetektCorpusInventory.canonical(plan.getValue("plan_identity")))
        assertEquals("c832b3938eb553dad22bf483db5780dde8571b86b83679f20064c95fdf2356ad", hash(cohort.planIdentity.toByteArray()))
        assertEquals(listOf(
            "1a04a745f3a90b093f07725c33f7aa5e4f68dcb4e08300e7498eabb23046469f",
            "855ccc2174e1497b74560aa5041923b870dbb289c68728eeffaf58140eb58890",
            "c6e6311078fe7d849755351e936326f00db5c508482f9830ac2367268a41c0c6",
            "83266a809d5e1e5ec7591a36882f7435ff7f108ea28a3426b418ce7d0f9ee421",
            "b9c1f5300aa5eb0fa12ba772bd42f5c5b49c1ca96c95a62b3191b15698b375dd",
            "75ec7045f1c36c6a01ea9e479acf8672aca29a62b964cc35972a84f606523515",
        ), DetektCorpusCohort.preProtocol.map { hash(it.planIdentity.toByteArray()) })
    }

    @Test fun `protocol matrix is exactly thirty seven ordered calls with enabled negative and option cases`() {
        val matrix = load().matrix(cohort)
        val analyses = matrix.getValue("analyses").jsonArray.map { it.jsonObject }
        val calls = matrix.getValue("invocation_schedule").jsonArray.map { it.jsonObject }
        assertEquals(27, analyses.size)
        assertEquals(37, calls.size)
        assertEquals(cohort.expectedSchedule, calls.map { row -> CorpusInvocationSpec(
            CorpusInvocationKind.entries.single { it.id == row.text("kind") }, row.text("rule_id"), row.text("case_id")) })
        assertEquals((1..37).toList(), calls.map { it.number("ordinal") })
        assertEquals(28, calls.count { it.flag("native_launch_required") })
        assertEquals(31, calls.count { it.flag("runtime_callback_required") })
        assertEquals(listOf("common", "native", "common", "native", "common", "native"),
            calls.filter { it.text("kind") == "unsupported_target_abstention" }.map { it.text("request_target") })
        for (row in analyses) {
            val disabled = row.text("case_id") == "configuration"
            assertEquals(disabled, row.flag("rule_disabled_for_this_configuration_control"))
            assertEquals(!disabled, row.flag("require_active_upstream_descriptor"))
            assertEquals(!disabled, row.flag("selected_sarif_descriptor_enabled_required"))
            assertTrue(row.flag("require_actual_native_launch"))
            assertEquals("not_run", row.text("state"))
            assertFalse(row.flag("attempted"))
            assertEquals(JsonNull, row["observed_findings"])
            assertEquals(DetektCatalog.byId.getValue(row.text("rule_id")).sarifId, row.text("selected_sarif_rule_id"))
        }
        assertEquals(6, analyses.count { it.text("case_id") in setOf("negative", "regression") && it.number("authored_expected_count") == 0 })
        val counts = matrix.getValue("counts").jsonObject
        assertEquals(listOf(15, 12, 18, 17, 21, 113), listOf("authored_alert_bearing_analyses", "authored_zero_finding_analyses",
            "authored_expected_finding_observations", "unique_rule_source_sets", "unique_rule_source_configuration_sets", "planned_progress_events").map { counts.number(it) })
        assertEquals(0, counts.number("actual_executions"))
        assertEquals(JsonNull, counts["actual_findings"])
    }

    @Test fun `every authored range locates exact ASCII source with declaration and parameter spans`() {
        var observations = 0
        for (item in protocolFixtures(load())) {
            val cases = listOf("positive", "negative", "suppression").map { item.getValue(it).jsonObject } + controls(item)
            for (case in cases) {
                for ((_, sourceValue) in sources(case)) {
                    val source = sourceValue.jsonPrimitive.content
                    assertTrue(source.endsWith('\n') && source.all { it.code in 1..127 && it != '\r' })
                }
                for (row in expected(case)) {
                    val source = sources(case).getValue(row.text("path")).jsonPrimitive.content
                    val span = row.text("span")
                    var offset = -span.length
                    repeat(row.number("occurrence") + 1) { offset = source.indexOf(span, offset + span.length); assertTrue(offset >= 0) }
                    fun point(position: Int) = source.substring(0, position).let { it.count { c -> c == '\n' } + 1 to it.substringAfterLast('\n').length }
                    assertEquals(row.number("line") to row.number("column"), point(offset))
                    assertEquals(row.number("end_line") to row.number("end_column"), point(offset + span.length))
                    ++observations
                }
            }
        }
        assertEquals(14, observations) // Source ranges, not observed detector results.
    }

    @Test fun `iterator exhaustion keeps delegated and qualified false positives and dormant throw miss`() {
        val item = fixture(load(), "iterator-not-throwing-no-such-element-exception")
        val positive = item.getValue("positive").jsonObject
        assertEquals(listOf("ExhaustedSentinel"), spans(positive))
        assertTrue(code(positive).contains("if (!hasNext()) return 0"))
        assertTrue(code(positive).contains("override fun hasNext(): Boolean = index < values.size"))
        assertTrue(code(item.getValue("negative").jsonObject).contains("throw NoSuchElementException(\"No elements\")"))
        val controls = controls(item)
        assertEquals(listOf("anticipated_upstream_false_positive", "anticipated_upstream_false_negative", "anticipated_upstream_false_positive"), controls.map { it.text("assessment") })
        assertTrue(code(controls[0]).contains("private val delegate = values.iterator()"))
        assertTrue(code(controls[0]).contains("override fun next(): Int = delegate.next()"))
        assertEquals(listOf("DelegatingIterator"), spans(controls[0]))
        assertTrue(code(controls[1]).contains("fun unusedExhaustion(): Nothing = throw NoSuchElementException"))
        assertTrue(code(controls[1]).contains("if (!hasNext()) return 0"))
        assertEquals(1, Regex("unusedExhaustion").findAll(code(controls[1])).count())
        assertTrue(expected(controls[1]).isEmpty())
        assertTrue(code(controls[2]).contains("throw java.util.NoSuchElementException"))
        assertEquals(listOf("QualifiedExhaustionGuard"), spans(controls[2]))
    }

    @Test fun `equals positives are legal overloads and typed control identifies the second declaration only`() {
        val item = fixture(load(), "wrong-equals-type-parameter")
        val positive = item.getValue("positive").jsonObject
        assertTrue(code(positive).contains("fun equals(other: DomainKey): Boolean"))
        assertFalse(code(positive).contains("override fun equals"))
        assertEquals(4 to 8, expected(positive).single().let { it.number("line") to it.number("column") })
        val negative = code(item.getValue("negative").jsonObject)
        assertTrue(negative.contains("override fun equals(other: kotlin.Any?)"))
        assertTrue(negative.contains("override fun hashCode()"))
        val controls = controls(item)
        assertTrue(code(controls[0]).contains("override fun equals(other: Any?)"))
        assertTrue(code(controls[0]).contains("fun equals(other: ComparedKey)"))
        assertFalse(code(controls[0]).contains("override fun equals(other: ComparedKey)"))
        val typed = expected(controls[0]).single()
        assertEquals("equals", typed.text("span"))
        assertEquals(1, typed.number("occurrence"))
        assertEquals(6 to 8, typed.number("line") to typed.number("column"))
        assertTrue(code(controls[1]).contains("typealias AnyValue = kotlin.Any?"))
        assertTrue(code(controls[1]).contains("override fun equals(other: AnyValue)"))
        assertEquals("anticipated_upstream_false_positive", controls[1].text("assessment"))
        assertEquals(6 to 17, expected(controls[1]).single().let { it.number("line") to it.number("column") })
        assertTrue(code(controls[2]).contains("interface StringMatcher"))
        assertTrue(expected(controls[2]).isEmpty())
        assertEquals("predicate_boundary", controls[2].text("assessment"))
    }

    @Test fun `broad catch remains opt in even with cancellation rethrow and partial option contrast`() {
        val inventory = load()
        val item = fixture(inventory, "too-generic-exception-caught")
        assertEquals("opt_in", item.text("default_activation"))
        val positive = item.getValue("positive").jsonObject
        assertEquals(listOf("failure: Exception", "failure: Throwable"), spans(positive))
        val controls = controls(item)
        assertTrue(code(controls[0]).contains("if (failure is java.util.concurrent.CancellationException) throw failure"))
        assertEquals("opt_in_policy_limit", controls[0].text("assessment"))
        assertEquals(listOf("failure: Exception"), spans(controls[0]))
        assertFalse(code(controls[0]).contains("suspend "))
        assertTrue(code(controls[1]).contains("catch (failure: java.lang.Exception)"))
        assertEquals("anticipated_upstream_false_negative", controls[1].text("assessment"))
        assertTrue(expected(controls[1]).isEmpty())
        assertEquals(sources(positive), sources(controls[2]))
        assertEquals(buildJsonObject { put("exceptionNames", JsonArray(listOf(JsonPrimitive("Throwable")))) }, controls[2]["options"])
        assertEquals(listOf(expected(positive)[1]), expected(controls[2]))
        val configured = inventory.matrix(cohort).getValue("analyses").jsonArray.map { it.jsonObject }
            .single { it.text("case_id") == "configured-throwable-only-keeps-one-active-finding" }
        assertTrue(configured.flag("require_active_upstream_descriptor"))
        assertFalse(configured.flag("rule_disabled_for_this_configuration_control"))
        assertEquals(1, configured.number("authored_expected_count"))
        assertEquals(JsonNull, configured["expected_override"])
        assertTrue(item.getValue("limitations").jsonArray.any { it.jsonPrimitive.content.contains("allowedExceptionNameRegex") })
    }

    @Test fun `suppression and child regression preserve unchanged neighbors and standard source replays`() {
        val inventory = load()
        val rows = inventory.matrix(cohort).getValue("analyses").jsonArray.map { it.jsonObject }.groupBy { it.text("rule_id") }
        for (item in protocolFixtures(inventory)) {
            val positive = item.getValue("positive").jsonObject
            val suppression = item.getValue("suppression").jsonObject
            assertEquals(positive["expected"], suppression["expected"])
            assertEquals(code(positive), code(suppression))
            assertEquals(2, sources(suppression).size)
            val copy = sources(suppression).getValue("SuppressedCase.kt").jsonPrimitive.content
            assertTrue(copy.startsWith("@file:Suppress(\"${item.text("upstream_id").substringAfter(':')}\")\n\n"))
            assertEquals(code(positive), copy.substringAfter("\n\n").replaceFirst("package suppressed.", "package "))
            val cases = rows.getValue(item.text("rule_id"))
            assertEquals(listOf("positive", "negative", "configuration", "version", "suppression", "regression"), cases.take(6).map { it.text("case_id") })
            assertEquals(cases[0]["source_set_sha256"], cases[2]["source_set_sha256"])
            assertEquals(cases[0]["source_set_sha256"], cases[3]["source_set_sha256"])
            assertEquals(cases[1]["source_set_sha256"], cases[5]["source_set_sha256"])
            assertEquals(JsonArray(emptyList()), cases[2]["authored_expected_ranges"])
            assertTrue(cases[2].flag("rule_disabled_for_this_configuration_control"))
            assertEquals(1, cases[5].getValue("source_files").jsonArray.size)
        }
    }

    @Test fun `protocol pins controls combined pins and scheduled plan cannot be mutated`() {
        assertEquals(8, DetektCorpusProtocolPins.files.size)
        assertEquals(30, (DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files).size)
        assertEquals(55, DetektCorpusInventory.filePins.size)
        assertTrue(DetektCorpusExpansionPins.files.keys.intersect(DetektCorpusProtocolPins.files.keys).isEmpty())
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusProtocolPins.files as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusProtocolPins.planIdentities as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusProtocolPins.controlIds as MutableMap<String, List<String>>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusInventory.filePins as MutableMap<String, String>).clear() }
        for (ids in cohort.independentControlIds.values) assertFailsWith<UnsupportedOperationException> { (ids as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (assertNotNull(cohort.expectedSchedule) as MutableList<CorpusInvocationSpec>).clear() }
        assertEquals(9, cohort.independentControlIds.values.sumOf { it.size })
        assertEquals(37, cohort.expectedSchedule?.size)
    }

    @Test fun `altered protocol policy options scope matrix and undecodable bytes fail before parsing`() {
        val registry = "$root/cohorts.json"
        val fixture = "$root/fixtures/too-generic-exception-caught.json"
        val scope = "$root/scope.json"
        val matrix = "$root/cohorts/${cohort.name}.json"
        val inputs = listOf(
            registry to Files.readString(repository.resolve(registry)).replace("\"requires_explicit_owner_opt_in\": true", "\"requires_explicit_owner_opt_in\": false"),
            fixture to Files.readString(repository.resolve(fixture)).replace("\"Throwable\"", "\"Exception\""),
            scope to Files.readString(repository.resolve(scope)).replace("\"authored_analyses\": 172", "\"authored_analyses\": 145"),
            matrix to Files.readString(repository.resolve(matrix)).replaceFirst("\"ordinal\": 1", "\"ordinal\": 2"),
            registry to Files.readString(repository.resolve(registry)).replaceFirst("{", "{\n  \"state\": \"passed\","),
            registry to "not JSON\n",
        )
        for ((relative, changed) in inputs) {
            assertNotEquals(Files.readString(repository.resolve(relative)), changed)
            val copy = copyData()
            Files.writeString(copy.resolve(relative), changed)
            val failure = assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(copy) }
            assertTrue(failure.message.orEmpty().contains("Pinned authored corpus bytes changed"))
        }
    }

    @Test fun `protocol missing extra linked and changed after load data cannot hide or borrow inputs`() {
        for (relative in DetektCorpusProtocolPins.files.keys) {
            val copy = copyData()
            Files.delete(copy.resolve(relative))
            assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(copy) }
        }
        val extra = copyData()
        Files.writeString(extra.resolve("$root/fixtures/extra.json"), "{}\n")
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(extra) }
        val linked = copyData()
        val relative = "$root/fixtures/wrong-equals-type-parameter.json"
        Files.delete(linked.resolve(relative))
        Files.createSymbolicLink(linked.resolve(relative), repository.resolve(relative))
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(linked) }
        val changed = copyData()
        val inventory = DetektCorpusInventory.load(changed)
        Files.writeString(changed.resolve("$root/README.md"), "Changed after load\n")
        assertFailsWith<IllegalArgumentException> { inventory.verify() }
        val nested = copyData()
        Files.createDirectory(nested.resolve("$root/hidden"))
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(nested) }
    }

    @Test fun `named coroutine graph and owned resource semantic gaps remain unavailable with zero transfer`() {
        val scope = read("$root/scope.json")
        val current = scope.getValue("current_scope").jsonObject
        assertEquals(1663, current.number("ids"))
        assertEquals(8, current.number("contexts"))
        assertEquals(0, current.number("new_contexts"))
        assertEquals(0, current.number("historical_stage_transfer"))
        assertEquals(JsonArray(emptyList()), current["scope_exclusions"])
        val gaps = scope.getValue("named_coroutine_and_resource_gaps").jsonArray.map { it.jsonObject }
        assertEquals(listOf("ktc.coroutines.swallowed-cancellation", "ktc.coroutines.result-swallowing-cancellation",
            "ktc.coroutines.suspending-finally-cleanup", "ktc.resources.owned_autocloseable_exit", "ktc.kotlin.detekt.potential-bugs.missing-use-call"), gaps.map { it.text("rule_id") })
        assertEquals(listOf("graph", "graph", "graph", "semantic", "semantic"), gaps.map { it.text("depth") })
        assertEquals(listOf("requires_refinement", "requires_refinement", "requires_refinement", "requires_refinement", "exact"), gaps.map { it.text("relation") })
        for (row in gaps) {
            assertFalse(row.text("rule_id") in DetektCorpusCohort.authoredRuleIds)
            assertFalse(row.flag("delivered_by_this_cohort"))
            assertEquals("unavailable_in_this_light_context", row.text("state"))
            for (key in listOf("attempted", "implemented", "tested", "qualified")) assertFalse(row.flag(key))
            assertTrue(row.flag("no_stage_transfer_from_broad_catch_or_other_syntax_rules"))
        }
        val next = scope.getValue("next_reusable_capability").jsonObject
        assertEquals("ktc.kotlin.detekt.potential-bugs.missing-use-call", next.text("first_exact_rule_id"))
        assertFalse(next.flag("semantic_execution_claimed"))
        assertFalse(next.flag("qualification_claimed"))
        assertTrue(next.text("current_boundary").contains("LIGHT-only"))
    }

    @Test fun `protocol budget refuses fabricated independent control IDs after the checked standard prefix`() {
        val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
            "sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}")
        val references = listOf(buildJsonObject { put("path", "synthetic-control-only.json"); put("sha256", "0".repeat(64)) })
        val settings = DetektCorpusSettings.selected(cohort.name, "37", null, null)
        val budget = DetektCorpusBudget(settings, {}, { 0L })
        val schedule = assertNotNull(cohort.expectedSchedule)
        val firstControl = schedule.indexOfFirst { it.caseId == "delegated-list-iterator-already-throws" }
        assertEquals(10, firstControl)
        for (call in schedule.take(firstControl)) {
            val ticket = budget.reserve(call.kind, call.ruleId, call.caseId)
            when (call.kind) {
                CorpusInvocationKind.ANALYSIS, CorpusInvocationKind.CLI_PROBE -> budget.observeProcess(ticket,
                    ProcessOutcome("executed", exitCode = 0, supervisorIdentity = identity, executionConfirmed = true), identity)
                CorpusInvocationKind.AUTHORIZATION_DENIAL -> budget.observeProcess(ticket,
                    ProcessOutcome("unavailable", listOf("execution_not_authorized")), identity)
                CorpusInvocationKind.ABSTENTION -> budget.observeAbstention(ticket, ProviderResult("detekt", "partial",
                    coverage = listOf(Coverage(call.ruleId, RuleContext(target = call.caseId.removePrefix("unsupported-")), state = "unsupported",
                        reason = "detekt_jvm_provider_does_not_qualify_common_or_native_targets")),
                    reasons = listOf("detekt_jvm_provider_does_not_qualify_common_or_native_targets")), null)
            }
            budget.checked(ticket, references)
        }
        val next = schedule[firstControl]
        for (id in listOf("control-6", "qualified-exhaustion-exception-is-still-correct", "configured-throwable-only-keeps-one-active-finding")) {
            assertFailsWith<IllegalArgumentException> { budget.reserve(CorpusInvocationKind.ANALYSIS, next.ruleId, id) }
            assertEquals(10, budget.summary().number("reserved_invocations"))
        }
        assertEquals(11, budget.reserve(next.kind, next.ruleId, next.caseId).ordinal)
    }
}
