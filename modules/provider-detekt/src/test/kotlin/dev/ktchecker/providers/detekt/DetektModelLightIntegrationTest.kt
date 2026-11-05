package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.*

/**
 * Separate opt-in native adapter check: exactly two genuine Detekt LIGHT analyses. The imported
 * model/domain receipts are SYNTHETIC fixtures, not successful Gradle/compiler evidence. No
 * qualification, current-project coverage, or historical stage transfer can follow from it.
 * No fake runner, launcher, engine, or SARIF is used here. Retain scratch on every outcome.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DetektModelLightIntegrationTest {
    @Test fun `typed LIGHT path runs positive and negative without foreign source set or semantic promotion`() {
        assumeTrue(System.getProperty("ktchecker.detekt.model.integration") == "true",
            "Explicit native model adapter check disabled; use -PdetektModelIntegration=true only under owner execution admission")
        check(Runtime.version().feature() == 21) { "The explicitly selected native test tuple requires JDK 21" }
        for (positive in listOf(true, false)) DetektModelFixtures(positive).use { f ->
            f.retained = true // Never destroy unknown child outputs or the evidence ancestors.
            println("Unqualified Detekt model LIGHT fixture (${if (positive) "positive" else "negative"}): ${f.work}")
            val model = f.importModel()
            val original = f.parent(model)
            val grants = DetektModelFixtures.Grants(f.installation(required = true))
            val prepared = DetektVerifiedModelBinding.prepare(model, f.mainId, original, grants.provider, f.selected)
            val archive = f.archive(prepared)
            prepared.bindAttempt(original, archive.effective, archive.child, archive.verify).use { permit ->
                grants.permit = permit
                val result = grants.provider.analyze(archive.child)
                Files.writeString(f.work.resolve("provider-result.json"), ContractJson.encodeToString(result))
                Files.writeString(f.work.resolve("detekt.yml"), prepared.plan.configuration)
                val outcome = assertNotNull(grants.outcomes.singleOrNull(), "Exactly one actual runner callback must be retained per analysis")
                Files.writeString(f.work.resolve("process-outcome.json"), buildJsonObject {
                    put("fixture_only", true); put("actual_model_export", false); put("qualified", false)
                    put("status", outcome.status); put("execution_confirmed", outcome.executionConfirmed)
                    put("exit_code", outcome.exitCode); put("cancelled", outcome.cancelled); put("timed_out", outcome.timedOut)
                    put("stdout", outcome.stdout); put("stderr", outcome.stderr)
                    put("reasons", JsonArray(outcome.reasons.map(::JsonPrimitive)))
                    put("command", JsonArray(outcome.command.map(::JsonPrimitive)))
                    put("supervisor_identity", outcome.supervisorIdentity?.toString())
                    put("original_key", prepared.originalAnalysisKey); put("child_key", archive.child.analysisKey())
                    put("binding", prepared.bindingIdentity.digest)
                }.toString())
                assertTrue(outcome.executionConfirmed && outcome.successfulExecution, "Actual native exec success is required: ${outcome.reasons}")
                assertNotNull(outcome.supervisorIdentity)
                assertEquals(0, outcome.exitCode)
                Files.write(f.work.resolve("detekt.sarif"), outcome.report("detekt.sarif"))
                archive.verify()
                assertEquals(original.context.compilerArguments, archive.child.context.compilerArguments)
                assertEquals("", archive.child.context.scope.target)
                assertEquals("partial", result.status)
                assertFalse(result.complete())
                assertTrue(result.coverage.all { !it.tested && !it.qualified && it.certificateId == null && !it.successful() })
                val inactive = result.coverage.filter { it.ruleId != f.exactRule }
                assertEquals(setOf("unavailable", "excluded", "unsupported"), inactive.map { it.state }.toSet())
                assertTrue(inactive.all { it.provenance == "not_executed" && !it.implemented })
                assertTrue(result.findings.none { it.location.path.startsWith("src/test/") })
                assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in result.reasons)
                // Require this exact selected rule to have run on BOTH outcomes. A successful
                // engine with an omitted/disabled rule descriptor is not a negative control.
                val exact = result.coverage.single { it.ruleId == f.exactRule }
                assertTrue(exact.implemented)
                assertEquals("executed", exact.provenance)
                assertEquals("exact_syntax_upstream_executed_advisory_only_parser_compatibility_unqualified", exact.reason)
                assertEquals(if (positive) "findings" else "unknown", exact.state)
                if (positive) {
                    assertEquals(f.exactRule, result.findings.single().ruleId)
                    assertEquals("src/main/kotlin/A.kt", result.findings.single().location.path)
                    assertEquals("advisory", result.findings.single().kind)
                    assertEquals("warning", result.findings.single().severity)
                    assertEquals("low", result.findings.single().certainty)
                } else {
                    assertTrue(result.findings.isEmpty(), "Negative main source must not borrow the positive foreign test source")
                }
            }
            assertFalse(requireNotNull(grants.permit).accepts(archive.child))
        }
    }
}
