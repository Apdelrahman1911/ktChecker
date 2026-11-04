package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Every authorized path has an unconditional verifier stop before any synthetic process launch. */
class KotlinJvmModelProviderTest {
    @Test fun providerModeIsFixedAndCannotBeSelectedByProfileOrModelMetadata(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val model = KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        val legacy = KotlinJvmCompilerProvider(f.installation)
        val preparation = f.prepare(model)
        val archive = f.archive(preparation.prepared)
        assertEquals("2.4.21", legacy.version)
        assertEquals("2.4.21/verified-model-1", model.version)
        expectModelFailure("compiler_model_binding_required") { model.executionProfile(preparation.input) }
        expectModelFailure("compiler_model_binding_required") { legacy.executionProfile(preparation.input) }
        expectModelFailure("compiler_model_input_mismatch") { model.executionProfile(archive.child, preparation.binding) }
        assertEquals("compiler_model_route_required", legacy.analyze(archive.child).reasons.single())
        assertFailsWith<IllegalArgumentException> {
            KotlinJvmCompilerProvider(f.installation, modelAuthority = KotlinJvmModelAuthority { null })
        }
    }

    @Test fun genericExecutionPermissionAndRoundTrippedEvidenceCannotRestoreLiveModelAuthority(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        val decoded = ContractJson.decodeFromString<AnalysisInput>(ContractJson.encodeToString(archive.child))
        assertEquals(archive.child, decoded)
        assertTrue(decoded.execution!!.approved)
        val forgedLegacy = stripModelEvidence(decoded, f.installation.digest)
        for (input in listOf(decoded, forgedLegacy, forgedLegacy.copy(execution = null))) {
            // A permissive ordinary owner grant would be enough for transport, not model semantics.
            if (input.execution != null) gate.executionPermit = genericPermit(input) { unitStop() }
            val result = gate.provider.analyze(input)
            assertEquals("compiler_model_not_authorized", result.reasons.single())
            assertTrue(result.coverage.none { it.qualified || it.tested || it.certificateId != null })
        }
        assertEquals(0, gate.executionLookups)
        assertEquals(0, archive.verifications)
        assertEquals(0, gate.processOutcomes)
    }

    @Test fun validModelPermissionDoesNotGrantOrdinaryProcessExecution(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        val count = archive.verifications
        assertEquals("execution_not_authorized", gate.provider.analyze(archive.child).reasons.single())
        assertEquals(1, gate.executionLookups)
        assertEquals(count, archive.verifications)
        assertEquals(0, gate.processOutcomes)
        gate.modelPermit!!.close()
    }

    @Test fun bothExactPermissionsPassCustomJvmAndActualCompilerVersionGatesButDoNotExecute(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        var genericVerified = 0
        gate.executionPermit = genericPermit(archive.child) { genericVerified++; unitStop() }
        val result = gate.provider.analyze(archive.child)
        assertEquals("unit_stop_before_launch", result.reasons.single())
        assertEquals(1, genericVerified)
        assertEquals("2.4.21", archive.child.context.compilerVersion)
        assertEquals("2.4.21/verified-model-1", result.providerVersion)
        assertEquals("customJvm", archive.child.context.scope.target)
        assertEquals(0, gate.processOutcomes)
        assertTrue(result.findings.isEmpty())
        assertTrue(result.coverage.none { it.qualified || it.tested || it.certificateId != null })
        gate.modelPermit!!.close()
    }

    @Test fun broadAuthorityCannotDowngradeRetainedModelAttemptByDeletingMarkersOrSubstitutingDigest(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        gate.returnPermitForForeignInput = true // Defense in depth even against an erroneous owner lookup.
        val count = archive.verifications
        val input = archive.child
        val profile = assertNotNull(input.execution)
        val changes = listOf(input.copy(context = input.context.copy(policies = input.context.policies - "ktchecker.model" - "ktchecker.model-certificate")),
            input.copy(context = input.context.copy(toolchainDigest = f.installation.digest)),
            input.copy(execution = profile.copy(arguments = profile.arguments.filterNot {
                it.startsWith(KotlinJvmModelBinding.PROFILE_PROPERTY) })),
            stripModelEvidence(input, f.installation.digest), input.copy(requiredRuleIds = preparation.input.requiredRuleIds),
            input.copy(outputRoot = f.work.resolve("other-output").toString()))
        changes.forEach { changed ->
            gate.executionPermit = genericPermit(changed) { unitStop() }
            assertEquals("compiler_model_attempt_mismatch", gate.provider.analyze(changed).reasons.single())
        }
        assertEquals(KotlinJvmInputMode.VERIFIED_MODEL, gate.provider.inputMode)
        assertEquals(count, archive.verifications)
        assertEquals(0, gate.executionLookups)
        assertEquals(0, gate.processOutcomes)
        gate.modelPermit!!.close()
    }

    @Test fun sameBytesOrSameInstallationCannotTransferPermitToAnotherProviderInstance(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        val permit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        val equalInstallation = KotlinJvmToolchain(f.java, f.installation.compilerClasspath)
        assertEquals(f.installation.digest, equalInstallation.digest)
        var ordinaryLookups = 0
        for (installation in listOf(f.installation, equalInstallation)) {
            val other = KotlinJvmCompilerProvider(installation,
                authority = ExecutionAuthority { ordinaryLookups++; genericPermit(archive.child) { unitStop() } },
                inputMode = KotlinJvmInputMode.VERIFIED_MODEL, modelAuthority = ExactKotlinJvmModelAuthority(permit))
            assertEquals("compiler_model_attempt_mismatch", other.analyze(archive.child).reasons.single())
        }
        assertEquals(0, ordinaryLookups)
        assertEquals(2, archive.verifications)
        permit.close()
    }

    @Test fun eachProviderPreparationReverifiesLiveArchiveRatherThanTrustingPermitLookup(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        var stop = false
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child) {
            archive.verify()
            if (stop) throw ExecutionProblem("unit_stop_in_live_model_verifier", "No synthetic process is permitted.")
        }
        stop = true
        val count = archive.verifications
        repeat(2) {
            // If live model verification regresses away, the runner's second ordinary check still
            // unconditionally stops before launch. No test path can execute the fake launcher.
            gate.executionPermit = guardedGenericPermit(archive.child) {}
            assertEquals("unit_stop_in_live_model_verifier", gate.provider.analyze(archive.child).reasons.single())
        }
        assertEquals(count + 2, archive.verifications)
        assertEquals(0, gate.processOutcomes)
        gate.modelPermit!!.close()
    }

    @Test fun revocationAfterLookupCannotBecomeLegacyFallback(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        gate.executionPermit = guardedGenericPermit(archive.child) { gate.modelPermit!!.close() }
        assertEquals("compiler_model_attempt_mismatch", gate.provider.analyze(archive.child).reasons.single())
        assertEquals(2, archive.verifications)
        assertEquals(0, gate.processOutcomes)
    }

    @Test fun installedRuntimeDriftIsRejectedBeforeAnySyntheticProcessCanStart(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        gate.executionPermit = guardedGenericPermit(archive.child) {}
        Files.writeString(f.work.resolve("jdk/conf/security/java.security"), "changed selected runtime policy")
        assertEquals("compiler_java_runtime_changed", gate.provider.analyze(archive.child).reasons.single())
        assertEquals(0, gate.processOutcomes)
        gate.modelPermit!!.close()
    }

    @Test fun matchingLegacyQualificationNeverQualifiesModelModeEvenWithMarkersRemoved(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val preparation = f.prepare(KotlinJvmCompilerProvider(f.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL))
        val legacyInput = stripModelEvidence(preparation.input, f.installation.digest)
        val row = CompilerQualification(f.rule, legacyInput.context.scope.id, "2.4.21", f.installation.digest,
            assertNotNull(legacyInput.context.configurationDigest), Digests.sha256("synthetic eligibility row, not executed evidence"),
            assertNotNull(legacyInput.context.modelDigest), assertNotNull(legacyInput.context.dependencyDigest),
            Digests.sha256(ContractJson.encodeToString(legacyInput.context.compilerArguments)))
        val legacy = KotlinJvmCompilerProvider(f.installation, qualifications = listOf(row))
        val model = KotlinJvmCompilerProvider(f.installation, qualifications = listOf(row), inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
        // Pure eligibility only: no synthetic coverage result or successful-rule certificate.
        assertSame(row, legacy.qualificationFor(f.rule, legacyInput))
        assertNull(legacy.qualificationFor(f.rule, preparation.input))
        assertNull(model.qualificationFor(f.rule, legacyInput))
        assertNull(model.qualificationFor(f.rule, preparation.input))
    }

    @Test fun realWorkbenchLimitedLegacyShapeKeepsItsExecutionRouteWithoutQualification(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val input = workbenchLegacyInput(f)
        var permission: ExecutionPermit? = null
        var outcomes = 0
        val qualification = CompilerQualification(f.rule, input.context.scope.id, "2.4.21", f.installation.digest,
            f.configuration, Digests.sha256("synthetic eligibility only"), assertNotNull(input.context.modelDigest),
            assertNotNull(input.context.dependencyDigest), Digests.sha256(ContractJson.encodeToString(input.context.compilerArguments)))
        val provider = KotlinJvmCompilerProvider(f.installation, authority = ExecutionAuthority { selected ->
            permission?.let { ExactExecutionAuthority(it).authorize(selected) }
        }, qualifications = listOf(qualification), runner = IsolatedProcessRunner { outcomes++ })
        val profile = provider.executionProfile(input)
        val child = withBridgeTestProfiles(input, mapOf(provider.id to profile)).copy(execution = profile,
            outputRoot = f.work.resolve("legacy-output").toString())
        assertTrue(legacyDeclaredSingleCompilation(child))
        assertFalse(modelBearingInput(child))
        assertEquals("execution_not_authorized", provider.analyze(child).reasons.single())
        permission = genericPermit(child) { unitStop() }
        assertEquals("unit_stop_before_launch", provider.analyze(child).reasons.single())
        assertNull(provider.qualificationFor(f.rule, child))
        assertEquals(0, outcomes)
    }

    @Test fun legacyExceptionRejectsCompleteUnknownOrPartiallyRewrittenModelShapes(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = KotlinJvmCompilerProvider(f.installation)
        val input = workbenchLegacyInput(f)
        val profile = provider.executionProfile(input)
        val changes = listOf(input.copy(context = input.context.copy(fullProject = true)),
            input.copy(context = input.context.copy(policies = input.context.policies - "ktchecker.coverage")),
            input.copy(context = input.context.copy(policies = input.context.policies + ("ktchecker.coverage" to "full-project"))),
            input.copy(context = input.context.copy(policies = input.context.policies + ("ktchecker.model" to "unknown-model-kind"))),
            input.copy(context = input.context.copy(policies = input.context.policies + ("ktchecker.model-certificate" to ""))),
            input.copy(context = input.context.copy(policies = input.context.policies + ("ktchecker.model-future-authority" to "asserted"))),
            input.copy(execution = profile.copy(arguments = profile.arguments + (KotlinJvmModelBinding.PROFILE_PROPERTY + Digests.sha256("fake")))))
        changes.forEach { changed ->
            assertTrue(modelBearingInput(changed))
            expectModelFailure("compiler_model_binding_required") { provider.executionProfile(changed) }
            assertEquals("compiler_model_route_required", provider.analyze(changed).reasons.single())
        }
    }

    @Test fun retainedModelRouteCannotDowngradeEvenToExactBackwardCompatibleLegacyShape(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val gate = Gate(f)
        val preparation = f.prepare(gate.provider)
        val archive = f.archive(preparation.prepared)
        gate.modelPermit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        val stripped = stripModelEvidence(archive.child, f.installation.digest)
        val disguised = stripped.copy(context = stripped.context.copy(fullProject = false,
            policies = stripped.context.policies + mapOf("ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project")))
        assertTrue(legacyDeclaredSingleCompilation(disguised))
        assertEquals("compiler_model_not_authorized", gate.provider.analyze(disguised).reasons.single())
        gate.returnPermitForForeignInput = true
        assertEquals("compiler_model_attempt_mismatch", gate.provider.analyze(disguised).reasons.single())
        assertEquals(0, gate.executionLookups)
        assertEquals(2, archive.verifications)
        assertEquals(0, gate.processOutcomes)
        gate.modelPermit!!.close()
    }

    private class Gate(fixture: KotlinJvmModelBindingFixtures) {
        var modelPermit: KotlinJvmModelPermit? = null
        var executionPermit: ExecutionPermit? = null
        var returnPermitForForeignInput = false
        var executionLookups = 0
        var processOutcomes = 0
        val provider = KotlinJvmCompilerProvider(fixture.installation,
            authority = ExecutionAuthority { input ->
                executionLookups++
                executionPermit?.let { ExactExecutionAuthority(it).authorize(input) }
            }, runner = IsolatedProcessRunner { processOutcomes++ }, inputMode = KotlinJvmInputMode.VERIFIED_MODEL,
            modelAuthority = KotlinJvmModelAuthority { input ->
                modelPermit?.takeIf { returnPermitForForeignInput || it.accepts(input) }
            })
    }

    private fun stripModelEvidence(input: AnalysisInput, installedDigest: String) = input.copy(
        context = input.context.copy(toolchainDigest = installedDigest,
            policies = input.context.policies - "ktchecker.model" - "ktchecker.model-certificate"),
        execution = input.execution?.let { profile -> profile.copy(arguments = profile.arguments.filterNot {
            it.startsWith(KotlinJvmModelBinding.PROFILE_PROPERTY) }) })

    private fun genericPermit(input: AnalysisInput, verify: () -> Unit) = ExecutionPermit(assertNotNull(input.execution),
        input.snapshot.snapshotId, Path.of(input.materializedRoot), input.analysisKey(), verify)

    /** Mirrors Workbench.prepare's existing deliberately partial owner-declared context fields. */
    private fun workbenchLegacyInput(f: KotlinJvmModelBindingFixtures): AnalysisInput {
        val scope = RuleContext("jvm", "main", ":fixture", "main")
        val files = f.snapshot.files.filter { it.path.endsWith(".kt") }.map { it.path }.sorted()
        val arguments = listOf("-jvm-target", "21")
        val libraries = emptyMap<String, String>()
        val classpath = f.dependencies.map { ArtifactIdentity(it.coordinate, assertNotNull(it.digest), it.path) }
        val modelDigest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("kind", "owner-declared-single-compilation"); put("scope", ContractJson.encodeToJsonElement(scope))
            put("compilerVersion", "2.4.21"); put("files", ContractJson.encodeToJsonElement(files))
            put("arguments", ContractJson.encodeToJsonElement(arguments)); put("libraries", ContractJson.encodeToJsonElement(libraries))
        }))
        return AnalysisInput(f.snapshot, f.sourceRoot.toString(), AnalysisContext(scope = scope, compilerVersion = "2.4.21",
            classpath = classpath, compilerArguments = arguments, libraries = libraries,
            policies = mapOf("ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project",
                "ktchecker.activation-plan" to Digests.canonical(buildJsonObject {
                    put("policyDigest", f.configuration); put("schemaVersion", 1)
                    putJsonArray("decisions") { add(buildJsonObject {
                        put("ruleId", f.rule); put("active", true); put("origin", "explicit-request"); put("reason", "enabled")
                    }) }
                })),
            modelDigest = modelDigest, dependencyDigest = Digests.sha256(ContractJson.encodeToJsonElement(classpath).toString()),
            toolchainDigest = f.installation.digest, configurationDigest = f.configuration, fullProject = false, compilationFiles = files),
            requiredRuleIds = listOf(f.rule))
    }

    private fun guardedGenericPermit(input: AnalysisInput, firstCheck: () -> Unit): ExecutionPermit {
        var count = 0
        return genericPermit(input) {
            if (++count > 1) throw ExecutionProblem("unit_fallback_stop_before_launch", "The synthetic launcher must never execute.")
            firstCheck()
        }
    }

    private fun unitStop(): Nothing = throw ExecutionProblem("unit_stop_before_launch", "Synthetic authority test, no process execution.")
}
