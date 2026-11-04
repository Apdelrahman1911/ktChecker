package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import kotlin.test.*

/** Pure synthetic data comparisons only: no native helper, exporter or START capture runs. */
class OwnerFixtureStartupTest {
    private val identity = OwnedSupervisorIdentity("ktchecker-owned-child-supervisor/preview-1", "linux-x86_64",
        Digests.sha256("synthetic declared C source, not a build proof"), Digests.sha256("synthetic binary, never executed"))

    private fun syntheticConfirmed() = ProcessOutcome("executed", exitCode = 0,
        supervisorIdentity = identity, executionConfirmed = true)

    @Test fun explicitlySyntheticConfirmedCompletionAndBothIdentityComponentsCompare(): Unit {
        val synthetic = syntheticConfirmed()
        verifyOwnerFixtureSupervisorCompletion(identity, synthetic)
        assertTrue(synthetic.executed)
        assertTrue(synthetic.successfulExecution)
        val inputs = Digests.sha256("synthetic owner runtime inputs")
        val startup = Digests.sha256("synthetic startup image inputs")
        val home = Digests.sha256("synthetic initial writable-home derivation")
        val combined = ownerFixtureRuntimeIdentity(inputs, startup, home)
        assertEquals(Digests.sha256("ktchecker/owner-fixture-runtime/3\u0000" + inputs + "\u0000" + startup + "\u0000" + home), combined)
        assertNotEquals(combined, ownerFixtureRuntimeIdentity(Digests.sha256("changed owner runtime inputs"), startup, home))
        assertNotEquals(combined, ownerFixtureRuntimeIdentity(inputs, Digests.sha256("changed startup image inputs"), home))
        assertNotEquals(combined, ownerFixtureRuntimeIdentity(inputs, startup, Digests.sha256("changed initial home derivation")))
        assertNotEquals(combined, ownerFixtureRuntimeIdentity(startup, inputs, home))
        assertEquals("owner_fixture_runtime_identity_invalid", assertFailsWith<OwnerFixtureProblem> {
            ownerFixtureRuntimeIdentity("not an identity", startup, home)
        }.code)
    }

    @Test fun comparisonRejectsMissingAndEveryMismatchedActualSupervisorIdentity(): Unit {
        val changed = listOf(null, identity.copy(protocolVersion = "other-protocol"),
            identity.copy(target = "linux-aarch64"), identity.copy(sourceDigest = Digests.sha256("different source")),
            identity.copy(binaryDigest = Digests.sha256("different binary")))
        changed.forEach { actual ->
            assertEquals("owner_fixture_supervisor_actual_identity_mismatch", assertFailsWith<OwnerFixtureProblem> {
                verifyOwnerFixtureSupervisorCompletion(identity, syntheticConfirmed().copy(supervisorIdentity = actual))
            }.code)
        }
    }

    @Test fun zeroForkExitAndSuccessTextCannotReplaceExecutionConfirmation(): Unit {
        val zeroForkExitOnly = ProcessOutcome("executed", exitCode = 0, supervisorIdentity = identity)
        assertFalse(zeroForkExitOnly.executed)
        assertFalse(zeroForkExitOnly.successfulExecution)
        listOf(zeroForkExitOnly, zeroForkExitOnly.copy(status = "failed"),
            zeroForkExitOnly.copy(cancelled = true), zeroForkExitOnly.copy(supervisorIdentity = null)).forEach { actual ->
            assertEquals("owner_fixture_supervisor_execution_not_confirmed", assertFailsWith<OwnerFixtureProblem> {
                verifyOwnerFixtureSupervisorCompletion(identity, actual)
            }.code)
        }
    }

    @Test fun confirmedExecutionStillRejectsPostVerificationAndTerminalFailures(): Unit {
        val synthetic = syntheticConfirmed()
        // Mirrors the retained first-export outcome shape, not a new actual process receipt.
        val firstAttemptShape = synthetic.copy(status = "failed", reasons = listOf("snapshot_changed_during_execution"))
        listOf(firstAttemptShape, synthetic.copy(status = "failed"), synthetic.copy(status = "unavailable"),
            synthetic.copy(exitCode = null), synthetic.copy(exitCode = 1), synthetic.copy(cancelled = true),
            synthetic.copy(timedOut = true), synthetic.copy(truncated = true),
            synthetic.copy(reasons = listOf("synthetic_cleanup_failure"))).forEach { actual ->
            assertEquals("owner_fixture_process_verification_failed", assertFailsWith<OwnerFixtureProblem> {
                verifyOwnerFixtureSupervisorCompletion(identity, actual)
            }.code)
        }
        assertTrue(firstAttemptShape.executionConfirmed)
        assertEquals(0, firstAttemptShape.exitCode)
        assertEquals(listOf("snapshot_changed_during_execution"), firstAttemptShape.reasons)
    }

    @Test fun confirmedFailureCannotHideMissingOrMismatchedActualIdentity(): Unit {
        val failed = syntheticConfirmed().copy(status = "failed", reasons = listOf("synthetic_owner_verification_failure"))
        listOf(null, identity.copy(binaryDigest = Digests.sha256("different binary"))).forEach { actual ->
            assertEquals("owner_fixture_supervisor_actual_identity_mismatch", assertFailsWith<OwnerFixtureProblem> {
                verifyOwnerFixtureSupervisorCompletion(identity, failed.copy(supervisorIdentity = actual))
            }.code)
        }
    }

    @Test fun ownerCallbackPreservesExactProblemAndDoesNotReclassifyOtherExceptions(): Unit {
        var invoked = false
        ownerFixtureExecutionVerification { invoked = true }
        assertTrue(invoked)
        val ownerFailure = OwnerFixtureProblem("owner_fixture_seed_inventory_changed")
        val preserved = assertFailsWith<ExecutionProblem> { ownerFixtureExecutionVerification { throw ownerFailure } }
        assertEquals(ownerFailure.code, preserved.code)
        assertSame(ownerFailure, preserved.cause)
        val runtimeFailure = ExecutionProblem("synthetic_runtime_failure", "synthetic failure")
        assertSame(runtimeFailure, assertFailsWith<ExecutionProblem> { ownerFixtureExecutionVerification { throw runtimeFailure } })
        val unrelated = IllegalStateException("synthetic unrelated failure")
        assertSame(unrelated, assertFailsWith<IllegalStateException> { ownerFixtureExecutionVerification { throw unrelated } })
    }

    @Test fun startupEvidenceAllocationIsGuardedBeforeSerialization(): Unit {
        val file = ModelFile("synthetic-image", Digests.sha256("synthetic image bytes"), 1)
        val evidence = OwnerFixtureStartupEvidence(1, "synthetic START time",
            OwnerFixtureSupervisorIdentity(identity.protocolVersion, identity.target, identity.sourceDigest, identity.binaryDigest),
            file, file, file, "synthetic code source, never inspected", listOf("synthetic class role"),
            listOf(OwnerFixtureStartupInput("synthetic role", "synthetic original", "synthetic preserved", file.digest, 1)),
            listOf(OwnerFixtureStartupTree("synthetic root", "synthetic preserved root", listOf("file:synthetic-image"))))
        verifyOwnerFixtureStartupEvidenceBound(evidence)
        val oversized = listOf(evidence.copy(runtimeCodeSource = "x".repeat(700_000)),
            evidence.copy(inputs = List(8_001) { evidence.inputs.single() }),
            evidence.copy(trees = listOf(evidence.trees.single().copy(entries = List(8_001) { "file:synthetic-image" }))))
        oversized.forEach { data ->
            assertEquals("owner_fixture_startup_bound", assertFailsWith<OwnerFixtureProblem> {
                verifyOwnerFixtureStartupEvidenceBound(data)
            }.code)
        }
    }
}
