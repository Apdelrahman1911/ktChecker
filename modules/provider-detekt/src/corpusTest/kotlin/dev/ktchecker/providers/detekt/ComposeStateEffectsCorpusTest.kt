package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * A separately consented fixture owner, not a project importer or a release certificate.
 * No assumption/skip path: the dedicated one-rule task either checks its actual observations
 * or fails closed. The ordinary test and older corpus task cannot select this class.
 * SOURCE predictions, actual SARIF, guard denials and partial-facet results remain distinct.
 */
@Tag("detekt-compose-state-effects")
class ComposeStateEffectsCorpusTest {
    @Test fun `one explicitly selected rule retains actual facets or visible no-launch denials`() {
        val settings = Settings.selected()
        val budget = Budget(settings)
        val inventory = Inventory.load(settings.repository)
        val fixture = inventory.fixtures.single { it.ruleId == settings.ruleId }
        val expectation = inventory.contexts.array("rules").map { it.jsonObject }
            .single { it.string("rule_id") == fixture.ruleId }
        val mode = expectation.string("mode")
        val plannedCalls = expectation.integer("expected_native_invocations_per_selected_job")
        assertEquals(if (mode == UNAVAILABLE) 0 else fixture.cases.size, plannedCalls)
        require(settings.maxInvocations >= plannedCalls) { "Owner budget cannot cover the selected rule; do not silently truncate its cases" }
        val evidence = EvidenceDirectory(settings.repository, settings.evidenceRoot)
        evidence.json("started.json", buildJsonObject {
            put("state", "started_unfinished"); put("captured_at", Instant.now().toString())
            put("selected_rule_id", fixture.ruleId); put("source_case_count", fixture.cases.size)
            put("authored_whole_batch_case_count", 61); put("expected_native_calls", plannedCalls)
            put("selected_mode", mode); put("qualified", false); put("build_outcome", JsonNull)
            put("automatic_cleanup", false); put("publication_committed", false)
            put("note", "Retain this run, all scratch and outputs on failure or uncertain native return. No live corpus sidecar or certificate is published.")
        })
        try {
            inventory.recheck()
            val ownerFile = OwnerCompilationFile(settings.ownerFile, settings.ownerFileDigest, fixture.ruleId)
            evidence.bytes("owner/independently-selected-compilations.json", ownerFile.readBytes())
            inventory.pinnedBytes.forEach { (relative, bytes) -> evidence.bytes("authored/$relative", bytes) }
            val store = SnapshotStore(evidence.run.resolve("scratch/store"), "compose-state-effects-owner")
            val identity = SnapshotIdentity("compose-state-effects-owner", "compose-state-effects-v1", fixture.ruleId.substringAfterLast('.'))
            store.registerWorktree(identity)
            var head: SnapshotCommit? = null
            var previousPaths = emptySet<String>()
            val captures = fixture.cases.map { case ->
                budget.check()
                val changes = case.sources.map { (path, source) -> SnapshotChange.PutFile(SnapshotPath(path = path), ImmutableBlob.utf8(source)) } +
                    (previousPaths - case.sources.keys).map { SnapshotChange.Delete(SnapshotPath(path = it)) }
                val previous = head
                val commit = store.sealOverlay(OverlayRequest(identity, "fixture-owner", "compose-state-effects-v1", case.id,
                    previous?.snapshotId, previous?.headRevision ?: 0, changes))
                previous?.let { assertEquals(it.snapshotId, commit.manifest.parentSnapshotId) }
                head = commit
                previousPaths = case.sources.keys
                // SnapshotStore requires an existing real destination parent; it does not create it.
                val caseParent = evidence.directory("scratch/${case.id}")
                val materialization = store.materialize(identity, commit.snapshotId, caseParent.resolve("sources"))
                CapturedCase(case, commit, materialization, store)
            }
            // Library controls use an independently fixed capture/owner, not tags copied from a request.
            contextControls(fixture, captures.first(), ownerFile, settings, evidence, budget)
            val outcomes = mutableListOf<JsonObject>()
            for (capture in captures) {
                budget.check()
                inventory.recheck()
                val case = capture.case
                val prefix = "cases/${case.id}"
                val owner = FixtureOwner(capture, ownerFile, EnvironmentSelection.REVIEWED)
                val input = owner.input(fixture.ruleId, evidence.run.resolve("outputs/${case.id}"))
                assertNull(input.execution)
                evidence.json("$prefix/source-oracle.json", case.document)
                evidence.bytes("$prefix/input-before-profile.json", ContractJson.encodeToString(input).toByteArray())
                capture.case.sources.forEach { (path, _) ->
                    val bytes = Files.readAllBytes(capture.materialization.root.resolve(safeRelativePath(path)))
                    assertEquals(input.snapshot.files.single { it.path == path }.digest, Digests.sha256(bytes))
                    evidence.bytes("$prefix/sources/$path", bytes)
                }
                var observed: ProcessOutcome? = null
                Route(settings.installation, onComplete = { actual ->
                    check(observed == null) { "A selected case may never retry the native runner" }
                    observed = actual
                    budget.observed()
                    evidence.json("$prefix/process.json", processJson(actual))
                }).use { route ->
                    val permit = route.prepare(input, owner)
                    assertTrue(permit.accepts(input))
                    assertTrue(Digests.valid(permit.bindingDigest))
                    evidence.json("$prefix/library-binding.json", buildJsonObject {
                        put("binding_digest", permit.bindingDigest); put("source", "live_fixture_owner_preparation")
                        put("owner_descriptor_digest", settings.ownerFileDigest); put("execution_granted_by_library_permit", false)
                    })
                    if (mode == UNAVAILABLE) {
                        val result = route.provider.analyze(input, budget.cancellation)
                        evidence.bytes("$prefix/provider-result.json", ContractJson.encodeToString(result).toByteArray())
                        assertUnavailable(result, fixture.ruleId, "detekt_compose_api_surface_unknown")
                        assertNull(observed, "Unknown attributed surfaces must not launch an empty invocation")
                        outcomes += buildJsonObject {
                            put("case_id", case.id); put("observation_kind", "guard_denial_not_detector_negative")
                            put("raw_detector_oracle_observed", false); put("native_invocations", 0)
                            put("whole_rule_state", "unavailable"); put("tested", false); put("qualified", false)
                        }
                    } else {
                        val plan = route.provider.executionPlan(input, budget.childMillis(), MAX_OUTPUT_BYTES)
                        budget.check()
                        assertEquals("light", plan.analysisMode)
                        assertEquals(setOf(fixture.ruleId), plan.activeRuleIds)
                        val applicability = assertNotNull(plan.composeApplicability.rules[fixture.ruleId])
                        assertTrue(applicability.executableFacets)
                        assertEquals(mode == REVIEWED, applicability.completeApiSurface)
                        assertEquals(if (mode == PARTIAL) "detekt_compose_partial_api_surface" else null, applicability.reason)
                        assertTrue(plan.execution.arguments.any { it == DetektLibraryPermit.PROFILE_PROPERTY + permit.bindingDigest })
                        assertFalse("--classpath" in plan.execution.arguments, "This is a LIGHT syntax run, not a hidden compiler/type-resolution run")
                        val executionInput = input.copy(execution = plan.execution)
                        route.execution.set(ExecutionPermit(plan.execution, input.snapshot.snapshotId, capture.materialization.root,
                            executionInput.analysisKey(), { capture.verify(); owner.resolve(); inventory.recheck(); budget.check() }))
                        assertTrue(permit.accepts(executionInput), "Only subsequently attaching the exact host profile may preserve this library grant")
                        evidence.bytes("$prefix/input.json", ContractJson.encodeToString(executionInput).toByteArray())
                        evidence.bytes("$prefix/planned-config.yml", plan.configuration.toByteArray())
                        evidence.json("$prefix/engine-artifacts.json", JsonObject(plan.artifacts.associate { artifact ->
                            artifact.coordinate to buildJsonObject { put("path", artifact.path); put("digest", artifact.digest) }
                        }))
                        budget.reserve()
                        val result = route.provider.analyze(executionInput, budget.cancellation)
                        evidence.bytes("$prefix/provider-result.json", ContractJson.encodeToString(result).toByteArray())
                        val actual = assertNotNull(observed, "Admitted selected facet did not reach the runner: ${result.reasons}")
                        // Retain actual failed outputs if readable before assertions; never invent a successful receipt.
                        val rawBytes = runCatching { actual.report("detekt.sarif", MAX_REPORT_BYTES) }.getOrNull()
                        val configBytes = runCatching { actual.report("detekt.yml", MAX_REPORT_BYTES) }.getOrNull()
                        rawBytes?.let { evidence.bytes("$prefix/actual-report.sarif", it) }
                        configBytes?.let { evidence.bytes("$prefix/actual-config.yml", it) }
                        assertEquals("executed", actual.status, actual.reasons.joinToString())
                        assertTrue(actual.executionConfirmed, "An exit code without actual exec confirmation is not an observation")
                        assertTrue(actual.reasons.isEmpty(), "Failed/uncertain cleanup must stop the selected job, not trigger another native invocation")
                        assertEquals(0, actual.exitCode)
                        assertFalse(actual.cancelled || actual.timedOut || actual.truncated)
                        val supervisor = assertNotNull(actual.supervisorIdentity)
                        assertEquals("ktchecker-owned-child-supervisor/preview-1", supervisor.protocolVersion)
                        assertTrue(supervisor.target.isNotBlank())
                        assertTrue(Digests.valid(supervisor.sourceDigest) && Digests.valid(supervisor.binaryDigest))
                        val actualConfig = assertNotNull(configBytes)
                        assertEquals(plan.configurationDigest, Digests.sha256(actualConfig))
                        assertContentEquals(plan.configuration.toByteArray(), actualConfig)
                        val actualReport = assertNotNull(rawBytes)
                        val parsed = DetektReportParser().parse(actualReport, executionInput)
                        val sarifId = DetektCatalog.byId.getValue(fixture.ruleId).sarifId
                        assertTrue(sarifId in parsed.enabledSarifIds,
                            "A real enabled negative must retain the exact enabled SARIF descriptor; empty results alone are insufficient")
                        assertTrue(parsed.diagnostics.all { it.sarifId == sarifId }, "Unselected reports are not part of this one-rule oracle")
                        assertSites(case.raw, parsed.diagnostics.map { it.location }, executionInput)
                        for (withheld in parsed.diagnostics.filter { diagnostic -> case.withheld.any { it.key == key(diagnostic.location) } }) {
                            val headline = withheld.message.lineSequence().first { it.isNotBlank() }
                            assertTrue(result.rawDiagnostics.none { it.message.contains(headline) },
                                "Unsupported advice may not leak through CLI stdout/stderr RawDiagnostic records either")
                            assertTrue(result.findings.none { it.diagnosis.contains(headline) })
                        }
                        assertSites(case.admitted, result.findings.map { it.location }, executionInput)
                        assertTrue(case.suppressed.none { site -> parsed.diagnostics.any { key(it.location) == site.key } })
                        assertProjection(fixture, case, mode, result, executionInput, permit.bindingDigest)
                        capture.verify()
                        owner.resolve()
                        assertEquals(plan.artifacts, DetektCatalog.verifyArtifacts(settings.installation.artifactDirectory))
                        inventory.recheck()
                        budget.check()
                        outcomes += buildJsonObject {
                            put("case_id", case.id); put("observation_kind", "actual_detekt_light_sarif")
                            put("report_digest", Digests.sha256(actualReport)); put("configuration_digest", Digests.sha256(actualConfig))
                            put("binding_digest", permit.bindingDigest); put("native_invocations", 1)
                            put("expected_raw_locations", case.raw.size); put("actual_raw_locations", parsed.diagnostics.size)
                            put("advisory_locations", result.findings.size); put("withheld_family_locations", case.withheld.size)
                            put("semantic_interpretation", case.document.string("semantic_interpretation"))
                            put("whole_rule_state", result.coverage.single().state)
                            put("tested", false); put("qualified", false)
                        }
                    }
                }
                capture.verify()
                budget.check()
            }
            captures.forEach { it.verify() } // Later immutable overlays must not change any earlier case/parent.
            inventory.recheck()
            ownerFile.readBytes()
            assertEquals(plannedCalls, budget.reserved)
            assertEquals(plannedCalls, budget.callbacks)
            budget.check()
            evidence.json("assertions-completed.json", buildJsonObject {
                put("state", "selected_assertions_completed_build_outcome_unknown")
                put("selected_rule_id", fixture.ruleId); put("selected_mode", mode); put("cases", JsonArray(outcomes))
                put("native_calls", budget.callbacks); put("aggregate_elapsed_millis", budget.elapsedMillis())
                put("source_oracles_not_run_for_other_rules", JsonArray(RULE_COUNTS.keys.filter { it != fixture.ruleId }.map(::JsonPrimitive)))
                put("authored_batch_rule_count", 8); put("authored_batch_case_count", 61)
                put("whole_batch_observed", false); put("all_eight_rules_delivered", false)
                put("parser_compatibility_qualified", false); put("real_world_precision", JsonNull)
                put("release_qualified", false); put("build_outcome", JsonNull); put("publication_committed", false)
                put("note", "This is immutable per-attempt evidence, not a live sidecar, certificate or evidence that the surrounding build later succeeded. No scratch is automatically removed.")
            })
            evidence.json("evidence-index.json", evidence.hashIndex())
            budget.check()
        } catch (failure: Throwable) {
            // Exactly one bounded failure artifact; no retry, live publication, or automatic cleanup.
            runCatching { evidence.json("failed-or-incomplete.json", buildJsonObject {
                put("state", "failed_or_incomplete"); put("failure_type", failure.javaClass.name)
                put("native_calls_reserved", budget.reserved); put("runner_callbacks_observed", budget.callbacks)
                put("qualified", false); put("publication_committed", false); put("scratch_retained", true)
            }) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun contextControls(fixture: Fixture, capture: CapturedCase, file: OwnerCompilationFile,
                                settings: Settings, evidence: EvidenceDirectory, budget: Budget) {
        fun record(id: String, result: ProviderResult) {
            evidence.bytes("controls/$id/result.json", ContractJson.encodeToString(result).toByteArray())
            evidence.json("controls/$id/observation.json", buildJsonObject {
                put("kind", "actual_provider_guard_check_not_detector_execution")
                put("runner_callbacks", 0); put("tested", false); put("qualified", false)
            })
        }
        fun input(owner: FixtureOwner, id: String) = owner.input(fixture.ruleId, evidence.run.resolve("control-outputs/$id"))
        fun deny(id: String, selection: EnvironmentSelection, reason: String) {
            budget.check()
            val owner = FixtureOwner(capture, file, selection)
            val selected = input(owner, id)
            var callbacks = 0
            Route(settings.installation, onComplete = { callbacks++ }).use { route ->
                route.prepare(selected, owner)
                val result = route.provider.analyze(selected, budget.cancellation)
                assertUnavailable(result, fixture.ruleId, reason)
                assertEquals(0, callbacks)
                record(id, result)
            }
        }
        val owner = FixtureOwner(capture, file, EnvironmentSelection.REVIEWED)
        val original = input(owner, "default-denial")
        for (id in listOf("untrusted-library-labels", "copied-approved-profile")) {
            var callbacks = 0
            Route(settings.installation, onComplete = { callbacks++ }).use { route ->
                val tagged = original.copy(context = original.context.copy(libraries = mapOf("compose-runtime" to "1.12.1", "compose-rules" to "0.6.8")),
                    execution = if (id == "copied-approved-profile") TrustedExecution("/deliberately-unselected/java", approved = true) else null)
                val result = route.provider.analyze(tagged, budget.cancellation)
                assertUnavailable(result, fixture.ruleId, "detekt_compose_library_not_authorized")
                assertEquals(0, callbacks)
                record(id, result)
            }
        }
        deny("missing-runtime", EnvironmentSelection.MISSING_RUNTIME, "detekt_compose_library_missing")
        deny("missing-annotations", EnvironmentSelection.MISSING_ANNOTATIONS, "detekt_compose_library_missing")
        deny("unsupported-runtime", EnvironmentSelection.UNSUPPORTED_RUNTIME, "detekt_compose_tuple_unsupported")
        if (fixture.ruleId.endsWith(".UnstableCollections"))
            deny("missing-immutable-family", EnvironmentSelection.MISSING_IMMUTABLE, "detekt_compose_api_surface_unknown")
        val changedInputs = listOf(
            Triple("wrong-source-set", original.copy(context = original.context.copy(scope = original.context.scope.copy(sourceSet = "test"))), "detekt_compose_compilation_mismatch"),
            Triple("wrong-membership", original.copy(context = original.context.copy(compilationFiles = emptyList())), "detekt_compose_compilation_members_mismatch"),
            Triple("reordered-dependencies", original.copy(context = original.context.copy(classpath = original.context.classpath.reversed())), "detekt_compose_compilation_mismatch"),
            Triple("profile-present-at-prepare", original.copy(execution = TrustedExecution("/deliberately-unselected/java", approved = true)), "detekt_compose_preparation_profile_present"),
        )
        for ((id, altered, reason) in changedInputs) {
            budget.check()
            var callbacks = 0
            Route(settings.installation, onComplete = { callbacks++ }).use { route ->
                val problem = assertFailsWith<ExecutionProblem> { route.prepare(altered, owner) }
                assertEquals(reason, problem.code)
                assertEquals(0, callbacks)
                evidence.json("controls/$id/preparation-rejection.json", buildJsonObject {
                    put("kind", "structural_preparation_rejection_not_provider_coverage")
                    put("code", problem.code); put("runner_callbacks", 0); put("qualified", false)
                })
            }
        }
        var ownerChanged = false
        val changingOwner = DetektComposeCompilationOwner {
            val captured = owner.resolve()
            if (ownerChanged) captured.copy(compilationId = captured.compilationId + ":changed-owner-selection") else captured
        }
        var changedCallbacks = 0
        Route(settings.installation, onComplete = { changedCallbacks++ }).use { route ->
            route.prepare(original, changingOwner)
            ownerChanged = true
            val result = route.provider.analyze(original, budget.cancellation)
            assertUnavailable(result, fixture.ruleId, "detekt_compose_owner_resolution_changed")
            assertEquals(0, changedCallbacks)
            record("changed-owner-resolution", result)
        }
        var crossCallbacks = 0
        Route(settings.installation, onComplete = { crossCallbacks++ }).use { first ->
            val grant = first.prepare(original, owner)
            Route(settings.installation, onComplete = { crossCallbacks++ }).use { second ->
                second.library.set(grant)
                val result = second.provider.analyze(original, budget.cancellation)
                assertUnavailable(result, fixture.ruleId, "detekt_compose_binding_mismatch")
                assertEquals(0, crossCallbacks)
                record("provider-instance-rebind", result)
                second.library.set(null) // The original owner still closes its own live grant.
            }
        }
        var revokedCallbacks = 0
        Route(settings.installation, onComplete = { revokedCallbacks++ }).use { route ->
            val grant = route.prepare(original, owner)
            grant.close()
            assertFalse(grant.accepts(original))
            val result = route.provider.analyze(original, budget.cancellation)
            assertUnavailable(result, fixture.ruleId, "detekt_compose_library_not_authorized")
            assertEquals(0, revokedCallbacks)
            record("revoked-library-permit", result)
        }
        var disabledCallbacks = 0
        Route(settings.installation, DetektSettings(disabledRuleIds = setOf(fixture.ruleId)), onComplete = { disabledCallbacks++ }).use { route ->
            route.prepare(original, owner)
            val result = route.provider.analyze(original, budget.cancellation)
            assertUnavailable(result, fixture.ruleId, "rule_disabled_by_owner_selected_configuration")
            assertEquals(0, disabledCallbacks)
            record("owner-disabled-selected-rule", result)
        }
        capture.verify()
        budget.check()
    }

    private fun assertProjection(fixture: Fixture, case: Case, mode: String, result: ProviderResult,
                                 input: AnalysisInput, bindingDigest: String) {
        assertEquals("detekt", result.providerId)
        assertEquals("partial", result.status)
        assertEquals(DETEKT_VERSION, result.providerVersion)
        assertTrue("detekt_compatibility_tuple_unqualified" in result.reasons)
        assertTrue("project_compiler_2.4.21_differs_from_detekt_embedded_compiler" in result.reasons)
        assertTrue("detekt_compose_heuristic_sites_are_not_resolved_api_identity" in result.reasons)
        assertFalse(result.complete())
        val coverage = result.coverage.single()
        assertEquals(fixture.ruleId, coverage.ruleId)
        assertEquals(input.context.scope, coverage.context)
        assertEquals("executed", coverage.provenance)
        assertFalse(coverage.tested || coverage.qualified || coverage.successful())
        assertNull(coverage.certificateId)
        if (mode == PARTIAL) {
            assertEquals("unavailable", coverage.state)
            assertEquals("detekt_compose_partial_api_surface", coverage.reason)
            assertFalse(coverage.implemented, "Useful facets do not implement or qualify the whole selected rule")
        } else {
            assertEquals(REVIEWED, mode)
            assertEquals(if (case.admitted.isEmpty()) "unknown" else "findings", coverage.state)
            assertTrue(coverage.implemented)
        }
        for (finding in result.findings) {
            assertEquals(fixture.ruleId, finding.ruleId)
            assertEquals(fixture.upstreamId, finding.upstreamId)
            assertEquals(input.context.scope, finding.context)
            assertEquals("warning", finding.severity)
            assertEquals("advisory", finding.kind)
            assertEquals("low", finding.certainty)
            assertNull(finding.fixHint)
            assertNull(finding.symbolId)
            assertNull(finding.semanticDigest)
            assertTrue(finding.verification.all { it.state == "not_run" && it.command.isEmpty() })
            assertTrue(finding.evidence.any { it.kind == "compose_library_applicability" && it.digest == bindingDigest })
        }
        val selectedRaw = result.rawDiagnostics.filter { it.upstreamId == DetektCatalog.byId.getValue(fixture.ruleId).sarifId }
        assertEquals(case.withheld.size, selectedRaw.size)
        assertTrue(selectedRaw.all { it.message == "Compose diagnostic withheld: exact API-family applicability is unproven." })
        assertSites(case.withheld, selectedRaw.map { assertNotNull(it.location) }, input)
        if (case.withheld.isNotEmpty()) assertTrue("detekt_compose_unproven_diagnostic_api_family" in result.reasons)
    }

    private fun assertUnavailable(result: ProviderResult, ruleId: String, reason: String) {
        assertEquals("partial", result.status)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.rawDiagnostics.isEmpty(), "No unsupported advice may be promoted through raw diagnostics")
        assertFalse(result.complete())
        val coverage = result.coverage.single()
        assertEquals(ruleId, coverage.ruleId)
        assertEquals("unavailable", coverage.state)
        assertEquals(reason, coverage.reason)
        assertTrue(reason in result.reasons)
        assertEquals("not_executed", coverage.provenance)
        assertFalse(coverage.implemented || coverage.tested || coverage.qualified || coverage.successful())
        assertNull(coverage.certificateId)
    }

    private fun assertSites(expected: List<Site>, actual: List<SourceLocation>, input: AnalysisInput) {
        assertEquals(expected.map { it.key }.sorted(), actual.map(::key).sorted(), "Exact independent start/end locations, not only diagnostic counts")
        actual.forEach { location ->
            assertEquals(input.snapshot.files.single { it.path == location.path }.digest, location.fileDigest)
            assertTrue(location.path in assertNotNull(input.context.compilationFiles))
        }
    }

    private fun processJson(actual: ProcessOutcome) = buildJsonObject {
        put("status", actual.status); put("exit_code", actual.exitCode)
        put("execution_confirmed", actual.executionConfirmed); put("executed", actual.executed)
        put("successful_execution", actual.successfulExecution); put("output_directory", actual.outputDirectory?.toString())
        put("output_identity_private_datum", actual.outputIdentityForPrivateEvidence())
        put("stdout", actual.stdout); put("stderr", actual.stderr)
        put("reasons", JsonArray(actual.reasons.map(::JsonPrimitive)))
        put("command", JsonArray(actual.command.map(::JsonPrimitive)))
        put("cancelled", actual.cancelled); put("timed_out", actual.timedOut); put("truncated", actual.truncated)
        put("metrics", ContractJson.encodeToJsonElement(actual.metrics))
        put("supervisor_identity", actual.supervisorIdentity?.let { identity -> buildJsonObject {
            put("protocol_version", identity.protocolVersion); put("target", identity.target)
            put("source_digest", identity.sourceDigest); put("binary_digest", identity.binaryDigest)
            put("origin", "actual_runner_outcome_not_an_inspector_or_synthetic_receipt")
            put("native_qualification", false)
        } } ?: JsonNull)
    }

    private class Route(installation: DetektInstallation, settings: DetektSettings = DetektSettings(),
                        onComplete: (ProcessOutcome) -> Unit) : AutoCloseable {
        val library = AtomicReference<DetektLibraryPermit?>()
        val execution = AtomicReference<ExecutionPermit?>()
        val provider = DetektProvider(installation = installation, settings = settings,
            authority = ExecutionAuthority { candidate -> execution.get()?.let { ExactExecutionAuthority(it).authorize(candidate) } },
            runner = IsolatedProcessRunner(onComplete),
            libraryAuthority = DetektLibraryAuthority { candidate -> library.get()?.takeIf { it.accepts(candidate) } })
        fun prepare(input: AnalysisInput, owner: DetektComposeCompilationOwner): DetektLibraryPermit {
            check(library.get() == null)
            return DetektLibraryPermit.prepare(provider, input, owner).also(library::set)
        }
        override fun close() { library.getAndSet(null)?.close(); execution.set(null) }
    }

    private data class CapturedCase(val case: Case, val commit: SnapshotCommit,
                                    val materialization: MaterializedSnapshot, val store: SnapshotStore) {
        fun verify() { check(store.verifyMaterialization(materialization).valid) { "Registered immutable fixture source drifted" } }
    }

    private enum class EnvironmentSelection { REVIEWED, MISSING_RUNTIME, MISSING_ANNOTATIONS, MISSING_IMMUTABLE, UNSUPPORTED_RUNTIME }

    /** Owns exact synthetic compilation scope/membership independently of any AnalysisInput. */
    private class FixtureOwner(private val capture: CapturedCase, private val file: OwnerCompilationFile,
                               private val selection: EnvironmentSelection) : DetektComposeCompilationOwner {
        override fun resolve(): DetektComposeCompilation {
            capture.verify()
            val environment = file.environment(selection)
            val capturedFiles = capture.commit.toContractManifest().files
            val selectedFiles = capture.case.members.map { member -> capturedFiles.single { it.path == member } }
            return DetektComposeCompilation(environment.compilationId, environment.scope, environment.platform,
                environment.sourceSetIds, selectedFiles, environment.compilerVersion, environment.compilerArguments, environment.dependencies)
        }
        fun input(ruleId: String, output: Path): AnalysisInput {
            val owned = resolve()
            return AnalysisInput(capture.commit.toContractManifest(), capture.materialization.root.toString(),
                AnalysisContext(scope = owned.scope, compilerVersion = owned.compilerVersion,
                    classpath = owned.dependencies.map { it.identity }, compilerArguments = owned.compilerArguments,
                    compilationFiles = owned.sourceFiles.map { it.path }), listOf(ruleId), outputRoot = output.toString())
        }
    }

    private data class OwnerEnvironment(val compilationId: String, val scope: RuleContext, val platform: String,
                                        val sourceSetIds: List<String>, val compilerVersion: String,
                                        val compilerArguments: List<String>, val dependencies: List<DetektComposeArtifact>)

    /** Explicit host-selected digest, not request-supplied serialized library authority. */
    private class OwnerCompilationFile(private val path: Path, private val expectedDigest: String, private val ruleId: String) {
        fun readBytes(): ByteArray {
            exactPath(path.toString())
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) in 1L..1_048_576L)
            val bytes = Files.readAllBytes(path)
            require(Digests.sha256(bytes) == expectedDigest) { "Independent owner descriptor bytes changed" }
            return bytes
        }
        fun environment(selection: EnvironmentSelection): OwnerEnvironment {
            val document = Json.parseToJsonElement(readBytes().toString(Charsets.UTF_8)).jsonObject
            require(document.string("schema") == "ktchecker/compose-state-effects-owner-compilations/1")
            require(document.string("dependency_vector_kind") == "complete_owner_resolved_jvm_compile_classpath")
            val base = parse(document.objectValue(if (selection == EnvironmentSelection.UNSUPPORTED_RUNTIME) "unsupported_runtime" else "reviewed"))
            require(base.scope == FIXTURE_SCOPE && base.compilationId == ":compose-state-effects-corpus:jvm:main")
            require(base.platform == "jvm" && base.compilerVersion == "2.4.21" && base.sourceSetIds == listOf("main"))
            require(base.dependencies.any { it.identity.coordinate.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") })
            require(base.dependencies.any { it.identity.coordinate.startsWith("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:") })
            val runtimes = base.dependencies.filter { it.identity.coordinate.startsWith("androidx.compose.runtime:runtime-desktop:") }
            require(runtimes.size == 1) { "Select one actual resolved JVM runtime variant, not an API-only label" }
            if (selection == EnvironmentSelection.UNSUPPORTED_RUNTIME) {
                require(runtimes.single().identity.coordinate != DetektComposeLibraryPins.runtime.coordinate &&
                    runtimes.single().identity.digest != DetektComposeLibraryPins.runtime.digest) {
                    "Unsupported-version control requires separately pinned real other-version bytes, not relabeled reviewed bytes"
                }
            } else {
                requirePin(base, DetektComposeLibraryPins.runtime)
                requirePin(base, DetektComposeLibraryPins.annotations)
                if (ruleId.endsWith(".UnstableCollections")) requirePin(base, DetektComposeLibraryPins.immutableCollections)
            }
            val dependencies = when (selection) {
                EnvironmentSelection.MISSING_RUNTIME -> base.dependencies.filterNot {
                    it.identity.coordinate.startsWith("androidx.compose.runtime:runtime-desktop:") ||
                        it.identity.coordinate.startsWith("androidx.compose.runtime:runtime-annotation-jvm:")
                }
                EnvironmentSelection.MISSING_ANNOTATIONS -> base.dependencies.filterNot { it.identity.coordinate == DetektComposeLibraryPins.annotations.coordinate }
                EnvironmentSelection.MISSING_IMMUTABLE -> base.dependencies.filterNot { it.identity.coordinate == DetektComposeLibraryPins.immutableCollections.coordinate }
                else -> base.dependencies
            }
            return base.copy(dependencies = dependencies)
        }
        private fun requirePin(environment: OwnerEnvironment, pin: DetektComposeBinaryPin) {
            val artifact = environment.dependencies.single { it.identity.coordinate == pin.coordinate }
            require(artifact.identity.digest == pin.digest && artifact.size == pin.size) { "Owner expected API binary pin differs from SOURCE-reviewed published module metadata" }
        }
        private fun parse(value: JsonObject): OwnerEnvironment {
            val scope = value.objectValue("scope")
            val dependencies = value.array("dependencies").map { raw ->
                val item = raw.jsonObject
                val path = exactPath(item.string("path"))
                val identity = ArtifactIdentity(item.string("coordinate"), item.string("digest"), path.toString())
                require(Digests.valid(identity.digest) && item.long("size") > 0)
                require(path.fileName.toString().endsWith(".jar") && !path.fileName.toString().endsWith("-sources.jar") && !identity.coordinate.endsWith(":sources"))
                DetektComposeArtifact(identity, item.long("size"))
            }
            require(dependencies.size >= 3 && dependencies.size <= 256)
            require(dependencies.map { it.identity.coordinate }.distinct().size == dependencies.size)
            require(dependencies.map { it.identity.path }.distinct().size == dependencies.size)
            return OwnerEnvironment(value.string("compilation_id"),
                RuleContext(target = scope.string("target"), sourceSet = scope.string("source_set"), module = scope.string("module"), compilation = scope.string("compilation")),
                value.string("platform"), value.strings("source_set_ids"), value.string("compiler_version"), value.strings("compiler_arguments"), dependencies)
        }
    }

    private data class Site(val path: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int, val text: String) {
        val key: String get() = listOf(path, line, column, endLine, endColumn).joinToString(":")
        fun validate(sources: Map<String, String>) {
            val source = sources.getValue(path)
            val lines = source.split('\n')
            fun offset(row: Int, column: Int): Int {
                require(row in 1..lines.size && column in 0..lines[row - 1].length)
                return lines.take(row - 1).sumOf { it.length + 1 } + column
            }
            val first = offset(line, column)
            val last = offset(endLine, endColumn)
            require(last > first)
            assertEquals(text, source.substring(first, last), "Authored literal SOURCE span drift")
        }
        companion object {
            fun parse(value: JsonObject) = Site(value.string("path"), value.integer("line"), value.integer("column"),
                value.integer("end_line"), value.integer("end_column"), value.string("text"))
        }
    }

    private data class Case(val id: String, val document: JsonObject, val sources: Map<String, String>, val members: List<String>,
                            val raw: List<Site>, val admitted: List<Site>, val withheld: List<Site>, val suppressed: List<Site>,
                            val uninvoked: List<Site>) {
        companion object {
            fun parse(value: JsonObject): Case {
                fun sites(key: String) = value.array(key).map { Site.parse(it.jsonObject) }
                val sources = value.objectValue("sources").mapValues { it.value.jsonPrimitive.content }
                val case = Case(value.string("id"), value, sources, value.strings("compilation_files"),
                    sites("expected_raw_findings"), sites("expected_admitted_findings"), sites("expected_withheld_raw_findings"), sites("suppressed_targets"),
                    sites("uninvoked_raw_source_oracle"))
                require(case.id.matches(Regex("[a-z0-9-]+")))
                require(case.members == listOf("src/main/kotlin/Subject.kt"))
                require(case.sources.keys.containsAll(case.members))
                case.sources.forEach { (path, source) ->
                    safeRelativePath(path)
                    require(source.all { it.code < 128 }) { "This corpus declares ASCII SOURCE only, not an encoding qualification" }
                    require(!source.contains("[[hit]]") && !source.contains("[[suppressed]]"))
                }
                (case.raw + case.admitted + case.withheld + case.suppressed + case.uninvoked).forEach { it.validate(case.sources) }
                assertEquals(case.raw.map { it.key }.sorted(), (case.admitted + case.withheld + case.uninvoked).map { it.key }.sorted())
                assertTrue(case.suppressed.none { target -> case.raw.any { it.key == target.key } })
                if (case.id == "selected-main-excludes-test") {
                    require(case.sources.keys == setOf("src/main/kotlin/Subject.kt", "src/test/kotlin/TestOnly.kt"))
                    require(case.raw.isEmpty())
                    val excluded = value.array("excluded_positive_oracle").map { Site.parse(it.jsonObject) }
                    require(excluded.isNotEmpty() && excluded.all { it.path !in case.members })
                    excluded.forEach { it.validate(case.sources) }
                }
                return case
            }
        }
    }

    private data class Fixture(val ruleId: String, val upstreamId: String, val activation: String, val cases: List<Case>)

    private class Inventory(val fixtures: List<Fixture>, val contexts: JsonObject,
                            val pinnedBytes: Map<String, ByteArray>, private val root: Path) {
        fun recheck() { pinnedBytes.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(safeRelativePath(path))), "Authored input drift: $path") } }
        companion object {
            fun load(repository: Path): Inventory {
                val root = repository.resolve("providers/detekt-fixtures/corpus/compose-state-effects-v1")
                val bytes = DOCUMENT_PINS.mapValues { (relative, expected) ->
                    val file = exactPath(root.resolve(safeRelativePath(relative)).toString())
                    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) in 1L..2_097_152L)
                    Files.readAllBytes(file).also { assertEquals(expected, Digests.sha256(it), "Pinned authored document changed: $relative") }
                }
                fun document(name: String) = Json.parseToJsonElement(bytes.getValue(name).toString(Charsets.UTF_8)).jsonObject
                val manifest = document("manifest.json")
                assertEquals(1, manifest.integer("schema_version"))
                assertEquals(61, manifest.integer("authored_case_count"))
                assertEquals(5, manifest.objectValue("activation_counts").integer("advisory"))
                assertEquals(3, manifest.objectValue("activation_counts").integer("opt_in"))
                assertEquals(JsonNull, manifest.objectValue("observation")["actual_execution"])
                assertFalse(manifest.objectValue("observation").getValue("tested").jsonPrimitive.boolean)
                assertFalse(manifest.objectValue("observation").getValue("qualified").jsonPrimitive.boolean)
                val fixtures = manifest.array("rules").map { item ->
                    val row = item.jsonObject
                    val fixture = document(row.string("path"))
                    assertEquals("sha256:" + row.string("sha256"), Digests.sha256(bytes.getValue(row.string("path"))))
                    assertEquals(row.string("rule_id"), fixture.string("rule_id"))
                    assertEquals(row.string("upstream_id"), fixture.string("upstream_id"))
                    assertEquals(row.string("default_activation"), fixture.string("default_activation"))
                    assertEquals("syntax", fixture.string("depth"))
                    assertEquals("84794e3175a8ab6e1d024a5db6c299be2454ae6b", fixture.objectValue("source_oracle").string("commit"))
                    val cases = fixture.array("cases").map { Case.parse(it.jsonObject) }
                    assertEquals(RULE_COUNTS.getValue(fixture.string("rule_id")), cases.size)
                    assertEquals(cases.size, cases.map { it.id }.distinct().size)
                    assertEquals("positive", cases[0].id)
                    assertTrue(cases[0].raw.isNotEmpty())
                    assertEquals("valid-alternative", cases[1].id)
                    assertTrue(cases[1].raw.isEmpty())
                    val suppression = cases.single { it.document.string("kind") == "suppression_neighbor" }
                    assertTrue(suppression.suppressed.isNotEmpty() && suppression.raw.isNotEmpty())
                    assertTrue(suppression.sources.values.any { it.contains("@Suppress(\"${fixture.string("upstream_id")}\")") })
                    assertTrue(cases.any { it.document.string("kind") == "alias_control" })
                    assertTrue(cases.any { it.document.string("kind") == "homonym_control" })
                    assertTrue(cases.any { it.id == "selected-main-excludes-test" })
                    val catalog = DetektCatalog.byId.getValue(fixture.string("rule_id"))
                    assertEquals(fixture.string("upstream_id"), catalog.upstream_id)
                    assertEquals(fixture.string("default_activation"), catalog.default_activation)
                    assertTrue(catalog.exact)
                    Fixture(fixture.string("rule_id"), fixture.string("upstream_id"), fixture.string("default_activation"), cases)
                }
                assertEquals(RULE_COUNTS.keys, fixtures.map { it.ruleId }.toSet())
                assertEquals(61, fixtures.sumOf { it.cases.size })
                assertEquals(5, fixtures.count { it.activation == "advisory" })
                assertEquals(3, fixtures.count { it.activation == "opt_in" })
                val pending = document("pending-coverage.json")
                assertEquals("authored_unrun_not_a_live_coverage_sidecar", pending.string("status"))
                assertEquals(JsonNull, pending["actual_execution"])
                assertEquals(61, pending.integer("authored_source_case_count"))
                assertTrue(pending.array("rules").all { it.jsonObject.string("state") == "unavailable" &&
                    !it.jsonObject.getValue("tested").jsonPrimitive.boolean && !it.jsonObject.getValue("qualified").jsonPrimitive.boolean })
                val contexts = document("admission-contexts.json")
                assertEquals(RULE_COUNTS.keys, contexts.array("rules").map { it.jsonObject.string("rule_id") }.toSet())
                assertEquals(4, contexts.array("rules").count { it.jsonObject.string("mode") == REVIEWED })
                assertEquals(2, contexts.array("rules").count { it.jsonObject.string("mode") == PARTIAL })
                assertEquals(2, contexts.array("rules").count { it.jsonObject.string("mode") == UNAVAILABLE })
                assertEquals(13, contexts.array("controls").size)
                val noLaunchIds = contexts.array("rules").filter { it.jsonObject.string("mode") == UNAVAILABLE }
                    .map { it.jsonObject.string("rule_id") }.toSet()
                fixtures.forEach { fixture -> fixture.cases.forEach { case ->
                    if (fixture.ruleId in noLaunchIds) {
                        assertEquals(case.raw, case.uninvoked)
                        assertTrue(case.admitted.isEmpty() && case.withheld.isEmpty(), "No-launch SOURCE oracles cannot be called admitted or observed withheld output")
                    } else assertTrue(case.uninvoked.isEmpty())
                } }
                assertEquals(9, fixtures.sumOf { fixture -> fixture.cases.sumOf { it.uninvoked.size } })
                val boxing = fixtures.single { it.ruleId.endsWith(".MutableStateAutoboxing") }
                assertFalse(boxing.cases.first().sources.values.single().contains("@Composable"), "Do not add a blanket composable annotation guard to a file/function visitor")
                assertEquals(2, fixtures.sumOf { fixture -> fixture.cases.sumOf { it.withheld.size } })
                return Inventory(fixtures, contexts, bytes, root)
            }
        }
    }

    private data class Settings(val repository: Path, val ruleId: String, val installation: DetektInstallation,
                                val ownerFile: Path, val ownerFileDigest: String, val evidenceRoot: Path,
                                val maxInvocations: Int, val aggregateMillis: Long, val childMillis: Long) {
        companion object {
            fun selected(): Settings {
                fun required(key: String): String = System.getProperty("ktchecker.detekt.compose.$key")
                    ?: error("Explicit dedicated composeStateEffectsCorpusTest property missing: $key; no assumption/skip/fallback")
                require(required("task") == "composeStateEffectsCorpusTest")
                require(required("consent") == "compose-state-effects-v1-one-rule")
                val ruleId = required("rule")
                require(ruleId in RULE_COUNTS)
                val javaHome = exactPath(required("jdkHome"))
                val ownerDigest = required("ownerCompilationSha256")
                require(Digests.valid(ownerDigest))
                val maximum = required("maxInvocations").toInt()
                val aggregate = required("aggregateMillis").toLong()
                val child = required("childMillis").toLong()
                require(maximum in 0..8 && aggregate in 1_000..300_000 && child in 1..60_000 && child <= aggregate)
                require(System.getProperty("java.specification.version") == "21") { "The dedicated harness requires the explicitly scheduled JDK21 test host" }
                return Settings(exactPath(System.getProperty("ktchecker.repositoryRoot")), ruleId,
                    DetektInstallation(javaHome.resolve("bin/java"), exactPath(required("artifacts")), javaHome),
                    exactPath(required("ownerCompilation")), ownerDigest, exactPath(required("evidenceRoot")), maximum, aggregate, child)
            }
        }
    }

    private class Budget(private val settings: Settings) {
        private val start = System.nanoTime()
        var reserved = 0; private set
        var callbacks = 0; private set
        val cancellation = CancellationToken { Thread.currentThread().isInterrupted || elapsedMillis() >= settings.aggregateMillis }
        fun elapsedMillis(): Long = (System.nanoTime() - start) / 1_000_000
        fun check() { check(!cancellation.isCancelled()) { "Selected Compose job aggregate deadline/cancellation reached; no retry" } }
        fun childMillis(): Long { check(); return minOf(settings.childMillis, settings.aggregateMillis - elapsedMillis()).coerceAtLeast(1) }
        fun reserve() { check(); check(reserved < settings.maxInvocations); reserved++ }
        fun observed() { check(callbacks < reserved); callbacks++ }
    }

    /** Private unique immutable evidence directory. No overwrite, publication or automatic cleanup. */
    private class EvidenceDirectory(repository: Path, selected: Path) {
        val run: Path
        private val directoryKeys = linkedMapOf<Path, String>()
        private val completedWrites = mutableListOf<JsonObject>()
        private var totalBytes = 0L
        init {
            require(selected.startsWith(repository.resolve("build")) && selected != repository.resolve("build")) {
                "Evidence must be an explicit dedicated subdirectory under this workspace's build/, never a live corpus root"
            }
            exactPath(selected.toString())
            Files.createDirectories(selected)
            exactPath(selected.toString())
            run = Files.createTempDirectory(selected, "compose-state-effects-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            remember(run)
        }
        fun directory(relative: String): Path {
            var current = run
            safeRelativePath(relative).forEach { segment ->
                current = current.resolve(segment)
                if (!Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
                remember(current)
            }
            verifyDirectories()
            return current
        }
        fun hashIndex(): JsonObject = buildJsonObject {
            put("kind", "hashes_of_completed_private_evidence_writes_not_custody_or_execution_authority")
            put("entries", JsonArray(completedWrites.toList()))
            put("excludes_this_index_and_any_later_failure_marker", true)
            put("qualifies_surrounding_build_or_release", false)
        }
        fun json(relative: String, value: JsonObject) = bytes(relative, value.toString().toByteArray())
        fun bytes(relative: String, bytes: ByteArray): JsonObject {
            require(bytes.size <= 8_388_608 && totalBytes + bytes.size <= 67_108_864) { "Bounded evidence exhausted; retain scratch and stop" }
            val rel = safeRelativePath(relative)
            rel.parent?.let { directory(it.toString()) }
            verifyDirectories()
            Files.newDirectoryStream(run).use { stream ->
                val secure = stream as? SecureDirectoryStream<Path> ?: error("No anchored no-follow evidence I/O; refuse an unsafe fallback")
                val parts = rel.iterator().asSequence().toList()
                fun writeAt(directory: SecureDirectoryStream<Path>, index: Int, directoryPath: Path) {
                    val actual = directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
                    require(actual.isDirectory && actual.fileKey()?.toString() == directoryKeys.getValue(directoryPath))
                    if (index == parts.lastIndex) {
                        val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                        directory.newByteChannel(parts[index], options).use { channel ->
                            val buffer = java.nio.ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) channel.write(buffer)
                        }
                    } else directory.newDirectoryStream(parts[index], NOFOLLOW_LINKS).use { child ->
                        writeAt(child, index + 1, directoryPath.resolve(parts[index]))
                    }
                }
                writeAt(secure, 0, run)
            }
            totalBytes += bytes.size
            verifyDirectories()
            return buildJsonObject { put("path", relative); put("size", bytes.size); put("sha256", Digests.sha256(bytes)) }.also { completedWrites += it }
        }
        private fun remember(directory: Path) {
            exactPath(directory.toString())
            val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink)
            val key = requireNotNull(attributes.fileKey()?.toString())
            val previous = directoryKeys.putIfAbsent(directory, key)
            require(previous == null || previous == key)
        }
        private fun verifyDirectories() {
            directoryKeys.forEach { (directory, expected) ->
                exactPath(directory.toString())
                val actual = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(actual.isDirectory && !actual.isSymbolicLink && actual.fileKey()?.toString() == expected)
            }
        }
    }

    companion object {
        private const val REVIEWED = "reviewed_api_surface"
        private const val PARTIAL = "partial_reviewed_message_facets"
        private const val UNAVAILABLE = "unavailable_unattributed_surface"
        private const val MAX_OUTPUT_BYTES = 1_048_576
        private const val MAX_REPORT_BYTES = 1_048_576
        private val FIXTURE_SCOPE = RuleContext(target = "jvm", sourceSet = "main", module = ":compose-state-effects-corpus", compilation = "main")
        private val RULE_COUNTS = linkedMapOf(
            "ktc.compose.pack.RememberMissing" to 8,
            "ktc.compose.pack.RememberContentMissing" to 8,
            "ktc.compose.pack.LambdaParameterInRestartableEffect" to 8,
            "ktc.compose.pack.ContentSlotReused" to 8,
            "ktc.compose.pack.MutableStateAutoboxing" to 8,
            "ktc.compose.pack.StateParam" to 7,
            "ktc.compose.pack.MutableStateParam" to 7,
            "ktc.compose.pack.UnstableCollections" to 7,
        )
        private val DOCUMENT_PINS = mapOf(
            "README.md" to "sha256:acdb1d141cb3561d3174c253e9a4f97bba34d0af3fe4a78d09d548ed36d5c68c",
            "admission-contexts.json" to "sha256:c029c119b0ffce1329f1cf99063d155dde8cce871157645e3b13b76a9e445ef2",
            "api-surface-review.json" to "sha256:a027758498d025d3dce68a0feabd05f11cc2c2afbe8e84fcdc991a56abe0aba2",
            "fixtures/content-slot-reused.json" to "sha256:38292bd913bf17c436df22120ccfe169b271d584edad0cb3f94eba8525e48c4a",
            "fixtures/lambda-parameter-in-restartable-effect.json" to "sha256:d161a74a26d6641a834b1d93794c68285a2efde438850598ce59619c0def1e3f",
            "fixtures/mutable-state-autoboxing.json" to "sha256:ea1afe6fa542ec723b939273748896b77d986663a08200e812b58f9328637b6c",
            "fixtures/mutable-state-param.json" to "sha256:c3b182d724e6b1cde1f24b249060248968b8a3e9568088cfada0454f5205c43d",
            "fixtures/remember-content-missing.json" to "sha256:7cec613305a3440b4ae7a6bdde9996ca7143d59d777dbb411f250c22f3a8ab2f",
            "fixtures/remember-missing.json" to "sha256:9eef666f2aeb916905e6f48829bd32ae63324771f2c93e0d60ecea8d0917151e",
            "fixtures/state-param.json" to "sha256:6058d0233a6dbbabf86349251b930dc520e32ee15df1abe8fc8dfdf04abc4b39",
            "fixtures/unstable-collections.json" to "sha256:eeb8ee583489fa015e3553465f875a1de935db887a5a8a464e5e53d4f717dc2e",
            "manifest.json" to "sha256:907c9374788e13a549eba81626adbfb8ca0b97bfbb3bb8bce8a6967e2273f79a",
            "owner-input-format.json" to "sha256:b84f71b54c87c31f736f26a31aceb9b21926cd347f39d61addb891af985adc6d",
            "pending-coverage.json" to "sha256:80501f4bdea67077c2850597d58918cf32633ccb8940699ab857c0bab372b409",
            "source-oracles.json" to "sha256:4474e3ed59dd595c908b9b10246cd4e8ebd256df3c47ee0bbc4fe5b1d3ffa21f",
        )
        private fun key(location: SourceLocation) = listOf(location.path, location.line, location.column, location.endLine, location.endColumn).joinToString(":")
        private fun exactPath(raw: String): Path = Path.of(raw).also { path ->
            require(path.isAbsolute && path.normalize() == path && path.toString() == raw) { "Exact normalized absolute owner path required" }
            var ancestor: Path? = path
            while (ancestor != null) { require(!Files.isSymbolicLink(ancestor)); ancestor = ancestor.parent }
        }
        private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        private fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.int
        private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
        private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
        private fun JsonObject.objectValue(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.strings(key: String): List<String> = array(key).map { it.jsonPrimitive.let { value -> require(value.isString); value.content } }
    }
}
