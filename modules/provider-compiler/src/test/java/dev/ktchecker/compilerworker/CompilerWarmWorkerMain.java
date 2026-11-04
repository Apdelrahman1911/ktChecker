package dev.ktchecker.compilerworker;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol.*;

/** One finite JVM generation, exactly one active job. Not a daemon, RPC client, provider or scheduler. */
public final class CompilerWarmWorkerMain {
    private CompilerWarmWorkerMain() {}

    /** Fake implementations are unit-test seams only; external main always constructs the pinned adapter. */
    public interface Session extends AutoCloseable {
        int execute(List<String> argv, DiagnosticSink sink) throws Exception;
        boolean disposalObserved();
        @Override void close() throws Exception;
    }
    public interface SessionFactory extends AutoCloseable {
        String compilerVersion();
        Session open() throws Exception;
        @Override void close() throws Exception;
    }

    public static void main(String[] args) {
        RawOutput stdout = new RawOutput(System.out), stderr = new RawOutput(System.err);
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        boolean successful = false;
        try {
            require(args.length == 4, "worker_arguments");
            Path output = Path.of(args[0]);
            require(output.isAbsolute() && output.normalize().equals(output) && output.toRealPath().equals(output), "worker_output_alias");
            byte[] encoded = readBounded(output, "plan.bin", MAX_PLAN_BYTES);
            require(sha256(encoded).equals(args[1]), "worker_plan_digest");
            Plan plan = decodePlan(encoded);
            require(plan.generation().equals(args[2]) && plan.implementationKey().equals(args[3]), "worker_launch_identity");
            Path ownJar = Path.of(CompilerWarmWorkerMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            require(ownJar.equals(output.resolve("worker.jar")), "worker_component_origin");
            verifyArtifact(output, "worker.jar", plan.bridgeDigest(), Files.size(ownJar));
            BooleanSupplier rawQuiet = () -> stdout.count() == 0 && stderr.count() == 0;
            try (SessionFactory factory = new PinnedKotlinCompilerAdapter(plan, output)) {
                require(rawQuiet.getAsBoolean(), "raw_process_output");
                successful = runFixedPlan(plan, output, factory, System::nanoTime, rawQuiet);
            }
            successful &= rawQuiet.getAsBoolean();
        } catch (Throwable failure) {
            // Preserve earlier immutable receipts. No exception text/path/stack is a protocol frame.
            successful = false;
        }
        if (!successful) System.exit(70);
    }

    /** Public only for focused fake-session tests; no serialized data selects a SessionFactory. */
    public static boolean runFixedPlan(Plan plan, Path output, SessionFactory factory,
                                       LongSupplier clock, BooleanSupplier rawQuiet) throws Exception {
        String outputKey = directoryKey(output);
        boolean sessions = runSessionSequence(plan, output, factory, clock, rawQuiet);
        require(directoryKey(output).equals(outputKey), "output_root_changed");
        Set<String> expectedCompilerEntries = new HashSet<>();
        for (int i = 0; i < plan.compilerArtifacts().size(); i++) {
            Artifact artifact = plan.compilerArtifacts().get(i);
            expectedCompilerEntries.add(i + ".jar");
            verifyArtifact(output, "compiler/" + i + ".jar", artifact.digest(), artifact.size());
        }
        require(entries(safePath(output, "compiler")).equals(expectedCompilerEntries), "unexpected_compiler_entry");
        boundedTree(output, MAX_ARTIFACT_TOTAL_BYTES + 8_388_608L, 4_096);
        return sessions && rawQuiet.getAsBoolean();
    }

    /** Session/disposal/receipt unit seam only; this return omits image verification and is NOT a generation terminal. */
    public static boolean runSessionSequence(Plan plan, Path output, SessionFactory factory,
                                             LongSupplier clock, BooleanSupplier rawQuiet) throws Exception {
        validatePlan(plan);
        String planDigest = sha256(encodePlan(plan));
        String outputKey = directoryKey(output);
        String loaderToken = UUID.randomUUID().toString().replace("-", "");
        String version = factory.compilerVersion();
        require(version.equals("2.4.21"), "compiler_version_changed");
        long origin = clock.getAsLong();
        require(entries(output).equals(Set.of("tmp", "worker.jar", "plan.bin", "compiler", "jobs")), "unexpected_generation_entry");
        require(entries(safePath(output, "jobs")).equals(Set.of("0", "1", "2", "3")), "unexpected_job_entry");
        Map<String, String> directoryKeys = new LinkedHashMap<>();
        for (String path : List.of("tmp", "compiler", "jobs")) directoryKeys.put(path, directoryKey(safePath(output, path)));
        for (Job job : plan.jobs()) {
            for (String path : List.of("jobs/" + job.ordinal(), "jobs/" + job.ordinal() + "/sources", job.outputPath())) {
                directoryKeys.put(path, directoryKey(safePath(output, path)));
            }
        }
        boolean poisoned = false;
        for (Job job : plan.jobs()) {
            Path jobRoot = safePath(output, "jobs/" + job.ordinal());
            String jobKey = directoryKeys.get("jobs/" + job.ordinal());
            DiagnosticSink diagnostics = new DiagnosticSink();
            long started = -1, returned = -1, disposed = -1;
            int code = -1;
            boolean disposalObserved = false;
            String failure = poisoned ? "generation_poisoned" : "";
            String state = poisoned ? "not_run" : "outcome_unknown";
            if (!poisoned) {
                started = clock.getAsLong() - origin;
                Session session = null;
                try {
                    require(directoryKey(output).equals(outputKey), "output_root_changed");
                    verifyDirectories(output, directoryKeys);
                    verifyPlan(plan, output, planDigest);
                    verifySource(job, output);
                    require(entries(jobRoot).equals(Set.of("sources", "classes"))
                            && entries(safePath(output, job.outputPath())).isEmpty(), "job_output_not_fresh");
                    require(rawQuiet.getAsBoolean(), "raw_process_output");
                    session = factory.open();
                    code = session.execute(arguments(plan, job, output), diagnostics);
                    returned = clock.getAsLong() - origin;
                    if (code != 0 && code != 1) failure = "compiler_internal_error";
                    if (diagnostics.cancelled()) failure = "compiler_cancelled";
                    if (diagnostics.invalidObserved() || (code == 0 && diagnostics.hasErrors())) failure = "diagnostic_protocol_failed";
                } catch (Throwable problem) {
                    failure = "compiler_call_failed";
                } finally {
                    if (session != null) {
                        try {
                            session.close();
                            disposed = clock.getAsLong() - origin;
                            disposalObserved = session.disposalObserved();
                            if (!disposalObserved && failure.isEmpty()) failure = "compiler_disposal_unobserved";
                        } catch (Throwable problem) {
                            if (failure.isEmpty()) failure = "compiler_disposal_failed";
                        }
                    }
                }
                try {
                    require(directoryKey(output).equals(outputKey) && directoryKey(jobRoot).equals(jobKey), "output_root_changed");
                    verifyDirectories(output, directoryKeys);
                    verifyPlan(plan, output, planDigest);
                    verifySource(job, output);
                    require(entries(jobRoot).equals(Set.of("sources", "classes")), "unexpected_job_output");
                    require(rawQuiet.getAsBoolean(), "raw_process_output");
                    require(!diagnostics.invalidObserved(), "diagnostic_protocol_failed");
                    boundedTree(safePath(output, job.outputPath()), 1_048_576L, 512);
                } catch (Throwable problem) {
                    if (failure.isEmpty()) failure = "worker_integrity_failed";
                }
                poisoned = !failure.isEmpty() || !disposalObserved;
                if (!poisoned) state = "sealed";
            }
            Receipt receipt = new Receipt(plan.generation(), planDigest, plan.implementationKey(), loaderToken,
                    job.ordinal(), job.jobId(), job.originalKey(), job.effectiveKey(), job.sourceDigest(), outputKey, jobKey,
                    version, state, code, started, returned, disposed, disposalObserved, diagnostics.cancelled(), failure, diagnostics.values());
            // Even a not_run record may not be written through a substituted job root.
            require(directoryKey(output).equals(outputKey) && directoryKey(jobRoot).equals(jobKey), "receipt_root_changed");
            Path receiptPath = safePath(output, job.receiptPath());
            Files.write(receiptPath, encodeReceipt(receipt), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(receiptPath, PosixFilePermissions.fromString("r--------"));
        }
        require(directoryKey(output).equals(outputKey), "output_root_changed");
        verifyDirectories(output, directoryKeys);
        verifyPlan(plan, output, planDigest);
        for (Job job : plan.jobs()) verifySource(job, output);
        return !poisoned && rawQuiet.getAsBoolean();
    }

    public static List<String> arguments(Plan plan, Job job, Path output) throws Exception {
        List<String> values = new ArrayList<>(List.of("-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names",
                "-jvm-target", "17", "-jdk-home", System.getProperty("java.home"), "-d", safePath(output, job.outputPath()).toString(), "-classpath"));
        List<String> dependencies = new ArrayList<>();
        for (int index : plan.dependencyIndexes()) dependencies.add(safePath(output, "compiler/" + index + ".jar").toString());
        values.add(String.join(java.io.File.pathSeparator, dependencies));
        values.add(safePath(output, job.sourcePath()).toString());
        return List.copyOf(values);
    }

    public static void verifySource(Job job, Path output) throws Exception {
        require(entries(safePath(output, "jobs/" + job.ordinal() + "/sources")).equals(Set.of("Fixture.kt")), "source_membership_changed");
        byte[] bytes = readBounded(output, job.sourcePath(), MAX_SOURCE_BYTES);
        require(bytes.length == job.sourceSize() && sha256(bytes).equals(job.sourceDigest()), "source_changed");
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
    }

    private static void verifyPlan(Plan plan, Path output, String digest) throws Exception {
        require(sha256(readBounded(output, "plan.bin", MAX_PLAN_BYTES)).equals(digest), "plan_changed");
        require(sha256(encodePlan(plan)).equals(digest), "plan_descriptor_changed");
    }

    private static void verifyDirectories(Path root, Map<String, String> expected) throws Exception {
        for (var entry : expected.entrySet()) require(directoryKey(safePath(root, entry.getKey())).equals(entry.getValue()), "job_descendant_replaced");
    }

    /** Bounded data-only membership check shared with the trusted host; not ownership registration. */
    public static Set<String> entries(Path root) throws Exception {
        directoryKey(root);
        Set<String> result = new HashSet<>();
        try (var stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) { require(result.size() < 4_096, "directory_entry_limit"); result.add(path.getFileName().toString()); }
        }
        return Set.copyOf(result);
    }

    public static void boundedTree(Path root, long maximumBytes, int maximumEntries) throws Exception {
        directoryKey(root);
        long bytes = 0;
        int count = 0;
        try (var stream = Files.walk(root, 16)) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                require(++count <= maximumEntries, "output_entry_limit");
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!attributes.isSymbolicLink() && (attributes.isDirectory() || attributes.isRegularFile()), "unsafe_output_entry");
                require(!attributes.isDirectory() || root.relativize(path).getNameCount() < 16, "output_depth_limit");
                bytes = Math.addExact(bytes, attributes.isRegularFile() ? attributes.size() : 0);
                require(bytes <= maximumBytes, "output_byte_limit");
            }
        }
    }

    private static final class RawOutput extends OutputStream {
        private final PrintStream original;
        private long count;
        private RawOutput(PrintStream original) { this.original = original; }
        synchronized long count() { return count; }
        @Override public synchronized void write(int value) {
            if (count < 65_536) original.write(value);
            if (count < Long.MAX_VALUE) count++;
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            int retained = (int) Math.max(0L, Math.min(length, 65_536L - count));
            if (retained > 0) original.write(bytes, offset, retained);
            count = length > Long.MAX_VALUE - count ? Long.MAX_VALUE : count + length;
        }
        @Override public synchronized void flush() { original.flush(); }
    }
}
