package dev.ktchecker.workerprotocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static dev.ktchecker.workerprotocol.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CompilerJobWireTest {
    @Test void completeDetachedCompilerTupleRoundTripsAndBinds() {
        var generation = generation(); var launch = launch(generation, 9);
        var request = request(generation, 1); var job = job(launch, request, 1);
        var observation = observation(launch, generation, request, 1, List.of(error(request)));
        var result = result(job, observation);
        assertEquals(generation, CompilerJobWire.decodeGeneration(CompilerJobWire.encodeGeneration(generation)));
        assertEquals(request, CompilerJobWire.decodeRequest(CompilerJobWire.encodeRequest(request)));
        assertEquals(observation, CompilerJobWire.decodeObservation(CompilerJobWire.encodeObservation(observation)));
        CompilerJobWire.matchGeneration(launch, generation);
        CompilerJobWire.matchHello(launch, generation, hello(launch));
        CompilerJobWire.matchRequest(launch, generation, job, request);
        ApplicationWire.matchResult(launch, hello(launch), job, result);
        CompilerJobWire.matchObservation(launch, generation, job, request, result, observation);
        assertNotEquals(request.original().analysisKey(), request.effective().analysisKey());
        assertNotEquals(request.original().snapshotDigest(), request.effective().snapshotDigest());
        // Framework installation identity must not be silently rewritten to the relocated JDK one.
        assertNotEquals(launch.identity().toolchainDigest(), request.original().toolchainDigest());
    }

    @Test void listsAreDeeplyImmutableAndOrderRemainsSignificant() {
        var original = generation();
        var artifacts = new ArrayList<>(original.compilerArtifacts());
        var dependencies = new ArrayList<>(original.dependencyIndexes());
        var generation = new CompilerJobWire.Generation(original.profile(), original.apiVersion(), original.compilerVersion(),
                original.jvmTarget(), original.jdk(), original.bridge(), artifacts, dependencies);
        byte[] before = CompilerJobWire.encodeGeneration(generation);
        artifacts.clear(); dependencies.clear();
        assertArrayEquals(before, CompilerJobWire.encodeGeneration(generation));
        assertThrows(UnsupportedOperationException.class, () -> generation.compilerArtifacts().clear());
        assertThrows(UnsupportedOperationException.class, () -> generation.dependencyIndexes().add(0));
        var reordered = new CompilerJobWire.Generation(original.profile(), original.apiVersion(), original.compilerVersion(),
                original.jvmTarget(), original.jdk(), original.bridge(), List.of(original.compilerArtifacts().get(0),
                original.compilerArtifacts().get(2), original.compilerArtifacts().get(1)), List.of(1, 2));
        assertNotEquals(CompilerJobWire.compilerClosureDigest(generation), CompilerJobWire.compilerClosureDigest(reordered));
        assertNotEquals(CompilerJobWire.profileDigest(generation), CompilerJobWire.profileDigest(reordered));
        var request = request(generation, 1);
        var sources = new ArrayList<>(request.sources()); var options = new ArrayList<>(CompilerJobWire.M0_OPTIONS);
        var frozen = new CompilerJobWire.Request(request.profile(), request.original(), request.effective(), request.snapshotRoot(),
                request.jobRoot(), request.sourceRoot(), request.outputRoot(), sources, options, options, request.profileDigest(), request.deadlineIdentity());
        byte[] requestBefore = CompilerJobWire.encodeRequest(frozen);
        sources.clear(); options.clear();
        assertArrayEquals(requestBefore, CompilerJobWire.encodeRequest(frozen));
        assertThrows(UnsupportedOperationException.class, () -> frozen.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.originalOptions().clear());
        var diagnostics = new ArrayList<>(List.of(error(request)));
        var observation = observation(launch(generation, 9), generation, request, 1, diagnostics);
        byte[] observationBefore = CompilerJobWire.encodeObservation(observation);
        diagnostics.clear();
        assertArrayEquals(observationBefore, CompilerJobWire.encodeObservation(observation));
        assertThrows(UnsupportedOperationException.class, () -> observation.diagnostics().clear());
    }

    @Test void identicalLogicalBytesDoNotMakePhysicalRootsOrKeysInterchangeable() {
        var generation = generation(); var launch = launch(generation, 9);
        var one = request(generation, 1, source("source-a", "fun sample() = 1\n"));
        var two = request(generation, 1, source("source-b", "fun sample() = 1\n"));
        assertEquals(one.original().memberDigest(), two.original().memberDigest());
        assertFalse(Arrays.equals(CompilerJobWire.encodeRequest(one), CompilerJobWire.encodeRequest(two)));
        var jobOne = job(launch, one, 1); var jobTwo = job(launch, two, 1);
        assertNotEquals(ApplicationWire.requestDigest(jobOne), ApplicationWire.requestDigest(jobTwo));
        var observation = observation(launch, generation, one, 0, List.of()); var result = result(jobOne, observation);
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchResult(launch, hello(launch), jobTwo, result));
        var changedBytes = request(generation, 1, source("source-a", "fun sample() = 2\n"));
        assertNotEquals(one.original().memberDigest(), changedBytes.original().memberDigest());
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchRequest(launch, generation, jobOne, changedBytes));
    }

    @Test void outerAndInnerIdentityAliasesAreRejectedEvenWithValidIndividualDigests() {
        var generation = generation(); var launch = launch(generation, 9);
        var request = request(generation, 1); var job = job(launch, request, 1);
        for (String field : List.of("original", "effective", "deadline", "body")) {
            var changed = new ApplicationWire.Job(job.generation(), job.helloDigest(), job.sequence(), job.jobId(),
                    field.equals("original") ? hash("other") : job.originalKey(),
                    field.equals("effective") ? hash("other") : job.effectiveKey(),
                    field.equals("deadline") ? "f".repeat(32) : job.deadlineIdentity(),
                    field.equals("body") ? CompilerJobWire.encodeRequest(request(generation, 2)) : job.payload());
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchRequest(launch, generation, changed, request), field);
        }
        var badMembers = new CompilerJobWire.InputIdentity(request.original().analysisKey(), request.original().snapshotDigest(),
                hash("foreign-members"), request.original().modelDigest(), request.original().dependencyDigest(),
                request.original().configurationDigest(), request.original().toolchainDigest());
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Request(request.profile(), badMembers,
                request.effective(), request.snapshotRoot(), request.jobRoot(), request.sourceRoot(), request.outputRoot(),
                request.sources(), request.originalOptions(), request.effectiveOptions(), request.profileDigest(), request.deadlineIdentity()));
    }

    @Test void rootsRejectTraversalReuseForeignSequenceAndKnownArtifactAliases() {
        var generation = generation(); var launch = launch(generation, 9);
        var request = request(generation, 1);
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Request(request.profile(), request.original(),
                request.effective(), request.snapshotRoot(), request.jobRoot(), request.sourceRoot(),
                new ApplicationWire.Root(request.outputRoot().path(), request.sourceRoot().fileKey()), request.sources(),
                request.originalOptions(), request.effectiveOptions(), request.profileDigest(), request.deadlineIdentity()));
        var foreign = request(generation, 2);
        var badSequence = new ApplicationWire.Job(GENERATION, ApplicationWire.helloDigest(hello(launch)), 1,
                ApplicationWire.jobId(GENERATION, 1), foreign.original().analysisKey(), foreign.effective().analysisKey(),
                foreign.deadlineIdentity(), CompilerJobWire.encodeRequest(foreign));
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchRequest(launch, generation, badSequence, foreign));
        var alias = new CompilerJobWire.Request(request.profile(), request.original(), request.effective(),
                new ApplicationWire.Root(request.snapshotRoot().path(), generation.bridge().fileKey()), request.jobRoot(),
                request.sourceRoot(), request.outputRoot(), request.sources(), request.originalOptions(), request.effectiveOptions(),
                request.profileDigest(), request.deadlineIdentity());
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchRequest(launch, generation, job(launch, alias, 1), alias));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Source("../Fixture.kt", hash("x"), 1, "source"));
    }

    @Test void fixedM0ProfileRejectsUnknownVersionsSourcesPluginsAndOptionsWithoutNormalization() {
        var generation = generation(); var request = request(generation, 1);
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation("other", generation.apiVersion(),
                generation.compilerVersion(), 17, generation.jdk(), generation.bridge(), generation.compilerArtifacts(), generation.dependencyIndexes()));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation(generation.profile(), generation.apiVersion(),
                generation.compilerVersion(), 21, generation.jdk(), generation.bridge(), generation.compilerArtifacts(), generation.dependencyIndexes()));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Jdk(generation.jdk().home(), generation.jdk().executable(),
                generation.jdk().executableDigest(), generation.jdk().runtimeDigest(), 17));
        for (String source : List.of("Other.kt", "Fixture.java", "Fixture.kts", "./Fixture.kt", "fixture.kt")) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Source(source, hash("source"), 10, "key"));
        }
        for (long size : new long[] {0, -1, Long.MAX_VALUE, CompilerJobWire.MAX_SOURCE_BYTES + 1L}) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Source("Fixture.kt", hash("source"), size, "key"));
        }
        for (List<String> options : List.of(List.of("-jvm-target", "21"), List.of("-Xplugin=evil.jar", "17"),
                List.of("17", "-jvm-target"), List.of("-jvm-target=17"), List.<String>of())) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Request(request.profile(), request.original(), request.effective(),
                    request.snapshotRoot(), request.jobRoot(), request.sourceRoot(), request.outputRoot(), request.sources(), options,
                    request.effectiveOptions(), request.profileDigest(), request.deadlineIdentity()));
        }
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Request(request.profile(), request.original(), request.effective(),
                request.snapshotRoot(), request.jobRoot(), request.sourceRoot(), request.outputRoot(), List.of(request.sources().get(0), request.sources().get(0)),
                request.originalOptions(), request.effectiveOptions(), request.profileDigest(), request.deadlineIdentity()));
    }

    @Test void artifactCountsOrderingPinsSizesAndDuplicateNamesCoordinatesOrKeysAreStrict() {
        var generation = generation(); var artifacts = generation.compilerArtifacts();
        for (List<Integer> indexes : List.of(List.of(2, 1), List.of(1, 1), List.of(-1), List.of(3), List.of(2))) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation(generation.profile(), generation.apiVersion(),
                    generation.compilerVersion(), 17, generation.jdk(), generation.bridge(), artifacts, indexes));
        }
        var compiler = artifacts.get(0); var stdlib = artifacts.get(1);
        for (var bad : List.of(new CompilerJobWire.Artifact(compiler.coordinate(), compiler.fileName(), hash("unreviewed"), 10, compiler.fileKey()),
                new CompilerJobWire.Artifact(compiler.coordinate(), compiler.fileName(), compiler.digest(), 10, generation.bridge().fileKey()))) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation(generation.profile(), generation.apiVersion(),
                    generation.compilerVersion(), 17, generation.jdk(), generation.bridge(), List.of(bad, stdlib), List.of(1)));
        }
        var duplicateName = new CompilerJobWire.Artifact("different-coordinate", stdlib.fileName(), stdlib.digest(), stdlib.size(), "new-key");
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation(generation.profile(), generation.apiVersion(),
                generation.compilerVersion(), 17, generation.jdk(), generation.bridge(), List.of(compiler, stdlib, duplicateName), List.of(1)));
        var hugeCompiler = new CompilerJobWire.Artifact(compiler.coordinate(), compiler.fileName(), compiler.digest(), CompilerJobWire.MAX_ARTIFACT_BYTES, compiler.fileKey());
        var hugeStdlib = new CompilerJobWire.Artifact(stdlib.coordinate(), stdlib.fileName(), stdlib.digest(), CompilerJobWire.MAX_ARTIFACT_BYTES, stdlib.fileKey());
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Generation(generation.profile(), generation.apiVersion(),
                generation.compilerVersion(), 17, generation.jdk(), generation.bridge(), List.of(hugeCompiler, hugeStdlib, artifacts.get(2)), List.of(1)));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Artifact("coord", "../bad.jar", hash("x"), 1, "key"));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Artifact("coord", "valid.jar", hash("x"), Long.MIN_VALUE, "key"));
    }

    @Test void commandEnvironmentAndArgvAreExactSharedProfileNotInjectableText() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1);
        assertEquals(CompilerJobWire.command(launch.generationRoot(), generation), launch.command());
        assertEquals(CompilerJobWire.environment(launch.generationRoot(), generation), launch.environment());
        List<String> argv = CompilerJobWire.arguments(launch, generation, request);
        assertEquals(List.of("-no-stdlib", "-no-reflect", "-Xrender-internal-diagnostic-names", "-jvm-target", "17",
                "-jdk-home", generation.jdk().home().path(), "-d", request.outputRoot().path(), "-classpath",
                child(ROOT, "compiler", "1.jar") + java.io.File.pathSeparator + child(ROOT, "compiler", "2.jar"),
                child(request.sourceRoot().path(), "Fixture.kt")), argv);
        assertThrows(UnsupportedOperationException.class, () -> argv.add("-Xplugin=foreign.jar"));
        var command = new ArrayList<>(launch.command()); command.add("-javaagent=foreign.jar");
        var changed = new ApplicationWire.Launch(launch.identity(), launch.limits(), launch.generationRoot(), launch.directory(),
                command, launch.environment(), launch.payload());
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchGeneration(changed, generation));
        var environment = new LinkedHashMap<>(launch.environment()); environment.put("UNREVIEWED", "value");
        var foreign = new ApplicationWire.Launch(launch.identity(), launch.limits(), launch.generationRoot(), launch.directory(),
                launch.command(), environment, launch.payload());
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchGeneration(foreign, generation));
        var wrongObserved = new ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), LOADER, "2.5.0", true, true);
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchHello(launch, generation, wrongObserved));
    }

    @Test void everyDisposalResetSealReturnCancellationAndClosureFailureRemainsUnsealable() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1);
        var observation = observation(launch, generation, request, 0, List.of());
        CompilerJobWire.requireSealed(observation);
        for (String field : List.of("version", "returned", "code", "dispose-zero", "dispose-two", "dispose-completed", "cancelled",
                "before-app", "before-env", "before-keepalive", "before-loader", "after-app", "after-env", "after-keepalive", "after-loader",
                "collector", "inputs", "outputs", "failure")) {
            var changed = changed(observation, field);
            assertEquals(changed, CompilerJobWire.decodeObservation(CompilerJobWire.encodeObservation(changed)));
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.requireSealed(changed), field);
        }
        var internal = new CompilerJobWire.Diagnostic("EXCEPTION", "not a user finding", "", -1, -1, "");
        var raw = new CompilerJobWire.Diagnostic("OUTPUT", "not a protocol log", "", -1, -1, "");
        for (var diagnostic : List.of(internal, raw)) {
            var invalid = observation(launch, generation, request, 0, List.of(diagnostic));
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.requireSealed(invalid));
        }
        var codeZeroError = observation(launch, generation, request, 0, List.of(error(request)));
        var codeOneNoErrors = observation(launch, generation, request, 1, List.of());
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.requireSealed(codeZeroError));
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.requireSealed(codeOneNoErrors));
        var cancellation = new CompilerJobWire.Diagnostic("INFO", "Compilation was canceled", "", -1, -1, "");
        assertThrows(ApplicationWire.ProtocolException.class, () -> observation(launch, generation, request, 0, List.of(cancellation)));
    }

    @Test void diagnosticCountsAggregateUtf8SizesUnknownSeveritiesAndLocationsAreBounded() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1);
        var info = new CompilerJobWire.Diagnostic("INFO", "bounded", "", -1, -1, "");
        var tooMany = new ArrayList<CompilerJobWire.Diagnostic>();
        for (int i = 0; i <= CompilerJobWire.MAX_DIAGNOSTICS; i++) tooMany.add(info);
        assertThrows(ApplicationWire.ProtocolException.class, () -> observation(launch, generation, request, 0, tooMany));
        var maximum = new CompilerJobWire.Diagnostic("LOGGING", "x".repeat(CompilerJobWire.MAX_SOURCE_BYTES), "", -1, -1, "");
        assertThrows(ApplicationWire.ProtocolException.class, () -> observation(launch, generation, request, 0,
                List.of(maximum, maximum, maximum, maximum)));
        new CompilerJobWire.Diagnostic("INFO", "\ud83d\ude00".repeat(4_096), "", -1, -1, "");
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("INFO", "\ud83d\ude00".repeat(4_097), "", -1, -1, ""));
        for (String message : List.of("\ud800", "nul\0text", "x".repeat(16_385))) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("INFO", message, "", -1, -1, ""));
        }
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("UNKNOWN", "x", "", -1, -1, ""));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("INFO", "x", "", 1, 1, ""));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("ERROR", "x", "Fixture.kt", 0, 1, "ID"));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new CompilerJobWire.Diagnostic("ERROR", "x", "Fixture.kt", Integer.MAX_VALUE, 1, "ID"));
    }

    @Test void onlyExactLogicalOrMaterializedDiagnosticLocationsMayBind() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1); var job = job(launch, request, 1);
        for (String path : List.of("Fixture.kt", child(request.sourceRoot().path(), "Fixture.kt"))) {
            var error = new CompilerJobWire.Diagnostic("ERROR", "Unresolved reference.", path, 1, 1, "UNRESOLVED_REFERENCE");
            var observation = observation(launch, generation, request, 1, List.of(error));
            CompilerJobWire.matchObservation(launch, generation, job, request, result(job, observation), observation);
        }
        // POSIX aliases remain negative cases; native variants also cover foreign paths and aliases.
        for (String path : List.of("Other.kt", "./Fixture.kt", "../Fixture.kt", "/other/Fixture.kt",
                child(synthetic("ktchecker-wire-only-foreign"), "Fixture.kt"),
                child(request.sourceRoot().path(), "..", "sources", "Fixture.kt"))) {
            var error = new CompilerJobWire.Diagnostic("ERROR", "Unresolved reference.", path, 1, 1, "UNRESOLVED_REFERENCE");
            var observation = observation(launch, generation, request, 1, List.of(error));
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchObservation(launch, generation, job,
                    request, result(job, observation), observation));
        }
    }

    @Test void observedArgvRootsMembersAndCompleteBodyCannotBeSwappedAfterMatchingHeader() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1); var job = job(launch, request, 1);
        var observation = observation(launch, generation, request, 0, List.of());
        var result = result(job, observation);
        for (String field : List.of("argv", "members", "root")) {
            var changed = new CompilerJobWire.Observation(observation.compilerVersion(), field.equals("argv") ? hash("foreign") : observation.argvDigest(),
                    field.equals("members") ? hash("foreign") : observation.memberDigest(), observation.jobRoot(), observation.sourceRoot(),
                    field.equals("root") ? new ApplicationWire.Root(child(ROOT, "jobs", "1", "foreign"), "foreign-key") : observation.outputRoot(),
                    observation.compilationReturned(), observation.compilerCode(), observation.disposalCount(), observation.disposalCompleted(),
                    observation.cancellationObserved(), observation.beforeReset(), observation.afterReset(), observation.collectorSealed(),
                    observation.inputsRevalidated(), observation.outputsRevalidated(), observation.failureCode(), observation.diagnostics());
            // Rebinding the outer payload is not enough to make a foreign inner observation valid.
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchObservation(launch, generation, job, request, result(job, changed), changed), field);
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.matchObservation(launch, generation, job, request, result, changed), field);
        }
    }

    @Test void malformedBinaryCountsBooleanEnumsDigestUtf8AndTrailingBytesCannotBecomeObservations() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1);
        var observation = observation(launch, generation, request, 1, List.of(error(request)));
        byte[] bytes = CompilerJobWire.encodeObservation(observation);
        int countOffset = diagnosticCountOffset(bytes);
        for (int count : new int[] {-1, 129, Integer.MAX_VALUE}) {
            byte[] bad = bytes.clone(); putInt(bad, countOffset, count);
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(bad));
        }
        byte[] badSeverity = bytes.clone(); badSeverity[countOffset + 4] = 127;
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(badSeverity));
        byte[] badDigest = bytes.clone(); int digestAt = find(badDigest, observation.diagnosticsDigest());
        badDigest[digestAt + 7] = badDigest[digestAt + 7] == '0' ? (byte) '1' : (byte) '0';
        assertEquals(ApplicationWire.ErrorCode.DIGEST,
                assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(badDigest)).code());
        byte[] badUtf8 = bytes.clone(); int message = find(badUtf8, "Unresolved reference");
        badUtf8[message] = (byte) 0xc0; badUtf8[message + 1] = (byte) 0xaf;
        assertEquals(ApplicationWire.ErrorCode.UTF8,
                assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(badUtf8)).code());
        byte[] badBoolean = bytes.clone(); badBoolean[returnedOffset(badBoolean)] = 2;
        assertEquals(ApplicationWire.ErrorCode.BOOLEAN,
                assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(badBoolean)).code());
        byte[] badFailure = bytes.clone(); badFailure[digestAt - 5] = 0;
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(badFailure));
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(join(bytes, new byte[] {0})));
        byte[] wrongVersion = bytes.clone(); wrongVersion[5] = 'X';
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(wrongVersion));
        byte[] wrongKind = bytes.clone(); wrongKind[0] = 1;
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(wrongKind));
        byte[] badRequestCount = CompilerJobWire.encodeRequest(request);
        int sources = sourceCountOffset(badRequestCount); putInt(badRequestCount, sources, Integer.MAX_VALUE);
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeRequest(badRequestCount));
        byte[] badArtifactCount = CompilerJobWire.encodeGeneration(generation);
        putInt(badArtifactCount, artifactCountOffset(badArtifactCount), Integer.MAX_VALUE);
        assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeGeneration(badArtifactCount));
    }

    @Test void everyTruncatedGenerationRequestAndObservationIsRejected() {
        var generation = generation(); var launch = launch(generation, 9); var request = request(generation, 1);
        byte[] encodedGeneration = CompilerJobWire.encodeGeneration(generation), encodedRequest = CompilerJobWire.encodeRequest(request),
                encodedObservation = CompilerJobWire.encodeObservation(observation(launch, generation, request, 0, List.of()));
        for (int i = 0; i < encodedGeneration.length; i++) {
            byte[] truncated = Arrays.copyOf(encodedGeneration, i);
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeGeneration(truncated));
        }
        for (int i = 0; i < encodedRequest.length; i++) {
            byte[] truncated = Arrays.copyOf(encodedRequest, i);
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeRequest(truncated));
        }
        for (int i = 0; i < encodedObservation.length; i++) {
            byte[] truncated = Arrays.copyOf(encodedObservation, i);
            assertThrows(ApplicationWire.ProtocolException.class, () -> CompilerJobWire.decodeObservation(truncated));
        }
    }

    private static CompilerJobWire.Observation changed(CompilerJobWire.Observation value, String field) {
        return new CompilerJobWire.Observation(field.equals("version") ? "unobserved" : value.compilerVersion(), value.argvDigest(), value.memberDigest(),
                value.jobRoot(), value.sourceRoot(), value.outputRoot(), !field.equals("returned"),
                field.equals("returned") ? -1 : field.equals("code") ? 2 : value.compilerCode(),
                field.equals("dispose-zero") ? 0 : field.equals("dispose-two") ? 2 : value.disposalCount(),
                !field.equals("dispose-completed"), field.equals("cancelled"),
                new CompilerJobWire.Reset(!field.equals("before-app"), !field.equals("before-env"), !field.equals("before-keepalive"), !field.equals("before-loader")),
                new CompilerJobWire.Reset(!field.equals("after-app"), !field.equals("after-env"), !field.equals("after-keepalive"), !field.equals("after-loader")),
                !field.equals("collector"), !field.equals("inputs"), !field.equals("outputs"),
                field.equals("failure") ? CompilerJobWire.FailureCode.INTERNAL_FAILURE : value.failureCode(), value.diagnostics());
    }

    private static ApplicationWire.Reader observationPrefix(byte[] bytes) {
        var in = new ApplicationWire.Reader(bytes, ApplicationWire.MAX_RESULT_BODY_BYTES);
        in.u8(); in.string(128); in.string(128); in.string(71); in.string(71);
        ApplicationWire.readRoot(in); ApplicationWire.readRoot(in); ApplicationWire.readRoot(in);
        return in;
    }

    private static int returnedOffset(byte[] bytes) { return bytes.length - observationPrefix(bytes).remaining(); }

    private static int diagnosticCountOffset(byte[] bytes) {
        var in = observationPrefix(bytes);
        in.bool(); in.i32(); in.i32(); in.bool(); in.bool();
        for (int i = 0; i < 11; i++) in.bool(); // before/after reset plus seal/input/output checks
        in.u8(); in.string(71);
        return bytes.length - in.remaining();
    }

    private static int sourceCountOffset(byte[] bytes) {
        var in = new ApplicationWire.Reader(bytes, ApplicationWire.MAX_JOB_BODY_BYTES);
        in.u8(); in.string(128); in.string(128);
        for (int i = 0; i < 14; i++) in.string(71);
        for (int i = 0; i < 4; i++) ApplicationWire.readRoot(in);
        return bytes.length - in.remaining();
    }

    private static int artifactCountOffset(byte[] bytes) {
        var in = new ApplicationWire.Reader(bytes, ApplicationWire.MAX_LAUNCH_BODY_BYTES);
        in.u8(); in.string(128); in.string(128); in.string(128); in.string(128); in.i32();
        ApplicationWire.readRoot(in); in.string(4_096); in.string(71); in.string(71); in.i32();
        in.string(128); in.string(128); in.string(71); in.i64(); in.string(256);
        return bytes.length - in.remaining();
    }
}
