package dev.ktchecker.compiler

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag

/**
 * Opt-in real native/compiler route, excluded from ordinary test/check. A passing JUnit method
 * checks documentary observation wiring, not9passed or445qualified diagnostics.
 */
@Tag("compiler-current445-native-integration")
class CompilerCurrent445FixtureIntegrationTest {
    @Test fun actualNineObservationRecordsRetainEightKnownOraclesAndOneUnknownWithoutRuleCredit() {
        val contract = CompilerCurrent445FixtureContract
        check(System.getProperty("ktchecker.compiler.current445.task") == contract.CONSENT) {
            "Only the explicit compilerCurrent445Fixtures task may run this integration method"
        }
        fun property(name: String) = requireNotNull(System.getProperty("ktchecker.compiler.current445." + name))
        val receipt = CompilerCurrent445FixtureRunner.run(arrayOf(
            "--cohort=" + contract.SELECTOR, "--consent=" + property("consent"),
            "--binding-sha256=" + property("bindingSha256"),
            "--overall-seconds=" + property("overallSeconds"), "--per-job-seconds=" + property("perJobSeconds"),
            "--evidence-mib=" + property("evidenceMiB"), property("repository")))
        assertTrue(receipt.validUtf8ScheduleRecorded)
        assertEquals(8, receipt.knownCasesMatched)
        assertEquals(listOf(contract.CASE_IDS.last()), receipt.unknownCaseIds)
        assertFalse(receipt.runComplete)
        assertFalse(receipt.sourcePlanRawByteRetentionCapabilityResolved)
        fun json(relative: String): JsonObject = Json.parseToJsonElement(CompilerFixtureProvenance.readStable(
            receipt.directory.resolve(relative), 4_194_304).toString(Charsets.UTF_8)).jsonObject
        val summary = json("summary.json")
        assertFalse(summary.getValue("run_complete").jsonPrimitive.boolean)
        assertFalse(summary.getValue("source_plan_expected_outcome_closure_complete").jsonPrimitive.boolean)
        assertFalse(summary.getValue("source_plan_raw_byte_retention_capability_resolved").jsonPrimitive.boolean)
        val cases = summary.getValue("cases").jsonArray
        assertEquals(contract.CASE_IDS, cases.map { it.jsonObject.getValue("case_id").jsonPrimitive.content })
        assertTrue(cases.take(8).all { it.jsonObject.getValue("expected_outcome_matched").jsonPrimitive.boolean })
        assertEquals(JsonNull, cases.last().jsonObject.getValue("expected_outcome_matched"))
        val errorDirectory = "jobs/06-" + contract.CASE_IDS[5] + "/"
        assertNotEquals(0, json(errorDirectory + "process-1.json").getValue("exit_code").jsonPrimitive.int)
        assertEquals("failed", json(errorDirectory + "result.json").getValue("status").jsonPrimitive.content)
        val scope = json("scope.json")
        assertEquals(445, scope.getValue("compiler_mappings").jsonArray.size)
        assertEquals(1665, scope.getValue("catalog_rules").jsonArray.size)
        assertEquals(46, scope.getValue("existing_exclusion_count_unchanged").jsonPrimitive.int)
        assertTrue(scope.getValue("catalog_rules").jsonArray.all { row ->
            row.jsonObject.getValue("tested_stage") == JsonNull && row.jsonObject.getValue("qualified_stage") == JsonNull
        })
    }
}
