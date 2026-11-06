package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlin.test.*

/** Optional original-export DENIAL attachment; no fake worker, compiler or model capability. */
class OwnerModelTransportScopeTest {
    @Test fun firstExportFenceRemainsExactAcrossFirstReviewAttachmentWithoutRenewingNative120() {
        val overall = CommandTestPhase(900_000); val export = CommandTestPhase(120_000)
        val deadline = OwnedApplicationDeadline.afterMillis(120_000)
        val clock = OwnerModelCommandClock(deadline, originalOverall = overall, originalExport = export)
        val started = deadline.startedAtNanos
        clock.checkpoint()
        val scope = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = export)
        clock.attachOriginalPhases(scope)
        assertSame(deadline, clock.originalDeadline); assertSame(deadline, clock.invocation.originalDeadline)
        assertEquals(started, clock.originalDeadline.startedAtNanos); assertEquals(120_000L, deadline.durationMillis)
        assertEquals(2, export.checkpoints)
        export.fail = true; assertTrue(clock.cancelled())
        export.fail = false; assertTrue(clock.cancelled())
    }

    @Test fun originallyAttachedExportCannotBeDroppedOrReplacedByEqualDurationData() {
        for (replacement in listOf<OwnerModelPhaseFence?>(null, CommandTestPhase(120_000))) {
            val overall = CommandTestPhase(900_000); val export = CommandTestPhase(120_000)
            val clock = OwnerModelCommandClock(OwnedApplicationDeadline.afterMillis(120_000), originalOverall = overall, originalExport = export)
            assertEquals("owner_model_original_export_fence_replaced", assertFailsWith<CliProblem> {
                clock.attachOriginalPhases(OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = replacement))
            }.code)
            assertTrue(clock.cancelled())
        }
    }

    @Test fun nextGenuinePatchRequiresADistinctExportFenceAndDoesNotTouchOldOne() {
        val gate = OwnerModelLineageGate(); val overall = CommandTestPhase(900_000); val oldExport = CommandTestPhase(120_000)
        val first = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = oldExport)
        gate.leave(gate.enter("one", 1, commandTestClock(first), first), false)
        oldExport.fail = true
        val reused = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = oldExport)
        assertEquals("owner_model_original_export_fence_replayed", assertFailsWith<CliProblem> {
            gate.enter("two", 2, commandTestClock(reused), reused)
        }.code)
        val nextExport = CommandTestPhase(120_000)
        val next = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = nextExport)
        val clock = commandTestClock(next)
        gate.leave(gate.enter("two", 2, clock, next), false); clock.checkpoint()
        assertEquals(0, oldExport.checkpoints); assertEquals(1, nextExport.checkpoints)
        assertEquals(2, gate.enteredCount)
    }

    @Test fun exportFenceModeMayNotSwitchOnOrOffAcrossAnExistingLineage() {
        for (hasExport in listOf(false, true)) {
            val gate = OwnerModelLineageGate(); val overall = CommandTestPhase(900_000)
            val first = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = if (hasExport) CommandTestPhase(120_000) else null)
            gate.leave(gate.enter("one", 1, commandTestClock(first), first), false)
            val next = OwnerModelClockScope(overall, CommandTestPhase(300_000), originalExport = if (hasExport) null else CommandTestPhase(120_000))
            assertEquals("owner_model_original_export_fence_mode_changed", assertFailsWith<CliProblem> {
                gate.enter("two", 2, commandTestClock(next), next)
            }.code)
            assertEquals(1, gate.enteredCount)
        }
    }

    @Test fun laterClockUsesCachedExportShapeWithoutExternalGetterBeforeWholePatchDetachment() {
        var reads = 0
        val export = object : OwnerModelPhaseFence {
            override val originalMillis: Long get() { reads++; check(reads == 1); return 120_000 }
            override fun checkpoint() { }
        }
        val scope = OwnerModelClockScope(CommandTestPhase(900_000), CommandTestPhase(300_000), originalExport = export)
        assertTrue(scope.shapeValid); assertEquals(1, reads)
        val clock = commandTestClock(scope)
        val frozen = OwnerModelFrozenPatch.capture(commandTestPatch(), commandTestBinding, clock::checkpoint)
        assertEquals(commandTestPatch(), frozen.request); assertEquals(1, reads)
    }

    @Test fun wrongExportWidthAndRoleAliasCannotBecomeAValidOriginalScope() {
        val overall = CommandTestPhase(900_000); val review = CommandTestPhase(300_000)
        for (export in listOf<OwnerModelPhaseFence>(overall, review, CommandTestPhase(119_999), CommandTestPhase(120_001))) {
            val scope = OwnerModelClockScope(overall, review, originalExport = export)
            assertFalse(scope.shapeValid)
            assertFailsWith<CliProblem> { commandTestClock(scope) }
        }
    }
}
