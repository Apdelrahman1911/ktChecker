package dev.ktchecker.compilerworker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Data only: decoding a plan or receipt never creates an owner capability or process terminal. */
public final class CompilerWarmWorkerProtocol {
    private CompilerWarmWorkerProtocol() {}

    public static final String VERSION = "ktchecker/compiler-warm-generation/preview-1";
    public static final String API = "kotlin-2.4.21/caller-owned-JvmCliPipeline-1";
    public static final String COMPILER_SHA256 = "sha256:ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02";
    public static final int JOB_COUNT = 4;
    public static final int MAX_PLAN_BYTES = 65_536;
    public static final int MAX_RECEIPT_BYTES = 131_072;
    public static final int MAX_SOURCE_BYTES = 16_384;
    public static final int MAX_DIAGNOSTIC_BYTES = 65_536;
    public static final int MAX_DIAGNOSTICS = 128;
    public static final long MAX_ARTIFACT_BYTES = 67_108_864L;
    public static final long MAX_ARTIFACT_TOTAL_BYTES = 134_217_728L;
    public static final List<String> KINDS = List.of("positive", "clean", "clean", "positive");

    public record Artifact(String fileName, String digest, long size) {}

    public record Job(int ordinal, String jobId, String kind, String originalKey, String effectiveKey,
                      String snapshotDigest, String modelDigest, String dependencyDigest,
                      String configurationDigest, String profileDigest, String sourceDigest, long sourceSize) {
        public String sourcePath() { return "jobs/" + ordinal + "/sources/Fixture.kt"; }
        public String outputPath() { return "jobs/" + ordinal + "/classes"; }
        public String receiptPath() { return "jobs/" + ordinal + "/receipt.bin"; }
    }

    public record Plan(String generation, String ownerDomain, String implementationKey, String bridgeDigest,
                       String toolchainDigest, List<Artifact> compilerArtifacts, List<Integer> dependencyIndexes,
                       List<Job> jobs) {
        public Plan {
            compilerArtifacts = List.copyOf(compilerArtifacts);
            dependencyIndexes = List.copyOf(dependencyIndexes);
            jobs = List.copyOf(jobs);
        }
    }

    public record Diagnostic(String severity, String message, String path, int line, int column, String diagnosticId) {}

    public record Receipt(String generation, String planDigest, String implementationKey, String loaderToken,
                          int ordinal, String jobId, String originalKey, String effectiveKey, String sourceDigest,
                          String outputRootKey, String jobRootKey, String compilerVersion, String state,
                          int compilerCode, long startedNanos, long returnedNanos, long disposedNanos,
                          boolean disposalObserved, boolean cancellationObserved, String failureCode,
                          List<Diagnostic> diagnostics) {
        public Receipt { diagnostics = List.copyOf(diagnostics); }
    }

    /** Mutable sink belongs to one session only; no global collector or previous-job diagnostics. */
    public static final class DiagnosticSink {
        private final List<Diagnostic> values = new ArrayList<>();
        private int bytes;
        private boolean errors;
        private boolean cancelled;
        private boolean invalidObserved;

        public synchronized void report(Diagnostic value) {
            try {
                errors |= "ERROR".equals(value.severity()) || "EXCEPTION".equals(value.severity());
                cancelled |= "Compilation was canceled".equals(value.message());
                validateDiagnostic(value);
                int added = utf8Size(value.severity()) + utf8Size(value.message()) + utf8Size(value.path())
                        + utf8Size(value.diagnosticId()) + 24;
                require(values.size() < MAX_DIAGNOSTICS && bytes + added <= MAX_DIAGNOSTIC_BYTES, "diagnostic_limit");
                values.add(value);
                bytes += added;
            } catch (RuntimeException failure) {
                // Upstream catching a rejected callback must not erase this protocol failure.
                invalidObserved = true;
                throw failure;
            }
        }

        public synchronized boolean hasErrors() { return errors; }
        public synchronized boolean cancelled() { return cancelled; }
        public synchronized boolean invalidObserved() { return invalidObserved; }
        public synchronized List<Diagnostic> values() { return List.copyOf(values); }
        public synchronized void clear() {
            // A reset during one invocation must not erase already observed errors or cancellation.
            invalidObserved |= !values.isEmpty();
            require(values.isEmpty(), "diagnostic_clear_after_observation");
        }
    }

    public static String profileDigest(Job job, List<Integer> dependencies) {
        return sha256((API + "\n-no-stdlib\n-no-reflect\n-Xrender-internal-diagnostic-names\n-jvm-target\n17\n"
                + "-jdk-home={selected-java-home}\n-d=" + job.outputPath() + "\n-classpath={ordered-dependency-indexes}:"
                + dependencies + "\nsource=" + job.sourcePath()).getBytes(StandardCharsets.UTF_8));
    }

    public static void validatePlan(Plan plan) {
        require(plan.generation().matches("[a-f0-9]{32}") && plan.ownerDomain().matches("[a-f0-9]{32}"), "generation_identity");
        for (String value : List.of(plan.implementationKey(), plan.bridgeDigest(), plan.toolchainDigest())) digest(value);
        require(!plan.compilerArtifacts().isEmpty() && plan.compilerArtifacts().size() <= 16, "compiler_artifact_count");
        long total = 0;
        int compilerCount = 0;
        for (Artifact artifact : plan.compilerArtifacts()) {
            require(artifact.fileName().matches("[A-Za-z0-9._-]+\\.jar"), "artifact_name");
            digest(artifact.digest());
            require(artifact.size() > 0 && artifact.size() <= MAX_ARTIFACT_BYTES, "artifact_size");
            total = Math.addExact(total, artifact.size());
            if (artifact.fileName().equals("kotlin-compiler-embeddable-2.4.21.jar")) {
                require(artifact.digest().equals(COMPILER_SHA256), "unreviewed_compiler_bytes");
                compilerCount++;
            }
        }
        require(total <= MAX_ARTIFACT_TOTAL_BYTES && compilerCount == 1, "compiler_image");
        require(plan.compilerArtifacts().stream().map(Artifact::fileName).distinct().count() == plan.compilerArtifacts().size(), "duplicate_artifact");
        require(!plan.dependencyIndexes().isEmpty() && plan.dependencyIndexes().size() <= 3, "dependency_count");
        int previous = -1;
        boolean stdlib = false;
        for (int index : plan.dependencyIndexes()) {
            require(index > previous && index < plan.compilerArtifacts().size(), "dependency_order");
            String name = plan.compilerArtifacts().get(index).fileName();
            require(name.equals("kotlin-stdlib-2.4.21.jar") || name.matches("annotations-[0-9.]+\\.jar"), "dependency_not_allowlisted");
            stdlib |= name.equals("kotlin-stdlib-2.4.21.jar");
            previous = index;
        }
        require(stdlib && plan.jobs().size() == JOB_COUNT, "fixed_job_count");
        for (int index = 0; index < JOB_COUNT; index++) {
            Job job = plan.jobs().get(index);
            require(job.ordinal() == index && job.kind().equals(KINDS.get(index)), "fixed_job_order");
            require(job.jobId().equals(plan.generation() + "-job-" + index), "job_identity");
            for (String value : List.of(job.originalKey(), job.effectiveKey(), job.snapshotDigest(), job.modelDigest(),
                    job.dependencyDigest(), job.configurationDigest(), job.profileDigest(), job.sourceDigest())) digest(value);
            require(job.sourceSize() > 0 && job.sourceSize() <= MAX_SOURCE_BYTES, "source_size");
            require(job.profileDigest().equals(profileDigest(job, plan.dependencyIndexes())), "fixed_profile");
        }
    }

    public static byte[] encodePlan(Plan plan) throws IOException {
        validatePlan(plan);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            string(out, VERSION); string(out, "plan"); string(out, API);
            for (String value : List.of(plan.generation(), plan.ownerDomain(), plan.implementationKey(), plan.bridgeDigest(), plan.toolchainDigest())) string(out, value);
            out.writeInt(plan.compilerArtifacts().size());
            for (Artifact artifact : plan.compilerArtifacts()) { string(out, artifact.fileName()); string(out, artifact.digest()); out.writeLong(artifact.size()); }
            out.writeInt(plan.dependencyIndexes().size());
            for (int index : plan.dependencyIndexes()) out.writeInt(index);
            out.writeInt(plan.jobs().size());
            for (Job job : plan.jobs()) {
                out.writeInt(job.ordinal());
                for (String value : List.of(job.jobId(), job.kind(), job.originalKey(), job.effectiveKey(), job.snapshotDigest(),
                        job.modelDigest(), job.dependencyDigest(), job.configurationDigest(), job.profileDigest(), job.sourceDigest())) string(out, value);
                out.writeLong(job.sourceSize());
            }
        }
        require(bytes.size() <= MAX_PLAN_BYTES, "plan_limit");
        return bytes.toByteArray();
    }

    public static Plan decodePlan(byte[] bytes) throws IOException {
        require(bytes.length <= MAX_PLAN_BYTES, "plan_limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            header(in, "plan");
            String generation = string(in), owner = string(in), implementation = string(in), bridge = string(in), toolchain = string(in);
            int count = count(in, 16);
            List<Artifact> artifacts = new ArrayList<>();
            for (int i = 0; i < count; i++) artifacts.add(new Artifact(string(in), string(in), in.readLong()));
            count = count(in, 3);
            List<Integer> dependencies = new ArrayList<>();
            for (int i = 0; i < count; i++) dependencies.add(in.readInt());
            count = count(in, JOB_COUNT);
            List<Job> jobs = new ArrayList<>();
            for (int i = 0; i < count; i++) jobs.add(new Job(in.readInt(), string(in), string(in), string(in), string(in),
                    string(in), string(in), string(in), string(in), string(in), string(in), in.readLong()));
            require(in.read() == -1, "plan_trailing_bytes");
            Plan plan = new Plan(generation, owner, implementation, bridge, toolchain, artifacts, dependencies, jobs);
            validatePlan(plan);
            return plan;
        }
    }

    public static byte[] encodeReceipt(Receipt value) throws IOException {
        validateReceipt(value);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            string(out, VERSION); string(out, "receipt"); string(out, API);
            for (String text : List.of(value.generation(), value.planDigest(), value.implementationKey(), value.loaderToken())) string(out, text);
            out.writeInt(value.ordinal());
            for (String text : List.of(value.jobId(), value.originalKey(), value.effectiveKey(), value.sourceDigest(),
                    value.outputRootKey(), value.jobRootKey(), value.compilerVersion(), value.state())) string(out, text);
            out.writeInt(value.compilerCode());
            out.writeLong(value.startedNanos()); out.writeLong(value.returnedNanos()); out.writeLong(value.disposedNanos());
            out.writeBoolean(value.disposalObserved()); out.writeBoolean(value.cancellationObserved()); string(out, value.failureCode());
            out.writeInt(value.diagnostics().size());
            for (Diagnostic diagnostic : value.diagnostics()) {
                string(out, diagnostic.severity()); string(out, diagnostic.message()); string(out, diagnostic.path());
                out.writeInt(diagnostic.line()); out.writeInt(diagnostic.column()); string(out, diagnostic.diagnosticId());
            }
        }
        require(bytes.size() <= MAX_RECEIPT_BYTES, "receipt_limit");
        return bytes.toByteArray();
    }

    public static Receipt decodeReceipt(byte[] bytes) throws IOException {
        require(bytes.length <= MAX_RECEIPT_BYTES, "receipt_limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            header(in, "receipt");
            String generation = string(in), plan = string(in), implementation = string(in), loader = string(in);
            int ordinal = in.readInt();
            String job = string(in), original = string(in), effective = string(in), source = string(in), root = string(in),
                    jobRoot = string(in), compiler = string(in), state = string(in);
            int code = in.readInt();
            long started = in.readLong(), returned = in.readLong(), disposed = in.readLong();
            boolean observed = bool(in), cancelled = bool(in);
            String failure = string(in);
            int count = count(in, MAX_DIAGNOSTICS);
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (int i = 0; i < count; i++) diagnostics.add(new Diagnostic(string(in), string(in), string(in), in.readInt(), in.readInt(), string(in)));
            require(in.read() == -1, "receipt_trailing_bytes");
            Receipt receipt = new Receipt(generation, plan, implementation, loader, ordinal, job, original, effective, source,
                    root, jobRoot, compiler, state, code, started, returned, disposed, observed, cancelled, failure, diagnostics);
            validateReceipt(receipt);
            return receipt;
        }
    }

    public static void validateReceipt(Receipt value) {
        require(value.generation().matches("[a-f0-9]{32}") && value.ordinal() >= 0 && value.ordinal() < JOB_COUNT, "receipt_identity");
        require(value.jobId().equals(value.generation() + "-job-" + value.ordinal()), "receipt_job_identity");
        require(value.loaderToken().matches("[a-f0-9]{32}"), "loader_token");
        for (String digest : List.of(value.planDigest(), value.implementationKey(), value.originalKey(), value.effectiveKey(), value.sourceDigest())) digest(digest);
        require(!value.outputRootKey().isEmpty() && !value.jobRootKey().isEmpty(), "receipt_output_identity");
        require(value.compilerVersion().equals("2.4.21") || value.compilerVersion().equals("unobserved"), "receipt_compiler_version");
        require(Set.of("sealed", "outcome_unknown", "not_run").contains(value.state()), "receipt_state");
        require(value.failureCode().matches("[a-z_]{0,80}"), "receipt_failure_code");
        if (value.state().equals("not_run")) {
            require(value.compilerCode() == -1 && value.startedNanos() == -1 && value.returnedNanos() == -1
                    && value.disposedNanos() == -1 && !value.disposalObserved() && !value.cancellationObserved()
                    && value.diagnostics().isEmpty() && !value.failureCode().isEmpty(), "not_run_has_observations");
        } else {
            require(value.startedNanos() >= 0 && value.returnedNanos() >= -1 && value.disposedNanos() >= -1, "receipt_clock");
            require(value.returnedNanos() == -1 || value.returnedNanos() >= value.startedNanos(), "receipt_return_clock");
            require(value.disposedNanos() == -1 || value.disposedNanos() >= Math.max(value.startedNanos(), value.returnedNanos()), "receipt_disposal_clock");
            // Any observed int is retained, including an unexpected API result; only 0/1 may seal.
            require(value.returnedNanos() != -1 || value.compilerCode() == -1, "code_without_api_return");
            require(!value.disposalObserved() || value.disposedNanos() >= 0, "disposal_without_return");
        }
        if (value.state().equals("sealed")) require((value.compilerCode() == 0 || value.compilerCode() == 1)
                && value.returnedNanos() >= 0 && value.disposedNanos() >= value.returnedNanos() && value.disposalObserved()
                && !value.cancellationObserved() && value.compilerVersion().equals("2.4.21") && value.failureCode().isEmpty(), "unsealed_outcome");
        if (value.state().equals("outcome_unknown")) require(!value.failureCode().isEmpty(), "unknown_without_failure");
        DiagnosticSink sink = new DiagnosticSink();
        for (Diagnostic diagnostic : value.diagnostics()) sink.report(diagnostic);
        require(!sink.cancelled() || value.cancellationObserved(), "erased_cancellation");
        require(!value.state().equals("sealed") || value.compilerCode() != 0 || !sink.hasErrors(), "clean_with_error_diagnostics");
    }

    public static void matchReceipt(Plan plan, String planDigest, Job job, Receipt receipt, String rootKey, String jobKey) {
        validateReceipt(receipt);
        require(receipt.generation().equals(plan.generation()) && receipt.planDigest().equals(planDigest)
                && receipt.implementationKey().equals(plan.implementationKey()) && receipt.ordinal() == job.ordinal()
                && receipt.jobId().equals(job.jobId()) && receipt.originalKey().equals(job.originalKey())
                && receipt.effectiveKey().equals(job.effectiveKey()) && receipt.sourceDigest().equals(job.sourceDigest())
                && receipt.outputRootKey().equals(rootKey) && receipt.jobRootKey().equals(jobKey), "foreign_receipt");
    }

    /** Path checks and fresh byte hashes, never mtime identities; cooperative same-UID/ABA limitation remains. */
    public static Path safePath(Path root, String relative) throws IOException {
        require(root.isAbsolute() && root.normalize().equals(root), "noncanonical_root");
        require(root.toRealPath().equals(root), "aliased_root");
        require(relative.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"), "unsafe_relative_path");
        Path current = root;
        directoryKey(root);
        for (String component : relative.split("/")) {
            require(!component.equals(".") && !component.equals(".."), "unsafe_relative_path");
            current = current.resolve(component);
            require(!Files.isSymbolicLink(current), "linked_descendant");
        }
        return current;
    }

    public static String directoryKey(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(attributes.isDirectory() && !attributes.isSymbolicLink() && attributes.fileKey() != null, "unsafe_directory");
        return attributes.fileKey().toString();
    }

    public static byte[] readBounded(Path root, String relative, int maximum) throws IOException {
        require(maximum > 0 && maximum <= MAX_RECEIPT_BYTES, "read_bound");
        Path path = safePath(root, relative);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && !before.isSymbolicLink() && before.fileKey() != null
                && before.size() >= 0 && before.size() <= maximum, "unsafe_file");
        byte[] bytes;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maximum + 1); }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(bytes.length == before.size() && after.isRegularFile() && before.fileKey().equals(after.fileKey())
                && after.size() == before.size(), "file_changed");
        return bytes;
    }

    public static void verifyArtifact(Path root, String relative, String expected, long size) throws Exception {
        digest(expected);
        require(size > 0 && size <= MAX_ARTIFACT_BYTES, "artifact_size");
        Path path = safePath(root, relative);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(before.isRegularFile() && before.size() == size && before.fileKey() != null, "artifact_changed");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65_536];
            int read;
            while ((read = input.read(buffer)) != -1) { count += read; require(count <= size, "artifact_grew"); digest.update(buffer, 0, read); }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        require(count == size && after.isRegularFile() && before.fileKey().equals(after.fileKey()) && after.size() == size
                && expected.equals("sha256:" + HexFormat.of().formatHex(digest.digest())), "artifact_changed");
    }

    public static String sha256(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static void require(boolean value, String code) { if (!value) throw new IllegalArgumentException(code); }
    private static void digest(String value) { require(value.matches("sha256:[a-f0-9]{64}"), "invalid_digest"); }
    private static byte[] utf8(String value) {
        require(value.indexOf('\0') < 0, "nul_string");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid_string_utf8", invalid);
        }
    }
    private static int utf8Size(String value) { return utf8(value).length; }
    private static void validateDiagnostic(Diagnostic value) {
        require(Set.of("EXCEPTION", "ERROR", "STRONG_WARNING", "WARNING", "INFO", "LOGGING", "OUTPUT").contains(value.severity()), "diagnostic_severity");
        require(utf8Size(value.message()) <= 16_384 && utf8Size(value.path()) <= 4_096 && utf8Size(value.diagnosticId()) <= 256, "diagnostic_field_limit");
        require(value.line() >= -1 && value.column() >= -1, "diagnostic_location");
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = utf8(value);
        require(bytes.length <= 16_384, "string_limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int length = count(in, 16_384);
        byte[] bytes = in.readNBytes(length);
        require(bytes.length == length, "truncated_string");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        require(value.indexOf('\0') < 0, "nul_string");
        return value;
    }
    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt(); require(count >= 0 && count <= maximum, "count_limit"); return count;
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); require(value <= 1, "invalid_boolean"); return value == 1;
    }
    private static void header(DataInputStream in, String kind) throws IOException {
        require(string(in).equals(VERSION) && string(in).equals(kind) && string(in).equals(API), "protocol_version");
    }
}
