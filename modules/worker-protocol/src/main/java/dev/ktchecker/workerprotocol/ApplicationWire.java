package dev.ktchecker.workerprotocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Detached data and strict framing only. Neither a descriptor, a digest nor a decoded result is
 * an execution capability, a compiler/reset proof, a native termination or qualified coverage.
 * Every cross-process identity is an echo until independently checked by its owner.
 */
public final class ApplicationWire {
    private ApplicationWire() {}

    public static final String VERSION = "ktchecker/k2-live-application/preview-1";
    private static final String LAUNCH_VERSION = "ktchecker/application-launch/preview-1";
    public static final int MAX_LAUNCH_BYTES = 65_536;
    public static final int MAX_HELLO_BYTES = 8_192;
    public static final int MAX_JOB_BYTES = 65_536;
    public static final int MAX_RESULT_BYTES = 131_072;
    public static final int MAX_FATAL_BYTES = 4_096;
    public static final int MAX_LAUNCH_BODY_BYTES = 32_768;
    public static final int MAX_JOB_BODY_BYTES = 61_440;
    public static final int MAX_RESULT_BODY_BYTES = 126_976;
    public static final int MAX_JOBS = 4_096;
    public static final int MAX_STREAM_BYTES = 16_777_216;
    public static final int CHUNK_BYTES = 8_192;
    private static final int MAX_CONTROLS = 8_192;
    private static final int MAX_COMMAND = 64;
    private static final int MAX_ENVIRONMENT = 64;
    private static final int MAX_LAUNCH_TEXT_BYTES = 32_768;
    static final int MAX_PATH_BYTES = 4_096;
    static final int MAX_KEY_BYTES = 256;
    static final int MAX_ID_BYTES = 128;
    private static final int HELLO = 1, JOB = 2, RESULT = 3, FATAL = 4;

    /** Fixed reasons only: never retain attacker text, exception causes or terminal transcripts. */
    public enum ErrorCode {
        NULL, LIMIT, LENGTH, TRUNCATED, TRAILING, UTF8, NUL, VALUE, VERSION, KIND,
        BOOLEAN, ENUM, DIGEST, TOKEN, PATH, DUPLICATE, ORDER, IDENTITY, UNSEALED,
        UNSUPPORTED, FAILED, ENDED, CONSUMER
    }

    public static final class ProtocolException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final ErrorCode code;
        private ProtocolException(ErrorCode code) {
            super("application_wire_" + code.name().toLowerCase(java.util.Locale.ROOT));
            this.code = code;
        }
        public ErrorCode code() { return code; }
    }

    public record Identity(String generation, String ownerDomain, String worktreeDigest,
                           String implementationDigest, String bridgeDigest,
                           String compilerClosureDigest, String toolchainDigest,
                           String adapterVersion, String profile) {
        public Identity {
            token(generation); token(ownerDomain);
            checkDigest(worktreeDigest); checkDigest(implementationDigest); checkDigest(bridgeDigest);
            checkDigest(compilerClosureDigest); checkDigest(toolchainDigest);
            identifier(adapterVersion); identifier(profile);
        }
    }

    /** Lexical canonicality only. NO filesystem access, link resolution or registration authority. */
    public record Root(String path, String fileKey) {
        public Root { absolutePath(path); key(fileKey); }
    }

    /**
     * Dynamic admission cap, not a batch size. Conservatively reserves all maximum-sized frames
     * and the original transport's control/epoch bounds. Future jobs cannot increase these caps.
     */
    public record Limits(int maxJobs, int inputLimit, int outputLimit) {
        public Limits {
            require(maxJobs >= 1 && maxJobs <= MAX_JOBS, ErrorCode.LIMIT);
            require(inputLimit >= 1 && inputLimit <= MAX_STREAM_BYTES
                    && outputLimit >= 1 && outputLimit <= MAX_STREAM_BYTES, ErrorCode.LIMIT);
            long inputReserve = (MAX_JOB_BYTES + 4L) * maxJobs;
            long outputReserve = MAX_HELLO_BYTES + 4L + MAX_FATAL_BYTES + 4L
                    + (MAX_RESULT_BYTES + 4L) * maxJobs;
            long controlsPerJob = 2L + (MAX_JOB_BYTES + 4L + CHUNK_BYTES - 1L) / CHUNK_BYTES;
            require(inputReserve <= inputLimit && outputReserve <= outputLimit
                    && controlsPerJob * maxJobs + 1L <= MAX_CONTROLS, ErrorCode.LIMIT);
        }
    }

    public record Launch(Identity identity, Limits limits, Root generationRoot, String directory,
                         List<String> command, Map<String, String> environment, byte[] payload) {
        public Launch {
            nonnull(identity); nonnull(limits); nonnull(generationRoot); absolutePath(directory);
            command = strings(command, MAX_COMMAND, MAX_PATH_BYTES);
            require(!command.isEmpty(), ErrorCode.VALUE);
            absolutePath(command.get(0));
            environment = environmentCopy(environment);
            long textBytes = 0;
            for (String value : command) textBytes += utf8(value, MAX_PATH_BYTES).length + 4L;
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                textBytes += utf8(entry.getKey(), MAX_ID_BYTES).length + 4L;
                textBytes += utf8(entry.getValue(), MAX_PATH_BYTES).length + 4L;
            }
            require(textBytes <= MAX_LAUNCH_TEXT_BYTES, ErrorCode.LIMIT);
            payload = bytesCopy(payload, MAX_LAUNCH_BODY_BYTES);
            // Also reject an aggregate overflow at construction, not after unbounded serialization.
            Writer sizeCheck = new Writer(MAX_LAUNCH_BYTES);
            writeLaunch(sizeCheck, identity, limits, generationRoot, directory, command, environment, payload);
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Launch that && identity.equals(that.identity) && limits.equals(that.limits)
                    && generationRoot.equals(that.generationRoot) && directory.equals(that.directory)
                    && command.equals(that.command) && environment.equals(that.environment)
                    && Arrays.equals(payload, that.payload);
        }
        @Override public int hashCode() {
            return 31 * java.util.Objects.hash(identity, limits, generationRoot, directory, command, environment)
                    + Arrays.hashCode(payload);
        }
    }

    public sealed interface Message permits Hello, Job, Result, Fatal {}

    public record Hello(Identity identity, String launchDigest, String loaderToken, String observedVersion,
                        boolean originsVerified, boolean initialResetObserved) implements Message {
        public Hello {
            nonnull(identity); checkDigest(launchDigest); token(loaderToken);
            identifier(observedVersion);
        }
    }

    public record Job(String generation, String helloDigest, int sequence, String jobId,
                      String originalKey, String effectiveKey, String deadlineIdentity,
                      byte[] payload) implements Message {
        public Job {
            jobIdentity(generation, sequence, jobId); checkDigest(helloDigest);
            checkDigest(originalKey); checkDigest(effectiveKey); token(deadlineIdentity);
            payload = bytesCopy(payload, MAX_JOB_BODY_BYTES);
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Job that && generation.equals(that.generation)
                    && helloDigest.equals(that.helloDigest) && sequence == that.sequence
                    && jobId.equals(that.jobId) && originalKey.equals(that.originalKey)
                    && effectiveKey.equals(that.effectiveKey) && deadlineIdentity.equals(that.deadlineIdentity)
                    && Arrays.equals(payload, that.payload);
        }
        @Override public int hashCode() {
            return 31 * java.util.Objects.hash(generation, helloDigest, sequence, jobId, originalKey,
                    effectiveKey, deadlineIdentity) + Arrays.hashCode(payload);
        }
    }

    public enum ResultState { APPLICATION_SEALED, OUTCOME_UNKNOWN }

    public record Result(String generation, String helloDigest, int sequence, String jobId,
                         String requestDigest, String originalKey, String effectiveKey, String loaderToken,
                         ResultState state, byte[] payload) implements Message {
        public Result {
            jobIdentity(generation, sequence, jobId); checkDigest(helloDigest); checkDigest(requestDigest);
            checkDigest(originalKey); checkDigest(effectiveKey); token(loaderToken); nonnull(state);
            payload = bytesCopy(payload, MAX_RESULT_BODY_BYTES);
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Result that && generation.equals(that.generation)
                    && helloDigest.equals(that.helloDigest) && sequence == that.sequence
                    && jobId.equals(that.jobId) && requestDigest.equals(that.requestDigest)
                    && originalKey.equals(that.originalKey) && effectiveKey.equals(that.effectiveKey)
                    && loaderToken.equals(that.loaderToken) && state == that.state
                    && Arrays.equals(payload, that.payload);
        }
        @Override public int hashCode() {
            return 31 * java.util.Objects.hash(generation, helloDigest, sequence, jobId, requestDigest,
                    originalKey, effectiveKey, loaderToken, state) + Arrays.hashCode(payload);
        }
    }

    public enum FatalReason {
        PROTOCOL, IDENTITY, LIMIT, UNSUPPORTED, RAW_IO, CALLBACK, COMPILER, RESET, OWNERSHIP, INTERNAL
    }

    /** Sequence zero means no request has been admitted yet. A fatal can never establish cleanup. */
    public record Fatal(String generation, int sequence, FatalReason reason) implements Message {
        public Fatal {
            token(generation); require(sequence >= 0 && sequence <= MAX_JOBS, ErrorCode.LIMIT);
            nonnull(reason);
        }
    }

    public static String jobId(String generation, int sequence) {
        token(generation); require(sequence >= 1 && sequence <= MAX_JOBS, ErrorCode.LIMIT);
        return generation + "-job-" + sequence;
    }

    public static byte[] encodeLaunch(Launch value) {
        nonnull(value);
        Writer out = new Writer(MAX_LAUNCH_BYTES);
        writeLaunch(out, value.identity, value.limits, value.generationRoot, value.directory,
                value.command, value.environment, value.payload);
        return out.finish();
    }

    private static void writeLaunch(Writer out, Identity identity, Limits limits, Root root, String directory,
                                    List<String> command, Map<String, String> environment, byte[] payload) {
        out.string(LAUNCH_VERSION, MAX_ID_BYTES);
        writeIdentity(out, identity);
        out.i32(limits.maxJobs); out.i32(limits.inputLimit); out.i32(limits.outputLimit);
        writeRoot(out, root); out.string(directory, MAX_PATH_BYTES);
        out.i32(command.size());
        for (String value : command) out.string(value, MAX_PATH_BYTES);
        out.i32(environment.size());
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            out.string(entry.getKey(), MAX_ID_BYTES); out.string(entry.getValue(), MAX_PATH_BYTES);
        }
        out.bytes(payload, MAX_LAUNCH_BODY_BYTES);
    }

    public static Launch decodeLaunch(byte[] bytes) {
        Reader in = new Reader(bytes, MAX_LAUNCH_BYTES);
        require(LAUNCH_VERSION.equals(in.string(MAX_ID_BYTES)), ErrorCode.VERSION);
        Identity identity = readIdentity(in);
        Limits limits = new Limits(in.i32(), in.i32(), in.i32());
        Root root = readRoot(in);
        String directory = in.string(MAX_PATH_BYTES);
        int count = in.count(MAX_COMMAND);
        List<String> command = new ArrayList<>(count);
        for (int i = 0; i < count; i++) command.add(in.string(MAX_PATH_BYTES));
        count = in.count(MAX_ENVIRONMENT);
        Map<String, String> environment = new TreeMap<>();
        String previous = null;
        for (int i = 0; i < count; i++) {
            String name = in.string(MAX_ID_BYTES);
            require(previous == null || previous.compareTo(name) < 0, ErrorCode.ORDER);
            previous = name;
            String value = in.string(MAX_PATH_BYTES);
            require(environment.put(name, value) == null, ErrorCode.DUPLICATE);
        }
        byte[] payload = in.bytes(MAX_LAUNCH_BODY_BYTES);
        in.end();
        return new Launch(identity, limits, root, directory, command, environment, payload);
    }

    /** Kind/version/fields, not length prefix. No map/object deserialization or extension fallback. */
    public static byte[] encode(Message value) {
        nonnull(value);
        int kind = kind(value);
        Writer out = new Writer(kindLimit(kind));
        out.u8(kind); out.string(VERSION, MAX_ID_BYTES);
        if (value instanceof Hello hello) {
            writeIdentity(out, hello.identity);
            out.string(hello.launchDigest, 71); out.string(hello.loaderToken, 32);
            out.string(hello.observedVersion, MAX_ID_BYTES);
            out.bool(hello.originsVerified); out.bool(hello.initialResetObserved);
        } else if (value instanceof Job job) {
            out.string(job.generation, 32); out.string(job.helloDigest, 71);
            out.i32(job.sequence); out.string(job.jobId, 48);
            out.string(job.originalKey, 71); out.string(job.effectiveKey, 71);
            out.string(job.deadlineIdentity, 32); out.bytes(job.payload, MAX_JOB_BODY_BYTES);
        } else if (value instanceof Result result) {
            out.string(result.generation, 32); out.string(result.helloDigest, 71);
            out.i32(result.sequence); out.string(result.jobId, 48);
            out.string(result.requestDigest, 71); out.string(result.originalKey, 71);
            out.string(result.effectiveKey, 71); out.string(result.loaderToken, 32);
            out.u8(result.state.ordinal() + 1); out.bytes(result.payload, MAX_RESULT_BODY_BYTES);
        } else if (value instanceof Fatal fatal) {
            out.string(fatal.generation, 32); out.i32(fatal.sequence); out.u8(fatal.reason.ordinal() + 1);
        } else {
            throw failure(ErrorCode.KIND);
        }
        return out.finish();
    }

    public static Message decode(byte[] bytes) {
        Reader in = new Reader(bytes, MAX_RESULT_BYTES);
        int kind = in.u8();
        require(bytes.length <= kindLimit(kind), ErrorCode.LENGTH);
        require(VERSION.equals(in.string(MAX_ID_BYTES)), ErrorCode.VERSION);
        Message value;
        switch (kind) {
            case HELLO -> value = new Hello(readIdentity(in), in.string(71), in.string(32),
                    in.string(MAX_ID_BYTES), in.bool(), in.bool());
            case JOB -> value = new Job(in.string(32), in.string(71), in.i32(), in.string(48),
                    in.string(71), in.string(71), in.string(32), in.bytes(MAX_JOB_BODY_BYTES));
            case RESULT -> value = new Result(in.string(32), in.string(71), in.i32(), in.string(48),
                    in.string(71), in.string(71), in.string(71), in.string(32),
                    enumValue(ResultState.values(), in.u8()), in.bytes(MAX_RESULT_BODY_BYTES));
            case FATAL -> value = new Fatal(in.string(32), in.i32(), enumValue(FatalReason.values(), in.u8()));
            default -> throw failure(ErrorCode.KIND);
        }
        in.end();
        return value;
    }

    public static byte[] frame(Message value) {
        byte[] payload = encode(value);
        Writer out = new Writer(payload.length + 4);
        out.i32(payload.length); out.raw(payload);
        return out.finish();
    }

    public static Message decodeFrame(byte[] frame) {
        Reader in = new Reader(frame, MAX_RESULT_BYTES + 4);
        int length = in.i32();
        require(length > 0 && length <= MAX_RESULT_BYTES, ErrorCode.LENGTH);
        require(length == in.remaining(), length > in.remaining() ? ErrorCode.TRUNCATED : ErrorCode.TRAILING);
        byte[] payload = in.raw(length);
        in.end();
        return decode(payload);
    }

    public static String launchDigest(Launch value) { return digest("application-launch", encodeLaunch(value)); }
    public static String helloDigest(Hello value) { return digest("application-hello", encode(value)); }
    public static String requestDigest(Job value) { return digest("application-request", encode(value)); }

    /** Raw CONTENT hash only: matches external artifact/source pins. Structured identities use digest. */
    public static String sha256(byte[] fileContent) {
        nonnull(fileContent);
        return "sha256:" + HexFormat.of().formatHex(sha().digest(fileContent));
    }

    /** Canonical domain and payload are separately length-prefixed; concatenation is never ambiguous. */
    public static String digest(String domain, byte[] canonicalPayload) {
        identifier(domain); nonnull(canonicalPayload);
        require(canonicalPayload.length <= MAX_STREAM_BYTES, ErrorCode.LIMIT);
        byte[] domainBytes = utf8("ktchecker/worker-wire/preview-1/" + domain, 256);
        MessageDigest hash = sha();
        hash.update(intBytes(domainBytes.length)); hash.update(domainBytes);
        hash.update(intBytes(canonicalPayload.length)); hash.update(canonicalPayload);
        return "sha256:" + HexFormat.of().formatHex(hash.digest());
    }

    public static void matchHello(Launch launch, Hello hello) {
        nonnull(launch); nonnull(hello);
        require(launch.identity.equals(hello.identity) && launchDigest(launch).equals(hello.launchDigest), ErrorCode.IDENTITY);
        require(hello.originsVerified && hello.initialResetObserved, ErrorCode.UNSEALED);
    }

    public static void matchJob(Launch launch, Hello hello, Job job, int expectedSequence) {
        nonnull(job); matchHello(launch, hello);
        require(expectedSequence >= 1 && expectedSequence <= launch.limits.maxJobs, ErrorCode.LIMIT);
        require(job.sequence == expectedSequence && job.generation.equals(launch.identity.generation)
                && job.helloDigest.equals(helloDigest(hello)), ErrorCode.IDENTITY);
    }

    /** Matching does not consume a grant. A sequential owner must separately enforce single use. */
    public static void matchResult(Launch launch, Hello hello, Job job, Result result) {
        nonnull(result); matchJob(launch, hello, job, job.sequence);
        require(result.generation.equals(job.generation) && result.helloDigest.equals(job.helloDigest)
                && result.sequence == job.sequence && result.jobId.equals(job.jobId)
                && result.requestDigest.equals(requestDigest(job)) && result.originalKey.equals(job.originalKey)
                && result.effectiveKey.equals(job.effectiveKey) && result.loaderToken.equals(hello.loaderToken), ErrorCode.IDENTITY);
        require(result.state == ResultState.APPLICATION_SEALED, ErrorCode.UNSEALED);
    }

    /**
     * Single-thread-confined incremental parser. Retains ONE partial payload, never a result list.
     * Chunk boundaries have no meaning. Any malformed byte or consumer exception is permanently
     * fatal: no resynchronization, retry, alternate schema or acceptance after partial EOF.
     */
    public static final class Decoder {
        private final int maxFrames;
        private final long maxBytes;
        private final byte[] prefix = new byte[4];
        private int prefixUsed, payloadLength, payloadUsed, frames;
        private byte[] payload;
        private long received;
        private boolean failed, ended, accepting;

        public Decoder(int maxFrames, long maxBytes) {
            require(maxFrames >= 1 && maxFrames <= MAX_JOBS + 2
                    && maxBytes >= 1 && maxBytes <= MAX_STREAM_BYTES, ErrorCode.LIMIT);
            this.maxFrames = maxFrames;
            this.maxBytes = maxBytes;
        }

        public void accept(byte[] bytes, Consumer<Message> consume) {
            accept(bytes, 0, bytes == null ? 0 : bytes.length, consume);
        }

        public void accept(byte[] bytes, int offset, int length, Consumer<Message> consume) {
            try {
                require(!failed, ErrorCode.FAILED); require(!ended, ErrorCode.ENDED);
                require(!accepting, ErrorCode.CONSUMER);
                nonnull(bytes); nonnull(consume);
                require(offset >= 0 && length >= 0 && offset <= bytes.length
                        && length <= bytes.length - offset, ErrorCode.LENGTH);
                require(length <= maxBytes - received, ErrorCode.LIMIT);
                // Charge the entire received chunk before invoking any user callback.
                received += length;
                accepting = true;
                int cursor = offset, end = offset + length;
                while (cursor < end) {
                    if (prefixUsed < 4) {
                        require(frames < maxFrames, ErrorCode.LIMIT);
                        int n = Math.min(4 - prefixUsed, end - cursor);
                        System.arraycopy(bytes, cursor, prefix, prefixUsed, n);
                        prefixUsed += n; cursor += n;
                        if (prefixUsed < 4) continue;
                        payloadLength = (prefix[0] & 255) << 24 | (prefix[1] & 255) << 16
                                | (prefix[2] & 255) << 8 | (prefix[3] & 255);
                        require(payloadLength > 0 && payloadLength <= MAX_RESULT_BYTES, ErrorCode.LENGTH);
                        // The full declared frame must fit the ORIGINAL admitted byte cap.
                        require(payloadLength <= maxBytes - (received - (end - cursor)), ErrorCode.LIMIT);
                    }
                    if (payload == null) {
                        if (cursor == end) break;
                        int kind = bytes[cursor] & 255;
                        // Validate the kind-specific bound BEFORE allocating the payload.
                        require(payloadLength <= kindLimit(kind), ErrorCode.LENGTH);
                        payload = new byte[payloadLength];
                        payload[0] = bytes[cursor++]; payloadUsed = 1;
                    }
                    int n = Math.min(payloadLength - payloadUsed, end - cursor);
                    System.arraycopy(bytes, cursor, payload, payloadUsed, n);
                    payloadUsed += n; cursor += n;
                    if (payloadUsed == payloadLength) {
                        Message value = decode(payload);
                        frames++;
                        prefixUsed = 0; payloadLength = 0; payloadUsed = 0; payload = null;
                        try { consume.accept(value); }
                        catch (RuntimeException | Error rejected) { throw failure(ErrorCode.CONSUMER); }
                        require(!failed && !ended, ErrorCode.FAILED);
                    }
                }
            } catch (RuntimeException | Error rejected) {
                poison();
                throw rejected;
            } finally {
                accepting = false;
            }
        }

        public void endOfInput() {
            try {
                require(!failed, ErrorCode.FAILED); require(!ended, ErrorCode.ENDED);
                require(!accepting, ErrorCode.CONSUMER);
                require(!hasPartialFrame(), ErrorCode.TRUNCATED);
                ended = true;
            } catch (RuntimeException | Error rejected) {
                poison();
                throw rejected;
            }
        }

        public boolean hasPartialFrame() { return prefixUsed != 0 || payload != null; }
        public boolean isFailed() { return failed; }
        public int frameCount() { return frames; }
        public long receivedBytes() { return received; }

        private void poison() {
            failed = true;
            payload = null; payloadLength = 0; payloadUsed = 0;
            // prefixUsed deliberately survives: poisoned partial state is not clean EOF.
        }
    }

    private static int kind(Message value) {
        if (value instanceof Hello) return HELLO;
        if (value instanceof Job) return JOB;
        if (value instanceof Result) return RESULT;
        if (value instanceof Fatal) return FATAL;
        throw failure(ErrorCode.KIND);
    }

    private static int kindLimit(int kind) {
        return switch (kind) {
            case HELLO -> MAX_HELLO_BYTES;
            case JOB -> MAX_JOB_BYTES;
            case RESULT -> MAX_RESULT_BYTES;
            case FATAL -> MAX_FATAL_BYTES;
            default -> throw failure(ErrorCode.KIND);
        };
    }

    private static void writeIdentity(Writer out, Identity identity) {
        out.string(identity.generation, 32); out.string(identity.ownerDomain, 32);
        out.string(identity.worktreeDigest, 71); out.string(identity.implementationDigest, 71);
        out.string(identity.bridgeDigest, 71); out.string(identity.compilerClosureDigest, 71);
        out.string(identity.toolchainDigest, 71); out.string(identity.adapterVersion, MAX_ID_BYTES);
        out.string(identity.profile, MAX_ID_BYTES);
    }

    private static Identity readIdentity(Reader in) {
        return new Identity(in.string(32), in.string(32), in.string(71), in.string(71), in.string(71),
                in.string(71), in.string(71), in.string(MAX_ID_BYTES), in.string(MAX_ID_BYTES));
    }

    static void writeRoot(Writer out, Root root) {
        out.string(root.path, MAX_PATH_BYTES); out.string(root.fileKey, MAX_KEY_BYTES);
    }

    static Root readRoot(Reader in) { return new Root(in.string(MAX_PATH_BYTES), in.string(MAX_KEY_BYTES)); }

    private static void jobIdentity(String generation, int sequence, String jobId) {
        require(jobId(generation, sequence).equals(jobId), ErrorCode.IDENTITY);
    }

    static void token(String value) {
        nonnull(value); require(value.length() == 32 && value.matches("[a-f0-9]{32}"), ErrorCode.TOKEN);
    }

    static void checkDigest(String value) {
        nonnull(value); require(value.length() == 71 && value.matches("sha256:[a-f0-9]{64}"), ErrorCode.DIGEST);
    }

    static void identifier(String value) {
        nonnull(value);
        require(value.length() >= 1 && value.length() <= MAX_ID_BYTES
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:/+\\-]*"), ErrorCode.VALUE);
    }

    static void key(String value) {
        text(value, MAX_KEY_BYTES, false);
        require(!value.isBlank(), ErrorCode.VALUE);
    }

    static void absolutePath(String value) {
        text(value, MAX_PATH_BYTES, false);
        try {
            Path path = Path.of(value);
            require(path.isAbsolute() && path.normalize().equals(path) && path.toString().equals(value), ErrorCode.PATH);
        } catch (InvalidPathException invalid) {
            throw failure(ErrorCode.PATH);
        }
    }

    /** Bounded strict UTF-8, also excluding control characters in structural/path/identity strings. */
    static void text(String value, int maximum, boolean emptyAllowed) {
        byte[] encoded = utf8(value, maximum);
        require(emptyAllowed || encoded.length != 0, ErrorCode.VALUE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c >= 32 && c != 127, ErrorCode.VALUE);
        }
    }

    static byte[] utf8(String value, int maximum) {
        nonnull(value); require(value.length() <= maximum, ErrorCode.LIMIT);
        require(value.indexOf('\0') < 0, ErrorCode.NUL);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            require(encoded.remaining() <= maximum, ErrorCode.LIMIT);
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw failure(ErrorCode.UTF8);
        }
    }

    static List<String> strings(List<String> values, int maximumCount, int maximumBytes) {
        nonnull(values); require(values.size() <= maximumCount, ErrorCode.LIMIT);
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            require(copy.size() < maximumCount, ErrorCode.LIMIT);
            utf8(value, maximumBytes); copy.add(value);
        }
        return List.copyOf(copy);
    }

    static <T> List<T> list(List<T> values, int maximum) {
        nonnull(values); require(values.size() <= maximum, ErrorCode.LIMIT);
        ArrayList<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            require(copy.size() < maximum, ErrorCode.LIMIT); nonnull(value); copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static Map<String, String> environmentCopy(Map<String, String> values) {
        nonnull(values); require(values.size() <= MAX_ENVIRONMENT, ErrorCode.LIMIT);
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            require(copy.size() < MAX_ENVIRONMENT, ErrorCode.LIMIT); nonnull(entry);
            String name = entry.getKey();
            text(name, MAX_ID_BYTES, false);
            require(name.matches("[A-Za-z_][A-Za-z0-9_]*"), ErrorCode.VALUE);
            require(!name.startsWith("LD_") && !name.startsWith("DYLD_")
                    && !List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH",
                    "JAVA_OPTS", "JDK_JAVAC_OPTIONS", "GLIBC_TUNABLES", "GCONV_PATH", "LOCPATH").contains(name), ErrorCode.UNSUPPORTED);
            String value = entry.getValue(); utf8(value, MAX_PATH_BYTES);
            require(copy.put(name, value) == null, ErrorCode.DUPLICATE);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static byte[] bytesCopy(byte[] values, int maximum) {
        nonnull(values); require(values.length <= maximum, ErrorCode.LIMIT);
        return values.clone();
    }

    static <T> T enumValue(T[] values, int encoded) {
        require(encoded >= 1 && encoded <= values.length, ErrorCode.ENUM);
        return values[encoded - 1];
    }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("sha256_unavailable"); }
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    static void nonnull(Object value) { require(value != null, ErrorCode.NULL); }
    static void require(boolean value, ErrorCode code) { if (!value) throw failure(code); }
    static ProtocolException failure(ErrorCode code) { return new ProtocolException(code); }

    /** Fixed-capacity writer: an oversized field is rejected before writing or growing a buffer. */
    static final class Writer {
        private final byte[] bytes;
        private int position;
        Writer(int maximum) {
            require(maximum > 0 && maximum <= MAX_RESULT_BYTES + 4, ErrorCode.LIMIT);
            bytes = new byte[maximum];
        }
        private void reserve(int count) { require(count >= 0 && count <= bytes.length - position, ErrorCode.LIMIT); }
        void u8(int value) { reserve(1); require(value >= 0 && value <= 255, ErrorCode.VALUE); bytes[position++] = (byte) value; }
        void bool(boolean value) { u8(value ? 1 : 0); }
        void i32(int value) {
            reserve(4);
            bytes[position++] = (byte) (value >>> 24); bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8); bytes[position++] = (byte) value;
        }
        void i64(long value) { i32((int) (value >>> 32)); i32((int) value); }
        void string(String value, int maximum) { bytes(utf8(value, maximum), maximum); }
        void bytes(byte[] value, int maximum) {
            nonnull(value); require(value.length <= maximum, ErrorCode.LIMIT);
            reserve(4 + value.length); i32(value.length); raw(value);
        }
        void raw(byte[] value) {
            nonnull(value); reserve(value.length);
            System.arraycopy(value, 0, bytes, position, value.length); position += value.length;
        }
        byte[] finish() { return Arrays.copyOf(bytes, position); }
    }

    /** A detached snapshot of caller bytes: no decoder result retains a caller-owned byte array. */
    static final class Reader {
        private final byte[] bytes;
        private int position;
        Reader(byte[] bytes, int maximum) {
            nonnull(bytes); require(bytes.length <= maximum, ErrorCode.LIMIT);
            this.bytes = bytes.clone();
        }
        int remaining() { return bytes.length - position; }
        private void need(int count) { require(count >= 0 && count <= remaining(), ErrorCode.TRUNCATED); }
        int u8() { need(1); return bytes[position++] & 255; }
        boolean bool() { int value = u8(); require(value <= 1, ErrorCode.BOOLEAN); return value == 1; }
        int i32() {
            need(4);
            return (bytes[position++] & 255) << 24 | (bytes[position++] & 255) << 16
                    | (bytes[position++] & 255) << 8 | (bytes[position++] & 255);
        }
        long i64() { return ((long) i32() << 32) | (i32() & 0xffff_ffffL); }
        int count(int maximum) { int value = i32(); require(value >= 0 && value <= maximum, ErrorCode.LIMIT); return value; }
        byte[] raw(int length) {
            need(length); byte[] result = Arrays.copyOfRange(bytes, position, position + length); position += length; return result;
        }
        byte[] bytes(int maximum) { return raw(count(maximum)); }
        String string(int maximum) {
            int length = count(maximum); need(length);
            try {
                String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, position, length)).toString();
                position += length;
                require(value.indexOf('\0') < 0, ErrorCode.NUL);
                return value;
            } catch (CharacterCodingException invalid) {
                throw failure(ErrorCode.UTF8);
            }
        }
        void end() { require(position == bytes.length, ErrorCode.TRAILING); }
    }
}
