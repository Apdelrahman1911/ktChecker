package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.SyntheticJdk
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Synthetic host-authority/filesystem controls ONLY. No Java launcher/compiler/native session runs. */
class CompilerLiveHostAuthorityTest {
    @Test fun fixtureCapabilityClassificationDoesNotHideUnexpectedIoOrProductionFailures() {
        for (capability in LiveFixtureCapability.entries) {
            assertTrue(unavailableLiveFixtureOperation(capability, UnsupportedOperationException()))
            assertFalse(unavailableLiveFixtureOperation(capability, IOException("synthetic unexpected I/O")))
            assertFalse(unavailableLiveFixtureOperation(capability, FileAlreadyExistsException("synthetic")))
            assertFalse(unavailableLiveFixtureOperation(capability, NoSuchFileException("synthetic")))
            assertFalse(unavailableLiveFixtureOperation(capability, FileSystemException("synthetic", null, "disk full")))
            assertFalse(unavailableLiveFixtureOperation(capability, CompilerApplicationProblem("synthetic_production_failure")))
            assertFalse(unavailableLiveFixtureOperation(capability, AssertionError("synthetic assertion")))
        }
        assertTrue(unavailableLiveFixtureOperation(LiveFixtureCapability.SYMBOLIC_LINKS, AccessDeniedException("synthetic")))
        assertTrue(unavailableLiveFixtureOperation(LiveFixtureCapability.SYMBOLIC_LINKS,
            FileSystemException("synthetic", null, "A required privilege is not held by the client.")))
        assertFalse(unavailableLiveFixtureOperation(LiveFixtureCapability.NOFOLLOW_READ, AccessDeniedException("synthetic")))
        assertFalse(unavailableLiveFixtureOperation(LiveFixtureCapability.FILE_KEYS, SecurityException("synthetic")))
    }

    @Test fun bothEntryPointsDenyByDefaultWithoutSubmitting(): Unit = LiveHostControl().use { f ->
        val open = assertIs<CompilerApplicationOpenResult.Unavailable>(KotlinJvmCompilerApplication.open(f.request))
        assertEquals(listOf("compiler_live_generation_not_authorized"), open.reasons)
        val result = KotlinJvmLiveCompilerProvider(f.application).analyze(f.input)
        assertEquals(listOf("compiler_live_attempt_not_authorized"), result.reasons)
        assertEquals(f.input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.findings.isEmpty())
        assertFalse(result.completeFor(f.input))
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun generationGrantNeedsExactRequestObjectAndCannotReplay(): Unit = LiveHostControl().use { f ->
        val permit = CompilerGenerationPermit(f.request, {}, {})
        val equalDescriptor = CompilerGenerationRequest(f.owner, f.namespace, f.installation, f.launch, f.generationDeadline)
        assertEquals("compiler_live_generation_binding_mismatch", assertFailsWith<CompilerApplicationProblem> {
            permit.consume(equalDescriptor)
        }.code)
        permit.consume(f.request)
        assertEquals("compiler_live_generation_permit_used", assertFailsWith<CompilerApplicationProblem> {
            permit.consume(f.request)
        }.code)
        assertEquals(0, f.session.submissions)
    }

    @Test fun repeatedOpenCannotRevokeTheAlreadyAdmittedGeneration(): Unit = LiveHostControl().use { f ->
        f.generationPermit.consume(f.request)
        val active = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        active.consume()
        val authority = retainedGenerationAuthority(f.generationPermit)
        repeat(2) {
            val result = assertIs<CompilerApplicationOpenResult.Unavailable>(KotlinJvmCompilerApplication.open(f.request, authority))
            assertEquals(listOf("compiler_live_generation_permit_used"), result.reasons)
            assertNull(result.cleanup)
        }
        f.generationPermit.checkLive()
        active.checkpoint()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun foreignGenerationAuthorityCannotConsumeOrRevokeTheOriginalGrant(): Unit = LiveHostControl().use { f ->
        val foreign = CompilerGenerationRequest(f.owner, f.namespace, f.installation, f.launch, f.generationDeadline)
        val authority = retainedGenerationAuthority(f.generationPermit)
        fun rejectForeign() {
            val result = assertIs<CompilerApplicationOpenResult.Unavailable>(KotlinJvmCompilerApplication.open(foreign, authority))
            assertEquals(listOf("compiler_live_generation_binding_mismatch"), result.reasons)
            assertNull(result.cleanup)
            f.generationPermit.checkLive()
        }
        rejectForeign()
        f.generationPermit.consume(f.request) // The wrong-request open did not steal this fresh grant.
        rejectForeign() // It cannot revoke an already-admitted original either.
        f.application.checkLive()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun admittedGenerationPreparationFailureStillRevokesBeforeItsOwnCancellation(): Unit = LiveHostControl().use { f ->
        val grant = CompilerGenerationPermit(f.request, { throw IllegalStateException("synthetic-private-preparation-detail") }, {})
        var cancellations = 0
        grant.bindCancellation {
            assertEquals("compiler_live_generation_revoked", assertFailsWith<CompilerApplicationProblem> { grant.checkLive() }.code)
            cancellations++
        }
        val result = assertIs<CompilerApplicationOpenResult.Unavailable>(
            KotlinJvmCompilerApplication.open(f.request, retainedGenerationAuthority(grant)))
        assertEquals(listOf("compiler_live_generation_verification_failed"), result.reasons)
        assertNull(result.cleanup)
        assertEquals(1, cancellations)
        f.generationPermit.checkLive() // A different grant's admitted failure did not touch this one.
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun publicGenerationRevokeClosesActualHostGateBeforeCancellationOutsideTheLock(): Unit = LiveHostControl().use { f ->
        val active = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        active.consume()
        val pool = Executors.newSingleThreadExecutor()
        try {
            f.session.onCancel = {
                assertEquals("compiler_live_generation_revoked", assertFailsWith<CompilerApplicationProblem> { f.generationPermit.checkLive() }.code)
                val gate = pool.submit<String> {
                    assertEquals("compiler_live_attempt_revoked", assertFailsWith<CompilerApplicationProblem> { active.checkpoint() }.code)
                    assertFailsWith<CompilerApplicationProblem> { f.application.checkLive() }.code
                }
                assertEquals("compiler_live_application_closed", gate.get(5, TimeUnit.SECONDS))
            }
            f.generationPermit.revoke()
            assertEquals(1, f.session.cancellations)
            active.revoke()
            f.application.cancel()
            assertEquals(1, f.session.cancellations)
            assertEquals(0, f.session.submissions)
        } finally { pool.shutdownNow() }
    }

    @Test fun generationRevocationBeforeBindingCannotPublishAReadyApplication(): Unit = LiveHostControl().use { f ->
        val grant = CompilerGenerationPermit(f.request, {}, {})
        val session = NeverSubmitSession(f.launch)
        grant.revoke()
        assertEquals("compiler_live_generation_revoked", assertFailsWith<CompilerApplicationProblem> {
            KotlinJvmCompilerApplication.bindingControls(f.request, grant, session)
        }.code)
        assertEquals(1, session.cancellations)
        assertEquals(0, session.submissions)
        f.application.checkLive()
        assertEquals(0, f.session.cancellations)
    }

    @Test fun generationRevocationRacingBindingNeverLeavesTheReturnedHostGateOpen(): Unit = LiveHostControl().use { f ->
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val grant = CompilerGenerationPermit(f.request, {}, {})
                val session = NeverSubmitSession(f.launch)
                val start = CountDownLatch(1)
                val bound = pool.submit<KotlinJvmCompilerApplication?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { KotlinJvmCompilerApplication.bindingControls(f.request, grant, session) }
                    catch (failure: CompilerApplicationProblem) {
                        assertTrue(failure.code in setOf("compiler_live_generation_revoked", "compiler_live_application_closed"))
                        null
                    }
                }
                val revoked = pool.submit<Unit> {
                    check(start.await(5, TimeUnit.SECONDS))
                    grant.revoke()
                }
                start.countDown()
                val application = bound.get(5, TimeUnit.SECONDS)
                revoked.get(5, TimeUnit.SECONDS)
                application?.let {
                    assertEquals("compiler_live_application_closed", assertFailsWith<CompilerApplicationProblem> { it.checkLive() }.code)
                }
                assertTrue(session.cancellations >= 1) // Bind/revoke overlap may both dispatch an idempotent cancel.
                assertEquals(0, session.submissions)
            }
            f.application.checkLive()
            assertEquals(0, f.session.cancellations)
        } finally { pool.shutdownNow() }
    }

    @Test fun selfDeclaredCompilerPinCannotReplaceActualInstalledArtifactBytes(): Unit = LiveHostControl().use { f ->
        assertEquals("compiler_live_installation_mismatch", assertFailsWith<CompilerApplicationProblem> {
            CompilerGenerationCapture.capture(f.request) {}
        }.code)
        assertEquals(0, f.session.submissions)
    }

    @Test fun attemptRetainsOriginalClockAndConsumesOnceBeforeAnyMaterialization(): Unit = LiveHostControl().use { f ->
        val provider = KotlinJvmLiveCompilerProvider(f.application)
        val deadline = OwnedApplicationDeadline.afterMillis(60_000)
        var registrations = 0
        val grant = f.grant(provider, deadline = deadline, register = { registrations++ })
        assertSame(deadline, grant.originalDeadline)
        assertTrue(grant.matches(provider, CompilerAttemptRequest(f.application, f.input)))
        grant.consume()
        assertEquals("compiler_live_attempt_permit_used", assertFailsWith<CompilerApplicationProblem> { grant.consume() }.code)
        assertEquals(0, registrations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun equalProviderInstanceCannotUseAnotherProvidersGrant(): Unit = LiveHostControl().use { f ->
        val authority = RetainedLiveAuthority()
        val first = KotlinJvmLiveCompilerProvider(f.application, authority)
        val second = KotlinJvmLiveCompilerProvider(f.application, authority)
        var callbacks = 0
        authority.attempt = f.grant(first, verify = { callbacks++ })
        val capturedCalls = callbacks
        val result = second.analyze(f.input)
        assertEquals(listOf("compiler_live_attempt_binding_mismatch"), result.reasons)
        assertEquals(capturedCalls, callbacks)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun foreignOwnerOrApplicationFailsBeforeOwnerCaptureCallbacks(): Unit = LiveHostControl().use { f ->
        val provider = KotlinJvmLiveCompilerProvider(f.application)
        var callbacks = 0
        assertEquals("compiler_live_attempt_binding_mismatch", assertFailsWith<CompilerApplicationProblem> {
            CompilerAttemptPermit(OwnedApplicationOwner(), provider, f.application, f.input,
                OwnedApplicationDeadline.afterMillis(60_000), { callbacks++ }, {}, {})
        }.code)
        val otherPermit = CompilerGenerationPermit(f.request, {}, {})
        val other = KotlinJvmCompilerApplication.bindingControls(f.request, otherPermit, NeverSubmitSession(f.launch))
        assertEquals("compiler_live_attempt_binding_mismatch", assertFailsWith<CompilerApplicationProblem> {
            CompilerAttemptPermit(f.owner, provider, other, f.input, OwnedApplicationDeadline.afterMillis(60_000),
                { callbacks++ }, {}, {})
        }.code)
        assertEquals(0, callbacks)
    }

    @Test fun fullOriginalIncludingRulesBaselineAndOptionsCannotBeReplaced(): Unit = LiveHostControl().use { f ->
        val provider = KotlinJvmLiveCompilerProvider(f.application)
        val rules = f.input.requiredRuleIds.toMutableList()
        val input = f.input.copy(requiredRuleIds = rules)
        val grant = f.grant(provider, input = input)
        rules += "ktc.compiler.common.unresolved_reference"
        assertEquals(f.input.requiredRuleIds, grant.original.requiredRuleIds)
        val variants = listOf(input, f.input.copy(changedPaths = listOf("Fixture.kt")),
            f.input.copy(baselineSnapshotId = Digests.sha256("foreign-baseline")),
            f.input.copy(materializedRoot = f.root.resolve("equal-byte-alias").toString()),
            f.input.copy(context = f.input.context.copy(compilerArguments = listOf("-jvm-target", "17", "-nowarn"))))
        variants.forEach { assertFalse(grant.matches(provider, CompilerAttemptRequest(f.application, it))) }
        assertFailsWith<UnsupportedOperationException> { (grant.original.requiredRuleIds as MutableList<String>).clear() }
        assertEquals(0, f.session.submissions)
    }

    @Test fun modelProfilesAndChangedOptionsAreExplicitlyUnsupportedBeforeAuthority(): Unit = LiveHostControl().use { f ->
        val authority = RetainedLiveAuthority()
        val provider = KotlinJvmLiveCompilerProvider(f.application, authority)
        val variants = listOf(
            f.input.copy(context = f.input.context.copy(modelDigest = Digests.sha256("imported-model"))),
            f.input.copy(context = f.input.context.copy(fullProject = true)),
            f.input.copy(context = f.input.context.copy(policies = mapOf("ktchecker.model" to "verified"))),
        )
        variants.forEach { assertEquals(listOf("compiler_live_model_profile_unsupported"), provider.analyze(it).reasons) }
        listOf(listOf("-jvm-target", "21"), listOf("-jvm-target", "17", "-Xplugin=foreign.jar"),
            listOf("-jvm-target", "17", "-Xfriend-paths=foreign.jar"), listOf("-nowarn"), emptyList()).forEach { flags ->
            assertEquals(listOf("compiler_live_options_unsupported"), provider.analyze(f.input.copy(
                context = f.input.context.copy(compilerArguments = flags))).reasons)
        }
        assertEquals(listOf("compiler_live_execution_profile_unsupported"), provider.analyze(f.input.copy(
            execution = TrustedExecution(f.root.resolve("never-executed").toString(), approved = true))).reasons)
        assertEquals(0, authority.lookups)
        assertEquals(0, f.session.submissions)
    }

    @Test fun effectiveInputOnlyChangesPreciselyReviewedPhysicalLocations(): Unit = LiveHostControl().use { f ->
        val source = f.generationRoot.resolve("jobs").resolve("1").resolve("sources")
        val output = f.generationRoot.resolve("jobs").resolve("1").resolve("classes")
        val effective = liveEffective(f.request, f.input, source, output)
        validateLiveEffective(f.request, f.input, effective, source, output)
        assertEquals(f.input.snapshot, effective.snapshot)
        assertEquals(f.input.context.compilerArguments, effective.context.compilerArguments)
        assertNotEquals(f.input.analysisKey(), effective.analysisKey())
        val variants = listOf(
            effective.copy(context = effective.context.copy(compilerArguments = listOf("-jvm-target", "21"))),
            effective.copy(context = effective.context.copy(classpath = effective.context.classpath.reversed())),
            effective.copy(context = effective.context.copy(toolchainDigest = Digests.sha256("different-installation"))),
            effective.copy(snapshot = effective.snapshot.copy(declaredRoots = listOf("src"))),
            effective.copy(requiredRuleIds = emptyList()), effective.copy(outputRoot = output.parent.toString()),
            effective.copy(changedPaths = listOf("Fixture.kt")),
        )
        variants.forEach { changed ->
            assertEquals("compiler_live_effective_input_mismatch", assertFailsWith<CompilerApplicationProblem> {
                validateLiveEffective(f.request, f.input, changed, source, output)
            }.code)
        }
    }

    @Test fun sameLengthOriginalSourceDriftAndAdditionalMembersAreRejected(): Unit = LiveHostControl().use { f ->
        val grant = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        val source = f.sourceRoot.resolve("Fixture.kt")
        val bytes = Files.readAllBytes(source)
        Files.write(source, bytes.map { (it.toInt() xor 1).toByte() }.toByteArray())
        assertFailsWith<CompilerApplicationProblem> { grant.capture.verify {} }
        Files.write(source, bytes)
        grant.capture.verify {}
        Files.writeString(f.sourceRoot.resolve("Extra.kt"), "fun extra() = 1")
        assertFailsWith<CompilerApplicationProblem> { grant.capture.verify {} }
        assertEquals(0, f.session.submissions)
    }

    @Test fun equalByteRootReplacementCannotReplaceRegisteredCapture(): Unit = LiveHostControl().use { f ->
        val grant = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        val old = f.root.resolve("old-original")
        Files.move(f.sourceRoot, old)
        Files.createDirectory(f.sourceRoot)
        Files.copy(old.resolve("Fixture.kt"), f.sourceRoot.resolve("Fixture.kt"))
        assertEquals("compiler_live_path_drift", assertFailsWith<CompilerApplicationProblem> { grant.capture.verify {} }.code)
    }

    @Test fun symbolicLinkCannotReplaceRegisteredCaptureWhenFixtureSupportsLinks(): Unit = LiveHostControl().use { f ->
        val grant = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        val old = f.root.resolve("old-original")
        Files.move(f.sourceRoot, old)
        liveFixtureOperation(LiveFixtureCapability.SYMBOLIC_LINKS) { Files.createSymbolicLink(f.sourceRoot, old) }
        // The capability gate surrounds link creation ONLY, never the production rejection below.
        assertFailsWith<CompilerApplicationProblem> { grant.capture.verify {} }
    }

    @Test fun malformedUtf8SourceCannotBeAdmitted(): Unit = LiveHostControl().use { f ->
        val bytes = byteArrayOf(0xc3.toByte())
        Files.write(f.sourceRoot.resolve("Fixture.kt"), bytes)
        val changed = f.input.copy(snapshot = f.input.snapshot.copy(snapshotId = Digests.sha256("malformed-utf8-capture"),
            files = listOf(SnapshotFile("Fixture.kt", Digests.sha256(bytes), bytes.size.toLong()))))
        assertEquals("compiler_live_source_not_utf8", assertFailsWith<CompilerApplicationProblem> {
            f.grant(KotlinJvmLiveCompilerProvider(f.application), input = changed)
        }.code)
    }

    @Test fun originalRequestDeadlineSpentInsideOwnerCallbackIsNotRenewed(): Unit = LiveHostControl().use { f ->
        val deadline = OwnedApplicationDeadline.afterMillis(40)
        assertEquals("compiler_live_request_deadline", assertFailsWith<CompilerApplicationProblem> {
            f.grant(KotlinJvmLiveCompilerProvider(f.application), deadline = deadline, verify = { Thread.sleep(70) })
        }.code)
        assertTrue(deadline.isExpired())
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun generationDeadlineSpentInPreparationIsNotRecreatedAtOpen(): Unit = LiveHostControl().use { f ->
        val original = OwnedApplicationDeadline.afterMillis(500)
        val request = CompilerGenerationRequest(f.owner, f.namespace, f.installation, f.launch, original)
        val grant = CompilerGenerationPermit(request, { Thread.sleep(550) }, {})
        val authority = object : KotlinJvmApplicationAuthority {
            override fun authorizeGeneration(request: CompilerGenerationRequest) = grant
            override fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit? = null
        }
        val result = assertIs<CompilerApplicationOpenResult.Unavailable>(KotlinJvmCompilerApplication.open(request, authority))
        assertSame(original, request.originalDeadline)
        assertEquals(listOf("compiler_live_generation_deadline"), result.reasons)
        assertNull(result.cleanup)
        assertEquals(0, f.session.submissions)
    }

    @Test fun outputReceiptsDetectSameLengthChangesAndRejectUnreviewedMembers(): Unit = LiveHostControl().use { f ->
        val output = Files.createDirectory(f.root.resolve("output-control"))
        val file = output.resolve("FixtureKt.class")
        Files.write(file, byteArrayOf(1, 2, 3))
        val first = CompilerTree.capture(output, CompilerWire.MAX_JOB_OUTPUT_FILES, CompilerWire.MAX_JOB_OUTPUT_BYTES, true) {}
        Files.write(file, byteArrayOf(1, 2, 4))
        val second = CompilerTree.capture(output, CompilerWire.MAX_JOB_OUTPUT_FILES, CompilerWire.MAX_JOB_OUTPUT_BYTES, true) {}
        assertNotEquals(first, second)
        assertEquals(first.bytes, second.bytes)
        Files.writeString(output.resolve("unreviewed.txt"), "not a compiler product")
        assertEquals("compiler_live_output_member_unsupported", assertFailsWith<CompilerApplicationProblem> {
            CompilerTree.capture(output, CompilerWire.MAX_JOB_OUTPUT_FILES, CompilerWire.MAX_JOB_OUTPUT_BYTES, true) {}
        }.code)
    }

    @Test fun nestedKotlinModuleProductsUseCanonicalLogicalReceiptNames(): Unit = LiveHostControl().use { f ->
        val output = Files.createDirectory(f.root.resolve("module-output-control"))
        val metadata = Files.createDirectory(output.resolve("META-INF"))
        Files.write(metadata.resolve("main.kotlin_module"), byteArrayOf(1, 2, 3))
        val receipt = CompilerTree.capture(output, CompilerWire.MAX_JOB_OUTPUT_FILES, CompilerWire.MAX_JOB_OUTPUT_BYTES, true) {}
        assertEquals(setOf("META-INF", "META-INF/main.kotlin_module"), receipt.entries.keys)
        assertEquals(3L, receipt.bytes)
    }

    @Test fun revocationPrecedesRuntimeCancellation(): Unit = LiveHostControl().use { f ->
        val grant = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        grant.consume()
        f.session.onCancel = { assertFailsWith<CompilerApplicationProblem> { f.generationPermit.checkLive() } }
        grant.revoke()
        assertTrue(f.session.cancellations > 0)
        assertFailsWith<CompilerApplicationProblem> { grant.checkpoint() }
        assertEquals(0, f.session.submissions)
    }

    @Test fun unusedPermitRevokeCannotCancelAnotherAdmittedPermit(): Unit = LiveHostControl().use { f ->
        val provider = KotlinJvmLiveCompilerProvider(f.application)
        val unused = f.grant(provider)
        val active = f.grant(provider)
        active.consume()
        unused.revoke()
        active.checkpoint()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun preAdmissionCancellationAuthorityErrorAndMismatchCannotCancelActiveOwner(): Unit = LiveHostControl().use { f ->
        val authority = RetainedLiveAuthority()
        val provider = KotlinJvmLiveCompilerProvider(f.application, authority)
        val active = f.grant(provider)
        active.consume()
        assertEquals(listOf("compiler_live_cancelled"), provider.analyze(f.input, CancellationToken { true }).reasons)
        assertEquals(listOf("compiler_live_cancellation_check_failed"), provider.analyze(f.input,
            CancellationToken { throw IllegalStateException("synthetic-private-cancellation-detail") }).reasons)
        authority.failure = IllegalStateException("synthetic-private-authority-detail")
        assertEquals(listOf("compiler_live_attempt_authority_failed"), provider.analyze(f.input).reasons)
        authority.failure = null
        authority.attempt = f.grant(KotlinJvmLiveCompilerProvider(f.application))
        assertEquals(listOf("compiler_live_attempt_binding_mismatch"), provider.analyze(f.input).reasons)
        active.checkpoint()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun replayAndConcurrentCoreAdmissionDoNotCancelTheExistingActivePermit(): Unit = LiveHostControl().use { f ->
        val provider = KotlinJvmLiveCompilerProvider(f.application)
        val active = f.grant(provider)
        active.consume()
        assertEquals("compiler_live_attempt_permit_used", assertFailsWith<CompilerApplicationProblem> {
            f.application.compile(active, CancellationToken.NONE)
        }.code)
        val other = f.grant(provider)
        assertEquals("compiler_live_concurrent_attempt", assertFailsWith<CompilerApplicationProblem> {
            f.application.compile(other, CancellationToken.NONE)
        }.code)
        active.checkpoint()
        assertFalse(other.matches(provider, CompilerAttemptRequest(f.application, f.input)))
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun cancellationBeforeAnalyzeCreatesNoJobDirectory(): Unit = LiveHostControl().use { f ->
        val result = KotlinJvmLiveCompilerProvider(f.application).analyze(f.input, CancellationToken { true })
        assertEquals("cancelled", result.status)
        assertTrue(result.findings.isEmpty())
        assertEquals(f.input.requiredRuleIds, result.coverage.map { it.ruleId })
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun syntheticBindingFactoryCannotExecuteEvenWithOwnerGrant(): Unit = LiveHostControl().use { f ->
        val authority = RetainedLiveAuthority()
        val provider = KotlinJvmLiveCompilerProvider(f.application, authority)
        var registrations = 0
        authority.attempt = f.grant(provider, register = { registrations++ })
        val result = provider.analyze(f.input)
        assertEquals(listOf("compiler_live_binding_control_no_execution"), result.reasons)
        assertEquals(0, registrations)
        assertEquals(0, f.session.submissions)
        assertFalse(result.complete())
    }

    @Test fun oversizedRequiredRowsAreExplicitlyUnresolvedRatherThanTruncated(): Unit = LiveHostControl().use { f ->
        val huge = f.input.copy(requiredRuleIds = List(1025) { "ktc.synthetic.rule_$it" })
        val result = KotlinJvmLiveCompilerProvider(f.application).analyze(huge)
        assertEquals(listOf("compiler_live_input_limit", "compiler_live_required_rows_unavailable"), result.reasons)
        assertTrue(result.coverage.isEmpty())
        assertFalse(result.completeFor(huge))
    }
}

private fun retainedGenerationAuthority(permit: CompilerGenerationPermit): KotlinJvmApplicationAuthority =
    object : KotlinJvmApplicationAuthority {
        override fun authorizeGeneration(request: CompilerGenerationRequest): CompilerGenerationPermit = permit
        override fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit? = null
    }

private class RetainedLiveAuthority : KotlinJvmApplicationAuthority {
    var attempt: CompilerAttemptPermit? = null
    var failure: Exception? = null
    var lookups = 0
    override fun authorizeGeneration(request: CompilerGenerationRequest): CompilerGenerationPermit? = null
    override fun authorizeAttempt(provider: KotlinJvmLiveCompilerProvider, request: CompilerAttemptRequest): CompilerAttemptPermit? {
        lookups++
        failure?.let { throw it }
        return attempt
    }
}

private class NeverSubmitSession(launch: Wire.Launch) : OwnedApplicationSession {
    private val submissionCount = AtomicInteger()
    private val cancellationCount = AtomicInteger()
    val submissions: Int get() = submissionCount.get()
    val cancellations: Int get() = cancellationCount.get()
    @Volatile var onCancel: () -> Unit = {}
    override val hello = Wire.Hello(launch.identity(), Wire.launchDigest(launch), "c".repeat(32), CompilerWire.COMPILER_VERSION, true, true)
    override fun <T : Any> submit(permit: OwnedApplicationJobPermit<T>, cancellation: CancellationToken): OwnedApplicationJobResult<T> {
        submissionCount.incrementAndGet()
        throw AssertionError("Synthetic host controls MUST NOT submit a runtime job")
    }
    override fun retire(): OwnedApplicationRetirement = throw AssertionError("Synthetic host controls have no cleanup receipt")
    override fun cancel() { cancellationCount.incrementAndGet(); onCancel() }
}

/** Setup-only prerequisites, never wrappers around a production call or its assertion. */
private enum class LiveFixtureCapability(val description: String) {
    SYMBOLIC_LINKS("symbolic-link creation"), FILE_KEYS("nonempty file keys"), NOFOLLOW_READ("NOFOLLOW_LINKS input streams"),
}

private fun unavailableLiveFixtureOperation(capability: LiveFixtureCapability, failure: Throwable): Boolean {
    if (failure is UnsupportedOperationException) return true
    if (capability != LiveFixtureCapability.SYMBOLIC_LINKS) return false
    if (failure is AccessDeniedException || failure is SecurityException) return true
    // Some providers report unavailable link privileges as plain FileSystemException. Keep the
    // allowlist narrow; unknown/localized reasons and unrelated I/O fail rather than becoming skips.
    if (failure.javaClass != FileSystemException::class.java) return false
    val reason = (failure as FileSystemException).reason?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)
    return reason != null && reason in setOf("a required privilege is not held by the client", "operation not permitted",
        "permission denied", "operation not supported", "symbolic links not supported")
}

private fun <T> liveFixtureOperation(capability: LiveFixtureCapability, action: () -> T): T = try {
    action()
} catch (failure: Exception) {
    if (!unavailableLiveFixtureOperation(capability, failure)) throw failure
    assumeTrue(false, "Selected synthetic fixture filesystem lacks ${capability.description}")
    throw AssertionError("Failed fixture assumption unexpectedly returned")
}

private fun liveHostFixtureRoot(): Path {
    val temporary = Files.createTempDirectory("ktc-live-host-control-")
    try {
        val root = temporary.toRealPath()
        val probe = root.resolve("fixture-capability-probe")
        Files.write(probe, byteArrayOf(1))
        try {
            val paths = buildList<Path> {
                var part = checkNotNull(root.root)
                add(part)
                for (name in root) { part = part.resolve(name); add(part) }
                add(probe)
            }
            for (path in paths) {
                val key = liveFixtureOperation(LiveFixtureCapability.FILE_KEYS) {
                    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
                }
                assumeTrue(!key.isNullOrBlank(), "Selected synthetic fixture filesystem requires nonempty file keys")
            }
            liveFixtureOperation(LiveFixtureCapability.NOFOLLOW_READ) {
                Files.newInputStream(probe, READ, NOFOLLOW_LINKS).use { check(it.read() == 1 && it.read() == -1) }
            }
        } finally { Files.deleteIfExists(probe) }
        return root
    } catch (failure: Throwable) {
        check(temporary.toFile().deleteRecursively()) { "Synthetic fixture prerequisite cleanup failed" }
        throw failure
    }
}

/** The admitted compiler digest deliberately does NOT match the fake installation: real open must fail. */
private class LiveHostControl : AutoCloseable {
    val generationDeadline = OwnedApplicationDeadline.afterMillis(120_000)
    val root: Path = liveHostFixtureRoot()
    val owner = OwnedApplicationOwner()
    val namespace = CompilerApplicationNamespace("synthetic-owner", "synthetic-workspace", "synthetic-worktree")
    val sourceRoot: Path = Files.createDirectory(root.resolve("original"))
    val generationRoot: Path = Files.createDirectory(root.resolve("generation"))
    private val home = root.resolve("jdk")
    private val java = SyntheticJdk.create(home, "Linux")
    private val names = listOf("kotlin-compiler-embeddable-2.4.21.jar", "kotlin-stdlib-2.4.21.jar", "annotations-13.0.jar")
    private val artifacts = names.mapIndexed { index, name ->
        val path = root.resolve(name)
        Files.writeString(path, "NEVER EXECUTED synthetic artifact $index")
        ArtifactIdentity("synthetic:$name", Digests.sha256(Files.readAllBytes(path)), path.toString())
    }
    val installation = KotlinJvmToolchain(java, artifacts)
    val launch: Wire.Launch
    val request: CompilerGenerationRequest
    val session: NeverSubmitSession
    val generationPermit: CompilerGenerationPermit
    val application: KotlinJvmCompilerApplication
    val input: AnalysisInput

    init {
        Files.createDirectory(generationRoot.resolve("compiler"))
        Files.createDirectory(generationRoot.resolve("jobs"))
        Files.createDirectory(generationRoot.resolve("tmp"))
        val bridgePath = generationRoot.resolve("bridge.jar")
        Files.writeString(bridgePath, "NEVER EXECUTED synthetic bridge")
        val bridge = CompilerWire.Artifact("synthetic:bridge", "bridge.jar", Digests.sha256(Files.readAllBytes(bridgePath)),
            Files.size(bridgePath), key(bridgePath))
        val copied = artifacts.mapIndexed { index, artifact ->
            val path = generationRoot.resolve("compiler").resolve("$index.jar")
            Files.copy(Path.of(artifact.path), path)
            CompilerWire.Artifact(artifact.coordinate, names[index], if (index == 0) CompilerWire.COMPILER_SHA256 else artifact.digest,
                Files.size(path), key(path))
        }
        val generation = CompilerWire.Generation(CompilerWire.PROFILE, CompilerWire.API, CompilerWire.COMPILER_VERSION, 17,
            CompilerWire.Jdk(Wire.Root(home.toString(), key(home)), java.toString(), installation.executableDigest,
                installation.runtimeImage.digest, 21), bridge, copied, listOf(1, 2))
        val registered = Wire.Root(generationRoot.toString(), key(generationRoot))
        val identity = Wire.Identity("a".repeat(32), owner.identity, namespace.digest, Digests.sha256("synthetic-host-only"),
            bridge.digest(), CompilerWire.compilerClosureDigest(generation), CompilerWire.toolchainDigest(generation),
            CompilerWire.API, CompilerWire.PROFILE)
        launch = Wire.Launch(identity, Wire.Limits(4, 1_048_576, 1_048_576), registered, generationRoot.toString(),
            CompilerWire.command(registered, generation), CompilerWire.environment(registered, generation), CompilerWire.encodeGeneration(generation))
        Files.write(generationRoot.resolve("launch.bin"), Wire.encodeLaunch(launch))
        request = CompilerGenerationRequest(owner, namespace, installation, launch, generationDeadline)
        session = NeverSubmitSession(launch)
        generationPermit = CompilerGenerationPermit(request, {}, {})
        application = KotlinJvmCompilerApplication.bindingControls(request, generationPermit, session)
        val bytes = "fun value() = 1\n".toByteArray(Charsets.UTF_8)
        Files.write(sourceRoot.resolve("Fixture.kt"), bytes)
        input = AnalysisInput(SnapshotManifest(Digests.sha256("synthetic-snapshot"), namespace.workspaceId, namespace.worktreeId,
            listOf(SnapshotFile("Fixture.kt", Digests.sha256(bytes), bytes.size.toLong())), namespace.ownerId,
            declaredRoots = listOf(".")), sourceRoot.toString(), AnalysisContext(RuleContext("jvm", "synthetic", ":synthetic", "main"),
            CompilerWire.COMPILER_VERSION, request.dependencyClasspath, CompilerWire.M0_OPTIONS,
            dependencyDigest = request.dependencyDigest, toolchainDigest = installation.digest,
            configurationDigest = request.configurationDigest, compilationFiles = listOf("Fixture.kt")),
            requiredRuleIds = listOf("ktc.compiler.common.senseless_comparison"))
    }

    fun grant(provider: KotlinJvmLiveCompilerProvider, input: AnalysisInput = this.input,
        deadline: OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(60_000), verify: () -> Unit = {},
        register: (CompilerJobMaterialization) -> Unit = {}): CompilerAttemptPermit =
        CompilerAttemptPermit(owner, provider, application, input, deadline, verify, register, {})

    override fun close() { root.toFile().deleteRecursively() }
    private fun key(path: Path): String = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()).toString()
}
