package dev.ktchecker.workerprotocol;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static dev.ktchecker.workerprotocol.CompilerModelWire.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Detached scalar/codec controls only. Every path, file key, identity and artifact descriptor below is
 * synthetic; no filesystem, compiler, JDK image, artifact loader, provider or native helper is used.
 */
class CompilerModelWireTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_TOKEN = "fedcba9876543210fedcba9876543210";
    private static final Path BASE = Path.of("model-wire-fixture").toAbsolutePath().normalize();
    private static final ApplicationWire.Root GENERATION_ROOT = root("generation", "generation-key");
    private static final ApplicationWire.Root SNAPSHOT_ROOT = root("archive/snapshot", "snapshot-key");
    private static final ApplicationWire.Root ORIGINAL_CP_ROOT = root("archive/classpath", "original-cp-key");
    private static final ApplicationWire.Root ORIGINAL_OUTPUT_ROOT = root("archive/output", "original-output-key");
    private static final ApplicationWire.Root JOB_ROOT = root("generation/jobs/1", "job-key");
    private static final ApplicationWire.Root SOURCE_ROOT = root("generation/jobs/1/sources", "sources-key");
    private static final ApplicationWire.Root CP_ROOT = root("generation/jobs/1/classpath", "cp-key");
    private static final ApplicationWire.Root OUTPUT_ROOT = root("generation/jobs/1/classes", "classes-key");
    private static final List<String> OPTIONS = List.of("-module-name", "raw.module", "-jvm-target", "21");
    private static final List<Member> FILES = List.of(
            member("src/One.kt", 24, "one-key"), member("src/Two.kt", 12, "two-key"),
            member("build.gradle.kts", 100, "build-key"), member("model/settings.json", 60, "model-key"),
            member("resources/blob.bin", 64, "resource-key"));
    private static final List<String> SELECTED = List.of("src/Two.kt", "src/One.kt");
    private static final List<Dependency> DEPENDENCIES = List.of(
            dependency("example:first:1", "first", 100, "dependency-0"),
            dependency("example:second:2", "second", 120, "dependency-1"));
    private static final Generation GENERATION = generation();
    private static final ApplicationWire.Launch LAUNCH = launch(GENERATION_ROOT, GENERATION);
    private static final ApplicationWire.Hello HELLO = new ApplicationWire.Hello(LAUNCH.identity(),
            ApplicationWire.launchDigest(LAUNCH), OTHER_TOKEN, COMPILER_VERSION, true, true);
    private static final Reset RESET = new Reset(true, true, true, true);

    @Test void generationRequestAndObservationRoundTripWithoutExecutingDescriptors() {
        Request request = request();
        Observation observation = observation(request, 1, List.of(error("raw error", "src/One.kt", "UNMAPPED")));
        assertEquals(GENERATION, decodeGeneration(encodeGeneration(GENERATION)));
        assertEquals(request, decodeRequest(encodeRequest(request)));
        assertEquals(observation, decodeObservation(encodeObservation(observation)));
        assertArrayEquals(encodeRequest(request), encodeRequest(decodeRequest(encodeRequest(request))));
        ApplicationWire.Job job = job(request);
        ApplicationWire.Result result = result(job, observation);
        assertDoesNotThrow(() -> matchGeneration(LAUNCH, GENERATION));
        assertDoesNotThrow(() -> matchHello(LAUNCH, GENERATION, HELLO));
        assertDoesNotThrow(() -> matchRequest(LAUNCH, GENERATION, job, request));
        assertDoesNotThrow(() -> matchObservation(LAUNCH, GENERATION, job, request, result, observation));
        assertDoesNotThrow(() -> ApplicationWire.matchResult(LAUNCH, HELLO, job, result));
    }

    @Test void payloadKindsAndVersionAreNotInterchangeableWithM0OrEachOther() {
        rejects(ApplicationWire.ErrorCode.KIND, () -> decodeGeneration(encodeRequest(request())));
        rejects(ApplicationWire.ErrorCode.KIND, () -> decodeRequest(encodeGeneration(GENERATION)));
        byte[] changed = encodeRequest(request());
        changed[5] = 'X';
        rejects(ApplicationWire.ErrorCode.VERSION, () -> decodeRequest(changed));
        assertEquals("ktchecker/compiler-model-job/preview-1", VERSION);
        assertEquals("ktchecker/compiler-jvm-model/preview-1", PROFILE);
        assertEquals("kotlin-2.4.21/caller-owned-JvmCliPipeline-model-1", API);
    }

    @Test void trailingAndRepresentativeTruncatedRequestBytesFailClosed() {
        byte[] encoded = encodeRequest(request());
        rejects(ApplicationWire.ErrorCode.TRAILING, () -> decodeRequest(Arrays.copyOf(encoded, encoded.length + 1)));
        for (int size : new int[]{0, 1, 4, 5, 10, encoded.length - 1})
            rejects(() -> decodeRequest(Arrays.copyOf(encoded, size)));
        rejects(ApplicationWire.ErrorCode.LIMIT,
                () -> decodeRequest(new byte[ApplicationWire.MAX_JOB_BODY_BYTES + 1]));
        rejects(ApplicationWire.ErrorCode.LIMIT,
                () -> decodeObservation(new byte[ApplicationWire.MAX_RESULT_BODY_BYTES + 1]));
    }

    @Test void malformedUtf8NulAndNoncanonicalVersionLengthAreRejectedBeforeDtos() {
        byte[] encoded = encodeRequest(request());
        byte[] malformed = encoded.clone(); malformed[5] = (byte) 0xff;
        rejects(ApplicationWire.ErrorCode.UTF8, () -> decodeRequest(malformed));
        byte[] nul = encoded.clone(); nul[5] = 0;
        rejects(ApplicationWire.ErrorCode.NUL, () -> decodeRequest(nul));
        byte[] length = encoded.clone(); length[1] = 0x7f;
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> decodeRequest(length));
    }

    @Test void fullSnapshotIsNotRelabeledAsCompilationSubset() {
        Request request = request();
        assertEquals(FILES, request.files());
        assertEquals(SELECTED, request.compilationPaths());
        assertNotEquals(membersDigest(FILES), membersDigest(FILES.subList(0, 2)));
        assertEquals(membersDigest(FILES), request.original().memberDigest());
        assertEquals(digest("snapshot-object"), request.original().snapshotDigest());
        assertNotEquals(request.original().snapshotDigest(), request.original().memberDigest());
        assertEquals(workDigest(FILES, SELECTED, DEPENDENCIES, OPTIONS),
                observation(request, 0, List.of()).memberDigest());
    }

    @Test void wholeMemberOrderAndOpaqueBytesAffectTheWorkDigest() {
        ArrayList<Member> reordered = new ArrayList<>(FILES);
        Collections.swap(reordered, 2, 3);
        assertNotEquals(membersDigest(FILES), membersDigest(reordered));
        assertNotEquals(workDigest(FILES, SELECTED, DEPENDENCIES, OPTIONS),
                workDigest(reordered, SELECTED, DEPENDENCIES, OPTIONS));
        ArrayList<Member> changed = new ArrayList<>(FILES);
        Member opaque = changed.get(4);
        changed.set(4, new Member(opaque.logicalPath(), digest("other opaque bytes"), opaque.size(), opaque.fileKey()));
        assertNotEquals(workDigest(FILES, SELECTED, DEPENDENCIES, OPTIONS),
                workDigest(changed, SELECTED, DEPENDENCIES, OPTIONS));
    }

    @Test void orderedSelectionClasspathOccurrencesAndExactOptionsAllBindWork() {
        String original = workDigest(FILES, SELECTED, DEPENDENCIES, OPTIONS);
        assertNotEquals(original, workDigest(FILES, List.of("src/One.kt", "src/Two.kt"), DEPENDENCIES, OPTIONS));
        assertNotEquals(original, workDigest(FILES, List.of("src/One.kt"), DEPENDENCIES, OPTIONS));
        assertNotEquals(original, workDigest(FILES, SELECTED, List.of(DEPENDENCIES.get(1), DEPENDENCIES.get(0)), OPTIONS));
        assertNotEquals(original, workDigest(FILES, SELECTED, DEPENDENCIES,
                List.of("-jvm-target", "21", "-module-name", "raw.module")));
    }

    @Test void physicalCopyKeysAreNotSubstitutedForLogicalWholeSnapshotIdentity() {
        ArrayList<Member> relocated = new ArrayList<>();
        for (Member file : FILES) relocated.add(new Member(file.logicalPath(), file.digest(), file.size(), file.fileKey() + "-copy"));
        assertEquals(membersDigest(FILES), membersDigest(relocated));
        assertEquals(workDigest(FILES, SELECTED, DEPENDENCIES, OPTIONS),
                workDigest(relocated, SELECTED, DEPENDENCIES, OPTIONS));
        assertFalse(Arrays.equals(encodeRequest(request()),
                encodeRequest(request(relocated, SELECTED, DEPENDENCIES, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT))));
    }

    @Test void repeatedCoordinatesAndDigestsKeepSeparateOrderedMaterializedCopies() {
        Dependency first = dependency("example:same:1", "same", 20, "ordinal-0");
        Dependency second = dependency("example:same:1", "same", 20, "ordinal-1");
        List<Dependency> copies = List.of(first, second);
        Request request = request(FILES, SELECTED, copies, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT);
        assertEquals(copies, decodeRequest(encodeRequest(request)).dependencies());
        List<String> argv = arguments(LAUNCH, GENERATION, request);
        assertEquals(Path.of(CP_ROOT.path()).resolve("artifact-0.jar") + File.pathSeparator
                + Path.of(CP_ROOT.path()).resolve("artifact-1.jar"), argv.get(argv.indexOf("-classpath") + 1));
        assertNotEquals(workDigest(FILES, SELECTED, copies, OPTIONS),
                workDigest(FILES, SELECTED, List.of(first), OPTIONS));
        // Swapping logically identical occurrences has the same logical work; physical keys remain in the request.
        assertEquals(workDigest(FILES, SELECTED, copies, OPTIONS),
                workDigest(FILES, SELECTED, List.of(second, first), OPTIONS));
    }

    @Test void emptyClasspathHasNoInventedOriginalRootAndUsesExplicitOwnedEmptyDirectory() {
        Request request = request(FILES, SELECTED, List.of(), OPTIONS, OPTIONS, null);
        assertNull(request.originalClasspathRoot());
        assertEquals(request, decodeRequest(encodeRequest(request)));
        List<String> argv = arguments(LAUNCH, GENERATION, request);
        assertEquals(CP_ROOT.path(), argv.get(argv.indexOf("-classpath") + 1));
        assertFalse(argv.contains(""));
        rejects(ApplicationWire.ErrorCode.IDENTITY,
                () -> request(FILES, SELECTED, List.of(), OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        rejects(ApplicationWire.ErrorCode.IDENTITY,
                () -> request(FILES, SELECTED, DEPENDENCIES, OPTIONS, OPTIONS, null));
    }

    @Test void fixedArgumentsDoNotInjectImplementationStdlibOrNoncompiledFiles() {
        Request request = request();
        List<String> argv = arguments(LAUNCH, GENERATION, request);
        assertEquals(List.of("-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names"), argv.subList(0, 3));
        assertEquals(OPTIONS, argv.subList(3, 3 + OPTIONS.size()));
        assertEquals(SELECTED.stream().map(p -> Path.of(SOURCE_ROOT.path()).resolve(p).toString()).toList(),
                argv.subList(argv.size() - SELECTED.size(), argv.size()));
        for (Member file : FILES.subList(2, FILES.size()))
            assertFalse(argv.contains(Path.of(SOURCE_ROOT.path()).resolve(file.logicalPath()).toString()));
        assertTrue(argv.stream().noneMatch(value -> value.contains("compiler" + File.separator + "0.jar")));
        assertThrows(UnsupportedOperationException.class, () -> argv.add("injected"));
    }

    @Test void compilationSubsetMustBeNonemptyUniquePresentAndExactlyKt() {
        for (List<String> selected : List.of(List.<String>of(), List.of("missing.kt"),
                List.of("src/One.kt", "src/One.kt"), List.of("build.gradle.kts"), List.of("src/ONE.KT")))
            rejects(() -> request(FILES, selected, DEPENDENCIES, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        assertDoesNotThrow(() -> request(FILES, List.of("src/One.kt"), DEPENDENCIES, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
    }

    @Test void logicalPathsRejectTraversalEmptySegmentsControlsAndAliases() {
        for (String path : List.of("", "/One.kt", "a/../One.kt", "a/./One.kt", "a//One.kt", "a/", "../One.kt",
                "a\\One.kt", "a:One.kt", "a\nOne.kt", "a\u0000One.kt"))
            rejects(() -> member(path, 0, "key"));
        assertDoesNotThrow(() -> member("spaces allowed/One.kt", 0, "key"));
    }

    @Test void pathByteAndDepthLimitsAreNotCharacterCountOrNormalization() {
        assertDoesNotThrow(() -> member("\u00e9".repeat(254) + ".kt", 0, "key"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> member("\u00e9".repeat(255) + ".kt", 0, "key"));
        assertDoesNotThrow(() -> member("d/".repeat(MAX_PATH_DEPTH - 1) + "One.kt", 0, "key"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> member("d/".repeat(MAX_PATH_DEPTH) + "One.kt", 0, "key"));
        rejects(ApplicationWire.ErrorCode.UTF8, () -> member("bad\ud800.kt", 0, "key"));
    }

    @Test void memberPathPrefixAndPhysicalKeyCollisionsAreRejected() {
        rejects(ApplicationWire.ErrorCode.PATH, () -> membersDigest(List.of(member("dir", 0, "one"), member("dir/One.kt", 0, "two"))));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> membersDigest(List.of(member("One.kt", 0, "one"), member("One.kt", 0, "two"))));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> membersDigest(List.of(member("One.kt", 0, "same"), member("Two.kt", 0, "same"))));
        rejects(() -> membersDigest(List.of()));
    }

    @Test void sourceFileAndWholeSnapshotByteLimitsAllowEmptyMembers() {
        assertDoesNotThrow(() -> member("Empty.kt", 0, "empty"));
        assertDoesNotThrow(() -> member("Maximum.kt", MAX_SOURCE_BYTES, "maximum"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> member("Negative.kt", -1, "key"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> member("Large.kt", MAX_SOURCE_BYTES + 1L, "key"));
        ArrayList<Member> total = new ArrayList<>();
        for (int i = 0; i < MAX_SNAPSHOT_BYTES / MAX_SOURCE_BYTES; i++) total.add(member("f" + i + ".kt", MAX_SOURCE_BYTES, "k" + i));
        assertDoesNotThrow(() -> membersDigest(total));
        total.add(member("overflow.kt", 1, "overflow"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> membersDigest(total));
    }

    @Test void snapshotCountAndCompilationCountHaveIndependentBounds() {
        ArrayList<Member> files = new ArrayList<>();
        for (int i = 0; i < MAX_SNAPSHOT_FILES; i++) files.add(member("f" + i + ".kt", 0, "k" + i));
        assertDoesNotThrow(() -> membersDigest(files));
        ArrayList<String> selected = new ArrayList<>();
        for (int i = 0; i < MAX_COMPILATION_FILES; i++) selected.add(files.get(i).logicalPath());
        assertDoesNotThrow(() -> request(files, selected, List.of(), OPTIONS, OPTIONS, null));
        selected.add(files.get(MAX_COMPILATION_FILES).logicalPath());
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> request(files, selected, List.of(), OPTIONS, OPTIONS, null));
        files.add(member("overflow.kt", 0, "overflow"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> membersDigest(files));
    }

    @Test void dependencySizeTotalCountAndCopyKeyBoundsDoNotDeduplicate() {
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> dependency("example:a:1", "a", 0, "key"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> dependency("example:a:1", "a", MAX_DEPENDENCY_BYTES + 1, "key"));
        ArrayList<Dependency> total = new ArrayList<>();
        for (int i = 0; i < 4; i++) total.add(dependency("example:repeated:1", "same", MAX_DEPENDENCY_BYTES, "ordinal" + i));
        assertDoesNotThrow(() -> request(FILES, SELECTED, total, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        total.add(dependency("example:repeated:1", "same", 1, "overflow"));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> request(FILES, SELECTED, total, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        ArrayList<Dependency> count = new ArrayList<>();
        for (int i = 0; i <= MAX_DEPENDENCIES; i++) count.add(dependency("example:repeated:1", "same", 1, "ordinal" + i));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> request(FILES, SELECTED, count, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> request(FILES, SELECTED,
                List.of(DEPENDENCIES.get(0), DEPENDENCIES.get(0)), OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
    }

    @Test void effectiveMemberDependencyAndRootKeysMustBeDistinct() {
        List<Dependency> aliased = List.of(dependency("example:alias:1", "alias", 1, FILES.get(0).fileKey()));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> request(FILES, SELECTED, aliased, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
        ArrayList<Member> files = new ArrayList<>(FILES);
        Member first = files.get(0);
        files.set(0, new Member(first.logicalPath(), first.digest(), first.size(), SOURCE_ROOT.fileKey()));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> request(files, SELECTED, DEPENDENCIES, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT));
    }

    @Test void requestAggregateCapRejectsCombinedPerItemMaximaBeforePublication() {
        ArrayList<Member> files = new ArrayList<>();
        for (int i = 0; i < 170; i++)
            files.add(member("d" + i + "/" + "x".repeat(75) + ".kt", 0, "k".repeat(250) + i));
        assertDoesNotThrow(() -> membersDigest(files)); // Logical manifest itself fits; full physical request does not.
        rejects(ApplicationWire.ErrorCode.LIMIT,
                () -> request(files, List.of(files.get(0).logicalPath()), List.of(), OPTIONS, OPTIONS, null));
    }

    @Test void exactOptionGrammarPreservesOrderAndExplicitJvmTargetSpelling() {
        for (String target : List.of("1.8", "11", "17", "21")) {
            List<String> flags = List.of("-progressive", "-Werror", "-jvm-target", target,
                    "-api-version", "2.4", "-nowarn", "-language-version", "2.4", "-module-name", "_raw.module-1");
            assertEquals(flags, validateOptions(flags));
        }
        for (String version : List.of("2.0", "2.1", "2.2", "2.3", "2.4"))
            assertDoesNotThrow(() -> validateOptions(List.of("-language-version", version, "-api-version", version, "-jvm-target", "21")));
        assertDoesNotThrow(() -> validateOptions(List.of("-module-name", "a".repeat(128), "-jvm-target", "21")));
        rejects(() -> validateOptions(List.of("-module-name", "a".repeat(129), "-jvm-target", "21")));
    }

    @Test void missingTargetUnsupportedJvmAliasesAndDuplicateFlagsAreNotNormalized() {
        for (List<String> flags : List.of(List.<String>of(), List.of("-nowarn"), List.of("-jvm-target"),
                List.of("-jvm-target", "8"), List.of("-jvm-target", "1.8.0"), List.of("-jvm-target", "22"),
                List.of("-jvm-target=21"), List.of("-jvm-target", "21", "-module-name", "9bad"),
                List.of("-jvm-target", "21", "-language-version", "2.5"),
                List.of("-jvm-target", "21", "-api-version", "1.9"))) rejects(() -> validateOptions(flags));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> validateOptions(List.of("-nowarn", "-nowarn", "-jvm-target", "21")));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> validateOptions(List.of("-jvm-target", "21", "-jvm-target", "21")));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> validateOptions(Collections.nCopies(MAX_OPTIONS + 1, "-nowarn")));
    }

    @Test void freeformPluginsArgfilesPathsAndCompilerClasspathFlagsAreUnsupported() {
        for (String flag : List.of("@args.txt", "-Xplugin=plugin.jar", "-P", "-classpath", "-cp", "-d", "-jdk-home",
                "-Xallow-anything", "Source.kt", "--", "-script", "-include-runtime", "-no-stdlib")) {
            rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> validateOptions(List.of("-jvm-target", "21", flag)));
        }
        for (String module : List.of("../name", "a/b", "a b", "name:part", "\u00e9"))
            rejects(() -> validateOptions(List.of("-jvm-target", "21", "-module-name", module)));
    }

    @Test void optionsAndRequestListsAreDefensivelyCopiedAndImmutable() {
        ArrayList<String> options = new ArrayList<>(OPTIONS);
        List<String> validated = validateOptions(options); options.clear();
        assertEquals(OPTIONS, validated);
        assertThrows(UnsupportedOperationException.class, () -> validated.add("-nowarn"));
        ArrayList<Member> files = new ArrayList<>(FILES);
        ArrayList<String> selected = new ArrayList<>(SELECTED);
        ArrayList<Dependency> dependencies = new ArrayList<>(DEPENDENCIES);
        Request request = request(files, selected, dependencies, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT);
        files.clear(); selected.clear(); dependencies.clear();
        assertEquals(FILES, request.files()); assertEquals(SELECTED, request.compilationPaths());
        assertEquals(DEPENDENCIES, request.dependencies());
        assertThrows(UnsupportedOperationException.class, () -> request.files().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.compilationPaths().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.dependencies().clear());
    }

    @Test void originalAndEffectiveOptionOrderMustBeElementForElementEqual() {
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> request(FILES, SELECTED, DEPENDENCIES, OPTIONS,
                List.of("-jvm-target", "21", "-module-name", "raw.module"), ORIGINAL_CP_ROOT));
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> request(FILES, SELECTED, DEPENDENCIES, OPTIONS,
                List.of("-module-name", "raw.module", "-jvm-target", "17"), ORIGINAL_CP_ROOT));
    }

    @Test void fullOriginalEffectiveContextMustSurviveRelocationUnchanged() {
        Request request = request();
        InputIdentity effective = request.effective();
        for (InputIdentity changed : List.of(
                new InputIdentity(effective.analysisKey(), digest("changed snapshot"), effective.memberDigest(), effective.modelDigest(),
                        effective.dependencyDigest(), effective.configurationDigest(), effective.toolchainDigest()),
                new InputIdentity(effective.analysisKey(), effective.snapshotDigest(), digest("changed members"), effective.modelDigest(),
                        effective.dependencyDigest(), effective.configurationDigest(), effective.toolchainDigest()),
                new InputIdentity(effective.analysisKey(), effective.snapshotDigest(), effective.memberDigest(), digest("changed model"),
                        effective.dependencyDigest(), effective.configurationDigest(), effective.toolchainDigest()),
                new InputIdentity(effective.analysisKey(), effective.snapshotDigest(), effective.memberDigest(), effective.modelDigest(),
                        digest("changed dependencies"), effective.configurationDigest(), effective.toolchainDigest()),
                new InputIdentity(effective.analysisKey(), effective.snapshotDigest(), effective.memberDigest(), effective.modelDigest(),
                        effective.dependencyDigest(), digest("changed config"), effective.toolchainDigest()),
                new InputIdentity(effective.analysisKey(), effective.snapshotDigest(), effective.memberDigest(), effective.modelDigest(),
                        effective.dependencyDigest(), effective.configurationDigest(), digest("changed toolchain")))) {
            rejects(ApplicationWire.ErrorCode.IDENTITY, () -> identities(request, request.original(), changed,
                    request.ownerInputKey(), request.archivedInputKey(), request.modelBindingDigest()));
        }
        assertNotEquals(request.original().analysisKey(), request.effective().analysisKey());
    }

    @Test void ownerArchiveAndModelEchoChangesAlterPayloadButCannotMintAuthority() {
        Request original = request();
        ApplicationWire.Job job = job(original);
        for (Request changed : List.of(
                identities(original, original.original(), original.effective(), digest("other owner"), original.archivedInputKey(), original.modelBindingDigest()),
                identities(original, original.original(), original.effective(), original.ownerInputKey(), digest("other archive"), original.modelBindingDigest()),
                identities(original, original.original(), original.effective(), original.ownerInputKey(), original.archivedInputKey(), digest("other binding")))) {
            assertFalse(Arrays.equals(encodeRequest(original), encodeRequest(changed)));
            rejects(ApplicationWire.ErrorCode.IDENTITY, () -> matchRequest(LAUNCH, GENERATION, job, changed));
        }
        // Both are merely well-formed DTOs; only the host's separately retained object grant can authorize either.
    }

    @Test void deadlineAndOriginalEffectiveKeysRemainBoundToTheApplicationEnvelope() {
        Request request = request(); ApplicationWire.Job job = job(request);
        for (ApplicationWire.Job changed : List.of(
                new ApplicationWire.Job(job.generation(), job.helloDigest(), job.sequence(), job.jobId(), job.originalKey(), job.effectiveKey(), OTHER_TOKEN, job.payload()),
                new ApplicationWire.Job(job.generation(), job.helloDigest(), job.sequence(), job.jobId(), digest("wrong original"), job.effectiveKey(), job.deadlineIdentity(), job.payload()),
                new ApplicationWire.Job(job.generation(), job.helloDigest(), job.sequence(), job.jobId(), job.originalKey(), digest("wrong effective"), job.deadlineIdentity(), job.payload())))
            rejects(ApplicationWire.ErrorCode.IDENTITY, () -> matchRequest(LAUNCH, GENERATION, changed, request));
    }

    @Test void exactEffectiveLayoutAndOriginalDisjointnessAreCheckedAtMatching() {
        Request request = request();
        Request wrongJob = roots(request, SNAPSHOT_ROOT, ORIGINAL_CP_ROOT, ORIGINAL_OUTPUT_ROOT,
                root("generation/jobs/2", "job-2"), SOURCE_ROOT, CP_ROOT, OUTPUT_ROOT);
        rejects(ApplicationWire.ErrorCode.PATH, () -> matchRequest(LAUNCH, GENERATION, job(wrongJob), wrongJob));
        Request nestedOriginal = roots(request, root("generation/stolen", "stolen-key"), ORIGINAL_CP_ROOT,
                ORIGINAL_OUTPUT_ROOT, JOB_ROOT, SOURCE_ROOT, CP_ROOT, OUTPUT_ROOT);
        rejects(ApplicationWire.ErrorCode.PATH, () -> matchRequest(LAUNCH, GENERATION, job(nestedOriginal), nestedOriginal));
        Request overlappingOriginals = roots(request, SNAPSHOT_ROOT, root("archive/snapshot/classpath", "nested-cp"),
                ORIGINAL_OUTPUT_ROOT, JOB_ROOT, SOURCE_ROOT, CP_ROOT, OUTPUT_ROOT);
        rejects(ApplicationWire.ErrorCode.PATH, () -> matchRequest(LAUNCH, GENERATION, job(overlappingOriginals), overlappingOriginals));
    }

    @Test void reservedGenerationArtifactKeysCannotBeBorrowedByInputRoots() {
        Request request = request();
        ApplicationWire.Root borrowed = new ApplicationWire.Root(SNAPSHOT_ROOT.path(), GENERATION.bridge().fileKey());
        Request changed = roots(request, borrowed, ORIGINAL_CP_ROOT, ORIGINAL_OUTPUT_ROOT, JOB_ROOT, SOURCE_ROOT, CP_ROOT, OUTPUT_ROOT);
        rejects(ApplicationWire.ErrorCode.IDENTITY, () -> matchRequest(LAUNCH, GENERATION, job(changed), changed));
    }

    @Test void compilerVersionJdkMajorAndPinnedCompilerDigestAreExact() {
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> new Generation(PROFILE, API, "2.4.20", GENERATION.jdk(), GENERATION.bridge(), GENERATION.compilerArtifacts()));
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> new Jdk(GENERATION.jdk().home(), GENERATION.jdk().executable(),
                GENERATION.jdk().executableDigest(), GENERATION.jdk().runtimeDigest(), 17));
        Artifact compiler = GENERATION.compilerArtifacts().get(0);
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> new Generation(PROFILE, API, COMPILER_VERSION, GENERATION.jdk(),
                GENERATION.bridge(), List.of(new Artifact(compiler.coordinate(), compiler.fileName(), digest("not the pinned compiler"), compiler.size(), compiler.fileKey()))));
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> new Generation(PROFILE, API, COMPILER_VERSION, GENERATION.jdk(),
                GENERATION.bridge(), List.of(GENERATION.compilerArtifacts().get(1))));
    }

    @Test void generationArtifactClosureOrderKeysSizesAndBridgeCapAreBounded() {
        List<Artifact> original = GENERATION.compilerArtifacts();
        Generation reordered = new Generation(PROFILE, API, COMPILER_VERSION, GENERATION.jdk(), GENERATION.bridge(),
                List.of(original.get(1), original.get(0)));
        assertNotEquals(compilerClosureDigest(GENERATION), compilerClosureDigest(reordered));
        assertNotEquals(profileDigest(GENERATION), profileDigest(reordered));
        rejects(ApplicationWire.ErrorCode.DUPLICATE, () -> new Generation(PROFILE, API, COMPILER_VERSION,
                GENERATION.jdk(), GENERATION.bridge(), List.of(original.get(0), original.get(0))));
        Artifact largeBridge = new Artifact("test:bridge:1", "bridge.jar", digest("bridge"), MAX_BRIDGE_BYTES + 1, "bridge-copy");
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> new Generation(PROFILE, API, COMPILER_VERSION, GENERATION.jdk(), largeBridge, original));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> new Artifact("test:a:1", "a.jar", digest("a"), MAX_ARTIFACT_BYTES + 1, "a"));
    }

    @Test void launchIsFixedAndPathSeparatorsCannotExpandDeclaredClasspath() {
        assertTrue(command(GENERATION_ROOT, GENERATION).contains("-Xmx512m"));
        assertEquals(MAIN_CLASS, command(GENERATION_ROOT, GENERATION).get(command(GENERATION_ROOT, GENERATION).size() - 2));
        assertFalse(environment(GENERATION_ROOT, GENERATION).containsKey("JAVA_TOOL_OPTIONS"));
        ArrayList<String> altered = new ArrayList<>(LAUNCH.command()); altered.add("-javaagent:unapproved.jar");
        ApplicationWire.Launch injected = new ApplicationWire.Launch(LAUNCH.identity(), LAUNCH.limits(), LAUNCH.generationRoot(),
                LAUNCH.directory(), altered, LAUNCH.environment(), LAUNCH.payload());
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> matchGeneration(injected, GENERATION));
        ApplicationWire.Root separatorRoot = root("generation" + File.pathSeparator + "extra", "separator-key");
        ApplicationWire.Launch separatorLaunch = launch(separatorRoot, GENERATION);
        rejects(ApplicationWire.ErrorCode.PATH, () -> matchGeneration(separatorLaunch, GENERATION));
    }

    @Test void helloNeedsActualProfileVersionAndObservedOriginsAndReset() {
        rejects(ApplicationWire.ErrorCode.UNSUPPORTED, () -> matchHello(LAUNCH, GENERATION,
                new ApplicationWire.Hello(HELLO.identity(), HELLO.launchDigest(), HELLO.loaderToken(), "2.4.20", true, true)));
        rejects(ApplicationWire.ErrorCode.UNSEALED, () -> matchHello(LAUNCH, GENERATION,
                new ApplicationWire.Hello(HELLO.identity(), HELLO.launchDigest(), HELLO.loaderToken(), COMPILER_VERSION, false, true)));
        rejects(ApplicationWire.ErrorCode.UNSEALED, () -> matchHello(LAUNCH, GENERATION,
                new ApplicationWire.Hello(HELLO.identity(), HELLO.launchDigest(), HELLO.loaderToken(), COMPILER_VERSION, true, false)));
    }

    @Test void globalErrorWithMissingIdAndUnknownSourceIdRemainRawCodeOneResults() {
        Request request = request();
        for (Diagnostic diagnostic : List.of(error("warnings found and -Werror specified", "", ""),
                error("raw message", "src/Two.kt", "FUTURE_UNMAPPED_ID"))) {
            Observation observation = observation(request, 1, List.of(diagnostic));
            assertDoesNotThrow(() -> requireSealed(observation));
            assertEquals(List.of(diagnostic), decodeObservation(encodeObservation(observation)).diagnostics());
            ApplicationWire.Job job = job(request);
            assertDoesNotThrow(() -> matchObservation(LAUNCH, GENERATION, job, request, result(job, observation), observation));
        }
    }

    @Test void compilerCodeAndRawErrorPresenceMustAgree() {
        Request request = request();
        rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(observation(request, 0, List.of(error("error", "", "")))));
        rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(observation(request, 1, List.of())));
        assertDoesNotThrow(() -> requireSealed(observation(request, 0, List.of(info("warning-like informational message")))));
    }

    @Test void exceptionOutputCancellationAndInternalCodesAreFatalOnly() {
        Request request = request();
        for (String severity : List.of("EXCEPTION", "OUTPUT")) {
            Observation observation = observation(request, 1, List.of(error("error", "", ""),
                    new Diagnostic(severity, "raw internal payload not promised by profile", "", -1, -1, "")));
            rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(observation));
        }
        rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(observation(request, 2, List.of())));
        Observation base = observation(request, 0, List.of());
        for (String message : List.of("Compilation was canceled", "Compilation was cancelled")) {
            Observation cancelled = status(base, true, 0, 1, true, true, RESET, RESET, true, true, true,
                    FailureCode.NONE, List.of(info(message)));
            rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(cancelled));
            rejects(ApplicationWire.ErrorCode.IDENTITY, () -> observation(request, 0, List.of(info(message))));
        }
    }

    @Test void onlyCompleteDisposalResetAndRevalidationCanSeal() {
        Observation base = observation(request(), 0, List.of());
        Reset incomplete = new Reset(true, false, true, true);
        for (Observation broken : List.of(
                status(base, false, -1, 1, true, false, RESET, RESET, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 0, true, false, RESET, RESET, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 2, true, false, RESET, RESET, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, false, false, RESET, RESET, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, true, false, incomplete, RESET, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, true, false, RESET, incomplete, true, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, true, false, RESET, RESET, false, true, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, true, false, RESET, RESET, true, false, true, FailureCode.NONE, List.of()),
                status(base, true, 0, 1, true, false, RESET, RESET, true, true, false, FailureCode.NONE, List.of())))
            rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(broken));
        for (FailureCode failure : FailureCode.values()) if (failure != FailureCode.NONE)
            rejects(ApplicationWire.ErrorCode.UNSEALED, () -> requireSealed(status(base, true, 0, 1, true, false,
                    RESET, RESET, true, true, true, failure, List.of())));
    }

    @Test void noncompiledBasenameForeignAndNormalizedDiagnosticPathsAreRejected() {
        Request request = request(); ApplicationWire.Job job = job(request);
        for (String path : List.of("build.gradle.kts", "resources/blob.bin", "One.kt", "src/./One.kt",
                Path.of(SOURCE_ROOT.path()).resolve("other/One.kt").toString(),
                Path.of(SNAPSHOT_ROOT.path()).resolve("src/One.kt").toString())) {
            Observation observation = observation(request, 1, List.of(error("raw", path, "UNMAPPED")));
            rejects(ApplicationWire.ErrorCode.PATH,
                    () -> matchObservation(LAUNCH, GENERATION, job, request, result(job, observation), observation));
        }
        String exact = Path.of(SOURCE_ROOT.path()).resolve("src/One.kt").toString();
        Observation accepted = observation(request, 1, List.of(error("raw", exact, "")));
        assertDoesNotThrow(() -> matchObservation(LAUNCH, GENERATION, job, request, result(job, accepted), accepted));
    }

    @Test void diagnosticCoordinateStructureIsBoundedWithoutInventingMissingPoints() {
        rejects(ApplicationWire.ErrorCode.IDENTITY, () -> new Diagnostic("ERROR", "raw", "", 1, 1, ""));
        for (int value : new int[]{-2, 0, MAX_SOURCE_BYTES + 2})
            rejects(() -> new Diagnostic("ERROR", "raw", "src/One.kt", value, 1, ""));
        Request request = request(); ApplicationWire.Job job = job(request);
        for (Diagnostic diagnostic : List.of(new Diagnostic("ERROR", "raw", "src/One.kt", -1, -1, ""),
                new Diagnostic("ERROR", "raw", "src/One.kt", 1, 100, ""))) {
            Observation observation = observation(request, 1, List.of(diagnostic));
            rejects(ApplicationWire.ErrorCode.IDENTITY,
                    () -> matchObservation(LAUNCH, GENERATION, job, request, result(job, observation), observation));
        }
        // Byte-size bounds here are deliberately coarse; worker/host tests validate actual raw UTF16 lines.
    }

    @Test void diagnosticMessageCountAndEncodedAggregateHaveIndependentCaps() {
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> info("x".repeat(MAX_DIAGNOSTIC_MESSAGE_BYTES + 1)));
        assertDoesNotThrow(() -> observation(request(), 0, Collections.nCopies(MAX_DIAGNOSTICS, info("x"))));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> observation(request(), 0, Collections.nCopies(MAX_DIAGNOSTICS + 1, info("x"))));
        assertDoesNotThrow(() -> observation(request(), 0, Collections.nCopies(6, info("x".repeat(16_300)))));
        rejects(ApplicationWire.ErrorCode.LIMIT, () -> observation(request(), 0,
                Collections.nCopies(6, info("x".repeat(MAX_DIAGNOSTIC_MESSAGE_BYTES)))));
    }

    @Test void rawDiagnosticPayloadIsChecksummedAndNeverNormalized() {
        Diagnostic raw = new Diagnostic("ERROR", "raw\r\nmessage \ud83d\ude00", "src/One.kt", 1, 1, "UNKNOWN");
        Observation observation = observation(request(), 1, List.of(raw));
        byte[] encoded = encodeObservation(observation);
        assertEquals(raw, decodeObservation(encoded).diagnostics().get(0));
        byte[] tampered = encoded.clone();
        int offset = indexOf(tampered, raw.message().getBytes(StandardCharsets.UTF_8));
        assertTrue(offset >= 0); tampered[offset] = 'R';
        rejects(ApplicationWire.ErrorCode.DIGEST, () -> decodeObservation(tampered));
        assertNotEquals(diagnosticsDigest(List.of(raw)), diagnosticsDigest(List.of(
                new Diagnostic(raw.severity(), "raw\nmessage \ud83d\ude00", raw.path(), raw.line(), raw.column(), raw.diagnosticId()))));
    }

    @Test void workArgvAndResultEnvelopeCannotBeReboundToAnotherObservation() {
        Request request = request(); ApplicationWire.Job job = job(request);
        Observation base = observation(request, 0, List.of());
        for (Observation changed : List.of(digests(base, digest("wrong argv"), base.memberDigest()),
                digests(base, base.argvDigest(), digest("only selected members"))))
            rejects(ApplicationWire.ErrorCode.IDENTITY, () -> matchObservation(LAUNCH, GENERATION, job,
                    request, result(job, changed), changed));
        ApplicationWire.Result good = result(job, base);
        ApplicationWire.Result wrongRequest = new ApplicationWire.Result(good.generation(), good.helloDigest(), good.sequence(), good.jobId(),
                digest("foreign request"), good.originalKey(), good.effectiveKey(), good.loaderToken(), good.state(), good.payload());
        rejects(ApplicationWire.ErrorCode.IDENTITY, () -> matchObservation(LAUNCH, GENERATION, job, request, wrongRequest, base));
        ApplicationWire.Result wrongLoader = new ApplicationWire.Result(good.generation(), good.helloDigest(), good.sequence(), good.jobId(),
                good.requestDigest(), good.originalKey(), good.effectiveKey(), TOKEN, good.state(), good.payload());
        rejects(ApplicationWire.ErrorCode.IDENTITY, () -> ApplicationWire.matchResult(LAUNCH, HELLO, job, wrongLoader));
    }

    @Test void observationDetachesItsDiagnosticListAndResetChecksEveryBit() {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>(List.of(info("kept")));
        Observation observation = observation(request(), 0, diagnostics); diagnostics.clear();
        assertEquals(List.of(info("kept")), observation.diagnostics());
        assertThrows(UnsupportedOperationException.class, () -> observation.diagnostics().clear());
        assertTrue(RESET.complete());
        assertFalse(new Reset(false, true, true, true).complete());
        assertFalse(new Reset(true, false, true, true).complete());
        assertFalse(new Reset(true, true, false, true).complete());
        assertFalse(new Reset(true, true, true, false).complete());
    }

    private static ApplicationWire.Root root(String relative, String key) {
        return new ApplicationWire.Root(BASE.resolve(relative).toString(), key);
    }
    private static String digest(String text) { return ApplicationWire.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static Member member(String logical, long size, String key) { return new Member(logical, digest("member:" + logical), size, key); }
    private static Dependency dependency(String coordinate, String contentLabel, long size, String key) {
        return new Dependency(coordinate, digest(contentLabel), size, key);
    }
    private static Generation generation() {
        ApplicationWire.Root jdk = root("jdk21", "jdk-key");
        return new Generation(PROFILE, API, COMPILER_VERSION,
                new Jdk(jdk, Path.of(jdk.path()).resolve("bin/java").toString(), digest("synthetic executable"), digest("synthetic runtime"), 21),
                new Artifact("test:model-worker:1", "model-worker.jar", digest("synthetic bridge"), 128, "bridge-key"),
                List.of(new Artifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21", "kotlin-compiler-embeddable-2.4.21.jar",
                                COMPILER_SHA256, 128, "compiler-key"),
                        new Artifact("test:compiler-support:1", "support.jar", digest("synthetic support"), 80, "support-key")));
    }
    private static ApplicationWire.Launch launch(ApplicationWire.Root root, Generation generation) {
        ApplicationWire.Identity identity = new ApplicationWire.Identity(TOKEN, OTHER_TOKEN, digest("synthetic worktree"),
                digest("synthetic implementation"), generation.bridge().digest(), compilerClosureDigest(generation),
                toolchainDigest(generation), API, PROFILE);
        return new ApplicationWire.Launch(identity, new ApplicationWire.Limits(2, 262_144, 524_288), root, root.path(),
                command(root, generation), environment(root, generation), encodeGeneration(generation));
    }
    private static InputIdentity input(String analysis, String members) {
        return new InputIdentity(digest(analysis), digest("snapshot-object"), members, digest("model-object"),
                digest("dependency-object"), digest("configuration-object"), digest("toolchain-object"));
    }
    private static Request request() { return request(FILES, SELECTED, DEPENDENCIES, OPTIONS, OPTIONS, ORIGINAL_CP_ROOT); }
    private static Request request(List<Member> files, List<String> selected, List<Dependency> dependencies,
                                   List<String> originalOptions, List<String> effectiveOptions, ApplicationWire.Root originalClasspath) {
        String members = membersDigest(files);
        return new Request(PROFILE, digest("owner input"), digest("archived input"), digest("model binding"),
                input("original analysis", members), input("effective analysis", members), SNAPSHOT_ROOT, originalClasspath,
                ORIGINAL_OUTPUT_ROOT, JOB_ROOT, SOURCE_ROOT, CP_ROOT, OUTPUT_ROOT, files, selected, dependencies,
                originalOptions, effectiveOptions, profileDigest(GENERATION), TOKEN);
    }
    private static Request roots(Request r, ApplicationWire.Root snapshot, ApplicationWire.Root originalClasspath,
                                 ApplicationWire.Root originalOutput, ApplicationWire.Root job, ApplicationWire.Root sources,
                                 ApplicationWire.Root classpath, ApplicationWire.Root output) {
        return new Request(r.profile(), r.ownerInputKey(), r.archivedInputKey(), r.modelBindingDigest(), r.original(), r.effective(),
                snapshot, originalClasspath, originalOutput, job, sources, classpath, output, r.files(), r.compilationPaths(),
                r.dependencies(), r.originalOptions(), r.effectiveOptions(), r.profileDigest(), r.deadlineIdentity());
    }
    private static Request identities(Request r, InputIdentity original, InputIdentity effective,
                                      String owner, String archived, String binding) {
        return new Request(r.profile(), owner, archived, binding, original, effective, r.snapshotRoot(), r.originalClasspathRoot(),
                r.originalOutputRoot(), r.jobRoot(), r.sourceRoot(), r.classpathRoot(), r.outputRoot(), r.files(), r.compilationPaths(),
                r.dependencies(), r.originalOptions(), r.effectiveOptions(), r.profileDigest(), r.deadlineIdentity());
    }
    private static ApplicationWire.Job job(Request request) {
        return new ApplicationWire.Job(TOKEN, ApplicationWire.helloDigest(HELLO), 1, ApplicationWire.jobId(TOKEN, 1),
                request.original().analysisKey(), request.effective().analysisKey(), request.deadlineIdentity(), encodeRequest(request));
    }
    private static Observation observation(Request request, int code, List<Diagnostic> diagnostics) {
        return new Observation(COMPILER_VERSION, argvDigest(arguments(LAUNCH, GENERATION, request)),
                workDigest(request.files(), request.compilationPaths(), request.dependencies(), request.originalOptions()),
                request.jobRoot(), request.sourceRoot(), request.outputRoot(), true, code, 1, true, false,
                RESET, RESET, true, true, true, FailureCode.NONE, diagnostics);
    }
    private static Observation status(Observation base, boolean returned, int code, int disposals, boolean disposed,
                                      boolean cancelled, Reset before, Reset after, boolean sealed, boolean inputs,
                                      boolean outputs, FailureCode failure, List<Diagnostic> diagnostics) {
        return new Observation(base.compilerVersion(), base.argvDigest(), base.memberDigest(), base.jobRoot(), base.sourceRoot(), base.outputRoot(),
                returned, code, disposals, disposed, cancelled, before, after, sealed, inputs, outputs, failure, diagnostics);
    }
    private static Observation digests(Observation base, String argv, String members) {
        return new Observation(base.compilerVersion(), argv, members, base.jobRoot(), base.sourceRoot(), base.outputRoot(),
                base.compilationReturned(), base.compilerCode(), base.disposalCount(), base.disposalCompleted(), base.cancellationObserved(),
                base.beforeReset(), base.afterReset(), base.collectorSealed(), base.inputsRevalidated(), base.outputsRevalidated(),
                base.failureCode(), base.diagnostics());
    }
    private static ApplicationWire.Result result(ApplicationWire.Job job, Observation observation) {
        return new ApplicationWire.Result(job.generation(), job.helloDigest(), job.sequence(), job.jobId(), ApplicationWire.requestDigest(job),
                job.originalKey(), job.effectiveKey(), HELLO.loaderToken(), ApplicationWire.ResultState.APPLICATION_SEALED, encodeObservation(observation));
    }
    private static Diagnostic error(String message, String path, String id) {
        return new Diagnostic("ERROR", message, path, path.isEmpty() ? -1 : 1, path.isEmpty() ? -1 : 1, id);
    }
    private static Diagnostic info(String message) { return new Diagnostic("INFO", message, "", -1, -1, ""); }
    private static void rejects(Runnable operation) { assertThrows(ApplicationWire.ProtocolException.class, operation::run); }
    private static void rejects(ApplicationWire.ErrorCode code, Runnable operation) {
        assertEquals(code, assertThrows(ApplicationWire.ProtocolException.class, operation::run).code());
    }
    private static int indexOf(byte[] value, byte[] needle) {
        for (int i = 0; i <= value.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && value[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }
}
