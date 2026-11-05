package dev.ktchecker.compiler

import dev.ktchecker.compiler.live.CompilerApplicationProblem
import dev.ktchecker.compiler.live.model.ObservedJvmProducedOutputPreview
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * Nominal-origin and fail-closed unit controls with real registered SnapshotStore imports and
 * SYNTHETIC ordinary compiler/JDK bytes. No pinned graph, generated binding, launch, attempt,
 * Accepted output or compiler execution is fabricated. The fixture launcher must never execute.
 */
class ObservedJvmModelOriginTest {
    private fun observed(f: KotlinJvmModelBindingFixtures,
        clock: OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(120_000)): ObservedProjectModel {
        val invocation = ObservedExportInvocation(clock)
        val runtime = f.installation.runtimeImage.view().entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.map {
            ModelArtifact("compilation-jdk:${it.relativePath}", f.installation.runtimeImage.root.resolve(it.relativePath).toString(), it.digest, it.size)
        }
        // Owner-selected fixture registry is frozen BEFORE serializing/parsing the report.
        val registered = (f.compilerArtifacts + f.dependencies + runtime).associateBy { it.path }
        val graph = f.graph()
        val raw = ContractJson.encodeToString(graph).toByteArray()
        val request = ModelImportRequest(f.namespace, f.snapshot, f.sourceRoot.toString(), f.configuration,
            Digests.sha256(raw), GradleModelExporter.scriptDigest(), invocation.identity)
        val permit = ObservedExportPermit(request, invocation, Digests.sha256("SYNTHETIC runtime; no Gradle launch"),
            Digests.sha256("SYNTHETIC independent observations; incomplete configuration closure"),
            verifySnapshot = {
                check(f.store.snapshot(f.snapshotIdentity, f.snapshot.snapshotId).toContractManifest() == f.snapshot)
                val files = Files.walk(f.sourceRoot).use { paths -> paths.peek { check(!Files.isSymbolicLink(it)) }
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map { f.sourceRoot.relativize(it).toString().replace('\\', '/') }.toList().sorted() }
                check(files == f.snapshot.files.map { it.path }.sorted())
                f.snapshot.files.forEach { file ->
                    val path = f.sourceRoot.resolve(file.path)
                    check(Files.size(path) == file.size && Digests.sha256(Files.readAllBytes(path)) == file.digest)
                }
            }, verifyExecutedExport = { check(request.exportDigest == Digests.sha256(raw)) },
            verifyArtifact = { check(registered[it.path] == it) }, verifyObservedInputs = { check(it == graph) })
        return ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(request, raw)
    }

    @Test fun observedOriginRetainsActualTokenButItsVerifiedViewAlwaysFailsClosed() {
        KotlinJvmModelBindingFixtures().use { f ->
            val model = observed(f)
            val origin = JvmCompilerModelOrigin.ObservedOneShotOrigin(model)
            assertSame(model, origin.observed)
            assertEquals("compiler_model_observed_origin_forbidden", assertFailsWith<KotlinJvmModelException> {
                origin.verified
            }.code)
            val facts = origin.context(f.compilationId)
            assertEquals(ModelCompleteness.UNAVAILABLE, facts.completeness)
            assertFalse(assertNotNull(facts.context).fullProject)
            assertNull(facts.certificateId); assertNull(origin.graphIdentity())
            assertEquals(model.identity, origin.identity)
        }
    }

    @Test fun originalClockRequiresObjectIdentityNotAnEqualDurationOrFreshBudget() {
        KotlinJvmModelBindingFixtures().use { f ->
            val clock = OwnedApplicationDeadline.afterMillis(120_000)
            val model = observed(f, clock)
            val origin = JvmCompilerModelOrigin.ObservedOneShotOrigin(model)
            origin.requireOriginalDeadline(clock)
            val replacement = OwnedApplicationDeadline.afterMillis(clock.durationMillis)
            assertEquals(clock.durationMillis, replacement.durationMillis)
            assertNotSame(clock, replacement)
            assertEquals("compiler_model_observed_original_deadline_mismatch", assertFailsWith<KotlinJvmModelException> {
                origin.requireOriginalDeadline(replacement)
            }.code)
            assertSame(clock, model.invocation.originalDeadline)
        }
    }

    @Test fun everyRawObservedPolicyAndIncompleteStateMustMatchTheRetainedTypedOrigin() {
        KotlinJvmModelBindingFixtures().use { f ->
            val model = observed(f)
            val origin = JvmCompilerModelOrigin.ObservedOneShotOrigin(model)
            val context = assertNotNull(origin.context(f.compilationId).context)
            origin.requireObservedPolicies(context)
            for (key in listOf(OBSERVED_ASSURANCE_POLICY, OBSERVED_MODEL_EVIDENCE_POLICY,
                    OBSERVED_INVOCATION_POLICY, OBSERVED_CLOSURE_POLICY)) {
                for (policies in listOf(context.policies - key, context.policies + (key to "retargeted"))) {
                    assertEquals("compiler_model_observed_evidence_mismatch", assertFailsWith<KotlinJvmModelException> {
                        origin.requireObservedPolicies(context.copy(policies = policies))
                    }.code)
                }
            }
            assertEquals("compiler_model_observed_evidence_mismatch", assertFailsWith<KotlinJvmModelException> {
                origin.requireObservedPolicies(context.copy(fullProject = true))
            }.code)
        }
    }

    @Test fun ordinarySyntheticObservedImportCannotManufactureAProducedPreviewOrBinding() {
        KotlinJvmModelBindingFixtures().use { f ->
            val model = observed(f)
            assertNull(model.jvmProducedGraph)
            assertEquals("compiler_model_observed_graph_unavailable", assertFailsWith<CompilerApplicationProblem> {
                ObservedJvmProducedOutputPreview.create(model, f.installation, model.invocation.originalDeadline)
            }.code)
            assertEquals("compiler_model_observed_original_deadline_mismatch", assertFailsWith<KotlinJvmModelException> {
                ObservedJvmProducedOutputPreview.create(model, f.installation, OwnedApplicationDeadline.afterMillis(120_000))
            }.code)
        }
    }

    @Test fun noReservedObservedKeyCanHideInsideTheLegacyLimitedInputException() {
        KotlinJvmModelBindingFixtures().use { f ->
            val legacy = AnalysisInput(f.snapshot, f.sourceRoot.toString(), AnalysisContext(
                RuleContext("customJvm", "main", ":fixture", "main"), policies = mapOf(
                    "ktchecker.model" to "owner-declared-single-compilation", "ktchecker.coverage" to "partial-project"),
                fullProject = false), listOf(f.rule))
            assertTrue(legacyDeclaredSingleCompilation(legacy)); assertFalse(modelBearingInput(legacy))
            val keys = listOf(OBSERVED_ASSURANCE_POLICY, OBSERVED_MODEL_EVIDENCE_POLICY, OBSERVED_INVOCATION_POLICY,
                OBSERVED_CLOSURE_POLICY, OBSERVED_PLAN_EVIDENCE_POLICY, "ktchecker.observed-future", "ktchecker.observed-")
            keys.forEach { key ->
                val marked = legacy.copy(context = legacy.context.copy(policies = legacy.context.policies + (key to "synthetic marker")))
                assertFalse(legacyDeclaredSingleCompilation(marked), key)
                assertTrue(modelBearingInput(marked), key)
            }
        }
    }

    @Test fun verifiedFactoryAndItsOriginalArtifactOrderRemainSeparateFromObservedOrigin() {
        KotlinJvmModelBindingFixtures().use { f ->
            val observed = observed(f)
            val verified = f.importModel()
            val binding = KotlinJvmModelBinding.verifyAndBind(verified, f.compilationId, f.installation)
            assertSame(verified, binding.model); assertNull(binding.observedModel)
            assertEquals(f.compilerArtifacts, binding.compilation.toolchain!!.compilerArtifacts)
            assertEquals(f.dependencies.map { it.path }, binding.originalContext.classpath.map { it.path })
            assertFalse(VerifiedProjectModel::class.java.isInstance(observed))
            assertEquals(ModelCompleteness.COMPLETE, verified.context(f.compilationId, f.configuration).completeness)
            assertFalse(assertNotNull(observed.context(f.compilationId, f.configuration).context).fullProject)
        }
    }
}
