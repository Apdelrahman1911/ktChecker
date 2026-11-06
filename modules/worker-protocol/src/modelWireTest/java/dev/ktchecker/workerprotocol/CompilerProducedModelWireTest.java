package dev.ktchecker.workerprotocol;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static dev.ktchecker.workerprotocol.CompilerModelWire.*;
import static org.junit.jupiter.api.Assertions.*;

/** Detached synthetic DTO/codec controls only. Not a producer-success or compiler-execution test. */
class CompilerProducedModelWireTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";
    private static final Path BASE = Path.of("produced-wire-data").toAbsolutePath().normalize();
    private static final List<Member> FILES = List.of(
        new Member("src/main/Api.kt", sha("main source"), 11, "main-key"),
        new Member("src/test/Use.kt", sha("test source"), 11, "test-key"),
        new Member("build.gradle", sha("configuration"), 13, "config-key"));
    private static final List<Dependency> DEPENDENCIES = List.of(
        new Dependency(producedArtifactCoordinate(":||main"), sha("complete produced JAR"), 140, "produced-copy-key"),
        new Dependency("data:stdlib:1", sha("stdlib"), 100, "stdlib-copy-key"));
    private static final List<String> OPTIONS = List.of("-jvm-target", "21", "-module-name", "consumer");

    @Test void exactRawCompilationIdsKeepTheRootModuleEmptyTargetAndNamedModule() {
        for (String raw : List.of(":||main", ":||test", ":shared:api|desktop|main", ":shared-api||test",
                ":||" + "x".repeat(ApplicationWire.MAX_ID_BYTES - 3))) {
            ProducedExecution execution = new ProducedExecution(sha("plan"), raw, sha("raw context"), sha("resolution"), List.of());
            ProducedDependency dependency = new ProducedDependency(0, raw, sha("reference"), TOKEN, 1,
                sha("binding"), sha("request"), sha("result"), producedOutputDigest(tree()), true);
            assertEquals(raw, execution.compilationId());
            assertEquals(raw, dependency.producerCompilationId());
            assertEquals(execution, decodeRequest(encodeRequest(request(execution, true))).producedExecution());
        }
    }

    @Test void malformedAndOversizedCompilationIdsNeverBecomeLineageOrArtifactLabels() {
        for (String invalid : List.of("", "main", "root||main", ":|main", ":||", ":|||main", ":||main|extra",
                ":bad:||main", ":bad::nested||main", ":| |main", ":|| ", ":||main\n", ":||main\u0000",
                ":||" + "x".repeat(ApplicationWire.MAX_ID_BYTES - 2), ":||" + "x".repeat(ApplicationWire.MAX_ID_BYTES))) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedExecution(
                sha("plan"), invalid, sha("raw context"), sha("resolution"), List.of()), invalid);
            assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedDependency(0, invalid,
                sha("reference"), TOKEN, 1, sha("binding"), sha("request"), sha("result"), producedOutputDigest(tree()), false), invalid);
            assertThrows(ApplicationWire.ProtocolException.class, () -> producedArtifactCoordinate(invalid), invalid);
        }
    }

    @Test void producedArtifactLabelsAreIdentifierSafeWithoutChangingTheRawIdentity() {
        String coordinate = producedArtifactCoordinate(":||main");
        assertEquals("produced-jvm:" + sha(":||main").substring(7), coordinate);
        assertTrue(coordinate.matches("produced-jvm:[a-f0-9]{64}"));
        assertDoesNotThrow(() -> new Dependency(coordinate, sha("output"), 1, "file-key"));
        assertThrows(ApplicationWire.ProtocolException.class,
            () -> new Dependency("produced-jvm::||main", sha("output"), 1, "file-key"));
        for (String other : List.of(":||test", ":lib||main", ":|jvm|main", ":||Main"))
            assertNotEquals(coordinate, producedArtifactCoordinate(other));
        assertEquals(":||main", dependency(true).producerCompilationId());
    }

    @Test void legacyOverloadAndEncodingRemainUnchangedAndProducedKindIsDistinct() {
        Request legacy = request(null, false);
        Request oldConstructor = new Request(legacy.profile(), legacy.ownerInputKey(), legacy.archivedInputKey(), legacy.modelBindingDigest(),
            legacy.original(), legacy.effective(), legacy.snapshotRoot(), legacy.originalClasspathRoot(), legacy.originalOutputRoot(),
            legacy.jobRoot(), legacy.sourceRoot(), legacy.classpathRoot(), legacy.outputRoot(), legacy.files(), legacy.compilationPaths(),
            legacy.dependencies(), legacy.originalOptions(), legacy.effectiveOptions(), legacy.profileDigest(), legacy.deadlineIdentity());
        assertArrayEquals(encodeRequest(legacy), encodeRequest(oldConstructor));
        assertEquals(2, encodeRequest(legacy)[0]);
        assertEquals(workDigest(FILES, legacy.compilationPaths(), DEPENDENCIES, OPTIONS), workDigest(legacy));
        Request produced = request(execution(dependency(true)), true);
        assertEquals(4, encodeRequest(produced)[0]);
        assertEquals(produced, decodeRequest(encodeRequest(produced)));
        assertEquals(legacy, decodeRequest(encodeRequest(legacy)));
        byte[] wrongKind = encodeRequest(produced); wrongKind[0] = 2;
        assertThrows(ApplicationWire.ProtocolException.class, () -> decodeRequest(wrongKind));
        assertThrows(ApplicationWire.ProtocolException.class, () -> request(execution(dependency(true)), false));
        assertThrows(ApplicationWire.ProtocolException.class, () -> request(null, true));
    }

    @Test void exactlyOneFriendOrdinalIsProjectedAndNeitherCompilationSourcesNorClasspathAreMerged() {
        Generation generation = generation(true); ApplicationWire.Launch launch = launch(generation);
        Request friend = request(execution(dependency(true)), true);
        List<String> arguments = arguments(launch, generation, friend);
        String copied = Path.of(friend.classpathRoot().path()).resolve("artifact-0.jar").toString();
        assertTrue(arguments.contains("-Xfriend-paths=" + copied));
        assertEquals(1L, arguments.stream().filter(a -> a.startsWith("-Xfriend-paths=")).count());
        assertEquals(copied + File.pathSeparator + Path.of(friend.classpathRoot().path()).resolve("artifact-1.jar"),
            arguments.get(arguments.indexOf("-classpath") + 1));
        assertEquals(Path.of(friend.sourceRoot().path()).resolve("src/test/Use.kt").toString(), arguments.get(arguments.size() - 1));
        assertFalse(arguments.contains(Path.of(friend.sourceRoot().path()).resolve("src/main/Api.kt").toString()));
        assertEquals(FILES, friend.files()); // The full snapshot still includes BOTH source domains and configuration.
        Request nonfriend = request(execution(dependency(false)), true);
        assertTrue(arguments(launch, generation, nonfriend).stream().noneMatch(a -> a.startsWith("-Xfriend-paths=")));
        assertEquals(friend.originalOptions(), nonfriend.originalOptions());
        assertEquals(friend.dependencies(), nonfriend.dependencies());
        assertNotEquals(workDigest(friend), workDigest(nonfriend));
    }

    @Test void logicalWorkDigestBindsEveryLineageFieldNotOnlyDependencyBytes() {
        ProducedDependency d = dependency(true);
        Request original = request(execution(d), true);
        List<ProducedDependency> variants = List.of(
            new ProducedDependency(1, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, ":||different", d.referenceDigest(), TOKEN, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), sha("different reference"), TOKEN, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), OTHER, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 2, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1, sha("different binding"), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1, d.producerBindingDigest(), sha("different request"), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1, d.producerBindingDigest(), d.requestDigest(), sha("different result"), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), sha("different tree"), true),
            dependency(false));
        for (ProducedDependency changed : variants) {
            Request candidate = request(execution(changed), true);
            assertEquals(original.files(), candidate.files());
            assertEquals(original.dependencies(), candidate.dependencies());
            assertEquals(original.original(), candidate.original());
            assertNotEquals(workDigest(original), workDigest(candidate));
        }
        for (ProducedExecution changed : List.of(
            new ProducedExecution(sha("different plan"), ":||test", sha("raw context"), sha("resolution"), List.of(d)),
            new ProducedExecution(sha("plan"), ":||test", sha("different raw context"), sha("resolution"), List.of(d))))
            assertNotEquals(workDigest(original), workDigest(request(changed, true)));
    }

    @Test void crossGenerationSameOrLaterSequenceAndUnboundOrdinalAreRefused() {
        Generation generation = generation(true); ApplicationWire.Launch launch = launch(generation);
        ProducedDependency d = dependency(true);
        Request good = request(execution(d), true);
        assertDoesNotThrow(() -> matchRequest(launch, generation, job(launch, good), good));
        for (ProducedDependency wrong : List.of(
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), OTHER, 1, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 2, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true),
            new ProducedDependency(0, d.producerCompilationId(), d.referenceDigest(), TOKEN, 3, d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true))) {
            Request bad = request(execution(wrong), true);
            assertThrows(ApplicationWire.ProtocolException.class, () -> matchRequest(launch, generation, job(launch, bad), bad));
        }
        ProducedDependency badOrdinal = new ProducedDependency(2, d.producerCompilationId(), d.referenceDigest(), TOKEN, 1,
            d.producerBindingDigest(), d.requestDigest(), d.resultDigest(), d.outputDigest(), true);
        assertThrows(ApplicationWire.ProtocolException.class, () -> request(execution(badOrdinal), true));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedExecution(sha("plan"), ":||main",
            sha("raw context"), sha("resolution"), List.of(d)));
        assertThrows(ApplicationWire.ProtocolException.class, () -> request(new ProducedExecution(sha("plan"), ":||test",
            sha("raw context"), sha("wrong resolved dependency"), List.of(d)), true));
    }

    @Test void completeInventoryIncludesDirectoriesAndKotlinMetadataAndRejectsUnboundedOrForeignEntries() {
        List<ProducedMember> complete = tree();
        String digest = producedOutputDigest(complete);
        ArrayList<ProducedMember> missingModule = new ArrayList<>(complete); missingModule.remove(1);
        assertNotEquals(digest, producedOutputDigest(missingModule)); // Comparison to sealed inventory is separately mandatory.
        ArrayList<ProducedMember> changedModule = new ArrayList<>(complete);
        changedModule.set(1, new ProducedMember("META-INF/main.kotlin_module", false, 3, sha("changed module metadata")));
        assertNotEquals(digest, producedOutputDigest(changedModule));
        for (List<ProducedMember> invalid : List.of(List.<ProducedMember>of(), List.of(complete.get(3)),
            List.of(complete.get(0), complete.get(1)), List.of(complete.get(0), complete.get(0)),
            List.of(complete.get(2), complete.get(0), complete.get(1), complete.get(3))))
            assertThrows(ApplicationWire.ProtocolException.class, () -> producedOutputDigest(invalid));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedMember("src/Api.kt", false, 5, sha("source")));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedMember("META-INF/MANIFEST.MF", false, 5, sha("manifest")));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedMember("../Escape.class", false, 5, sha("escape")));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedMember("p", true, 1, null));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedMember("Huge.class", false, MAX_JOB_OUTPUT_BYTES + 1, sha("huge")));
    }

    @Test void producedMetadataIsDetachedBoundedAndCannotEvadeExistingRequestFrame() {
        ArrayList<ProducedDependency> values = new ArrayList<>(List.of(dependency(true)));
        ProducedExecution value = new ProducedExecution(sha("plan"), ":||test", sha("raw context"), sha("resolution"), values);
        values.clear(); assertEquals(1, value.producers().size());
        assertThrows(UnsupportedOperationException.class, () -> value.producers().clear());
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ProducedExecution(sha("plan"), ":||test",
            sha("raw context"), sha("resolution"), List.of(dependency(true), dependency(false))));
        byte[] body = encodeRequest(request(value, true));
        assertThrows(ApplicationWire.ProtocolException.class, () -> decodeRequest(Arrays.copyOf(body, body.length - 1)));
        assertThrows(ApplicationWire.ProtocolException.class, () -> decodeRequest(Arrays.copyOf(body, body.length + 1)));
        assertThrows(ApplicationWire.ProtocolException.class, () -> decodeRequest(new byte[ApplicationWire.MAX_JOB_BODY_BYTES + 1]));
        assertEquals(64, MAX_COMPILATION_FILES);
    }

    private static List<ProducedMember> tree() { return List.of(new ProducedMember("META-INF", true, 0, null),
        new ProducedMember("META-INF/main.kotlin_module", false, 3, sha("module")), new ProducedMember("p", true, 0, null),
        new ProducedMember("p/Api.class", false, 5, sha("class"))); }
    private static ProducedDependency dependency(boolean friend) { return new ProducedDependency(0, ":||main", sha("reference"), TOKEN, 1,
        sha("producer binding"), sha("producer request"), sha("producer result"), producedOutputDigest(tree()), friend); }
    private static ProducedExecution execution(ProducedDependency dependency) {
        return new ProducedExecution(sha("plan"), ":||test", sha("raw context"), sha("resolution"), List.of(dependency));
    }
    private static String sha(String value) { return ApplicationWire.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static ApplicationWire.Root root(String path, String key) { return new ApplicationWire.Root(BASE.resolve(path).toString(), key); }
    private static Generation generation(boolean produced) {
        ApplicationWire.Root jdk = root("jdk", "jdk-key");
        return new Generation(produced ? PRODUCED_PROFILE : PROFILE, produced ? PRODUCED_API : API, COMPILER_VERSION,
            new Jdk(jdk, Path.of(jdk.path()).resolve("bin/java").toString(), sha("SYNTHETIC-java"), sha("SYNTHETIC-jdk"), 21),
            new Artifact("data:bridge:1", "bridge.jar", sha("SYNTHETIC-bridge"), 128, "bridge-key"),
            List.of(new Artifact("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21", "kotlin-compiler-embeddable-2.4.21.jar",
                COMPILER_SHA256, 128, "compiler-key")));
    }
    private static ApplicationWire.Launch launch(Generation generation) {
        ApplicationWire.Root root = root("generation", "generation-key");
        ApplicationWire.Identity identity = new ApplicationWire.Identity(TOKEN, OTHER, sha("worktree"), sha("implementation"),
            generation.bridge().digest(), compilerClosureDigest(generation), toolchainDigest(generation), generation.apiVersion(), generation.profile());
        // Include frame headers and control frames so the lineage tests reach request matching.
        int jobs = 4;
        int inputLimit = (ApplicationWire.MAX_JOB_BYTES + 4) * jobs;
        int outputLimit = ApplicationWire.MAX_HELLO_BYTES + 4 + ApplicationWire.MAX_FATAL_BYTES + 4
            + (ApplicationWire.MAX_RESULT_BYTES + 4) * jobs;
        return new ApplicationWire.Launch(identity, new ApplicationWire.Limits(jobs, inputLimit, outputLimit), root, root.path(),
            command(root, generation), environment(root, generation), encodeGeneration(generation));
    }
    private static InputIdentity input(String label) { return new InputIdentity(sha(label), sha("snapshot"), membersDigest(FILES),
        sha("model"), sha("resolution"), sha("configuration"), sha("toolchain")); }
    private static Request request(ProducedExecution execution, boolean produced) {
        return new Request(produced ? PRODUCED_PROFILE : PROFILE, sha("P"), sha("K"), sha("binding"), input("C"), input("E"),
            root("archive/sources", "archive-sources"), root("archive/classpath", "archive-classpath"), root("attempt", "attempt-output"),
            root("generation/jobs/2", "job-key"), root("generation/jobs/2/sources", "sources-key"),
            root("generation/jobs/2/classpath", "classpath-key"), root("generation/jobs/2/classes", "output-key"),
            FILES, List.of("src/test/Use.kt"), DEPENDENCIES, OPTIONS, OPTIONS, profileDigest(generation(produced)), TOKEN, execution);
    }
    private static ApplicationWire.Job job(ApplicationWire.Launch launch, Request request) {
        ApplicationWire.Hello hello = new ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), OTHER, COMPILER_VERSION, true, true);
        return new ApplicationWire.Job(TOKEN, ApplicationWire.helloDigest(hello), 2, ApplicationWire.jobId(TOKEN, 2),
            request.original().analysisKey(), request.effective().analysisKey(), TOKEN, encodeRequest(request));
    }
}
