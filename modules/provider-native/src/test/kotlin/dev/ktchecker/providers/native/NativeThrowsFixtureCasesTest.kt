package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlin.test.*

/** Authored source/model and synthetic receipt checks only; never Apple or JVM compilation stand-ins. */
class NativeThrowsFixtureCasesTest {
    private val fixture = Path.of(System.getProperty("ktchecker.repository"))
        .resolve("providers/platform-fixtures/native/throws")
    private val cohort get() = ContractJson.decodeFromString<NativeThrowsFixtureCohort>(Files.readString(fixture.resolve("cohort.json")))
    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "macos-aarch64",
        Digests.sha256("synthetic supervisor source"), Digests.sha256("synthetic supervisor binary"))
    private val inspected get() = NativeThrowsFixtureCases.supervisor(identity)

    @Test fun `authored batch retains only four exact Native mappings and unexecuted source provenance`() {
        NativeThrowsFixtureCases.validateCohort(cohort)
        assertEquals(4, cohort.rules.size)
        assertEquals(20, cohort.cases.size)
        assertEquals("authored_unexecuted", cohort.sourceProvenance)
        assertEquals(setOf("iosArm64", "iosSimulatorArm64"), cohort.targets.toSet())
        for (rule in cohort.rules) {
            assertEquals("FirNativeErrors", NativeCatalog.byId.getValue(rule.ruleId).family)
            assertEquals(rule.upstreamId, NativeCatalog.byId.getValue(rule.ruleId).upstreamId)
            assertEquals("ERROR", rule.declaredSeverity)
        }
        assertEquals(setOf("named_error", "compatible", "observation_only"), cohort.cases.map { it.expectation }.toSet())
        assertTrue(cohort.cases.none { it.ruleId.startsWith("ktc.compiler.jvm.") })
    }

    @Test fun `source freeze lists every regular authored input with exact bytes`() {
        val path = fixture.resolve(NativeThrowsFixtureCases.SOURCE_MANIFEST)
        assertEquals(NativeThrowsFixtureCases.SOURCE_MANIFEST_DIGEST, fileDigest(path))
        val manifest = ContractJson.decodeFromString<NativeThrowsSourceManifest>(Files.readString(path))
        val files = Files.walk(fixture).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it != path }.toList() }
        assertEquals(files.size, manifest.files.size)
        assertEquals(files.map { fixture.relativize(it).toString().replace('\\', '/') }.toSet(), manifest.files.map { it.path }.toSet())
        for (file in manifest.files) {
            val source = fixture.resolve(file.path)
            assertFalse(Files.isSymbolicLink(source))
            assertEquals(file.digest, fileDigest(source))
            assertEquals(file.size, Files.size(source))
        }
        for (case in cohort.cases) {
            val lines = Files.readString(fixture.resolve(case.sourcePath)).split('\n')
            for (anchor in case.anchors) {
                assertTrue(anchor.line in 1..lines.size)
                assertTrue(anchor.firstColumn in 0..anchor.lastColumn)
                assertTrue(anchor.lastColumn <= lines[anchor.line - 1].length)
            }
        }
    }

    @Test fun `integrated immutable fixture capture loads the pinned authored source manifest without executing tools`() {
        val work = Files.createTempDirectory("ktchecker-throws-source-freeze-")
        val frozen = FixtureSnapshots.materialize(fixture, work)
        val loaded = NativeThrowsFixtureCases.load(frozen)
        assertEquals(cohort, loaded)
        frozen.verify()
    }

    @Test fun `each iOS fixture model owns common plus only the selected authored alternative`() {
        val digests = mutableSetOf<String>()
        val snapshot = snapshot()
        for (case in cohort.cases) for (target in cohort.targets) {
            val binding = NativeThrowsFixtureCases.modelBinding(snapshot, case, target)
            assertEquals(RuleContext(target, "iosMain", ":", "main"), binding.scope)
            assertEquals(mapOf(NativeThrowsFixtureCases.COMMON_SOURCE to "commonMain", case.sourcePath to "iosMain"), binding.sourceSetByFile)
            assertEquals(NativeThrowsFixtureCases.requiredTasks(target), binding.requiredTaskPaths.toSet())
            assertEquals(2, binding.sourceSetByFile.size)
            assertTrue(Digests.valid(binding.modelDigest))
            assertEquals("explicit_fixture_producer_model_not_general_project_import", binding.provenance)
            digests += binding.modelDigest
        }
        assertEquals(40, digests.size)
    }

    @Test fun `missing duplicated and unknown snapshot members never produce a trusted fixture model`() {
        val case = case("override-filter")
        val original = snapshot()
        val common = original.files.single { it.path == NativeThrowsFixtureCases.COMMON_SOURCE }
        for (changed in listOf(original.copy(files = original.files.filter { it.path != case.sourcePath }),
            original.copy(files = original.files + common), original.copy(unknownInputs = listOf("unresolved fixture inputs")))) {
            assertFailsWith<ExecutionProblem> { NativeThrowsFixtureCases.modelBinding(changed, case, "iosArm64") }
        }
        assertFailsWith<ExecutionProblem> { NativeThrowsFixtureCases.modelBinding(original, case, "jvm") }
        assertFailsWith<ExecutionProblem> { NativeThrowsFixtureCases.modelBinding(original, case.copy(expectation = "compatible"), "iosArm64") }
    }

    @Test fun `explicit closed argument contract has no default home case version target or task fallback`() {
        val args = arrayOf("--fixture", "/owner/fixture", "--gradle", "/owner/gradlew", "--target", "iosArm64",
            "--case", "override-filter", "--result", "/owner/private/result.json", "--native-home", "/owner/native-2.4.21", "--java-home", "/owner/jdk25")
        assertEquals(7, NativeThrowsFixtureCases.parseOptions(args).size)
        for (changed in listOf(args.dropLast(2).toTypedArray(), args + arrayOf("--compiler-version", "2.4.20"),
            args.map { if (it == "iosArm64") "jvm" else it }.toTypedArray(),
            args.map { if (it == "override-filter") "positive" else it }.toTypedArray(),
            args + arrayOf("--case", "override-filter"))) {
            assertFailsWith<IllegalArgumentException> { NativeThrowsFixtureCases.parseOptions(changed) }
        }
    }

    @Test fun `actual version grammar does not upgrade different compiler or JDK outputs`() {
        assertTrue(NativeThrowsFixtureCases.acceptsJdkVersion("openjdk version \"25.0.1\""))
        assertTrue(NativeThrowsFixtureCases.acceptsJdkVersion("java version \"25\""))
        assertTrue(NativeThrowsFixtureCases.acceptsNativeVersion("kotlinc-native 2.4.21 (JRE 25)"))
        assertTrue(NativeThrowsFixtureCases.acceptsNativeVersion("Kotlin/Native compiler version 2.4.21"))
        for (output in listOf("openjdk version \"21.0.9\"", "openjdk version \"252.0.1\"", "java version \"25unsupported\""))
            assertFalse(NativeThrowsFixtureCases.acceptsJdkVersion(output))
        for (output in listOf("kotlinc-native 2.4.20", "kotlinc-native 2.4.210", "kotlinc-jvm 2.4.21", "not a Native compiler"))
            assertFalse(NativeThrowsFixtureCases.acceptsNativeVersion(output))
    }

    @Test fun `synthetic named Native error assertions require exact owned executed receipt and preserve unqualified coverage`() {
        val case = case("override-filter")
        val input = input(case)
        val provider = provider(case, input)
        val checked = NativeThrowsFixtureCases.assertions(case, input, provider, receipts(case, input), inspected)
        assertTrue(checked.passed)
        assertTrue(checked.processEvidenceConfirmed)
        assertTrue(checked.nativeTasksObserved)
        assertFalse(provider.complete())
        assertTrue(provider.coverage.all { it.state == "incomplete" && !it.tested && !it.qualified && !it.successful() })
        val noDiagnostic = receipts(case, input).map { if (it.phase == "provider") it.copy(stderr = "", stderrDigest = Digests.sha256("")) else it }
        assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, noDiagnostic, inspected).passed,
            "A finding detached from the captured process output is not diagnostic evidence")
    }

    @Test fun `synthetic compatible assertion needs zero exit actual common target tasks and no selected finding`() {
        val case = case("override-filter-matching")
        val input = input(case)
        val provider = provider(case, input)
        assertTrue(NativeThrowsFixtureCases.assertions(case, input, provider, receipts(case, input), inspected).passed)
        val wrong = receipts(case, input).map { if (it.phase == "provider") it.copy(exitCode = 1) else it }
        assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, wrong, inspected).passed)
    }

    @Test fun `fork child exit or negative signal never substitutes for explicit process confirmation and loaded helper identity`() {
        val case = case("override-filter")
        val input = input(case)
        val provider = provider(case, input)
        val original = receipts(case, input)
        for (changed in listOf(original.dropLast(1), original + original.last(),
            original.map { it.copy(executionConfirmed = false) }, original.map { it.copy(supervisorIdentity = null) },
            original.map { if (it.phase == "provider") it.copy(exitCode = -15) else it },
            original.map { if (it.phase == "provider") it.copy(status = "failed", timedOut = true) else it })) {
            assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, changed, inspected).passed)
        }
        val merelyExited = ProcessOutcome("executed", exitCode = 1, supervisorIdentity = identity)
        assertFalse(merelyExited.executed)
        assertFalse(merelyExited.successfulExecution)
    }

    @Test fun `changed helper snapshot analysis key or version witness cannot pass`() {
        val case = case("override-filter")
        val input = input(case)
        val provider = provider(case, input)
        val original = receipts(case, input)
        for (changed in listOf(
            original.map { if (it.phase == "provider") it.copy(supervisorIdentity = inspected.copy(binaryDigest = Digests.sha256("different helper"))) else it },
            original.map { if (it.phase == "provider") it.copy(snapshotId = Digests.sha256("different snapshot")) else it },
            original.map { if (it.phase == "provider") it.copy(analysisKey = Digests.sha256("different analysis")) else it },
            original.map { if (it.phase == "native-version") it.copy(stdout = "kotlinc-native 2.4.20", stdoutDigest = Digests.sha256("kotlinc-native 2.4.20")) else it },
            original.map { if (it.phase == "provider") it.copy(stdoutDigest = Digests.sha256("different output")) else it },
        )) assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, changed, inspected).passed)
        assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, original, null).passed)
    }

    @Test fun `cached duplicate sibling missing and unrelated failed tasks are not Native execution proof`() {
        val case = case("override-filter")
        val input = input(case)
        val provider = provider(case, input)
        val required = NativeThrowsFixtureCases.requiredTasks("iosArm64").toList()
        val originals = receipts(case, input)
        for (output in listOf(
            required.joinToString("\n") { "> Task $it FROM-CACHE" },
            required.joinToString("\n") { "> Task :sibling:${it.removePrefix(":")}" },
            required.joinToString("\n") { "> Task $it" } + "\n> Task ${required.last()}",
            "> Task ${required.last()}",
            required.joinToString("\n") { "> Task $it" } + "\n> Task :other:compileKotlinIosArm64 FAILED",
        )) {
            val changed = originals.map { if (it.phase == "provider") it.copy(stdout = output, stdoutDigest = Digests.sha256(output)) else it }
            assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider, changed, inspected).passed)
        }
    }

    @Test fun `named finding must preserve exact rule target source digest and authored anchor`() {
        val case = case("override-filter")
        val input = input(case)
        val provider = provider(case, input)
        val original = provider.findings.single()
        for (finding in listOf(original.copy(upstreamId = "THROWS_LIST_EMPTY"), original.copy(providerId = "kotlin-compiler-jvm"),
            original.copy(context = input.context.scope.copy(target = "iosSimulatorArm64")),
            original.copy(location = original.location.copy(path = NativeThrowsFixtureCases.COMMON_SOURCE)),
            original.copy(location = original.location.copy(fileDigest = Digests.sha256("different source"))),
            original.copy(location = original.location.copy(line = original.location.line + 1)),
            original.copy(location = original.location.copy(column = 0)), original.copy(semanticDigest = Digests.sha256("different analysis")))) {
            assertFalse(NativeThrowsFixtureCases.assertions(case, input, provider.copy(findings = listOf(finding)), receipts(case, input), inspected).passed)
        }
    }

    @Test fun `raw compiler errors model digest scope compiler flags and false certificates never look clean`() {
        val case = case("override-filter-matching")
        val input = input(case)
        val provider = provider(case, input)
        for (changed in listOf(provider.copy(rawDiagnostics = listOf(RawDiagnostic("synthetic backend failure"))),
            provider.copy(status = "failed", reasons = listOf("native_nonzero_without_named_source_error")),
            provider.copy(coverage = provider.coverage.map { it.copy(qualified = true) }),
            provider.copy(coverage = provider.coverage.map { it.copy(certificateId = null) }))) {
            assertFalse(NativeThrowsFixtureCases.assertions(case, input, changed, receipts(case, input), inspected).passed)
        }
        for (context in listOf(input.context.copy(modelDigest = Digests.sha256("different model")), input.context.copy(fullProject = false),
            input.context.copy(scope = input.context.scope.copy(sourceSet = "commonMain")), input.context.copy(compilerVersion = "2.4.20"),
            input.context.copy(toolchainDigest = null), input.context.copy(compilerArguments = emptyList()),
            input.context.copy(compilationFiles = input.context.compilationFiles!!.dropLast(1)))) {
            val changed = input.copy(context = context)
            assertFalse(NativeThrowsFixtureCases.assertions(case, changed, provider, receipts(case, changed), inspected).passed)
        }
    }

    @Test fun `suppression and severity demotion observations never become passing controls even with apparently clean output`() {
        for (case in cohort.cases.filter { it.expectation == "observation_only" }) {
            val input = input(case)
            val provider = provider(case, input).copy(status = "failed", rawDiagnostics = listOf(RawDiagnostic("synthetic suppression or backend failure")))
            val checked = NativeThrowsFixtureCases.assertions(case, input, provider, receipts(case, input), inspected)
            assertFalse(checked.passed)
            assertTrue(checked.processEvidenceConfirmed)
            assertContains(checked.failures, "native_throws_observation_only_not_a_passing_control")
            assertEquals(1, provider.rawDiagnostics.size, "An observation must not erase raw failures")
        }
    }

    private fun case(name: String): NativeThrowsFixtureCase = cohort.cases.single { it.name == name }

    private fun snapshot(): SnapshotManifest = SnapshotManifest(Digests.sha256("synthetic full authored fixture snapshot"), "fixture", "fixture",
        Files.walk(fixture).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().map {
            SnapshotFile(fixture.relativize(it).toString().replace('\\', '/'), fileDigest(it), Files.size(it))
        }.toList() }, ownerId = "synthetic-fixture-owner")

    private fun input(case: NativeThrowsFixtureCase): AnalysisInput {
        val snapshot = snapshot()
        val binding = NativeThrowsFixtureCases.modelBinding(snapshot, case, "iosArm64")
        val toolchain = Digests.sha256("synthetic complete toolchain binding, never executed")
        return AnalysisInput(snapshot, "/synthetic/frozen", AnalysisContext(binding.scope, compilerVersion = "2.4.21",
            classpath = listOf(ArtifactIdentity("synthetic-native", toolchain, "/synthetic/native.jar")),
            compilerArguments = NativeThrowsFixtureCases.compilerArguments(case), libraries = mapOf("kotlin-stdlib-native" to "2.4.21"),
            modelDigest = binding.modelDigest, dependencyDigest = toolchain, toolchainDigest = toolchain,
            configurationDigest = NativeThrowsFixtureCases.configurationDigest(case, "iosArm64"), fullProject = true,
            compilationFiles = binding.sourceSetByFile.keys.sorted()), listOf(case.ruleId),
            execution = TrustedExecution("/synthetic/gradle", gradleFixtureArguments(binding.requiredTaskPaths, case.name), true,
                Digests.sha256("synthetic gradle")), outputRoot = "/synthetic/outputs")
    }

    private fun provider(case: NativeThrowsFixtureCase, input: AnalysisInput): ProviderResult {
        val location = case.anchors.firstOrNull()?.let { SourceLocation(case.sourcePath,
            input.snapshot.files.single { it.path == case.sourcePath }.digest, it.line, it.firstColumn) }
        val findings = if (case.expectation == "named_error" && location != null) listOf(Finding(case.ruleId,
            "synthetic Native finding", "synthetic diagnostic", "synthetic rationale", location, Digests.sha256("synthetic finding"),
            "kotlin-native", input.context.scope, "error", "compile_failure", "high", upstreamId = case.upstreamId,
            semanticDigest = input.analysisKey())) else emptyList()
        return ProviderResult("kotlin-native", "incomplete", findings,
            listOf(Coverage(case.ruleId, input.context.scope, "incomplete", NativeThrowsFixtureCases.QUALIFICATION_REASON,
                provenance = "executed", certificateId = Digests.sha256("synthetic selected output certificate"), implemented = true)),
            reasons = listOf(NativeThrowsFixtureCases.QUALIFICATION_REASON), providerVersion = "2.4.21")
    }

    private fun receipts(case: NativeThrowsFixtureCase, input: AnalysisInput): List<NativeThrowsProcessReceipt> {
        val failed = case.expectation == "named_error"
        val stdout = NativeThrowsFixtureCases.requiredTasks("iosArm64").joinToString("\n") {
            "> Task $it" + if (failed && it == ":compileKotlinIosArm64") " FAILED" else ""
        } + if (failed) "\nBUILD FAILED" else "\nBUILD SUCCESSFUL"
        val stderr = if (failed) case.anchors.single().let {
            "e: ${input.materializedRoot}/${case.sourcePath}:${it.line}:${it.firstColumn + 1} [${case.upstreamId}] synthetic diagnostic"
        } else ""
        // Explicit test stand-ins only: the new confirmation flag is never inferred from exitCode.
        return listOf("jdk-version" to "openjdk version \"25.0.1\"", "native-version" to "kotlinc-native 2.4.21", "provider" to stdout).map { (phase, output) ->
            NativeThrowsFixtureCases.receipt(phase, input, ProcessOutcome("executed", exitCode = if (phase == "provider" && case.expectation == "named_error") 1 else 0,
                stdout = output, stderr = if (phase == "provider") stderr else "", command = listOf("/synthetic/gradle") + input.execution!!.arguments,
                supervisorIdentity = identity, executionConfirmed = true))
        }
    }
}
