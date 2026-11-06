package dev.ktchecker.cli

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.projectmodel.CONFIGURATION_INPUT_CLOSURE_UNPROVEN
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.snapshot.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real source-ledger tests and DATA/state tests only; no fake model, process or admitted parent. */
class OwnerModelLineageTest {
    @TempDir lateinit var directory: Path

    @Test fun patchFixUsesActualDirectParentWholeInventoryDeletesModesAndPreservedPriorSnapshot() {
        val f = CommandSnapshotFixture(directory)
        val one = commandTestPatch(files = listOf(commandTestFile("Keep.kt", "before".toByteArray()),
            commandTestFile("Removed.kt"), commandTestFile("script.sh", "not executed".toByteArray(), executable = true)))
        val (first, before) = f.capture(one)
        val two = commandTestPatch(before, listOf(commandTestFile("Added.kt"),
            commandTestFile("Keep.kt", "after".toByteArray()), commandTestFile("script.sh", "not executed".toByteArray(), executable = false)))
        val frozen = f.freeze(two)
        val overlay = frozen.overlay(before.manifest)
        assertEquals(CommitMode.STRICT_HEAD, overlay.mode)
        assertEquals(listOf("Removed.kt"), overlay.changes.filterIsInstance<SnapshotChange.Delete>().map { it.path.path })
        assertEquals(two.files.map { it.path }, overlay.changes.filterIsInstance<SnapshotChange.PutFile>().map { it.path.path })
        val second = OwnerModelSnapshotLineage(frozen, before)
        val after = second.capture(f.store) { }
        assertSame(before, first.commit); assertSame(after, second.commit)
        assertTrue(first.mutationVerified && second.mutationVerified)
        assertFalse(first.historyUncertain || second.historyUncertain)
        assertEquals(before.snapshotId, after.manifest.parentSnapshotId)
        assertEquals(before.snapshotId, after.manifest.producerBaseSnapshotId)
        assertEquals(2L, after.headRevision); assertEquals(2L, after.sourceEpoch)
        assertEquals(CaptureProof(CaptureKind.IMMUTABLE_OVERLAY, 2L), after.manifest.capture)
        assertEquals(listOf("Added.kt" to "added", "Keep.kt" to "modified", "Removed.kt" to "deleted", "script.sh" to "modified"),
            after.manifest.delta.map { it.path to it.operation })
        assertEquals(frozen.entries, after.manifest.entries)
        assertEquals(before.manifest, f.store.snapshot(f.identity, before.snapshotId))
        assertEquals(after.manifest, f.store.snapshot(f.identity, after.snapshotId))
        val oldFiles = f.store.materialize(f.identity, before.snapshotId, directory.resolve("original"))
        val newFiles = f.store.materialize(f.identity, after.snapshotId, directory.resolve("fixed"))
        assertEquals("before", Files.readString(oldFiles.root.resolve("Keep.kt")))
        assertEquals("after", Files.readString(newFiles.root.resolve("Keep.kt")))
        assertTrue(Files.exists(oldFiles.root.resolve("Removed.kt")))
        assertFalse(Files.exists(newFiles.root.resolve("Removed.kt")))
        assertTrue(Files.isExecutable(oldFiles.root.resolve("script.sh")))
        assertFalse(Files.isExecutable(newFiles.root.resolve("script.sh")))
        assertTrue(f.store.verifyMaterialization(oldFiles).valid && f.store.verifyMaterialization(newFiles).valid)
        assertEquals(2, f.store.currentHead(f.identity).acceptedPatchCount)
    }

    @Test fun equalBytesAreStillANewImmutableCommitNotPriorModelOrClockReuse() {
        val f = CommandSnapshotFixture(directory)
        val (_, first) = f.capture(commandTestPatch())
        val (_, second) = f.capture(commandTestPatch(first), first)
        assertEquals(first.manifest.treeDigest, second.manifest.treeDigest)
        assertNotEquals(first.snapshotId, second.snapshotId)
        assertEquals(first.snapshotId, second.manifest.parentSnapshotId)
        assertTrue(second.manifest.delta.isEmpty())
        assertFalse(second.replayed)
    }

    @Test fun returningASnapshotCannotBypassParentRootEpochHashModeOrFullInventoryChecks() {
        val f = CommandSnapshotFixture(directory)
        val request = commandTestPatch(files = listOf(commandTestFile("A.kt"), commandTestFile("B.kt", executable = true)))
        val frozen = f.freeze(request)
        val (_, actual) = f.capture(request)
        val m = actual.manifest
        val forged = listOf(actual.copy(replayed = true), actual.copy(headRevision = 2), actual.copy(sourceEpoch = 2),
            actual.copy(manifest = m.copy(parentSnapshotId = Digests.sha256("foreign"))),
            actual.copy(manifest = m.copy(producerBaseSnapshotId = Digests.sha256("foreign"))),
            actual.copy(manifest = m.copy(roots = listOf(SnapshotRoot("extra", "extra")))),
            actual.copy(manifest = m.copy(entries = m.entries.take(1))),
            actual.copy(manifest = m.copy(entries = m.entries.map { it.copy(executable = false) })),
            actual.copy(manifest = m.copy(capture = m.capture.copy(sourceEpoch = 2))),
            actual.copy(manifest = m.copy(delta = emptyList())),
            actual.copy(manifest = m.copy(treeDigest = Digests.sha256("forged tree"))),
            actual.copy(manifest = m.copy(snapshotId = Digests.sha256("forged manifest"))),
            actual.copy(manifest = m.copy(scope = m.scope.copy(unknownInputs = listOf("invented omission")))))
        forged.forEach { data -> assertFailsWith<CliProblem> { OwnerModelSnapshotLineage.verifyCommit(frozen, null, data) } }
        OwnerModelSnapshotLineage.verifyCommit(frozen, null, actual)
        assertEquals(actual.manifest, f.store.snapshot(f.identity, actual.snapshotId))
    }

    @Test fun foreignInterveningHeadCannotBecomeFreshLineageOrCallerSelectedBaseline() {
        val f = CommandSnapshotFixture(directory)
        val request = commandTestPatch()
        val foreign = f.store.sealOverlay(f.freeze(request).overlay(null).copy(clientId = "foreign-client", patchId = "foreign-patch"))
        val holder = OwnerModelSnapshotLineage(f.freeze(request), null)
        assertEquals("owner_model_actual_head_unknown_or_changed", assertFailsWith<CliProblem> { holder.capture(f.store) { } }.code)
        assertTrue(holder.historyUncertain); assertFalse(holder.mutationAttempted)
        assertNull(holder.commit); assertFalse(holder.mutationVerified)
        assertEquals(foreign.snapshotId, f.store.currentHead(f.identity).headSnapshotId)
        assertFailsWith<CliProblem> { holder.capture(f.store) { } }
        assertEquals(1, f.store.currentHead(f.identity).acceptedPatchCount)
    }

    @Test fun oneHolderNeverReplaysASuccessOrPicksUpASecondReturnedCommit() {
        val f = CommandSnapshotFixture(directory)
        val (holder, actual) = f.capture(commandTestPatch())
        var callbacks = 0
        assertEquals("owner_model_snapshot_submission_replayed", assertFailsWith<CliProblem> {
            holder.capture(f.store) { callbacks++ }
        }.code)
        assertEquals(0, callbacks); assertSame(actual, holder.commit)
        assertEquals(1L, f.store.currentHead(f.identity).headRevision)
    }

    @Test fun afterStateInstallFailureIsUnknownEvenWhenTheStoreActuallyCommittedAndNeverRetries() {
        var faults = 0
        val f = CommandSnapshotFixture(directory, SnapshotFaultInjector { point ->
            if (point == "after_state_install") { faults++; throw IllegalStateException("SYNTHETIC disk boundary fault") }
        })
        val holder = OwnerModelSnapshotLineage(f.freeze(commandTestPatch()), null)
        assertFailsWith<IllegalStateException> { holder.capture(f.store) { } }
        assertTrue(holder.mutationAttempted && holder.historyUncertain)
        assertFalse(holder.mutationVerified); assertNull(holder.commit)
        // Documentary store read for this unit test ONLY. It must NOT promote the holder to proof.
        val documentary = f.store.currentHead(f.identity)
        assertEquals(1L, documentary.headRevision); assertNotNull(documentary.headSnapshotId)
        assertFailsWith<CliProblem> { holder.capture(f.store) { } }
        assertEquals(1, faults); assertNull(holder.commit); assertTrue(holder.historyUncertain)
        assertEquals(documentary, f.store.currentHead(f.identity))
    }

    @Test fun beforeStateInstallFailureAlsoStaysUnknownWithoutAttemptingRecovery() {
        val f = CommandSnapshotFixture(directory, SnapshotFaultInjector { point ->
            if (point == "before_state_install") throw IllegalStateException("SYNTHETIC preinstall fault")
        })
        val holder = OwnerModelSnapshotLineage(f.freeze(commandTestPatch()), null)
        assertFailsWith<IllegalStateException> { holder.capture(f.store) { } }
        assertEquals(0L, f.store.currentHead(f.identity).headRevision)
        assertTrue(holder.historyUncertain); assertFalse(holder.mutationVerified)
        assertFailsWith<CliProblem> { holder.capture(f.store) { } }
    }

    @Test fun originalOverallStartsBeforeReviewAndAttachingFirst300NeverResetsOriginal120() {
        val overall = CommandTestPhase(900_000)
        val deadline = OwnedApplicationDeadline.afterMillis(120_000)
        val clock = OwnerModelCommandClock(deadline, originalOverall = overall)
        val started = deadline.startedAtNanos
        clock.checkpoint()
        assertEquals(1, overall.checkpoints)
        val review = CommandTestPhase(300_000)
        val scope = OwnerModelClockScope(overall, review)
        clock.attachOriginalPhases(scope)
        assertSame(deadline, clock.originalDeadline); assertSame(deadline, clock.invocation.originalDeadline)
        assertEquals(started, clock.originalDeadline.startedAtNanos)
        assertEquals(120_000L, clock.originalDeadline.durationMillis)
        assertEquals(2, overall.checkpoints); assertEquals(1, review.checkpoints)
        assertFailsWith<CliProblem> { clock.attachOriginalPhases(OwnerModelClockScope(overall, CommandTestPhase(300_000))) }
        assertTrue(clock.cancelled())
    }

    @Test fun equalDurationOverallCannotReplaceTheOriginalOrRecoverFromStickyPhaseFailure() {
        val overall = CommandTestPhase(900_000)
        val clock = OwnerModelCommandClock(OwnedApplicationDeadline.afterMillis(120_000), originalOverall = overall)
        assertEquals("owner_model_original_overall_replaced", assertFailsWith<CliProblem> {
            clock.attachOriginalPhases(OwnerModelClockScope(CommandTestPhase(900_000), CommandTestPhase(300_000)))
        }.code)
        assertFailsWith<CliProblem> { clock.attachOriginalPhases(OwnerModelClockScope(overall, CommandTestPhase(300_000))) }
        val original = commandTestClock(OwnerModelClockScope(overall, CommandTestPhase(300_000)))
        overall.fail = true
        assertFailsWith<IllegalStateException> { original.checkpoint() }
        overall.fail = false
        assertTrue(original.cancelled()); assertFailsWith<CliProblem> { original.checkpoint() }
        assertFailsWith<CliProblem> { OwnerModelCommandClock(OwnedApplicationDeadline.afterMillis(120_001)) }
    }

    @Test fun laterClockConstructionUsesCachedScopeWithoutReenteringExternalGettersBeforePatchDetachment() {
        var overallReads = 0
        val overall = object : OwnerModelPhaseFence {
            override val originalMillis: Long get() {
                overallReads++
                check(overallReads == 1) { "SYNTHETIC external getter must not be reentered" }
                return 900_000L
            }
            override fun checkpoint() { }
        }
        val scope = OwnerModelClockScope(overall, CommandTestPhase(300_000))
        assertEquals(1, overallReads); assertTrue(scope.shapeValid)
        val nextClock = commandTestClock(scope) // Actual later-submit constructor seam; no owner/worker.
        assertEquals(1, overallReads)
        val patch = OwnerModelFrozenPatch.capture(commandTestPatch(), commandTestBinding, nextClock::checkpoint)
        assertEquals(1, overallReads); assertEquals(commandTestPatch(), patch.request)
    }

    @Test fun freshPatchClockDoesNotTouchDeniedPreviousReviewScopeOrReuseInvocation() {
        val overall = CommandTestPhase(900_000)
        val beforeReview = CommandTestPhase(300_000)
        val before = commandTestClock(OwnerModelClockScope(overall, beforeReview))
        before.checkpoint(); before.deny(); beforeReview.fail = true
        val afterReview = CommandTestPhase(300_000)
        val after = commandTestClock(OwnerModelClockScope(overall, afterReview))
        after.checkpoint()
        assertFalse(after.cancelled()); assertTrue(before.cancelled())
        assertEquals(1, beforeReview.checkpoints)
        assertNotSame(before.originalDeadline, after.originalDeadline)
        assertNotSame(before.invocation, after.invocation)
        assertNotEquals(before.invocation.identity, after.invocation.identity)
    }

    @Test fun gateReservesWholePatchAndRetainsClockOrdinalIdentityAndFailureTombstones() {
        val gate = OwnerModelLineageGate()
        val overall = CommandTestPhase(900_000)
        val review = CommandTestPhase(300_000)
        val firstScope = OwnerModelClockScope(overall, review)
        val firstClock = commandTestClock(firstScope)
        val token = gate.enter("one", 1, firstClock, firstScope)
        assertFalse(gate.idle)
        assertFailsWith<CliProblem> { gate.enter("two", 2, commandTestClock(), null) }
        assertEquals(0, overall.checkpoints); assertEquals(0, review.checkpoints) // No callbacks/IO under gate.
        gate.leave(token, false)
        assertFailsWith<CliProblem> { gate.enter("one", 2, commandTestClock(firstScope), firstScope) }
        assertFailsWith<CliProblem> { gate.enter("two", 3, commandTestClock(firstScope), firstScope) }
        val secondScope = OwnerModelClockScope(overall, CommandTestPhase(300_000))
        assertFailsWith<CliProblem> { gate.enter("two", 2, OwnerModelCommandClock(firstClock.originalDeadline, secondScope), secondScope) }
        assertFailsWith<CliProblem> { gate.enter("two", 2, commandTestClock(firstScope), firstScope) }
        val alienScope = OwnerModelClockScope(CommandTestPhase(900_000), CommandTestPhase(300_000))
        assertFailsWith<CliProblem> { gate.enter("two", 2, commandTestClock(alienScope), alienScope) }
        assertFailsWith<CliProblem> { gate.enter("two", 2, commandTestClock(), null) }
        val second = gate.enter("two", 2, commandTestClock(secondScope), secondScope)
        gate.leave(second, true)
        assertEquals(2, gate.enteredCount); assertTrue(gate.idle)
        assertFailsWith<CliProblem> { gate.enter("three", 3, commandTestClock(), null) }
    }

    @Test fun explicitPolicyAndMaintenanceReserveSameGateWithoutAddingOrRearmingPatches() {
        val gate = OwnerModelLineageGate()
        val policy = gate.enterPolicy()
        assertFailsWith<CliProblem> { gate.enter("one", 1, commandTestClock(), null) }
        assertFailsWith<CliProblem> { gate.enterMaintenance() }
        gate.leave(policy, false)
        assertEquals(0, gate.enteredCount)
        assertFailsWith<CliProblem> { gate.enterPolicy() }
        val patch = gate.enter("one", 1, commandTestClock(), null)
        assertFailsWith<CliProblem> { gate.enterPolicy() }
        assertFailsWith<CliProblem> { gate.enterMaintenance() }
        gate.leave(patch, true)
        val cleanup = gate.enterMaintenance() // An explicit ONE cleanup may still be needed after failure.
        gate.leave(cleanup, false)
        assertEquals(1, gate.enteredCount)
        assertFailsWith<CliProblem> { gate.enter("two", 2, commandTestClock(), null) }
    }

    @Test fun generationBoundRemains32WithNoEvictionOrFreshClockRearm() {
        val gate = OwnerModelLineageGate()
        for (number in 1..32) gate.leave(gate.enter("patch-$number", number.toLong(), commandTestClock(), null), false)
        assertEquals(32, gate.enteredCount)
        assertEquals(32, OWNER_MODEL_MAX_PATCHES)
        assertFailsWith<CliProblem> { gate.enter("patch-33", 33, commandTestClock(), null) }
        assertFailsWith<CliProblem> { gate.enter("patch-1", 33, commandTestClock(), null) }
    }

    @Test fun exactOpaqueHandleIndexPreservesOlderEntriesAndRejectsEqualDecodedData() {
        data class Key(val label: String)
        val old = Key("old-parent"); val next = Key("next-parent")
        val oldValue = Any(); val newValue = Any()
        val handles = OwnerModelExactHandles<Key, Any>()
        handles.retain(old, oldValue); handles.retain(next, newValue)
        assertSame(oldValue, handles.exact(old)); assertSame(newValue, handles.exact(next))
        assertFailsWith<CliProblem> { handles.exact(Key("old-parent")) }
        assertFailsWith<CliProblem> { handles.retain(old, Any()) }
        assertSame(oldValue, handles.exact(old))
    }

    @Test fun failedUnknownNew1665ModelKeepsExactPrior1663ContextsWithoutInventingD() {
        val f = CommandSnapshotFixture(directory)
        val (holder, commit) = f.capture(commandTestPatch())
        val inventory = Catalog().ids.sorted()
        assertTrue(OwnerFullCatalog1665.accepts(inventory))
        val oldInventory = inventory - setOf(OwnerFullCatalog1665.ERROR_SUPPRESSION, OwnerFullCatalog1665.INAPPLICABLE_OPERATOR_MODIFIER)
        assertEquals(1663, oldInventory.size)
        assertEquals("sha256:fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b",
            Digests.sha256(Digests.canonical(JsonArray(oldInventory.map(::JsonPrimitive)))))
        val contexts = listOf(RuleContext(module = ":removed"), RuleContext(target = "iosArm64", module = ":retargeted"),
            RuleContext(sourceSet = "test", module = ":surviving", compilation = "test"))
        // SYNTHETIC documentary prior slot DATA only; never admitted or passed as model authority.
        val prior = contexts.mapIndexed { index, context -> RequiredPlanSlot("prior-$index", AnalysisInput(commit.toContractManifest(),
            "/NON-EXECUTED-documentary", AnalysisContext(scope = context), oldInventory), state = "unsupported", reasons = listOf("documentary-unit")) }
        val oldBytes = Digests.canonical(ContractJson.encodeToJsonElement(prior))
        val before = buildJsonObject { put("snapshotId", commit.snapshotId); put("invocationId", "DOCUMENTARY-ONLY") }
        val projection = ownerModelUnresolvedInventory(holder.commit!!.manifest, f.freeze(commandTestPatch()).entries, before, prior, null)
        assertEquals(ContractJson.encodeToJsonElement(prior), projection["priorWholeContextVector"])
        assertEquals(ContractJson.encodeToJsonElement(commit.manifest), projection["registeredSnapshot"])
        assertEquals(before, projection["documentaryBefore"])
        assertEquals(JsonNull, projection["actualAttemptedWholePlan"])
        assertEquals(inventory, projection.getValue("unresolvedRequiredRuleIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1665, projection.getValue("unresolvedRequiredRuleIds").jsonArray.size)
        assertEquals(oldBytes, Digests.canonical(projection.getValue("priorWholeContextVector")))
        assertTrue(prior.all { it.input.requiredRuleIds == oldInventory })
        assertEquals(JsonPrimitive(OwnerFullCatalog1665.ID), projection["catalogContract"])
        assertEquals(JsonPrimitive(OwnerFullCatalog1665.RULE_IDS_DIGEST), projection["catalogRuleIdsDigest"])
        assertEquals(JsonPrimitive("unknown"), projection["newContextApplicability"])
        assertEquals(JsonPrimitive(CONFIGURATION_INPUT_CLOSURE_UNPROVEN), projection["mandatoryGap"])
        listOf("admissionAuthority", "configurationInputClosureProven", "fullProject", "complete", "qualified").forEach {
            assertEquals(JsonPrimitive(false), projection[it])
        }
    }
}
