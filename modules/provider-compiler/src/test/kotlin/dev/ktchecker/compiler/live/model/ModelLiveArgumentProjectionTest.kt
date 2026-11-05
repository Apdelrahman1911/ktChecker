package dev.ktchecker.compiler.live.model

import dev.ktchecker.contracts.*
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import kotlin.test.*

/**
 * SOURCE-ONLY authored controls. Synthetic legacy importer/JDK/JAR fixtures never execute.
 * Genuine original-full-argv success additionally requires the opt-in real exporter harness;
 * these controls do not create a counterfeit pinned compiler or an importer-layout bypass.
 */
class ModelLiveArgumentProjectionTest {
    @Test fun retainedOriginalArgumentBudgetIsNotTheWireOptionBudget() {
        val input = ModelRenderingControl().original
        val raw = List(ModelLiveLimits.ORIGINAL_ARGUMENTS) { "argument-$it" }
        val frozen = freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = raw)))
        assertEquals(raw, frozen.context.compilerArguments)
        assertEquals(16, ModelWire.MAX_OPTIONS)
        expectModelLiveFailure("compiler_model_live_options_unsupported") { requireModelLiveShape(frozen) }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = raw + "one-too-many")))
        }
    }

    @Test fun originalTokenBoundsAreUtf8BytesNotOnlyKotlinCharacterCount() {
        val input = ModelRenderingControl().original
        val atLimit = "é".repeat(ModelLiveLimits.ORIGINAL_ARGUMENT_BYTES / 2)
        assertEquals(atLimit, freezeModelLiveInput(input.copy(context = input.context.copy(
            compilerArguments = listOf(atLimit)))).context.compilerArguments.single())
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = listOf(atLimit + "é"))))
        }
        expectModelLiveFailure("compiler_model_live_input_limit") {
            freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = listOf("x\u0000y"))))
        }
    }

    @Test fun originalArgumentsAreDetachedWithoutDroppingReorderingOrDeduplication() {
        val input = ModelRenderingControl().original
        val raw = mutableListOf("-classpath", "/owner/original/first.jar:/owner/original/second.jar",
            "-d", "/owner/gradle-build/classes", "-jvm-target", "17", "-no-stdlib", "-no-reflect",
            "/owner/sources/B.kt", "/owner/sources/A.kt")
        val expected = raw.toList()
        val frozen = freezeModelLiveInput(input.copy(context = input.context.copy(compilerArguments = raw)))
        raw.reverse(); raw += "-nowarn"
        assertEquals(expected, frozen.context.compilerArguments)
        assertNotEquals(input.analysisKey(), frozen.analysisKey())
        assertFailsWith<UnsupportedOperationException> {
            (frozen.context.compilerArguments as MutableList<String>).add("-nowarn")
        }
        expectModelLiveFailure("compiler_model_live_options_unsupported") { requireModelLiveShape(frozen) }
    }

    @Test fun aDigestPolicyDoesNotGiveUntypedFullArgumentsExecutionSemantics() {
        val input = ModelRenderingControl().original
        val alleged = input.copy(context = input.context.copy(
            compilerArguments = listOf("-jvm-target", "17", "-classpath", "/claimed/a.jar"),
            policies = input.context.policies + ("ktchecker.argument-layout" to Digests.sha256("mere JSON claim"))))
        expectModelLiveFailure("compiler_model_live_options_unsupported") { requireModelLiveShape(alleged) }
    }

    @Test fun legacyBindingRetainsItsExactScalarAndSourceOrdering(): Unit = ModelLiveHostFixture().use { f ->
        assertNull(f.prepared.argumentLayoutDigest)
        assertEquals(f.original.context.compilerArguments, f.prepared.normalizedCompilerOptions)
        assertEquals(f.original.context.compilationFiles, f.prepared.executableCompilationFiles)
        requireModelLiveShape(f.original, f.prepared.binding)
        assertFailsWith<UnsupportedOperationException> {
            (f.prepared.executableCompilationFiles as MutableList<String>).reverse()
        }
        assertFailsWith<UnsupportedOperationException> {
            (f.prepared.normalizedCompilerOptions as MutableList<String>).add("-nowarn")
        }
        assertEquals(0, f.session.submissions)
    }

    @Test fun evenARealBindingCannotLendItsProjectionToRewrittenRawArguments(): Unit = ModelLiveHostFixture().use { f ->
        val variants = listOf(f.original.context.compilerArguments.reversed(),
            f.original.context.compilerArguments + "-nowarn", f.original.context.compilerArguments.dropLast(1))
        variants.forEach { raw -> expectModelLiveFailure("compiler_model_live_argument_projection_mismatch") {
            requireModelLiveShape(f.original.copy(context = f.original.context.copy(compilerArguments = raw)), f.prepared.binding)
        } }
        assertEquals(0, f.session.submissions)
    }

    @Test fun theRawMemberOrderIsBoundSeparatelyFromTheExecutableProjection(): Unit = ModelLiveHostFixture().use { f ->
        val reversed = requireNotNull(f.original.context.compilationFiles).reversed()
        expectModelLiveFailure("compiler_model_live_argument_projection_mismatch") {
            requireModelLiveShape(f.original.copy(context = f.original.context.copy(compilationFiles = reversed)), f.prepared.binding)
        }
        assertEquals(0, f.session.submissions)
    }

    @Test fun archiveCaptureNeedsTheSameBindingAndCannotNormalizeRawArgumentsItself(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val altered = archive.child.copy(context = archive.child.context.copy(
            compilerArguments = archive.child.context.compilerArguments + listOf("-classpath", "/unauthorized/dependency.jar")))
        expectModelLiveFailure("compiler_model_live_options_unsupported") { ModelArchiveCapture.capture(altered) {} }
        expectModelLiveFailure("compiler_model_live_argument_projection_mismatch") {
            ModelArchiveCapture.capture(altered, f.prepared.binding) {}
        }
        assertEquals(0, f.session.submissions)
    }

    @Test fun boundedRawInputLookupDoesNotBecomeAnExecutionGrant(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val raw = archive.child.copy(context = archive.child.context.copy(
            compilerArguments = listOf("-jvm-target", "17", "-classpath", "/unauthorized/dependency.jar")))
        val result = f.provider.analyze(raw)
        assertEquals("compiler_model_live_attempt_not_authorized", result.reasons.first())
        assertEquals(raw.requiredRuleIds, result.coverage.map { it.ruleId })
        assertFalse(result.completeFor(raw))
        assertEquals(1, f.authority.lookups)
        assertEquals(0, f.session.submissions)
    }

    @Test fun retainedLegacyPermitRejectsBoundedForeignRawInputBeforeCallbacks(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        f.authority.attempt = f.grant(archive)
        val verifications = archive.verifications
        val raw = archive.child.copy(context = archive.child.context.copy(compilerArguments = listOf("-nowarn")))
        val result = f.provider.analyze(raw)
        assertEquals("compiler_model_live_attempt_binding_mismatch", result.reasons.first())
        assertEquals(verifications, archive.verifications)
        assertEquals(0, archive.registrations)
        assertEquals(0, f.session.submissions)
    }
}
