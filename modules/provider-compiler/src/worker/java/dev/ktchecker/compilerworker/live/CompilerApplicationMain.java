package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerJobWire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.ktchecker.workerprotocol.ApplicationWire.FatalReason;

/**
 * Fixed, dormant M0 bridge. Its only external entry always constructs the real pinned K2 adapter.
 * The application protocol is sequential and persistent, not a launch-time job list. A compiler
 * code of one is a diagnostic result, never a process exit or a transport-cleanup observation.
 * No reset bit here proves isolation of arbitrary JVM statics, threads or native state.
 */
public final class CompilerApplicationMain {
    static final int MAX_JOB_OUTPUT_FILES = CompilerJobWire.MAX_JOB_OUTPUT_FILES;
    static final long MAX_JOB_OUTPUT_BYTES = CompilerJobWire.MAX_JOB_OUTPUT_BYTES;
    static final int MAX_GENERATION_OUTPUT_FILES = 1024;
    static final long MAX_GENERATION_OUTPUT_BYTES = CompilerJobWire.MAX_GENERATION_OUTPUT_BYTES;
    static final int MAX_TEMP_FILES = CompilerJobWire.MAX_TEMP_FILES;
    static final long MAX_TEMP_BYTES = CompilerJobWire.MAX_TEMP_BYTES;
    static final int MAX_TREE_DEPTH = 16;

    private CompilerApplicationMain() {}

    public static void main(String[] args) {
        // Keep these private. Neither the compiler nor a collector is handed these references.
        InputStream input = System.in;
        PrintStream output = System.out;
        GenerationFence fence = new GenerationFence();
        PoisonIo io = PoisonIo.install(fence);
        ApplicationWire.Launch launch = null;
        PinnedKotlinCompilerAdapter adapter = null;
        FrameWriter writer = null;
        int sequence = 0;
        try {
            require(args.length == 1, fence, FatalReason.PROTOCOL);
            Path descriptor = canonicalPath(args[0]);
            byte[] encodedLaunch = readBoundedFile(descriptor, ApplicationWire.MAX_LAUNCH_BYTES);
            launch = ApplicationWire.decodeLaunch(encodedLaunch);
            require(Arrays.equals(encodedLaunch, ApplicationWire.encodeLaunch(launch)), fence, FatalReason.PROTOCOL);
            CompilerJobWire.Generation generation = CompilerJobWire.decodeGeneration(launch.payload());
            CompilerJobWire.matchGeneration(launch, generation);
            require(descriptor.equals(Path.of(launch.generationRoot().path()).resolve("launch.bin")), fence, FatalReason.IDENTITY);
            GenerationFiles files = new GenerationFiles(launch, generation, encodedLaunch);
            files.verifyInstallation();
            verifyProcess(launch, generation, io, fence);
            writer = new FrameWriter(output, launch.limits().outputLimit(), fence, io::requireUnchanged);
            startPoisonReporter(writer, fence, launch.identity().generation());
            adapter = new PinnedKotlinCompilerAdapter(launch, generation, files, fence);
            files.verifyInstallation();
            io.requireUnchanged();
            fence.requireHealthy();
            String loaderToken = UUID.randomUUID().toString().replace("-", "");
            ApplicationWire.Hello hello = new ApplicationWire.Hello(
                    launch.identity(), ApplicationWire.launchDigest(launch), loaderToken,
                    adapter.compilerVersion(), adapter.originsVerified(), adapter.initialReset().complete());
            CompilerJobWire.matchHello(launch, generation, hello);
            writer.write(hello);
            FrameReader reader = new FrameReader(input, launch.limits(), fence);
            while (true) {
                // In particular, do not exit at maxJobs: the owner still needs a LIVE DISARM ACK.
                io.requireUnchanged();
                fence.requireHealthy();
                ApplicationWire.Message message = reader.next();
                if (message == null) {
                    // EOF is healthy only between complete requests, with the closure still owned.
                    files.verifyInstallation();
                    verifyProcess(launch, generation, io, fence);
                    break;
                }
                io.requireUnchanged();
                fence.requireHealthy();
                require(message instanceof ApplicationWire.Job, fence, FatalReason.PROTOCOL);
                ApplicationWire.Job job = (ApplicationWire.Job) message;
                require(sequence < launch.limits().maxJobs(), fence, FatalReason.LIMIT);
                ApplicationWire.matchJob(launch, hello, job, sequence + 1);
                CompilerJobWire.Request request = CompilerJobWire.decodeRequest(job.payload());
                CompilerJobWire.matchRequest(launch, generation, job, request);
                sequence = job.sequence();
                verifyProcess(launch, generation, io, fence);
                JobFiles jobFiles = files.admit(job, request);
                List<String> argv = CompilerJobWire.arguments(launch, generation, request);
                JobState state = fence.newJob(sequence, jobFiles.source.path().toString());
                try {
                    Execution execution = executeOwned(adapter, state, argv);
                    // No lock is held during filesystem scans, compiler execution, disposal or IPC.
                    jobFiles.verifyAfter();
                    verifyProcess(launch, generation, io, fence);
                    Sealed sealed = state.seal(execution);
                    CompilerJobWire.Observation observation = new CompilerJobWire.Observation(
                            adapter.compilerVersion(), CompilerJobWire.argvDigest(argv),
                            CompilerJobWire.membersDigest(request.sources()),
                            request.jobRoot(), request.sourceRoot(), request.outputRoot(),
                            true, execution.compilerCode(), sealed.disposalCount(), true, false,
                            execution.beforeReset(), execution.afterReset(), true, true, true,
                            CompilerJobWire.FailureCode.NONE, sealed.diagnostics());
                    CompilerJobWire.requireSealed(observation);
                    ApplicationWire.Result result = new ApplicationWire.Result(
                            job.generation(), ApplicationWire.helloDigest(hello), job.sequence(), job.jobId(),
                            ApplicationWire.requestDigest(job), job.originalKey(), job.effectiveKey(), loaderToken,
                            ApplicationWire.ResultState.APPLICATION_SEALED, CompilerJobWire.encodeObservation(observation));
                    CompilerJobWire.matchObservation(launch, generation, job, request, result, observation);
                    ApplicationWire.matchResult(launch, hello, job, result);
                    io.requireUnchanged();
                    fence.requireHealthy();
                    writer.write(result);
                } catch (Throwable failure) {
                    state.invalidate();
                    throw failure;
                }
                // No invocation, pipeline, compiler diagnostic object or session is retained here.
            }
        } catch (ApplicationWire.ProtocolException malformed) {
            fence.poison(FatalReason.PROTOCOL);
        } catch (WorkerFailure rejected) {
            fence.poison(rejected.reason);
        } catch (Throwable ignored) {
            // Never print exception messages, stack traces, compiler text or a terminal transcript.
            fence.poison(FatalReason.INTERNAL);
        } finally {
            if (adapter != null) {
                try { adapter.close(); }
                catch (Throwable ignored) { fence.poison(FatalReason.COMPILER); }
            }
            try { io.requireUnchanged(); }
            catch (Throwable ignored) { fence.poison(FatalReason.RAW_IO); }
            if (fence.isPoisoned() && launch != null) {
                try {
                    if (writer == null) writer = new FrameWriter(output, launch.limits().outputLimit(), fence, io::requireUnchanged);
                    writer.fatal(new ApplicationWire.Fatal(launch.identity().generation(), sequence, fence.reason()));
                } catch (Throwable ignored) { /* One best effort, never retry or fall back to raw text. */ }
            }
        }
        // Native owned-process v2, not this exit status, establishes generation cleanup/ownership.
        System.exit(fence.isPoisoned() ? 70 : 0);
    }

    /** Worker-package-only seam for focused tests; external main never accepts an injected factory. */
    interface SessionFactory extends AutoCloseable {
        Session open(JobState state) throws Exception;
        String compilerVersion();
        boolean originsVerified();
        CompilerJobWire.Reset initialReset();
        @Override void close() throws Exception;
    }

    interface Session extends AutoCloseable {
        int execute(List<String> argv) throws Exception;
        CompilerJobWire.Reset beforeReset();
        CompilerJobWire.Reset afterReset();
        boolean disposalCompleted();
        @Override void close() throws Exception;
    }

    record Execution(int compilerCode, CompilerJobWire.Reset beforeReset, CompilerJobWire.Reset afterReset,
                     boolean disposalCompleted) {}
    record Sealed(List<CompilerJobWire.Diagnostic> diagnostics, int disposalCount) {
        Sealed { diagnostics = List.copyOf(diagnostics); }
    }

    /** Caller owns close in the outer finally, including failures before upstream pipeline setup. */
    static Execution executeOwned(SessionFactory factory, JobState state, List<String> argv) {
        Session session = null;
        CompilerJobWire.Reset before = null;
        CompilerJobWire.Reset after = null;
        boolean completed = false;
        int code = -1;
        state.startExecuting();
        try {
            session = factory.open(state);
            require(session != null, state.fence, FatalReason.COMPILER);
            before = session.beforeReset();
            require(before != null && before.complete(), state.fence, FatalReason.RESET);
            code = session.execute(List.copyOf(argv));
            state.executionReturned(code);
        } catch (Throwable ignored) {
            state.fence.poison(FatalReason.COMPILER);
        } finally {
            state.beginDisposing();
            if (session != null) {
                try {
                    session.close();
                    completed = session.disposalCompleted();
                    after = session.afterReset();
                    if (!completed || after == null || !after.complete()) state.fence.poison(FatalReason.RESET);
                } catch (Throwable ignored) {
                    state.fence.poison(FatalReason.RESET);
                }
            } else {
                state.fence.poison(FatalReason.COMPILER);
            }
        }
        state.fence.requireHealthy();
        return new Execution(code, before, after, completed);
    }

    enum Phase { NEW, EXECUTING, DISPOSING, SEALED, INVALID }
    enum Callback { COLLECTOR, ROOT_DISPOSAL }

    /** Sticky first poison, one active token, no callback routing through a mutable current sink. */
    static final class GenerationFence {
        private final Object lock = new Object();
        private final Object generationToken = new Object();
        private FatalReason poison;
        private JobState active;
        private int lastSequence;
        private int rawOperations;
        private long rawBytes;

        JobState newJob(int sequence, String sourcePath) {
            synchronized (lock) {
                healthyLocked();
                if (active != null || sequence != lastSequence + 1) throw rejectLocked(FatalReason.CALLBACK);
                JobState state = new JobState(this, generationToken, sequence, sourcePath, Thread.currentThread());
                active = state;
                lastSequence = sequence;
                return state;
            }
        }

        void poison(FatalReason reason) {
            synchronized (lock) { poisonLocked(reason); }
        }

        private void poisonLocked(FatalReason reason) {
            if (poison == null) {
                poison = reason;
                lock.notifyAll();
            }
        }
        private WorkerFailure rejectLocked(FatalReason reason) {
            poisonLocked(reason);
            return new WorkerFailure(poison);
        }
        private void healthyLocked() { if (poison != null) throw new WorkerFailure(poison); }
        void requireHealthy() { synchronized (lock) { healthyLocked(); } }
        boolean isPoisoned() { synchronized (lock) { return poison != null; } }
        FatalReason reason() { synchronized (lock) { return poison == null ? FatalReason.INTERNAL : poison; } }
        int currentSequence() { synchronized (lock) { return lastSequence; } }
        FatalReason awaitPoison() throws InterruptedException {
            synchronized (lock) {
                while (poison == null) lock.wait();
                return poison;
            }
        }

        void rawAttempt(long bytes) {
            synchronized (lock) {
                poisonLocked(FatalReason.RAW_IO);
                if (rawOperations < Integer.MAX_VALUE) rawOperations++;
                long amount = Math.max(0L, bytes);
                rawBytes = amount > Long.MAX_VALUE - rawBytes ? Long.MAX_VALUE : rawBytes + amount;
            }
        }
        int rawOperations() { synchronized (lock) { return rawOperations; } }
        long rawBytes() { synchronized (lock) { return rawBytes; } }
    }

    static final class JobState {
        private final GenerationFence fence;
        private final Object generationToken;
        private final Object jobToken = new Object();
        private final int sequence;
        private final String sourcePath;
        private final Thread invokingThread;
        private Phase phase = Phase.NEW;
        private int leases;
        private Thread leaseThread;
        private boolean returned;
        private int compilerCode = -1;
        private int rootDisposals;
        private int diagnosticBytes = 4; // Canonical diagnostic count prefix is part of the cap.
        private boolean errors;
        private ArrayList<CompilerJobWire.Diagnostic> diagnostics = new ArrayList<>();

        private JobState(GenerationFence fence, Object generationToken, int sequence, String sourcePath, Thread invokingThread) {
            this.fence = fence;
            this.generationToken = generationToken;
            this.sequence = sequence;
            this.sourcePath = sourcePath;
            this.invokingThread = invokingThread;
        }

        void startExecuting() {
            synchronized (fence.lock) {
                fence.healthyLocked();
                if (!activeLocked() || phase != Phase.NEW || Thread.currentThread() != invokingThread)
                    throw fence.rejectLocked(FatalReason.CALLBACK);
                phase = Phase.EXECUTING;
            }
        }

        void requireExecuting() {
            synchronized (fence.lock) {
                fence.healthyLocked();
                if (!activeLocked() || phase != Phase.EXECUTING || Thread.currentThread() != invokingThread)
                    throw fence.rejectLocked(FatalReason.CALLBACK);
            }
        }

        Lease enter(Callback kind) {
            synchronized (fence.lock) {
                boolean cleanup = kind == Callback.ROOT_DISPOSAL && phase == Phase.DISPOSING;
                if (!activeLocked() || Thread.currentThread() != invokingThread || leases != 0
                        || (kind == Callback.COLLECTOR && phase != Phase.EXECUTING && phase != Phase.DISPOSING)
                        || (kind == Callback.ROOT_DISPOSAL && !cleanup))
                    throw fence.rejectLocked(FatalReason.CALLBACK);
                // Permit only this job's identity-only root cleanup after an earlier execute failure.
                // Poison is never cleared and no collector is allowed to publish after it.
                if (fence.poison != null && !cleanup) throw new WorkerFailure(fence.poison);
                leases = 1;
                leaseThread = Thread.currentThread();
                return new Lease(this, jobToken, kind, leaseThread);
            }
        }

        private boolean activeLocked() {
            return generationToken == fence.generationToken && fence.active == this;
        }

        private void checkLeaseLocked(Lease lease, Callback expected) {
            if (lease.state != this || lease.token != jobToken || lease.closed || lease.kind != expected
                    || !activeLocked() || leases != 1 || leaseThread != Thread.currentThread()
                    || lease.thread != Thread.currentThread()
                    || (phase != Phase.EXECUTING && phase != Phase.DISPOSING))
                throw fence.rejectLocked(FatalReason.CALLBACK);
            if (expected == Callback.ROOT_DISPOSAL && phase != Phase.DISPOSING)
                throw fence.rejectLocked(FatalReason.CALLBACK);
            if (expected == Callback.COLLECTOR) fence.healthyLocked();
        }

        void report(Lease lease, CompilerJobWire.Diagnostic diagnostic) {
            try {
                CompilerJobWire.validateDiagnostic(diagnostic);
                if (diagnostic.severity().equals("EXCEPTION") || diagnostic.severity().equals("OUTPUT"))
                    throw new WorkerFailure(FatalReason.COMPILER);
                if (!diagnostic.path().isEmpty() && !diagnostic.path().equals("Fixture.kt")
                        && !diagnostic.path().equals(sourcePath)) throw new WorkerFailure(FatalReason.CALLBACK);
                if (diagnostic.message().equals("Compilation was canceled")
                        || diagnostic.message().equals("Compilation was cancelled"))
                    throw new WorkerFailure(FatalReason.COMPILER);
                int bytes = 1 + 4 + utf8Length(diagnostic.message()) + 4 + utf8Length(diagnostic.path())
                        + 4 + 4 + 4 + utf8Length(diagnostic.diagnosticId());
                synchronized (fence.lock) {
                    checkLeaseLocked(lease, Callback.COLLECTOR);
                    if (diagnostics.size() >= CompilerJobWire.MAX_DIAGNOSTICS
                            || bytes > CompilerJobWire.MAX_DIAGNOSTIC_BYTES - diagnosticBytes)
                        throw fence.rejectLocked(FatalReason.LIMIT);
                    diagnosticBytes += bytes;
                    diagnostics.add(diagnostic);
                    if (diagnostic.severity().equals("ERROR")) errors = true;
                }
            } catch (Throwable failure) {
                callbackFailed(failure instanceof WorkerFailure rejected ? rejected.reason : FatalReason.CALLBACK);
                throw failure;
            }
        }

        void clear(Lease lease) {
            synchronized (fence.lock) {
                checkLeaseLocked(lease, Callback.COLLECTOR);
                // A later clear must not erase a real error or previously detached observations.
                if (!diagnostics.isEmpty() || errors || diagnosticBytes != 4)
                    throw fence.rejectLocked(FatalReason.CALLBACK);
            }
        }

        boolean hasErrors(Lease lease) {
            synchronized (fence.lock) {
                checkLeaseLocked(lease, Callback.COLLECTOR);
                return errors;
            }
        }

        void rootDisposed(Lease lease) {
            synchronized (fence.lock) {
                checkLeaseLocked(lease, Callback.ROOT_DISPOSAL);
                if (rootDisposals < 2) rootDisposals++;
                if (rootDisposals != 1) throw fence.rejectLocked(FatalReason.CALLBACK);
            }
        }

        void callbackFailed(FatalReason reason) { fence.poison(reason); }

        void executionReturned(int code) {
            synchronized (fence.lock) {
                if (!activeLocked() || phase != Phase.EXECUTING || leases != 0)
                    throw fence.rejectLocked(FatalReason.CALLBACK);
                returned = true;
                compilerCode = code;
                if (code != 0 && code != 1) throw fence.rejectLocked(FatalReason.COMPILER);
            }
        }

        void beginDisposing() {
            synchronized (fence.lock) {
                if (!activeLocked() || (phase != Phase.EXECUTING && phase != Phase.NEW)
                        || Thread.currentThread() != invokingThread || leases != 0)
                    fence.poisonLocked(FatalReason.CALLBACK);
                // Always enter the caller-owned close path, even after execution/callback poison.
                phase = Phase.DISPOSING;
            }
        }

        Sealed seal(Execution execution) {
            synchronized (fence.lock) {
                fence.healthyLocked();
                if (!activeLocked() || phase != Phase.DISPOSING || leases != 0)
                    throw fence.rejectLocked(FatalReason.CALLBACK);
                if (!returned || (compilerCode != 0 && compilerCode != 1)
                        || execution.compilerCode() != compilerCode || !execution.disposalCompleted()
                        || rootDisposals != 1 || !execution.beforeReset().complete() || !execution.afterReset().complete())
                    throw fence.rejectLocked(FatalReason.RESET);
                Sealed result = new Sealed(diagnostics, rootDisposals);
                diagnostics.clear();
                diagnostics = null;
                phase = Phase.SEALED;
                fence.active = null;
                return result;
            }
        }

        void invalidate() {
            synchronized (fence.lock) {
                if (phase != Phase.SEALED) {
                    phase = Phase.INVALID;
                    if (diagnostics != null) diagnostics.clear();
                }
                if (fence.active == this) fence.active = null;
                fence.poisonLocked(FatalReason.INTERNAL);
            }
        }

        Phase phase() { synchronized (fence.lock) { return phase; } }
        int rootDisposals() { synchronized (fence.lock) { return rootDisposals; } }
        int sequence() { return sequence; }
    }

    static final class Lease implements AutoCloseable {
        private final JobState state;
        private final Object token;
        private final Callback kind;
        private final Thread thread;
        private boolean closed;
        private Lease(JobState state, Object token, Callback kind, Thread thread) {
            this.state = state;
            this.token = token;
            this.kind = kind;
            this.thread = thread;
        }
        @Override public void close() {
            synchronized (state.fence.lock) {
                if (closed || state.leases != 1 || state.leaseThread != Thread.currentThread() || thread != Thread.currentThread())
                    throw state.fence.rejectLocked(FatalReason.CALLBACK);
                closed = true;
                state.leases = 0;
                state.leaseThread = null;
            }
        }
    }

    static final class PoisonInput extends InputStream {
        private final GenerationFence fence;
        PoisonInput(GenerationFence fence) { this.fence = fence; }
        private IOException attempted(long bytes) { fence.rawAttempt(bytes); return new IOException("private_input_only"); }
        @Override public int read() throws IOException { throw attempted(1); }
        @Override public int read(byte[] bytes) throws IOException { throw attempted(bytes == null ? 0 : bytes.length); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { throw attempted(Math.max(0, length)); }
        @Override public byte[] readAllBytes() throws IOException { throw attempted(0); }
        @Override public byte[] readNBytes(int length) throws IOException { throw attempted(Math.max(0, length)); }
        @Override public int readNBytes(byte[] bytes, int offset, int length) throws IOException { throw attempted(Math.max(0, length)); }
        @Override public long transferTo(OutputStream target) throws IOException { throw attempted(0); }
        @Override public long skip(long amount) throws IOException { throw attempted(Math.max(0, amount)); }
        @Override public int available() throws IOException { throw attempted(0); }
        @Override public void close() throws IOException { throw attempted(0); }
        @Override public synchronized void mark(int limit) { fence.rawAttempt(0); }
        @Override public synchronized void reset() throws IOException { throw attempted(0); }
    }

    static final class PoisonOutput extends OutputStream {
        private final GenerationFence fence;
        PoisonOutput(GenerationFence fence) { this.fence = fence; }
        private IOException attempted(long bytes) { fence.rawAttempt(bytes); return new IOException("private_output_only"); }
        @Override public void write(int value) throws IOException { throw attempted(1); }
        @Override public void write(byte[] bytes) throws IOException { throw attempted(bytes == null ? 0 : bytes.length); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw attempted(Math.max(0, length)); }
        @Override public void flush() throws IOException { throw attempted(0); }
        @Override public void close() throws IOException { throw attempted(0); }
    }

    private static final class PoisonIo {
        private final GenerationFence fence;
        private final PoisonInput input;
        private final PrintStream output;
        private final PrintStream error;
        private PoisonIo(GenerationFence fence) {
            this.fence = fence;
            input = new PoisonInput(fence);
            output = new PrintStream(new PoisonOutput(fence), false, StandardCharsets.UTF_8);
            error = new PrintStream(new PoisonOutput(fence), false, StandardCharsets.UTF_8);
        }
        static PoisonIo install(GenerationFence fence) {
            PoisonIo io = new PoisonIo(fence);
            System.setIn(io.input);
            System.setOut(io.output);
            System.setErr(io.error);
            return io;
        }
        void requireUnchanged() {
            require(System.in == input && System.out == output && System.err == error, fence, FatalReason.RAW_IO);
        }
    }

    static final class FrameReader {
        private final InputStream input;
        private final ApplicationWire.Decoder decoder;
        private final GenerationFence fence;
        private final byte[] buffer = new byte[ApplicationWire.CHUNK_BYTES];
        private ApplicationWire.Message one;
        private boolean ended;
        FrameReader(InputStream input, ApplicationWire.Limits limits, GenerationFence fence) {
            this.input = input;
            this.fence = fence;
            decoder = new ApplicationWire.Decoder(limits.maxJobs() + 1, limits.inputLimit());
        }
        ApplicationWire.Message next() throws IOException {
            require(!ended && one == null, fence, FatalReason.PROTOCOL);
            while (one == null) {
                fence.requireHealthy();
                int count = input.read(buffer);
                if (count < 0) {
                    decoder.endOfInput();
                    ended = true;
                    return null;
                }
                require(count > 0, fence, FatalReason.PROTOCOL);
                decoder.accept(buffer, 0, count, value -> {
                    require(one == null, fence, FatalReason.PROTOCOL);
                    one = value;
                });
                // No queue and no request is admitted while the preceding one is executing.
                require(one == null || !decoder.hasPartialFrame(), fence, FatalReason.PROTOCOL);
            }
            ApplicationWire.Message value = one;
            one = null;
            return value;
        }
    }

    /**
     * One generation-scoped daemon exposes idle poison without waiting for another request/EOF.
     * It is not a watchdog, a fresh deadline or cleanup authority. It sees only immutable identity,
     * the fixed poison code and private writer: never callback payloads or compiler/session objects.
     */
    static Thread startPoisonReporter(FrameWriter writer, GenerationFence fence, String generation) {
        Thread reporter = new Thread(() -> {
            FatalReason reason;
            try { reason = fence.awaitPoison(); }
            catch (InterruptedException interrupted) {
                fence.poison(FatalReason.INTERNAL);
                reason = fence.reason();
                Thread.currentThread().interrupt();
            }
            try { writer.fatal(new ApplicationWire.Fatal(generation, fence.currentSequence(), reason)); }
            catch (Throwable ignored) { /* At most one private best effort; native v2 owns cleanup. */ }
        }, "compiler-application-poison");
        reporter.setDaemon(true);
        reporter.start();
        return reporter;
    }

    /**
     * Main and reporter share this emission guard. Its monitor is NOT the generation/callback
     * lock; no such lock is held over IPC. Known poison forbids a new HELLO/RESULT. Poison racing
     * an already-reserved/in-flight frame can leave that frame complete or partial, followed by
     * fixed FATAL; it never gives an irrevocable qualified guarantee for an earlier result.
     */
    static final class FrameWriter {
        private final PrintStream output;
        private final int limit;
        private final GenerationFence fence;
        private final Runnable checkIo;
        private long written;
        private boolean fatalAttempted;
        FrameWriter(PrintStream output, int limit, GenerationFence fence, Runnable checkIo) {
            this.output = output;
            this.limit = limit;
            this.fence = fence;
            this.checkIo = checkIo;
        }
        synchronized void write(ApplicationWire.Message message) throws IOException {
            checkIo.run();
            fence.requireHealthy();
            require(message instanceof ApplicationWire.Hello || message instanceof ApplicationWire.Result, fence, FatalReason.PROTOCOL);
            byte[] frame = ApplicationWire.frame(message);
            require(frame.length <= limit - written - ApplicationWire.MAX_FATAL_BYTES - 4L, fence, FatalReason.LIMIT);
            for (int offset = 0; offset < frame.length; offset += ApplicationWire.CHUNK_BYTES) {
                checkIo.run();
                fence.requireHealthy();
                int count = Math.min(ApplicationWire.CHUNK_BYTES, frame.length - offset);
                output.write(frame, offset, count);
                written += count;
                if (output.checkError()) throw new IOException("private_output_failed");
            }
            output.flush();
            if (output.checkError()) throw new IOException("private_output_failed");
        }
        synchronized void fatal(ApplicationWire.Fatal fatal) throws IOException {
            if (fatalAttempted) return;
            fatalAttempted = true;
            byte[] frame = ApplicationWire.frame(fatal);
            if (frame.length > limit - written) return;
            output.write(frame);
            written += frame.length;
            output.flush();
            if (output.checkError()) throw new IOException("private_output_failed");
        }
    }

    /** Only immutable installation/root/source metadata survives a job; never compiler state. */
    static final class GenerationFiles {
        final ApplicationWire.Launch launch;
        final CompilerJobWire.Generation generation;
        final Path root;
        private final ApplicationWire.Root compilerRoot;
        private final ApplicationWire.Root jobsRoot;
        private final ApplicationWire.Root tempRoot;
        private final FileReceipt bridge;
        private final FileReceipt descriptor;
        private final FileReceipt javaExecutable;
        private final List<FileReceipt> compilerArtifacts;
        private final ArrayList<JobRegistration> jobs = new ArrayList<>();
        private final Set<String> jobKeys = new HashSet<>();

        GenerationFiles(ApplicationWire.Launch launch, CompilerJobWire.Generation generation, byte[] encodedLaunch) throws IOException {
            this.launch = launch;
            this.generation = generation;
            root = checkRoot(launch.generationRoot());
            compilerRoot = observeRoot(root.resolve("compiler"));
            jobsRoot = observeRoot(root.resolve("jobs"));
            tempRoot = observeRoot(root.resolve("tmp"));
            bridge = FileReceipt.fromArtifact(root.resolve("bridge.jar"), generation.bridge());
            descriptor = FileReceipt.capture(root.resolve("launch.bin"), encodedLaunch.length, ApplicationWire.sha256(encodedLaunch), null);
            Path javaPath = canonicalPath(generation.jdk().executable());
            BasicFileAttributes javaAttributes = regular(javaPath);
            javaExecutable = FileReceipt.capture(javaPath, javaAttributes.size(), generation.jdk().executableDigest(), null);
            ArrayList<FileReceipt> artifacts = new ArrayList<>();
            HashSet<String> distinctFiles = new HashSet<>();
            distinctFiles.add(bridge.fileKey());
            distinctFiles.add(descriptor.fileKey());
            for (int i = 0; i < generation.compilerArtifacts().size(); i++) {
                FileReceipt file = FileReceipt.fromArtifact(root.resolve("compiler").resolve(i + ".jar"), generation.compilerArtifacts().get(i));
                fsRequire(distinctFiles.add(file.fileKey()));
                artifacts.add(file);
            }
            compilerArtifacts = List.copyOf(artifacts);
            HashSet<String> distinctRoots = new HashSet<>();
            for (ApplicationWire.Root value : List.of(launch.generationRoot(), compilerRoot, jobsRoot, tempRoot, generation.jdk().home()))
                fsRequire(distinctRoots.add(value.fileKey()));
        }

        Path compilerPath(int index) { return compilerArtifacts.get(index).path(); }
        Path bridgePath() { return bridge.path(); }

        void verifyInstallation() throws IOException {
            checkRoot(launch.generationRoot());
            checkRoot(compilerRoot);
            checkRoot(jobsRoot);
            checkRoot(tempRoot);
            checkRoot(generation.jdk().home());
            expectNames(root, Set.of("bridge.jar", "launch.bin", "compiler", "jobs", "tmp"));
            Set<String> compilerNames = new HashSet<>();
            for (int i = 0; i < compilerArtifacts.size(); i++) compilerNames.add(i + ".jar");
            expectNames(Path.of(compilerRoot.path()), compilerNames);
            bridge.verify();
            descriptor.verify();
            javaExecutable.verify();
            for (FileReceipt artifact : compilerArtifacts) artifact.verify();
            Set<String> expectedJobs = new HashSet<>();
            for (int i = 1; i <= jobs.size(); i++) expectedJobs.add(Integer.toString(i));
            expectNames(Path.of(jobsRoot.path()), expectedJobs);
            long outputBytes = 0;
            int outputFiles = 0;
            Set<String> outputKeys = new HashSet<>();
            outputKeys.add(bridge.fileKey());
            outputKeys.add(descriptor.fileKey());
            outputKeys.add(javaExecutable.fileKey());
            outputKeys.add(launch.generationRoot().fileKey());
            outputKeys.add(compilerRoot.fileKey());
            outputKeys.add(jobsRoot.fileKey());
            for (FileReceipt artifact : compilerArtifacts) outputKeys.add(artifact.fileKey());
            for (JobRegistration job : jobs) {
                outputKeys.add(job.jobRoot().fileKey());
                outputKeys.add(job.sourceRoot().fileKey());
                outputKeys.add(job.source().fileKey());
            }
            for (JobRegistration job : jobs) {
                job.verifySource();
                TreeSize size = scanTree(checkRoot(job.outputRoot()), MAX_JOB_OUTPUT_FILES, MAX_JOB_OUTPUT_BYTES, outputKeys);
                outputBytes += size.bytes();
                outputFiles += size.files();
                fsRequire(outputBytes <= MAX_GENERATION_OUTPUT_BYTES && outputFiles <= MAX_GENERATION_OUTPUT_FILES);
            }
            scanTree(Path.of(tempRoot.path()), MAX_TEMP_FILES, MAX_TEMP_BYTES, new HashSet<>(outputKeys));
        }

        JobFiles admit(ApplicationWire.Job job, CompilerJobWire.Request request) throws IOException {
            fsRequire(job.sequence() == jobs.size() + 1 && job.sequence() <= launch.limits().maxJobs());
            Path expectedJob = root.resolve("jobs").resolve(Integer.toString(job.sequence()));
            fsRequire(checkRoot(request.jobRoot()).equals(expectedJob));
            fsRequire(checkRoot(request.sourceRoot()).equals(expectedJob.resolve("sources")));
            fsRequire(checkRoot(request.outputRoot()).equals(expectedJob.resolve("classes")));
            checkRoot(request.snapshotRoot());
            Set<String> currentPaths = new HashSet<>();
            Set<String> currentKeys = new HashSet<>();
            for (ApplicationWire.Root value : List.of(request.jobRoot(), request.sourceRoot(), request.outputRoot(), request.snapshotRoot())) {
                fsRequire(currentPaths.add(value.path()) && currentKeys.add(value.fileKey()));
            }
            for (ApplicationWire.Root value : List.of(request.jobRoot(), request.sourceRoot(), request.outputRoot()))
                fsRequire(jobKeys.add(value.fileKey()));
            expectNames(expectedJob, Set.of("sources", "classes"));
            expectNames(Path.of(request.sourceRoot().path()), Set.of("Fixture.kt"));
            expectNames(Path.of(request.outputRoot().path()), Set.of());
            expectNames(Path.of(request.snapshotRoot().path()), Set.of("Fixture.kt"));
            fsRequire(request.sources().size() == 1);
            CompilerJobWire.Source declared = request.sources().get(0);
            fsRequire(declared.logicalPath().equals("Fixture.kt") && declared.size() > 0
                    && declared.size() <= CompilerJobWire.MAX_SOURCE_BYTES);
            FileReceipt source = FileReceipt.capture(Path.of(request.sourceRoot().path()).resolve("Fixture.kt"),
                    declared.size(), declared.digest(), declared.fileKey());
            FileReceipt snapshot = FileReceipt.capture(Path.of(request.snapshotRoot().path()).resolve("Fixture.kt"),
                    declared.size(), declared.digest(), null);
            fsRequire(!source.fileKey().equals(snapshot.fileKey()) && jobKeys.add(source.fileKey()));
            strictSource(source.path());
            strictSource(snapshot.path());
            JobRegistration registration = new JobRegistration(request.jobRoot(), request.sourceRoot(), request.outputRoot(), source);
            jobs.add(registration);
            verifyInstallation();
            return new JobFiles(this, registration, request.snapshotRoot(), snapshot, source);
        }
    }

    private record JobRegistration(ApplicationWire.Root jobRoot, ApplicationWire.Root sourceRoot,
                                   ApplicationWire.Root outputRoot, FileReceipt source) {
        void verifySource() throws IOException {
            expectNames(checkRoot(jobRoot), Set.of("sources", "classes"));
            expectNames(checkRoot(sourceRoot), Set.of("Fixture.kt"));
            checkRoot(outputRoot);
            source.verify();
            strictSource(source.path());
        }
    }

    static final class JobFiles {
        private final GenerationFiles generation;
        private final JobRegistration registration;
        private final ApplicationWire.Root snapshotRoot;
        private final FileReceipt snapshot;
        final FileReceipt source;
        private JobFiles(GenerationFiles generation, JobRegistration registration, ApplicationWire.Root snapshotRoot,
                         FileReceipt snapshot, FileReceipt source) {
            this.generation = generation;
            this.registration = registration;
            this.snapshotRoot = snapshotRoot;
            this.snapshot = snapshot;
            this.source = source;
        }
        void verifyAfter() throws IOException {
            registration.verifySource();
            expectNames(checkRoot(snapshotRoot), Set.of("Fixture.kt"));
            snapshot.verify();
            strictSource(snapshot.path());
            Set<String> currentSourceKeys = new HashSet<>();
            currentSourceKeys.add(snapshot.fileKey());
            currentSourceKeys.add(source.fileKey());
            scanTree(checkRoot(registration.outputRoot()), MAX_JOB_OUTPUT_FILES, MAX_JOB_OUTPUT_BYTES, currentSourceKeys);
            generation.verifyInstallation();
        }
    }

    record FileReceipt(Path path, String fileKey, long size, String digest, long modifiedMillis) {
        static FileReceipt fromArtifact(Path path, CompilerJobWire.Artifact artifact) throws IOException {
            return capture(path, artifact.size(), artifact.digest(), artifact.fileKey());
        }
        static FileReceipt capture(Path path, long size, String digest, String expectedKey) throws IOException {
            path = canonicalPath(path.toString());
            BasicFileAttributes attributes = regular(path);
            String fileKey = key(attributes);
            fsRequire(size >= 0 && attributes.size() == size && (expectedKey == null || expectedKey.equals(fileKey)));
            FileReceipt receipt = new FileReceipt(path, fileKey, size, digest, attributes.lastModifiedTime().toMillis());
            receipt.verify();
            return receipt;
        }
        void verify() throws IOException {
            BasicFileAttributes before = regular(path);
            fsRequire(fileKey.equals(key(before)) && size == before.size() && modifiedMillis == before.lastModifiedTime().toMillis());
            fsRequire(digest.equals(hashFile(path, size)));
            BasicFileAttributes after = regular(path);
            fsRequire(fileKey.equals(key(after)) && size == after.size() && modifiedMillis == after.lastModifiedTime().toMillis());
        }
    }

    record TreeSize(int files, long bytes) {}
    private static final class TreeCounter {
        int files;
        int entries;
        long bytes;
    }

    static TreeSize scanTree(Path root, int fileLimit, long byteLimit, Set<String> fileKeys) throws IOException {
        TreeCounter counter = new TreeCounter();
        scanDirectory(root, 0, fileLimit, byteLimit, counter, fileKeys);
        return new TreeSize(counter.files, counter.bytes);
    }

    private static void scanDirectory(Path directory, int depth, int fileLimit, long byteLimit,
                                      TreeCounter counter, Set<String> fileKeys) throws IOException {
        fsRequire(depth <= MAX_TREE_DEPTH);
        BasicFileAttributes initial = directory(directory);
        fsRequire(fileKeys.add(key(initial)));
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                fsRequire(++counter.entries <= fileLimit * 2 + MAX_TREE_DEPTH);
                BasicFileAttributes value = attributes(path);
                if (value.isDirectory()) {
                    scanDirectory(path, depth + 1, fileLimit, byteLimit, counter, fileKeys);
                } else {
                    fsRequire(value.isRegularFile() && !value.isSymbolicLink() && fileKeys.add(key(value)));
                    fsRequire(++counter.files <= fileLimit && value.size() >= 0 && value.size() <= byteLimit - counter.bytes);
                    counter.bytes += value.size();
                }
            }
        }
        BasicFileAttributes after = directory(directory);
        fsRequire(key(initial).equals(key(after)) && initial.lastModifiedTime().equals(after.lastModifiedTime()));
    }

    static Path canonicalPath(String value) throws IOException {
        Path path = Path.of(value);
        fsRequire(path.isAbsolute() && path.equals(path.normalize()) && path.toString().equals(value));
        Path cursor = path.getRoot();
        fsRequire(cursor != null);
        BasicFileAttributes top = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        fsRequire(top.isDirectory() && !top.isSymbolicLink());
        int names = path.getNameCount();
        for (int i = 0; i < names - 1; i++) {
            cursor = cursor.resolve(path.getName(i));
            BasicFileAttributes parent = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            fsRequire(parent.isDirectory() && !parent.isSymbolicLink());
        }
        return path;
    }

    static Path checkRoot(ApplicationWire.Root root) throws IOException {
        Path path = canonicalPath(root.path());
        fsRequire(key(directory(path)).equals(root.fileKey()));
        return path;
    }

    static ApplicationWire.Root observeRoot(Path path) throws IOException {
        path = canonicalPath(path.toString());
        return new ApplicationWire.Root(path.toString(), key(directory(path)));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        canonicalPath(path.toString());
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        fsRequire(!attributes.isSymbolicLink() && attributes.fileKey() != null);
        return attributes;
    }
    private static BasicFileAttributes directory(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        fsRequire(attributes.isDirectory());
        return attributes;
    }
    private static BasicFileAttributes regular(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        fsRequire(attributes.isRegularFile());
        return attributes;
    }
    private static String key(BasicFileAttributes attributes) {
        fsRequire(attributes.fileKey() != null);
        return attributes.fileKey().toString();
    }

    static void expectNames(Path directory, Set<String> expected) throws IOException {
        BasicFileAttributes before = directory(directory);
        Set<String> observed = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                fsRequire(observed.size() < expected.size() && expected.contains(name) && observed.add(name));
                attributes(entry); // Reject links/special aliases before another helper uses the member.
            }
        }
        fsRequire(observed.equals(expected));
        BasicFileAttributes after = directory(directory);
        fsRequire(key(before).equals(key(after)) && before.lastModifiedTime().equals(after.lastModifiedTime()));
    }

    static byte[] readBoundedFile(Path path, int maximum) throws IOException {
        BasicFileAttributes before = regular(path);
        fsRequire(before.size() >= 0 && before.size() <= maximum);
        byte[] value;
        try (InputStream stream = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            value = stream.readNBytes(maximum + 1);
        }
        fsRequire(value.length == before.size() && value.length <= maximum);
        BasicFileAttributes after = regular(path);
        fsRequire(key(before).equals(key(after)) && before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime()));
        return value;
    }

    static void strictSource(Path path) throws IOException {
        byte[] bytes = readBoundedFile(path, CompilerJobWire.MAX_SOURCE_BYTES);
        fsRequire(bytes.length > 0);
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            fsRequire(source.indexOf('\0') < 0 && !source.startsWith("\ufeff"));
        } catch (CharacterCodingException invalid) {
            throw new WorkerFailure(FatalReason.OWNERSHIP);
        }
    }

    private static String hashFile(Path path, long size) throws IOException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new WorkerFailure(FatalReason.INTERNAL); }
        long remaining = size;
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                fsRequire(count > 0);
                sha.update(buffer, 0, count);
                remaining -= count;
            }
            fsRequire(input.read() == -1);
        }
        return "sha256:" + HexFormat.of().formatHex(sha.digest());
    }

    private static void verifyProcess(ApplicationWire.Launch launch, CompilerJobWire.Generation generation,
                                      PoisonIo io, GenerationFence fence) throws Exception {
        require(Runtime.version().feature() == 21 && generation.jdk().major() == 21, fence, FatalReason.IDENTITY);
        require(canonicalPath(System.getProperty("java.home")).equals(checkRoot(generation.jdk().home())), fence, FatalReason.IDENTITY);
        require(System.getProperty("user.dir").equals(launch.directory()), fence, FatalReason.IDENTITY);
        require(System.getProperty("java.class.path").equals(Path.of(launch.generationRoot().path()).resolve("bridge.jar").toString()), fence, FatalReason.IDENTITY);
        require("true".equals(System.getProperty("java.awt.headless"))
                && "false".equals(System.getProperty("kotlin.environment.keepalive"))
                && "false".equals(System.getProperty("kotlin.colors.enabled"))
                && "UTF-8".equals(System.getProperty("file.encoding"))
                && "en".equals(System.getProperty("user.language")) && "US".equals(System.getProperty("user.country"))
                && System.getProperty("java.io.tmpdir").equals(Path.of(launch.generationRoot().path()).resolve("tmp").toString()), fence, FatalReason.IDENTITY);
        require(launch.command().equals(CompilerJobWire.command(launch.generationRoot(), generation))
                && launch.environment().equals(CompilerJobWire.environment(launch.generationRoot(), generation))
                && System.getenv().equals(launch.environment()), fence, FatalReason.IDENTITY);
        String command = ProcessHandle.current().info().command().orElseThrow(() -> new WorkerFailure(FatalReason.IDENTITY));
        require(canonicalPath(command).equals(canonicalPath(generation.jdk().executable())), fence, FatalReason.IDENTITY);
        Path bridge = Path.of(launch.generationRoot().path()).resolve("bridge.jar");
        for (Class<?> type : List.of(CompilerApplicationMain.class, PinnedKotlinCompilerAdapter.class,
                ApplicationWire.class, CompilerJobWire.class)) {
            require(type.getClassLoader() == CompilerApplicationMain.class.getClassLoader()
                    && type.getProtectionDomain().getCodeSource() != null
                    && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(bridge), fence, FatalReason.IDENTITY);
        }
        io.requireUnchanged();
        fence.requireHealthy();
    }

    private static int utf8Length(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException invalid) { throw new WorkerFailure(FatalReason.CALLBACK); }
    }

    static void require(boolean accepted, GenerationFence fence, FatalReason reason) {
        if (!accepted) {
            fence.poison(reason);
            throw new WorkerFailure(reason);
        }
    }
    private static void fsRequire(boolean accepted) { if (!accepted) throw new WorkerFailure(FatalReason.OWNERSHIP); }

    static final class WorkerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final FatalReason reason;
        WorkerFailure(FatalReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
