package dev.ktchecker.cli

import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import dev.ktchecker.kernel.ParentPlanStatus
import dev.ktchecker.kernel.PlanSlotStatus
import dev.ktchecker.kernel.ReviewStatus
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/**
 * SYNTHETIC detached outcome/status DATA tests of actual-used custody helpers. No process runner,
 * export importer/permit, generation open, compiler, ACK, observation, or quarantine thread runs.
 * A passing tuple comparator is NOT claimed as real execution/cleanup/qualification evidence.
 */
class OwnerModelCommandLifecycleTest {
    @TempDir lateinit var directory: Path
    private val supervisor = OwnedSupervisorIdentity("SYNTHETIC-unit", "no-platform-execution", Digests.sha256("source-data"), Digests.sha256("binary-data"))
    private val command = listOf("/NON-EXECUTED/java", "-cp", "/NON-EXECUTED/bootstrap.jar", "org.gradle.launcher.GradleMain")
    private fun outcome(root: Path) = ProcessOutcome("executed", exitCode = 0, outputDirectory = root,
        command = command, supervisorIdentity = supervisor, executionConfirmed = true)

    @Test fun beforeEntryIsClosableButCallbackOnlyAndUnknownRunnerReturnAreNot() {
        val root = OwnerModelRootPin.capture(directory.toRealPath())
        val data = outcome(root.path)
        val holder = OwnerModelExportReturnCustody()
        assertTrue(holder.safeToClose); assertFalse(holder.childGroupQuiescent)
        holder.enterRunner()
        assertFalse(holder.safeToClose)
        holder.retainCallback(data)
        assertSame(data, holder.completed); assertNull(holder.originalReturned)
        assertFalse(holder.childGroupQuiescent); assertFalse(holder.safeToClose)
        assertFailsWith<CliProblem> { holder.verifyOriginalReturn(root, command, supervisor) }
        assertFailsWith<CliProblem> { holder.enterRunner() }
    }

    @Test fun equalCopiedOutcomeIsNotTheOriginalCallbackReturnAndCannotBePromotedLater() {
        val root = OwnerModelRootPin.capture(directory.toRealPath())
        val actualData = outcome(root.path)
        val copy = actualData.copy()
        assertEquals(actualData, copy); assertNotSame(actualData, copy)
        val holder = OwnerModelExportReturnCustody()
        holder.enterRunner(); holder.retainCallback(actualData)
        assertEquals("owner_model_export_outcome_substituted", assertFailsWith<CliProblem> {
            holder.originalReturn(copy, root, supervisor) { command }
        }.code)
        assertSame(actualData, holder.completed); assertSame(copy, holder.originalReturned)
        assertFalse(holder.childGroupQuiescent); assertFalse(holder.safeToClose)
        assertFailsWith<CliProblem> { holder.originalReturn(actualData, root, supervisor) { command } }
        assertFailsWith<CliProblem> { holder.retainCallback(copy) }
        assertFalse(holder.childGroupQuiescent)
    }

    @Test fun failedUnavailableCancelledTruncatedWrongImagePathAndArgvDataStayUnverified() {
        val root = OwnerModelRootPin.capture(directory.toRealPath())
        val nominalData = outcome(root.path)
        val variants = listOf(nominalData.copy(status = "failed"), nominalData.copy(status = "unavailable"),
            nominalData.copy(exitCode = null), nominalData.copy(exitCode = 1), nominalData.copy(executionConfirmed = false),
            nominalData.copy(reasons = listOf("SYNTHETIC child-group quiescence unknown")), nominalData.copy(cancelled = true),
            nominalData.copy(timedOut = true), nominalData.copy(truncated = true), nominalData.copy(command = command.reversed()),
            nominalData.copy(outputDirectory = root.path.resolve("elsewhere")), nominalData.copy(supervisorIdentity = null),
            nominalData.copy(supervisorIdentity = supervisor.copy(binaryDigest = Digests.sha256("foreign"))))
        variants.forEach { data ->
            val holder = OwnerModelExportReturnCustody()
            holder.enterRunner(); holder.retainCallback(data)
            assertFailsWith<CliProblem> { holder.originalReturn(data, root, supervisor) { command } }
            assertSame(data, holder.originalReturned); assertFalse(holder.safeToClose); assertFalse(holder.childGroupQuiescent)
            assertFailsWith<CliProblem> { holder.originalReturn(nominalData, root, supervisor) { command } }
            assertFailsWith<CliProblem> { holder.verifyOriginalReturn(root, command, supervisor) }
        }
    }

    @Test fun matchingSyntheticTupleTestsComparatorOnlyAndCannotReadAnUnownedReport() {
        val root = OwnerModelRootPin.capture(directory.toRealPath())
        val data = outcome(root.path)
        val holder = OwnerModelExportReturnCustody()
        holder.enterRunner(); holder.retainCallback(data); holder.originalReturn(data, root, supervisor) { command }
        assertTrue(holder.childGroupQuiescent && holder.safeToClose) // DATA comparator only.
        holder.verifyOriginalReturn(root, command, supervisor)
        assertSame(data, holder.originalReturned)
        assertFailsWith<CliProblem> { holder.originalReturn(data, root, supervisor) { command } }
        Files.writeString(root.path.resolve("invented-report.json"), "not a runtime-owned report")
        assertFailsWith<ExecutionProblem> { data.report("invented-report.json") } // No real outputIdentity.
    }

    @Test fun actualReturnIsRetainedBeforeUnavailableExpectedCommandAndCannotRetryItsVerification() {
        val data = outcome(directory.toRealPath()).copy(status = "unavailable", executionConfirmed = false, outputDirectory = null)
        val holder = OwnerModelExportReturnCustody()
        holder.enterRunner(); holder.retainCallback(data)
        var commandResolutions = 0
        assertFailsWith<CliProblem> {
            holder.originalReturn(data, null, supervisor) {
                commandResolutions++
                assertSame(data, holder.originalReturned) // This is the real callsite's lazy resolver order.
                throw CliProblem("owner_model_export_output_unavailable", "SYNTHETIC no pre-output tuple")
            }
        }
        assertSame(data, holder.originalReturned); assertSame(data, holder.completed)
        assertEquals(1, commandResolutions); assertFalse(holder.childGroupQuiescent); assertFalse(holder.safeToClose)
        assertFailsWith<CliProblem> { holder.originalReturn(data, null, supervisor) { commandResolutions++; command } }
        assertEquals(1, commandResolutions); assertFalse(holder.childGroupQuiescent)
    }

    @Test fun missingOrReplacedPreselectedOutputRootCannotSupportQuiescenceVerification() {
        val selected = ownerModelFreshDirectory(directory.toRealPath().resolve("output"))
        val data = outcome(selected.path)
        val missing = OwnerModelExportReturnCustody()
        missing.enterRunner(); missing.retainCallback(data)
        assertFailsWith<CliProblem> { missing.originalReturn(data, null, supervisor) { command } }
        val holder = OwnerModelExportReturnCustody()
        holder.enterRunner(); holder.retainCallback(data)
        Files.move(selected.path, directory.toRealPath().resolve("retained-original-output"))
        Files.createDirectory(selected.path)
        assertFailsWith<CliProblem> { holder.originalReturn(data, selected, supervisor) { command } }
        assertFalse(holder.safeToClose); assertFalse(holder.childGroupQuiescent)
    }

    @Test fun retirementRequiresAnOwnedOpenAndOneOriginalOperationNotAStatusLabel() {
        val holder = OwnerModelGenerationRetirement("SYNTHETIC-generation", "SYNTHETIC-namespace")
        var calls = 0
        val data = OwnerLiveGenerationStatus("SYNTHETIC-generation", "SYNTHETIC-namespace", "closed-label-is-not-a-receipt", null, null, null)
        assertFailsWith<CliProblem> { holder.retire(false) { calls++; data } }
        assertEquals(0, calls); assertFalse(holder.attempted); assertFalse(holder.verified)
        assertSame(data, holder.retire(true) { calls++; data })
        assertTrue(holder.attempted); assertFalse(holder.threw); assertFalse(holder.verified)
        assertSame(data, holder.returned)
        assertFailsWith<CliProblem> { holder.retire(true) { calls++; data } }
        assertEquals(1, calls); assertFalse(holder.verified)
    }

    @Test fun thrownOrForeignRetirementReturnRetainsUncertaintyAndCannotRetry() {
        val thrown = OwnerModelGenerationRetirement("generation", "namespace")
        var calls = 0
        assertFailsWith<IllegalStateException> { thrown.retire(true) { calls++; error("SYNTHETIC retirement failure") } }
        assertTrue(thrown.attempted && thrown.threw); assertFalse(thrown.verified); assertNull(thrown.returned)
        assertFailsWith<CliProblem> { thrown.retire(true) { calls++; error("must not run") } }
        assertEquals(1, calls)
        val mismatch = OwnerModelGenerationRetirement("generation", "namespace")
        val foreign = OwnerLiveGenerationStatus("different", "namespace", "retired-label", null, null, null)
        assertFailsWith<CliProblem> { mismatch.retire(true) { foreign } }
        assertSame(foreign, mismatch.returned); assertTrue(mismatch.attempted && mismatch.threw)
        assertFalse(mismatch.verified)
    }

    @Test fun parentEmptyMissingOrRunningRowsAreNotTerminalAndTerminalIsNotCleanup() {
        val slots = listOf("producer", "consumer").map { slot -> PlanSlotStatus(slot, "review-$slot", RuleContext(compilation = slot),
            listOf("documentary-unit-rule"), "running", emptyList()) }
        val parent = ParentPlanStatus("parent", "client", "patch", "snapshot", Digests.sha256("DATA plan"), "incomplete", slots)
        val children = slots.map { ReviewStatus(it.reviewId, "client", "child-data", 1, "snapshot", "running") }
        assertFalse(OwnerModelPlanStatus(parent.copy(slots = emptyList()), emptyList()).terminal)
        assertFalse(OwnerModelPlanStatus(parent, children.take(1)).terminal)
        assertFalse(OwnerModelPlanStatus(parent, children).terminal)
        val cancelled = children.map { it.copy(state = "cancelled", cancelled = true) }.toMutableList()
        val terminal = OwnerModelPlanStatus(parent, cancelled)
        cancelled.clear()
        assertTrue(terminal.terminal); assertEquals(2, terminal.children.size)
        assertFalse(OwnerModelPlanStatus(parent, terminal.children.map { it.copy(reviewId = "wrong") }).terminal)
        // Terminal vector does NOT produce any original CLOSED retirement object.
        assertFalse(OwnerModelGenerationRetirement("generation", "namespace").verified)
    }

    @Test fun actualUnavailableConsumerShapeIsTerminalWithoutInventingACompilerResult() {
        val slot = PlanSlotStatus("consumer", "review", RuleContext(), emptyList(), "unavailable", listOf("producer unavailable"))
        val parent = ParentPlanStatus("parent", "client", "patch", "snapshot", Digests.sha256("unit"), "incomplete", listOf(slot))
        val child = ReviewStatus("review", "client", "child", 1, "snapshot", "incomplete", reasons = listOf("producer unavailable"))
        assertTrue(OwnerModelPlanStatus(parent, listOf(child)).terminal)
        assertNull(child.result)
        assertFalse(OwnerModelPlanStatus(parent, listOf(child.copy(reasons = emptyList()))).terminal)
    }

    private class IdleReached : RuntimeException()
    @Test fun actualSessionLifetimeCallsiteFencesRetainsAndDeniesBeforeAllocatingTheAnchor() {
        val calls = mutableListOf<String>()
        val lifetime = OwnerModelLifetimeQuarantine({ calls += "fence" }, { calls += "retain" }, { calls += "deny" },
            { calls += "anchor-start" }, { calls += "must-not-idle-on-caller"; throw IdleReached() })
        lifetime.enter()
        assertEquals(listOf("fence", "retain", "deny", "anchor-start"), calls)
    }

    @Test fun failedAnchorStartIdlesWithoutCloseRetireRetryOrRegularReturn() {
        val calls = mutableListOf<String>()
        val lifetime = OwnerModelLifetimeQuarantine({ calls += "fence" }, { calls += "retain" }, { calls += "deny" },
            { calls += "anchor-start"; error("SYNTHETIC thread-start failure") }, { calls += "idle"; throw IdleReached() })
        assertFailsWith<IdleReached> { lifetime.enter() }
        assertEquals(listOf("fence", "retain", "deny", "anchor-start", "idle"), calls)
    }

    @Test fun firstRetentionFailureAtActualSessionLifetimeCallsiteCannotEscapeItsIdleFallback() {
        val calls = mutableListOf<String>()
        val lifetime = OwnerModelLifetimeQuarantine({ calls += "fence" },
            { calls += "retain-start"; error("SYNTHETIC first retain failure") }, { calls += "must-not-deny" },
            { calls += "must-not-start" }, { calls += "idle"; throw IdleReached() })
        assertFailsWith<IdleReached> { lifetime.enter() }
        assertEquals(listOf("fence", "retain-start", "idle"), calls)
    }

    @Test fun failedFenceOrDenialAlsoCannotBypassTheSameProtectedLifetimePath() {
        for (stage in listOf("fence", "deny")) {
            val calls = mutableListOf<String>()
            fun step(name: String) { calls += name; if (name == stage) error("SYNTHETIC $stage failure") }
            val lifetime = OwnerModelLifetimeQuarantine({ step("fence") }, { step("retain") }, { step("deny") },
                { step("must-not-start") }, { calls += "idle"; throw IdleReached() })
            assertFailsWith<IdleReached> { lifetime.enter() }
            assertEquals(if (stage == "fence") listOf("fence", "idle") else listOf("fence", "retain", "deny", "idle"), calls)
        }
    }
}
