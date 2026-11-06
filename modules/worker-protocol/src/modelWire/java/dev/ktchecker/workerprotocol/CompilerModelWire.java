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
 * The explicitly unsupported-by-default bounded JVM-model compiler DTO profile. JDK ONLY: no compiler classes,
 * loaders, reflection, filesystem operations, model permits or execution capabilities live here.
 * Whole snapshot membership and the exact compilation subset remain distinct. A wider profile
 * needs a new reviewed version, never guessed flags, normalized model inputs or fallback.
 */
public final class CompilerModelWire {
    private CompilerModelWire() {}

    public static final String VERSION = "ktchecker/compiler-model-job/preview-1";
    public static final String PROFILE = "ktchecker/compiler-jvm-model/preview-1";
    public static final String PRODUCED_PROFILE = "ktchecker/compiler-jvm-model/produced-preview-1";
    public static final String PRODUCED_API = "kotlin-2.4.21/caller-owned-JvmCliPipeline-produced-1";
    public static final String API = "kotlin-2.4.21/caller-owned-JvmCliPipeline-model-1";
    public static final String COMPILER_VERSION = "2.4.21";
    public static final String COMPILER_SHA256 = "sha256:ef19419c765e7ac8404465fa026ca8fc4dbb8a822de0fc79aa53c8dce29f1d02";
    public static final String MAIN_CLASS = "dev.ktchecker.compilerworker.live.CompilerModelApplicationMain";
    public static final int MAX_SNAPSHOT_FILES = 256;
    public static final int MAX_SOURCE_BYTES = 1_048_576;
    public static final long MAX_SNAPSHOT_BYTES = 16_777_216L;
    public static final int MAX_COMPILATION_FILES = 64;
    public static final int MAX_DEPENDENCIES = 16;
    public static final long MAX_DEPENDENCY_BYTES = 67_108_864L;
    public static final long MAX_DEPENDENCY_TOTAL_BYTES = 268_435_456L;
    public static final int MAX_LOGICAL_PATH_BYTES = 512;
    public static final int MAX_PATH_DEPTH = 16;
    public static final int MAX_OPTIONS = 16;
    public static final int MAX_DIAGNOSTICS = 512;
    public static final int MAX_DIAGNOSTIC_MESSAGE_BYTES = 16_384;
    public static final int MAX_DIAGNOSTIC_BYTES = 98_304;
    public static final int MAX_ARTIFACTS = 16;
    public static final long MAX_ARTIFACT_BYTES = 67_108_864L;
    public static final long MAX_ARTIFACT_TOTAL_BYTES = 134_217_728L;
    public static final long MAX_BRIDGE_BYTES = 4_194_304L;
    public static final int MAX_JOB_OUTPUT_FILES = 4_096;
    public static final long MAX_JOB_OUTPUT_BYTES = 67_108_864L;
    public static final int MAX_GENERATION_OUTPUT_FILES = 16_384;
    public static final long MAX_GENERATION_OUTPUT_BYTES = 268_435_456L;
    public static final int MAX_TEMP_FILES = 1_024;
    public static final long MAX_TEMP_BYTES = 33_554_432L;
    public static final long MAX_GENERATION_SNAPSHOT_BYTES = 67_108_864L;
    public static final long MAX_GENERATION_DEPENDENCY_BYTES = 536_870_912L;
    private static final int MAX_ARGV = 128;
    private static final int MAX_ARGV_BYTES = ApplicationWire.MAX_RESULT_BODY_BYTES;
    private static final int GENERATION = 1, REQUEST = 2, OBSERVATION = 3, PRODUCED_REQUEST = 4;

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

    public record Generation(String profile, String apiVersion, String compilerVersion,
                             Jdk jdk, Artifact bridge, List<Artifact> compilerArtifacts) {
        public Generation {
            require(((PROFILE.equals(profile) && API.equals(apiVersion)) ||
                    (PRODUCED_PROFILE.equals(profile) && PRODUCED_API.equals(apiVersion)))
                    && COMPILER_VERSION.equals(compilerVersion), ErrorCode.UNSUPPORTED);
            nonnull(jdk); nonnull(bridge);
            require(bridge.size <= MAX_BRIDGE_BYTES, ErrorCode.LIMIT);
            compilerArtifacts = ApplicationWire.list(compilerArtifacts, MAX_ARTIFACTS);
            require(!compilerArtifacts.isEmpty(), ErrorCode.VALUE);
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
        }
    }

    /** Framework snapshot/model identities survive relocation; memberDigest is the whole logical manifest. */
    public record InputIdentity(String analysisKey, String snapshotDigest, String memberDigest,
                                String modelDigest, String dependencyDigest, String configurationDigest,
                                String toolchainDigest) {
        public InputIdentity {
            checkDigest(analysisKey); checkDigest(snapshotDigest); checkDigest(memberDigest);
            checkDigest(modelDigest); checkDigest(dependencyDigest); checkDigest(configurationDigest);
            checkDigest(toolchainDigest);
        }
    }

    /** Every snapshot member, including noncompiled model/build/resource bytes, in original order. */
    public record Member(String logicalPath, String digest, long size, String fileKey) {
        public Member {
            CompilerModelWire.logicalPath(logicalPath); checkDigest(digest); ApplicationWire.key(fileKey);
            require(size >= 0 && size <= MAX_SOURCE_BYTES, ErrorCode.LIMIT);
        }
    }

    /** One model classpath occurrence. Repeated coordinates/digests are not sorted or deduplicated. */
    public record Dependency(String coordinate, String digest, long size, String fileKey) {
        public Dependency {
            ApplicationWire.identifier(coordinate); checkDigest(digest); ApplicationWire.key(fileKey);
            require(size > 0 && size <= MAX_DEPENDENCY_BYTES, ErrorCode.LIMIT);
        }
    }

    /** Detached lineage, never a host producer-success capability. A worker ALSO checks its own prior result/tree. */
    public record ProducedDependency(int classpathIndex, String producerCompilationId, String referenceDigest,
                                     String generation, int producerSequence, String producerBindingDigest,
                                     String requestDigest, String resultDigest, String outputDigest, boolean friend) {
        public ProducedDependency {
            require(classpathIndex >= 0 && classpathIndex < MAX_DEPENDENCIES, ErrorCode.LIMIT);
            modelCompilationId(producerCompilationId); checkDigest(referenceDigest);
            ApplicationWire.token(generation); require(producerSequence > 0 && producerSequence <= ApplicationWire.MAX_JOBS, ErrorCode.LIMIT);
            checkDigest(producerBindingDigest); checkDigest(requestDigest); checkDigest(resultDigest); checkDigest(outputDigest);
        }
    }

    public record ProducedExecution(String planDigest, String compilationId, String rawContextDigest,
                                    String resolutionDigest, List<ProducedDependency> producers) {
        public ProducedExecution {
            checkDigest(planDigest); modelCompilationId(compilationId); checkDigest(rawContextDigest); checkDigest(resolutionDigest);
            producers = ApplicationWire.list(producers, 1); // First reviewed vertical slice: one actual producer edge.
            for (ProducedDependency producer : producers) require(!producer.producerCompilationId.equals(compilationId), ErrorCode.IDENTITY);
        }
    }

    /**
     * Exact exporter identity: Gradle project path | target name | compilation name.
     * In particular the JVM plugin's root main compilation is ":||main". This is
     * opaque lineage metadata, NOT an artifact coordinate or a filesystem path.
     * Validation never normalizes names or grants model/execution authority.
     */
    static void modelCompilationId(String value) {
        ApplicationWire.text(value, MAX_ID_BYTES, false);
        String[] parts = value.split("\\|", -1);
        require(parts.length == 3, ErrorCode.VALUE);
        require(parts[0].startsWith(":") && (parts[0].equals(":") ||
                (!parts[0].endsWith(":") && !parts[0].contains("::"))), ErrorCode.VALUE);
        require((parts[1].isEmpty() || !parts[1].isBlank()) && !parts[2].isBlank(), ErrorCode.VALUE);
    }

    /** Identifier-safe byte-artifact label; the raw compilation ID stays in ProducedExecution. */
    public static String producedArtifactCoordinate(String compilationId) {
        modelCompilationId(compilationId);
        return "produced-jvm:" + ApplicationWire.sha256(ApplicationWire.utf8(compilationId, MAX_ID_BYTES)).substring(7);
    }

    /** Complete logical compiler-output inventory, including directories and Kotlin module metadata. */
    public record ProducedMember(String path, boolean directory, long size, String digest) {
        public ProducedMember {
            logicalPath(path); require(size >= 0 && size <= MAX_JOB_OUTPUT_BYTES, ErrorCode.LIMIT);
            if (directory) require(size == 0 && digest == null, ErrorCode.VALUE);
            else {
                checkDigest(digest);
                require(path.endsWith(".class") || (path.startsWith("META-INF/") && path.endsWith(".kotlin_module")), ErrorCode.UNSUPPORTED);
            }
        }
    }

    /** Streaming bounded inventory hash; inventories cannot inflate the unchanged request frame. */
    public static String producedOutputDigest(List<ProducedMember> values) {
        List<ProducedMember> members = ApplicationWire.list(values, MAX_JOB_OUTPUT_FILES);
        require(!members.isEmpty(), ErrorCode.VALUE);
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        digest.update(ApplicationWire.utf8("ktchecker/complete-produced-jvm-tree/1", MAX_ID_BYTES));
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(members.size()).array());
        Set<String> directories = new HashSet<>(), names = new HashSet<>();
        long bytes = 0; String previous = ""; boolean hasClass = false;
        for (ProducedMember member : members) {
            require(previous.compareTo(member.path) < 0 && names.add(member.path), ErrorCode.DUPLICATE); previous = member.path;
            String parent = member.path;
            while (parent.lastIndexOf('/') >= 0) {
                parent = parent.substring(0, parent.lastIndexOf('/'));
                require(directories.contains(parent), ErrorCode.PATH);
            }
            if (member.directory) directories.add(member.path); else hasClass |= member.path.endsWith(".class");
            require(member.size <= MAX_JOB_OUTPUT_BYTES - bytes, ErrorCode.LIMIT); bytes += member.size;
            ApplicationWire.Writer entry = new ApplicationWire.Writer(1_024);
            entry.string(member.path, MAX_LOGICAL_PATH_BYTES); entry.bool(member.directory); entry.i64(member.size);
            if (!member.directory) entry.string(member.digest, 71);
            byte[] encoded = entry.finish(); digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array()); digest.update(encoded);
        }
        require(hasClass, ErrorCode.UNSUPPORTED);
        return "sha256:" + java.util.HexFormat.of().formatHex(digest.digest());
    }

    public record Request(String profile, String ownerInputKey, String archivedInputKey, String modelBindingDigest,
                          InputIdentity original, InputIdentity effective,
                          ApplicationWire.Root snapshotRoot, ApplicationWire.Root originalClasspathRoot,
                          ApplicationWire.Root originalOutputRoot, ApplicationWire.Root jobRoot,
                          ApplicationWire.Root sourceRoot, ApplicationWire.Root classpathRoot,
                          ApplicationWire.Root outputRoot, List<Member> files, List<String> compilationPaths,
                          List<Dependency> dependencies, List<String> originalOptions, List<String> effectiveOptions,
                          String profileDigest, String deadlineIdentity, ProducedExecution producedExecution) {
        /** Legacy source/binary vocabulary remains explicitly separate and keeps its original encoding. */
        public Request(String profile, String ownerInputKey, String archivedInputKey, String modelBindingDigest,
                       InputIdentity original, InputIdentity effective, ApplicationWire.Root snapshotRoot,
                       ApplicationWire.Root originalClasspathRoot, ApplicationWire.Root originalOutputRoot,
                       ApplicationWire.Root jobRoot, ApplicationWire.Root sourceRoot, ApplicationWire.Root classpathRoot,
                       ApplicationWire.Root outputRoot, List<Member> files, List<String> compilationPaths,
                       List<Dependency> dependencies, List<String> originalOptions, List<String> effectiveOptions,
                       String profileDigest, String deadlineIdentity) {
            this(profile, ownerInputKey, archivedInputKey, modelBindingDigest, original, effective, snapshotRoot,
                originalClasspathRoot, originalOutputRoot, jobRoot, sourceRoot, classpathRoot, outputRoot,
                files, compilationPaths, dependencies, originalOptions, effectiveOptions, profileDigest, deadlineIdentity, null);
        }
        public Request {
            require((PROFILE.equals(profile) && producedExecution == null) ||
                (PRODUCED_PROFILE.equals(profile) && producedExecution != null), ErrorCode.UNSUPPORTED);
            checkDigest(ownerInputKey); checkDigest(archivedInputKey); checkDigest(modelBindingDigest);
            nonnull(original); nonnull(effective); nonnull(snapshotRoot); nonnull(originalOutputRoot);
            nonnull(jobRoot); nonnull(sourceRoot); nonnull(classpathRoot); nonnull(outputRoot);
            files = memberCopy(files);
            compilationPaths = compilationCopy(files, compilationPaths);
            dependencies = dependencyCopy(dependencies);
            require((originalClasspathRoot == null) == dependencies.isEmpty(), ErrorCode.IDENTITY);
            originalOptions = validateOptions(originalOptions); effectiveOptions = validateOptions(effectiveOptions);
            require(originalOptions.equals(effectiveOptions), ErrorCode.UNSUPPORTED);
            checkDigest(profileDigest); ApplicationWire.token(deadlineIdentity);
            String members = membersDigest(files);
            require(members.equals(original.memberDigest) && members.equals(effective.memberDigest)
                    && original.snapshotDigest.equals(effective.snapshotDigest)
                    && original.modelDigest.equals(effective.modelDigest)
                    && original.dependencyDigest.equals(effective.dependencyDigest)
                    && original.configurationDigest.equals(effective.configurationDigest)
                    && original.toolchainDigest.equals(effective.toolchainDigest), ErrorCode.IDENTITY);
            List<ApplicationWire.Root> roots = roots(snapshotRoot, originalClasspathRoot, originalOutputRoot,
                    jobRoot, sourceRoot, classpathRoot, outputRoot);
            requireDistinctRoots(roots.toArray(ApplicationWire.Root[]::new));
            Set<String> keys = new HashSet<>();
            for (ApplicationWire.Root root : roots) keys.add(root.fileKey());
            for (Member file : files) require(keys.add(file.fileKey), ErrorCode.DUPLICATE);
            for (Dependency dependency : dependencies) require(keys.add(dependency.fileKey), ErrorCode.DUPLICATE);
            if (producedExecution != null) {
                require(producedExecution.resolutionDigest.equals(original.dependencyDigest), ErrorCode.IDENTITY);
                for (ProducedDependency producer : producedExecution.producers)
                    require(producer.classpathIndex < dependencies.size(), ErrorCode.IDENTITY);
            }
            // All per-item maxima cannot be combined to evade the existing application frame cap.
            encodeRequestFields(profile, ownerInputKey, archivedInputKey, modelBindingDigest, original, effective,
                    snapshotRoot, originalClasspathRoot, originalOutputRoot, jobRoot, sourceRoot, classpathRoot,
                    outputRoot, files, compilationPaths, dependencies, originalOptions, effectiveOptions,
                    profileDigest, deadlineIdentity, producedExecution);
        }
    }

    /** Raw detached severity and ID are preserved; source text/locations are not normalized here. */
    public record Diagnostic(String severity, String message, String path, int line, int column,
                             String diagnosticId) {
        public Diagnostic {
            severityCode(severity);
            ApplicationWire.utf8(message, MAX_DIAGNOSTIC_MESSAGE_BYTES);
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

    /** No per-job process exit or serializable permit. A sealed result is still model-preview/unqualified. */
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
        public String diagnosticsDigest() { return CompilerModelWire.diagnosticsDigest(diagnostics); }
    }

    public static byte[] encodeGeneration(Generation value) {
        nonnull(value);
        ApplicationWire.Writer out = writer(GENERATION, ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        out.string(value.profile, MAX_ID_BYTES); out.string(value.apiVersion, MAX_ID_BYTES);
        out.string(value.compilerVersion, MAX_ID_BYTES);
        writeJdk(out, value.jdk); writeArtifact(out, value.bridge); writeArtifacts(out, value.compilerArtifacts);
        return out.finish();
    }

    public static Generation decodeGeneration(byte[] bytes) {
        ApplicationWire.Reader in = reader(bytes, GENERATION, ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        String profile = in.string(MAX_ID_BYTES), api = in.string(MAX_ID_BYTES), compiler = in.string(MAX_ID_BYTES);
        Jdk jdk = readJdk(in); Artifact bridge = readArtifact(in); List<Artifact> artifacts = readArtifacts(in);
        in.end();
        return new Generation(profile, api, compiler, jdk, bridge, artifacts);
    }

    public static byte[] encodeRequest(Request value) {
        nonnull(value);
        return encodeRequestFields(value.profile, value.ownerInputKey, value.archivedInputKey, value.modelBindingDigest,
                value.original, value.effective, value.snapshotRoot, value.originalClasspathRoot, value.originalOutputRoot,
                value.jobRoot, value.sourceRoot, value.classpathRoot, value.outputRoot, value.files, value.compilationPaths,
                value.dependencies, value.originalOptions, value.effectiveOptions, value.profileDigest, value.deadlineIdentity, value.producedExecution);
    }

    private static byte[] encodeRequestFields(String profile, String ownerInputKey, String archivedInputKey,
            String modelBindingDigest, InputIdentity original, InputIdentity effective, ApplicationWire.Root snapshotRoot,
            ApplicationWire.Root originalClasspathRoot, ApplicationWire.Root originalOutputRoot,
            ApplicationWire.Root jobRoot, ApplicationWire.Root sourceRoot, ApplicationWire.Root classpathRoot,
            ApplicationWire.Root outputRoot, List<Member> files, List<String> compilationPaths,
            List<Dependency> dependencies, List<String> originalOptions, List<String> effectiveOptions,
            String profileDigest, String deadlineIdentity, ProducedExecution producedExecution) {
        ApplicationWire.Writer out = writer(producedExecution == null ? REQUEST : PRODUCED_REQUEST, ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(profile, MAX_ID_BYTES); out.string(ownerInputKey, 71); out.string(archivedInputKey, 71);
        out.string(modelBindingDigest, 71); writeInput(out, original); writeInput(out, effective);
        ApplicationWire.writeRoot(out, snapshotRoot); out.bool(originalClasspathRoot != null);
        if (originalClasspathRoot != null) ApplicationWire.writeRoot(out, originalClasspathRoot);
        ApplicationWire.writeRoot(out, originalOutputRoot); ApplicationWire.writeRoot(out, jobRoot);
        ApplicationWire.writeRoot(out, sourceRoot); ApplicationWire.writeRoot(out, classpathRoot);
        ApplicationWire.writeRoot(out, outputRoot);
        out.i32(files.size()); for (Member file : files) writeMember(out, file);
        out.i32(compilationPaths.size());
        for (String path : compilationPaths) out.string(path, MAX_LOGICAL_PATH_BYTES);
        out.i32(dependencies.size()); for (Dependency dependency : dependencies) writeDependency(out, dependency);
        writeOptions(out, originalOptions); writeOptions(out, effectiveOptions);
        out.string(profileDigest, 71); out.string(deadlineIdentity, 32);
        if (producedExecution != null) writeProducedExecution(out, producedExecution);
        return out.finish();
    }

    public static Request decodeRequest(byte[] bytes) {
        ApplicationWire.Reader in = new ApplicationWire.Reader(bytes, ApplicationWire.MAX_JOB_BODY_BYTES);
        int kind = in.u8(); require(kind == REQUEST || kind == PRODUCED_REQUEST, ErrorCode.KIND);
        require(in.string(MAX_ID_BYTES).equals(VERSION), ErrorCode.VERSION);
        String profile = in.string(MAX_ID_BYTES), owner = in.string(71), archived = in.string(71), binding = in.string(71);
        InputIdentity original = readInput(in), effective = readInput(in);
        ApplicationWire.Root snapshot = ApplicationWire.readRoot(in);
        ApplicationWire.Root originalClasspath = in.bool() ? ApplicationWire.readRoot(in) : null;
        ApplicationWire.Root originalOutput = ApplicationWire.readRoot(in), job = ApplicationWire.readRoot(in),
                source = ApplicationWire.readRoot(in), classpath = ApplicationWire.readRoot(in), output = ApplicationWire.readRoot(in);
        int count = in.count(MAX_SNAPSHOT_FILES);
        List<Member> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) files.add(readMember(in));
        count = in.count(MAX_COMPILATION_FILES);
        List<String> compilation = new ArrayList<>(count);
        for (int i = 0; i < count; i++) compilation.add(in.string(MAX_LOGICAL_PATH_BYTES));
        count = in.count(MAX_DEPENDENCIES);
        List<Dependency> dependencies = new ArrayList<>(count);
        for (int i = 0; i < count; i++) dependencies.add(readDependency(in));
        List<String> originalOptions = readOptions(in), effectiveOptions = readOptions(in);
        String profileDigest = in.string(71), deadline = in.string(32);
        ProducedExecution produced = kind == PRODUCED_REQUEST ? readProducedExecution(in) : null;
        require((kind == REQUEST) == PROFILE.equals(profile), ErrorCode.UNSUPPORTED);
        in.end();
        return new Request(profile, owner, archived, binding, original, effective, snapshot, originalClasspath,
                originalOutput, job, source, classpath, output, files, compilation, dependencies,
                originalOptions, effectiveOptions, profileDigest, deadline, produced);
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
        out.string("strict-model-options-1", MAX_ID_BYTES);
        if (PRODUCED_PROFILE.equals(value.profile)) out.string("same-generation-complete-produced-tree-friend-1", MAX_ID_BYTES);
        out.i32(MAX_SNAPSHOT_FILES); out.i32(MAX_SOURCE_BYTES); out.i64(MAX_SNAPSHOT_BYTES);
        out.i32(MAX_COMPILATION_FILES); out.i32(MAX_DEPENDENCIES); out.i64(MAX_DEPENDENCY_BYTES);
        out.i64(MAX_DEPENDENCY_TOTAL_BYTES); out.i32(MAX_LOGICAL_PATH_BYTES); out.i32(MAX_PATH_DEPTH);
        out.i32(MAX_OPTIONS); out.i32(MAX_DIAGNOSTICS); out.i32(MAX_DIAGNOSTIC_MESSAGE_BYTES);
        out.i32(MAX_DIAGNOSTIC_BYTES); out.i32(MAX_JOB_OUTPUT_FILES); out.i64(MAX_JOB_OUTPUT_BYTES);
        out.i32(MAX_GENERATION_OUTPUT_FILES); out.i64(MAX_GENERATION_OUTPUT_BYTES);
        out.i32(MAX_TEMP_FILES); out.i64(MAX_TEMP_BYTES);
        out.i64(MAX_GENERATION_SNAPSHOT_BYTES); out.i64(MAX_GENERATION_DEPENDENCY_BYTES);
        return ApplicationWire.digest("compiler-model-profile", out.finish());
    }

    /** Whole ordered logical snapshot manifest; not the framework snapshot object or physical file keys. */
    public static String membersDigest(List<Member> values) {
        List<Member> files = memberCopy(values);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(VERSION, MAX_ID_BYTES); writeLogicalMembers(out, files);
        return ApplicationWire.digest("compiler-model-whole-members", out.finish());
    }

    /** Full logical snapshot + exact ordered selection/classpath occurrences/options, never a subset relabel. */
    public static String workDigest(List<Member> values, List<String> selected, List<Dependency> classpath,
                                    List<String> options) {
        List<Member> files = memberCopy(values);
        List<String> compilation = compilationCopy(files, selected);
        List<Dependency> dependencies = dependencyCopy(classpath);
        List<String> flags = validateOptions(options);
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(VERSION, MAX_ID_BYTES); writeLogicalMembers(out, files);
        out.i32(compilation.size()); for (String path : compilation) out.string(path, MAX_LOGICAL_PATH_BYTES);
        out.i32(dependencies.size());
        for (Dependency dependency : dependencies) {
            out.string(dependency.coordinate, MAX_ID_BYTES); out.string(dependency.digest, 71); out.i64(dependency.size);
        }
        writeOptions(out, flags);
        return ApplicationWire.digest("compiler-model-exact-work", out.finish());
    }

    public static String workDigest(Request request) {
        nonnull(request);
        String base = workDigest(request.files, request.compilationPaths, request.dependencies, request.originalOptions);
        if (request.producedExecution == null) return base;
        ApplicationWire.Writer out = new ApplicationWire.Writer(ApplicationWire.MAX_JOB_BODY_BYTES);
        out.string(base, 71); writeProducedExecution(out, request.producedExecution);
        return ApplicationWire.digest("compiler-model-produced-exact-work", out.finish());
    }

    public static String diagnosticsDigest(List<Diagnostic> values) {
        List<Diagnostic> diagnostics = diagnosticCopy(values);
        ApplicationWire.Writer out = new ApplicationWire.Writer(MAX_DIAGNOSTIC_BYTES + 256);
        out.string(VERSION, MAX_ID_BYTES); writeDiagnostics(out, diagnostics);
        return ApplicationWire.digest("compiler-diagnostics", out.finish());
    }

    /** Fixed model-preview launch, not an API for arbitrary JVM flags, agents, argfiles or plugins. */
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
        require(generation.profile.equals(request.profile) && profileDigest(generation).equals(request.profileDigest), ErrorCode.IDENTITY);
        ArrayList<String> values = new ArrayList<>();
        values.add("-no-stdlib"); values.add("-no-reflect"); values.add("-Xrender-internal-diagnostic-names");
        values.addAll(request.effectiveOptions);
        values.add("-jdk-home"); values.add(generation.jdk.home.path());
        values.add("-d"); values.add(request.outputRoot.path());
        values.add("-classpath");
        List<String> dependencies = new ArrayList<>(request.dependencies.size());
        for (int i = 0; i < request.dependencies.size(); i++)
            dependencies.add(resolve(request.classpathRoot, "artifact-" + i + ".jar"));
        // An explicitly empty owned directory is not an empty-string/current-directory lookup.
        values.add(dependencies.isEmpty() ? request.classpathRoot.path() : String.join(File.pathSeparator, dependencies));
        if (request.producedExecution != null) {
            for (ProducedDependency producer : request.producedExecution.producers) if (producer.friend)
                values.add("-Xfriend-paths=" + resolve(request.classpathRoot, "artifact-" + producer.classpathIndex + ".jar"));
        }
        for (String path : request.compilationPaths) {
            String physical = resolve(request.sourceRoot, path); ApplicationWire.absolutePath(physical); values.add(physical);
        }
        List<String> argv = List.copyOf(values);
        argvDigest(argv); // Aggregate argv bound is separate from the unchanged request body cap.
        return argv;
    }

    public static String argvDigest(List<String> values) {
        List<String> argv = ApplicationWire.strings(values, MAX_ARGV, MAX_PATH_BYTES * MAX_DEPENDENCIES);
        ApplicationWire.Writer out = new ApplicationWire.Writer(MAX_ARGV_BYTES);
        out.string(VERSION, MAX_ID_BYTES); out.i32(argv.size());
        for (String argument : argv) out.string(argument, MAX_PATH_BYTES * MAX_DEPENDENCIES);
        return ApplicationWire.digest("compiler-model-effective-argv", out.finish());
    }

    public static void matchGeneration(ApplicationWire.Launch launch, Generation generation) {
        nonnull(launch); nonnull(generation);
        ApplicationWire.Identity identity = launch.identity();
        require(identity.adapterVersion().equals(generation.apiVersion) && identity.profile().equals(generation.profile)
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
        requireDisjoint(launch.generationRoot(), generation.jdk.home);
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
        require(request.profile.equals(generation.profile) && job.generation().equals(launch.identity().generation()) && job.sequence() <= launch.limits().maxJobs()
                && job.originalKey().equals(request.original.analysisKey)
                && job.effectiveKey().equals(request.effective.analysisKey)
                && job.deadlineIdentity().equals(request.deadlineIdentity)
                && Arrays.equals(job.payload(), encodeRequest(request))
                && profileDigest(generation).equals(request.profileDigest), ErrorCode.IDENTITY);
        if (request.producedExecution != null) for (ProducedDependency producer : request.producedExecution.producers)
            require(producer.generation.equals(job.generation()) && producer.producerSequence < job.sequence(), ErrorCode.IDENTITY);
        String relativeJob = "jobs/" + job.sequence();
        require(request.jobRoot.path().equals(resolve(launch.generationRoot(), relativeJob))
                && request.sourceRoot.path().equals(resolve(launch.generationRoot(), relativeJob + "/sources"))
                && request.classpathRoot.path().equals(resolve(launch.generationRoot(), relativeJob + "/classpath"))
                && request.outputRoot.path().equals(resolve(launch.generationRoot(), relativeJob + "/classes")), ErrorCode.PATH);
        List<ApplicationWire.Root> originals = roots(request.snapshotRoot, request.originalClasspathRoot, request.originalOutputRoot);
        for (int i = 0; i < originals.size(); i++) {
            requireDisjoint(originals.get(i), launch.generationRoot()); requireDisjoint(originals.get(i), generation.jdk.home);
            for (int j = 0; j < i; j++) requireDisjoint(originals.get(i), originals.get(j));
        }
        Set<String> reservedKeys = new HashSet<>();
        reservedKeys.add(launch.generationRoot().fileKey()); reservedKeys.add(generation.jdk.home.fileKey());
        reservedKeys.add(generation.bridge.fileKey);
        for (Artifact artifact : generation.compilerArtifacts) reservedKeys.add(artifact.fileKey);
        for (ApplicationWire.Root root : roots(request.snapshotRoot, request.originalClasspathRoot, request.originalOutputRoot,
                request.jobRoot, request.sourceRoot, request.classpathRoot, request.outputRoot))
            require(!reservedKeys.contains(root.fileKey()), ErrorCode.IDENTITY);
        for (Member file : request.files) require(!reservedKeys.contains(file.fileKey), ErrorCode.IDENTITY);
        for (Dependency dependency : request.dependencies) require(!reservedKeys.contains(dependency.fileKey), ErrorCode.IDENTITY);
        require(request.classpathRoot.path().indexOf(File.pathSeparatorChar) < 0, ErrorCode.PATH);
        for (int i = 0; i < request.dependencies.size(); i++)
            ApplicationWire.absolutePath(resolve(request.classpathRoot, "artifact-" + i + ".jar"));
        for (Member file : request.files) {
            ApplicationWire.absolutePath(resolve(request.sourceRoot, file.logicalPath));
            ApplicationWire.absolutePath(resolve(request.snapshotRoot, file.logicalPath));
        }
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
                && observation.memberDigest.equals(workDigest(request))
                && observation.argvDigest.equals(argvDigest(arguments(launch, generation, request))), ErrorCode.IDENTITY);
        for (Diagnostic diagnostic : observation.diagnostics) {
            if (diagnostic.path.isEmpty()) continue; // Global ERROR/unknown ID is retained as raw failure evidence.
            Member matched = null;
            for (String path : request.compilationPaths) {
                if (diagnostic.path.equals(path) || diagnostic.path.equals(resolve(request.sourceRoot, path))) {
                    for (Member member : request.files) if (member.logicalPath.equals(path)) { matched = member; break; }
                    break;
                }
            }
            require(matched != null, ErrorCode.PATH);
            require(diagnostic.line >= 1 && diagnostic.column >= 1
                    && diagnostic.line <= matched.size + 1 && diagnostic.column <= matched.size + 1, ErrorCode.IDENTITY);
            // Exact UTF16 line bounds are additionally checked against actual bytes by worker and host.
        }
        requireSealed(observation);
        // The observed HELLO/loader is independently matched by ApplicationWire.matchResult.
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

    /** Exact finite token grammar; duplicates/unsupported forms fail, no sorting or normalization. */
    public static List<String> validateOptions(List<String> values) {
        List<String> options = ApplicationWire.strings(values, MAX_OPTIONS, MAX_ID_BYTES);
        Set<String> seen = new HashSet<>();
        boolean target = false;
        for (int i = 0; i < options.size(); i++) {
            String flag = options.get(i);
            require(seen.add(flag), ErrorCode.DUPLICATE);
            switch (flag) {
                case "-progressive", "-Werror", "-nowarn" -> { }
                case "-language-version", "-api-version", "-jvm-target", "-module-name" -> {
                    require(++i < options.size(), ErrorCode.UNSUPPORTED);
                    String value = options.get(i);
                    switch (flag) {
                        case "-language-version", "-api-version" -> require(
                                Set.of("2.0", "2.1", "2.2", "2.3", "2.4").contains(value), ErrorCode.UNSUPPORTED);
                        case "-jvm-target" -> {
                            require(Set.of("1.8", "11", "17", "21").contains(value), ErrorCode.UNSUPPORTED);
                            target = true;
                        }
                        case "-module-name" -> require(value.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}"), ErrorCode.UNSUPPORTED);
                        default -> throw failure(ErrorCode.UNSUPPORTED);
                    }
                }
                default -> throw failure(ErrorCode.UNSUPPORTED);
            }
        }
        require(target, ErrorCode.UNSUPPORTED);
        return options;
    }

    private static void logicalPath(String path) {
        ApplicationWire.text(path, MAX_LOGICAL_PATH_BYTES, false);
        require(!path.startsWith("/") && path.indexOf('\\') < 0 && path.indexOf(':') < 0, ErrorCode.PATH);
        String[] parts = path.split("/", -1);
        require(parts.length <= MAX_PATH_DEPTH, ErrorCode.LIMIT);
        for (String part : parts) require(!part.isEmpty() && !part.equals(".") && !part.equals(".."), ErrorCode.PATH);
    }

    private static List<Member> memberCopy(List<Member> values) {
        List<Member> files = ApplicationWire.list(values, MAX_SNAPSHOT_FILES);
        require(!files.isEmpty(), ErrorCode.VALUE);
        Set<String> paths = new HashSet<>(), keys = new HashSet<>();
        long total = 0;
        for (Member file : files) {
            require(paths.add(file.logicalPath) && keys.add(file.fileKey), ErrorCode.DUPLICATE);
            require(file.size <= MAX_SNAPSHOT_BYTES - total, ErrorCode.LIMIT); total += file.size;
        }
        for (String path : paths) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
                require(!paths.contains(path.substring(0, slash)), ErrorCode.PATH);
        }
        return files;
    }

    private static List<String> compilationCopy(List<Member> files, List<String> values) {
        List<String> compilation = ApplicationWire.strings(values, MAX_COMPILATION_FILES, MAX_LOGICAL_PATH_BYTES);
        require(!compilation.isEmpty(), ErrorCode.VALUE);
        Set<String> paths = new HashSet<>(), selected = new HashSet<>();
        for (Member file : files) paths.add(file.logicalPath);
        for (String path : compilation) {
            logicalPath(path);
            require(path.endsWith(".kt") && paths.contains(path), ErrorCode.UNSUPPORTED);
            require(selected.add(path), ErrorCode.DUPLICATE);
        }
        return compilation;
    }

    private static List<Dependency> dependencyCopy(List<Dependency> values) {
        List<Dependency> dependencies = ApplicationWire.list(values, MAX_DEPENDENCIES);
        Set<String> keys = new HashSet<>();
        long total = 0;
        for (Dependency dependency : dependencies) {
            require(keys.add(dependency.fileKey), ErrorCode.DUPLICATE);
            require(dependency.size <= MAX_DEPENDENCY_TOTAL_BYTES - total, ErrorCode.LIMIT); total += dependency.size;
        }
        return dependencies;
    }

    private static void writeLogicalMembers(ApplicationWire.Writer out, List<Member> files) {
        out.i32(files.size());
        for (Member file : files) {
            out.string(file.logicalPath, MAX_LOGICAL_PATH_BYTES); out.string(file.digest, 71); out.i64(file.size);
        }
    }

    private static List<ApplicationWire.Root> roots(ApplicationWire.Root... values) {
        List<ApplicationWire.Root> roots = new ArrayList<>();
        for (ApplicationWire.Root root : values) if (root != null) roots.add(root);
        return roots;
    }

    private static void requireDisjoint(ApplicationWire.Root first, ApplicationWire.Root second) {
        Path a = Path.of(first.path()), b = Path.of(second.path());
        require(!a.startsWith(b) && !b.startsWith(a), ErrorCode.PATH);
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
        return 1 + 4 + ApplicationWire.utf8(value.message, MAX_DIAGNOSTIC_MESSAGE_BYTES).length
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

    private static void writeMember(ApplicationWire.Writer out, Member value) {
        out.string(value.logicalPath, MAX_LOGICAL_PATH_BYTES); out.string(value.digest, 71);
        out.i64(value.size); out.string(value.fileKey, MAX_KEY_BYTES);
    }

    private static Member readMember(ApplicationWire.Reader in) {
        return new Member(in.string(MAX_LOGICAL_PATH_BYTES), in.string(71), in.i64(), in.string(MAX_KEY_BYTES));
    }

    private static void writeDependency(ApplicationWire.Writer out, Dependency value) {
        out.string(value.coordinate, MAX_ID_BYTES); out.string(value.digest, 71);
        out.i64(value.size); out.string(value.fileKey, MAX_KEY_BYTES);
    }

    private static Dependency readDependency(ApplicationWire.Reader in) {
        return new Dependency(in.string(MAX_ID_BYTES), in.string(71), in.i64(), in.string(MAX_KEY_BYTES));
    }

    private static void writeProducedExecution(ApplicationWire.Writer out, ProducedExecution value) {
        out.string(value.planDigest, 71); out.string(value.compilationId, MAX_ID_BYTES);
        out.string(value.rawContextDigest, 71); out.string(value.resolutionDigest, 71); out.i32(value.producers.size());
        for (ProducedDependency producer : value.producers) {
            out.i32(producer.classpathIndex); out.string(producer.producerCompilationId, MAX_ID_BYTES);
            out.string(producer.referenceDigest, 71); out.string(producer.generation, 32); out.i32(producer.producerSequence);
            out.string(producer.producerBindingDigest, 71); out.string(producer.requestDigest, 71);
            out.string(producer.resultDigest, 71); out.string(producer.outputDigest, 71); out.bool(producer.friend);
        }
    }
    private static ProducedExecution readProducedExecution(ApplicationWire.Reader in) {
        String plan = in.string(71), compilation = in.string(MAX_ID_BYTES), raw = in.string(71), resolution = in.string(71);
        int count = in.count(1); List<ProducedDependency> producers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) producers.add(new ProducedDependency(in.i32(), in.string(MAX_ID_BYTES), in.string(71),
            in.string(32), in.i32(), in.string(71), in.string(71), in.string(71), in.string(71), in.bool()));
        return new ProducedExecution(plan, compilation, raw, resolution, producers);
    }

    private static void writeOptions(ApplicationWire.Writer out, List<String> values) {
        out.i32(values.size());
        for (String value : values) out.string(value, MAX_ID_BYTES);
    }

    private static List<String> readOptions(ApplicationWire.Reader in) {
        int count = in.count(MAX_OPTIONS);
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
            out.u8(severityCode(value.severity)); out.string(value.message, MAX_DIAGNOSTIC_MESSAGE_BYTES);
            out.string(value.path, MAX_PATH_BYTES); out.i32(value.line); out.i32(value.column);
            out.string(value.diagnosticId, MAX_KEY_BYTES);
        }
    }

    private static List<Diagnostic> readDiagnostics(ApplicationWire.Reader in) {
        int before = in.remaining();
        int count = in.count(MAX_DIAGNOSTICS);
        List<Diagnostic> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Diagnostic value = new Diagnostic(severity(in.u8()), in.string(MAX_DIAGNOSTIC_MESSAGE_BYTES), in.string(MAX_PATH_BYTES),
                    in.i32(), in.i32(), in.string(MAX_KEY_BYTES));
            require(before - in.remaining() <= MAX_DIAGNOSTIC_BYTES, ErrorCode.LIMIT);
            values.add(value);
        }
        return values;
    }
}
