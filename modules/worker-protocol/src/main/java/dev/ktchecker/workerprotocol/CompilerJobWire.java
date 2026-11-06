package dev.ktchecker.workerprotocol;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.ktchecker.workerprotocol.ApplicationWire.ErrorCode;
import static dev.ktchecker.workerprotocol.ApplicationWire.MAX_ID_BYTES;
import static dev.ktchecker.workerprotocol.ApplicationWire.MAX_KEY_BYTES;
import static dev.ktchecker.workerprotocol.ApplicationWire.MAX_PATH_BYTES;
import static dev.ktchecker.workerprotocol.ApplicationWire.checkDigest;
import static dev.ktchecker.workerprotocol.ApplicationWire.failure;
import static dev.ktchecker.workerprotocol.ApplicationWire.nonnull;
import static dev.ktchecker.workerprotocol.ApplicationWire.require;

/**
 * The explicitly unsupported-by-default M0 compiler DTO profile. JDK ONLY: no compiler classes,
 * loaders, reflection, filesystem operations, model permits or execution capabilities live here.
 * This profile is not the product's source/target scope: a wider profile needs a new reviewed
 * version, not guessed flags, normalized project inputs, fallback or silently removed work.
 */
public final class CompilerJobWire {
    private CompilerJobWire() {}

    public static final String VERSION = "ktchecker/compiler-job/m0-preview-1";
    public static final String PROFILE = "ktchecker/compiler-m0/preview-1";
    public static final String API = "kotlin-2.4.21/caller-owned-JvmCliPipeline-1";
    public static final String COMPILER_VERSION = "2.4.21";
    public static final String COMPILER_SHA256 = "sha256:ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02";
    public static final String MAIN_CLASS = "dev.ktchecker.compilerworker.live.CompilerApplicationMain";
    public static final int MAX_SOURCE_BYTES = 16_384;
    public static final int MAX_DIAGNOSTICS = 128;
    public static final int MAX_DIAGNOSTIC_BYTES = 65_536;
    public static final int MAX_ARTIFACTS = 16;
    public static final long MAX_ARTIFACT_BYTES = 67_108_864L;
    public static final long MAX_ARTIFACT_TOTAL_BYTES = 134_217_728L;
    public static final long MAX_BRIDGE_BYTES = 4_194_304L;
    // Fixed M0 materialization caps. Owners still enforce actual retained-generation accounting.
    public static final int MAX_JOB_OUTPUT_FILES = 128;
    public static final long MAX_JOB_OUTPUT_BYTES = 8L * 1_024 * 1_024;
    public static final long MAX_GENERATION_OUTPUT_BYTES = 32L * 1_024 * 1_024;
    public static final int MAX_TEMP_FILES = 128;
    public static final long MAX_TEMP_BYTES = 8L * 1_024 * 1_024;
    public static final List<String> M0_OPTIONS = List.of("-jvm-target", "17");
    private static final String SOURCE_NAME = "Fixture.kt";
    private static final int MAX_DEPENDENCIES = 3;
    private static final int GENERATION = 1, REQUEST = 2, OBSERVATION = 3;

    /** File keys name admitted materialized files, not the original installation's artifact paths. */
    public record Artifact(String coordinate, String fileName, String digest, long size, String fileKey) {
        public Artifact {
            ApplicationWire.identifier(coordinate);
            ApplicationWire.text(fileName, MAX_ID_BYTES, false);
            require(fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar"), ErrorCode.PATH);
            checkDigest(digest); ApplicationWire.key(fileKey);
            require(size > 0 && size <= MAX_ARTIFACT_BYTES, ErrorCode.LIMIT);
        }
    }

    /** Echoed runtime descriptor; a self-reported version or file hash is NOT installation proof. */
    public record Jdk(ApplicationWire.Root home, String executable, String executableDigest,
                      String runtimeDigest, int major) {
        public Jdk {
            nonnull(home); ApplicationWire.absolutePath(executable);
            checkDigest(executableDigest); checkDigest(runtimeDigest);
            require(major == 21, ErrorCode.UNSUPPORTED);
            require(executable.equals(resolve(home, "bin/java")), ErrorCode.PATH);
        }
    }

    public record Generation(String profile, String apiVersion, String compilerVersion, int jvmTarget,
                             Jdk jdk, Artifact bridge, List<Artifact> compilerArtifacts,
                             List<Integer> dependencyIndexes) {
        public Generation {
            require(PROFILE.equals(profile) && API.equals(apiVersion)
                    && COMPILER_VERSION.equals(compilerVersion) && jvmTarget == 17, ErrorCode.UNSUPPORTED);
            nonnull(jdk); nonnull(bridge);
            require(bridge.size <= MAX_BRIDGE_BYTES, ErrorCode.LIMIT);
            compilerArtifacts = ApplicationWire.list(compilerArtifacts, MAX_ARTIFACTS);
            dependencyIndexes = ApplicationWire.list(dependencyIndexes, MAX_DEPENDENCIES);
            require(!compilerArtifacts.isEmpty() && !dependencyIndexes.isEmpty(), ErrorCode.VALUE);
            Set<String> names = new HashSet<>(), coordinates = new HashSet<>(), keys = new HashSet<>();
            keys.add(bridge.fileKey);
            long total = 0;
            int compilers = 0;
            for (Artifact artifact : compilerArtifacts) {
                require(names.add(artifact.fileName) && coordinates.add(artifact.coordinate)
                        && keys.add(artifact.fileKey), ErrorCode.DUPLICATE);
                require(artifact.size <= MAX_ARTIFACT_TOTAL_BYTES - total, ErrorCode.LIMIT);
                total += artifact.size;
                if (artifact.fileName.equals("kotlin-compiler-embeddable-2.4.21.jar")) {
                    require(COMPILER_SHA256.equals(artifact.digest), ErrorCode.UNSUPPORTED);
                    compilers++;
                }
            }
            require(compilers == 1, ErrorCode.UNSUPPORTED);
            int previous = -1;
            boolean stdlib = false;
            for (int index : dependencyIndexes) {
                require(index > previous && index < compilerArtifacts.size(), ErrorCode.ORDER);
                String name = compilerArtifacts.get(index).fileName;
                require(name.equals("kotlin-stdlib-2.4.21.jar") || name.matches("annotations-[0-9.]+\\.jar"), ErrorCode.UNSUPPORTED);
                stdlib |= name.equals("kotlin-stdlib-2.4.21.jar");
                previous = index;
            }
            require(stdlib, ErrorCode.UNSUPPORTED);
        }
    }

    /**
     * Both original and effective framework identities survive independently. In particular the
     * framework toolchain/model digest is NOT rewritten to equal the relocated JDK descriptor.
     * The owner retains the complete frozen inputs and proves their original/effective relation.
     */
    public record InputIdentity(String analysisKey, String snapshotDigest, String memberDigest,
                                String modelDigest, String dependencyDigest, String configurationDigest,
                                String toolchainDigest) {
        public InputIdentity {
            checkDigest(analysisKey); checkDigest(snapshotDigest); checkDigest(memberDigest);
            checkDigest(modelDigest); checkDigest(dependencyDigest); checkDigest(configurationDigest);
            checkDigest(toolchainDigest);
        }
    }

    public record Source(String logicalPath, String digest, long size, String fileKey) {
        public Source {
            require(SOURCE_NAME.equals(logicalPath), ErrorCode.UNSUPPORTED);
            checkDigest(digest); ApplicationWire.key(fileKey);
            require(size > 0 && size <= MAX_SOURCE_BYTES, ErrorCode.LIMIT);
        }
    }

    public record Request(String profile, InputIdentity original, InputIdentity effective,
                          ApplicationWire.Root snapshotRoot, ApplicationWire.Root jobRoot,
                          ApplicationWire.Root sourceRoot, ApplicationWire.Root outputRoot,
                          List<Source> sources, List<String> originalOptions, List<String> effectiveOptions,
                          String profileDigest, String deadlineIdentity) {
        public Request {
            require(PROFILE.equals(profile), ErrorCode.UNSUPPORTED);
            nonnull(original); nonnull(effective); nonnull(snapshotRoot); nonnull(jobRoot);
            nonnull(sourceRoot); nonnull(outputRoot);
            sources = ApplicationWire.list(sources, 1);
            require(sources.size() == 1, ErrorCode.UNSUPPORTED);
            originalOptions = ApplicationWire.strings(originalOptions, 2, MAX_ID_BYTES);
            effectiveOptions = ApplicationWire.strings(effectiveOptions, 2, MAX_ID_BYTES);
            require(originalOptions.equals(M0_OPTIONS) && effectiveOptions.equals(M0_OPTIONS), ErrorCode.UNSUPPORTED);
            checkDigest(profileDigest); ApplicationWire.token(deadlineIdentity);
            String members = membersDigest(sources);
            require(members.equals(original.memberDigest) && members.equals(effective.memberDigest), ErrorCode.IDENTITY);
            requireDistinctRoots(snapshotRoot, jobRoot, sourceRoot, outputRoot);
            for (ApplicationWire.Root root : List.of(snapshotRoot, jobRoot, sourceRoot, outputRoot)) {
                require(!root.fileKey().equals(sources.get(0).fileKey), ErrorCode.IDENTITY);
            }
        }
    }

    /** Raw detached severity and ID are preserved; source text/locations are not normalized here. */
    public record Diagnostic(String severity, String message, String path, int line, int column,
                             String diagnosticId) {
        public Diagnostic {
            severityCode(severity);
            ApplicationWire.utf8(message, MAX_SOURCE_BYTES);
            ApplicationWire.text(path, MAX_PATH_BYTES, true);
            ApplicationWire.text(diagnosticId, MAX_KEY_BYTES, true);
            require(line >= -1 && column >= -1 && line != 0 && column != 0
                    && line <= MAX_SOURCE_BYTES + 1 && column <= MAX_SOURCE_BYTES + 1, ErrorCode.VALUE);
            if (path.isEmpty()) require(line == -1 && column == -1, ErrorCode.IDENTITY);
        }
    }

    /** These observations do not assert arbitrary static/thread/heap reset or extraction provenance. */
    public record Reset(boolean applicationNull, boolean environmentNull, boolean keepAliveDisabled,
                        boolean contextLoaderRestored) {
        public boolean complete() {
            return applicationNull && environmentNull && keepAliveDisabled && contextLoaderRestored;
        }
    }

    public enum FailureCode {
        NONE, COMPILER_INTERNAL, COMPILATION_CANCELLED, EXECUTE_THROWN, DISPOSE_THROWN,
        DISPOSAL_MISSING, DISPOSAL_DUPLICATE, RESET_FAILED, CALLBACK_POISON, RAW_IO,
        OWNERSHIP_CHANGED, INVALID_DIAGNOSTIC, UNSUPPORTED_PROFILE, INTERNAL_FAILURE
    }

    /** No per-job process exit or serializable permit. A sealed result is still M0/unqualified. */
    public record Observation(String compilerVersion, String argvDigest, String memberDigest,
                              ApplicationWire.Root jobRoot, ApplicationWire.Root sourceRoot,
                              ApplicationWire.Root outputRoot, boolean compilationReturned,
                              int compilerCode, int disposalCount, boolean disposalCompleted,
                              boolean cancellationObserved, Reset beforeReset, Reset afterReset,
                              boolean collectorSealed, boolean inputsRevalidated, boolean outputsRevalidated,
                              FailureCode failureCode, List<Diagnostic> diagnostics) {
        public Observation {
            require(COMPILER_VERSION.equals(compilerVersion) || "unobserved".equals(compilerVersion), ErrorCode.UNSUPPORTED);
            checkDigest(argvDigest); checkDigest(memberDigest);
            nonnull(jobRoot); nonnull(sourceRoot); nonnull(outputRoot);
            requireDistinctRoots(jobRoot, sourceRoot, outputRoot);
            require(disposalCount >= 0 && disposalCount <= 2, ErrorCode.LIMIT); // 2 is saturated duplicate.
            require(compilationReturned || compilerCode == -1, ErrorCode.VALUE);
            nonnull(beforeReset); nonnull(afterReset); nonnull(failureCode);
            diagnostics = diagnosticCopy(diagnostics);
            for (Diagnostic diagnostic : diagnostics) {
                require(!isCancellation(diagnostic) || cancellationObserved, ErrorCode.IDENTITY);
            }
        }
        public String diagnosticsDigest() { return CompilerJobWire.diagnosticsDigest(diagnostics); }
    }

    public static byte[] encodeGeneration(Generation value) {
        nonnull(value);
        ApplicationWire.Writer out = writer(GENERATION, ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        out.string(value.profile, MAX_ID_BYTES); out.string(value.apiVersion, MAX_ID_BYTES);
        out.string(value.compilerVersion, MAX_ID_BYTES); out.i32(value.jvmTarget);
        writeJdk(out, value.jdk); writeArtifact(out, value.bridge);
        writeArtifacts(out, value.compilerArtifacts);
        out.i32(value.dependencyIndexes.size());
        for (int index : value.dependencyIndexes) out.i32(index);
        return out.finish();
    }

    public static Generation decodeGeneration(byte[] bytes) {
        ApplicationWire.Reader in = reader(bytes, GENERATION, ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        String profile = in.string(MAX_ID_BYTES), api = in.string(MAX_ID_BYTES), compiler = in.string(MAX_ID_BYTES);
        int target = in.i32();
        Jdk jdk = readJdk(in); Artifact bridge = readArtifact(in);
        List<Artifact> artifacts = readArtifacts(in);
        int count = in.count(MAX_DEPENDENCIES);
        List<Integer> dependencies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) dependencies.add(in.i32());
        in.end();
        return new Generation(profile, api, compiler, target, jdk, bridge, artifacts, dependencies);
    }

    public static byte[] encodeRequest(Request value) {
        nonnull(value);
        ApplicationWire.Writer out = writer(REQUEST, ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(value.profile, MAX_ID_BYTES);
        writeInput(out, value.original); writeInput(out, value.effective);
        ApplicationWire.writeRoot(out, value.snapshotRoot); ApplicationWire.writeRoot(out, value.jobRoot);
        ApplicationWire.writeRoot(out, value.sourceRoot); ApplicationWire.writeRoot(out, value.outputRoot);
        out.i32(value.sources.size());
        for (Source source : value.sources) writeSource(out, source);
        writeOptions(out, value.originalOptions); writeOptions(out, value.effectiveOptions);
        out.string(value.profileDigest, 71); out.string(value.deadlineIdentity, 32);
        return out.finish();
    }

    public static Request decodeRequest(byte[] bytes) {
        ApplicationWire.Reader in = reader(bytes, REQUEST, ApplicationWire.MAX_JOB_BODY_BYTES);
        String profile = in.string(MAX_ID_BYTES);
        InputIdentity original = readInput(in), effective = readInput(in);
        ApplicationWire.Root snapshot = ApplicationWire.readRoot(in), job = ApplicationWire.readRoot(in),
                source = ApplicationWire.readRoot(in), output = ApplicationWire.readRoot(in);
        int count = in.count(1);
        List<Source> sources = new ArrayList<>(count);
        for (int i = 0; i < count; i++) sources.add(readSource(in));
        List<String> originalOptions = readOptions(in), effectiveOptions = readOptions(in);
        String profileDigest = in.string(71), deadline = in.string(32);
        in.end();
        return new Request(profile, original, effective, snapshot, job, source, output, sources,
                originalOptions, effectiveOptions, profileDigest, deadline);
    }

    public static byte[] encodeObservation(Observation value) {
        nonnull(value);
        ApplicationWire.Writer out = writer(OBSERVATION, ApplicationWire.MAX_RESULT_BODY_BYTES);
        out.string(value.compilerVersion, MAX_ID_BYTES); out.string(value.argvDigest, 71);
        out.string(value.memberDigest, 71);
        ApplicationWire.writeRoot(out, value.jobRoot); ApplicationWire.writeRoot(out, value.sourceRoot);
        ApplicationWire.writeRoot(out, value.outputRoot);
        out.bool(value.compilationReturned); out.i32(value.compilerCode); out.i32(value.disposalCount);
        out.bool(value.disposalCompleted); out.bool(value.cancellationObserved);
        writeReset(out, value.beforeReset); writeReset(out, value.afterReset);
        out.bool(value.collectorSealed); out.bool(value.inputsRevalidated); out.bool(value.outputsRevalidated);
        out.u8(value.failureCode.ordinal() + 1);
        out.string(value.diagnosticsDigest(), 71);
        writeDiagnostics(out, value.diagnostics);
        return out.finish();
    }

    public static Observation decodeObservation(byte[] bytes) {
        ApplicationWire.Reader in = reader(bytes, OBSERVATION, ApplicationWire.MAX_RESULT_BODY_BYTES);
        String compiler = in.string(MAX_ID_BYTES), argv = in.string(71), members = in.string(71);
        ApplicationWire.Root job = ApplicationWire.readRoot(in), source = ApplicationWire.readRoot(in),
                output = ApplicationWire.readRoot(in);
        boolean returned = in.bool(); int code = in.i32(), disposalCount = in.i32();
        boolean disposalCompleted = in.bool(), cancelled = in.bool();
        Reset before = readReset(in), after = readReset(in);
        boolean sealed = in.bool(), inputs = in.bool(), outputs = in.bool();
        FailureCode failure = ApplicationWire.enumValue(FailureCode.values(), in.u8());
        String declaredDiagnostics = in.string(71); checkDigest(declaredDiagnostics);
        List<Diagnostic> diagnostics = readDiagnostics(in);
        in.end();
        require(declaredDiagnostics.equals(diagnosticsDigest(diagnostics)), ErrorCode.DIGEST);
        return new Observation(compiler, argv, members, job, source, output, returned, code, disposalCount,
                disposalCompleted, cancelled, before, after, sealed, inputs, outputs, failure, diagnostics);
    }

    public static String compilerClosureDigest(Generation value) {
        nonnull(value);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        out.string(VERSION, MAX_ID_BYTES); writeArtifacts(out, value.compilerArtifacts);
        return ApplicationWire.digest("compiler-ordered-closure", out.finish());
    }

    /** The admitted JDK/runtime/executable descriptor, NOT the framework installation/model digest. */
    public static String toolchainDigest(Generation value) {
        nonnull(value);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        out.string(VERSION, MAX_ID_BYTES); writeJdk(out, value.jdk);
        return ApplicationWire.digest("compiler-jdk-descriptor", out.finish());
    }

    public static String profileDigest(Generation value) {
        nonnull(value);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_JOB_BODY_BYTES);
        out.bytes(encodeGeneration(value), ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        writeOptions(out, M0_OPTIONS);
        out.i32(MAX_SOURCE_BYTES); out.i32(MAX_DIAGNOSTICS); out.i32(MAX_DIAGNOSTIC_BYTES);
        out.i32(MAX_JOB_OUTPUT_FILES); out.i64(MAX_JOB_OUTPUT_BYTES); out.i64(MAX_GENERATION_OUTPUT_BYTES);
        out.i32(MAX_TEMP_FILES); out.i64(MAX_TEMP_BYTES);
        return ApplicationWire.digest("compiler-m0-profile", out.finish());
    }

    /** Ordered logical manifest only; the full request additionally binds physical roots/file keys. */
    public static String membersDigest(List<Source> values) {
        List<Source> sources = ApplicationWire.list(values, 1);
        require(sources.size() == 1, ErrorCode.UNSUPPORTED);
        ApplicationWire.Writer out = new ApplicationWire.Writer(1_024);
        out.string(VERSION, MAX_ID_BYTES); out.i32(sources.size());
        for (Source source : sources) {
            out.string(source.logicalPath, MAX_ID_BYTES); out.string(source.digest, 71); out.i64(source.size);
        }
        return ApplicationWire.digest("compiler-source-members", out.finish());
    }

    public static String diagnosticsDigest(List<Diagnostic> values) {
        List<Diagnostic> diagnostics = diagnosticCopy(values);
        ApplicationWire.Writer out = new ApplicationWire.Writer(MAX_DIAGNOSTIC_BYTES + 256);
        out.string(VERSION, MAX_ID_BYTES); writeDiagnostics(out, diagnostics);
        return ApplicationWire.digest("compiler-diagnostics", out.finish());
    }

    /** Fixed TEST-profile launch, not an API for arbitrary JVM flags, agents, argfiles or plugins. */
    public static List<String> command(ApplicationWire.Root generationRoot, Generation generation) {
        nonnull(generationRoot); nonnull(generation);
        return List.of(generation.jdk.executable, "-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.language=en",
                "-Duser.country=US", "-Djava.awt.headless=true", "-Dkotlin.environment.keepalive=false",
                "-Dkotlin.colors.enabled=false", "-Djava.io.tmpdir=" + resolve(generationRoot, "tmp"),
                "-cp", resolve(generationRoot, "bridge.jar"), MAIN_CLASS, resolve(generationRoot, "launch.bin"));
    }

    /** Exact replacement environment, not augmentation of inherited Java/loader options. */
    public static Map<String, String> environment(ApplicationWire.Root generationRoot, Generation generation) {
        nonnull(generationRoot); nonnull(generation);
        return Map.of("LANG", "C.UTF-8", "LC_ALL", "C.UTF-8", "HOME", resolve(generationRoot, "tmp"),
                "TMPDIR", resolve(generationRoot, "tmp"), "PATH", resolve(generation.jdk.home, "bin"));
    }

    public static List<String> arguments(ApplicationWire.Launch launch, Generation generation, Request request) {
        matchGeneration(launch, generation); nonnull(request);
        require(PROFILE.equals(request.profile) && profileDigest(generation).equals(request.profileDigest), ErrorCode.IDENTITY);
        ArrayList<String> values = new ArrayList<>(16);
        values.add("-no-stdlib"); values.add("-no-reflect"); values.add("-Xrender-internal-diagnostic-names");
        values.addAll(request.effectiveOptions);
        values.add("-jdk-home"); values.add(generation.jdk.home.path());
        values.add("-d"); values.add(request.outputRoot.path());
        values.add("-classpath");
        List<String> dependencies = new ArrayList<>(generation.dependencyIndexes.size());
        for (int index : generation.dependencyIndexes) dependencies.add(resolve(launch.generationRoot(), "compiler/" + index + ".jar"));
        values.add(String.join(File.pathSeparator, dependencies));
        values.add(resolve(request.sourceRoot, SOURCE_NAME));
        return List.copyOf(values);
    }

    public static String argvDigest(List<String> values) {
        List<String> argv = ApplicationWire.strings(values, 64, MAX_PATH_BYTES * MAX_DEPENDENCIES);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(VERSION, MAX_ID_BYTES); out.i32(argv.size());
        for (String argument : argv) out.string(argument, MAX_PATH_BYTES * MAX_DEPENDENCIES);
        return ApplicationWire.digest("compiler-effective-argv", out.finish());
    }

    public static void matchGeneration(ApplicationWire.Launch launch, Generation generation) {
        nonnull(launch); nonnull(generation);
        ApplicationWire.Identity identity = launch.identity();
        require(identity.adapterVersion().equals(API) && identity.profile().equals(PROFILE)
                && identity.bridgeDigest().equals(generation.bridge.digest)
                && identity.compilerClosureDigest().equals(compilerClosureDigest(generation))
                && identity.toolchainDigest().equals(toolchainDigest(generation))
                && Arrays.equals(launch.payload(), encodeGeneration(generation)), ErrorCode.IDENTITY);
        require(launch.directory().equals(launch.generationRoot().path())
                && launch.command().equals(command(launch.generationRoot(), generation))
                && launch.environment().equals(environment(launch.generationRoot(), generation)), ErrorCode.UNSUPPORTED);
        // No argument-list separator may turn a declared single path into extra classpath entries.
        require(launch.generationRoot().path().indexOf(File.pathSeparatorChar) < 0
                && generation.jdk.home.path().indexOf(File.pathSeparatorChar) < 0, ErrorCode.PATH);
        require(!launch.generationRoot().fileKey().equals(generation.jdk.home.fileKey()), ErrorCode.IDENTITY);
        for (Artifact artifact : generation.compilerArtifacts) {
            require(!launch.generationRoot().fileKey().equals(artifact.fileKey), ErrorCode.IDENTITY);
        }
        require(!launch.generationRoot().fileKey().equals(generation.bridge.fileKey), ErrorCode.IDENTITY);
    }

    public static void matchHello(ApplicationWire.Launch launch, Generation generation, ApplicationWire.Hello hello) {
        matchGeneration(launch, generation); ApplicationWire.matchHello(launch, hello);
        require(COMPILER_VERSION.equals(hello.observedVersion()), ErrorCode.UNSUPPORTED);
    }

    public static void matchRequest(ApplicationWire.Launch launch, Generation generation,
                                    ApplicationWire.Job job, Request request) {
        matchGeneration(launch, generation); nonnull(job); nonnull(request);
        require(job.generation().equals(launch.identity().generation()) && job.sequence() <= launch.limits().maxJobs()
                && job.originalKey().equals(request.original.analysisKey)
                && job.effectiveKey().equals(request.effective.analysisKey)
                && job.deadlineIdentity().equals(request.deadlineIdentity)
                && Arrays.equals(job.payload(), encodeRequest(request))
                && profileDigest(generation).equals(request.profileDigest), ErrorCode.IDENTITY);
        String relativeJob = "jobs/" + job.sequence();
        require(request.jobRoot.path().equals(resolve(launch.generationRoot(), relativeJob))
                && request.sourceRoot.path().equals(resolve(launch.generationRoot(), relativeJob + "/sources"))
                && request.outputRoot.path().equals(resolve(launch.generationRoot(), relativeJob + "/classes")), ErrorCode.PATH);
        Set<String> reservedKeys = new HashSet<>(), reservedPaths = new HashSet<>();
        reservedKeys.add(launch.generationRoot().fileKey()); reservedKeys.add(generation.jdk.home.fileKey());
        reservedKeys.add(generation.bridge.fileKey);
        reservedPaths.add(launch.generationRoot().path()); reservedPaths.add(generation.jdk.home.path());
        reservedPaths.add(resolve(launch.generationRoot(), "bridge.jar"));
        for (int i = 0; i < generation.compilerArtifacts.size(); i++) {
            reservedKeys.add(generation.compilerArtifacts.get(i).fileKey);
            reservedPaths.add(resolve(launch.generationRoot(), "compiler/" + i + ".jar"));
        }
        reservedPaths.add(resolve(request.sourceRoot, SOURCE_NAME));
        for (ApplicationWire.Root root : List.of(request.snapshotRoot, request.jobRoot, request.sourceRoot, request.outputRoot)) {
            require(!reservedKeys.contains(root.fileKey()) && !reservedPaths.contains(root.path()), ErrorCode.IDENTITY);
        }
        require(!reservedKeys.contains(request.sources.get(0).fileKey), ErrorCode.IDENTITY);
    }

    public static void matchObservation(ApplicationWire.Launch launch, Generation generation,
                                        ApplicationWire.Job job, Request request, ApplicationWire.Result result,
                                        Observation observation) {
        matchRequest(launch, generation, job, request); nonnull(result); nonnull(observation);
        require(result.generation().equals(job.generation()) && result.helloDigest().equals(job.helloDigest())
                && result.sequence() == job.sequence() && result.jobId().equals(job.jobId())
                && result.requestDigest().equals(ApplicationWire.requestDigest(job))
                && result.originalKey().equals(job.originalKey()) && result.effectiveKey().equals(job.effectiveKey())
                && result.state() == ApplicationWire.ResultState.APPLICATION_SEALED
                && Arrays.equals(result.payload(), encodeObservation(observation)), ErrorCode.IDENTITY);
        require(observation.jobRoot.equals(request.jobRoot) && observation.sourceRoot.equals(request.sourceRoot)
                && observation.outputRoot.equals(request.outputRoot)
                && observation.memberDigest.equals(membersDigest(request.sources))
                && observation.argvDigest.equals(argvDigest(arguments(launch, generation, request))), ErrorCode.IDENTITY);
        String physicalSource = resolve(request.sourceRoot, SOURCE_NAME);
        for (Diagnostic diagnostic : observation.diagnostics) {
            require(diagnostic.path.isEmpty() || diagnostic.path.equals(SOURCE_NAME)
                    || diagnostic.path.equals(physicalSource), ErrorCode.PATH);
            if (!diagnostic.path.isEmpty()) {
                require(diagnostic.line >= 1 && diagnostic.column >= 1
                        && diagnostic.line <= request.sources.get(0).size + 1
                        && diagnostic.column <= request.sources.get(0).size + 1, ErrorCode.IDENTITY);
            }
            if (diagnostic.severity.equals("ERROR")) require(!diagnostic.path.isEmpty(), ErrorCode.IDENTITY);
        }
        requireSealed(observation);
        // Exact loader/HELLO identity is ALSO mandatory via ApplicationWire.matchResult by the
        // runtime/worker session holding the observed Hello; this DTO codec never invents one.
    }

    public static void requireSealed(Observation value) {
        nonnull(value);
        require(COMPILER_VERSION.equals(value.compilerVersion) && value.compilationReturned
                && (value.compilerCode == 0 || value.compilerCode == 1) && value.disposalCount == 1
                && value.disposalCompleted && !value.cancellationObserved && value.beforeReset.complete()
                && value.afterReset.complete() && value.collectorSealed && value.inputsRevalidated
                && value.outputsRevalidated && value.failureCode == FailureCode.NONE, ErrorCode.UNSEALED);
        boolean errors = false;
        for (Diagnostic diagnostic : value.diagnostics) {
            require(!diagnostic.severity.equals("EXCEPTION") && !diagnostic.severity.equals("OUTPUT")
                    && !isCancellation(diagnostic), ErrorCode.UNSEALED);
            if (diagnostic.severity.equals("ERROR")) {
                errors = true;
                require(!diagnostic.diagnosticId.isEmpty(), ErrorCode.UNSEALED);
            }
        }
        require(value.compilerCode != 0 || !errors, ErrorCode.UNSEALED);
        require(value.compilerCode != 1 || errors, ErrorCode.UNSEALED);
    }

    public static void validateDiagnostic(Diagnostic value) {
        nonnull(value);
        // Records are final/validated and Strings immutable; call sites get a fixed-code null check.
        require(diagnosticSize(value) <= MAX_DIAGNOSTIC_BYTES, ErrorCode.LIMIT);
    }

    private static void requireDistinctRoots(ApplicationWire.Root... roots) {
        Set<String> paths = new HashSet<>(), keys = new HashSet<>();
        for (ApplicationWire.Root root : roots) {
            nonnull(root);
            require(paths.add(root.path()) && keys.add(root.fileKey()), ErrorCode.DUPLICATE);
        }
    }

    private static String resolve(ApplicationWire.Root root, String relative) {
        return Path.of(root.path()).resolve(relative).toString();
    }

    private static List<Diagnostic> diagnosticCopy(List<Diagnostic> values) {
        List<Diagnostic> diagnostics = ApplicationWire.list(values, MAX_DIAGNOSTICS);
        long total = 4; // count is part of the bounded diagnostic payload.
        for (Diagnostic diagnostic : diagnostics) {
            int added = diagnosticSize(diagnostic);
            require(added <= MAX_DIAGNOSTIC_BYTES - total, ErrorCode.LIMIT);
            total += added;
        }
        return diagnostics;
    }

    private static int diagnosticSize(Diagnostic value) {
        nonnull(value);
        return 1 + 4 + ApplicationWire.utf8(value.message, MAX_SOURCE_BYTES).length
                + 4 + ApplicationWire.utf8(value.path, MAX_PATH_BYTES).length + 4 + 4
                + 4 + ApplicationWire.utf8(value.diagnosticId, MAX_KEY_BYTES).length;
    }

    private static boolean isCancellation(Diagnostic value) {
        return value.message.equals("Compilation was canceled") || value.message.equals("Compilation was cancelled");
    }

    private static int severityCode(String severity) {
        nonnull(severity);
        return switch (severity) {
            case "EXCEPTION" -> 1;
            case "ERROR" -> 2;
            case "STRONG_WARNING" -> 3;
            case "MANDATORY_WARNING" -> 4;
            case "WARNING" -> 5;
            case "INFO" -> 6;
            case "LOGGING" -> 7;
            case "OUTPUT" -> 8;
            default -> throw failure(ErrorCode.ENUM);
        };
    }

    private static String severity(int code) {
        return switch (code) {
            case 1 -> "EXCEPTION";
            case 2 -> "ERROR";
            case 3 -> "STRONG_WARNING";
            case 4 -> "MANDATORY_WARNING";
            case 5 -> "WARNING";
            case 6 -> "INFO";
            case 7 -> "LOGGING";
            case 8 -> "OUTPUT";
            default -> throw failure(ErrorCode.ENUM);
        };
    }

    private static ApplicationWire.Writer writer(int kind, int maximum) {
        ApplicationWire.Writer out = new ApplicationWire.Writer(maximum);
        out.u8(kind); out.string(VERSION, MAX_ID_BYTES);
        return out;
    }

    private static ApplicationWire.Reader reader(byte[] bytes, int kind, int maximum) {
        ApplicationWire.Reader in = new ApplicationWire.Reader(bytes, maximum);
        require(in.u8() == kind, ErrorCode.KIND);
        require(in.string(MAX_ID_BYTES).equals(VERSION), ErrorCode.VERSION);
        return in;
    }

    private static void writeArtifact(ApplicationWire.Writer out, Artifact artifact) {
        out.string(artifact.coordinate, MAX_ID_BYTES); out.string(artifact.fileName, MAX_ID_BYTES);
        out.string(artifact.digest, 71); out.i64(artifact.size); out.string(artifact.fileKey, MAX_KEY_BYTES);
    }

    private static Artifact readArtifact(ApplicationWire.Reader in) {
        return new Artifact(in.string(MAX_ID_BYTES), in.string(MAX_ID_BYTES), in.string(71),
                in.i64(), in.string(MAX_KEY_BYTES));
    }

    private static void writeArtifacts(ApplicationWire.Writer out, List<Artifact> values) {
        out.i32(values.size());
        for (Artifact artifact : values) writeArtifact(out, artifact);
    }

    private static List<Artifact> readArtifacts(ApplicationWire.Reader in) {
        int count = in.count(MAX_ARTIFACTS);
        List<Artifact> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(readArtifact(in));
        return result;
    }

    private static void writeJdk(ApplicationWire.Writer out, Jdk value) {
        ApplicationWire.writeRoot(out, value.home); out.string(value.executable, MAX_PATH_BYTES);
        out.string(value.executableDigest, 71); out.string(value.runtimeDigest, 71); out.i32(value.major);
    }

    private static Jdk readJdk(ApplicationWire.Reader in) {
        return new Jdk(ApplicationWire.readRoot(in), in.string(MAX_PATH_BYTES), in.string(71), in.string(71), in.i32());
    }

    private static void writeInput(ApplicationWire.Writer out, InputIdentity value) {
        out.string(value.analysisKey, 71); out.string(value.snapshotDigest, 71); out.string(value.memberDigest, 71);
        out.string(value.modelDigest, 71); out.string(value.dependencyDigest, 71);
        out.string(value.configurationDigest, 71); out.string(value.toolchainDigest, 71);
    }

    private static InputIdentity readInput(ApplicationWire.Reader in) {
        return new InputIdentity(in.string(71), in.string(71), in.string(71), in.string(71), in.string(71), in.string(71), in.string(71));
    }

    private static void writeSource(ApplicationWire.Writer out, Source value) {
        out.string(value.logicalPath, MAX_ID_BYTES); out.string(value.digest, 71);
        out.i64(value.size); out.string(value.fileKey, MAX_KEY_BYTES);
    }

    private static Source readSource(ApplicationWire.Reader in) {
        return new Source(in.string(MAX_ID_BYTES), in.string(71), in.i64(), in.string(MAX_KEY_BYTES));
    }

    private static void writeOptions(ApplicationWire.Writer out, List<String> values) {
        out.i32(values.size());
        for (String value : values) out.string(value, MAX_ID_BYTES);
    }

    private static List<String> readOptions(ApplicationWire.Reader in) {
        int count = in.count(2);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(in.string(MAX_ID_BYTES));
        return values;
    }

    private static void writeReset(ApplicationWire.Writer out, Reset value) {
        out.bool(value.applicationNull); out.bool(value.environmentNull);
        out.bool(value.keepAliveDisabled); out.bool(value.contextLoaderRestored);
    }

    private static Reset readReset(ApplicationWire.Reader in) { return new Reset(in.bool(), in.bool(), in.bool(), in.bool()); }

    private static void writeDiagnostics(ApplicationWire.Writer out, List<Diagnostic> values) {
        out.i32(values.size());
        for (Diagnostic value : values) {
            out.u8(severityCode(value.severity)); out.string(value.message, MAX_SOURCE_BYTES);
            out.string(value.path, MAX_PATH_BYTES); out.i32(value.line); out.i32(value.column);
            out.string(value.diagnosticId, MAX_KEY_BYTES);
        }
    }

    private static List<Diagnostic> readDiagnostics(ApplicationWire.Reader in) {
        int before = in.remaining();
        int count = in.count(MAX_DIAGNOSTICS);
        List<Diagnostic> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Diagnostic value = new Diagnostic(severity(in.u8()), in.string(MAX_SOURCE_BYTES), in.string(MAX_PATH_BYTES),
                    in.i32(), in.i32(), in.string(MAX_KEY_BYTES));
            require(before - in.remaining() <= MAX_DIAGNOSTIC_BYTES, ErrorCode.LIMIT);
            values.add(value);
        }
        return values;
    }
}
