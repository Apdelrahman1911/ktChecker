package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Exact synthetic kernel-observer tuples; these are not real kernel/process execution receipts. */
class KotlinJvmModelAttemptTest {
    @Test fun archiveChangesOnlySourceDependencyLocationsAndAttemptOutput(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val original = preparation.prepared.original
        val archive = f.archive(preparation.prepared)
        val permit = preparation.prepared.bindAttempt(original, archive.effective, archive.child, archive::verify)
        assertEquals(2, archive.verifications)
        assertEquals(original.requiredRuleIds, archive.effective.requiredRuleIds)
        assertEquals(listOf(f.rule), archive.child.requiredRuleIds)
        assertEquals(original.context.toolchainDigest, archive.child.context.toolchainDigest)
        assertNotEquals(f.installation.digest, archive.child.context.toolchainDigest)
        assertEquals(original.context.compilationFiles, archive.child.context.compilationFiles)
        assertEquals(original.context.compilerArguments, archive.child.context.compilerArguments)
        assertEquals(original.context.policies, archive.child.context.policies)
        assertEquals(preparation.prepared.compilerProfile, archive.child.execution)
        assertEquals(archive.child.analysisKey(), permit.expectedAnalysisKey)
        assertTrue(permit.accepts(archive.child))
        assertTrue(permit.matches(provider, archive.child))
        assertSame(permit, ExactKotlinJvmModelAuthority(permit).authorize(archive.child))
        permit.verify(provider, archive.child) {}
        assertEquals(3, archive.verifications)
        permit.close()
    }

    @Test fun exactWholeOriginalIsRequiredBeforeAnyArchiveVerifierRuns(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val preparation = f.prepare(modelProvider(f))
        val original = preparation.prepared.original
        val archive = f.archive(preparation.prepared)
        val alternatives = listOf(original.copy(requiredRuleIds = listOf(f.rule)),
            original.copy(changedPaths = emptyList()), original.copy(baselineSnapshotId = Digests.sha256("foreign-baseline")),
            original.copy(materializedRoot = f.work.resolve("same-byte-alias").toString()),
            original.copy(context = original.context.copy(toolchainDigest = f.installation.digest)),
            original.copy(context = original.context.copy(policies = original.context.policies - "ktchecker.model")))
        alternatives.forEach { changed -> expectModelFailure("compiler_model_original_mismatch") {
            preparation.prepared.bindAttempt(changed, archive.effective, archive.child, archive::verify)
        } }
        assertEquals(0, archive.verifications)
    }

    @Test fun effectiveModelPoliciesArgumentsMembershipAndParentRulesCannotChange(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val preparation = f.prepare(modelProvider(f))
        val archive = f.archive(preparation.prepared)
        val effective = archive.effective
        val alternatives = listOf(effective.copy(requiredRuleIds = listOf(f.rule)),
            effective.copy(changedPaths = emptyList()), effective.copy(baselineSnapshotId = Digests.sha256("new-baseline")),
            effective.copy(snapshot = effective.snapshot.copy(scopeExclusions = listOf("unvisited-consumer"))),
            effective.copy(context = effective.context.copy(toolchainDigest = f.installation.digest)),
            effective.copy(context = effective.context.copy(compilerArguments = effective.context.compilerArguments + "-nowarn")),
            effective.copy(context = effective.context.copy(compilationFiles = effective.context.compilationFiles!!.reversed())),
            effective.copy(context = effective.context.copy(policies = effective.context.policies - "ktchecker.model")),
            effective.copy(execution = preparation.prepared.compilerProfile))
        alternatives.forEach { changed -> expectModelFailure("compiler_model_effective_mismatch") {
            preparation.prepared.bindAttempt(preparation.input, changed,
                changed.copy(requiredRuleIds = preparation.prepared.compilerRuleIds, execution = preparation.prepared.compilerProfile), archive::verify)
        } }
        assertEquals(0, archive.verifications)
    }

    @Test fun equalByteDuplicateDependenciesStillRequireExactOrdinalArchivePaths(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val preparation = f.prepare(modelProvider(f))
        val archive = f.archive(preparation.prepared)
        val original = preparation.input.context.classpath
        assertEquals(original[0].coordinate, original[1].coordinate)
        assertEquals(original[0].digest, original[1].digest)
        assertNotEquals(original[0].path, original[1].path)
        val classpaths = listOf(archive.effective.context.classpath.reversed(), original,
            archive.effective.context.classpath.dropLast(1), archive.effective.context.classpath + archive.effective.context.classpath.first(),
            archive.effective.context.classpath.map { it.copy(coordinate = "same-bytes-wrong-coordinate") })
        classpaths.forEach { classpath ->
            val changed = archive.effective.copy(context = archive.effective.context.copy(classpath = classpath))
            expectModelFailure("compiler_model_classpath_mapping_mismatch") {
                preparation.prepared.bindAttempt(preparation.input, changed,
                    changed.copy(requiredRuleIds = preparation.prepared.compilerRuleIds, execution = preparation.prepared.compilerProfile), archive::verify)
            }
        }
        assertEquals(0, archive.verifications)
    }

    @Test fun archiveLayoutOutputOverlapAndChangedChildRejectWithoutCallbacks(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val preparation = f.prepare(modelProvider(f))
        val archive = f.archive(preparation.prepared)
        val effective = archive.effective
        expectModelFailure("compiler_model_archive_layout_mismatch") {
            preparation.prepared.bindAttempt(preparation.input, effective.copy(materializedRoot = f.sourceRoot.toString()), archive.child, archive::verify)
        }
        expectModelFailure("compiler_model_path_invalid") {
            preparation.prepared.bindAttempt(preparation.input, effective.copy(materializedRoot = effective.materializedRoot + "/../sources"), archive.child, archive::verify)
        }
        val archiveParent = Path.of(effective.materializedRoot).parent
        for (output in listOf(archiveParent, archiveParent.resolve("output"), archiveParent.parent)) {
            expectModelFailure("compiler_model_output_overlap") {
                preparation.prepared.bindAttempt(preparation.input, effective.copy(outputRoot = output.toString()), archive.child, archive::verify)
            }
        }
        val children = listOf(archive.child.copy(requiredRuleIds = preparation.input.requiredRuleIds), archive.child.copy(execution = null),
            archive.child.copy(execution = preparation.prepared.compilerProfile.copy(approved = false)),
            archive.child.copy(execution = preparation.prepared.compilerProfile.copy(arguments = preparation.prepared.compilerProfile.arguments.filterNot {
                it.startsWith(KotlinJvmModelBinding.PROFILE_PROPERTY) })),
            archive.child.copy(outputRoot = f.work.resolve("other-attempt-output").toString()))
        children.forEach { child -> expectModelFailure("compiler_model_child_mismatch") {
            preparation.prepared.bindAttempt(preparation.input, effective, child, archive::verify)
        } }
        assertEquals(0, archive.verifications)
    }

    @Test fun archivedAttemptsSurviveOriginalSourceAndDependencyRemoval(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val archive = f.archive(preparation.prepared)
        f.removeOriginalInputs()
        assertFalse(Files.exists(f.sourceRoot))
        assertTrue(f.dependencies.none { Files.exists(Path.of(it.path)) })
        val first = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        repeat(3) { first.verify(provider, archive.child) {} }
        assertEquals(preparation.input.context.compilationFiles, CompilerSnapshot.read(archive.child).map { it.relativePath })
        assertEquals(2, CompilerSnapshot.classpath(archive.child.context.classpath).size)
        // Same already-frozen archive, new attempt output: a distinct exact permit is still required.
        val retryOutput = Files.createDirectories(f.work.resolve("retry-output"))
        val retry = archive.effective.copy(outputRoot = retryOutput.toString())
        val retryChild = retry.copy(requiredRuleIds = preparation.prepared.compilerRuleIds, execution = preparation.prepared.compilerProfile)
        assertFalse(first.accepts(retryChild))
        val second = preparation.prepared.bindAttempt(preparation.input, retry, retryChild, archive::verify)
        second.verify(provider, retryChild) {}
        // New admission, unlike an accepted archive attempt, must still verify original dependencies.
        assertFails { preparation.binding.bindPreparedInput(preparation.input, listOf(f.rule), provider, preparation.profiles) }
        first.close(); second.close()
    }

    @Test fun wholeFrozenResourceAndDependencyDriftInvalidatePermitVerification(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val archive = f.archive(preparation.prepared)
        val permit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        val paths = listOf(Path.of(archive.effective.materializedRoot).resolve("src/main/resources/state.txt"),
            Path.of(archive.effective.context.classpath.first().path))
        paths.forEach { path ->
            val bytes = Files.readAllBytes(path)
            path.toFile().setWritable(true, true)
            try {
                Files.write(path, bytes.map { (it.toInt() xor 1).toByte() }.toByteArray())
                assertTrue(permit.accepts(archive.child)) // Lookup is pure identity, not a byte attestation.
                assertFailsWith<IllegalStateException> { permit.verify(provider, archive.child) {} }
            } finally { Files.write(path, bytes) }
        }
        permit.verify(provider, archive.child) {}
        permit.close()
    }

    @Test fun installedImageMustStillVerifyAfterOriginalInputsDisappear(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val archive = f.archive(preparation.prepared)
        val permit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        f.removeOriginalInputs()
        Files.delete(f.work.resolve("jdk/empty-runtime-directory"))
        assertEquals("compiler_java_runtime_changed", assertFailsWith<KotlinJvmRuntimeException> {
            permit.verify(provider, archive.child) {}
        }.code)
        permit.close()
    }

    @Test fun foreignAttemptProviderAndRevokedCapabilitiesDoNotInvokeVerifier(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val archive = f.archive(preparation.prepared)
        val otherArchive = f.archive(preparation.prepared, "other")
        val permit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        val count = archive.verifications
        val otherProvider = modelProvider(f)
        assertFalse(permit.matches(otherProvider, archive.child))
        expectModelFailure("compiler_model_attempt_mismatch") { permit.verify(otherProvider, archive.child) {} }
        assertFalse(permit.accepts(otherArchive.child))
        expectModelFailure("compiler_model_attempt_mismatch") { permit.verify(provider, otherArchive.child) {} }
        assertNull(ExactKotlinJvmModelAuthority(permit).authorize(otherArchive.child))
        permit.close(); permit.close()
        assertFalse(permit.accepts(archive.child))
        expectModelFailure("compiler_model_attempt_mismatch") { permit.verify(provider, archive.child) {} }
        assertEquals(count, archive.verifications)
        assertEquals(0, otherArchive.verifications)
    }

    @Test fun revocationDuringInstallationCheckpointCannotSurviveRepeatedMatchCheck(): Unit = KotlinJvmModelBindingFixtures().use { f ->
        val provider = modelProvider(f)
        val preparation = f.prepare(provider)
        val archive = f.archive(preparation.prepared)
        val permit = preparation.prepared.bindAttempt(preparation.input, archive.effective, archive.child, archive::verify)
        expectModelFailure("compiler_model_attempt_mismatch") {
            permit.verify(provider, archive.child) { permit.close() }
        }
        assertFalse(permit.accepts(archive.child))
    }

    private fun modelProvider(fixture: KotlinJvmModelBindingFixtures) =
        KotlinJvmCompilerProvider(fixture.installation, inputMode = KotlinJvmInputMode.VERIFIED_MODEL)
}
