package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Authored data and pure controls only: no provider, Detekt engine, process, or model execution. */
class DetektCorpusInventoryTest {
    @TempDir lateinit var temporary: Path
    private val repository: Path get() = Path.of(System.getProperty("ktchecker.repositoryRoot")).toAbsolutePath().normalize()
    private val expansion = DetektCorpusInventory.EXPANSION
    private var copies = 0
    private fun load() = DetektCorpusInventory.load(repository)
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun fixture(inventory: DetektCorpusInventory, slug: String) =
        inventory.fixtures.single { it.text("rule_id").substringAfterLast('.') == slug }
    private fun controls(fixture: JsonObject) = fixture.getValue("controls").jsonArray.map { it.jsonObject }
    private fun read(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
    private fun hash(bytes: ByteArray) = Digests.sha256(bytes).removePrefix("sha256:")

    /** Copies these small, fixed authored inputs only; never an engine, cache, installation or evidence. */
    private fun copyData(): Path {
        val root = Files.createDirectory(temporary.resolve("authored-${++copies}"))
        for (relative in DetektCorpusInventory.filePins.keys) {
            val destination = root.resolve(relative)
            Files.createDirectories(destination.parent)
            Files.copy(repository.resolve(relative), destination)
        }
        return root
    }

    @Test fun `loader joins sixteen current exact syntax mappings while retaining ten and ninety one`() {
        val inventory = load()
        inventory.verify()
        val previousIds = DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet()
        val previous = inventory.fixtures.filter { it.text("rule_id") in previousIds }
        assertEquals(16, previous.size)
        assertEquals(previousIds, previous.map { it.text("rule_id") }.toSet())
        assertEquals(145, previous.sumOf { 6 + controls(it).size })
        assertEquals(11, previous.count { it.text("default_activation") == "advisory" })
        assertEquals(5, previous.count { it.text("default_activation") == "opt_in" })
        val priorIds = DetektCorpusCohort.preObservability.flatMap { it.analysesByRule.keys }.toSet()
        val prior = inventory.fixtures.filter { it.text("rule_id") in priorIds }
        assertEquals(19, prior.size)
        assertEquals(172, prior.sumOf { 6 + controls(it).size })
        assertEquals(13, prior.count { it.text("default_activation") == "advisory" })
        assertEquals(6, prior.count { it.text("default_activation") == "opt_in" })
        assertEquals(22, inventory.fixtures.size)
        assertEquals(DetektCorpusCohort.authoredRuleIds, inventory.fixtures.map { it.text("rule_id") }.toSet())
        assertEquals(199, inventory.fixtures.sumOf { 6 + controls(it).size })
        val old = inventory.fixtures.filter { it.text("corpus_id") == "detekt-syntax-corpus-v1" }
        assertEquals(10, old.size)
        assertEquals(91, old.sumOf { 6 + controls(it).size })
        for (item in inventory.fixtures) {
            val mapping = DetektCatalog.byId.getValue(item.text("rule_id"))
            assertTrue(mapping.exact)
            assertEquals("syntax", mapping.depth)
            assertEquals(mapping.catalog_record_sha256, item.text("catalog_record_sha256"))
            assertEquals(mapping.upstream_id, item.text("upstream_id"))
            assertEquals("unexecuted", item.text("authoring_state"))
            assertFalse(item.keys.any { it in setOf("passed", "tested", "qualified") })
        }
        val pins = DetektCorpusExpansionPins.files.filterKeys { it.startsWith(DetektCorpusInventory.BASELINE + "/") }
        assertEquals(10, pins.size)
        for ((path, digest) in pins) assertEquals(digest, hash(Files.readAllBytes(repository.resolve(path))))
    }

    @Test fun `two added matrices bind exact scheduled calls and require active descriptors for negatives`() {
        val inventory = load()
        val added = DetektCorpusCohort.all.filter { it.expansion }
        assertEquals(2, added.size)
        for (cohort in added) {
            val matrix = inventory.matrix(cohort)
            val analyses = matrix.getValue("analyses").jsonArray.map { it.jsonObject }
            val calls = matrix.getValue("invocation_schedule").jsonArray.map { it.jsonObject }
            assertEquals(27, analyses.size)
            assertEquals(37, calls.size)
            assertEquals(cohort.expectedSchedule, calls.map { call -> CorpusInvocationSpec(
                CorpusInvocationKind.entries.single { it.id == call.text("kind") }, call.text("rule_id"), call.text("case_id")) })
            assertEquals(28, calls.count { it.flag("native_launch_required") })
            assertEquals(31, calls.count { it.flag("runtime_callback_required") })
            assertEquals(113, matrix.getValue("counts").jsonObject.number("planned_progress_events"))
            assertEquals((1..37).toList(), calls.map { it.number("ordinal") })
            assertEquals((0..26).toList(), calls.filter { it.text("kind") == "detector_analysis" }.map { it.number("matrix_index") })
            for (row in analyses) {
                val disabled = row.text("case_id") == "configuration"
                assertEquals(disabled, row.flag("rule_disabled_for_this_configuration_control"))
                assertEquals(!disabled, row.flag("require_active_upstream_descriptor"))
                assertEquals(!disabled, row.flag("selected_sarif_descriptor_enabled_required"))
                assertEquals(DetektCatalog.byId.getValue(row.text("rule_id")).sarifId, row.text("selected_sarif_rule_id"))
                assertTrue(row.flag("require_actual_native_launch"))
                assertEquals("not_run", row.text("state"))
                assertFalse(row.flag("attempted"))
                assertEquals(JsonNull, row["observed_findings"])
                assertFalse(row.flag("natural_project_observation"))
                if (row.text("case_id") in setOf("negative", "regression")) {
                    assertEquals(0, row.number("authored_expected_count"))
                    assertTrue(row.flag("require_active_upstream_descriptor"))
                }
            }
            assertEquals(10, analyses.count { it.number("authored_expected_count") == 0 && it.text("case_id") != "configuration" })
        }
        assertFailsWith<IllegalArgumentException> { inventory.matrix(DetektCorpusCohort.EXCEPTIONS_CORE) }
    }

    @Test fun `proposal identities and denominators cannot import historical or natural success`() {
        val inventory = load()
        val registry = inventory.expansionRegistry
        assertEquals("CONNECTED_SOURCE_CANDIDATE_UNCOMPILED_UNEXECUTED_NOT_ADMITTED", registry.text("state"))
        assertEquals(JsonNull, registry["default_cohort"])
        assertEquals(0, registry.number("actual_tests_executed"))
        assertEquals(0, registry.number("actual_provider_invocations"))
        assertEquals(0, registry.number("historical_stage_transfer"))
        for (value in registry.getValue("cohorts").jsonArray) {
            val row = value.jsonObject
            val cohort = DetektCorpusCohort.selected(row.text("cohort_id"))
            assertEquals(cohort.planIdentity, DetektCorpusInventory.canonical(row.getValue("plan_identity")))
            assertEquals(row.text("plan_identity_sha256"), hash(cohort.planIdentity.toByteArray()))
            assertFalse(row.flag("execution_admitted"))
            assertEquals("connected_source_candidate_uncompiled_unexecuted", row.text("runtime_registration_state"))
            val identity = row.getValue("plan_identity").jsonObject
            assertFalse(identity.flag("previous_stages_accepted"))
            assertFalse(identity.flag("aggregate_context_activation"))
            val counts = row.getValue("counts").jsonObject
            assertEquals(0, counts.number("actual_executions"))
            assertEquals(JsonNull, counts["actual_findings"])
            assertEquals(0, counts.number("natural_project_observations"))
            assertEquals(0, counts.number("precision_reviewed_natural_observations"))
        }
        val scope = read(repository.resolve("$expansion/scope.json"))
        val current = scope.getValue("current_scope").jsonObject
        assertEquals(1663, current.number("ids"))
        assertEquals(8, current.number("contexts"))
        assertEquals(0, current.number("new_contexts"))
        assertEquals(JsonArray(emptyList()), current["scope_exclusions"])
        assertEquals(0, current.number("historical_stage_transfer"))
        val combined = scope.getValue("combined_proposed_authoring_scope").jsonObject
        assertEquals(16, combined.number("rule_count"))
        assertEquals(145, combined.number("authored_analyses"))
        assertFalse(combined.flag("aggregate_execution_or_sidecar_created"))
        assertFalse(combined.flag("scope_reduction"))
    }

    @Test fun `genuine upstream options change same byte cases without disabling the rule`() {
        val inventory = load()
        val condition = fixture(inventory, "complex-condition")
        val raised = controls(condition).single { it.text("id") == "owner-four-condition-threshold" }
        assertEquals(condition.getValue("positive").jsonObject["sources"], raised["sources"])
        assertEquals(buildJsonObject { put("allowedConditions", 4) }, raised["options"])
        assertEquals(1, condition.getValue("positive").jsonObject.getValue("expected").jsonArray.size)
        assertEquals(JsonArray(emptyList()), raised["expected"])
        val docs = controls(fixture(inventory, "outdated-documentation"))
        assertEquals(docs[0]["sources"], docs[1]["sources"])
        assertEquals(buildJsonObject { put("matchDeclarationsOrder", false) }, docs[1]["options"])
        assertEquals(listOf("render", "invalidTag"), docs[0].getValue("expected").jsonArray.map { it.jsonObject.text("span") })
        assertEquals(listOf("invalidTag"), docs[1].getValue("expected").jsonArray.map { it.jsonObject.text("span") })
        val visibility = controls(fixture(inventory, "forbidden-public-data-class"))
        assertEquals(visibility[0]["sources"], visibility[1]["sources"])
        assertEquals(buildJsonObject { put("ignorePackages", JsonArray(emptyList())) }, visibility[1]["options"])
        assertEquals(JsonArray(emptyList()), visibility[0]["expected"])
        assertEquals(1, visibility[1].getValue("expected").jsonArray.size)
        for (cohort in DetektCorpusCohort.all.filter { it.expansion }) {
            val options = inventory.matrix(cohort).getValue("analyses").jsonArray.map { it.jsonObject }
                .filter { it.getValue("upstream_options").jsonObject.isNotEmpty() }
            for (row in options) {
                assertFalse(row.flag("rule_disabled_for_this_configuration_control"))
                assertTrue(row.flag("require_active_upstream_descriptor"))
            }
        }
    }

    @Test fun `heuristic false positives false negatives and application policy limits remain explicit`() {
        val inventory = load()
        val allControls = inventory.fixtures.filter { it.text("corpus_id") == "detekt-syntax-expansion-v1" }
            .flatMap { controls(it) }.associateBy { it.text("id") }
        assertEquals(18, allControls.size)
        val falsePositives = setOf("materialized-list-is-not-a-range-receiver", "shadowed-array-constructor-requires-lambda",
            "stateful-second-poll-is-not-redundant", "whitespace-inside-string-literals-is-significant", "literal-operator-text-is-not-four-decisions")
        val falseNegatives = setOf("stored-range-origin-not-followed", "computed-default-expression-not-evaluated", "parenthesized-duplicate-is-not-flattened")
        assertEquals(falsePositives, allControls.values.filter { it.text("assessment") == "anticipated_upstream_false_positive" }.map { it.text("id") }.toSet())
        assertEquals(falseNegatives, allControls.values.filter { it.text("assessment") == "anticipated_upstream_false_negative" }.map { it.text("id") }.toSet())
        for (id in falsePositives) assertTrue(allControls.getValue(id).getValue("expected").jsonArray.isNotEmpty())
        for (id in falseNegatives) assertEquals(JsonArray(emptyList()), allControls.getValue(id)["expected"])
        val application = allControls.getValue("application-state-has-no-library-abi-promise")
        assertEquals("opt_in_policy_limit", application.text("assessment"))
        assertEquals("ScreenState", application.getValue("expected").jsonArray.single().jsonObject.text("span"))
        assertEquals(JsonArray(emptyList()), allControls.getValue("multi-statement-default-initializer-retains-audit")["expected"])
    }

    @Test fun `suppression preserves unsuppressed positive neighbors rather than globally filtering findings`() {
        for (item in load().fixtures.filter { it.text("corpus_id") == "detekt-syntax-expansion-v1" }) {
            val positive = item.getValue("positive").jsonObject
            val suppression = item.getValue("suppression").jsonObject
            assertEquals(positive["expected"], suppression["expected"])
            assertTrue(positive.getValue("expected").jsonArray.isNotEmpty())
            assertEquals(JsonArray(emptyList()), item.getValue("negative").jsonObject["expected"])
            val sources = positive.getValue("sources").jsonObject
            val suppressedSources = suppression.getValue("sources").jsonObject
            assertEquals(sources.size * 2, suppressedSources.size)
            for ((path, bytes) in sources) {
                assertEquals(bytes, suppressedSources[path])
                val copy = suppressedSources.getValue("Suppressed$path").jsonPrimitive.content
                assertTrue(copy.startsWith("@file:Suppress(\"${item.text("upstream_id").substringAfter(':')}\")\n"))
                assertFalse(copy.contains("Suppress(\"ktc."))
                assertEquals(bytes.jsonPrimitive.content, copy.substringAfter("\n\n").replaceFirst("package suppressed.", "package "))
            }
        }
    }

    @Test fun `missing baseline new fixture and registry data fail closed`() {
        val paths = listOf(DetektCorpusExpansionPins.files.keys.first { it.startsWith(DetektCorpusInventory.BASELINE + "/") },
            "$expansion/fixtures/complex-condition.json", "$expansion/cohorts.json", "$expansion/README.md")
        for (relative in paths) {
            val root = copyData()
            Files.delete(root.resolve(relative))
            assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(root) }
        }
    }

    @Test fun `changed ranges options duplicate keys and unknown metadata fail pinned bytes before decoding`() {
        val path = "$expansion/fixtures/complex-condition.json"
        val original = Files.readString(repository.resolve(path))
        val mutations = listOf(
            original.replace("\"column\": 8", "\"column\": 9"),
            original.replace("\"allowedConditions\": 4", "\"allowedConditions\": 5"),
            original.replaceFirst("{", "{\n  \"schema_version\": 1,"),
            original.replaceFirst("{", "{\n  \"unexpected_metadata\": true,"),
            "not JSON at all\n",
        )
        for (changed in mutations) {
            assertNotEquals(original, changed)
            val root = copyData()
            Files.writeString(root.resolve(path), changed)
            val failure = assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(root) }
            assertTrue(failure.message.orEmpty().contains("Pinned authored corpus bytes changed"))
        }
    }

    @Test fun `swapped or extra inputs cannot create a hidden seventeenth rule or alternate plan`() {
        val swapped = copyData()
        val first = swapped.resolve("$expansion/fixtures/complex-condition.json")
        val second = swapped.resolve("$expansion/fixtures/outdated-documentation.json")
        val bytes = Files.readAllBytes(first)
        Files.write(first, Files.readAllBytes(second)); Files.write(second, bytes)
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(swapped) }
        for (relative in listOf("$expansion/fixtures/extra.json", "$expansion/unregistered.txt",
            "${DetektCorpusInventory.BASELINE}/extra.json")) {
            val root = copyData()
            Files.writeString(root.resolve(relative), "{}\n")
            assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(root) }
        }
    }

    @Test fun `links at input file directory or repository boundaries are never accepted`() {
        val linkedFile = copyData()
        val relative = "$expansion/fixtures/complex-condition.json"
        Files.delete(linkedFile.resolve(relative))
        Files.createSymbolicLink(linkedFile.resolve(relative), repository.resolve(relative))
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(linkedFile) }
        val linkedDirectory = copyData()
        val directory = linkedDirectory.resolve("$expansion/fixtures")
        val retained = temporary.resolve("real-fixtures")
        Files.move(directory, retained)
        Files.createSymbolicLink(directory, retained)
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(linkedDirectory) }
        val root = copyData()
        val alias = temporary.resolve("repository-link")
        Files.createSymbolicLink(alias, root)
        assertFailsWith<IllegalArgumentException> { DetektCorpusInventory.load(alias) }
    }

    @Test fun `reverification rejects changed bytes or membership after initial successful load`() {
        val modified = copyData()
        val inventory = DetektCorpusInventory.load(modified)
        Files.writeString(modified.resolve("$expansion/README.md"), "Changed after load\n")
        assertFailsWith<IllegalArgumentException> { inventory.verify() }
        val added = copyData()
        val other = DetektCorpusInventory.load(added)
        Files.writeString(added.resolve("$expansion/extra.json"), "{}\n")
        assertFailsWith<IllegalArgumentException> { other.verify() }
    }

    @Test fun `fixed fixture and plan membership cannot be mutated through exposed collections`() {
        val inventory = load()
        assertFailsWith<UnsupportedOperationException> { (inventory.fixtures as MutableList<JsonObject>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusExpansionPins.files as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusExpansionPins.planIdentities as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (DetektCorpusExpansionPins.controlIds as MutableMap<String, List<String>>).clear() }
        for (ids in DetektCorpusExpansionPins.controlIds.values)
            assertFailsWith<UnsupportedOperationException> { (ids as MutableList<String>).clear() }
        assertEquals(22, DetektCorpusExpansionPins.files.size)
        val previousIds = DetektCorpusCohort.preProtocol.flatMap { it.analysesByRule.keys }.toSet()
        val priorIds = DetektCorpusCohort.preObservability.flatMap { it.analysesByRule.keys }.toSet()
        assertEquals(16, inventory.fixtures.count { it.text("rule_id") in previousIds })
        assertEquals(19, inventory.fixtures.count { it.text("rule_id") in priorIds })
        assertEquals(22, inventory.fixtures.size)
        inventory.verify()
    }

    @Test fun `canonical identities sort objects preserve arrays and use ASCII JSON escaping`() {
        val value = buildJsonObject {
            put("z", buildJsonArray { add(2); add(1); add(JsonNull) })
            put("a", buildJsonObject { put("\u00e9", "line\n\t\"\\\u007f\ud83d\ude00"); put("b", false) })
        }
        assertEquals("{\"a\":{\"b\":false,\"\\u00e9\":\"line\\n\\t\\\"\\\\\\u007f\\ud83d\\ude00\"},\"z\":[2,1,null]}",
            DetektCorpusInventory.canonical(value))
    }
}
