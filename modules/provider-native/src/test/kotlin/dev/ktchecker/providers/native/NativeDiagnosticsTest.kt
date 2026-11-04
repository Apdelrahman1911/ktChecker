package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlin.test.*

/** Synthetic named-diagnostic transport tests; no JVM substitution for actual Apple execution. */
class NativeDiagnosticsTest {
    @Test fun `all 344 common native and IR IDs retain exact names but no JVM mappings`() {
        assertEquals(344, NativeCatalog.rules.size)
        assertEquals(292, NativeCatalog.rules.count { it.ruleId.startsWith("ktc.compiler.common.") })
        assertEquals(43, NativeCatalog.rules.count { it.ruleId.startsWith("ktc.compiler.native.") })
        assertEquals(9, NativeCatalog.rules.count { it.ruleId.startsWith("ktc.compiler.ir.") })
        assertEquals(setOf("FirErrors", "FirNativeErrors", "IrActualizationErrors"), NativeCatalog.rules.map { it.family }.toSet())
        assertTrue(NativeCatalog.rules.none { it.ruleId.startsWith("ktc.compiler.jvm.") })
        for (rule in NativeCatalog.rules) {
            assertEquals(rule, NativeCatalog.byId[rule.ruleId])
            assertEquals(rule, NativeCatalog.byName[rule.upstreamId])
            val diagnostic = NativeDiagnosticParser.parse("", "e: file:///frozen/src/commonMain/Api.kt:1:1 [${rule.upstreamId}] synthetic transport").diagnostics.single()
            assertEquals(rule.upstreamId, diagnostic.name)
            assertEquals(1, diagnostic.line)
            assertEquals(0, diagnostic.column)
            assertEquals("error", diagnostic.severity)
        }
        assertEquals(NativeCatalog.byId.keys, KotlinNativeProvider().supportedRuleIds)
    }

    @Test fun `current legacy direct and URI diagnostic formats retain one based lines and zero based columns`() {
        for (line in listOf(
            "e: file:///snapshot/src/commonMain/Api.kt:2:4 [ACTUAL_WITHOUT_EXPECT] precise diagnosis",
            "error: /snapshot/src/commonMain/Api.kt: (2, 4): [ACTUAL_WITHOUT_EXPECT] precise diagnosis",
            "/snapshot/src/commonMain/Api.kt:2:4: error: [ACTUAL_WITHOUT_EXPECT] precise diagnosis",
            "\u001B[31me: file:///snapshot/src/commonMain/Api.kt:2:4: [ACTUAL_WITHOUT_EXPECT] precise diagnosis\u001B[0m",
        )) {
            val diagnostic = NativeDiagnosticParser.parse(line, "").diagnostics.single()
            assertEquals("ACTUAL_WITHOUT_EXPECT", diagnostic.name, line)
            assertEquals("precise diagnosis", diagnostic.message)
            assertEquals(2, diagnostic.line)
            assertEquals(3, diagnostic.column)
            assertEquals("error", diagnostic.severity)
        }
        assertEquals("warning", NativeDiagnosticParser.parse("w: [DEPRECATION] warning", "").diagnostics.single().severity)
        assertEquals("info", NativeDiagnosticParser.parse("info: [INFO] information", "").diagnostics.single().severity)
    }

    @Test fun `unnamed unknown and global diagnostics are preserved not guessed from text`() {
        val parsed = NativeDiagnosticParser.parse("", """
            e: /snapshot/Api.kt:1:1 actual declaration has no corresponding expected declaration
            e: /snapshot/Api.kt:1:1 [NEW_UNKNOWN_DIAGNOSTIC] unknown ID
            error: [ACTUAL_WITHOUT_EXPECT] no source position
        """.trimIndent())
        assertEquals(3, parsed.diagnostics.size)
        assertNull(parsed.diagnostics[0].name)
        assertEquals("NEW_UNKNOWN_DIAGNOSTIC", parsed.diagnostics[1].name)
        assertNull(parsed.diagnostics[1].name?.let { NativeCatalog.byName[it] })
        assertNull(parsed.diagnostics[2].path)
        assertFalse(parsed.buildSuccessful)
    }

    @Test fun `invalid positions do not become fabricated locations`() {
        for (line in listOf("e: /snapshot/Api.kt:0:1 [ACTUAL_WITHOUT_EXPECT] invalid",
            "e: /snapshot/Api.kt:1:0 [ACTUAL_WITHOUT_EXPECT] invalid",
            "e: /snapshot/Api.kt:999999999999999:1 [ACTUAL_WITHOUT_EXPECT] invalid")) {
            assertFailsWith<ExecutionProblem> { NativeDiagnosticParser.parse(line, "") }
        }
    }

    @Test fun `skipped cached no-source and aggregate Gradle tasks never prove target execution`() {
        val parsed = NativeDiagnosticParser.parse("""
            > Task :compileCommonMainKotlinMetadata UP-TO-DATE
            > Task :compileKotlinIosArm64 FROM-CACHE
            > Task :compileKotlinIosSimulatorArm64 SKIPPED
            > Task :library:compileKotlinIosArm64 NO-SOURCE
            > Task :other:compileKotlinIosArm64 FAILED
            > Task :actual:compileKotlinIosSimulatorArm64
            BUILD SUCCESSFUL in 1s
        """.trimIndent(), "")
        assertEquals(6, parsed.tasks.size)
        assertTrue(parsed.tasks.take(4).none { it.actuallyExecuted })
        assertTrue(parsed.tasks.takeLast(2).all { it.actuallyExecuted })
        assertTrue(parsed.buildSuccessful)
        assertEquals(setOf("compileCommonMainKotlinMetadata", "compileKotlinIosArm64"), NativeRunPlan("iosArm64", emptyList()).requiredTasks)
        assertEquals(setOf("compileCommonMainKotlinMetadata", "compileKotlinIosSimulatorArm64"), NativeRunPlan("iosSimulatorArm64", emptyList()).requiredTasks)
    }

    @Test fun `Linux host absence is explicit for every rule in both actual iOS target contexts`() {
        val host = System.getProperty("os.name").lowercase()
        if (host.contains("mac") || host.contains("darwin")) return // Real Apple execution is a separate fixture task, never an os.name simulation.
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val input = input(target)
            val provider = KotlinNativeProvider(plan = NativeRunPlan(target, emptyList()))
            val result = provider.analyze(input)
            assertEquals("unavailable", result.status)
            assertEquals(listOf("apple_native_execution_host_missing"), result.reasons)
            assertEquals(344, result.coverage.size)
            assertTrue(result.findings.isEmpty())
            assertTrue(result.coverage.all { it.context.target == target && it.provenance == "not_executed" &&
                it.certificateId == null && !it.successful() && !it.tested && !it.qualified })
            assertFalse(result.complete())
        }
    }

    @Test fun `native never relabels JVM semantics as ios coverage and approval never grants execution`() {
        val jvm = KotlinNativeProvider(plan = NativeRunPlan("iosArm64", emptyList())).analyze(input("jvm"))
        assertEquals("unavailable", jvm.status)
        assertEquals(listOf("native_ios_target_model_required"), jvm.reasons)
        val noPlan = KotlinNativeProvider().analyze(input("iosArm64"))
        assertEquals(listOf("native_trusted_plan_unavailable"), noPlan.reasons)
        assertTrue(jvm.coverage.none { it.successful() })
        assertTrue(noPlan.coverage.none { it.successful() })
    }

    @Test fun `driver always records a prerequisite failure without fabricating snapshot evidence`() {
        val work = Files.createTempDirectory("ktchecker-native-driver-test-")
        val report = work.resolve("result.json")
        NativeFixtureMain.main(arrayOf("--fixture", work.resolve("missing").toString(), "--case", "positive",
            "--target", "iosArm64", "--result", report.toString()))
        val result = ContractJson.decodeFromString<FixtureCheckEnvelope>(Files.readString(report))
        assertEquals("unavailable", result.state)
        assertEquals("", result.snapshotId)
        assertEquals("unavailable", result.providerResult.status)
        assertTrue(result.providerResult.coverage.isEmpty())
        assertTrue(result.assertions.any { it == "No source snapshot was captured." })
    }

    private fun input(target: String): AnalysisInput = AnalysisInput(
        SnapshotManifest(Digests.sha256("synthetic descriptor, never executed"), "fixture", "native-parser", emptyList()),
        Path.of(System.getProperty("java.io.tmpdir")).toString(),
        AnalysisContext(RuleContext(target, "commonMain"), compilerVersion = "2.4.21"),
        execution = TrustedExecution("/bin/true", approved = true),
    )
}
