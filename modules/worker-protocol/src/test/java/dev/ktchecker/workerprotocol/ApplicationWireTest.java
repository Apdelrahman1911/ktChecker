package dev.ktchecker.workerprotocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static dev.ktchecker.workerprotocol.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationWireTest {
    @Test void canonicalRoundTripAllMessageKindsAndLaunch() {
        var launch = launch();
        var hello = hello(launch);
        var request = request(generation(), 1);
        var job = job(launch, request, 1);
        var result = result(job, observation(launch, generation(), request, 0, List.of()));
        assertEquals(launch, ApplicationWire.decodeLaunch(ApplicationWire.encodeLaunch(launch)));
        for (var value : List.of(hello, job, result, new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.LIMIT))) {
            assertEquals(value, ApplicationWire.decode(ApplicationWire.encode(value)));
            assertEquals(value, ApplicationWire.decodeFrame(ApplicationWire.frame(value)));
            assertEquals(value.hashCode(), ApplicationWire.decodeFrame(ApplicationWire.frame(value)).hashCode());
        }
        ApplicationWire.matchHello(launch, hello);
        ApplicationWire.matchJob(launch, hello, job, 1);
        ApplicationWire.matchResult(launch, hello, job, result);
    }

    @Test void mutableListsMapsAndByteArraysCannotChangeDescriptors() {
        var basis = launch();
        var command = new ArrayList<>(basis.command());
        var environment = new LinkedHashMap<>(basis.environment());
        byte[] body = new byte[] {1, 2, 3};
        var value = new ApplicationWire.Launch(basis.identity(), basis.limits(), basis.generationRoot(),
                basis.directory(), command, environment, body);
        byte[] encoded = ApplicationWire.encodeLaunch(value);
        command.clear(); environment.clear(); Arrays.fill(body, (byte) 99);
        value.payload()[0] = 42;
        assertArrayEquals(encoded, ApplicationWire.encodeLaunch(value));
        assertThrows(UnsupportedOperationException.class, () -> value.command().add("-javaagent=bad"));
        assertThrows(UnsupportedOperationException.class, () -> value.environment().put("NEW", "bad"));
        var job = new ApplicationWire.Job(GENERATION, hash("hello"), 1, ApplicationWire.jobId(GENERATION, 1),
                hash("original"), hash("effective"), DEADLINE, body);
        var result = new ApplicationWire.Result(GENERATION, job.helloDigest(), 1, job.jobId(),
                ApplicationWire.requestDigest(job), job.originalKey(), job.effectiveKey(), LOADER,
                ApplicationWire.ResultState.APPLICATION_SEALED, body);
        byte[] jobBefore = ApplicationWire.encode(job), resultBefore = ApplicationWire.encode(result);
        body[0] ^= 7; job.payload()[1] ^= 7; result.payload()[2] ^= 7;
        assertArrayEquals(jobBefore, ApplicationWire.encode(job));
        assertArrayEquals(resultBefore, ApplicationWire.encode(result));
    }

    @Test void environmentEncodingIsDeterministicButUnsortedAndDuplicateWireKeysAreRejected() {
        var basis = launch();
        Map<String, String> first = new LinkedHashMap<>(), second = new LinkedHashMap<>();
        first.put("AA_TEST", "a"); first.put("ZZ_TEST", "z");
        second.put("ZZ_TEST", "z"); second.put("AA_TEST", "a");
        var one = new ApplicationWire.Launch(basis.identity(), basis.limits(), basis.generationRoot(),
                basis.directory(), basis.command(), first, new byte[0]);
        var two = new ApplicationWire.Launch(basis.identity(), basis.limits(), basis.generationRoot(),
                basis.directory(), basis.command(), second, new byte[0]);
        byte[] canonical = ApplicationWire.encodeLaunch(one);
        assertArrayEquals(canonical, ApplicationWire.encodeLaunch(two));
        int a = find(canonical, "AA_TEST"), z = find(canonical, "ZZ_TEST");
        byte[] unsorted = canonical.clone();
        System.arraycopy("ZZ_TEST".getBytes(StandardCharsets.UTF_8), 0, unsorted, a, 7);
        System.arraycopy("AA_TEST".getBytes(StandardCharsets.UTF_8), 0, unsorted, z, 7);
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeLaunch(unsorted));
        byte[] duplicate = canonical.clone();
        System.arraycopy(canonical, a, duplicate, z, 7);
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeLaunch(duplicate));
    }

    @Test void allSplitPositionsAndCoalescedFramesRoundTripWithoutBoundaryAssumptions() {
        var hello = hello(launch());
        var fatal = new ApplicationWire.Fatal(GENERATION, 1, ApplicationWire.FatalReason.CALLBACK);
        byte[] bytes = join(ApplicationWire.frame(hello), ApplicationWire.frame(fatal));
        for (int split = 0; split <= bytes.length; split++) {
            var decoder = new ApplicationWire.Decoder(2, bytes.length);
            var actual = new ArrayList<ApplicationWire.Message>();
            decoder.accept(bytes, 0, split, actual::add);
            decoder.accept(bytes, split, bytes.length - split, actual::add);
            decoder.endOfInput();
            assertEquals(List.of(hello, fatal), actual, "split=" + split);
            assertEquals(2, decoder.frameCount()); assertEquals(bytes.length, decoder.receivedBytes());
            assertFalse(decoder.hasPartialFrame()); assertFalse(decoder.isFailed());
        }
        var decoder = new ApplicationWire.Decoder(2, bytes.length);
        var actual = new ArrayList<ApplicationWire.Message>();
        for (byte value : bytes) decoder.accept(new byte[] {value}, actual::add);
        decoder.endOfInput();
        assertEquals(List.of(hello, fatal), actual);
    }

    @Test void adversarialChunksHandleMaximumPayloadAndManyCoalescedFrames() {
        byte[] body = new byte[ApplicationWire.MAX_RESULT_BODY_BYTES];
        Arrays.fill(body, (byte) 17);
        var result = new ApplicationWire.Result(GENERATION, hash("hello"), 1, ApplicationWire.jobId(GENERATION, 1),
                hash("request"), hash("original"), hash("effective"), LOADER, ApplicationWire.ResultState.OUTCOME_UNKNOWN, body);
        byte[] bytes = ApplicationWire.frame(result);
        for (int seed = 0; seed < 12; seed++) {
            var decoder = new ApplicationWire.Decoder(1, bytes.length);
            var actual = new ArrayList<ApplicationWire.Message>();
            Random random = new Random(seed);
            for (int i = 0; i < bytes.length;) {
                int n = Math.min(1 + random.nextInt(8_193), bytes.length - i);
                decoder.accept(bytes, i, n, actual::add); i += n;
            }
            decoder.endOfInput(); assertEquals(List.of(result), actual);
        }
        var fatal = new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.INTERNAL);
        byte[][] frames = new byte[100][];
        Arrays.fill(frames, ApplicationWire.frame(fatal));
        byte[] coalesced = join(frames);
        var decoder = new ApplicationWire.Decoder(100, coalesced.length);
        int[] seen = {0}; decoder.accept(coalesced, ignored -> seen[0]++); decoder.endOfInput();
        assertEquals(100, seen[0]);
    }

    @Test void lengthAndKindAreRejectedBeforePayloadAllocationAndNeverResynchronize() {
        for (int length : new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, ApplicationWire.MAX_RESULT_BYTES + 1}) {
            byte[] prefix = new byte[4]; putInt(prefix, 0, length);
            var decoder = new ApplicationWire.Decoder(2, ApplicationWire.MAX_STREAM_BYTES);
            assertThrows(ApplicationWire.ProtocolException.class, () -> decoder.accept(prefix, ignored -> fail()));
            assertTrue(decoder.isFailed()); assertEquals(0, decoder.frameCount());
            assertThrows(ApplicationWire.ProtocolException.class, () -> decoder.accept(
                    ApplicationWire.frame(new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.INTERNAL)), ignored -> fail()));
        }
        for (int kind : new int[] {1, 2, 4, 0, 255}) {
            int over = switch (kind) {
                case 1 -> ApplicationWire.MAX_HELLO_BYTES + 1;
                case 2 -> ApplicationWire.MAX_JOB_BYTES + 1;
                case 4 -> ApplicationWire.MAX_FATAL_BYTES + 1;
                default -> 1;
            };
            byte[] header = new byte[5]; putInt(header, 0, over); header[4] = (byte) kind;
            var decoder = new ApplicationWire.Decoder(1, ApplicationWire.MAX_STREAM_BYTES);
            assertThrows(ApplicationWire.ProtocolException.class, () -> decoder.accept(header, ignored -> fail()));
            assertTrue(decoder.isFailed());
        }
        var cap = new ApplicationWire.Decoder(1, 10);
        byte[] declared = new byte[4]; putInt(declared, 0, 100);
        assertThrows(ApplicationWire.ProtocolException.class, () -> cap.accept(declared, ignored -> fail()));
    }

    @Test void frameCountsAndCumulativeByteCapsRemainOriginal() {
        var frame = ApplicationWire.frame(new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.INTERNAL));
        var count = new ApplicationWire.Decoder(1, frame.length * 2L);
        count.accept(frame, ignored -> {});
        assertThrows(ApplicationWire.ProtocolException.class, () -> count.accept(frame, ignored -> fail()));
        var bytes = new ApplicationWire.Decoder(2, frame.length);
        bytes.accept(frame, ignored -> {});
        assertThrows(ApplicationWire.ProtocolException.class, () -> bytes.accept(new byte[] {0}, ignored -> fail()));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Decoder(Integer.MAX_VALUE, 1));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Decoder(1, Long.MAX_VALUE));
    }

    @Test void everyPartialEofPoisonsAndCompleteEofCannotBeReopened() {
        byte[] frame = ApplicationWire.frame(new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.PROTOCOL));
        for (int length = 1; length < frame.length; length++) {
            var decoder = new ApplicationWire.Decoder(1, frame.length);
            decoder.accept(frame, 0, length, ignored -> fail());
            assertTrue(decoder.hasPartialFrame());
            assertThrows(ApplicationWire.ProtocolException.class, decoder::endOfInput);
            assertTrue(decoder.isFailed());
        }
        var empty = new ApplicationWire.Decoder(1, 100);
        empty.endOfInput();
        assertThrows(ApplicationWire.ProtocolException.class, () -> empty.accept(new byte[0], ignored -> {}));
        var full = new ApplicationWire.Decoder(1, frame.length);
        full.accept(frame, ignored -> {}); full.endOfInput();
        assertThrows(ApplicationWire.ProtocolException.class, full::endOfInput);
    }

    @Test void malformedUtf8NulCountsBooleanEnumAndTrailingBytesAreStrict() {
        for (byte[] bad : List.of(new byte[] {(byte) 0xc0, (byte) 0xaf}, new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, new byte[] {(byte) 0x80}, new byte[] {0})) {
            var writer = new ApplicationWire.Writer(256);
            writer.u8(4); writer.bytes(bad, 128);
            assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decode(writer.finish()));
        }
        byte[] hello = ApplicationWire.encode(hello(launch())); hello[hello.length - 1] = 2;
        assertEquals(ApplicationWire.ErrorCode.BOOLEAN,
                assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decode(hello)).code());
        var fatal = new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.PROTOCOL);
        byte[] unknownEnum = ApplicationWire.encode(fatal); unknownEnum[unknownEnum.length - 1] = 0;
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decode(unknownEnum));
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decode(join(ApplicationWire.encode(fatal), new byte[] {0})));
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeFrame(join(ApplicationWire.frame(fatal), new byte[] {0})));
        byte[] shortFrame = Arrays.copyOf(ApplicationWire.frame(fatal), ApplicationWire.frame(fatal).length - 1);
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeFrame(shortFrame));
        byte[] launch = ApplicationWire.encodeLaunch(launch());
        for (int count : new int[] {-1, Integer.MAX_VALUE}) {
            byte[] changed = launch.clone(); putInt(changed, 0, count);
            assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeLaunch(changed));
            byte[] bad = launch.clone(); putInt(bad, commandCountOffset(bad), count);
            assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.decodeLaunch(bad));
        }
    }

    @Test void malformedStringsEnvironmentInjectionAndPathAliasesFailBeforeEncoding() {
        var launch = launch();
        String separator = Path.of(ROOT).getFileSystem().getSeparator();
        String nativeRoot = Path.of(ROOT).getRoot().toString();
        // The fallback is deliberately INVALID relative-drive syntax, not a guessed real drive.
        String drive = nativeRoot.length() >= 2 && nativeRoot.charAt(1) == ':' ? nativeRoot.substring(0, 2) : "C:";
        // POSIX spellings remain negative cases; native variants exercise aliases on every host.
        for (String path : List.of("relative", "/tmp/../private", "/tmp/./private", "/tmp//private", "/tmp/private/",
                ROOT + separator + ".." + separator + "private", ROOT + separator + "." + separator + "private",
                ROOT + separator + separator + "private", child(ROOT, "private") + separator,
                Path.of(ROOT).getRoot().relativize(Path.of(ROOT)).toString(),
                drive + "ktchecker-drive-relative", "\\ktchecker-root-relative")) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Root(path, "key"));
        }
        // Malformed UTF-16 intentionally stays a raw String until the codec sees it.
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Root(ROOT + separator + "\ud800", "key"));
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Root(ROOT, "\u0000"));
        for (String injection : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES")) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Launch(launch.identity(), launch.limits(),
                    launch.generationRoot(), launch.directory(), launch.command(), Map.of(injection, "not permitted"), new byte[0]));
        }
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Launch(launch.identity(), launch.limits(),
                launch.generationRoot(), launch.directory(), List.of(launch.command().get(0), "\udfff"), Map.of(), new byte[0]));
    }

    @Test void configuredJobCountIsNotFourAndCapsUseOverflowSafeWorstCaseReservations() {
        new ApplicationWire.Limits(9, 2_097_152, 2_097_152);
        new ApplicationWire.Limits(127, ApplicationWire.MAX_STREAM_BYTES, ApplicationWire.MAX_STREAM_BYTES);
        for (int max : new int[] {0, -1, 128, ApplicationWire.MAX_JOBS, Integer.MAX_VALUE}) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Limits(max,
                    ApplicationWire.MAX_STREAM_BYTES, ApplicationWire.MAX_STREAM_BYTES));
        }
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Limits(1,
                ApplicationWire.MAX_JOB_BYTES + 3, 2_097_152));
        var launch = launch();
        var ninth = job(launch, request(generation(), 9), 9);
        ApplicationWire.matchJob(launch, hello(launch), ninth, 9);
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchJob(launch, hello(launch), ninth, 10));
    }

    @Test void exactBindingsRejectReplayWrongLoaderDigestGenerationAndPrematureHello() {
        var launch = launch(); var hello = hello(launch);
        var request = request(generation(), 1); var job = job(launch, request, 1);
        var result = result(job, observation(launch, generation(), request, 0, List.of()));
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchJob(launch, hello, job, 2));
        var foreign = new ApplicationWire.Job("e".repeat(32), job.helloDigest(), 1, ApplicationWire.jobId("e".repeat(32), 1),
                job.originalKey(), job.effectiveKey(), job.deadlineIdentity(), job.payload());
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchJob(launch, hello, foreign, 1));
        var changedDeadline = new ApplicationWire.Job(GENERATION, job.helloDigest(), 1, job.jobId(), job.originalKey(),
                job.effectiveKey(), "f".repeat(32), job.payload());
        assertNotEquals(ApplicationWire.requestDigest(job), ApplicationWire.requestDigest(changedDeadline));
        assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchResult(launch, hello, changedDeadline, result));
        for (String field : List.of("loader", "request", "hello", "original", "effective", "state")) {
            var changed = new ApplicationWire.Result(result.generation(), field.equals("hello") ? hash("foreign") : result.helloDigest(),
                    result.sequence(), result.jobId(), field.equals("request") ? hash("foreign") : result.requestDigest(),
                    field.equals("original") ? hash("foreign") : result.originalKey(), field.equals("effective") ? hash("foreign") : result.effectiveKey(),
                    field.equals("loader") ? "f".repeat(32) : result.loaderToken(),
                    field.equals("state") ? ApplicationWire.ResultState.OUTCOME_UNKNOWN : result.state(), result.payload());
            assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchResult(launch, hello, job, changed), field);
        }
        for (var unready : List.of(new ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), LOADER, "2.4.21", false, true),
                new ApplicationWire.Hello(launch.identity(), ApplicationWire.launchDigest(launch), LOADER, "2.4.21", true, false),
                new ApplicationWire.Hello(launch.identity(), hash("other-launch"), LOADER, "2.4.21", true, true))) {
            assertThrows(ApplicationWire.ProtocolException.class, () -> ApplicationWire.matchHello(launch, unready));
        }
        assertThrows(ApplicationWire.ProtocolException.class, () -> new ApplicationWire.Job(GENERATION, job.helloDigest(), 1,
                GENERATION + "-job-01", job.originalKey(), job.effectiveKey(), DEADLINE, new byte[0]));
    }

    @Test void consumerExceptionsReentryAndCaughtReentryAreStickyWithoutRetainingPrivateText() {
        byte[] frame = ApplicationWire.frame(new ApplicationWire.Fatal(GENERATION, 0, ApplicationWire.FatalReason.INTERNAL));
        var decoder = new ApplicationWire.Decoder(2, frame.length * 2L);
        var rejected = assertThrows(ApplicationWire.ProtocolException.class, () -> decoder.accept(frame,
                ignored -> { throw new IllegalArgumentException("private terminal transcript"); }));
        assertEquals(ApplicationWire.ErrorCode.CONSUMER, rejected.code());
        assertNull(rejected.getCause()); assertFalse(rejected.getMessage().contains("private"));
        assertTrue(decoder.isFailed());
        var reentrant = new ApplicationWire.Decoder(2, frame.length * 2L);
        assertThrows(ApplicationWire.ProtocolException.class, () -> reentrant.accept(frame, ignored -> {
            try { reentrant.accept(frame, again -> fail()); }
            catch (ApplicationWire.ProtocolException intentionallySwallowed) { /* sticky failure must survive */ }
        }));
        assertTrue(reentrant.isFailed());
        var eof = new ApplicationWire.Decoder(2, frame.length * 2L);
        assertThrows(ApplicationWire.ProtocolException.class, () -> eof.accept(frame, ignored -> {
            try { eof.endOfInput(); }
            catch (ApplicationWire.ProtocolException intentionallySwallowed) { /* same fence */ }
        }));
        assertTrue(eof.isFailed());
    }

    @Test void structuredDigestsAreDomainSeparatedAndRawPinsStayStandard() {
        byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApplicationWire.sha256(payload));
        assertNotEquals(ApplicationWire.digest("hello", payload), ApplicationWire.digest("request", payload));
        assertNotEquals(ApplicationWire.sha256(payload), ApplicationWire.digest("hello", payload));
        var original = hello(launch());
        var other = new ApplicationWire.Hello(original.identity(), original.launchDigest(), "f".repeat(32),
                original.observedVersion(), original.originsVerified(), original.initialResetObserved());
        assertNotEquals(ApplicationWire.helloDigest(original), ApplicationWire.helloDigest(other));
    }

    private static int commandCountOffset(byte[] bytes) {
        var in = new ApplicationWire.Reader(bytes, ApplicationWire.MAX_LAUNCH_BYTES);
        in.string(128);
        for (int i = 0; i < 9; i++) in.string(128);
        in.i32(); in.i32(); in.i32();
        ApplicationWire.readRoot(in); in.string(4_096);
        return bytes.length - in.remaining();
    }
}
