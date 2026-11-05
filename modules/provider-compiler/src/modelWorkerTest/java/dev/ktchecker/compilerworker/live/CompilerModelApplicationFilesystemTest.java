package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerModelWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static dev.ktchecker.compilerworker.live.CompilerModelApplicationMain.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Local filesystem controls only. No JVM, compiler artifact, native helper or source is launched. */
class CompilerModelApplicationFilesystemTest {
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
        assertThrows(WorkerFailure.class, () -> readBoundedFile(link, CompilerModelWire.MAX_SOURCE_BYTES));
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

    @Test void oversizedNulAndBomSelectedSourceAreRejected() throws Exception {
        Path source = root.resolve("Fixture.kt");
        for (byte[] bytes : new byte[][]{
                new byte[CompilerModelWire.MAX_SOURCE_BYTES + 1],
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

    @Test void emptySelectedSourceIsAcceptedWithoutSynthesizingBytes() throws Exception {
        Path source = Files.write(root.resolve("Empty.kt"), new byte[0]);
        assumeReadableFile(source);
        assertDoesNotThrow(() -> strictSource(source));
        assertArrayEquals(new byte[0], Files.readAllBytes(source));
    }

    @Test void selectedCrlfAndSupplementaryUtf8BytesAreAcceptedUnchanged() throws Exception {
        byte[] original = "// \ud83d\ude00\r\nfun x() = 1\r\n".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(root.resolve("Lines.kt"), original);
        assumeReadableFile(source);
        assertDoesNotThrow(() -> strictSource(source));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void selectedBareCrIncludingFinalCrReturnsFixedUnsupportedAndDoesNotRewrite() throws Exception {
        for (String content : List.of("\r", "fun x() = 1\r", "one\rtwo", "one\r\r\n")) {
            byte[] original = content.getBytes(StandardCharsets.UTF_8);
            Path source = Files.write(root.resolve("Bare.kt"), original);
            assumeReadableFile(source);
            WorkerFailure failure = assertThrows(WorkerFailure.class, () -> strictSource(source));
            assertEquals(ApplicationWire.FatalReason.UNSUPPORTED, failure.reason);
            assertArrayEquals(original, Files.readAllBytes(source));
        }
    }

    @Test void fullNestedSnapshotRetainsOpaqueBinaryModelAndBuildMembers() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        SnapshotReceipt receipt = SnapshotReceipt.capture(fixture.root(), fixture.members(), true);
        assertDoesNotThrow(receipt::verify);
        for (FixtureMember original : snapshotBytes()) {
            assertArrayEquals(original.bytes(), Files.readAllBytes(receipt.file(original.path()).path()));
        }
        assertEquals(5, fixture.members().size());
        assertDoesNotThrow(() -> strictSource(receipt.file("src/One.kt").path()));
        assertDoesNotThrow(() -> strictSource(receipt.file("src/nested/Empty.kt").path()));
        assertThrows(WorkerFailure.class, () -> strictSource(receipt.file("resources/blob.bin").path()));
        // Only selecting the opaque binary as Kotlin would reject it; full membership itself is accepted.
    }

    @Test void opaqueNoncompiledByteMutationInvalidatesFullSnapshotReceipt() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        SnapshotReceipt receipt = SnapshotReceipt.capture(fixture.root(), fixture.members(), true);
        Path opaque = receipt.file("resources/blob.bin").path();
        byte[] changed = Files.readAllBytes(opaque); changed[changed.length - 1] ^= 1;
        Files.write(opaque, changed);
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void originalAndEffectiveSnapshotLeavesAreObservedIndependently() throws Exception {
        SnapshotFixture original = snapshot("original", snapshotBytes());
        SnapshotFixture effective = snapshot("effective", snapshotBytes());
        SnapshotReceipt originalReceipt = SnapshotReceipt.capture(original.root(), effective.members(), false);
        SnapshotReceipt effectiveReceipt = SnapshotReceipt.capture(effective.root(), effective.members(), true);
        Map<String, Path> identities = new LinkedHashMap<>();
        originalReceipt.addIdentities(identities); effectiveReceipt.addIdentities(identities);
        assertNotEquals(originalReceipt.file("src/One.kt").fileKey(), effectiveReceipt.file("src/One.kt").fileKey());
        assertDoesNotThrow(originalReceipt::verify); assertDoesNotThrow(effectiveReceipt::verify);
        assertThrows(WorkerFailure.class, () -> SnapshotReceipt.capture(effective.root(), original.members(), true));
    }

    @Test void identicalOriginalPathMayBeRetainedAgainButAnotherPathCannotBorrowItsKey() throws Exception {
        SnapshotFixture fixture = snapshot("original", snapshotBytes());
        SnapshotReceipt first = SnapshotReceipt.capture(fixture.root(), fixture.members(), false);
        SnapshotReceipt second = SnapshotReceipt.capture(fixture.root(), fixture.members(), false);
        Map<String, Path> identities = new LinkedHashMap<>();
        first.addIdentities(identities);
        int size = identities.size();
        assertDoesNotThrow(() -> second.addIdentities(identities));
        assertEquals(size, identities.size());
        FileReceipt leaf = first.file("src/One.kt");
        Map<String, Path> wrongPath = new LinkedHashMap<>();
        wrongPath.put(leaf.fileKey(), root.resolve("not-the-original-path"));
        assertThrows(WorkerFailure.class, () -> first.addIdentities(wrongPath));
    }

    @Test void nestedExtraFileAndUnmanifestedEmptyDirectoryCannotHideInSnapshot() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        SnapshotReceipt receipt = SnapshotReceipt.capture(fixture.root(), fixture.members(), true);
        Files.writeString(Path.of(fixture.root().path()).resolve("src/nested/extra.txt"), "unregistered");
        assertThrows(WorkerFailure.class, receipt::verify);
        SnapshotFixture second = snapshot("other", snapshotBytes());
        Files.createDirectory(Path.of(second.root().path()).resolve("unmanifested-empty"));
        assertThrows(WorkerFailure.class, () -> SnapshotReceipt.capture(second.root(), second.members(), true));
    }

    @Test void snapshotSubsetCannotStandInForTheActualWholeDirectory() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        List<CompilerModelWire.Member> selectedOnly = fixture.members().subList(0, 2);
        assertThrows(WorkerFailure.class, () -> SnapshotReceipt.capture(fixture.root(), selectedOnly, true));
    }

    @Test void nestedDirectorySymlinkCannotBorrowTheRetainedMemberKeys() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        Path nested = Path.of(fixture.root().path()).resolve("src/nested");
        Path retained = root.resolve("moved-nested");
        Files.move(nested, retained);
        symbolicLink(nested, retained);
        assertThrows(WorkerFailure.class, () -> SnapshotReceipt.capture(fixture.root(), fixture.members(), true));
    }

    @Test void originalHardLinkedSnapshotMembersCannotBorrowDistinctEffectiveKeys() throws Exception {
        Path sources = Files.createDirectory(root.resolve("original"));
        Path first = Files.writeString(sources.resolve("One.kt"), "x");
        assumeDistinctFileKeys(sources, first); assumeReadableFile(first);
        Path second = hardLink(sources.resolve("Two.kt"), first);
        assumeReadableFile(second);
        String digest = ApplicationWire.sha256(Files.readAllBytes(first));
        List<CompilerModelWire.Member> effectiveDescriptors = List.of(
                new CompilerModelWire.Member("One.kt", digest, 1, "independent-effective-one"),
                new CompilerModelWire.Member("Two.kt", digest, 1, "independent-effective-two"));
        ApplicationWire.Root observed = observeRoot(sources);
        assertThrows(WorkerFailure.class, () -> SnapshotReceipt.capture(observed, effectiveDescriptors, false));
    }

    @Test void parentMetadataAndFullPrecisionLeafTimeRemainPartOfReceipts() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        SnapshotReceipt receipt = SnapshotReceipt.capture(fixture.root(), fixture.members(), true);
        Path parent = Path.of(fixture.root().path()).resolve("src/nested");
        FileTime previous = noFollowAttributes(parent).lastModifiedTime();
        FileTime changed = FileTime.fromMillis(previous.toMillis() + 120_000);
        setFixtureModifiedTime(parent, changed);
        assumeTrue(!previous.equals(noFollowAttributes(parent).lastModifiedTime()), "Fixture cannot change directory modification time");
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void submillisecondLeafTimeChangeIsNotRoundedAwayWhenFilesystemSupportsIt() throws Exception {
        Path source = Files.writeString(root.resolve("One.kt"), "x");
        FileReceipt receipt = capture(source);
        FileTime changed = FileTime.from(receipt.modifiedTime().toInstant().plusNanos(1));
        setFixtureModifiedTime(source, changed);
        assumeTrue(changed.equals(noFollowAttributes(source).lastModifiedTime()), "Fixture lacks nanosecond timestamp precision");
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void repeatedClasspathCoordinatesAndDigestsRemainDistinctOrdinalFiles() throws Exception {
        Path classpath = Files.createDirectory(root.resolve("classpath"));
        Path first = archive(classpath.resolve("artifact-0.jar"), List.of(zipEntry("sample/Fake.class", new byte[]{1, 2, 3})));
        Path second = Files.copy(first, classpath.resolve("artifact-1.jar"));
        assumeReadableFile(second); assumeDistinctFileKeys(classpath, first, second);
        List<CompilerModelWire.Dependency> dependencies = List.of(dependency(first, "example:same:1"), dependency(second, "example:same:1"));
        assertEquals(dependencies.get(0).digest(), dependencies.get(1).digest());
        assertNotEquals(dependencies.get(0).fileKey(), dependencies.get(1).fileKey());
        ClasspathReceipt receipt = ClasspathReceipt.capture(observeRoot(classpath), dependencies, true);
        assertDoesNotThrow(receipt::verify);
        Files.write(second, new byte[]{0, 1, 2});
        assertThrows(WorkerFailure.class, receipt::verify);
    }

    @Test void originalClasspathCopiesArePinnedIndependentlyFromEffectiveKeys() throws Exception {
        Path original = Files.createDirectory(root.resolve("original-cp"));
        Path effective = Files.createDirectory(root.resolve("effective-cp"));
        Path first = archive(original.resolve("artifact-0.jar"), List.of(zipEntry("sample/Fake.class", new byte[]{1})));
        Path relocated = Files.copy(first, effective.resolve("artifact-0.jar"));
        assumeReadableFile(relocated); assumeDistinctFileKeys(original, effective, first, relocated);
        List<CompilerModelWire.Dependency> dependencies = List.of(dependency(relocated, "example:copy:1"));
        ClasspathReceipt originalReceipt = ClasspathReceipt.capture(observeRoot(original), dependencies, false);
        ClasspathReceipt effectiveReceipt = ClasspathReceipt.capture(observeRoot(effective), dependencies, true);
        Map<String, Path> identities = new LinkedHashMap<>();
        originalReceipt.addIdentities(identities); effectiveReceipt.addIdentities(identities);
        assertDoesNotThrow(originalReceipt::verify); assertDoesNotThrow(effectiveReceipt::verify);
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(observeRoot(original), dependencies, true));
    }

    @Test void emptyClasspathRequiresAbsentOriginalAndPresentEmptyEffectiveDirectory() throws Exception {
        Path empty = Files.createDirectory(root.resolve("empty-cp")); assumeStableFileKey(empty);
        ApplicationWire.Root observed = observeRoot(empty);
        assertDoesNotThrow(() -> ClasspathReceipt.capture(null, List.of(), false));
        assertDoesNotThrow(() -> ClasspathReceipt.capture(observed, List.of(), true));
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(null, List.of(), true));
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(observed, List.of(), false));
        Files.writeString(empty.resolve("unregistered.jar"), "not empty");
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(observed, List.of(), true));
    }

    @Test void extraClasspathMembersAndOriginalHardLinkAliasesAreRejected() throws Exception {
        Path classpath = Files.createDirectory(root.resolve("classpath"));
        Path first = archive(classpath.resolve("artifact-0.jar"), List.of(zipEntry("A.class", new byte[]{1})));
        assumeStableFileKey(classpath);
        List<CompilerModelWire.Dependency> one = List.of(dependency(first, "example:a:1"));
        Path extra = Files.copy(first, classpath.resolve("extra.jar")); assumeReadableFile(extra);
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(observeRoot(classpath), one, true));
        Files.delete(extra);
        Path second = hardLink(classpath.resolve("artifact-1.jar"), first); assumeReadableFile(second);
        CompilerModelWire.Dependency descriptor = one.get(0);
        List<CompilerModelWire.Dependency> syntheticEffectiveKeys = List.of(
                new CompilerModelWire.Dependency(descriptor.coordinate(), descriptor.digest(), descriptor.size(), "effective-one"),
                new CompilerModelWire.Dependency(descriptor.coordinate(), descriptor.digest(), descriptor.size(), "effective-two"));
        assertThrows(WorkerFailure.class, () -> ClasspathReceipt.capture(observeRoot(classpath), syntheticEffectiveKeys, false));
    }

    @Test void benignMultiReleaseDependencyContentsAreInspectedButNeverLoaded() throws Exception {
        Path archive = archive(root.resolve("multi-release.jar"), List.of(
                zipEntry("META-INF/", new byte[0]),
                textEntry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"),
                zipEntry("sample/Fake.class", new byte[]{0, 1}),
                zipEntry("META-INF/versions/9/sample/Fake.class", new byte[]{2, 3})));
        byte[] original = Files.readAllBytes(archive);
        assertDoesNotThrow(() -> inspectDependencyArchive(archive));
        assertArrayEquals(original, Files.readAllBytes(archive));
        // Bytes intentionally are not classfiles. Passing ZIP inspection is not execution or a compiler-class guarantee.
    }

    @Test void manifestClassPathSectionsAndAgentExpansionAreUnsupported() throws Exception {
        int index = 0;
        for (String manifest : List.of(
                "Manifest-Version: 1.0\r\nClass-Path: outside.jar\r\n\r\n",
                "Manifest-Version: 1.0\r\ncLaSs-PaTh: outside.jar\r\n\r\n",
                "Manifest-Version: 1.0\r\nClass-Path: out\r\n side.jar\r\n\r\n",
                "Manifest-Version: 1.0\r\n\r\nName: sample/Fake.class\r\nClass-Path: outside.jar\r\n\r\n",
                "Manifest-Version: 1.0\r\nBoot-Class-Path: outside.jar\r\n\r\n",
                "Manifest-Version: 1.0\r\nLauncher-Agent-Class: Agent\r\n\r\n",
                "Manifest-Version: 1.0\r\nPremain-Class: Agent\r\n\r\n",
                "Manifest-Version: 1.0\r\nAgent-Class: Agent\r\n\r\n")) {
            Path archive = archive(root.resolve("expansion-" + index++ + ".jar"), List.of(textEntry("META-INF/MANIFEST.MF", manifest)));
            assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
        }
    }

    @Test void manifestCaseTrailingSlashAliasesAndJarIndexCannotExpandOrigins() throws Exception {
        int index = 0;
        for (String name : List.of("meta-inf/MANIFEST.MF", "META-INF/manifest.mf", "META-INF/MANIFEST.MF/",
                "META-INF/INDEX.LIST", "meta-inf/index.list", "META-INF/INDEX.LIST/")) {
            Path archive = archive(root.resolve("alias-" + index++ + ".jar"), List.of(zipEntry(name, new byte[0])));
            assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
        }
        assertTrue(canonicalManifestSpelling("META-INF/MANIFEST.MF"));
        assertFalse(canonicalManifestSpelling("META-INF/MANIFEST.MF///"));
        assertFalse(canonicalManifestSpelling("meta-inf/manifest.mf"));
    }

    @Test void zipNamesRejectTraversalControlsAndFileDirectoryPrefixAmbiguity() throws Exception {
        int index = 0;
        for (String name : List.of("../outside.class", "/absolute.class", "a/./B.class", "a//B.class",
                "a\\B.class", "a:B.class", "a\nB.class")) {
            Path archive = archive(root.resolve("bad-name-" + index++ + ".jar"), List.of(zipEntry(name, new byte[]{1})));
            assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
        }
        for (List<FixtureEntry> entries : List.of(
                List.of(zipEntry("a", new byte[]{1}), zipEntry("a/", new byte[0])),
                List.of(zipEntry("a/", new byte[0]), zipEntry("a", new byte[]{1})),
                List.of(zipEntry("a", new byte[]{1}), zipEntry("a/B.class", new byte[]{2})),
                List.of(zipEntry("a/B.class", new byte[]{2}), zipEntry("a", new byte[]{1})))) {
            Path archive = archive(root.resolve("prefix-" + index++ + ".jar"), entries);
            assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
        }
    }

    @Test void archiveNameDepthManifestAndEntrySizeCapsAreCheckedWithoutExtraction() throws Exception {
        List<List<FixtureEntry>> cases = List.of(
                List.of(zipEntry("n".repeat(MAX_ARCHIVE_NAME_BYTES + 1), new byte[0])),
                List.of(zipEntry("d/".repeat(MAX_ARCHIVE_DEPTH) + "A.class", new byte[0])),
                List.of(zipEntry("META-INF/MANIFEST.MF", new byte[MAX_MANIFEST_BYTES + 1])));
        int index = 0;
        for (List<FixtureEntry> entries : cases) {
            Path archive = archive(root.resolve("cap-" + index++ + ".jar"), entries);
            assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
        }
        Path largeDeclared = archive(root.resolve("declared-large.jar"), List.of(zipEntry("A.class", new byte[]{1})));
        byte[] bytes = Files.readAllBytes(largeDeclared);
        putCentralInt(bytes, 24, (int) CompilerModelWire.MAX_DEPENDENCY_BYTES + 1);
        Files.write(largeDeclared, bytes);
        rejectsArchive(largeDeclared);
    }

    @Test void archiveDuplicateNamesAndCorruptSizesOrCrcAreNeverAccepted() throws Exception {
        String first = "duplicate-entry-A.class", second = "duplicate-entry-B.class";
        Path duplicate = archive(root.resolve("duplicate.jar"), List.of(zipEntry(first, new byte[]{1}), zipEntry(second, new byte[]{2})));
        byte[] bytes = Files.readAllBytes(duplicate);
        assertEquals(2, replaceEqualLength(bytes, second.getBytes(StandardCharsets.UTF_8), first.getBytes(StandardCharsets.UTF_8)));
        Files.write(duplicate, bytes); rejectsArchive(duplicate);
        Path wrongCrc = archive(root.resolve("crc.jar"), List.of(zipEntry("A.class", new byte[]{1, 2, 3})));
        bytes = Files.readAllBytes(wrongCrc); putCentralInt(bytes, 16, 0); Files.write(wrongCrc, bytes); rejectsArchive(wrongCrc);
        Path wrongSize = archive(root.resolve("size.jar"), List.of(zipEntry("A.class", new byte[]{1, 2, 3})));
        bytes = Files.readAllBytes(wrongSize); putCentralInt(bytes, 24, 2); Files.write(wrongSize, bytes); rejectsArchive(wrongSize);
    }

    @Test void archiveEntryCountIncludesEmptyEntriesAndDirectories() throws Exception {
        ArrayList<FixtureEntry> entries = new ArrayList<>();
        for (int i = 0; i <= MAX_ARCHIVE_ENTRIES; i++) entries.add(zipEntry("e" + i + "/", new byte[0]));
        Path archive = archive(root.resolve("too-many.jar"), entries);
        assertThrows(WorkerFailure.class, () -> inspectDependencyArchive(archive));
    }

    @Test void outputCannotBorrowAWholeSnapshotLeafOrNestedDirectoryKey() throws Exception {
        SnapshotFixture fixture = snapshot("full", snapshotBytes());
        SnapshotReceipt receipt = SnapshotReceipt.capture(fixture.root(), fixture.members(), true);
        Map<String, Path> identities = new LinkedHashMap<>(); receipt.addIdentities(identities);
        Path output = Files.createDirectory(root.resolve("classes")); assumeStableFileKey(output);
        hardLink(output.resolve("borrowed.class"), receipt.file("src/One.kt").path());
        assertThrows(WorkerFailure.class, () -> scanTree(output, 8, 128, new HashSet<>(identities.keySet())));
        Path nested = Path.of(fixture.root().path()).resolve("src/nested");
        assertThrows(WorkerFailure.class, () -> scanTree(nested, 8, 128, new HashSet<>(identities.keySet())));
    }

    private record FixtureMember(String path, byte[] bytes) {}
    private record SnapshotFixture(ApplicationWire.Root root, List<CompilerModelWire.Member> members) {}
    private record FixtureEntry(String name, byte[] bytes) {}

    private static List<FixtureMember> snapshotBytes() {
        return List.of(new FixtureMember("src/One.kt", "fun one() = 1\r\n".getBytes(StandardCharsets.UTF_8)),
                new FixtureMember("src/nested/Empty.kt", new byte[0]),
                new FixtureMember("build.gradle.kts", "// full archived build file\r".getBytes(StandardCharsets.UTF_8)),
                new FixtureMember("model/settings.json", "{\"frozen\":true}\n".getBytes(StandardCharsets.UTF_8)),
                new FixtureMember("resources/blob.bin", new byte[]{(byte) 0xff, 0, 13, (byte) 0xef, (byte) 0xbb, (byte) 0xbf}));
    }

    private SnapshotFixture snapshot(String name, List<FixtureMember> content) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        ArrayList<CompilerModelWire.Member> members = new ArrayList<>();
        for (FixtureMember member : content) {
            Path path = directory.resolve(member.path()); Files.createDirectories(path.getParent());
            Files.write(path, member.bytes()); assumeReadableFile(path);
            members.add(new CompilerModelWire.Member(member.path(), ApplicationWire.sha256(member.bytes()),
                    member.bytes().length, assumeStableFileKey(path)));
        }
        try (var paths = Files.walk(directory)) { assumeDistinctFileKeys(paths.toArray(Path[]::new)); }
        return new SnapshotFixture(observeRoot(directory), List.copyOf(members));
    }

    private static FixtureEntry zipEntry(String name, byte[] bytes) { return new FixtureEntry(name, bytes); }
    private static FixtureEntry textEntry(String name, String text) { return zipEntry(name, text.getBytes(StandardCharsets.UTF_8)); }
    private static Path archive(Path path, List<FixtureEntry> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            for (FixtureEntry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name())); output.write(entry.bytes()); output.closeEntry();
            }
        }
        assumeReadableFile(path);
        return path;
    }
    private static CompilerModelWire.Dependency dependency(Path path, String coordinate) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new CompilerModelWire.Dependency(coordinate, ApplicationWire.sha256(bytes), bytes.length, assumeStableFileKey(path));
    }
    private static void rejectsArchive(Path path) {
        Exception rejected = assertThrows(Exception.class, () -> inspectDependencyArchive(path));
        assertTrue(rejected instanceof WorkerFailure || rejected instanceof ZipException,
                "Only explicit worker archive rejection or JDK ZIP-format rejection is expected");
    }
    private static void putCentralInt(byte[] zip, int offset, int value) {
        for (int i = 0; i <= zip.length - 46; i++) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 1 && zip[i + 3] == 2) {
                ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN).putInt(i + offset, value); return;
            }
        }
        fail("Synthetic ZIP fixture has no central directory");
    }
    private static int replaceEqualLength(byte[] value, byte[] from, byte[] to) {
        assertEquals(from.length, to.length);
        int replacements = 0;
        for (int i = 0; i <= value.length - from.length; i++) {
            if (Arrays.equals(value, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, value, i, to.length); replacements++; i += from.length - 1;
            }
        }
        return replacements;
    }
    private static void setFixtureModifiedTime(Path path, FileTime value) throws IOException {
        try { Files.setLastModifiedTime(path, value); }
        catch (UnsupportedOperationException unsupported) {
            assumeTrue(false, "Fixture filesystem cannot set modification times"); throw unsupported;
        } catch (FileSystemException failure) {
            if (!unsupportedFeature(failure)) throw failure;
            assumeTrue(false, "Fixture filesystem cannot set modification times"); throw failure;
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
