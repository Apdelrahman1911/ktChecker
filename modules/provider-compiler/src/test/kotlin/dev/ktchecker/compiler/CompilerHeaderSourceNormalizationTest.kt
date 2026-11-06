package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Reflection-only parser/normalizer publication controls; NO compiler or worker is launched.
 * Source-shaped output, version banners and ProcessOutcome execution flags are synthetic.
 * These controls are NOT an actual compiler reproduction, per-rule qualification, runner
 * wiring test, end-to-end invocation, or evidence of real-world precision/recall.
 *
 * Partial results may publish unambiguous genuine-looking synthetic diagnostics. Therefore
 * denying a coverage certificate alone is insufficient: an ambiguous source echo must also
 * never be promoted into an actionable finding. No synthetic qualifications are installed.
 */
class CompilerHeaderSourceNormalizationTest {
    @Test fun supportedMemberEchoCannotPublishASecondMappedFinding() {
        for (style in Style.entries) withFixture({ path -> listOf(shadowRow(style, path)) }) { fixture ->
            assertGenuineControl(fixture)
            val result = normalize(fixture, listOf(BANNER, fixture.abstractHeader(style), MEMBER,
                fixture.ambiguousRows.single(), fixture.caret(), fixture.zeroHeader()))

            assertMalformedUseful(fixture, result, listOf(ABSTRACT_RULE, ZERO_RULE))
            assertQuarantined(result, FAKE_ID, 1)
            assertNull(result.findings.single { it.ruleId == ABSTRACT_RULE }.fixHint,
                "An ambiguous source/caret fence cannot certify an abstract-member hint")
        }
    }

    @Test fun unsupportedContinuationCannotLoseTheEchoWitness() {
        for (style in Style.entries) withFixture({ path -> listOf(shadowRow(style, path)) }) { fixture ->
            assertGenuineControl(fixture)
            val unsupported = "fun answer(): kotlin.collections.List<Int>"
            val result = normalize(fixture, listOf(BANNER, fixture.abstractHeader(style), unsupported,
                fixture.ambiguousRows.single(), fixture.caret(), fixture.zeroHeader()))

            assertMalformedUseful(fixture, result, listOf(ABSTRACT_RULE, ZERO_RULE))
            assertQuarantined(result, FAKE_ID, 1)
            assertTrue(result.rawDiagnostics.any { it.message == unsupported })
            assertNull(result.findings.single { it.ruleId == ABSTRACT_RULE }.fixHint)
        }
    }

    @Test fun laterLocationWitnessQuarantinesAnEarlierEcho() {
        for (style in Style.entries) withFixture({ path -> listOf(shadowRow(style, path)) }) { fixture ->
            assertGenuineControl(fixture)
            val echo = fixture.ambiguousRows.single()
            val result = normalize(fixture, listOf(BANNER, echo, fixture.zeroHeader(),
                fixture.abstractHeader(style), MEMBER, echo, fixture.caret()))

            assertMalformedUseful(fixture, result, listOf(ZERO_RULE, ABSTRACT_RULE))
            assertQuarantined(result, FAKE_ID, 2)
            assertNull(result.findings.single { it.ruleId == ABSTRACT_RULE }.fixHint)
        }
    }

    @Test fun ordinaryExpectedSourceEchoRemainsRawAndIncomplete() {
        for (style in Style.entries) withFixture({ path -> listOf(shadowRow(style, path)) }) { fixture ->
            assertGenuineControl(fixture)
            // Deliberately synthetic: exercise the ordinary expected-source branch, not the
            // abstract-member branch or a claim that this Kotlin row causes division by zero.
            val ordinary = header(style, fixture.sourcePath, 3, 1, "warning", ZERO_ID, ZERO_MESSAGE)
            val result = normalize(fixture, listOf(BANNER, ordinary, fixture.ambiguousRows.single(),
                "^", fixture.zeroHeader()))

            assertMalformedUseful(fixture, result, listOf(ZERO_RULE, ZERO_RULE))
            assertQuarantined(result, FAKE_ID, 1)
            assertEquals(listOf(3, fixture.zeroLine), result.findings.map { it.location.line })
        }
    }

    @Test fun selfCollidingLocatedHeaderNeverBecomesActionable() =
        withFixture({ path -> listOf(shadowRow(Style.COLON, path, reportedLine = 3)) }) { fixture ->
            assertGenuineControl(fixture)
            val result = normalize(fixture, listOf(BANNER, fixture.ambiguousRows.single(), fixture.zeroHeader()))

            assertMalformedUseful(fixture, result, listOf(ZERO_RULE))
            assertQuarantined(result, FAKE_ID, 1)
        }

    @Test fun sourceVersionCannotReplaceAnUnambiguousBanner() =
        withFixture({ listOf(OTHER_BANNER) }) { fixture ->
            assertGenuineControl(fixture)
            val witness = header(Style.COLON, fixture.sourcePath, 3, 1, "warning", ZERO_ID, ZERO_MESSAGE)
            val result = normalize(fixture, listOf(BANNER, witness, OTHER_BANNER, "^", fixture.zeroHeader()))

            assertMalformedUseful(fixture, result, listOf(ZERO_RULE, ZERO_RULE))
            assertFalse("compiler_version_transport_mismatch" in result.reasons)
            assertTrue(result.rawDiagnostics.any { it.location == null &&
                it.message.contains("kotlinc-jvm 2.4.20") })
        }

    @Test fun ambiguousVersionCannotCreateExecutionTrust() =
        withFixture({ listOf(BANNER) }) { fixture ->
            assertGenuineControl(fixture)
            val witness = header(Style.COLON, fixture.sourcePath, 3, 1, "warning", ZERO_ID, ZERO_MESSAGE)
            val result = normalize(fixture, listOf(BANNER, fixture.zeroHeader(), witness, BANNER))

            assertEquals("partial", result.status)
            assertTrue(result.findings.isEmpty())
            assertTrue("compiler_version_transport_mismatch" in result.reasons)
            assertTrue("unparsed_or_malformed_compiler_output" in result.reasons)
            assertTrue(result.rawDiagnostics.count { it.upstreamId == ZERO_ID && it.location != null } == 2)
            assertFailedCoverage(fixture, result, "compiler_execution_or_version_untrusted", "not_executed")
        }

    @Test fun censusOverflowCannotLeavePrefixFindingsTrusted() =
        withFixture({ path -> (0 until 65).map { index -> shadowRow(Style.COLON, path) + " #$index" } }) { fixture ->
            assertGenuineControl(fixture)
            assertEquals(65, fixture.ambiguousRows.distinct().size)
            assertTrue(fixture.ambiguousRows.all { it.length <= CompilerDiagnosticParser.MAX_MESSAGE })
            assertTrue(fixture.ambiguousRows.take(64).sumOf { it.length } <= 4 * CompilerDiagnosticParser.MAX_MESSAGE)
            val witnesses = fixture.ambiguousRows.indices.map { index ->
                header(Style.COLON, fixture.sourcePath, index + 3, 1, "error", FAKE_ID,
                    "Unresolved reference 'witness_$index'.")
            }
            val result = normalize(fixture, listOf(BANNER, fixture.zeroHeader()) + witnesses +
                fixture.ambiguousRows.takeLast(1))

            assertEquals("partial", result.status)
            assertTrue(result.findings.isEmpty(), "An overflow cannot certify independence of the earlier prefix")
            assertTrue("compiler_version_transport_mismatch" in result.reasons)
            assertTrue("unparsed_or_malformed_compiler_output" in result.reasons)
            val expectedRaw = listOf(RawDiagnostic(LIMIT_MARKER, "info"), RawDiagnostic(BANNER, "info"),
                RawDiagnostic("[$ZERO_ID] $ZERO_MESSAGE", "warning", ZERO_ID)) +
                fixture.ambiguousRows.indices.map { index ->
                    RawDiagnostic("[$FAKE_ID] Unresolved reference 'witness_$index'.", "error", FAKE_ID)
                } + RawDiagnostic("[$FAKE_ID] ${'$'}{object : Contract {}} #64", "error", FAKE_ID)
            assertEquals(69, result.rawDiagnostics.size, "68 nonblank input messages plus the explicit parser-limit marker")
            assertEquals(expectedRaw, result.rawDiagnostics,
                "Quarantine retains prefix/version, every witness and the final echo in order; it never silently drops rows")
            assertFailedCoverage(fixture, result, "compiler_execution_or_version_untrusted", "not_executed")
        }

    @Test fun genuineHeaderAndSourceControlRetainsUsefulDiagnostics() =
        withFixture({ listOf("ordinary raw-string payload") }) { fixture ->
            val result = assertGenuineControl(fixture)
            assertEquals(listOf(fixture.missingLine, fixture.zeroLine), result.findings.map { it.location.line })
            assertTrue(result.findings.all { it.fixHint == null })
        }

    @Test fun unambiguousMemberFenceCanPublishTheNarrowHint() =
        withFixture({ listOf("${'$'}{object : Contract {}}") }) { fixture ->
            val result = normalize(fixture, listOf(BANNER, fixture.abstractHeader(Style.COLON), MEMBER,
                fixture.ambiguousRows.single(), fixture.caret(), fixture.zeroHeader()))
            assertEquals(listOf(ABSTRACT_RULE, ZERO_RULE), result.findings.map { it.ruleId })
            val finding = result.findings.first()
            assertEquals(ABSTRACT_MESSAGE + "\n" + MEMBER, finding.diagnosis)
            assertEquals("Implement the listed abstract members with compatible overrides, or inherit compatible concrete " +
                "implementations. Recompile in the same context to verify.", finding.fixHint)
            val verification = finding.verification.single()
            assertEquals("exact_context_recompile", verification.kind)
            assertEquals("not_run", verification.state)
            assertTrue(verification.command.isEmpty(), "A hint is not an authorized post-edit execution command")
            assertTrue(result.rawDiagnostics.isEmpty())
            assertEquals("partial", result.status)
            assertEquals(RULES.map { "exact_rule_context_tuple_not_qualified:$it" }, result.reasons)
            assertTrue(result.coverage.all { it.state == "unqualified" && it.certificateId == null &&
                !it.tested && !it.qualified })
            assertFalse(result.completeFor(fixture.input))
        }

    private fun assertGenuineControl(fixture: Fixture): ProviderResult {
        // Both selected mappings demonstrably reach publication without a learned collision.
        // The fixture contains other ambiguous source rows, but no whole-source census is claimed.
        val result = normalize(fixture, listOf(BANNER, fixture.missingHeader(), MISSING_ROW,
            " ".repeat(MISSING_ROW.indexOf("missing")) + "^", fixture.zeroHeader(), ZERO_ROW,
            " ".repeat(ZERO_ROW.indexOf('/')) + "^"))
        assertEquals(listOf(FAKE_RULE, ZERO_RULE), result.findings.map { it.ruleId })
        assertEquals(listOf(FAKE_ID, ZERO_ID), result.findings.map { it.upstreamId })
        assertTrue(result.rawDiagnostics.isEmpty())
        assertEquals("partial", result.status)
        assertEquals(RULES, result.coverage.map { it.ruleId })
        assertTrue(result.coverage.all { it.state == "unqualified" && it.certificateId == null &&
            !it.tested && !it.qualified })
        assertEquals(RULES.map { "exact_rule_context_tuple_not_qualified:$it" }, result.reasons)
        assertFalse(result.completeFor(fixture.input))
        return result
    }

    private fun assertMalformedUseful(fixture: Fixture, result: ProviderResult, rules: List<String>) {
        assertEquals("partial", result.status)
        assertEquals(rules, result.findings.map { it.ruleId })
        assertTrue(result.findings.none { it.ruleId == FAKE_RULE })
        assertTrue(result.findings.all { it.location.path == SOURCE_PATH &&
            it.location.fileDigest == fixture.sources.single().digest &&
            it.context == fixture.input.context.scope && it.semanticDigest == fixture.input.analysisKey() })
        assertTrue("unparsed_or_malformed_compiler_output" in result.reasons)
        assertTrue("raw_task_diagnostics_prevent_complete_coverage" in result.reasons)
        assertFailedCoverage(fixture, result, "failed_or_incomplete_compiler_task", "executed")
    }

    private fun assertQuarantined(result: ProviderResult, upstreamId: String, expected: Int) {
        val quarantined = result.rawDiagnostics.filter { it.upstreamId == upstreamId }
        assertEquals(expected, quarantined.size)
        assertTrue(quarantined.all { it.severity == "error" && it.location == null })
    }

    private fun assertFailedCoverage(fixture: Fixture, result: ProviderResult, reason: String, provenance: String) {
        assertEquals(RULES, result.coverage.map { it.ruleId })
        for (coverage in result.coverage) {
            assertEquals(fixture.input.context.scope, coverage.context)
            assertTrue(coverage.required)
            assertEquals("failed", coverage.state)
            assertEquals(reason, coverage.reason)
            assertEquals(provenance, coverage.provenance)
            assertNull(coverage.certificateId)
            assertFalse(coverage.tested)
            assertFalse(coverage.qualified)
            assertFalse(coverage.successful())
        }
        assertFalse(result.complete())
        assertFalse(result.completeFor(fixture.input))
    }

    private fun normalize(fixture: Fixture, lines: List<String>): ProviderResult {
        val stderr = lines.joinToString("\n", postfix = "\n")
        val outcome = ProcessOutcome(status = "executed", exitCode = 0, stderr = stderr, outputDirectory = fixture.output,
            executionConfirmed = true,
            metrics = AnalysisMetrics(outputBytes = stderr.toByteArray(Charsets.UTF_8).size.toLong()))
        assertTrue(outcome.successfulExecution)
        val method = KotlinJvmCompilerProvider::class.java.getDeclaredMethod("normalize",
            AnalysisInput::class.java, List::class.java, List::class.java, ProcessOutcome::class.java,
            CancellationToken::class.java, java.lang.Long.TYPE, KotlinJvmModelPermit::class.java)
        assertTrue(Modifier.isPrivate(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertEquals(7, method.parameterCount)
        assertEquals(ProviderResult::class.java, method.returnType)
        assertTrue(method.trySetAccessible())
        try {
            val result = method.invoke(fixture.provider, fixture.input, RULES, fixture.sources, outcome,
                CancellationToken.NONE, System.nanoTime(), null) as ProviderResult
            assertEquals(fixture.provider.id, result.providerId)
            assertEquals(CompilerRuleRegistry.VERSION, result.providerVersion)
            assertTrue(result.findings.all { finding -> finding.evidence.single { it.kind == "compiler-emission" }
                .digest == Digests.sha256(stderr + "\n") })
            fixture.calls.assertUnused()
            return result
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        } finally {
            method.setAccessible(false)
        }
    }

    private class Calls {
        var authority = 0
        var runner = 0
        var observation = 0
        fun assertUnused() {
            assertEquals(0, authority, "No execution authority may be acquired")
            assertEquals(0, runner, "No runner completion is expected")
            assertEquals(0, observation, "No executed-provider observation may be emitted")
        }
    }

    private class Fixture(val input: AnalysisInput, val sources: List<SealedCompilerSource>,
        val output: Path, val provider: KotlinJvmCompilerProvider, val calls: Calls,
        val ambiguousRows: List<String>) {
        val sourcePath: Path get() = output.resolve("sources").resolve(SOURCE_PATH)
        val zeroLine: Int get() = ambiguousRows.size + 4
        val missingLine: Int get() = ambiguousRows.size + 5
        fun abstractHeader(style: Style): String = header(style, sourcePath, 3,
            ambiguousRows.first().indexOf("object").let { if (it < 0) 1 else it + 1 },
            "error", ABSTRACT_ID, ABSTRACT_MESSAGE)
        fun caret(): String = " ".repeat(ambiguousRows.first().indexOf("object").coerceAtLeast(0)) + "^"
        fun zeroHeader(): String = header(Style.COLON, sourcePath, zeroLine, ZERO_ROW.indexOf('/') + 1,
            "warning", ZERO_ID, ZERO_MESSAGE)
        fun missingHeader(): String = header(Style.COLON, sourcePath, missingLine, MISSING_ROW.indexOf("missing") + 1,
            "error", FAKE_ID, "Unresolved reference 'missing'.")
    }

    private fun withFixture(rows: (Path) -> List<String>, action: (Fixture) -> Unit) {
        val temporary = Files.createTempDirectory("ktc-header-source-normalization-unit-")
        try {
            val root = temporary.toRealPath()
            val jar = Files.writeString(root.resolve("kotlin-compiler-embeddable-2.4.21.jar"), "not executable compiler bytes")
            val installation = KotlinJvmToolchain(SyntheticJdk.create(root.resolve("jdk")), listOf(
                ArtifactIdentity("unit:header-source-normalization", fileDigest(jar), jar.toString())),
                KotlinJvmRuntimeLimits(maxEntries = 32, maxDepth = 5, maxNameBytes = 128,
                    maxTotalNameBytes = 2_048, maxFileBytes = 1_024, maxTotalBytes = 4_096, maxScanMillis = 10_000))
            val materialized = Files.createDirectory(root.resolve("snapshot"))
            val outputs = Files.createDirectory(root.resolve("outputs"))
            val output = Files.createDirectory(outputs.resolve("synthetic-attempt"))
            val sourcePath = Files.createDirectory(output.resolve("sources")).resolve(SOURCE_PATH)
            val ambiguous = rows(sourcePath)
            val text = (listOf("interface Contract { fun answer(): Int }", "val text = \"\"\"") + ambiguous +
                listOf("\"\"\"", ZERO_ROW, MISSING_ROW)).joinToString("\n", postfix = "\n")
            Files.writeString(materialized.resolve(SOURCE_PATH), text)
            Files.writeString(sourcePath, text)
            val context = AnalysisContext(scope = RuleContext(target = "jvm", sourceSet = "synthetic",
                module = ":header-source-normalization-unit", compilation = "main"),
                compilerVersion = CompilerRuleRegistry.VERSION,
                modelDigest = Digests.sha256("synthetic model, not imported model evidence"),
                dependencyDigest = Digests.sha256("synthetic dependencies, not resolved evidence"),
                toolchainDigest = installation.digest,
                configurationDigest = Digests.sha256("synthetic configuration, not qualification evidence"),
                fullProject = true, compilationFiles = listOf(SOURCE_PATH))
            val input = AnalysisInput(SnapshotManifest(Digests.sha256(text), "unit", "unit",
                listOf(SnapshotFile(SOURCE_PATH, Digests.sha256(text), text.toByteArray(Charsets.UTF_8).size.toLong()))),
                materialized.toString(), context, requiredRuleIds = RULES, outputRoot = outputs.toString())
            assertNull(input.execution)
            val registry = CompilerRuleRegistry.load()
            for ((upstream, rule) in listOf(ABSTRACT_ID to ABSTRACT_RULE, FAKE_ID to FAKE_RULE, ZERO_ID to ZERO_RULE)) {
                val mapping = registry.byUpstreamId.getValue(upstream)
                assertEquals(rule, mapping.ruleId)
                assertTrue(mapping.jvmApplicable)
                assertNull(CompilerFlags.activationGap(mapping, context.compilerArguments))
            }
            val calls = Calls()
            val provider = KotlinJvmCompilerProvider(installation,
                authority = ExecutionAuthority { calls.authority++; null },
                runner = IsolatedProcessRunner { calls.runner++ }, registry = registry,
                qualifications = emptyList(), executionObserver = { _, _ -> calls.observation++ })
            val sources = CompilerSnapshot.read(input)
            assertEquals(listOf(SealedCompilerSource(SOURCE_PATH, Digests.sha256(text), text)), sources)
            action(Fixture(input, sources, output, provider, calls, ambiguous))
            calls.assertUnused()
        } finally {
            assertTrue(temporary.toFile().deleteRecursively(), "Remove only the test-created private fixture")
        }
    }

    private enum class Style { COLON, PAREN, FILE_URI }

    private companion object {
        const val SOURCE_PATH = "Main.kt"
        const val ABSTRACT_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
        const val ABSTRACT_RULE = "ktc.compiler.common.abstract_member_not_implemented"
        const val ABSTRACT_MESSAGE = "Object '<anonymous>' is not abstract and does not implement abstract member:"
        const val MEMBER = "fun answer(): Int"
        const val FAKE_ID = "UNRESOLVED_REFERENCE"
        const val FAKE_RULE = "ktc.compiler.common.unresolved_reference"
        const val ZERO_ID = "DIVISION_BY_ZERO"
        const val ZERO_RULE = "ktc.compiler.common.division_by_zero"
        val RULES = listOf(ABSTRACT_RULE, FAKE_RULE, ZERO_RULE)
        const val ZERO_ROW = "val zero = 1 / 0"
        const val ZERO_MESSAGE = "Division by zero"
        const val MISSING_ROW = "val use = missing"
        const val BANNER = "info: kotlinc-jvm 2.4.21 (JRE synthetic-header-source-only)"
        const val OTHER_BANNER = "info: kotlinc-jvm 2.4.20 (JRE synthetic-header-source-only)"
        const val LIMIT_MARKER = "Compiler header/source ambiguity tracking limit exceeded"

        fun header(style: Style, path: Path, line: Int, column: Int, severity: String,
            upstream: String, message: String): String {
            val location = when (style) {
                Style.COLON -> "$path:$line:$column"
                Style.PAREN -> "$path: ($line, $column)"
                Style.FILE_URI -> "${path.toUri()}:$line:$column"
            }
            return "$location: $severity: [$upstream] $message"
        }

        fun shadowRow(style: Style, path: Path, reportedLine: Int = 1): String =
            header(style, path, reportedLine, 1, "error", FAKE_ID, "${'$'}{object : Contract {}}")
    }
}
