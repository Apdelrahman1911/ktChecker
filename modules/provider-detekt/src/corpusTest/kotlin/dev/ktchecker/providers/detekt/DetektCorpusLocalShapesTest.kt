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

/** Authored source/data and synthetic budget controls only; never invokes an engine, compiler, native process or model. */
class DetektCorpusLocalShapesTest {
    @TempDir lateinit var temporary: Path
    private val repository: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val root = DetektCorpusInventory.LOCAL_SHAPES
    private val plans = DetektCorpusCohort.localShapeCohorts
    private var copies = 0
    private fun load() = DetektCorpusInventory.load(repository)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun read(path: String) = Json.parseToJsonElement(Files.readString(repository.resolve(path))).jsonObject
    private fun hash(bytes: ByteArray) = Digests.sha256(bytes).removePrefix("sha256:")
    private fun fixture(name: String) = load().fixtures.single { it.text("upstream_id") == "style:$name" }
    private fun controls(item: JsonObject) = item.getValue("controls").jsonArray.map { it.jsonObject }
    private fun node(item: JsonObject, name: String) = item.getValue(name).jsonObject
    private fun sources(item: JsonObject) = item.getValue("sources").jsonObject
    private fun code(item: JsonObject) = sources(item).getValue("Case.kt").jsonPrimitive.content
    private fun ranges(item: JsonObject) = item.getValue("expected").jsonArray.map { it.jsonObject }
    private fun spans(item: JsonObject) = ranges(item).map { it.text("span") }
    private fun added(inventory: DetektCorpusInventory) = inventory.fixtures.filter { it.text("corpus_id") == "detekt-syntax-local-shapes-v1" }
    private fun copyData(): Path {
        val copy = Files.createDirectory(temporary.resolve("local-shapes-${++copies}"))
        for (relative in DetektCorpusInventory.filePins.keys) {
            val path = copy.resolve(relative)
            Files.createDirectories(path.parent)
            Files.copy(repository.resolve(relative), path)
        }
        return copy
    }

    @Test fun `current thirty one and 287 retain the exact twenty two and 199 projection`() {
        val inventory = load()
        inventory.verify()
        assertEquals(31, inventory.fixtures.size)
        assertEquals(287, inventory.fixtures.sumOf { 6 + controls(it).size })
        assertEquals(15, inventory.fixtures.count { it.text("default_activation") == "advisory" })
        assertEquals(16, inventory.fixtures.count { it.text("default_activation") == "opt_in" })
        val priorIds = DetektCorpusCohort.preLocalShapes.flatMap { it.analysesByRule.keys }.toSet()
        val prior = inventory.fixtures.filter { it.text("rule_id") in priorIds }
        assertEquals(22, prior.size)
        assertEquals(199, prior.sumOf { 6 + controls(it).size })
        assertEquals(13, prior.count { it.text("default_activation") == "advisory" })
        assertEquals(9, prior.count { it.text("default_activation") == "opt_in" })
        assertEquals(9, added(inventory).size)
        assertEquals(88, added(inventory).sumOf { 6 + controls(it).size })
        assertEquals(34, added(inventory).sumOf { controls(it).size })
        assertEquals(DetektCorpusCohort.authoredRuleIds, inventory.fixtures.map { it.text("rule_id") }.toSet())
    }

    @Test fun `new exact syntax rules keep advisory and opt in taxonomy without qualification`() {
        for (item in added(load())) {
            val mapping = DetektCatalog.byId.getValue(item.text("rule_id"))
            assertTrue(mapping.exact)
            assertEquals("syntax", mapping.depth)
            assertEquals("intent_dependent", mapping.certainty)
            assertEquals(mapping.default_activation, item.text("default_activation"))
            assertEquals(mapping.severity, item.text("severity"))
            assertEquals(mapping.kind, item.text("kind"))
            assertEquals(mapping.certainty, item.text("certainty"))
            assertEquals(mapping.catalog_record_sha256, item.text("catalog_record_sha256"))
            assertEquals("unexecuted", item.text("authoring_state"))
            assertEquals("2.0.0-alpha.6", item.text("provider_version"))
            assertEquals("2.4.10", item.text("embedded_kotlin_version"))
            assertFalse(node(item, "light_eligibility").flag("requires_analysis_api"))
            assertFalse(node(item, "light_eligibility").flag("qualification"))
            assertTrue(node(item, "source_citation").flag("research_only"))
            assertEquals("4f18e9ac263a157229db846ac18d3a71b8861d74d415da3ebd0fa085dee461a6", node(item, "source_citation").text("source_archive_sha256"))
            assertTrue(item.text("selection_rationale").isNotBlank())
        }
        assertEquals(setOf("style:UnusedParameter", "style:UnusedPrivateClass"), added(load())
            .filter { it.text("default_activation") == "advisory" }.map { it.text("upstream_id") }.toSet())
    }

    @Test fun `all thirty eight old bytes and eight plan identities remain exact`() {
        val previous = node(read("$root/scope.json"), "preserved_baseline")
        val oldPins = DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files + DetektCorpusObservabilityPins.files
        assertEquals(38, oldPins.size)
        assertEquals(55, DetektCorpusInventory.filePins.size)
        assertEquals(17, DetektCorpusLocalShapesPins.files.size)
        assertTrue(oldPins.keys.intersect(DetektCorpusLocalShapesPins.files.keys).isEmpty())
        for ((path, digest) in oldPins) assertEquals(digest, hash(Files.readAllBytes(repository.resolve(path))))
        assertEquals(22, previous.getValue("fixture_files").jsonArray.size)
        assertEquals(12, previous.getValue("preserved_expansion_namespace_files").jsonArray.size)
        assertEquals(8, previous.getValue("preserved_protocol_namespace_files").jsonArray.size)
        assertEquals(8, previous.getValue("preserved_observability_namespace_files").jsonArray.size)
        val oldPlans = previous.getValue("cohorts").jsonArray.map { it.jsonObject }
        assertEquals(8, oldPlans.size)
        for ((plan, row) in DetektCorpusCohort.preLocalShapes.zip(oldPlans)) {
            assertEquals(plan.name, row.text("cohort_id"))
            assertEquals(hash(plan.planIdentity.toByteArray()), row.text("plan_identity_sha256"))
            assertFalse(row.flag("modified_by_this_packet"))
        }
        assertEquals(0, previous.number("execution_stage_transfer"))
    }

    @Test fun `exactly three closed plans have costs 39 39 and 40 and acknowledge their own policies`() {
        assertEquals(listOf("local-declaration-use-v1", "guarded-expression-policy-v1", "constant-and-body-policy-v1"), plans.map { it.name })
        assertEquals(listOf(listOf(10, 9, 10), listOf(9, 10, 10), listOf(10, 10, 10)), plans.map { it.analysesByRule.values.toList() })
        assertEquals(listOf(39, 39, 40), plans.map { it.plannedInvocations })
        assertEquals(listOf(30, 30, 31), plans.map { it.nativeLaunchCeiling })
        assertEquals(listOf(33, 33, 34), plans.map { it.expectedRuntimeCallbacks })
        assertEquals(11, DetektCorpusCohort.all.size)
        for (plan in plans) {
            assertSame(plan, DetektCorpusCohort.selected(plan.name))
            assertTrue(plan.localShapes && plan.requiresExplicitOwnerOptIn)
            assertFalse(plan.expansion || plan.protocol || plan.observability)
            assertEquals("syntax-local-shapes", plan.evidenceStem)
            assertEquals(root, plan.namespace)
        }
        for (name in listOf(null, "", "all", "full-thirty-one", "syntax-local-shapes-v1", "local-declaration-use-v1 ",
            "local-declaration-use-v1,guarded-expression-policy-v1", "LOCAL_DECLARATION_USE"))
            assertFailsWith<IllegalArgumentException> { DetektCorpusCohort.selected(name) }
        val registry = load().localShapesRegistry
        assertEquals(JsonNull, registry["default_cohort"])
        assertFalse(registry.flag("multi_cohort_or_all_selector_allowed"))
        for ((plan, value) in plans.zip(registry.getValue("cohorts").jsonArray)) {
            val row = value.jsonObject
            val identity = node(row, "plan_identity")
            val policies = JsonArray(plan.analysesByRule.keys.filter { DetektCatalog.byId.getValue(it).default_activation == "opt_in" }.map { JsonPrimitive(it) })
            assertEquals(policies, row["opt_in_rule_ids"])
            assertEquals(policies, identity["opt_in_rule_ids"])
            assertEquals(policies, load().matrix(plan)["explicit_opt_in_policy_rule_ids"])
            assertTrue(identity.flag("requires_explicit_owner_opt_in"))
            assertEquals(plan.planIdentity, DetektCorpusInventory.canonical(identity))
            assertEquals(row.text("plan_identity_sha256"), hash(plan.planIdentity.toByteArray()))
            assertFalse(row.flag("execution_admitted"))
        }
    }

    @Test fun `heterogeneous case counts still bind exact serial calls and platform abstentions`() {
        val inventory = load()
        for (plan in plans) {
            val matrix = inventory.matrix(plan)
            val analyses = matrix.getValue("analyses").jsonArray.map { it.jsonObject }
            val calls = matrix.getValue("invocation_schedule").jsonArray.map { it.jsonObject }
            assertEquals(plan.analysesByRule.values.sum(), analyses.size)
            assertEquals(plan.expectedSchedule, calls.map { CorpusInvocationSpec(
                CorpusInvocationKind.entries.single { kind -> kind.id == it.text("kind") }, it.text("rule_id"), it.text("case_id")) })
            assertEquals((1..plan.plannedInvocations).toList(), calls.map { it.number("ordinal") })
            assertEquals(plan.nativeLaunchCeiling, calls.count { it.flag("native_launch_required") })
            assertEquals(plan.expectedRuntimeCallbacks, calls.count { it.flag("runtime_callback_required") })
            assertEquals(listOf("common", "native", "common", "native", "common", "native"),
                calls.filter { it.text("kind") == "unsupported_target_abstention" }.map { it.text("request_target") })
            assertEquals(1, calls.count { it.text("kind") == "cli_probe" })
            val counts = node(matrix, "counts")
            assertEquals(2 + 3 * plan.plannedInvocations, counts.number("planned_progress_events"))
            assertEquals(0, counts.number("actual_executions"))
            assertEquals(JsonNull, counts["actual_findings"])
            assertEquals(0, counts.number("natural_project_observations"))
        }
    }

    @Test fun `only disabled lifecycle case disables descriptors and replays never count as new predicates`() {
        for (plan in plans) {
            val analyses = load().matrix(plan).getValue("analyses").jsonArray.map { it.jsonObject }
            for (row in analyses) {
                val disabled = row.text("case_id") == "configuration"
                assertEquals(disabled, row.flag("rule_disabled_for_this_configuration_control"))
                assertEquals(!disabled, row.flag("require_active_upstream_descriptor"))
                assertEquals(!disabled, row.flag("selected_sarif_descriptor_enabled_required"))
                assertEquals(DetektCatalog.byId.getValue(row.text("rule_id")).sarifId, row.text("selected_sarif_rule_id"))
                assertTrue(row.flag("require_actual_native_launch"))
                assertFalse(row.flag("attempted"))
                assertEquals(JsonNull, row["observed_findings"])
            }
            for (rule in plan.analysesByRule.keys) {
                val rows = analyses.filter { it.text("rule_id") == rule }.associateBy { it.text("case_id") }
                val positive = rows.getValue("positive")
                assertEquals(positive["source_set_sha256"], rows.getValue("configuration")["source_set_sha256"])
                assertEquals(positive["source_set_sha256"], rows.getValue("version")["source_set_sha256"])
                assertEquals(rows.getValue("negative")["source_set_sha256"], rows.getValue("regression")["source_set_sha256"])
                assertEquals(0, rows.getValue("configuration").number("authored_expected_count"))
                assertEquals(0, rows.getValue("negative").number("authored_expected_count"))
            }
        }
    }

    @Test fun `thirty two literal ASCII ranges and seventy source entries have exact end exclusive spans`() {
        var rangeCount = 0
        var sourceCount = 0
        for (item in added(load())) for (case in listOf(node(item, "positive"), node(item, "negative"), node(item, "suppression")) + controls(item)) {
            for (source in sources(case).values.map { it.jsonPrimitive.content }) {
                assertTrue(source.endsWith('\n') && source.all { it.code in 1..127 && it != '\r' })
                ++sourceCount
            }
            for (row in ranges(case)) {
                val source = sources(case).getValue(row.text("path")).jsonPrimitive.content
                val span = row.text("span")
                var offset = -span.length
                repeat(row.number("occurrence") + 1) { offset = source.indexOf(span, offset + span.length); assertTrue(offset >= 0) }
                fun point(position: Int) = source.substring(0, position).let { it.count { c -> c == '\n' } + 1 to it.substringAfterLast('\n').length }
                assertEquals(row.number("line") to row.number("column"), point(offset))
                assertEquals(row.number("end_line") to row.number("end_column"), point(offset + span.length))
                ++rangeCount
            }
        }
        assertEquals(32, rangeCount)
        assertEquals(70, sourceCount)
    }

    @Test fun `upstream file suppression retains the exact unsuppressed neighboring source`() {
        for (item in added(load())) {
            val positive = node(item, "positive")
            val suppression = node(item, "suppression")
            assertEquals(sources(positive).getValue("Case.kt"), sources(suppression).getValue("Case.kt"))
            assertEquals(positive["expected"], suppression["expected"])
            assertEquals(setOf("Case.kt", "SuppressedCase.kt"), sources(suppression).keys)
            val rule = item.text("upstream_id").substringAfter(':')
            assertEquals("@file:Suppress(\"$rule\")\n\n" + code(positive).replaceFirst("package ", "package suppressed."),
                sources(suppression).getValue("SuppressedCase.kt").jsonPrimitive.content)
        }
    }

    @Test fun `unused parameters distinguish named labels real regex exemptions and lexical shadow misses`() {
        val item = fixture("UnusedParameter")
        assertEquals(listOf("unusedRetry"), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(0, 1, 1, 0), cases.map { ranges(it).size })
        assertEquals(listOf("stale"), spans(cases[1]))
        assertEquals("reserved.*", node(cases[1], "options").text("allowedNames"))
        assertEquals(listOf("width"), spans(cases[2]))
        assertEquals(2, ranges(cases[2]).single().number("occurrence"))
        assertTrue(code(cases[2]).contains("render(width = 2)"))
        assertTrue(code(cases[3]).contains("fun label(width: Int)"))
        assertEquals("anticipated_upstream_false_negative", cases[3].text("assessment"))
    }

    @Test fun `private class advice preserves type use scoped name collision and reflection counterexample`() {
        val item = fixture("UnusedPrivateClass")
        assertEquals(listOf("private class ObsoleteIndex(val size: Int)"), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(0, 0, 1), cases.map { ranges(it).size })
        assertTrue(code(cases[0]).contains("value: Pending"))
        assertTrue(code(cases[1]).contains("class LocalIndex(val name: String)"))
        assertTrue(code(cases[2]).contains("Class.forName(\"corpus.privateclass.ReflectiveAdapter\")"))
        assertEquals(listOf("private class ReflectiveAdapter"), spans(cases[2]))
        assertEquals("anticipated_upstream_false_positive", cases[2].text("assessment"))
        assertEquals(JsonArray(emptyList()), item["upstream_rule_options"])
    }

    @Test fun `protected state distinguishes final open inherited sealed and deliberate factory contexts`() {
        val item = fixture("ProtectedMemberInFinalClass")
        assertEquals(listOf("protected val suffix: String = \".idx\""), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(0, 0, 0, 1), cases.map { ranges(it).size })
        assertTrue(code(cases[0]).contains("open class ExtensibleSlot"))
        assertTrue(code(cases[1]).contains("protected override fun suffix"))
        assertTrue(code(cases[2]).contains("sealed class ClosedSlots"))
        assertEquals(listOf("protected constructor(val value: String)"), spans(cases[3]))
        assertEquals("opt_in_policy_limit", cases[3].text("assessment"))
    }

    @Test fun `collapsible guards retain distinct outer and inner recovery and braceless miss`() {
        val item = fixture("CollapsibleIfStatements")
        assertTrue(spans(node(item, "positive")).single().startsWith("if (ready) {"))
        assertTrue(code(node(item, "negative")).contains("ready && permitted"))
        val cases = controls(item)
        assertTrue(cases.all { ranges(it).isEmpty() })
        assertTrue(code(cases[0]).contains("waitForReady()"))
        assertTrue(code(cases[1]).contains("send() else deny()"))
        assertTrue(code(cases[2]).contains("if (ready) if (permitted) send()"))
        assertEquals("anticipated_upstream_false_negative", cases[2].text("assessment"))
    }

    @Test fun `safe cast spelling is explicitly not stable getter or safe rewrite proof`() {
        val item = fixture("SafeCast")
        assertEquals(listOf("if (value is String) value else null"), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(1, 0, 0, 1), cases.map { ranges(it).size })
        assertTrue(code(cases[0]).contains("!is String"))
        assertTrue(code(cases[1]).contains("record()\n    value"))
        assertTrue(code(cases[2]).contains("holder.item is String"))
        assertTrue(code(cases[3]).contains("val observed: Any get() = source()"))
        assertEquals(listOf("if (observed is String) observed else null"), spans(cases[3]))
        assertEquals("anticipated_upstream_false_positive", cases[3].text("assessment"))
        assertTrue(cases[3].text("intent").contains("not generally equivalent"))
    }

    @Test fun `binary when options respect grouped alternatives exhaustive arms and one read subject binding`() {
        val cases = controls(fixture("UseIfInsteadOfWhen"))
        assertEquals(listOf(0, 0, 1, 0), cases.map { ranges(it).size })
        assertTrue(code(cases[0]).contains("0, 1 ->"))
        assertFalse(code(cases[1]).contains("else"))
        assertEquals(code(cases[2]), code(cases[3]))
        assertTrue(code(cases[2]).contains("when (val code = read())"))
        assertTrue(node(cases[3], "options").flag("ignoreWhenContainingVariableDeclaration"))
        assertEquals("genuine_upstream_option", cases[3].text("assessment"))
    }

    @Test fun `literal returning functions keep override contract null ineligibility and selective glob policy`() {
        val cases = controls(fixture("FunctionOnlyReturningConstant"))
        assertEquals(listOf(0, 1, 1, 1), cases.map { ranges(it).size })
        assertEquals(code(cases[0]), code(cases[1]))
        assertFalse(node(cases[1], "options").flag("ignoreOverridableFunction"))
        assertEquals(listOf("noCachedToken"), spans(cases[2]))
        assertTrue(code(cases[2]).contains("String? = null"))
        assertEquals("anticipated_upstream_false_positive", cases[2].text("assessment"))
        assertEquals(listOf("manualRetries"), spans(cases[3]))
        assertEquals(JsonArray(listOf(JsonPrimitive("generated*"))), node(cases[3], "options")["excludedFunctions"])
    }

    @Test fun `expression bodies retain actual line wrap option elvis early return and accessor entity`() {
        val item = fixture("ExpressionBodySyntax")
        assertEquals(listOf("return prefix + key"), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(0, 1, 0, 1), cases.map { ranges(it).size })
        assertEquals(code(cases[0]), code(cases[1]))
        assertTrue(node(cases[1], "options").flag("includeLineWrapping"))
        assertEquals(listOf("return prefix +\n        key"), spans(cases[1]))
        assertTrue(code(cases[2]).contains("value ?: return 0"))
        assertEquals(listOf("return key"), spans(cases[3]))
    }

    @Test fun `constant property advice cannot infer legal declared type scope or ABI from initializer`() {
        val item = fixture("MayBeConstant")
        assertEquals(listOf("retryLimit"), spans(node(item, "positive")))
        val cases = controls(item)
        assertEquals(listOf(1, 0, 1, 0), cases.map { ranges(it).size })
        assertTrue(code(cases[0]).contains("optionalLimit: Int? = 3"))
        assertEquals("anticipated_upstream_false_positive", cases[0].text("assessment"))
        assertTrue(code(cases[1]).contains("val localLimit = 3"))
        assertEquals(listOf("totalLimit"), spans(cases[2]))
        assertTrue(code(cases[2]).contains("BASE_LIMIT + 2"))
        assertTrue(code(cases[3]).contains("get() = 3"))
        assertEquals(JsonArray(emptyList()), item["upstream_rule_options"])
    }

    @Test fun `breadth inventory keeps all 156 syntax routes and 120 other mappings without scope reduction`() {
        val breadth = read("$root/breadth-inventory.json")
        val rows = breadth.getValue("exact_syntax_rules").jsonArray.map { it.jsonObject }
        assertEquals(156, rows.size)
        assertEquals(22, rows.count { it.text("disposition") == "preserved_authored" })
        assertEquals(9, rows.count { it.text("disposition") == "new_source_authored" })
        assertEquals(125, rows.count { it.text("disposition") == "deferred_no_new_execution" })
        assertEquals(120, breadth.getValue("other_mapping_rows").jsonArray.size)
        assertEquals(DetektCatalog.mappings.filter { it.exact && it.depth == "syntax" }.map { it.rule_id }.sorted(), rows.map { it.text("rule_id") })
        assertTrue(rows.all { !it.flag("scope_exclusion") && it.number("execution_or_stage_credit") == 0 && it.text("rationale").isNotBlank() })
        val scope = node(breadth, "full_scope")
        assertEquals(listOf(1663, 8, 13304, 0, 0), listOf("ids", "contexts", "cells", "new_contexts", "historical_stage_transfer").map { scope.number(it) })
        assertEquals(JsonArray(emptyList()), scope["scope_exclusions"])
        assertFalse(breadth.flag("qualification_claimed"))
    }

    @Test fun `local syntax advice supplies no semantic coroutine resource or rewrite qualification`() {
        val scope = read("$root/scope.json")
        for (value in scope.getValue("named_coroutine_and_resource_gaps").jsonArray) {
            val row = value.jsonObject
            assertFalse(row.text("rule_id") in DetektCorpusLocalShapesPins.controlIds)
            assertEquals("unavailable_in_this_light_context", row.text("state"))
            for (flag in listOf("attempted", "implemented", "tested", "qualified", "delivered_by_this_cohort")) assertFalse(row.flag(flag))
            assertTrue(row.flag("no_stage_transfer_from_local_shapes_or_other_syntax_rules"))
        }
        val contract = read("$root/integration-contract.json")
        assertFalse(contract.flag("execution_admitted"))
        assertTrue(contract.flag("no_safe_rewrite_or_binding_proof"))
        assertEquals("light", node(contract, "input_contract").text("analysis_mode"))
        assertFalse(node(contract, "input_contract").flag("genuine_imported_model_execution"))
        assertFalse(node(contract, "input_contract").flag("parser_compatibility_qualified"))
    }

    @Test fun `new plan vectors pin maps and control ids cannot mutate any cohort`() {
        assertFailsWith<UnsupportedOperationException> { (plans as MutableList<DetektCorpusCohort>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusCohort.preLocalShapes as MutableList<DetektCorpusCohort>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusLocalShapesPins.files as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusLocalShapesPins.planIdentities as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusLocalShapesPins.controlIds as MutableMap<String, List<String>>).clear() }
        for (ids in DetektCorpusLocalShapesPins.controlIds.values)
            assertFailsWith<UnsupportedOperationException> { (ids as MutableList<String>).clear() }
        for (plan in plans) assertFailsWith<UnsupportedOperationException> {
            (assertNotNull(plan.expectedSchedule) as MutableList<CorpusInvocationSpec>).clear()
        }
    }

    @Test fun `changed range options activation scope schedule and malformed JSON fail before decoding`() {
        val changes = listOf(
            "$root/fixtures/safe-cast.json" to { text: String -> text.replace("\"line\": 3", "\"line\": 4") },
            "$root/fixtures/unused-parameter.json" to { text: String -> text.replace("reserved.*", ".*") },
            "$root/fixtures/may-be-constant.json" to { text: String -> text.replace("\"opt_in\"", "\"advisory\"") },
            "$root/scope.json" to { text: String -> text.replace("\"authored_analyses\": 287", "\"authored_analyses\": 199") },
            "$root/cohorts/local-declaration-use-v1.json" to { text: String -> text.replace("default-reserved-names-are-exempt", "forged-control") },
            "$root/breadth-inventory.json" to { _: String -> "{not json\n" },
        )
        for ((relative, mutation) in changes) {
            val copy = copyData()
            val path = copy.resolve(relative)
            val before = Files.readString(path)
            val after = mutation(before)
            assertNotEquals(before, after)
            Files.writeString(path, after)
            assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(copy) }
        }
    }

    @Test fun `missing extra and unexpected nested new inputs are refused without symlink capability`() {
        val missing = copyData()
        Files.delete(missing.resolve("$root/fixtures/may-be-constant.json"))
        assertFails { DetektCorpusInventory.load(missing) }
        val extra = copyData()
        Files.writeString(extra.resolve("$root/unregistered.json"), "{}\n")
        assertFails { DetektCorpusInventory.load(extra) }
        val nested = copyData()
        Files.createDirectories(nested.resolve("$root/fixtures/unregistered/nested"))
        assertFails { DetektCorpusInventory.load(nested) }
    }

    @Test fun `linked new input is refused after actual symbolic link creation`() {
        val linked = copyData()
        val target = linked.resolve("$root/fixtures/may-be-constant.json")
        Files.delete(target)
        try {
            Files.createSymbolicLink(target, Path.of("unused-parameter.json"))
        } catch (failure: UnsupportedOperationException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Symlink-only control unavailable: actual filesystem provider does not support symbolic links; not a passed rejection control")
            return
        } catch (failure: java.nio.file.FileSystemException) {
            // Conservative actual capability check, never an OS-name shortcut or a catch around the rejection assertion.
            val privilegeDenied = failure is java.nio.file.AccessDeniedException || failure.reason?.trim()?.lowercase()?.removeSuffix(".") in setOf(
                "operation not permitted", "permission denied", "a required privilege is not held by the client")
            if (!privilegeDenied) throw failure // Missing parents, existing paths, storage errors and unknown reasons are failures.
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Symlink-only control unavailable: actual link creation was permission/privilege denied; not a passed rejection control")
            return
        } catch (failure: SecurityException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Symlink-only control unavailable: actual link creation was denied by the security policy; not a passed rejection control")
            return
        }
        assertTrue(Files.isSymbolicLink(target))
        assertFails { DetektCorpusInventory.load(linked) }
    }

    @Test fun `loaded inventory detects new data drift on every witness verification`() {
        val copy = copyData()
        val inventory = DetektCorpusInventory.load(copy)
        inventory.verify()
        val path = copy.resolve("$root/fixtures/safe-cast.json")
        Files.writeString(path, Files.readString(path) + " ")
        assertFailsWith<IllegalArgumentException> { inventory.verify() }
    }

    @Test fun `new plans cannot relax resource maxima or run on insufficient call grants`() {
        for (plan in plans) {
            val exact = DetektCorpusSettings.selected(plan.name, plan.plannedInvocations.toString(), null, null)
            assertEquals(plan.plannedInvocations, exact.maxInvocations)
            assertEquals(1_200_000L, exact.aggregateMillis)
            assertEquals(60_000L, exact.childMillis)
            assertFails { DetektCorpusSettings.selected(plan.name, (plan.plannedInvocations - 1).toString(), null, null) }
            assertFails { DetektCorpusSettings.selected(plan.name, "41", null, null) }
            assertFails { DetektCorpusSettings.selected(plan.name, null, "1200001", null) }
            assertFails { DetektCorpusSettings.selected(plan.name, null, null, "60001") }
        }
    }

    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
        "sha256:${"a".repeat(64)}", "sha256:${"b".repeat(64)}")
    private val references = listOf(buildJsonObject { put("path", "synthetic-control-only.json"); put("sha256", "0".repeat(64)) })
    private fun observe(budget: DetektCorpusBudget, call: CorpusInvocationSpec) {
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

    @Test fun `new context controls cannot reorder substitute borrow or abbreviate real schedule`() {
        for (plan in plans) {
            val budget = DetektCorpusBudget(DetektCorpusSettings.selected(plan.name, null, null, null), {}, { 0L })
            val schedule = assertNotNull(plan.expectedSchedule)
            for (call in schedule.take(10)) observe(budget, call)
            val next = schedule[10]
            val other = plans.first { it !== plan }
            val wrong = other.independentControlIds.getValue(other.analysesByRule.keys.first()).first()
            for (id in listOf("control-6", "configuration", wrong)) {
                assertFailsWith<IllegalArgumentException> { budget.reserve(next.kind, next.ruleId, id) }
                assertEquals(10, budget.summary().number("reserved_invocations"))
            }
            for (call in schedule.drop(10)) observe(budget, call)
            val summary = budget.finish()
            assertEquals(plan.plannedInvocations, summary.number("checked_invocations"))
            assertEquals(plan.expectedRuntimeCallbacks, summary.number("actual_runtime_callbacks"))
            assertFalse(summary.flag("publication_committed"))
            assertFails { budget.reserve(next.kind, next.ruleId, next.caseId) }
        }
    }

    @Test fun `unconfirmed or missing terminal callback does not release the next reservation`() {
        for (plan in plans) {
            val budget = DetektCorpusBudget(DetektCorpusSettings.selected(plan.name, null, null, null), {}, { 0L })
            val first = assertNotNull(plan.expectedSchedule).first()
            val ticket = budget.reserve(first.kind, first.ruleId, first.caseId)
            budget.observeProcess(ticket, ProcessOutcome("executed", exitCode = 0, executionConfirmed = false), identity)
            assertFails { budget.checked(ticket, references) }
            assertEquals(0, budget.summary().number("checked_invocations"))
            assertFails { budget.reserve(CorpusInvocationKind.AUTHORIZATION_DENIAL, first.ruleId, "configuration-permit-replay") }
        }
    }

    @Test fun `pre call work consumes original aggregate clock rather than creating a new timeout`() {
        val plan = plans.first()
        var now = 0L
        val settings = DetektCorpusSettings.selected(plan.name, null, "100", "60")
        val budget = DetektCorpusBudget(settings, {}, { now })
        now = 95_000_000L
        val first = assertNotNull(plan.expectedSchedule).first()
        val ticket = budget.reserve(first.kind, first.ruleId, first.caseId)
        assertEquals(5L, budget.childTimeoutMillis(ticket))
        now = 100_000_000L
        assertFailsWith<IllegalStateException> { budget.requireWithinDeadline() }
        assertTrue(budget.cancellation.isCancelled())
    }
}
