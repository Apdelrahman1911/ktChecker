package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

/** Source/contract unit controls only: no provider, compiler, JDK probe or native process is started. */
class CompilerOrdinary445FixtureContractTest {
    private val contract = CompilerOrdinary445FixtureContract
    private val repositoryPath by lazy { generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
        .take(8).first { Files.isRegularFile(it.resolve(contract.PLAN_PATH)) } }
    private fun repository(): Path = repositoryPath
    private fun bytes(path: String): ByteArray = CompilerFixtureProvenance.readStable(repository().resolve(path), contract.MAX_INPUT_BYTES)
    private fun sha(bytes: ByteArray) = Digests.sha256(bytes).removePrefix("sha256:")
    private val sourcePlan by lazy { contract.plan(bytes(contract.PLAN_PATH), bytes(contract.MANIFEST_PATH), CompilerRuleRegistry.load()) }
    private val bindingBytes by lazy { bytes(contract.BINDING_PATH) }
    private val boundPins by lazy { contract.binding(bindingBytes, sha(bindingBytes), sourcePlan) }
    private fun plan() = sourcePlan
    private fun binding() = bindingBytes
    private fun pins() = boundPins
    private fun args(root: Path = Path.of("/never-created-ordinary445-unit").toAbsolutePath().normalize()) = arrayOf(root.toString(),
        "--cohort=ordinary445-01", "--consent=" + contract.CONSENT, "--binding-sha256=" + "0".repeat(64),
        "--source-snapshot=build/evidence/source-snapshot-unit.json", "--source-snapshot-sha256=" + "0".repeat(64))
    private fun jobs(cohort: String) = contract.schedule(plan(), cohort, ::bytes)

    @Test fun defaultUnitRootUsesNativeAbsoluteNormalizedPathWithoutIo() {
        val supplied = Path.of(args().first())
        assertTrue(supplied.isAbsolute)
        assertEquals(supplied.normalize(), supplied)
        assertEquals(supplied.toString(), CompilerOrdinary445Settings.parse(args()).repositoryDirectory)
    }

    @Test fun current445IsExactAndNeitherHistoricalRouteIsAnOverride() {
        val registry = CompilerRuleRegistry.load()
        contract.requireMapping(registry.mappingIdentity)
        assertEquals(445, registry.mappings.size)
        assertEquals(402, registry.jvmRuleIds.size)
        assertFails { contract.requireMapping(CompilerFixtureMappingContract.LEGACY443) }
        assertFails { CompilerFixtureMappingContract.requireLegacy(registry.mappingIdentity) }
        assertFails { contract.requireMapping(registry.mappingIdentity.copy(resourceDigest = Digests.sha256("different bytes"))) }
        assertFails { contract.requireMapping(null) }
        assertFalse(CompilerCurrent445FixtureContract.SELECTOR in contract.SELECTORS)
        assertFalse("INAPPLICABLE_OPERATOR_MODIFIER_WARNING" in registry.byUpstreamId)
    }
    @Test fun closedOptionsRejectMissingConsentUnknownSelectorsLimitsAndDuplicates() {
        assertEquals(90_000L, CompilerOrdinary445Settings.parse(args()).perJobMillis)
        for (required in listOf("--cohort=", "--consent=", "--binding-sha256=", "--source-snapshot=", "--source-snapshot-sha256="))
            assertFails { CompilerOrdinary445Settings.parse(args().filterNot { it.startsWith(required) }.toTypedArray()) }
        for (extra in listOf("--max-attempts=29", "--per-job-seconds=91", "--overall-seconds=1201", "--evidence-mib=257",
            "--overall-seconds=0", "--overall-seconds=01", "--flags=-Werror", "--resume=previous", "--cohort=ordinary445-01"))
            assertFails { CompilerOrdinary445Settings.parse(args() + extra) }
        for (selector in listOf("all", "ordinary445-00", "ordinary445-26", "ordinary445-1", CompilerCurrent445FixtureContract.SELECTOR))
            assertFails { CompilerOrdinary445Settings.parse(args().map {
                if (it.startsWith("--cohort=")) "--cohort=" + selector else it
            }.toTypedArray()) }
        assertFails { CompilerOrdinary445Settings.parse(args() + "/second-root") }
    }
    @Test fun absentInputsRefuseBeforeRunOrScratchAllocation() {
        val directory = Files.createTempDirectory("ordinary445-admission-unit-")
        try {
            assertFails { CompilerOrdinary445FixtureRunner.run(args(directory).filterNot { it.startsWith("--consent=") }.toTypedArray()) }
            assertFails { CompilerOrdinary445FixtureRunner.run(args(directory)) }
            assertFalse(Files.exists(directory.resolve("build")))
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally { Files.delete(directory) }
    }
    @Test fun all98RulesArePartitionedWithoutOverlapAndEveryScheduledCallIsReachable() {
        val plan = plan()
        assertEquals(98, plan.fixtures.size)
        assertEquals(196, plan.sourcePins.size)
        assertEquals(196, plan.sourcePins.map { it.path }.toSet().size)
        assertEquals(plan.fixtures.map { it.ruleId }, contract.SELECTORS.flatMap { plan.selected(it).map { f -> f.ruleId } })
        assertEquals(List(24) { 4 } + 2, contract.SELECTORS.map { plan.selected(it).size })
        val all = contract.SELECTORS.map { contract.schedule(plan, it, ::bytes) }
        assertEquals(632, all.sumOf { it.size })
        assertEquals(607, all.sumOf { it.count { j -> !j.refusal } })
        assertEquals(25, all.sumOf { it.count { j -> j.refusal } })
        assertEquals(79, all.sumOf { it.count { j -> j.hardErrorSuppression } })
        for ((index, schedule) in all.withIndex()) {
            val chosen = plan.selected(contract.SELECTORS[index])
            assertEquals(6 * chosen.size + chosen.count { it.severity == "warning" } + 1, schedule.size)
            val settings = CompilerOrdinary445Settings.parse(args().map {
                if (it.startsWith("--cohort=")) "--cohort=" + contract.SELECTORS[index] else it
            }.toTypedArray())
            val budget = CompilerOrdinary445Budget(settings, schedule.map { it.id }, 0, { 0L })
            schedule.forEach { budget.reserve(it.id) }
            assertEquals(schedule.size, budget.reserved)
            assertFails { budget.reserve(schedule.first().id) }
            assertTrue(schedule.last().refusal)
        }
    }
    @Test fun exactPositiveNegativeSourcesAndSupplementalNonExecutionRemainVisible() {
        val plan = plan()
        plan.sourcePins.forEach { contract.requireBytes(it, bytes(it.path)) }
        for (f in plan.fixtures) {
            val index = plan.fixtures.indexOf(f) / 4
            val schedule = jobs(contract.SELECTORS[index]).filter { it.fixture?.ruleId == f.ruleId }
            assertEquals(contract.CASE_KINDS.toSet(), schedule.map { it.kind }.toSet())
            assertEquals(bytes(f.positive.path).toString(Charsets.UTF_8), schedule.single { it.control == "positive" }.sources.values.single())
            assertEquals(bytes(f.negative.path).toString(Charsets.UTF_8), schedule.single { it.control == "negative" }.sources.values.single())
            assertTrue(schedule.all { it.sources.keys.all { path -> !path.contains("supplemental") && !path.contains("planned-kmp") } })
        }
        val first = plan.sourcePins.first()
        assertFails { contract.requireBytes(first, bytes(first.path) + byteArrayOf(32)) }
    }
    @Test fun warningAndErrorSchedulesHaveDistinctExplicitConfigurationAndSuppressionControls() {
        val plan = plan()
        for (cohort in contract.SELECTORS) for (f in plan.selected(cohort)) {
            val schedule = contract.schedule(plan, cohort, ::bytes).filter { it.fixture?.ruleId == f.ruleId }
            val positive = schedule.single { it.control == "positive" }
            val version = schedule.single { it.control == "version" }
            assertEquals(f.flags + listOf("-language-version", "2.4", "-api-version", "2.4"), version.flags)
            val suppression = schedule.single { it.control == "suppression" }
            assertEquals("@file:Suppress(\"${f.upstreamId}\")\n" + positive.sources.getValue(positive.primaryPath),
                suppression.sources.getValue(suppression.primaryPath))
            if (f.severity == "warning") {
                assertEquals(7, schedule.size)
                assertTrue("-Xwarning-level=${f.upstreamId}:error" in schedule.single { it.control == "configuration" }.flags)
                assertTrue("-Xwarning-level=${f.upstreamId}:disabled" in schedule.single { it.control == "warning_disabled" }.flags)
                assertEquals(2, suppression.sources.size)
                assertNotNull(suppression.unsuppressedPath)
                assertTrue(suppression.sources.getValue(suppression.unsuppressedPath!!).contains(".ordinary445control"))
                assertFalse(suppression.hardErrorSuppression)
            } else {
                assertEquals(6, schedule.size)
                assertEquals("21", schedule.single { it.control == "configuration" }.flags[1])
                assertEquals(1, suppression.sources.size)
                assertNull(suppression.unsuppressedPath)
                assertTrue(suppression.hardErrorSuppression)
            }
            val regression = schedule.single { it.control == "regression" }
            assertNotEquals(positive.primaryPath, regression.primaryPath)
            assertEquals("\n\n" + positive.sources.getValue(positive.primaryPath), regression.sources.getValue(regression.primaryPath))
        }
    }
    @Test fun bindingAndPlanArePinnedClosedAndCannotBeOwnerHashRewrittenToLegacy() {
        val plan = plan(); val raw = binding()
        val original = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        assertEquals((contract.FIXED_DIGESTS.keys + contract.ROUTE_SOURCES + plan.sourcePins.map { it.path }).toSet(), pins().map { it.path }.toSet())
        assertFails { contract.binding(raw + byteArrayOf(32), sha(raw), plan) }
        assertFails { contract.plan(bytes(contract.PLAN_PATH) + byteArrayOf(32), bytes(contract.MANIFEST_PATH), CompilerRuleRegistry.load()) }
        val rows = original.getValue("pins").jsonArray
        val variants = listOf(
            original + ("mapping_contract" to JsonPrimitive(CompilerFixtureMappingContract.LEGACY443.contractId)),
            original + ("ordered_selectors" to JsonArray(original.getValue("ordered_selectors").jsonArray.dropLast(1))),
            original + ("ordered_rule_ids" to JsonArray(original.getValue("ordered_rule_ids").jsonArray.reversed())),
            original + ("pins" to JsonArray(rows.dropLast(1))),
            original + ("pins" to JsonArray(rows + rows.first())),
            original + ("override" to JsonPrimitive(true)),
        )
        for (variant in variants) {
            val encoded = JsonObject(variant).toString().toByteArray()
            assertFails { contract.binding(encoded, sha(encoded), plan) }
        }
    }
    @Test fun pathTraversalAliasesAndChangedSourcePinsAreRefused() {
        for (path in listOf("../secret", "/absolute", "a/../b", "a//b", "./a", "a\\b", "C:secret", "a/", "a\u0000b"))
            assertFails { contract.relativePath(path) }
        val raw = binding(); val root = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        val rows = root.getValue("pins").jsonArray
        val target = rows.indexOfFirst { it.jsonObject.getValue("path").jsonPrimitive.content == plan().sourcePins.first().path }
        val modified = rows.mapIndexed { i, row -> if (i == target) JsonObject(row.jsonObject + ("sha256" to JsonPrimitive("0".repeat(64)))) else row }
        val encoded = JsonObject(root + ("pins" to JsonArray(modified))).toString().toByteArray()
        assertFails { contract.binding(encoded, sha(encoded), plan()) }
    }
    private fun snapshot(): JsonObject {
        val raw = binding()
        return ordinary445Element(mapOf("capturedAt" to "unit-data-not-a-runtime-attestation", "head" to "a".repeat(40), "dirty" to true,
            "aggregate" to "b".repeat(64), "files" to (pins() + CompilerOrdinary445Pin(contract.BINDING_PATH, sha(raw), raw.size)).map { it.description() })).jsonObject
    }
    private fun snapshotCheck(value: JsonObject): CompilerOrdinary445SourceSnapshot {
        val raw = value.toString().toByteArray()
        return contract.sourceSnapshot(raw, sha(raw), pins(), binding(), sha(binding()))
    }
    @Test fun ownerSnapshotUsesExactHeadDirtySchemaAndBindsEverySelectedByteWithoutTreeClaim() {
        val receipt = snapshotCheck(snapshot())
        assertEquals("a".repeat(40), receipt.head)
        assertTrue(receipt.dirty)
        assertEquals(pins().size + 1, receipt.fileCount)
        assertEquals(false, receipt.description()["whole_tree_recomputed"])
        assertEquals(false, receipt.description()["aggregate_is_current_tree_claim"])
        assertEquals(false, receipt.description()["source_to_bytecode_or_execution_attestation"])
    }
    @Test fun missingMalformedHistoricalSnapshotIdentityAndDuplicateOrDriftedFilesFail() {
        val original = snapshot()
        for (key in listOf("head", "aggregate", "dirty")) assertFails { snapshotCheck(JsonObject(original - key)) }
        for ((key, value) in listOf("head" to JsonPrimitive("a14cab0"), "aggregate" to JsonPrimitive("short"),
            "dirty" to JsonPrimitive("true"), "dirty" to JsonPrimitive(1), "dirty" to JsonNull))
            assertFails { snapshotCheck(JsonObject(original + (key to value))) }
        assertFails { snapshotCheck(JsonObject((original - "head") + ("baseCommit" to original.getValue("head")))) }
        val entries = original.getValue("files").jsonArray
        assertFails { snapshotCheck(JsonObject(original + ("files" to JsonArray(entries.dropLast(1))))) }
        assertFails { snapshotCheck(JsonObject(original + ("files" to JsonArray(entries + entries.first())))) }
        val first = JsonObject(entries.first().jsonObject + ("bytes" to JsonPrimitive(0)))
        assertFails { snapshotCheck(JsonObject(original + ("files" to JsonArray(listOf(first) + entries.drop(1))))) }
        val raw = original.toString().toByteArray()
        assertFails { contract.sourceSnapshot(raw + byteArrayOf(32), sha(raw), pins(), binding(), sha(binding())) }
    }
    @Test fun ownerStopDeadlineAndAttemptOrderAreStickyAndCannotBeWidened() {
        var now = 0L; var stop = false
        val schedule = jobs("ordinary445-01").map { it.id }
        val settings = CompilerOrdinary445Settings.parse(args()).copy(overallMillis = 1000, perJobMillis = 900)
        val budget = CompilerOrdinary445Budget(settings, schedule, 0, { now }, { stop })
        assertFails { budget.reserve(schedule[1]) }
        budget.reserve(schedule[0])
        assertFails { budget.reserve(schedule[0]) }
        stop = true
        assertFails { budget.requireActive() }
        stop = false
        assertFails { budget.requireActive() }
        val deadline = CompilerOrdinary445Budget(settings, schedule, 0, { now })
        now = 1_000_000_000L
        assertFails { deadline.requireActive() }
        now = 0
        assertFails { deadline.requireActive() }
        val unsafe = CompilerOrdinary445Budget(settings, schedule, 0, { 0L }, { error("unreadable owner marker") })
        assertFails { unsafe.requireActive() }
    }
    @Test fun latePublicationFailureRevokesRatherThanRetriesSuccessfulAttribution() {
        var checkpoints = 0; var value = "not_published"
        val failure = IllegalStateException("late checkpoint")
        val publication = CompilerOrdinary445Publication { if (++checkpoints == 2) throw failure }
        val actual = assertFailsWith<IllegalStateException> {
            publication.publish(true, { value = "candidate_settled" }, { value = "revoked" })
        }
        assertSame(failure, actual)
        assertEquals("revoked", value)
        assertFails { publication.publish(true, { value = "wrong_retry" }, { value = "wrong_retry" }) }
        assertEquals("revoked", value)
    }
    @Test fun incompletePublicationCannotBecomeSuccessAndCorrectionFailureRetainsOriginal() {
        var called = false
        val publication = CompilerOrdinary445Publication { error("not allowed for ineligible run") }
        assertFalse(publication.publish(false, { error("not eligible") }, { called = true }))
        assertTrue(called)
        assertFails { publication.publish(true, {}, {}) }
        val original = IllegalStateException("publication write"); val correction = IllegalArgumentException("correction write")
        val second = CompilerOrdinary445Publication {}
        val actual = assertFailsWith<IllegalStateException> { second.publish(true, { throw original }, { throw correction }) }
        assertSame(original, actual)
        assertTrue(actual.suppressed.contains(correction))
    }
}
