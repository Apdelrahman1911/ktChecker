package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerJobWire;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static dev.ktchecker.compilerworker.live.CompilerApplicationMain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure/fake worker controls only; none proves a real K2 reset, native duplex or default authority. */
class CompilerApplicationLifecycleTest {
    private static final String SOURCE = "/fixture/Fixture.kt";
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String DIGEST = "sha256:" + "1".repeat(64);
    private static final CompilerJobWire.Reset RESET = new CompilerJobWire.Reset(true, true, true, true);

    @Test void oneRealisticDiagnosticIsDetachedAndCodeOneIsNotATransportFailure() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> { report(job, error("unresolved reference")); return 1; });
        Execution execution = executeOwned(factory, state, List.of("fixed"));
        Sealed sealed = state.seal(execution);
        assertEquals(1, execution.compilerCode());
        assertEquals("UNRESOLVED_REFERENCE", sealed.diagnostics().get(0).diagnosticId());
        assertEquals(1, sealed.disposalCount());
        assertEquals(1, factory.closes.get());
        assertEquals(Phase.SEALED, state.phase());
        assertFalse(fence.isPoisoned());
        assertThrows(UnsupportedOperationException.class, () -> sealed.diagnostics().clear());
    }

    @Test void sevenIndividuallyCreatedSessionsAreNotAFixedFourJobPlan() {
        GenerationFence fence = new GenerationFence();
        FakeFactory factory = new FakeFactory(job -> 0);
        ArrayList<Sealed> results = new ArrayList<>();
        for (int sequence = 1; sequence <= 7; sequence++) {
            // This token/request is created only after the preceding fake result has sealed.
            JobState state = fence.newJob(sequence, SOURCE);
            results.add(state.seal(executeOwned(factory, state, List.of("fixed"))));
        }
        assertEquals(7, results.size());
        assertEquals(7, factory.opens.get());
        assertEquals(7, factory.closes.get());
        assertEquals(7, factory.sessionIdentities.size());
        assertFalse(fence.isPoisoned());
    }

    @Test void executeThrowStillClosesCallerOwnedSessionExactlyOnce() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> { throw new IOException("test_only"); });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(1, factory.closes.get());
        assertEquals(1, state.rootDisposals());
        assertEquals(ApplicationWire.FatalReason.COMPILER, fence.reason());
        assertThrows(WorkerFailure.class, () -> fence.newJob(2, SOURCE));
    }

    @Test void executeErrorStillClosesCallerOwnedSessionExactlyOnce() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> { throw new AssertionError("test_only"); });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(1, factory.closes.get());
        assertEquals(1, state.rootDisposals());
    }

    @Test void invalidPreResetStillClosesReturnedSession() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.before = new CompilerJobWire.Reset(false, true, true, true);
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(0, factory.executes.get());
        assertEquals(1, factory.closes.get());
        assertEquals(ApplicationWire.FatalReason.RESET, fence.reason());
    }

    @Test void disposalThrowIsNeverSealed() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.closeAction = job -> { throw new IOException("test_only"); };
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(1, factory.closes.get());
        assertEquals(0, state.rootDisposals());
        assertEquals(ApplicationWire.FatalReason.RESET, fence.reason());
    }

    @Test void missingRootCallbackPreventsSealEvenIfSessionClaimsClosed() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.closeAction = job -> {};
        Execution execution = executeOwned(factory, state, List.of("fixed"));
        assertThrows(WorkerFailure.class, () -> state.seal(execution));
        assertEquals(ApplicationWire.FatalReason.RESET, fence.reason());
    }

    @Test void duplicateRootCallbackPoisonsBeforeUpstreamCanSwallowIt() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.closeAction = job -> {
            dispose(job);
            try { dispose(job); } catch (WorkerFailure ignored) { /* upstream swallowed it */ }
        };
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(2, state.rootDisposals());
        assertEquals(ApplicationWire.FatalReason.CALLBACK, fence.reason());
    }

    @Test void resetDriftAfterCloseIsNeverSealed() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.after = new CompilerJobWire.Reset(true, false, true, true);
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertEquals(1, factory.closes.get());
        assertEquals(ApplicationWire.FatalReason.RESET, fence.reason());
    }

    @Test void unrestoredContextLoaderCannotSeal() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.after = new CompilerJobWire.Reset(true, true, true, false);
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
        assertTrue(fence.isPoisoned());
    }

    @Test void unexpectedCompilerApiCodeCannotBecomeProcessSuccess() {
        for (int code : new int[]{-1, 2, 3, 255}) {
            GenerationFence fence = new GenerationFence();
            JobState state = fence.newJob(1, SOURCE);
            FakeFactory factory = new FakeFactory(job -> code);
            assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of("fixed")));
            assertEquals(1, factory.closes.get());
            assertEquals(ApplicationWire.FatalReason.COMPILER, fence.reason());
        }
    }

    @Test void clearAndHasErrorsAreLeasedAndCannotEraseDetachedDiagnostics() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        state.startExecuting();
        try (Lease lease = state.enter(Callback.COLLECTOR)) {
            state.clear(lease);
            assertFalse(state.hasErrors(lease));
            state.report(lease, error("first"));
            assertTrue(state.hasErrors(lease));
            assertThrows(WorkerFailure.class, () -> state.clear(lease));
        }
        assertEquals(ApplicationWire.FatalReason.CALLBACK, fence.reason());
    }

    @Test void callbackBeforeExecutionIsRejectedBeforeGetterEvaluation() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        AtomicInteger reads = new AtomicInteger();
        assertThrows(WorkerFailure.class, () -> {
            try (Lease lease = state.enter(Callback.COLLECTOR)) {
                reads.incrementAndGet();
                state.hasErrors(lease);
            }
        });
        assertEquals(0, reads.get());
        assertTrue(fence.isPoisoned());
    }

    @Test void lateCallbackCannotMutateSealedDiagnostics() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        Sealed sealed = state.seal(executeOwned(new FakeFactory(job -> { report(job, error("first")); return 1; }), state, List.of()));
        assertThrows(WorkerFailure.class, () -> report(state, error("late")));
        assertEquals(List.of(error("first")), sealed.diagnostics());
        assertThrows(WorkerFailure.class, () -> fence.newJob(2, SOURCE));
    }

    @Test void previousCollectorDuringNextJobPoisonsWithoutWritingNextCollector() {
        GenerationFence fence = new GenerationFence();
        JobState previous = fence.newJob(1, SOURCE);
        previous.seal(executeOwned(new FakeFactory(job -> 0), previous, List.of()));
        JobState next = fence.newJob(2, SOURCE);
        FakeFactory factory = new FakeFactory(job -> {
            try { report(previous, error("late")); } catch (WorkerFailure ignored) {}
            return 0;
        });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, next, List.of()));
        assertEquals(1, factory.closes.get());
        assertEquals(ApplicationWire.FatalReason.CALLBACK, fence.reason());
    }

    @Test void rootCallbackAfterLastSealDoesNotChangeDisposalCount() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        Sealed sealed = state.seal(executeOwned(new FakeFactory(job -> 0), state, List.of()));
        assertThrows(WorkerFailure.class, () -> dispose(state));
        assertEquals(1, state.rootDisposals());
        assertEquals(1, sealed.disposalCount());
        assertTrue(fence.isPoisoned());
    }

    @Test void foreignThreadCallbackIsRejectedBeforeDetachedConstruction() throws InterruptedException {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        state.startExecuting();
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try (Lease lease = state.enter(Callback.COLLECTOR)) {
                reads.incrementAndGet();
                state.hasErrors(lease);
            } catch (Throwable failure) { thrown.set(failure); }
        });
        thread.start();
        thread.join(2_000);
        assertFalse(thread.isAlive());
        assertInstanceOf(WorkerFailure.class, thrown.get());
        assertEquals(0, reads.get());
        assertTrue(fence.isPoisoned());
    }

    @Test void reentrantCallbackIsStickyEvenWhenSwallowed() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> {
            try (Lease lease = job.enter(Callback.COLLECTOR)) {
                try { job.enter(Callback.COLLECTOR); } catch (WorkerFailure ignored) {}
            }
            return 0;
        });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of()));
        assertEquals(1, factory.closes.get());
        assertEquals(ApplicationWire.FatalReason.CALLBACK, fence.reason());
    }

    @Test void inFlightLeaseAtSealIsNotAnImplicitWaitOrSuccess() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        Execution execution = executeOwned(new FakeFactory(job -> 0), state, List.of());
        try (Lease lease = state.enter(Callback.COLLECTOR)) {
            assertThrows(WorkerFailure.class, () -> state.seal(execution));
        }
        assertTrue(fence.isPoisoned());
    }

    @Test void disposalTimeMessagesBelongOnlyToTheSameOpenToken() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> 0);
        factory.closeAction = job -> { report(job, info("disposing")); dispose(job); };
        Sealed sealed = state.seal(executeOwned(factory, state, List.of()));
        assertEquals(List.of(info("disposing")), sealed.diagnostics());
        assertFalse(fence.isPoisoned());
    }

    @Test void leaseFromAnotherGenerationCannotBeUsedToCommit() {
        GenerationFence firstFence = new GenerationFence();
        GenerationFence secondFence = new GenerationFence();
        JobState first = firstFence.newJob(1, SOURCE);
        JobState second = secondFence.newJob(1, SOURCE);
        first.startExecuting(); second.startExecuting();
        try (Lease lease = first.enter(Callback.COLLECTOR)) {
            assertThrows(WorkerFailure.class, () -> second.report(lease, info("foreign")));
        }
        assertTrue(secondFence.isPoisoned());
        assertFalse(firstFence.isPoisoned());
    }

    @Test void diagnosticLimitRejectionRemainsStickyAfterSwallow() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> {
            for (int i = 0; i < CompilerJobWire.MAX_DIAGNOSTICS; i++) report(job, info("bounded"));
            try { report(job, info("overflow")); } catch (WorkerFailure ignored) {}
            return 0;
        });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of()));
        assertEquals(ApplicationWire.FatalReason.LIMIT, fence.reason());
    }

    @Test void diagnosticCanonicalByteLimitPoisonsBeforeSeal() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> {
            for (int i = 0; i < 4; i++) report(job, info("x".repeat(CompilerJobWire.MAX_SOURCE_BYTES)));
            return 0;
        });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of()));
        assertEquals(ApplicationWire.FatalReason.LIMIT, fence.reason());
    }

    @Test void exceptionOutputAndCancellationMessagesCannotSeal() {
        for (CompilerJobWire.Diagnostic diagnostic : List.of(
                new CompilerJobWire.Diagnostic("EXCEPTION", "fixed", "", -1, -1, ""),
                new CompilerJobWire.Diagnostic("OUTPUT", "fixed", "", -1, -1, ""),
                info("Compilation was canceled"), info("Compilation was cancelled"))) {
            GenerationFence fence = new GenerationFence();
            JobState state = fence.newJob(1, SOURCE);
            FakeFactory factory = new FakeFactory(job -> {
                try { report(job, diagnostic); } catch (WorkerFailure ignored) {}
                return 0;
            });
            assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of()));
            assertEquals(ApplicationWire.FatalReason.COMPILER, fence.reason());
        }
    }

    @Test void locationOutsideExactLogicalOrPhysicalSourcePoisons() {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        FakeFactory factory = new FakeFactory(job -> {
            report(job, new CompilerJobWire.Diagnostic("ERROR", "fixed", "/elsewhere/Fixture.kt", 1, 1, "TEST"));
            return 1;
        });
        assertThrows(WorkerFailure.class, () -> executeOwned(factory, state, List.of()));
        assertEquals(ApplicationWire.FatalReason.CALLBACK, fence.reason());
    }

    @Test void firstPoisonIsNotOverwrittenByLaterFailures() {
        GenerationFence fence = new GenerationFence();
        fence.poison(ApplicationWire.FatalReason.RAW_IO);
        fence.poison(ApplicationWire.FatalReason.RESET);
        assertEquals(ApplicationWire.FatalReason.RAW_IO, fence.reason());
    }

    @Test void noConcurrentOrNonMonotonicJobAdmission() {
        GenerationFence fence = new GenerationFence();
        fence.newJob(1, SOURCE);
        assertThrows(WorkerFailure.class, () -> fence.newJob(2, SOURCE));
        GenerationFence skipped = new GenerationFence();
        assertThrows(WorkerFailure.class, () -> skipped.newJob(2, SOURCE));
    }

    @Test void rawPrintStreamSwallowDoesNotEraseStickyPoisonOrForwardBytes() {
        GenerationFence fence = new GenerationFence();
        ByteArrayOutputStream privateTransport = new ByteArrayOutputStream();
        PrintStream publicCompilerStream = new PrintStream(new PoisonOutput(fence), false, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> publicCompilerStream.print("not forwarded"));
        assertTrue(publicCompilerStream.checkError());
        assertEquals(0, privateTransport.size());
        assertTrue(fence.rawOperations() > 0);
        assertTrue(fence.rawBytes() > 0);
        assertEquals(ApplicationWire.FatalReason.RAW_IO, fence.reason());
    }

    @Test void everyRawInputAttemptIncludingZeroLengthPoisons() {
        GenerationFence fence = new GenerationFence();
        PoisonInput publicInput = new PoisonInput(fence);
        assertThrows(IOException.class, () -> publicInput.readNBytes(0));
        assertThrows(IOException.class, publicInput::read);
        assertThrows(IOException.class, publicInput::readAllBytes);
        assertThrows(IOException.class, () -> publicInput.read(new byte[0]));
        assertThrows(IOException.class, publicInput::available);
        assertEquals(5, fence.rawOperations());
        assertEquals(ApplicationWire.FatalReason.RAW_IO, fence.reason());
    }

    @Test void rawCountersSaturateWithoutKeepingArbitraryBytes() {
        GenerationFence fence = new GenerationFence();
        fence.rawAttempt(Long.MAX_VALUE);
        fence.rawAttempt(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, fence.rawBytes());
        assertEquals(2, fence.rawOperations());
    }

    @Test void frameReaderAcceptsEveryByteSplitAndIdleEof() throws IOException {
        ApplicationWire.Job job = job(1);
        byte[] frame = ApplicationWire.frame(job);
        GenerationFence fence = new GenerationFence();
        InputStream input = new ByteArrayInputStream(frame) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        FrameReader reader = new FrameReader(input, limits(7), fence);
        assertEquals(job, reader.next());
        assertNull(reader.next());
        assertFalse(fence.isPoisoned());
    }

    @Test void frameReaderDoesNotQueuePrebatchedRequests() {
        byte[] first = ApplicationWire.frame(job(1));
        byte[] second = ApplicationWire.frame(job(2));
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        GenerationFence fence = new GenerationFence();
        FrameReader reader = new FrameReader(new ByteArrayInputStream(both), limits(7), fence);
        assertThrows(RuntimeException.class, reader::next);
        assertTrue(fence.isPoisoned());
    }

    @Test void partialFrameEofIsNeverAnIdleRetirement() {
        byte[] frame = ApplicationWire.frame(job(1));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(java.util.Arrays.copyOf(frame, frame.length - 1)), limits(7), new GenerationFence());
        assertThrows(ApplicationWire.ProtocolException.class, reader::next);
    }

    @Test void lateIdleCallbackEmitsFramedFatalWithoutAnotherInputOrEof() throws Exception {
        GenerationFence fence = new GenerationFence();
        JobState state = fence.newJob(1, SOURCE);
        state.seal(executeOwned(new FakeFactory(job -> 0), state, List.of()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(new PrintStream(bytes, false, StandardCharsets.UTF_8), 1_048_576, fence, () -> {});
        ApplicationWire.Result result = detachedResult(1);
        writer.write(result);
        Thread reporter = startPoisonReporter(writer, fence, TOKEN);
        assertThrows(WorkerFailure.class, () -> report(state, error("late")));
        reporter.join(2_000);
        assertFalse(reporter.isAlive());
        List<ApplicationWire.Message> frames = decodeFrames(bytes.toByteArray());
        assertEquals(2, frames.size());
        assertEquals(result, frames.get(0));
        assertEquals(new ApplicationWire.Fatal(TOKEN, 1, ApplicationWire.FatalReason.CALLBACK), frames.get(1));
    }

    @Test void simultaneousMainAndReporterFailuresEmitAtMostOneFatal() throws Exception {
        GenerationFence fence = new GenerationFence();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(new PrintStream(bytes, false, StandardCharsets.UTF_8), 1_048_576, fence, () -> {});
        Thread reporter = startPoisonReporter(writer, fence, TOKEN);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread mainFailure = new Thread(() -> {
            try {
                start.await();
                fence.poison(ApplicationWire.FatalReason.RESET);
                writer.fatal(new ApplicationWire.Fatal(TOKEN, 0, fence.reason()));
            } catch (Throwable failure) { unexpected.set(failure); }
        });
        Thread callbackFailure = new Thread(() -> {
            try { start.await(); fence.poison(ApplicationWire.FatalReason.RAW_IO); }
            catch (Throwable failure) { unexpected.set(failure); }
        });
        mainFailure.start(); callbackFailure.start(); start.countDown();
        mainFailure.join(2_000); callbackFailure.join(2_000); reporter.join(2_000);
        assertFalse(mainFailure.isAlive() || callbackFailure.isAlive() || reporter.isAlive());
        assertNull(unexpected.get());
        assertThrows(WorkerFailure.class, () -> writer.write(detachedResult(1)));
        List<ApplicationWire.Message> frames = decodeFrames(bytes.toByteArray());
        assertEquals(List.of(new ApplicationWire.Fatal(TOKEN, 0, fence.reason())), frames);
    }

    @Test void poisonRacingReservedFrameIsFollowedByFatalAndNoNewResult() throws Exception {
        GenerationFence fence = new GenerationFence();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.io.OutputStream output = new java.io.OutputStream() {
            boolean first = true;
            @Override public void write(int value) { throw new AssertionError("bulk expected"); }
            @Override public void write(byte[] value, int offset, int count) {
                bytes.write(value, offset, count);
                if (first) { first = false; fence.poison(ApplicationWire.FatalReason.RAW_IO); }
            }
        };
        FrameWriter writer = new FrameWriter(new PrintStream(output, false, StandardCharsets.UTF_8), 1_048_576, fence, () -> {});
        Thread reporter = startPoisonReporter(writer, fence, TOKEN);
        ApplicationWire.Result alreadyReserved = detachedResult(1);
        writer.write(alreadyReserved);
        reporter.join(2_000);
        assertFalse(reporter.isAlive());
        assertThrows(WorkerFailure.class, () -> writer.write(detachedResult(2)));
        assertEquals(List.of(alreadyReserved, new ApplicationWire.Fatal(TOKEN, 0, ApplicationWire.FatalReason.RAW_IO)),
                decodeFrames(bytes.toByteArray()));
    }

    private static ApplicationWire.Result detachedResult(int sequence) {
        // Generic framing data only, not a fabricated compiler observation or provider result.
        return new ApplicationWire.Result(TOKEN, DIGEST, sequence, ApplicationWire.jobId(TOKEN, sequence),
                DIGEST, DIGEST, DIGEST, TOKEN, ApplicationWire.ResultState.APPLICATION_SEALED, new byte[0]);
    }
    private static List<ApplicationWire.Message> decodeFrames(byte[] bytes) {
        ArrayList<ApplicationWire.Message> frames = new ArrayList<>();
        ApplicationWire.Decoder decoder = new ApplicationWire.Decoder(9, 1_048_576);
        decoder.accept(bytes, frames::add);
        decoder.endOfInput();
        return List.copyOf(frames);
    }

    private static CompilerJobWire.Diagnostic error(String message) {
        return new CompilerJobWire.Diagnostic("ERROR", message, SOURCE, 1, 1, "UNRESOLVED_REFERENCE");
    }
    private static CompilerJobWire.Diagnostic info(String message) {
        return new CompilerJobWire.Diagnostic("INFO", message, "", -1, -1, "");
    }
    private static void report(JobState state, CompilerJobWire.Diagnostic diagnostic) {
        try (Lease lease = state.enter(Callback.COLLECTOR)) { state.report(lease, diagnostic); }
    }
    private static void dispose(JobState state) {
        try (Lease lease = state.enter(Callback.ROOT_DISPOSAL)) { state.rootDisposed(lease); }
    }
    private static ApplicationWire.Limits limits(int jobs) { return new ApplicationWire.Limits(jobs, 1_048_576, 1_048_576); }
    private static ApplicationWire.Job job(int sequence) {
        return new ApplicationWire.Job(TOKEN, DIGEST, sequence, ApplicationWire.jobId(TOKEN, sequence), DIGEST, DIGEST, TOKEN, new byte[0]);
    }

    @FunctionalInterface private interface ExecuteAction { int run(JobState state) throws Exception; }
    @FunctionalInterface private interface CloseAction { void run(JobState state) throws Exception; }
    private static final class FakeFactory implements SessionFactory {
        final ExecuteAction action;
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger executes = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final java.util.Set<Object> sessionIdentities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        CompilerJobWire.Reset before = RESET;
        CompilerJobWire.Reset after = RESET;
        CloseAction closeAction = CompilerApplicationLifecycleTest::dispose;
        FakeFactory(ExecuteAction action) { this.action = action; }
        @Override public Session open(JobState state) {
            opens.incrementAndGet();
            Session session = new Session() {
                boolean completed;
                @Override public int execute(List<String> argv) throws Exception { executes.incrementAndGet(); return action.run(state); }
                @Override public void close() throws Exception { closes.incrementAndGet(); closeAction.run(state); completed = true; }
                @Override public CompilerJobWire.Reset beforeReset() { return before; }
                @Override public CompilerJobWire.Reset afterReset() { return after; }
                @Override public boolean disposalCompleted() { return completed; }
            };
            sessionIdentities.add(session);
            return session;
        }
        @Override public String compilerVersion() { return CompilerJobWire.COMPILER_VERSION; }
        @Override public boolean originsVerified() { return true; }
        @Override public CompilerJobWire.Reset initialReset() { return RESET; }
        @Override public void close() {}
    }
}
