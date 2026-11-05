package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Binding/lifecycle controls only. NeverSubmitModelSession cannot fabricate Accepted or cleanup. */
class ModelLiveAuthorityTest {
    @Test fun bothPublicEntryPointsDenyByDefaultWithoutAnySubmissionOrJobCopy(): Unit = ModelLiveHostFixture().use { f ->
        val open = assertIs<ModelCompilerApplicationOpenResult.Unavailable>(KotlinJvmModelCompilerApplication.open(f.request))
        assertEquals(listOf("compiler_model_live_generation_not_authorized"), open.reasons)
        assertNull(open.cleanup)
        val archive = f.archive()
        val result = KotlinJvmModelLiveCompilerProvider(f.installation).analyze(archive.child)
        assertEquals("compiler_model_live_attempt_not_authorized", result.reasons.first())
        assertEquals(archive.child.requiredRuleIds, result.coverage.map { it.ruleId })
        assertTrue(result.findings.isEmpty())
        assertFalse(result.completeFor(archive.child))
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun generationGrantRequiresTheExactRequestObjectAndIsSingleUse(): Unit = ModelLiveHostFixture().use { f ->
        val grant = ModelCompilerGenerationPermit(f.request, {}, {})
        expectModelLiveFailure("compiler_model_live_generation_binding_mismatch") { grant.consume(f.requestFor()) }
        grant.consume(f.request) // A wrong request did not steal or revoke the original grant.
        expectModelLiveFailure("compiler_model_live_generation_permit_used") { grant.consume(f.request) }
        grant.checkLive()
        assertEquals(0, f.session.submissions)
    }

    @Test fun repeatedOrForeignOpenCannotRevokeTheAlreadyAdmittedGeneration(): Unit = ModelLiveHostFixture().use { f ->
        f.generationPermit.consume(f.request)
        f.authority.generation = f.generationPermit
        val active = f.grant(f.archive())
        active.consume()
        repeat(2) {
            val replay = assertIs<ModelCompilerApplicationOpenResult.Unavailable>(KotlinJvmModelCompilerApplication.open(f.request, f.authority))
            assertEquals(listOf("compiler_model_live_generation_permit_used"), replay.reasons)
            assertNull(replay.cleanup)
            val foreign = assertIs<ModelCompilerApplicationOpenResult.Unavailable>(KotlinJvmModelCompilerApplication.open(f.requestFor(), f.authority))
            assertEquals(listOf("compiler_model_live_generation_binding_mismatch"), foreign.reasons)
            assertNull(foreign.cleanup)
        }
        active.checkpoint()
        f.generationPermit.checkLive()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
    }

    @Test fun admittedPreparationFailureRevokesItsOwnGrantWithoutPublishingPrivateExceptionText(): Unit = ModelLiveHostFixture().use { f ->
        val grant = ModelCompilerGenerationPermit(f.request, { error("synthetic private path and owner detail") }, {})
        var cancellations = 0
        grant.bindCancellation {
            expectModelLiveFailure("compiler_model_live_generation_revoked") { grant.checkLive() }
            cancellations++
        }
        f.authority.generation = grant
        val result = assertIs<ModelCompilerApplicationOpenResult.Unavailable>(KotlinJvmModelCompilerApplication.open(f.request, f.authority))
        assertEquals(listOf("compiler_model_live_generation_verification_failed"), result.reasons)
        assertNull(result.cleanup)
        assertEquals(1, cancellations)
        f.generationPermit.checkLive()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
    }

    @Test fun selfDeclaredPinnedCompilerDigestCannotReplaceActualInstalledBytes(): Unit = ModelLiveHostFixture().use { f ->
        expectModelLiveFailure("compiler_model_live_installation_mismatch") { ModelGenerationCapture.capture(f.request) {} }
        assertEquals(0, f.session.submissions)
    }

    @Test fun wrongNamespaceRejectsBeforeArchiveCallbacksAndWrongApplicationCannotConsumeATicket(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val foreignNamespace = CompilerApplicationNamespace("foreign-owner", f.namespace.workspaceId, f.namespace.worktreeId)
        val foreignRequest = f.requestFor(foreignNamespace)
        val foreignSession = NeverSubmitModelSession(foreignRequest.launch)
        val foreign = KotlinJvmModelCompilerApplication.bindingControls(foreignRequest,
            ModelCompilerGenerationPermit(foreignRequest, {}, {}), foreignSession)
        expectModelLiveFailure("compiler_model_live_application_binding_mismatch") { f.grant(archive, application = foreign) }
        assertEquals(0, archive.verifications)
        val sameNamespaceRequest = f.requestFor()
        val other = KotlinJvmModelCompilerApplication.bindingControls(sameNamespaceRequest,
            ModelCompilerGenerationPermit(sameNamespaceRequest, {}, {}), NeverSubmitModelSession(sameNamespaceRequest.launch))
        val original = f.grant(archive)
        expectModelLiveFailure("compiler_model_live_attempt_binding_mismatch") {
            other.compile(original, CancellationToken.NONE, System.nanoTime())
        }
        original.checkpoint()
        assertTrue(original.accepts(archive.child))
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
    }

    @Test fun exactProviderInstanceAndFullChildInputAreRequiredBeforeAnyOwnerCallback(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val grant = f.grant(archive)
        f.authority.attempt = grant
        val before = archive.verifications
        val equivalentProvider = KotlinJvmModelLiveCompilerProvider(f.installation, f.authority)
        val rejected = equivalentProvider.analyze(archive.child)
        assertEquals("compiler_model_live_attempt_binding_mismatch", rejected.reasons.first())
        val changed = archive.child.copy(changedPaths = emptyList())
        assertEquals("compiler_model_live_attempt_binding_mismatch", f.provider.analyze(changed).reasons.first())
        assertEquals(before, archive.verifications)
        grant.checkpoint()
        assertEquals(0, f.session.submissions)
        assertEquals(0, f.session.cancellations)
    }

    @Test fun replayConcurrentRejectionAndLateUnusedRevocationCannotCancelAnotherActiveAttempt(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val active = f.grant(archive)
        val unused = f.grant(archive)
        val rejected = f.grant(archive)
        active.consume()
        expectModelLiveFailure("compiler_live_attempt_permit_used") { f.application.compile(active, CancellationToken.NONE, System.nanoTime()) }
        expectModelLiveFailure("compiler_live_concurrent_attempt") { f.application.compile(rejected, CancellationToken.NONE, System.nanoTime()) }
        assertFalse(rejected.accepts(archive.child))
        unused.revoke(); rejected.revoke(); unused.close()
        active.checkpoint()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun preAdmissionCancellationAuthorityFailureAndMismatchDoNotTouchAnotherActiveTicket(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val active = f.grant(archive)
        active.consume()
        assertEquals("compiler_model_live_cancelled", f.provider.analyze(archive.child, CancellationToken { true }).reasons.first())
        assertEquals("compiler_model_live_cancellation_check_failed", f.provider.analyze(archive.child,
            CancellationToken { error("synthetic-private-detail") }).reasons.first())
        f.authority.failure = IllegalStateException("synthetic-private-authority-detail")
        assertEquals("compiler_model_live_attempt_authority_failed", f.provider.analyze(archive.child).reasons.first())
        f.authority.failure = null
        f.authority.attempt = f.grant(archive, f.reprepare(selected = KotlinJvmModelLiveCompilerProvider(f.installation)))
        assertEquals("compiler_model_live_attempt_binding_mismatch", f.provider.analyze(archive.child).reasons.first())
        active.checkpoint()
        assertEquals(0, f.session.cancellations)
        assertEquals(0, f.session.submissions)
    }

    @Test fun originalAttemptDeadlineExpiresBeforeAnalyzeAndIsNeverRecreated(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val deadline = OwnedApplicationDeadline.afterMillis(500)
        val prepared = f.reprepare(deadline)
        val grant = f.grant(archive, prepared)
        f.authority.attempt = grant
        assertSame(deadline, prepared.originalDeadline)
        assertSame(deadline, grant.originalDeadline)
        val before = archive.verifications
        Thread.sleep(550)
        val result = f.provider.analyze(archive.child)
        assertTrue(deadline.isExpired())
        assertEquals("compiler_model_live_request_deadline", result.reasons.first())
        assertEquals(before, archive.verifications)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun ownerCallbackCannotHideAnInterruptOrSpentOriginalDeadline(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        try {
            expectModelLiveFailure("compiler_live_interrupted") {
                f.grant(archive, verify = { archive.verify(); Thread.currentThread().interrupt() })
            }
        } finally { Thread.interrupted() }
        val deadline = OwnedApplicationDeadline.afterMillis(500)
        val prepared = f.reprepare(deadline)
        expectModelLiveFailure("compiler_model_live_request_deadline") {
            f.grant(archive, prepared, verify = { Thread.sleep(550) })
        }
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun publicGenerationRevokeClosesModelHostGateBeforeCancellationOutsideItsLock(): Unit = ModelLiveHostFixture().use { f ->
        val active = f.grant(f.archive())
        active.consume()
        val pool = Executors.newSingleThreadExecutor()
        try {
            f.session.onCancel = {
                expectModelLiveFailure("compiler_model_live_generation_revoked") { f.generationPermit.checkLive() }
                pool.submit<Unit> {
                    expectModelLiveFailure("compiler_live_attempt_revoked") { active.checkpoint() }
                    expectModelLiveFailure("compiler_live_application_closed") { f.application.checkLive() }
                }.get(5, TimeUnit.SECONDS)
            }
            f.generationPermit.revoke()
            assertEquals(1, f.session.cancellations)
            active.revoke(); f.application.cancel()
            assertEquals(1, f.session.cancellations)
            assertEquals(0, f.session.submissions)
        } finally { pool.shutdownNow() }
    }

    @Test fun bindingOnlyFactoryHasAHardExecutionStopEvenWithAnExactOwnerGrant(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        f.authority.attempt = f.grant(archive)
        val result = f.provider.analyze(archive.child)
        assertEquals("compiler_model_live_binding_control_no_execution", result.reasons.first())
        assertEquals("unavailable", result.status)
        assertTrue(result.findings.isEmpty())
        assertFalse(result.completeFor(archive.child))
        assertEquals(0, archive.registrations)
        assertEquals(0, f.session.submissions)
        assertTrue(liveNames(f.generationRoot.resolve("jobs"), 0).isEmpty())
    }

    @Test fun invalidOrOversizedInputsNeverReachAuthorityAndNeverLoseObligationsSilently(): Unit = ModelLiveHostFixture().use { f ->
        val archive = f.archive()
        val invalid = listOf(archive.child.copy(context = archive.child.context.copy(fullProject = false)),
            archive.child.copy(execution = f.cold.prepared.compilerProfile), archive.child.copy(outputRoot = null))
        invalid.forEach { input ->
            val result = f.provider.analyze(input)
            assertFalse(result.completeFor(input))
            assertEquals(input.requiredRuleIds, result.coverage.map { it.ruleId })
            assertTrue(result.findings.isEmpty())
        }
        val huge = archive.child.copy(requiredRuleIds = List(ModelLiveLimits.COMPILER_RULES + 1) { "synthetic.required.$it" })
        val result = f.provider.analyze(huge)
        assertEquals("compiler_model_live_input_limit", result.reasons.first())
        assertTrue("compiler_model_live_required_rows_unavailable" in result.reasons)
        assertTrue(result.coverage.isEmpty())
        assertFalse(result.completeFor(huge))
        assertEquals(0, f.authority.lookups)
        assertEquals(0, f.session.submissions)
    }
}
