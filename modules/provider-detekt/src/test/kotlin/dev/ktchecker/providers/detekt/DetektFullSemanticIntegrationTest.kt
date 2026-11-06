package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.GradleModelExporter
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.*

/**
 * Dedicated opt-in ONLY: exactly seven genuine pinned Detekt FULL invocations, zero standalone
 * Kotlin compiler or Gradle model invocations. The embedded Analysis API runs inside Detekt.
 * The source/model/ingress/archive receipts are explicitly
 * SYNTHETIC; the actual current exporter still rejects compiler/KGP2.4.10. Never treat this as
 * compatibility, complete resolution, resource-ownership correctness or release qualification.
 *
 * No fake engine, launcher, runner, report or filesystem fallback. The exact owner-selected
 * real whole JDK is mandatory. Every scratch ancestor/outcome is retained, including failures.
 */
@Tag("detekt-full-native")
@Execution(ExecutionMode.SAME_THREAD)
class DetektFullSemanticIntegrationTest {
    @Test fun `seven exact2_4_10 FULL observations distinguish resolved constructor and use identities`() {
        val originalStartedNanos = System.nanoTime()
        check(System.getProperty("ktchecker.detekt.full.task") == "fullSemanticTest") {
            "Only the dedicated fullSemanticTest task may select this native experiment, never ordinary test/check or generic Detekt flags"
        }
        check(System.getProperty("ktchecker.detekt.full.consent") == CONSENT) {
            "Explicit owner admission for the closed seven-invocation2.4.10 experiment is required"
        }
        check(System.getProperty("ktchecker.detekt.full.maxInvocations") == MAX_INVOCATIONS.toString())
        val aggregateMillis = number("ktchecker.detekt.full.aggregateMillis", 600_000, 1_000..600_000)
        val childMillis = number("ktchecker.detekt.full.childMillis", 60_000, 1_000..60_000)
        val deadline = OriginalFullDeadline(originalStartedNanos, aggregateMillis)
        val cases = DetektFullSemanticCases.cases
        check(cases.size == MAX_INVOCATIONS && cases.map { it.id }.distinct().size == MAX_INVOCATIONS)
        val suite = Files.createTempDirectory("ktc-detekt-full-native-seven-").toRealPath()
        println("Unqualified actual Detekt FULL / synthetic2.4.10 model evidence retained at $suite")
        writeJson(suite.resolve("intent.json"), buildJsonObject {
            put("schema", "ktchecker/detekt-full-seven-synthetic-model-experiment/1")
            put("consent", CONSENT); put("maximumInvocations", MAX_INVOCATIONS)
            put("aggregateMillis", aggregateMillis); put("childMillis", childMillis)
            put("actualGradleModelExport", false); put("standaloneKotlinCompilerInvocation", false); put("compilerEmissionExecuted", false)
            put("modeledCompiler", "2.4.10"); put("embeddedCompiler", "2.4.10")
            put("actualCurrentExporterPin", "2.4.21"); put("actualCurrentExporterDigest", GradleModelExporter.scriptDigest())
            put("currentExporterVersionGapRetained", true); put("allParentObligations", 1665); put("selectedDetektObligations", 276)
            put("catalogContract", DetektFullFixtures.OWNER_CATALOG_CONTRACT); put("catalogRuleIdsDigest", DetektFullFixtures.OWNER_RULE_IDS_DIGEST)
            put("completeResolutionProven", false); put("resourceOwnershipQualified", false)
            put("compatibilityQualified", false); put("releaseQualified", false)
            putJsonArray("schedule") { cases.forEach { add(caseJson(it)) } }
        })
        val completedCases = mutableListOf<String>()
        val actualExecutions = AtomicInteger(0)
        val reservations = AtomicInteger(0)
        var stage = "installation_capture"
        var fullyVerified = false
        var failure: String? = null
        try {
            deadline.check()
            val facts = DetektFullFixtureInstallation.native(deadline::check)
            val installation = facts.installation
            writeJson(suite.resolve("installation.json"), buildJsonObject {
                put("syntheticModelEnrollment", true); put("externalModeledCompilerArtifactsExecuted", false)
                put("installationDigest", installation.digest); put("wholeJdkImageDigest", installation.runtimeImageDigest)
                put("jdkHome", installation.jdkHome.toString()); put("javaExecutable", installation.javaExecutable.toString())
                put("javaExecutableDigest", installation.executableDigest)
                put("modeledCompilerVector", ContractJson.encodeToJsonElement(facts.compilerArtifacts))
                put("runtimeFileProjection", ContractJson.encodeToJsonElement(installation.runtimeArtifacts()))
                put("detektEngineArtifacts", ContractJson.encodeToJsonElement(installation.engineArtifacts))
                put("runtimeScope", "Live reused whole-image helper also binds directories/empty directories, release OS/arch/version and executable policy; FILE projection alone is not the image")
                put("runtimeLimitations", "Cooperative quiescent disk identity only; no loaded-code/JNI/hostile-same-UID/ABA attestation")
                put("qualified", false)
            })
            for ((index, case) in cases.withIndex()) {
                deadline.check()
                stage = "prepare:" + case.id
                val caseRoot = Files.createDirectory(suite.resolve((index + 1).toString().padStart(2, '0') + "-" + case.id))
                writeJson(caseRoot.resolve("authored-oracle.json"), caseJson(case))
                assertAuthoredRanges(case)
                DetektFullFixtures(case.sources, directory = caseRoot, retained = true, checkpoint = deadline::check).use { f ->
                    f.enrollInstallation(facts, extraDependency = false)
                    val model = f.importModel()
                    val original = f.parent(model)
                    val grants = DetektFullFixtures.Grants(installation, case.settings)
                    val prepared = DetektVerifiedFullModelBinding.prepare(f.origin(model), original, grants.provider, f.selected,
                        timeoutMillis = childMillis.toLong())
                    val archive = f.archive(prepared)
                    Files.writeString(caseRoot.resolve("synthetic-model.json"), ContractJson.encodeToString(model.export))
                    Files.writeString(caseRoot.resolve("original-input.json"), ContractJson.encodeToString(original))
                    Files.writeString(caseRoot.resolve("effective-input.json"), ContractJson.encodeToString(archive.effective))
                    Files.writeString(caseRoot.resolve("child-input.json"), ContractJson.encodeToString(archive.child))
                    Files.writeString(caseRoot.resolve("planned-detekt.yml"), prepared.plan.configuration)
                    writeJson(caseRoot.resolve("binding.json"), buildJsonObject {
                        put("binding", prepared.bindingIdentity.digest); put("argumentProjection", prepared.bindingIdentity.argumentProjectionDigest)
                        put("installation", prepared.bindingIdentity.installationDigest); put("wholeJdkImage", prepared.bindingIdentity.runtimeImageDigest)
                        put("originalKey", prepared.originalAnalysisKey); put("childKey", archive.child.analysisKey())
                        put("syntheticOriginAndArchive", true); put("actualGradleExport", false); put("qualified", false)
                    })
                    var outcomeCounted = false
                    try {
                        prepared.bindAttempt(original, archive.effective, archive.child, archive.verify).use { permit ->
                            grants.permit = permit
                            deadline.check()
                            check(reservations.get() == index && reservations.incrementAndGet() <= MAX_INVOCATIONS) {
                                "Closed schedule cannot add, duplicate or retry a native invocation"
                            }
                            stage = "execute:" + case.id
                            val result = grants.provider.analyze(archive.child)
                            Files.writeString(caseRoot.resolve("provider-result.json"), ContractJson.encodeToString(result))
                            val outcome = assertNotNull(grants.outcomes.singleOrNull(), "Exactly one actual runner outcome per reservation, with no fake or retry")
                            if (outcome.executionConfirmed) { actualExecutions.incrementAndGet(); outcomeCounted = true }
                            writeJson(caseRoot.resolve("process-outcome.json"), processJson(outcome))
                            stage = "verify:" + case.id
                            assertTrue(outcome.executionConfirmed && outcome.successfulExecution, "Actual native exec success required: ${outcome.reasons}")
                            assertNotNull(outcome.supervisorIdentity, "Owned supervisor identity is mandatory")
                            assertEquals(0, outcome.exitCode)
                            assertFalse(outcome.cancelled); assertFalse(outcome.timedOut); assertFalse(outcome.truncated)
                            val sarifBytes = outcome.report("detekt.sarif")
                            val actualConfig = outcome.report("detekt.yml")
                            Files.write(caseRoot.resolve("actual-detekt.sarif"), sarifBytes)
                            Files.write(caseRoot.resolve("actual-detekt.yml"), actualConfig)
                            assertEquals(prepared.plan.configurationDigest, Digests.sha256(actualConfig))
                            val report = DetektReportParser().parse(sarifBytes, archive.child)
                            assertEquals(setOf("detekt.potential-bugs.MissingUseCall"), report.enabledSarifIds,
                                "An omitted/disabled rule is not a valid negative control")
                            assertEquals(case.expected.size, report.diagnostics.size)
                            assertEquals(original, prepared.original)
                            assertEquals(1665, original.requiredRuleIds.size)
                            assertEquals(276, prepared.detektRuleIds.size)
                            assertEquals(f.wholeRuleIds, original.requiredRuleIds)
                            assertEquals(f.selected, result.coverage.map { it.ruleId })
                            assertEquals(original.context.compilerArguments, archive.child.context.compilerArguments)
                            assertEquals(original.context.compilationFiles, archive.child.context.compilationFiles)
                            assertEquals("", archive.child.context.scope.target)
                            assertEquals("2.4.10", archive.child.context.compilerVersion)
                            assertEquals(Digests.sha256("{}"), original.context.policies["ktchecker.execution-plan"])
                            assertEquals("partial", result.status); assertFalse(result.complete()); assertFalse(result.completeFor(archive.child))
                            assertTrue(result.coverage.all { !it.tested && !it.qualified && it.certificateId == null && !it.successful() })
                            val observation = result.coverage.single { it.ruleId == DETEKT_FULL_MISSING_USE_CALL }
                            assertEquals("executed", observation.provenance); assertTrue(observation.implemented)
                            assertEquals(if (case.expected.isEmpty()) "unknown" else "findings", observation.state)
                            assertEquals("semantic", observation.depth)
                            val inactive = result.coverage.filter { it.ruleId != DETEKT_FULL_MISSING_USE_CALL }
                            assertEquals(275, inactive.size)
                            assertTrue(inactive.all { it.provenance == "not_executed" && !it.implemented })
                            assertEquals(setOf("excluded", "unsupported", "unavailable"), inactive.map { it.state }.toSet())
                            val actualRanges = result.findings.map { finding ->
                                assertEquals(DETEKT_FULL_MISSING_USE_CALL, finding.ruleId)
                                assertEquals("warning", finding.severity); assertEquals("advisory", finding.kind); assertEquals("low", finding.certainty)
                                assertEquals("potential-bugs:MissingUseCall", finding.upstreamId)
                                assertEquals(archive.child.context.scope, finding.context)
                                assertFalse(finding.location.path.startsWith("src/test/"))
                                assertEquals(f.snapshot.files.single { it.path == finding.location.path }.digest, finding.location.fileDigest)
                                assertEquals(Digests.sha256(sarifBytes), finding.evidence.single().digest)
                                assertTrue(result.rawDiagnostics.any { it.upstreamId == "detekt.potential-bugs.MissingUseCall" && it.location == finding.location })
                                val location = finding.location
                                listOf(location.path, location.line.toString(), location.column.toString(), location.endLine.toString(), location.endColumn.toString()).joinToString(":")
                            }.sorted()
                            val expectedRanges = case.expected.map { listOf(it.path, it.line.toString(), it.column.toString(), it.endLine.toString(), it.endColumn.toString()).joinToString(":") }.sorted()
                            assertEquals(expectedRanges, actualRanges, "Pre-authored ranges cannot be learned or relaxed from the detector's output")
                            assertTrue("detekt_complete_type_resolution_not_proven" in result.reasons)
                            assertTrue("detekt_full_initial_rule_slice_not_release_scope" in result.reasons)
                            permit.verify(grants.provider, archive.child)
                            archive.verify(); deadline.check()
                            completedCases += case.id
                        }
                        assertFalse(requireNotNull(grants.permit).accepts(archive.child))
                    } finally {
                        if (!outcomeCounted) actualExecutions.addAndGet(grants.outcomes.count { it.executionConfirmed })
                        writeJson(caseRoot.resolve("all-process-outcomes.json"), JsonArray(grants.outcomes.map(::processJson)))
                        writeJson(caseRoot.resolve("case-status.json"), buildJsonObject {
                            put("case", case.id); put("stage", stage); put("observationVerified", case.id in completedCases)
                            put("syntheticModel", true); put("qualified", false); put("releaseQualified", false)
                        })
                    }
                }
            }
            deadline.check()
            assertEquals(MAX_INVOCATIONS, reservations.get()); assertEquals(MAX_INVOCATIONS, actualExecutions.get())
            assertEquals(cases.map { it.id }, completedCases)
            stage = "seven_observations_verified_not_qualified"
            fullyVerified = true
        } catch (problem: Throwable) {
            failure = problem.javaClass.name + ": " + problem.message.orEmpty().take(4096)
            throw problem
        } finally {
            writeJson(suite.resolve("suite-status.json"), buildJsonObject {
                put("stage", stage); put("failure", failure); put("observationChecksVerified", fullyVerified)
                put("reservedInvocations", reservations.get()); put("actualExecutionConfirmed", actualExecutions.get())
                put("elapsedMillis", (System.nanoTime() - originalStartedNanos) / 1_000_000)
                putJsonArray("completedCases") { completedCases.forEach { add(it) } }
                put("actualGradleModelExport", false); put("standaloneKotlinCompilerInvocation", false); put("compilerEmissionExecuted", false)
                put("requiredParentObligationCount", 1665); put("all1665ParentObligationsVerified", fullyVerified)
                put("catalogContract", DetektFullFixtures.OWNER_CATALOG_CONTRACT); put("catalogRuleIdsDigest", DetektFullFixtures.OWNER_RULE_IDS_DIGEST)
                put("currentExporter2_4_10SuccessClaimed", false)
                put("completeResolutionProven", false); put("compatibilityQualified", false); put("releaseQualified", false)
            })
        }
    }

    private fun number(key: String, default: Int, range: IntRange): Int {
        val raw = System.getProperty(key)
        val value = if (raw == null) default else raw.toIntOrNull() ?: error("Invalid $key")
        check(value in range) { "$key must stay in $range" }
        return value
    }

    private class OriginalFullDeadline(private val started: Long, private val maxMillis: Int) {
        fun check() {
            if (Thread.currentThread().isInterrupted) throw ExecutionProblem("detekt_full_owner_cancelled", "Original owner cancelled the seven-invocation experiment")
            if ((System.nanoTime() - started) / 1_000_000 >= maxMillis) {
                throw ExecutionProblem("detekt_full_owner_deadline_expired", "Original aggregate capture-to-final-verification deadline expired; no refresh or retry")
            }
        }
    }

    private fun assertAuthoredRanges(case: DetektFullSemanticCase) {
        for (range in case.expected) {
            assertEquals(range.line, range.endLine)
            assertEquals(range.expression, case.sources.getValue(range.path).lines()[range.line - 1].substring(range.column, range.endColumn))
        }
    }

    private fun caseJson(case: DetektFullSemanticCase): JsonObject = buildJsonObject {
        put("id", case.id); put("semanticControl", case.semanticControl); put("expectedFindings", case.expected.size)
        put("oracleAuthoredBeforeExecution", true); put("qualified", false)
        putJsonArray("ignoreClasses") { case.settings.ignoreClasses.forEach { add(it) } }
        putJsonArray("sources") { case.sources.toSortedMap().forEach { (path, source) -> add(buildJsonObject {
            put("path", path); put("digest", Digests.sha256(source)); put("bytes", source.toByteArray().size)
        }) } }
        putJsonArray("ranges") { case.expected.forEach { range -> add(buildJsonObject {
            put("path", range.path); put("line", range.line); put("column", range.column)
            put("endLine", range.endLine); put("endColumn", range.endColumn); put("expression", range.expression)
        }) } }
    }

    private fun processJson(outcome: ProcessOutcome): JsonObject = buildJsonObject {
        put("syntheticModelOnly", true); put("qualified", false)
        put("status", outcome.status); put("executionConfirmed", outcome.executionConfirmed)
        put("exitCode", outcome.exitCode); put("cancelled", outcome.cancelled); put("timedOut", outcome.timedOut); put("truncated", outcome.truncated)
        put("stdout", outcome.stdout); put("stderr", outcome.stderr); put("outputDirectory", outcome.outputDirectory?.toString())
        put("supervisorIdentity", outcome.supervisorIdentity?.toString())
        put("metrics", ContractJson.encodeToJsonElement(outcome.metrics))
        putJsonArray("reasons") { outcome.reasons.forEach { add(it) } }
        putJsonArray("command") { outcome.command.forEach { add(it) } }
    }

    private fun writeJson(path: Path, document: JsonElement) { Files.writeString(path, document.toString() + "\n") }

    companion object {
        const val CONSENT = "missing-use-call-seven-2.4.10"
        const val MAX_INVOCATIONS = 7
    }
}
