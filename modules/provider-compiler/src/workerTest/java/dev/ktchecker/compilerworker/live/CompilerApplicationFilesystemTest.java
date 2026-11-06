package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerJobWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static dev.ktchecker.compilerworker.live.CompilerApplicationMain.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Local filesystem controls only. No JVM, compiler artifact, native helper or source is launched. */
class CompilerApplicationFilesystemTest {
    @TempDir Path temporary;
    private Path root;

    @BeforeEach void trustedTemporaryRoot() throws IOException {
        // Resolve only JUnit's trusted fixture root (for example macOS /var -> /private/var).
        // Candidate aliases and links below it must still reach the worker unchanged.
        root = temporary.toRealPath();
    }

    @Test void exactRegisteredRootAndSourceAreAcceptedWithoutNormalizingAliases() throws Exception {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Path source = Files.writeString(sources.resolve("Fixture.kt"), "fun valid(): Int = 1\n", StandardCharsets.UTF_8);
        assumeStableFileKey(sources);
        assumeReadableFile(source);
        ApplicationWire.Root registered = observeRoot(sources);
        assertEquals(sources, checkRoot(registered));
        expectNames(sources, Set.of("Fixture.kt"));
        strictSource(source);
        byte[] bytes = Files.readAllBytes(source);
        FileReceipt receipt = FileReceipt.capture(source, bytes.length, ApplicationWire.sha256(bytes), null);
        assertDoesNotThrow(receipt::verify);
    }

    @Test void sourceByteChangeAtSameLogicalPathIsDetected() throws Exception {
        Path source = Files.writeString(root.resolve("Fixture.kt"), "fun x() = 1\n");
        FileReceipt receipt = capture(source);
        Files.writeString(source, "fun x() = 2\n");
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void equalBytesDoNotMakeAReplacementFileTheSameRegisteredMember() throws Exception {
        Path source = Files.writeString(root.resolve("Fixture.kt"), "fun x() = 1\n");
        String sourceKey = assumeStableFileKey(source);
        FileReceipt receipt = capture(source);
        Path replacement = Files.writeString(root.resolve("replacement.kt"), "fun x() = 1\n");
        assumeTrue(!sourceKey.equals(assumeStableFileKey(replacement)),
                "Fixture filesystem does not expose distinct replacement-file keys");
        Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void wrongSourceFileKeyFailsBeforeHashCanAuthorizeIt() throws Exception {
        Path source = Files.writeString(root.resolve("Fixture.kt"), "fun x() = 1\n");
        assertNotEquals("wrong-file-key", assumeStableFileKey(source));
        byte[] bytes = Files.readAllBytes(source);
        assertThrows(WorkerFailure.class, () -> FileReceipt.capture(source, bytes.length, ApplicationWire.sha256(bytes), "wrong-file-key"));
    }

    @Test void wrongRegisteredRootKeyIsRejected() throws Exception {
        Path directory = Files.createDirectory(root.resolve("sources"));
        assertNotEquals("wrong-file-key", assumeStableFileKey(directory));
        assertThrows(WorkerFailure.class, () -> checkRoot(new ApplicationWire.Root(directory.toString(), "wrong-file-key")));
    }

    @Test void byteEqualFixtureAtDifferentPathDoesNotSatisfyExactMemberSet() throws Exception {
        Path directory = Files.createDirectory(root.resolve("sources"));
        Files.writeString(directory.resolve("Renamed.kt"), "fun x() = 1\n");
        assumeStableFileKey(directory);
        assertThrows(WorkerFailure.class, () -> expectNames(directory, Set.of("Fixture.kt")));
    }

    @Test void extraKotlinJavaScriptAndMetadataMembersAreAllRejected() throws Exception {
        Path directory = Files.createDirectory(root.resolve("sources"));
        Path source = Files.writeString(directory.resolve("Fixture.kt"), "fun x() = 1\n");
        assumeStableFileKey(directory);
        assumeStableFileKey(source);
        for (String name : new String[]{"Extra.kt", "Extra.java", "script.kts", ".hidden"}) {
            Path extra = Files.writeString(directory.resolve(name), "extra");
            assertThrows(WorkerFailure.class, () -> expectNames(directory, Set.of("Fixture.kt")));
            Files.delete(extra);
        }
    }

    @Test void freshOutputCannotContainEvenOnePreexistingMember() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        assumeStableFileKey(output);
        expectNames(output, Set.of());
        Files.write(output.resolve("Old.class"), new byte[]{1});
        assertThrows(WorkerFailure.class, () -> expectNames(output, Set.of()));
    }

    @Test void lexicalParentAndDotAliasesAreNeverNormalizedIntoAdmission() {
        assertThrows(WorkerFailure.class, () -> canonicalPath(root.resolve("..").resolve("Fixture.kt").toString()));
        assertThrows(WorkerFailure.class, () -> canonicalPath(root.resolve(".").resolve("Fixture.kt").toString()));
        assertThrows(WorkerFailure.class, () -> canonicalPath("Fixture.kt"));
    }

    @Test void finalSymlinkIsNotAnArtifactOrSourceFile() throws Exception {
        Path actual = Files.writeString(root.resolve("actual.kt"), "fun x() = 1\n");
        assumeStableFileKey(root);
        assumeStableFileKey(actual);
        Path link = symbolicLink(root.resolve("Fixture.kt"), actual);
        assertThrows(WorkerFailure.class, () -> readBoundedFile(link, CompilerJobWire.MAX_SOURCE_BYTES));
        assertThrows(WorkerFailure.class, () -> expectNames(root, Set.of("actual.kt", "Fixture.kt")));
    }

    @Test void symlinkInAnAncestorIsRejectedBeforeFileOpen() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        Files.writeString(actual.resolve("Fixture.kt"), "fun x() = 1\n");
        Path alias = symbolicLink(root.resolve("sources"), actual);
        assertThrows(WorkerFailure.class, () -> canonicalPath(alias.resolve("Fixture.kt").toString()));
    }

    @Test void rootSymlinkCannotBorrowARealDirectoryFileKey() throws Exception {
        Path actual = Files.createDirectory(root.resolve("actual"));
        assumeStableFileKey(actual);
        ApplicationWire.Root registered = observeRoot(actual);
        Path link = symbolicLink(root.resolve("sources"), actual);
        ApplicationWire.Root aliased = new ApplicationWire.Root(link.toString(), registered.fileKey());
        assertThrows(WorkerFailure.class, () -> checkRoot(aliased));
    }

    @Test void malformedUtf8CannotReachCompilerArgumentConstruction() throws Exception {
        Path source = Files.write(root.resolve("Fixture.kt"), new byte[]{(byte) 0xc3, 0x28});
        assumeReadableFile(source);
        assertThrows(WorkerFailure.class, () -> strictSource(source));
    }

    @Test void emptyOversizedNulAndBomSourceAreRejected() throws Exception {
        Path source = root.resolve("Fixture.kt");
        for (byte[] bytes : new byte[][]{
                new byte[0], new byte[CompilerJobWire.MAX_SOURCE_BYTES + 1],
                "fun x() = 1\u0000\n".getBytes(StandardCharsets.UTF_8),
                "\ufefffun x() = 1\n".getBytes(StandardCharsets.UTF_8)}) {
            Files.write(source, bytes);
            assumeReadableFile(source);
            assertThrows(WorkerFailure.class, () -> strictSource(source));
        }
    }

    @Test void boundedReadRejectsOverlengthBeforeUnboundedAllocation() throws Exception {
        Path value = Files.write(root.resolve("launch.bin"), new byte[65]);
        assumeStableFileKey(value); // The size fence rejects before any NOFOLLOW open is needed.
        assertThrows(WorkerFailure.class, () -> readBoundedFile(value, 64));
    }

    @Test void outputTreeCountsExactFilesAndBytes() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        Path compiled = Files.write(output.resolve("FixtureKt.class"), new byte[11]);
        Path metadata = Files.createDirectory(output.resolve("META-INF"));
        Path module = Files.write(metadata.resolve("main.kotlin_module"), new byte[7]);
        assumeDistinctFileKeys(output, compiled, metadata, module);
        assertEquals(new TreeSize(2, 18), scanTree(output, 2, 18, new HashSet<>()));
    }

    @Test void outputTreeByteAndFileCapsAreIndependent() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        Path first = Files.write(output.resolve("one.class"), new byte[4]);
        Path second = Files.write(output.resolve("two.class"), new byte[4]);
        assumeDistinctFileKeys(output, first, second);
        assertThrows(WorkerFailure.class, () -> scanTree(output, 1, 64, new HashSet<>()));
        assertThrows(WorkerFailure.class, () -> scanTree(output, 8, 7, new HashSet<>()));
    }

    @Test void outputHardLinkAliasesAreRejected() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        Path first = Files.write(output.resolve("first.class"), new byte[]{1});
        assumeDistinctFileKeys(output, first);
        hardLink(output.resolve("alias.class"), first);
        assertThrows(WorkerFailure.class, () -> scanTree(output, 8, 64, new HashSet<>()));
    }

    @Test void outputSymlinkCannotEscapeItsRegisteredRoot() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        Path outside = Files.write(root.resolve("outside.class"), new byte[]{1});
        assumeStableFileKey(output);
        symbolicLink(output.resolve("escape.class"), outside);
        assertThrows(WorkerFailure.class, () -> scanTree(output, 8, 64, new HashSet<>()));
    }

    @Test void outputTreeDepthIsBoundedEvenWhenThereAreNoFiles() throws Exception {
        Path output = Files.createDirectory(root.resolve("classes"));
        Path cursor = output;
        Set<String> fixtureKeys = new HashSet<>();
        fixtureKeys.add(assumeStableFileKey(output));
        for (int i = 0; i <= MAX_TREE_DEPTH; i++) {
            cursor = Files.createDirectory(cursor.resolve("d")); // Keep fixture paths short on Windows.
            assumeTrue(fixtureKeys.add(assumeStableFileKey(cursor)),
                    "Fixture filesystem does not expose distinct directory keys");
        }
        assertThrows(WorkerFailure.class, () -> scanTree(output, 128, 1024, new HashSet<>()));
    }

    @Test void reusedOutputPhysicalKeysAcrossJobRootsAreRejected() throws Exception {
        Path first = Files.createDirectory(root.resolve("first-classes"));
        Path second = Files.createDirectory(root.resolve("second-classes"));
        Path compiled = Files.write(first.resolve("FixtureKt.class"), new byte[]{1});
        assumeDistinctFileKeys(first, second, compiled);
        hardLink(second.resolve("FixtureKt.class"), compiled);
        Set<String> generationKeys = new HashSet<>();
        scanTree(first, 8, 64, generationKeys);
        assertThrows(WorkerFailure.class, () -> scanTree(second, 8, 64, generationKeys));
    }

    @Test void capabilityClassificationDoesNotHideUnexpectedIoOrSetupFailures() {
        assertTrue(linkUnavailable(new AccessDeniedException("link")));
        assertFalse(unsupportedFeature(new AccessDeniedException("ordinary-file")));
        for (String reason : new String[]{"A required privilege is not held by the client.",
                "Operation not permitted", "Permission denied"}) {
            IOException failure = new FileSystemException("link", "target", reason);
            assertTrue(linkUnavailable(failure));
            assertFalse(unsupportedFeature(failure)); // Link privilege is not a metadata/read skip.
        }
        for (String reason : new String[]{"Operation not supported", "Not supported",
                "The request is not supported.", "Incorrect function.", "Function not implemented"}) {
            IOException failure = new FileSystemException("fixture", null, reason);
            assertTrue(unsupportedFeature(failure));
            assertTrue(linkUnavailable(failure));
        }
        for (IOException failure : new IOException[]{
                new FileSystemException("fixture", null, "No space left on device"),
                new FileSystemException("fixture", null, "Disk quota exceeded"),
                new FileSystemException("fixture", null, "Input/output error"),
                new FileSystemException("fixture", null, "The parameter is incorrect"),
                new FileSystemException("fixture", null, null),
                new IOException("Operation not supported"),
                new NoSuchFileException("missing", null, "Operation not supported"),
                new FileAlreadyExistsException("existing", null, "Operation not permitted")}) {
            assertFalse(unsupportedFeature(failure));
            assertFalse(linkUnavailable(failure));
        }
    }

    // These helpers inspect only fixtures, before the worker call under assertion. In particular,
    // an assumption must never run inside assertThrows(WorkerFailure.class, ...) and masquerade
    // as a worker rejection. Unknown IO/setup errors remain failures, not capability skips.
    private static BasicFileAttributes noFollowAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Fixture filesystem lacks NOFOLLOW_LINKS basic attributes");
            throw unsupported;
        } catch (FileSystemException failure) {
            if (!unsupportedFeature(failure)) throw failure;
            assumeTrue(false, "Fixture filesystem lacks NOFOLLOW_LINKS basic attributes");
            throw failure;
        }
    }

    private static String assumeStableFileKey(Path path) throws IOException {
        BasicFileAttributes first = noFollowAttributes(path);
        BasicFileAttributes second = noFollowAttributes(path);
        assertTrue(!first.isSymbolicLink() && !second.isSymbolicLink(), "Expected an ordinary fixture member");
        assumeTrue(first.fileKey() != null && second.fileKey() != null,
                "Fixture filesystem does not expose physical file keys");
        String key = first.fileKey().toString();
        assumeTrue(key != null && !key.isBlank() && key.equals(second.fileKey().toString()),
                "Fixture filesystem does not expose stable comparable file-key strings");
        return key;
    }

    private static void assumeDistinctFileKeys(Path... paths) throws IOException {
        Set<String> keys = new HashSet<>();
        for (Path path : paths) assumeTrue(keys.add(assumeStableFileKey(path)),
                "Fixture filesystem does not expose distinct physical file keys");
    }

    private static void assumeReadableFile(Path path) throws IOException {
        assumeStableFileKey(path);
        assertTrue(noFollowAttributes(path).isRegularFile(), "Expected an ordinary fixture file");
        InputStream input;
        try {
            input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Fixture filesystem lacks NOFOLLOW_LINKS file opens");
            throw unsupported;
        } catch (FileSystemException failure) {
            if (!unsupportedFeature(failure)) throw failure;
            assumeTrue(false, "Fixture filesystem lacks NOFOLLOW_LINKS file opens");
            throw failure;
        }
        try (input) { input.read(); } // Read/close failures are not unsupported-open skips.
    }

    private static Path symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic-link fixture creation support or privilege is unavailable");
            throw unavailable;
        } catch (FileSystemException failure) {
            if (!linkUnavailable(failure)) throw failure;
            assumeTrue(false, "Symbolic-link fixture creation support or privilege is unavailable");
            throw failure;
        }
        assumeTrue(noFollowAttributes(link).isSymbolicLink(),
                "Fixture filesystem lacks NOFOLLOW_LINKS symbolic-link metadata");
        return link;
    }

    private static Path hardLink(Path link, Path target) throws IOException {
        String targetKey = assumeStableFileKey(target);
        try {
            Files.createLink(link, target);
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Hard-link fixture creation support or privilege is unavailable");
            throw unavailable;
        } catch (FileSystemException failure) {
            if (!linkUnavailable(failure)) throw failure;
            assumeTrue(false, "Hard-link fixture creation support or privilege is unavailable");
            throw failure;
        }
        assertTrue(Files.isSameFile(link, target), "Hard-link fixture must name the same physical file");
        assumeTrue(targetKey.equals(assumeStableFileKey(link)),
                "Fixture filesystem does not expose comparable hard-link file keys");
        return link;
    }

    private static boolean linkUnavailable(IOException failure) {
        if (failure instanceof AccessDeniedException) return true;
        if (unsupportedFeature(failure)) return true;
        return switch (genericFilesystemReason(failure)) {
            case "a required privilege is not held by the client", "operation not permitted", "permission denied" -> true;
            default -> false;
        };
    }

    private static boolean unsupportedFeature(IOException failure) {
        return switch (genericFilesystemReason(failure)) {
            case "operation not supported", "not supported", "the request is not supported",
                    "incorrect function", "function not implemented" -> true;
            default -> false;
        };
    }

    private static String genericFilesystemReason(IOException failure) {
        // Do not relabel missing/existing paths, arbitrary IOException subclasses or unknown
        // localized messages. Windows may report link privilege as a plain FileSystemException.
        if (failure.getClass() != FileSystemException.class) return "";
        String reason = ((FileSystemException) failure).getReason();
        if (reason == null) return "";
        reason = reason.trim().toLowerCase(Locale.ROOT);
        return reason.endsWith(".") ? reason.substring(0, reason.length() - 1) : reason;
    }

    private static FileReceipt capture(Path path) throws Exception {
        assumeReadableFile(path);
        byte[] bytes = Files.readAllBytes(path);
        return FileReceipt.capture(path, bytes.length, ApplicationWire.sha256(bytes), null);
    }
}
