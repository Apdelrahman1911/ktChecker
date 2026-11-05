package dev.ktchecker.workerprotocol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Pure detached synthetic descriptors: these helpers never create files or execution authority. */
final class WireFixtures {
    static final String GENERATION = "a".repeat(32);
    static final String OWNER = "b".repeat(32);
    static final String LOADER = "c".repeat(32);
    static final String DEADLINE = "d".repeat(32);
    // Use only the native absolute anchor (including a drive/UNC root), not incidental temp names.
    // None of these synthetic paths is created, opened, resolved through links or required to exist.
    private static final Path SYNTHETIC_BASE = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath().normalize().getRoot();
    static final String ROOT = synthetic("ktchecker-wire-only-generation");
    private WireFixtures() {}

    static String hash(String value) { return ApplicationWire.sha256(value.getBytes(StandardCharsets.UTF_8)); }

    static String synthetic(String name) { return SYNTHETIC_BASE.resolve(name).toString(); }

    // Deliberately do not normalize: negative tests must retain lexical dot/dotdot aliases.
    static String child(String parent, String... components) {
        Path result = Path.of(parent);
        for (String component : components) result = result.resolve(component);
        return result.toString();
    }

    static CompilerJobWire.Generation generation() {
        var jdkRoot = new ApplicationWire.Root(synthetic("ktchecker-wire-only-jdk21"), "jdk-root-key");
        var jdk = new CompilerJobWire.Jdk(jdkRoot, child(jdkRoot.path(), "bin", "java"), hash("java"), hash("jdk"), 21);
        var bridge = new CompilerJobWire.Artifact("dev.ktchecker:worker:preview-1", "bridge.jar", hash("bridge"), 100, "bridge-key");
        var compiler = new CompilerJobWire.Artifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21",
                "kotlin-compiler-embeddable-2.4.21.jar", CompilerJobWire.COMPILER_SHA256, 10_000, "compiler-key");
        var stdlib = new CompilerJobWire.Artifact("org.jetbrains.kotlin:kotlin-stdlib:2.4.21",
                "kotlin-stdlib-2.4.21.jar", hash("stdlib"), 2_000, "stdlib-key");
        var annotations = new CompilerJobWire.Artifact("org.jetbrains:annotations:13.0",
                "annotations-13.0.jar", hash("annotations"), 500, "annotations-key");
        return new CompilerJobWire.Generation(CompilerJobWire.PROFILE, CompilerJobWire.API, CompilerJobWire.COMPILER_VERSION,
                17, jdk, bridge, List.of(compiler, stdlib, annotations), List.of(1, 2));
    }

    static ApplicationWire.Launch launch() { return launch(generation(), 9); }

    static ApplicationWire.Launch launch(CompilerJobWire.Generation generation, int jobs) {
        var root = new ApplicationWire.Root(ROOT, "generation-root-key");
        var identity = new ApplicationWire.Identity(GENERATION, OWNER, hash("worktree"), hash("implementation"),
                generation.bridge().digest(), CompilerJobWire.compilerClosureDigest(generation),
                CompilerJobWire.toolchainDigest(generation), CompilerJobWire.API, CompilerJobWire.PROFILE);
        return new ApplicationWire.Launch(identity, new ApplicationWire.Limits(jobs, 2_097_152, 2_097_152),
                root, root.path(), CompilerJobWire.command(root, generation), CompilerJobWire.environment(root, generation),
                CompilerJobWire.encodeGeneration(generation));
    }

    static ApplicationWire.Hello hello(ApplicationWire.Launch launch) {
        return new ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), LOADER,
                CompilerJobWire.COMPILER_VERSION, true, true);
    }

    static CompilerJobWire.Source source(String key, String content) {
        return new CompilerJobWire.Source("Fixture.kt", hash(content), content.getBytes(StandardCharsets.UTF_8).length, key);
    }

    static CompilerJobWire.Request request(CompilerJobWire.Generation generation, int sequence) {
        return request(generation, sequence, source("source-file-" + sequence, "fun example() = missingName\n"));
    }

    static CompilerJobWire.Request request(CompilerJobWire.Generation generation, int sequence, CompilerJobWire.Source source) {
        String members = CompilerJobWire.membersDigest(List.of(source));
        var original = new CompilerJobWire.InputIdentity(hash("original-" + sequence), hash("original-snapshot-" + sequence),
                members, hash("model"), hash("original-dependencies"), hash("original-configuration"), hash("framework-toolchain"));
        var effective = new CompilerJobWire.InputIdentity(hash("effective-" + sequence), hash("effective-snapshot-" + sequence),
                members, hash("model"), hash("effective-dependencies"), hash("effective-configuration"), hash("framework-toolchain"));
        String job = child(ROOT, "jobs", Integer.toString(sequence));
        return new CompilerJobWire.Request(CompilerJobWire.PROFILE, original, effective,
                new ApplicationWire.Root(synthetic("ktchecker-wire-only-capture-" + sequence), "snapshot-key-" + sequence),
                new ApplicationWire.Root(job, "job-key-" + sequence),
                new ApplicationWire.Root(child(job, "sources"), "sources-key-" + sequence),
                new ApplicationWire.Root(child(job, "classes"), "classes-key-" + sequence),
                List.of(source), CompilerJobWire.M0_OPTIONS, CompilerJobWire.M0_OPTIONS,
                CompilerJobWire.profileDigest(generation), DEADLINE);
    }

    static ApplicationWire.Job job(ApplicationWire.Launch launch, CompilerJobWire.Request request, int sequence) {
        return new ApplicationWire.Job(GENERATION, ApplicationWire.helloDigest(hello(launch)), sequence,
                ApplicationWire.jobId(GENERATION, sequence), request.original().analysisKey(), request.effective().analysisKey(),
                request.deadlineIdentity(), CompilerJobWire.encodeRequest(request));
    }

    static CompilerJobWire.Observation observation(ApplicationWire.Launch launch, CompilerJobWire.Generation generation,
                                                   CompilerJobWire.Request request, int code, List<CompilerJobWire.Diagnostic> diagnostics) {
        var reset = new CompilerJobWire.Reset(true, true, true, true);
        return new CompilerJobWire.Observation(CompilerJobWire.COMPILER_VERSION,
                CompilerJobWire.argvDigest(CompilerJobWire.arguments(launch, generation, request)),
                CompilerJobWire.membersDigest(request.sources()), request.jobRoot(), request.sourceRoot(), request.outputRoot(),
                true, code, 1, true, false, reset, reset, true, true, true, CompilerJobWire.FailureCode.NONE, diagnostics);
    }

    static ApplicationWire.Result result(ApplicationWire.Job job, CompilerJobWire.Observation observation) {
        return new ApplicationWire.Result(job.generation(), job.helloDigest(), job.sequence(), job.jobId(),
                ApplicationWire.requestDigest(job), job.originalKey(), job.effectiveKey(), LOADER,
                ApplicationWire.ResultState.APPLICATION_SEALED, CompilerJobWire.encodeObservation(observation));
    }

    static CompilerJobWire.Diagnostic error(CompilerJobWire.Request request) {
        return new CompilerJobWire.Diagnostic("ERROR", "Unresolved reference 'missingName'.",
                child(request.sourceRoot().path(), "Fixture.kt"), 1, 17, "UNRESOLVED_REFERENCE");
    }

    static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }

    static int find(byte[] bytes, String value) {
        byte[] wanted = value.getBytes(StandardCharsets.UTF_8);
        outer: for (int i = 0; i <= bytes.length - wanted.length; i++) {
            for (int j = 0; j < wanted.length; j++) if (bytes[i + j] != wanted[j]) continue outer;
            return i;
        }
        throw new AssertionError("Synthetic marker absent");
    }

    static byte[] join(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length = Math.addExact(length, value.length);
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length); offset += value.length;
        }
        return result;
    }
}
