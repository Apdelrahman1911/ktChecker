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
        assertTrue(NativeRunPlan("iosArm64", emptyList()).requiredTasks.isEmpty())
        assertTrue(NativeRunPlan("iosSimulatorArm64", emptyList()).requiredTasks.isEmpty())
        assertNull(NativeRunPlan("iosArm64", emptyList()).compilationModel)
    }

    @Test fun `task admission and proof require exact selected module common and ios paths`() {
        for ((target, task) in listOf("iosArm64" to "compileKotlinIosArm64", "iosSimulatorArm64" to "compileKotlinIosSimulatorArm64")) {
            val required = setOf(":selected:compileCommonMainKotlinMetadata", ":selected:$task")
            val plan = NativeRunPlan(target, emptyList(), requiredTasks = required)
            requireNativeTaskSelection(plan, required.toList(), ":selected")
            assertEquals("gradle_exact_task_not_selected", assertFailsWith<ExecutionProblem> {
                requireNativeTaskSelection(plan, required.map { it.replace(":selected:", ":other:") }, ":selected")
            }.code)
            val unrelated = NativeDiagnosticParser.parse(
                "> Task :other:compileCommonMainKotlinMetadata\n> Task :other:$task\nBUILD SUCCESSFUL", "")
            assertEquals(required.toList(), ExactGradleTaskProof.missingExecution(required, unrelated.tasks))
            val failed = NativeDiagnosticParser.parse("> Task :selected:$task\n> Task :other:$task FAILED", "")
            assertTrue(ExactGradleTaskProof.hasUnrelatedFailure(required, failed.tasks))
        }
    }

    @Test fun `partial or basename Native task models do not prove common and selected ios compilation`() {
        assertEquals("native_required_task_model_incomplete", assertFailsWith<ExecutionProblem> {
            val plan = NativeRunPlan("iosArm64", emptyList(), requiredTasks = setOf(":selected:compileKotlinIosArm64"))
            requireNativeTaskSelection(plan, plan.requiredTasks.toList(), ":selected")
        }.code)
        assertEquals("gradle_exact_task_model_required", assertFailsWith<ExecutionProblem> {
            val plan = NativeRunPlan("iosArm64", emptyList(), requiredTasks = setOf("compileKotlinIosArm64"))
            requireNativeTaskSelection(plan, plan.requiredTasks.toList(), ":selected")
        }.code)
    }

    @Test fun `pure Native factory keeps common file findings in the exact selected ios leaf context`() {
        val fingerprints = mutableSetOf<String>()
        for (target in listOf("iosArm64", "iosSimulatorArm64")) {
            val input = findingInput(target)
            val model = ExactCompilationModel(input.context.scope, input.context.modelDigest!!,
                mapOf("src/commonMain/Api.kt" to "commonMain"))
            val factory = NativeFindingFactory(input, NativeRunPlan(target, emptyList(), compilationModel = model))
            val rule = NativeCatalog.byName.getValue("ACTUAL_WITHOUT_EXPECT")
            val diagnostic = NativeDiagnostic(rule.upstreamId, "error", "synthetic factory regression", "src/commonMain/Api.kt", 2, 3)
            val location = SourceLocation("src/commonMain/Api.kt", input.snapshot.files.first().digest, 2, 3)
            val finding = factory.create(rule, diagnostic, location, Digests.sha256("synthetic output, never executed"), emptyList())
            assertEquals(input.context.scope, finding.context)
            assertEquals("iosMain", finding.context.sourceSet)
            assertEquals(location, finding.location)
            assertEquals(input.analysisKey(), finding.semanticDigest)
            assertEquals("kotlin-native", finding.providerId)
            val ownership = finding.evidence.single { it.kind == "compilation_source_membership" }
            assertEquals(location.path, ownership.artifactRef)
            assertEquals(model.modelDigest, ownership.digest)
            assertContains(ownership.summary, "Imported source set commonMain;")
            assertContains(ownership.summary, "selected analysis context ${input.context.scope.id};")
            assertContains(ownership.summary, "immutable snapshot ${input.snapshot.snapshotId};")
            assertContains(ownership.summary, "informational ownership only, not cross-context suppression authority")
            fingerprints += finding.fingerprint
        }
        assertEquals(2, fingerprints.size, "The same common location in distinct consumers must not merge contexts")
    }

    @Test fun `pure Native factory cannot normalize a captured sibling or a different imported scope`() {
        val input = findingInput("iosArm64")
        val model = ExactCompilationModel(input.context.scope, input.context.modelDigest!!,
            mapOf("src/commonMain/Api.kt" to "commonMain"))
        val plan = NativeRunPlan("iosArm64", emptyList(), compilationModel = model)
        val factory = NativeFindingFactory(input, plan)
        val rule = NativeCatalog.byName.getValue("ACTUAL_WITHOUT_EXPECT")
        val diagnostic = NativeDiagnostic(rule.upstreamId, "error", "synthetic sibling regression", "src/otherMain/Sibling.kt", 1, 0)
        val sibling = SourceLocation("src/otherMain/Sibling.kt", input.snapshot.files.last().digest)
        assertEquals("location_outside_compilation", assertFailsWith<ExecutionProblem> {
            factory.create(rule, diagnostic, sibling, Digests.sha256("synthetic output, never executed"), emptyList())
        }.code)
        assertEquals("compilation_model_binding_mismatch", assertFailsWith<ExecutionProblem> {
            NativeFindingFactory(input, plan.copy(compilationModel = model.copy(scope = model.scope.copy(sourceSet = "commonMain"))))
        }.code)
    }

    @Test fun `pure Native factory refuses findings outside an exact requested subset`() {
        val selected = NativeCatalog.byName.getValue("ACTUAL_WITHOUT_EXPECT")
        val unselected = NativeCatalog.byName.getValue("TYPE_MISMATCH")
        val input = findingInput("iosArm64", listOf(selected.ruleId))
        val model = ExactCompilationModel(input.context.scope, input.context.modelDigest!!,
            mapOf("src/commonMain/Api.kt" to "commonMain"))
        val factory = NativeFindingFactory(input, NativeRunPlan("iosArm64", emptyList(), compilationModel = model))
        val diagnostic = NativeDiagnostic(unselected.upstreamId, "error", "synthetic unselected compiler error", "src/commonMain/Api.kt", 2, 3)
        assertEquals("native_unselected_diagnostic:${unselected.ruleId}", assertFailsWith<ExecutionProblem> {
            factory.create(unselected, diagnostic, SourceLocation("src/commonMain/Api.kt", input.snapshot.files.first().digest, 2, 3),
                Digests.sha256("synthetic output, never executed"), emptyList())
        }.code)
    }

    @Test fun `pure Native subset normalizer retains mixed unselected compiler output without selected certificates`() {
        val selected = NativeCatalog.byName.getValue("ACTUAL_WITHOUT_EXPECT")
        val unselectedError = NativeCatalog.byName.getValue("TYPE_MISMATCH")
        val unselectedWarning = NativeCatalog.byName.getValue("DEPRECATION")
        val input = findingInput("iosArm64", listOf(selected.ruleId))
        val model = ExactCompilationModel(input.context.scope, input.context.modelDigest!!,
            mapOf("src/commonMain/Api.kt" to "commonMain"))
        val factory = NativeFindingFactory(input, NativeRunPlan("iosArm64", emptyList(), compilationModel = model))
        val location = SourceLocation("src/commonMain/Api.kt", input.snapshot.files.first().digest, 2, 3)
        val digest = Digests.sha256("synthetic output, never executed")
        val selectedDiagnostic = NativeDiagnostic(selected.upstreamId, "warning", "synthetic selected warning", location.path, 2, 3)
        val otherError = NativeDiagnostic(unselectedError.upstreamId, "error", "synthetic unselected error", location.path, 2, 3)
        val otherWarning = NativeDiagnostic(unselectedWarning.upstreamId, "warning", "synthetic unselected warning", location.path, 2, 3)
        var resolved = 0
        val mixed = factory.normalize(listOf(selectedDiagnostic, otherError, otherWarning), digest, emptyList()) {
            resolved++
            location
        }
        assertEquals(1, resolved, "Unselected compiler IDs cannot pass through finding construction")
        assertEquals(listOf(selected.ruleId), mixed.findings.map { it.ruleId })
        assertEquals(listOf(unselectedError.upstreamId, unselectedWarning.upstreamId), mixed.rawDiagnostics.map { it.upstreamId })
        assertTrue(mixed.errors.isEmpty(), "Known unrelated compiler output is an incomplete selection, not malformed transport")
        assertEquals(listOf("native_unselected_diagnostic:${unselectedError.ruleId}", "native_unselected_diagnostic:${unselectedWarning.ruleId}"), mixed.selectionGaps)
        assertFalse(mixed.exactSelectedOutput)
        assertTrue(mixed.hasReportedError, "A raw unselected error still prevents clean selected-output evidence")
        val warningOnly = factory.normalize(listOf(otherWarning), digest, emptyList()) { fail("Unselected warning must stay raw") }
        assertTrue(warningOnly.errors.isEmpty())
        assertFalse(warningOnly.exactSelectedOutput)
        assertFalse(warningOnly.hasReportedError)
        val selectedOnly = factory.normalize(listOf(selectedDiagnostic), digest, emptyList()) { location }
        assertTrue(selectedOnly.exactSelectedOutput)
        assertTrue(selectedOnly.rawDiagnostics.isEmpty())
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

    /** In-memory factory inputs only: no OS spoof, executable, source capture, or Native run. */
    private fun findingInput(target: String, requested: List<String> = emptyList()): AnalysisInput = AnalysisInput(
        SnapshotManifest(Digests.sha256("synthetic factory descriptor, never executed"), "fixture", "native-factory",
            listOf("src/commonMain/Api.kt", "src/otherMain/Sibling.kt").map { SnapshotFile(it, Digests.sha256(it), 1) }),
        "/unused-pure-factory", AnalysisContext(RuleContext(target, "iosMain", ":selected", "main"),
            compilerVersion = "2.4.21", modelDigest = Digests.sha256("synthetic imported model"),
            dependencyDigest = Digests.sha256("synthetic dependencies"), toolchainDigest = Digests.sha256("synthetic toolchain"),
            configurationDigest = Digests.sha256("synthetic configuration"), fullProject = true,
            compilationFiles = listOf("src/commonMain/Api.kt")), requested,
    )
}
