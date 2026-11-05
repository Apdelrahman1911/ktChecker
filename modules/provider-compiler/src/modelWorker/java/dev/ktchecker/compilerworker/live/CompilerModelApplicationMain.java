package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerModelWire;

import java.io.ByteArrayInputStream;
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
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Set;
import java.util.UUID;

import static dev.ktchecker.workerprotocol.ApplicationWire.FatalReason;

/**
 * Fixed, dormant JVM-model bridge; the M0 source and protocol remain separate. Its only external entry always constructs the real pinned K2 adapter.
 * The application protocol is sequential and persistent, not a launch-time job list. A compiler
 * code of one is a diagnostic result, never a process exit or a transport-cleanup observation.
 * No reset bit here proves isolation of arbitrary JVM statics, threads or native state.
 */
public final class CompilerModelApplicationMain {
    static final int MAX_JOB_OUTPUT_FILES = CompilerModelWire.MAX_JOB_OUTPUT_FILES;
    static final long MAX_JOB_OUTPUT_BYTES = CompilerModelWire.MAX_JOB_OUTPUT_BYTES;
    static final int MAX_GENERATION_OUTPUT_FILES = CompilerModelWire.MAX_GENERATION_OUTPUT_FILES;
    static final long MAX_GENERATION_OUTPUT_BYTES = CompilerModelWire.MAX_GENERATION_OUTPUT_BYTES;
    static final int MAX_TEMP_FILES = CompilerModelWire.MAX_TEMP_FILES;
    static final long MAX_TEMP_BYTES = CompilerModelWire.MAX_TEMP_BYTES;
    static final int MAX_TREE_DEPTH = 16;
    static final int MAX_ARCHIVE_ENTRIES = 20_000;
    static final int MAX_ARCHIVE_NAME_BYTES = 4_096;
    static final int MAX_ARCHIVE_DEPTH = 64;
    static final long MAX_ARCHIVE_EXPANDED_BYTES = 268_435_456L;
    static final int MAX_MANIFEST_BYTES = 65_536;

    private CompilerModelApplicationMain() {}

    public static void main(String[] args) {
        // Keep these private. Neither the compiler nor a collector is handed these references.
        InputStream input = System.in;
        PrintStream output = System.out;
        GenerationFence fence = new GenerationFence();
        PoisonIo io = PoisonIo.install(fence);
        ApplicationWire.Launch launch = null;
        PinnedKotlinModelCompilerAdapter adapter = null;
        FrameWriter writer = null;
        int sequence = 0;
        try {
            require(args.length == 1, fence, FatalReason.PROTOCOL);
            Path descriptor = canonicalPath(args[0]);
            byte[] encodedLaunch = readBoundedFile(descriptor, ApplicationWire.MAX_LAUNCH_BYTES);
            launch = ApplicationWire.decodeLaunch(encodedLaunch);
            require(Arrays.equals(encodedLaunch, ApplicationWire.encodeLaunch(launch)), fence, FatalReason.PROTOCOL);
            CompilerModelWire.Generation generation = CompilerModelWire.decodeGeneration(launch.payload());
            CompilerModelWire.matchGeneration(launch, generation);
            require(descriptor.equals(Path.of(launch.generationRoot().path()).resolve("launch.bin")), fence, FatalReason.IDENTITY);
            GenerationFiles files = new GenerationFiles(launch, generation, encodedLaunch);
            files.verifyInstallation();
            verifyProcess(launch, generation, io, fence);
            writer = new FrameWriter(output, launch.limits().outputLimit(), fence, io::requireUnchanged);
            startPoisonReporter(writer, fence, launch.identity().generation());
            adapter = new PinnedKotlinModelCompilerAdapter(launch, generation, files, fence);
            files.verifyInstallation();
            io.requireUnchanged();
            fence.requireHealthy();
            String loaderToken = UUID.randomUUID().toString().replace("-", "");
            ApplicationWire.Hello hello = new ApplicationWire.Hello(
                    launch.identity(), ApplicationWire.launchDigest(launch), loaderToken,
                    adapter.compilerVersion(), adapter.originsVerified(), adapter.initialReset().complete());
            CompilerModelWire.matchHello(launch, generation, hello);
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
                CompilerModelWire.Request request = CompilerModelWire.decodeRequest(job.payload());
                CompilerModelWire.matchRequest(launch, generation, job, request);
                sequence = job.sequence();
                verifyProcess(launch, generation, io, fence);
                JobFiles jobFiles = files.admit(job, request);
                List<String> argv = CompilerModelWire.arguments(launch, generation, request);
                JobState state = fence.newJob(sequence, jobFiles.diagnosticScope());
                try {
                    Execution execution = executeOwned(adapter, state, argv);
                    // No lock is held during filesystem scans, compiler execution, disposal or IPC.
                    jobFiles.verifyAfter();
                    verifyProcess(launch, generation, io, fence);
                    Sealed sealed = state.seal(execution);
                    CompilerModelWire.Observation observation = new CompilerModelWire.Observation(
                            adapter.compilerVersion(), CompilerModelWire.argvDigest(argv),
                            CompilerModelWire.workDigest(request),
                            request.jobRoot(), request.sourceRoot(), request.outputRoot(),
                            true, execution.compilerCode(), sealed.disposalCount(), true, false,
                            execution.beforeReset(), execution.afterReset(), true, true, true,
                            CompilerModelWire.FailureCode.NONE, sealed.diagnostics());
                    CompilerModelWire.requireSealed(observation);
                    ApplicationWire.Result result = new ApplicationWire.Result(
                            job.generation(), ApplicationWire.helloDigest(hello), job.sequence(), job.jobId(),
                            ApplicationWire.requestDigest(job), job.originalKey(), job.effectiveKey(), loaderToken,
                            ApplicationWire.ResultState.APPLICATION_SEALED, CompilerModelWire.encodeObservation(observation));
                    CompilerModelWire.matchObservation(launch, generation, job, request, result, observation);
                    ApplicationWire.matchResult(launch, hello, job, result);
                    io.requireUnchanged();
                    fence.requireHealthy();
                    writer.write(result);
                    // This is worker observation only. Host Accepted+finishAccepted is separately
                    // mandatory before its nonserialized capability can submit a dependent job.
                    if (request.producedExecution() != null && execution.compilerCode() == 0 &&
                        request.producedExecution().producers().isEmpty()) files.rememberSuccessful(job, request, result, observation);
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
        CompilerModelWire.Reset initialReset();
        @Override void close() throws Exception;
    }

    interface Session extends AutoCloseable {
        int execute(List<String> argv) throws Exception;
        CompilerModelWire.Reset beforeReset();
        CompilerModelWire.Reset afterReset();
        boolean disposalCompleted();
        @Override void close() throws Exception;
    }

    record Execution(int compilerCode, CompilerModelWire.Reset beforeReset, CompilerModelWire.Reset afterReset,
                     boolean disposalCompleted) {}
    record Sealed(List<CompilerModelWire.Diagnostic> diagnostics, int disposalCount) {
        Sealed { diagnostics = List.copyOf(diagnostics); }
    }

    /** Caller owns close in the outer finally, including failures before upstream pipeline setup. */
    static Execution executeOwned(SessionFactory factory, JobState state, List<String> argv) {
        Session session = null;
        CompilerModelWire.Reset before = null;
        CompilerModelWire.Reset after = null;
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

        JobState newJob(int sequence, DiagnosticScope scope) {
            synchronized (lock) {
                healthyLocked();
                if (active != null || sequence != lastSequence + 1) throw rejectLocked(FatalReason.CALLBACK);
                JobState state = new JobState(this, generationToken, sequence, scope, Thread.currentThread());
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
        private DiagnosticScope diagnosticScope;
        private final Thread invokingThread;
        private Phase phase = Phase.NEW;
        private int leases;
        private Thread leaseThread;
        private boolean returned;
        private int compilerCode = -1;
        private int rootDisposals;
        private int diagnosticBytes = 4; // Canonical diagnostic count prefix is part of the cap.
        private boolean errors;
        private ArrayList<CompilerModelWire.Diagnostic> diagnostics = new ArrayList<>();

        private JobState(GenerationFence fence, Object generationToken, int sequence, DiagnosticScope scope, Thread invokingThread) {
            this.fence = fence;
            this.generationToken = generationToken;
            this.sequence = sequence;
            this.diagnosticScope = java.util.Objects.requireNonNull(scope);
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

        void report(Lease lease, CompilerModelWire.Diagnostic diagnostic) {
            try {
                CompilerModelWire.validateDiagnostic(diagnostic);
                if (diagnostic.severity().equals("EXCEPTION") || diagnostic.severity().equals("OUTPUT"))
                    throw new WorkerFailure(FatalReason.COMPILER);
                diagnosticScope.validate(diagnostic);
                if (diagnostic.message().equals("Compilation was canceled")
                        || diagnostic.message().equals("Compilation was cancelled"))
                    throw new WorkerFailure(FatalReason.COMPILER);
                int bytes = 1 + 4 + utf8Length(diagnostic.message()) + 4 + utf8Length(diagnostic.path())
                        + 4 + 4 + 4 + utf8Length(diagnostic.diagnosticId());
                synchronized (fence.lock) {
                    checkLeaseLocked(lease, Callback.COLLECTOR);
                    if (diagnostics.size() >= CompilerModelWire.MAX_DIAGNOSTICS
                            || bytes > CompilerModelWire.MAX_DIAGNOSTIC_BYTES - diagnosticBytes)
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
                if ((compilerCode == 0 && errors) || (compilerCode == 1 && !errors))
                    throw fence.rejectLocked(FatalReason.COMPILER);
                Sealed result = new Sealed(diagnostics, rootDisposals);
                diagnostics.clear();
                diagnostics = null;
                diagnosticScope = null;
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
                    diagnosticScope = null;
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
        }, "compiler-model-application-poison");
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

    /** Detached text is used only to derive bounded UTF16 line widths, never compiler callback objects. */
    record DiagnosticSource(String logicalPath, String physicalPath, String text) {}

    static final class DiagnosticScope {
        private final Map<String, int[]> widths;
        DiagnosticScope(List<DiagnosticSource> sources) {
            fsRequire(sources != null && !sources.isEmpty() && sources.size() <= CompilerModelWire.MAX_COMPILATION_FILES);
            Map<String, int[]> collected = new LinkedHashMap<>();
            for (DiagnosticSource source : List.copyOf(sources)) {
                fsRequire(source != null && source.logicalPath() != null && source.physicalPath() != null
                        && source.text() != null && source.text().length() <= CompilerModelWire.MAX_SOURCE_BYTES);
                requireSupportedLineEndings(source.text());
                int[] lines = lineWidths(source.text());
                fsRequire(collected.putIfAbsent(source.logicalPath(), lines) == null);
                fsRequire(collected.putIfAbsent(source.physicalPath(), lines) == null);
            }
            widths = Map.copyOf(collected);
        }
        void validate(CompilerModelWire.Diagnostic diagnostic) {
            if (diagnostic.path().isEmpty()) {
                if (diagnostic.line() != -1 || diagnostic.column() != -1) throw new WorkerFailure(FatalReason.CALLBACK);
                return; // Global ERROR and unknown IDs are retained, not made clean.
            }
            int[] lines = widths.get(diagnostic.path());
            if (lines == null || diagnostic.line() < 1 || diagnostic.line() > lines.length
                    || diagnostic.column() < 1 || diagnostic.column() > lines[diagnostic.line() - 1] + 1)
                throw new WorkerFailure(FatalReason.CALLBACK);
        }
        private static int[] lineWidths(String source) {
            int[] values = new int[source.length() + 1];
            int count = 0, start = 0;
            for (int i = 0; i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    values[count++] = i - start - (i > start && source.charAt(i - 1) == '\r' ? 1 : 0);
                    start = i + 1;
                }
            }
            values[count++] = source.length() - start;
            return Arrays.copyOf(values, count);
        }
    }

    /** Only bounded immutable filesystem metadata survives a job; never compiler invocations or collectors. */
    static final class GenerationFiles {
        final ApplicationWire.Launch launch;
        final CompilerModelWire.Generation generation;
        final Path root;
        private final ApplicationWire.Root compilerRoot;
        private final ApplicationWire.Root jobsRoot;
        private final ApplicationWire.Root tempRoot;
        private final FileReceipt bridge;
        private final FileReceipt descriptor;
        private final FileReceipt javaExecutable;
        private final List<FileReceipt> compilerArtifacts;
        private final ArrayList<JobRegistration> jobs = new ArrayList<>();
        private final Map<Integer, ProducedCompletion> successfulProducers = new LinkedHashMap<>();
        private long admittedSnapshotBytes;
        private long admittedDependencyBytes;

        GenerationFiles(ApplicationWire.Launch launch, CompilerModelWire.Generation generation, byte[] encodedLaunch) throws IOException {
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
            for (int i = 0; i < generation.compilerArtifacts().size(); i++)
                artifacts.add(FileReceipt.fromArtifact(root.resolve("compiler").resolve(i + ".jar"), generation.compilerArtifacts().get(i)));
            compilerArtifacts = List.copyOf(artifacts);
            baseIdentities();
        }

        Path compilerPath(int index) { return compilerArtifacts.get(index).path(); }
        Path bridgePath() { return bridge.path(); }

        private Map<String, Path> baseIdentities() {
            Map<String, Path> keys = new LinkedHashMap<>();
            for (ApplicationWire.Root value : List.of(launch.generationRoot(), compilerRoot, jobsRoot, tempRoot, generation.jdk().home()))
                pinKey(keys, value.fileKey(), Path.of(value.path()));
            for (FileReceipt value : List.of(bridge, descriptor, javaExecutable)) pinKey(keys, value.fileKey(), value.path());
            for (FileReceipt value : compilerArtifacts) pinKey(keys, value.fileKey(), value.path());
            return keys;
        }

        void verifyInstallation() throws IOException {
            checkRoot(launch.generationRoot()); checkRoot(compilerRoot); checkRoot(jobsRoot);
            checkRoot(tempRoot); checkRoot(generation.jdk().home());
            expectNames(root, Set.of("bridge.jar", "launch.bin", "compiler", "jobs", "tmp"));
            Set<String> compilerNames = new HashSet<>();
            for (int i = 0; i < compilerArtifacts.size(); i++) compilerNames.add(i + ".jar");
            expectNames(Path.of(compilerRoot.path()), compilerNames);
            bridge.verify(); descriptor.verify(); javaExecutable.verify();
            for (FileReceipt artifact : compilerArtifacts) artifact.verify();
            Set<String> expectedJobs = new HashSet<>();
            for (int i = 1; i <= jobs.size(); i++) expectedJobs.add(Integer.toString(i));
            expectNames(Path.of(jobsRoot.path()), expectedJobs);
            Map<String, Path> allInputs = baseIdentities();
            long snapshotBytes = 0, dependencyBytes = 0;
            for (JobRegistration job : jobs) {
                job.verifyInputs(); job.addInputIdentities(allInputs);
                snapshotBytes += job.snapshotBytes; dependencyBytes += job.dependencyBytes;
            }
            fsRequire(snapshotBytes == admittedSnapshotBytes && dependencyBytes == admittedDependencyBytes
                    && snapshotBytes <= CompilerModelWire.MAX_GENERATION_SNAPSHOT_BYTES
                    && dependencyBytes <= CompilerModelWire.MAX_GENERATION_DEPENDENCY_BYTES);
            Set<String> outputKeys = new HashSet<>(allInputs.keySet());
            long outputBytes = 0;
            int outputFiles = 0;
            for (JobRegistration job : jobs) {
                TreeSize size = scanTree(checkRoot(job.outputRoot), MAX_JOB_OUTPUT_FILES, MAX_JOB_OUTPUT_BYTES, outputKeys);
                outputBytes += size.bytes(); outputFiles += size.files();
                fsRequire(outputBytes <= MAX_GENERATION_OUTPUT_BYTES && outputFiles <= MAX_GENERATION_OUTPUT_FILES);
            }
            for (ProducedCompletion producer : successfulProducers.values()) producer.output.verify();
            // tempRoot was reserved against output aliases, but the temp scan must add its own root.
            Set<String> tempKeys = new HashSet<>(outputKeys); tempKeys.remove(tempRoot.fileKey());
            scanTree(Path.of(tempRoot.path()), MAX_TEMP_FILES, MAX_TEMP_BYTES, tempKeys);
        }

        void rememberSuccessful(ApplicationWire.Job job, CompilerModelWire.Request request, ApplicationWire.Result result,
                                CompilerModelWire.Observation observation) throws IOException {
            CompilerModelWire.matchObservation(launch, generation, job, request, result, observation);
            fsRequire(request.producedExecution() != null && request.producedExecution().producers().isEmpty() &&
                observation.compilerCode() == 0 && job.sequence() == jobs.size() && !successfulProducers.containsKey(job.sequence()));
            ProducedTree output = ProducedTree.capture(request.outputRoot());
            successfulProducers.put(job.sequence(), new ProducedCompletion(request, result, output));
        }

        void verifyProducedDependencies(ApplicationWire.Job job, CompilerModelWire.Request request) throws IOException {
            if (request.producedExecution() == null) return;
            for (CompilerModelWire.ProducedDependency dependency : request.producedExecution().producers()) {
                ProducedCompletion completed = successfulProducers.get(dependency.producerSequence());
                fsRequire(completed != null);
                requireProducedLineage(job, request, dependency, completed.request, completed.result, completed.output.digest);
                completed.output.verify();
                String file = "artifact-" + dependency.classpathIndex() + ".jar";
                inspectProducedArchive(Path.of(request.classpathRoot().path()).resolve(file), completed.output.members);
                inspectProducedArchive(Path.of(request.originalClasspathRoot().path()).resolve(file), completed.output.members);
                completed.output.verify();
            }
        }

        JobFiles admit(ApplicationWire.Job job, CompilerModelWire.Request request) throws IOException {
            CompilerModelWire.matchRequest(launch, generation, job, request);
            fsRequire(job.sequence() == jobs.size() + 1 && job.sequence() <= launch.limits().maxJobs());
            long snapshotBytes = 0, dependencyBytes = 0;
            for (CompilerModelWire.Member member : request.files()) snapshotBytes += member.size();
            for (CompilerModelWire.Dependency dependency : request.dependencies()) dependencyBytes += dependency.size();
            fsRequire(snapshotBytes <= CompilerModelWire.MAX_GENERATION_SNAPSHOT_BYTES - admittedSnapshotBytes
                    && dependencyBytes <= CompilerModelWire.MAX_GENERATION_DEPENDENCY_BYTES - admittedDependencyBytes);
            Path expectedJob = root.resolve("jobs").resolve(Integer.toString(job.sequence()));
            fsRequire(checkRoot(request.jobRoot()).equals(expectedJob));
            fsRequire(checkRoot(request.sourceRoot()).equals(expectedJob.resolve("sources")));
            fsRequire(checkRoot(request.classpathRoot()).equals(expectedJob.resolve("classpath")));
            fsRequire(checkRoot(request.outputRoot()).equals(expectedJob.resolve("classes")));
            checkRoot(request.originalOutputRoot());
            expectNames(expectedJob, Set.of("sources", "classpath", "classes"));
            expectNames(Path.of(request.outputRoot().path()), Set.of());
            SnapshotReceipt original = SnapshotReceipt.capture(request.snapshotRoot(), request.files(), false);
            SnapshotReceipt effective = SnapshotReceipt.capture(request.sourceRoot(), request.files(), true);
            ClasspathReceipt originalClasspath = ClasspathReceipt.capture(request.originalClasspathRoot(), request.dependencies(), false);
            ClasspathReceipt effectiveClasspath = ClasspathReceipt.capture(request.classpathRoot(), request.dependencies(), true);
            verifyProducedDependencies(job, request);
            JobRegistration registration = new JobRegistration(request.jobRoot(), request.outputRoot(), request.originalOutputRoot(),
                    original, effective, originalClasspath, effectiveClasspath, request.compilationPaths(), snapshotBytes, dependencyBytes);
            Map<String, Path> keys = baseIdentities();
            for (JobRegistration previous : jobs) previous.addInputIdentities(keys);
            registration.addInputIdentities(keys); // Distinct copies; reusing the same original path is not dedup.
            jobs.add(registration);
            admittedSnapshotBytes += snapshotBytes; admittedDependencyBytes += dependencyBytes;
            verifyInstallation();
            return new JobFiles(this, registration);
        }
    }

    private record ProducedCompletion(CompilerModelWire.Request request, ApplicationWire.Result result, ProducedTree output) {}

    /** Pure corroboration of an ALREADY retained worker completion; this function cannot enroll one. */
    static void requireProducedLineage(ApplicationWire.Job job, CompilerModelWire.Request request,
                                      CompilerModelWire.ProducedDependency dependency, CompilerModelWire.Request before,
                                      ApplicationWire.Result result, String outputDigest) {
        CompilerModelWire.InputIdentity a = before.original(), b = request.original();
        fsRequire(request.producedExecution() != null && before.producedExecution() != null &&
            before.producedExecution().producers().isEmpty() &&
            dependency.generation().equals(job.generation()) && result.generation().equals(job.generation()) &&
            result.sequence() == dependency.producerSequence() && dependency.producerSequence() < job.sequence() &&
            result.state() == ApplicationWire.ResultState.APPLICATION_SEALED &&
            result.originalKey().equals(before.original().analysisKey()) && result.effectiveKey().equals(before.effective().analysisKey()) &&
            before.producedExecution().compilationId().equals(dependency.producerCompilationId()) &&
            before.producedExecution().planDigest().equals(request.producedExecution().planDigest()) &&
            before.modelBindingDigest().equals(dependency.producerBindingDigest()) &&
            result.requestDigest().equals(dependency.requestDigest()) &&
            ApplicationWire.sha256(ApplicationWire.encode(result)).equals(dependency.resultDigest()) &&
            outputDigest.equals(dependency.outputDigest()) && before.deadlineIdentity().equals(request.deadlineIdentity()) &&
            a.snapshotDigest().equals(b.snapshotDigest()) && a.memberDigest().equals(b.memberDigest()) &&
            a.modelDigest().equals(b.modelDigest()) && a.configurationDigest().equals(b.configurationDigest()) &&
            a.toolchainDigest().equals(b.toolchainDigest()));
        CompilerModelWire.Observation observation = CompilerModelWire.decodeObservation(result.payload());
        CompilerModelWire.requireSealed(observation);
        fsRequire(observation.compilerCode() == 0);
    }

    /** Complete sealed bytes + directory/file identities, not merely output existence or an ABI subset. */
    static final class ProducedTree {
        final List<CompilerModelWire.ProducedMember> members;
        final String digest;
        private final List<DirectoryReceipt> directories;
        private final List<FileReceipt> files;
        private ProducedTree(List<CompilerModelWire.ProducedMember> members, List<DirectoryReceipt> directories, List<FileReceipt> files) {
            this.members = List.copyOf(members); this.digest = CompilerModelWire.producedOutputDigest(this.members);
            this.directories = List.copyOf(directories); this.files = List.copyOf(files);
        }
        static ProducedTree capture(ApplicationWire.Root root) throws IOException {
            Path base = checkRoot(root);
            ArrayList<CompilerModelWire.ProducedMember> members = new ArrayList<>();
            ArrayList<DirectoryReceipt> directories = new ArrayList<>(); ArrayList<FileReceipt> files = new ArrayList<>();
            Map<String, Path> keys = new LinkedHashMap<>(); long total = 0;
            try (var walk = Files.walk(base, MAX_TREE_DEPTH + 1)) {
                var iterator = walk.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next(); BasicFileAttributes observed = attributes(path);
                    String relative = base.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                    fsRequire(relative.isEmpty() || relative.split("/").length <= MAX_TREE_DEPTH);
                    if (!relative.isEmpty()) fsRequire(members.size() < MAX_JOB_OUTPUT_FILES);
                    pinKey(keys, key(observed), path);
                    if (observed.isDirectory()) {
                        Set<String> names = new HashSet<>();
                        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                            for (Path child : children) {
                                fsRequire(names.size() < MAX_JOB_OUTPUT_FILES && names.add(child.getFileName().toString())); attributes(child);
                            }
                        }
                        directories.add(DirectoryReceipt.capture(new ApplicationWire.Root(path.toString(), key(observed)), names));
                        if (!relative.isEmpty()) members.add(new CompilerModelWire.ProducedMember(relative, true, 0, null));
                    } else {
                        fsRequire(observed.isRegularFile() && observed.size() <= MAX_JOB_OUTPUT_BYTES - total);
                        total += observed.size();
                        FileReceipt file = FileReceipt.capture(path, observed.size(), hashFile(path, observed.size()), key(observed));
                        files.add(file); members.add(new CompilerModelWire.ProducedMember(relative, false, file.size(), file.digest()));
                    }
                }
            }
            members.sort(java.util.Comparator.comparing(CompilerModelWire.ProducedMember::path));
            ProducedTree tree = new ProducedTree(members, directories, files); tree.verify(); return tree;
        }
        void verify() throws IOException {
            for (DirectoryReceipt directory : directories) directory.verify();
            for (FileReceipt file : files) file.verify();
            for (DirectoryReceipt directory : directories) directory.verify();
        }
    }

    /** No missing/extra/class-only filtering: compare every original directory and output byte. */
    static void inspectProducedArchive(Path jar, List<CompilerModelWire.ProducedMember> expected) throws IOException {
        inspectDependencyArchive(jar);
        ArrayList<CompilerModelWire.ProducedMember> actual = new ArrayList<>(); long total = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                fsRequire(actual.size() < MAX_JOB_OUTPUT_FILES && entry.getMethod() == ZipEntry.STORED &&
                    entry.getSize() >= 0 && entry.getSize() <= MAX_JOB_OUTPUT_BYTES - total);
                total += entry.getSize();
                String name = entry.isDirectory() ? entry.getName().substring(0, entry.getName().length() - 1) : entry.getName();
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                CRC32 crc = new CRC32(); long seen = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[8_192];
                    while (true) { int count = input.read(buffer); if (count < 0) break;
                        fsRequire(count > 0 && count <= entry.getSize() - seen); seen += count;
                        digest.update(buffer, 0, count); crc.update(buffer, 0, count);
                    }
                }
                fsRequire(seen == entry.getSize() && crc.getValue() == entry.getCrc());
                actual.add(new CompilerModelWire.ProducedMember(name, entry.isDirectory(), seen,
                    entry.isDirectory() ? null : "sha256:" + HexFormat.of().formatHex(digest.digest())));
            }
        }
        actual.sort(java.util.Comparator.comparing(CompilerModelWire.ProducedMember::path));
        fsRequire(actual.equals(expected) && CompilerModelWire.producedOutputDigest(actual).equals(CompilerModelWire.producedOutputDigest(expected)));
    }

    private static final class JobRegistration {
        final ApplicationWire.Root jobRoot, outputRoot, originalOutputRoot;
        final SnapshotReceipt original, effective;
        final ClasspathReceipt originalClasspath, effectiveClasspath;
        final List<String> compilationPaths;
        final long snapshotBytes, dependencyBytes;
        JobRegistration(ApplicationWire.Root jobRoot, ApplicationWire.Root outputRoot, ApplicationWire.Root originalOutputRoot,
                        SnapshotReceipt original, SnapshotReceipt effective, ClasspathReceipt originalClasspath,
                        ClasspathReceipt effectiveClasspath, List<String> compilationPaths, long snapshotBytes, long dependencyBytes) {
            this.jobRoot = jobRoot; this.outputRoot = outputRoot; this.originalOutputRoot = originalOutputRoot;
            this.original = original; this.effective = effective;
            this.originalClasspath = originalClasspath; this.effectiveClasspath = effectiveClasspath;
            this.compilationPaths = List.copyOf(compilationPaths);
            this.snapshotBytes = snapshotBytes; this.dependencyBytes = dependencyBytes;
        }
        void verifyInputs() throws IOException {
            expectNames(checkRoot(jobRoot), Set.of("sources", "classpath", "classes"));
            checkRoot(outputRoot); checkRoot(originalOutputRoot);
            original.verify(); effective.verify(); originalClasspath.verify(); effectiveClasspath.verify();
            for (String path : compilationPaths) {
                strictSource(original.file(path).path()); strictSource(effective.file(path).path());
            }
        }
        void addInputIdentities(Map<String, Path> keys) {
            pinKey(keys, jobRoot.fileKey(), Path.of(jobRoot.path()));
            pinKey(keys, originalOutputRoot.fileKey(), Path.of(originalOutputRoot.path()));
            original.addIdentities(keys); effective.addIdentities(keys);
            originalClasspath.addIdentities(keys); effectiveClasspath.addIdentities(keys);
        }
    }

    static final class JobFiles {
        private final GenerationFiles generation;
        private final JobRegistration registration;
        private JobFiles(GenerationFiles generation, JobRegistration registration) {
            this.generation = generation; this.registration = registration;
        }
        DiagnosticScope diagnosticScope() throws IOException {
            ArrayList<DiagnosticSource> sources = new ArrayList<>();
            for (String path : registration.compilationPaths) {
                FileReceipt file = registration.effective.file(path);
                file.verify();
                String text = readStrictSource(file.path());
                file.verify();
                sources.add(new DiagnosticSource(path, file.path().toString(), text));
            }
            return new DiagnosticScope(sources);
        }
        void verifyAfter() throws IOException {
            registration.verifyInputs();
            generation.verifyInstallation();
        }
    }

    /** Exact member tree including noncompiled files; directories cannot hide extra members or aliases. */
    static final class SnapshotReceipt {
        private final List<DirectoryReceipt> directories;
        private final Map<String, FileReceipt> files;
        private SnapshotReceipt(List<DirectoryReceipt> directories, Map<String, FileReceipt> files) {
            this.directories = List.copyOf(directories); this.files = Map.copyOf(files);
        }
        static SnapshotReceipt capture(ApplicationWire.Root root, List<CompilerModelWire.Member> members,
                                       boolean effective) throws IOException {
            // Also validates full logical paths, order, prefix collisions, keys and aggregate source sizes.
            CompilerModelWire.membersDigest(members);
            Path base = checkRoot(root);
            Map<String, Set<String>> names = new LinkedHashMap<>(); names.put("", new LinkedHashSet<>());
            for (CompilerModelWire.Member member : members) {
                String[] parts = member.logicalPath().split("/");
                String parent = "";
                for (int i = 0; i < parts.length; i++) {
                    names.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(parts[i]);
                    if (i + 1 < parts.length) parent = parent.isEmpty() ? parts[i] : parent + "/" + parts[i];
                }
            }
            fsRequire(names.size() <= 1 + CompilerModelWire.MAX_SNAPSHOT_FILES * (CompilerModelWire.MAX_PATH_DEPTH - 1));
            ArrayList<DirectoryReceipt> directories = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : names.entrySet()) {
                ApplicationWire.Root directory = entry.getKey().isEmpty() ? root : observeRoot(base.resolve(entry.getKey()));
                directories.add(DirectoryReceipt.capture(directory, entry.getValue()));
            }
            Map<String, FileReceipt> files = new LinkedHashMap<>();
            for (CompilerModelWire.Member member : members) {
                FileReceipt file = FileReceipt.capture(base.resolve(member.logicalPath()), member.size(), member.digest(),
                        effective ? member.fileKey() : null);
                fsRequire(files.putIfAbsent(member.logicalPath(), file) == null);
            }
            SnapshotReceipt receipt = new SnapshotReceipt(directories, files);
            receipt.addIdentities(new LinkedHashMap<>()); receipt.verify();
            return receipt;
        }
        FileReceipt file(String logicalPath) {
            FileReceipt file = files.get(logicalPath); fsRequire(file != null); return file;
        }
        void verify() throws IOException {
            for (DirectoryReceipt directory : directories) directory.verify();
            for (FileReceipt file : files.values()) file.verify();
            for (DirectoryReceipt directory : directories) directory.verify();
        }
        void addIdentities(Map<String, Path> keys) {
            for (DirectoryReceipt directory : directories) pinKey(keys, directory.root.fileKey(), Path.of(directory.root.path()));
            for (FileReceipt file : files.values()) pinKey(keys, file.fileKey(), file.path());
        }
    }

    /** One independent archive copy per ordinal, even for repeated logical coordinates or digests. */
    static final class ClasspathReceipt {
        private final DirectoryReceipt directory;
        private final List<FileReceipt> files;
        private ClasspathReceipt(DirectoryReceipt directory, List<FileReceipt> files) {
            this.directory = directory; this.files = List.copyOf(files);
        }
        static ClasspathReceipt capture(ApplicationWire.Root root, List<CompilerModelWire.Dependency> dependencies,
                                        boolean effective) throws IOException {
            fsRequire(dependencies != null && dependencies.size() <= CompilerModelWire.MAX_DEPENDENCIES);
            fsRequire(effective ? root != null : (root == null) == dependencies.isEmpty());
            if (root == null) {
                fsRequire(!effective && dependencies.isEmpty());
                return new ClasspathReceipt(null, List.of());
            }
            Path base = checkRoot(root);
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < dependencies.size(); i++) names.add("artifact-" + i + ".jar");
            DirectoryReceipt directory = DirectoryReceipt.capture(root, names);
            ArrayList<FileReceipt> files = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < dependencies.size(); i++) {
                CompilerModelWire.Dependency dependency = dependencies.get(i);
                fsRequire(dependency != null && dependency.size() <= CompilerModelWire.MAX_DEPENDENCY_TOTAL_BYTES - total);
                total += dependency.size();
                files.add(FileReceipt.capture(base.resolve("artifact-" + i + ".jar"), dependency.size(), dependency.digest(),
                        effective ? dependency.fileKey() : null));
            }
            ClasspathReceipt receipt = new ClasspathReceipt(directory, files);
            receipt.addIdentities(new LinkedHashMap<>()); receipt.verify();
            return receipt;
        }
        void verify() throws IOException {
            if (directory == null) { fsRequire(files.isEmpty()); return; }
            directory.verify();
            for (FileReceipt file : files) {
                file.verify(); inspectDependencyArchive(file.path()); file.verify();
            }
            directory.verify();
        }
        void addIdentities(Map<String, Path> keys) {
            if (directory != null) pinKey(keys, directory.root.fileKey(), Path.of(directory.root.path()));
            for (FileReceipt file : files) pinKey(keys, file.fileKey(), file.path());
        }
    }

    private record DirectoryReceipt(ApplicationWire.Root root, Set<String> names, FileTime modifiedTime) {
        DirectoryReceipt { names = Set.copyOf(names); }
        static DirectoryReceipt capture(ApplicationWire.Root root, Set<String> names) throws IOException {
            Path path = checkRoot(root); BasicFileAttributes before = directory(path);
            expectNames(path, names);
            BasicFileAttributes after = directory(path);
            fsRequire(root.fileKey().equals(key(before)) && root.fileKey().equals(key(after))
                    && before.lastModifiedTime().equals(after.lastModifiedTime()));
            return new DirectoryReceipt(root, names, before.lastModifiedTime());
        }
        void verify() throws IOException {
            Path path = checkRoot(root); BasicFileAttributes before = directory(path);
            fsRequire(root.fileKey().equals(key(before)) && before.lastModifiedTime().equals(modifiedTime));
            expectNames(path, names);
            BasicFileAttributes after = directory(path);
            fsRequire(root.fileKey().equals(key(after)) && after.lastModifiedTime().equals(modifiedTime));
        }
    }

    private static void pinKey(Map<String, Path> keys, String key, Path path) {
        Path previous = keys.putIfAbsent(key, path);
        // The same original immutable path can be reused by another attempt; another path cannot borrow its key.
        fsRequire(previous == null || previous.equals(path));
    }

    static boolean canonicalManifestSpelling(String name) {
        String comparable = name;
        while (comparable.endsWith("/")) comparable = comparable.substring(0, comparable.length() - 1);
        return !comparable.equalsIgnoreCase("META-INF/MANIFEST.MF") || name.equals("META-INF/MANIFEST.MF");
    }

    /** Bounded decompression/CRC inspection only; no extraction, class loading or manifest expansion. */
    static void inspectDependencyArchive(Path path) throws IOException {
        regular(path);
        Set<String> names = new HashSet<>(), fileNames = new HashSet<>(), directoryNames = new HashSet<>();
        int count = 0;
        long expanded = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                fsRequire(++count <= MAX_ARCHIVE_ENTRIES && name.length() <= MAX_ARCHIVE_NAME_BYTES
                        && utf8Length(name) <= MAX_ARCHIVE_NAME_BYTES && !name.isEmpty()
                        && !name.startsWith("/") && name.indexOf('\\') < 0 && name.indexOf(':') < 0
                        && names.add(name) && canonicalManifestSpelling(name));
                for (int i = 0; i < name.length(); i++) fsRequire(name.charAt(i) >= 32 && name.charAt(i) != 127);
                String[] parts = name.split("/", -1);
                fsRequire(parts.length <= MAX_ARCHIVE_DEPTH);
                for (int i = 0; i < parts.length; i++)
                    fsRequire(!parts[i].equals(".") && !parts[i].equals("..")
                            && (!parts[i].isEmpty() || (i == parts.length - 1 && entry.isDirectory())));
                String comparable = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                fsRequire(!comparable.equalsIgnoreCase("META-INF/INDEX.LIST"));
                // An explicit file may not also be an explicit/implicit directory, in either order.
                for (int slash = comparable.indexOf('/'); slash >= 0; slash = comparable.indexOf('/', slash + 1)) {
                    String parent = comparable.substring(0, slash);
                    fsRequire(!fileNames.contains(parent)); directoryNames.add(parent);
                }
                if (entry.isDirectory()) {
                    fsRequire(!fileNames.contains(comparable) && entry.getSize() == 0);
                    directoryNames.add(comparable);
                } else {
                    fsRequire(!directoryNames.contains(comparable) && fileNames.add(comparable));
                }
                fsRequire(entry.getSize() >= 0 && entry.getSize() <= CompilerModelWire.MAX_DEPENDENCY_BYTES
                        && entry.getSize() <= MAX_ARCHIVE_EXPANDED_BYTES - expanded);
                expanded += entry.getSize();
                boolean manifestEntry = name.equals("META-INF/MANIFEST.MF");
                if (manifestEntry) fsRequire(!entry.isDirectory() && entry.getSize() <= MAX_MANIFEST_BYTES);
                byte[] content = readArchiveEntry(zip, entry, manifestEntry);
                if (manifestEntry) {
                    Manifest manifest = new Manifest(new ByteArrayInputStream(content));
                    for (Attributes attributes : manifest.getEntries().values())
                        fsRequire(attributes.getValue(Attributes.Name.CLASS_PATH) == null);
                    Attributes attributes = manifest.getMainAttributes();
                    fsRequire(attributes.getValue(Attributes.Name.CLASS_PATH) == null
                            && attributes.getValue("Boot-Class-Path") == null
                            && attributes.getValue("Launcher-Agent-Class") == null
                            && attributes.getValue("Premain-Class") == null && attributes.getValue("Agent-Class") == null);
                }
            }
        }
    }

    private static byte[] readArchiveEntry(ZipFile zip, ZipEntry entry, boolean retainManifest) throws IOException {
        long declared = entry.getSize(), seen = 0;
        byte[] content = retainManifest ? new byte[(int) declared] : null;
        byte[] buffer = new byte[8_192];
        CRC32 crc = new CRC32();
        try (InputStream input = zip.getInputStream(entry)) {
            while (true) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, declared - seen + 1));
                if (count < 0) break;
                fsRequire(count > 0 && count <= declared - seen);
                crc.update(buffer, 0, count);
                if (content != null) System.arraycopy(buffer, 0, content, (int) seen, count);
                seen += count;
            }
        }
        fsRequire(seen == declared && entry.getCrc() >= 0 && crc.getValue() == entry.getCrc());
        return content; // All other entry bytes are discarded after bounded hashing/counting.
    }

    record FileReceipt(Path path, String fileKey, long size, String digest, FileTime modifiedTime) {
        static FileReceipt fromArtifact(Path path, CompilerModelWire.Artifact artifact) throws IOException {
            return capture(path, artifact.size(), artifact.digest(), artifact.fileKey());
        }
        static FileReceipt capture(Path path, long size, String digest, String expectedKey) throws IOException {
            path = canonicalPath(path.toString());
            BasicFileAttributes attributes = regular(path);
            String fileKey = key(attributes);
            fsRequire(size >= 0 && attributes.size() == size && (expectedKey == null || expectedKey.equals(fileKey)));
            FileReceipt receipt = new FileReceipt(path, fileKey, size, digest, attributes.lastModifiedTime());
            receipt.verify();
            return receipt;
        }
        void verify() throws IOException {
            BasicFileAttributes before = regular(path);
            fsRequire(fileKey.equals(key(before)) && size == before.size() && modifiedTime.equals(before.lastModifiedTime()));
            fsRequire(digest.equals(hashFile(path, size)));
            BasicFileAttributes after = regular(path);
            fsRequire(fileKey.equals(key(after)) && size == after.size() && modifiedTime.equals(after.lastModifiedTime()));
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

    static void strictSource(Path path) throws IOException { readStrictSource(path); }

    private static String readStrictSource(Path path) throws IOException {
        byte[] bytes = readBoundedFile(path, CompilerModelWire.MAX_SOURCE_BYTES);
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            fsRequire(source.indexOf('\0') < 0 && !source.startsWith("\ufeff"));
            requireSupportedLineEndings(source);
            return source; // Empty selected .kt files are supported; bytes are never normalized.
        } catch (CharacterCodingException invalid) {
            throw new WorkerFailure(FatalReason.OWNERSHIP);
        }
    }

    private static void requireSupportedLineEndings(String source) {
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\r' && (i + 1 == source.length() || source.charAt(i + 1) != '\n'))
                throw new WorkerFailure(FatalReason.UNSUPPORTED);
        }
        // A documented preview compatibility gap, not an assertion about Kotlin's bare-CR semantics.
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

    private static void verifyProcess(ApplicationWire.Launch launch, CompilerModelWire.Generation generation,
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
        require(launch.command().equals(CompilerModelWire.command(launch.generationRoot(), generation))
                && launch.environment().equals(CompilerModelWire.environment(launch.generationRoot(), generation))
                && System.getenv().equals(launch.environment()), fence, FatalReason.IDENTITY);
        String command = ProcessHandle.current().info().command().orElseThrow(() -> new WorkerFailure(FatalReason.IDENTITY));
        require(canonicalPath(command).equals(canonicalPath(generation.jdk().executable())), fence, FatalReason.IDENTITY);
        Path bridge = Path.of(launch.generationRoot().path()).resolve("bridge.jar");
        for (Class<?> type : List.of(CompilerModelApplicationMain.class, PinnedKotlinModelCompilerAdapter.class,
                ApplicationWire.class, CompilerModelWire.class)) {
            require(type.getClassLoader() == CompilerModelApplicationMain.class.getClassLoader()
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
