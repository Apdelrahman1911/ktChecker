package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerModelWire;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import static dev.ktchecker.workerprotocol.CompilerModelWire.*;
import static dev.ktchecker.compilerworker.live.CompilerModelApplicationMain.requireProducedLineage;
import static org.junit.jupiter.api.Assertions.*;

/** Pure synthetic lineage corroboration controls. No compiler/JVM installation, files, worker
 * successfulProducers enrollment, Accepted session, or host output token is manufactured here.
 * Passing this helper is NOT producer success: actual enrollment is separately execution-only.
 */
class CompilerProducedOutputLineageTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";
    private static final List<Member> FILES = List.of(new Member("src/main/Api.kt", sha("producer source"), 15, "source-1"),
        new Member("src/consumer/Use.kt", sha("consumer source"), 15, "source-2"));
    private static final String OUTPUT = producedOutputDigest(List.of(new ProducedMember("Api.class", false, 5, sha("SYNTHETIC class"))));

    @Test void consistentDetachedLineageOnlyCorroboratesAnAlreadyRetainedCompletion() {
        Request before = request(true, "", null);
        ApplicationWire.Result result = result(before, observation(before, ""), "");
        ProducedDependency dependency = dependency(result, "");
        Request consumer = request(false, "", dependency);
        assertDoesNotThrow(() -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, OUTPUT));
        assertEquals(List.of(), before.producedExecution().producers());
        assertEquals(List.of("src/consumer/Use.kt"), consumer.compilationPaths());
        assertEquals(FILES, consumer.files()); // Both complete source domains, no source merge.
    }

    @Test void failedProducerCodeOneCannotBeBorrowedEvenWithSelfConsistentResultDigest() {
        Request before = request(true, "", null);
        ApplicationWire.Result failed = result(before, observation(before, "code1"), "");
        ProducedDependency dependency = dependency(failed, "");
        Request consumer = request(false, "", dependency);
        assertDoesNotThrow(() -> requireSealed(decodeObservation(failed.payload()))); // A genuine seal may report failure.
        assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
            () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, failed, OUTPUT));
    }

    @Test void unknownUnsealedCancelledOrResetFailedDtoCannotEstablishProducedSuccess() {
        Request before = request(true, "", null);
        for (String invalid : List.of("unsealed", "cancelled", "reset", "disposal", "not-returned")) {
            ApplicationWire.Result result = result(before, observation(before, invalid), "");
            ProducedDependency dependency = dependency(result, "");
            Request consumer = request(false, "", dependency);
            assertThrows(IllegalArgumentException.class,
                () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, OUTPUT), invalid);
        }
        ApplicationWire.Result unknown = result(before, observation(before, ""), "unknown");
        ProducedDependency dependency = dependency(unknown, ""); Request consumer = request(false, "", dependency);
        assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
            () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, unknown, OUTPUT));
    }

    @Test void snapshotMembersModelConfigurationToolchainPlanAndOriginalDeadlineRemainExact() {
        Request before = request(true, "", null);
        ApplicationWire.Result result = result(before, observation(before, ""), "");
        ProducedDependency dependency = dependency(result, "");
        for (String drift : List.of("snapshot", "members", "model", "configuration", "toolchain", "plan", "deadline")) {
            Request consumer = request(false, drift, dependency);
            assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
                () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, OUTPUT), drift);
        }
    }

    @Test void staleCrossGenerationNonprecedingOrWrongOriginalEffectiveResultIsRejected() {
        Request before = request(true, "", null);
        for (String drift : List.of("generation", "sequence", "original", "effective")) {
            ApplicationWire.Result result = result(before, observation(before, ""), drift);
            ProducedDependency dependency = dependency(result, ""); Request consumer = request(false, "", dependency);
            assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
                () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, OUTPUT), drift);
        }
        ApplicationWire.Result result = result(before, observation(before, ""), "");
        ProducedDependency dependency = dependency(result, ""); Request consumer = request(false, "", dependency);
        assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
            () -> requireProducedLineage(job(consumer, 1), consumer, dependency, before, result, OUTPUT));
    }

    @Test void bindingRequestResultOutputAndCompilationCannotBeBorrowedFromAnotherProducer() {
        Request before = request(true, "", null);
        ApplicationWire.Result result = result(before, observation(before, ""), "");
        for (String drift : List.of("binding", "request", "result", "output", "compilation")) {
            ProducedDependency dependency = dependency(result, drift); Request consumer = request(false, "", dependency);
            assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
                () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, OUTPUT), drift);
        }
        ProducedDependency dependency = dependency(result, ""); Request consumer = request(false, "", dependency);
        assertThrows(CompilerModelApplicationMain.WorkerFailure.class,
            () -> requireProducedLineage(job(consumer, 2), consumer, dependency, before, result, sha("different entire tree")));
    }

    private static Request request(boolean producer, String drift, ProducedDependency dependency) {
        String label = producer ? "producer" : "consumer";
        List<Member> files = drift.equals("members") ? List.of(new Member(FILES.get(0).logicalPath(), sha("changed source"), 15, "source-1"), FILES.get(1)) : FILES;
        String resolution = sha(label + " resolution");
        InputIdentity original = input(label + " C", drift, files, resolution);
        InputIdentity effective = input(label + " E", drift, files, resolution);
        return new Request(PRODUCED_PROFILE, sha(label + " P"), sha(label + " K"), sha(label + " binding"), original, effective,
            root(label + "/archive/sources"), root(label + "/archive/classpath"), root(label + "/attempt"), root(label + "/job"),
            root(label + "/job/sources"), root(label + "/job/classpath"), root(label + "/job/classes"), files,
            List.of(producer ? "src/main/Api.kt" : "src/consumer/Use.kt"),
            List.of(new Dependency("SYNTHETIC:artifact", sha("artifact"), 30, "dependency-key")),
            List.of("-jvm-target", "21", "-module-name", label), List.of("-jvm-target", "21", "-module-name", label), sha("profile"), drift.equals("deadline") ? OTHER : TOKEN,
            new ProducedExecution(sha(drift.equals("plan") ? "different plan" : "plan"), producer ? ":||main" : ":||consumer",
                sha(label + " raw context"), resolution, producer ? List.of() : List.of(dependency)));
    }
    private static InputIdentity input(String key, String drift, List<Member> files, String resolution) {
        return new InputIdentity(sha(key), selected("snapshot", drift), membersDigest(files), selected("model", drift), resolution,
            selected("configuration", drift), selected("toolchain", drift));
    }
    private static Observation observation(Request before, String invalid) {
        boolean returned = !invalid.equals("not-returned");
        int code = !returned ? -1 : invalid.equals("code1") ? 1 : 0;
        return new Observation(COMPILER_VERSION, sha("argv"), workDigest(before), before.jobRoot(), before.sourceRoot(), before.outputRoot(),
            returned, code, invalid.equals("disposal") ? 0 : 1, !invalid.equals("disposal"), invalid.equals("cancelled"),
            new Reset(true, true, true, true), new Reset(!invalid.equals("reset"), true, true, true), !invalid.equals("unsealed"), true, true,
            FailureCode.NONE, code == 1 ? List.of(new Diagnostic("ERROR", "SYNTHETIC producer failure", "src/main/Api.kt", 1, 1, "UNRESOLVED_REFERENCE")) : List.of());
    }
    private static ApplicationWire.Result result(Request before, Observation observation, String drift) {
        String generation = drift.equals("generation") ? OTHER : TOKEN;
        int sequence = drift.equals("sequence") ? 2 : 1;
        return new ApplicationWire.Result(generation, sha("hello"), sequence, ApplicationWire.jobId(generation, sequence),
            sha("producer request"), drift.equals("original") ? sha("wrong C") : before.original().analysisKey(),
            drift.equals("effective") ? sha("wrong E") : before.effective().analysisKey(), OTHER,
            drift.equals("unknown") ? ApplicationWire.ResultState.OUTCOME_UNKNOWN : ApplicationWire.ResultState.APPLICATION_SEALED,
            encodeObservation(observation));
    }
    private static ProducedDependency dependency(ApplicationWire.Result result, String drift) {
        return new ProducedDependency(0, drift.equals("compilation") ? ":||other" : ":||main", sha("reference"), result.generation(), result.sequence(),
            sha(drift.equals("binding") ? "wrong binding" : "producer binding"), drift.equals("request") ? sha("wrong request") : result.requestDigest(),
            drift.equals("result") ? sha("wrong result") : ApplicationWire.sha256(ApplicationWire.encode(result)),
            drift.equals("output") ? sha("wrong output") : OUTPUT, false);
    }
    private static ApplicationWire.Job job(Request request, int sequence) {
        return new ApplicationWire.Job(TOKEN, sha("hello"), sequence, ApplicationWire.jobId(TOKEN, sequence),
            request.original().analysisKey(), request.effective().analysisKey(), request.deadlineIdentity(), encodeRequest(request));
    }
    private static ApplicationWire.Root root(String relative) {
        return new ApplicationWire.Root(Path.of("produced-lineage-DATA").toAbsolutePath().normalize().resolve(relative).toString(), relative + "-key");
    }
    private static String selected(String field, String drift) { return sha(field.equals(drift) ? "different " + field : field); }
    private static String sha(String text) { return ApplicationWire.sha256(text.getBytes(StandardCharsets.UTF_8)); }
}
