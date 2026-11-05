package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.*
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import java.util.AbstractList
import kotlin.test.*

/** Typed relation and archive parity controls only; all exporter/JDK/JAR fixtures are synthetic. */
class ModelLiveBindingTest {
    @Test fun preparationRetainsFullModelCustomTargetParentObligationsAndOriginalClock(): Unit = ModelLiveHostFixture().use { f ->
        assertEquals(f.original, f.prepared.original)
        assertEquals(f.original.analysisKey(), f.prepared.originalAnalysisKey)
        assertSame(f.originalDeadline, f.prepared.originalDeadline)
        assertSame(f.cold.binding.identity, f.prepared.bindingIdentity)
        assertEquals("customJvm", f.prepared.original.context.scope.target)
        assertEquals(listOf("src/main/kotlin/B.kt", "src/main/kotlin/A.kt"), f.prepared.original.context.compilationFiles)
        assertEquals(f.modelFixture.snapshot, f.prepared.original.snapshot)
        assertEquals(5, f.prepared.original.snapshot.files.size)
        assertEquals(2, f.prepared.original.requiredRuleIds.size)
        assertEquals(listOf(f.modelFixture.rule), f.prepared.compilerRuleIds)
        assertEquals(f.cold.input.context.compilerArguments, f.prepared.original.context.compilerArguments)
        assertEquals(f.cold.input.context.classpath, f.prepared.original.context.classpath)
        assertEquals(emptyModelColdProfilesDigest(), f.prepared.original.context.policies["ktchecker.execution-plan"])
        assertNull(f.prepared.original.execution)
        assertNull(f.prepared.original.outputRoot)
        assertNotEquals(f.installation.digest, f.original.context.toolchainDigest)
        assertNotEquals(f.cold.binding.model.identity.toolchainDigest, f.original.context.toolchainDigest)
        assertEquals(0, f.session.submissions)
    }

    @Test fun livePreparationUsesTheSameTypedModelValidationForContextAndIngressPolicies(): Unit = ModelLiveHostFixture().use { f ->
        val context = f.original.context
        val changed = listOf(context.copy(scope = context.scope.copy(target = "jvm")),
            context.copy(modelDigest = Digests.sha256("foreign-model")), context.copy(configurationDigest = Digests.sha256("foreign-config")),
            context.copy(dependencyDigest = Digests.sha256("foreign-dependencies")), context.copy(toolchainDigest = f.installation.digest),
            context.copy(classpath = context.classpath.reversed()), context.copy(compilerArguments = context.compilerArguments + "-nowarn"),
            context.copy(compilationFiles = context.compilationFiles!!.reversed()), context.copy(fullProject = false),
            context.copy(libraries = mapOf("invented" to "library")), context.copy(policies = context.policies - "ktchecker.model"),
            context.copy(policies = context.policies + ("unapproved-policy" to "not a permitted addition")))
        changed.forEach { value ->
            expectSharedModelFailure("compiler_model_context_mismatch") {
                f.cold.binding.bindPreparedLiveInput(f.original.copy(context = value), f.prepared.compilerRuleIds, f.provider, f.originalDeadline)
            }
        }
        expectSharedModelFailure("compiler_model_input_mismatch") {
            f.cold.binding.bindPreparedLiveInput(f.original.copy(snapshot = f.original.snapshot.copy(files = f.original.snapshot.files.dropLast(1))),
                f.prepared.compilerRuleIds, f.provider, f.originalDeadline)
        }
        assertEquals(0, f.authority.lookups)
    }

    @Test fun noColdPermitProfileOrEquivalentInstallationObjectSubstitutesForLivePreparation(): Unit = ModelLiveHostFixture().use { f ->
        expectModelLiveFailure("compiler_model_live_cold_profile_mismatch") {
            f.cold.binding.bindPreparedLiveInput(f.cold.input, f.prepared.compilerRuleIds, f.provider, f.originalDeadline)
        }
        expectModelLiveFailure("compiler_model_live_cold_execution_unsupported") {
            f.cold.binding.bindPreparedLiveInput(f.original.copy(execution = f.cold.prepared.compilerProfile),
                f.prepared.compilerRuleIds, f.provider, f.originalDeadline)
        }
        val equalInstallation = KotlinJvmToolchain(f.modelFixture.java, f.installation.compilerClasspath)
        assertEquals(f.installation.digest, equalInstallation.digest)
        val other = KotlinJvmModelLiveCompilerProvider(equalInstallation)
        expectModelLiveFailure("compiler_model_live_installation_mismatch") {
            f.cold.binding.bindPreparedLiveInput(f.original, f.prepared.compilerRuleIds, other, f.originalDeadline)
        }
        assertEquals(0, f.session.submissions)
    }

    @Test fun ownerArchiveAndChildPreserveEveryFieldExceptTheExactAllowedProjection(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        assertEquals(f.original, permit.original)
        assertEquals(archive.effective, permit.archived)
        assertEquals(archive.child, permit.input)
        assertEquals(f.original.requiredRuleIds, permit.archived.requiredRuleIds)
        assertEquals(f.prepared.compilerRuleIds, permit.input.requiredRuleIds)
        assertEquals(f.original.snapshot, permit.input.snapshot)
        assertEquals(f.original.context.policies, permit.input.context.policies)
        assertEquals(f.original.context.scope, permit.input.context.scope)
        assertEquals(f.original.context.compilationFiles, permit.input.context.compilationFiles)
        assertEquals(f.original.context.compilerArguments, permit.input.context.compilerArguments)
        assertEquals(f.original.context.toolchainDigest, permit.input.context.toolchainDigest)
        assertEquals(f.original.changedPaths, permit.input.changedPaths)
        assertNull(permit.archived.execution)
        assertNull(permit.input.execution)
        assertSame(f.originalDeadline, permit.originalDeadline)
        assertEquals(3, setOf(f.original.analysisKey(), permit.archivedInputKey, permit.providerInputKey).size)
        assertTrue(permit.accepts(archive.child))
        assertTrue(permit.matches(f.provider, ModelCompilerAttemptRequest(archive.child)))
        assertEquals(0, archive.registrations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
        permit.close()
    }

    @Test fun fullOwnerInputCannotBeSubstitutedBeforeArchiveCallbacks(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val changed = listOf(f.original.copy(requiredRuleIds = f.prepared.compilerRuleIds),
            f.original.copy(changedPaths = emptyList()), f.original.copy(baselineSnapshotId = Digests.sha256("other-baseline")),
            f.original.copy(materializedRoot = f.root.resolve("same-byte-alias").toString()),
            f.original.copy(context = f.original.context.copy(toolchainDigest = f.installation.digest)))
        changed.forEach { original -> expectModelLiveFailure("compiler_model_live_original_mismatch") {
            f.prepared.bindAttempt(original, archive.effective, archive.child, f.application, archive::verify, archive::register, archive::verifyMaterialization)
        } }
        assertEquals(0, archive.verifications)
    }

    @Test fun extractedArchiveRelationHasColdLiveAndPureFailureParity(): Unit = ModelLiveHostFixture().use { f ->
        val coldArchive = f.modelFixture.archive(f.cold.prepared, "cold-parity")
        val liveArchive = f.archive()
        val changes: List<Pair<String, (AnalysisInput) -> AnalysisInput>> = listOf(
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(requiredRuleIds = f.prepared.compilerRuleIds) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(changedPaths = emptyList()) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(baselineSnapshotId = Digests.sha256("other-baseline")) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(snapshot = it.snapshot.copy(files = it.snapshot.files.dropLast(1))) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(compilationFiles = it.context.compilationFiles!!.reversed())) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(compilerArguments = it.context.compilerArguments + "-nowarn")) },
            "compiler_model_effective_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(policies = it.context.policies - "ktchecker.model")) },
            "compiler_model_classpath_mapping_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(classpath = it.context.classpath.reversed())) },
            "compiler_model_classpath_mapping_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(classpath = it.context.classpath.dropLast(1))) },
            "compiler_model_classpath_mapping_mismatch" to { it: AnalysisInput -> it.copy(context = it.context.copy(classpath = it.context.classpath.map { a -> a.copy(coordinate = "foreign-coordinate") })) },
            "compiler_model_archive_layout_mismatch" to { it: AnalysisInput -> it.copy(materializedRoot = f.modelFixture.sourceRoot.toString()) },
            "compiler_model_archive_layout_mismatch" to { it: AnalysisInput -> it.copy(outputRoot = null) },
            "compiler_model_path_invalid" to { it: AnalysisInput -> it.copy(materializedRoot = it.materializedRoot + "/../sources") },
            "compiler_model_output_overlap" to { it: AnalysisInput -> it.copy(outputRoot = Path.of(it.materializedRoot).parent.resolve("output").toString()) },
        )
        requireJvmModelArchiveRelocation(f.cold.input, coldArchive.effective)
        requireJvmModelArchiveRelocation(f.original, liveArchive.effective)
        changes.forEach { (code, change) ->
            val coldChanged = change(coldArchive.effective)
            val liveChanged = change(liveArchive.effective)
            expectSharedModelFailure(code) { requireJvmModelArchiveRelocation(f.cold.input, coldChanged) }
            expectSharedModelFailure(code) { requireJvmModelArchiveRelocation(f.original, liveChanged) }
            expectSharedModelFailure(code) { f.cold.prepared.bindAttempt(f.cold.input, coldChanged, coldArchive.child, coldArchive::verify) }
            expectSharedModelFailure(code) {
                f.prepared.bindAttempt(f.original, liveChanged, liveArchive.child, f.application,
                    liveArchive::verify, liveArchive::register, liveArchive::verifyMaterialization)
            }
        }
        assertEquals(0, coldArchive.verifications)
        assertEquals(0, liveArchive.verifications)
    }

    @Test fun childCannotChangeRulesOptionsModelOutputOrIntroduceColdExecution(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val child = archive.child
        val changed = listOf(child.copy(requiredRuleIds = f.original.requiredRuleIds), child.copy(requiredRuleIds = emptyList()),
            child.copy(context = child.context.copy(compilerArguments = child.context.compilerArguments + "-nowarn")),
            child.copy(context = child.context.copy(modelDigest = Digests.sha256("foreign-model"))),
            child.copy(outputRoot = f.root.resolve("other-output").toString()))
        changed.forEach { value -> expectModelLiveFailure("compiler_model_live_child_mismatch") {
            f.prepared.bindAttempt(f.original, archive.effective, value, f.application, archive::verify, archive::register, archive::verifyMaterialization)
        } }
        expectModelLiveFailure("compiler_model_live_cold_execution_unsupported") {
            f.prepared.bindAttempt(f.original, archive.effective, child.copy(execution = f.cold.prepared.compilerProfile), f.application,
                archive::verify, archive::register, archive::verifyMaterialization)
        }
        assertEquals(0, archive.verifications)
    }

    @Test fun duplicateDependenciesRetainBothOccurrencesAndCannotSwapTheirArchiveOrdinals(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val permit = f.grant(archive)
        val artifacts = permit.input.context.classpath
        assertEquals(2, artifacts.size)
        assertEquals(artifacts[0].coordinate, artifacts[1].coordinate)
        assertEquals(artifacts[0].digest, artifacts[1].digest)
        assertEquals(listOf("artifact-0.jar", "artifact-1.jar"), artifacts.map { Path.of(it.path).fileName.toString() })
        assertEquals(2, permit.capture.dependencies.map { it.fileKey }.toSet().size)
        val changed = archive.effective.copy(context = archive.effective.context.copy(classpath = artifacts.reversed()))
        expectSharedModelFailure("compiler_model_classpath_mapping_mismatch") {
            f.prepared.bindAttempt(f.original, changed, archive.child, f.application, archive::verify, archive::register, archive::verifyMaterialization)
        }
        permit.close()
    }

    @Test fun acceptedArchiveNeverRereadsObsoleteOriginalSourceOrDependencyPaths(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        f.modelFixture.removeOriginalInputs()
        assertFalse(Files.exists(f.modelFixture.sourceRoot))
        assertTrue(f.modelFixture.dependencies.none { Files.exists(Path.of(it.path)) })
        val permit = f.grant(archive)
        repeat(3) { permit.verifyOriginal {} }
        assertEquals(f.original.context.compilationFiles!!.toSet(), permit.capture.sourceTexts.keys)
        assertEquals(f.original.snapshot.files.size, permit.capture.files.size)
        assertFails { f.reprepare() } // A fresh admission, unlike an existing archive, needs original dependencies.
        permit.close()
    }

    @Test fun preparationAndAttemptCollectionsAreDetachedAndImmutable(): Unit = ModelLiveHostFixture().use { f ->
        val rules = f.prepared.compilerRuleIds.toMutableList()
        val paths = f.original.changedPaths.toMutableList()
        val policies = f.original.context.policies.toMutableMap()
        val input = f.original.copy(changedPaths = paths, context = f.original.context.copy(policies = policies))
        val prepared = f.cold.binding.bindPreparedLiveInput(input, rules, f.provider, f.originalDeadline)
        rules.clear(); paths.clear(); policies.clear()
        assertEquals(f.prepared.compilerRuleIds, prepared.compilerRuleIds)
        assertEquals(f.original, prepared.original)
        val permit = f.grant(f.archive(prepared), prepared)
        assertFailsWith<UnsupportedOperationException> { (prepared.compilerRuleIds as MutableList<String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (prepared.original.snapshot.files as MutableList<SnapshotFile>).clear() }
        assertFailsWith<UnsupportedOperationException> { (permit.input.context.policies as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (permit.capture.sourceTexts as MutableMap<String, String>).clear() }
        assertFailsWith<UnsupportedOperationException> { (permit.input.context.classpath as MutableList<ArtifactIdentity>).clear() }
        permit.close()
    }

    @Test fun ruleLimitIsCheckedBeforeDetachingOrIteratingTheSuppliedProjection(): Unit = ModelLiveHostFixture().use { f ->
        val tooMany = object : AbstractList<String>() {
            override val size: Int get() = ModelLiveLimits.COMPILER_RULES + 1
            override fun get(index: Int): String = error("Oversized list must not be iterated")
        }
        expectModelLiveFailure("compiler_model_live_rule_projection_invalid") {
            f.cold.binding.bindPreparedLiveInput(f.original, tooMany, f.provider, f.originalDeadline)
        }
        for (rules in listOf(emptyList(), listOf(f.modelFixture.rule, f.modelFixture.rule), listOf("catalog.required-but-not-implemented"))) {
            expectModelLiveFailure("compiler_model_live_rule_projection_invalid") {
                f.cold.binding.bindPreparedLiveInput(f.original, rules, f.provider, f.originalDeadline)
            }
        }
    }

    @Test fun hostDelegatesExactUnchangedOptionValidationToTheWire() {
        val accepted = listOf(listOf("-jvm-target", "1.8"), listOf("-jvm-target", "11", "-progressive"),
            listOf("-nowarn", "-jvm-target", "17", "-Werror"),
            listOf("-language-version", "2.4", "-api-version", "2.4", "-jvm-target", "21", "-module-name", "_fixture"))
        accepted.forEach { tokens ->
            val mutable = tokens.toMutableList()
            requireModelLiveOptions(mutable)
            assertEquals(tokens, mutable)
            val detached = ModelWire.validateOptions(mutable)
            assertEquals(tokens, detached)
            mutable.clear()
            assertEquals(tokens, detached)
            assertFailsWith<UnsupportedOperationException> { (detached as MutableList<String>).clear() }
        }
        val rejected = listOf(emptyList(), listOf("-nowarn"), listOf("-jvm-target", "22"), listOf("-jvm-target", "8"),
            listOf("-jvm-target", "21", "-jvm-target", "21"), listOf("-jvm-target", "21", "-nowarn", "-nowarn"),
            listOf("-jvm-target", "21", "-module-name", "1module"), listOf("-jvm-target", "21", "-language-version", "2.5"),
            listOf("-jvm-target", "21", "-classpath", "/unapproved"), listOf("-jvm-target", "21", "@response.txt"),
            listOf("-jvm-target", "21", "-Xplugin=foreign.jar"), List(17) { "-nowarn" })
        rejected.forEach { tokens ->
            assertFailsWith<IllegalArgumentException> { ModelWire.validateOptions(tokens) }
            expectModelLiveFailure("compiler_model_live_options_unsupported") { requireModelLiveOptions(tokens) }
        }
    }
}
