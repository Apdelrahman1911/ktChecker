package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerModelWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static dev.ktchecker.compilerworker.live.CompilerModelApplicationMain.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Filesystem/ZIP inventory controls over plainly SYNTHETIC class-shaped bytes, never executed.
 * No successfulProducers record, host token, compiler, Gradle, JDK image or native helper is faked.
 */
class CompilerProducedOutputFilesystemTest {
    @TempDir Path temporary;
    private Path root;
    @BeforeEach void trustedRoot() throws Exception { root = temporary.toRealPath(); key(root); }

    @Test void completeTreeAndStoredArchivePreserveEveryDirectoryClassAndKotlinModule() throws Exception {
        Path output = outputTree();
        ProducedTree tree = ProducedTree.capture(observe(output));
        assertEquals(List.of("META-INF", "META-INF/main.kotlin_module", "p", "p/Api.class", "p/ApiKt.class"),
            tree.members.stream().map(CompilerModelWire.ProducedMember::path).toList());
        Path jar = jar("complete.jar", output, tree.members, Map.of(), false);
        assertDoesNotThrow(tree::verify);
        assertDoesNotThrow(() -> inspectProducedArchive(jar, tree.members));
        assertEquals(CompilerModelWire.producedOutputDigest(tree.members), tree.digest);
    }

    @Test void moduleOmissionClassOmissionDirectoryOmissionAndExtraClassAllFailFullInventoryEquality() throws Exception {
        Path output = outputTree(); ProducedTree tree = ProducedTree.capture(observe(output));
        int index = 0;
        for (String omitted : List.of("META-INF/main.kotlin_module", "p/Api.class", "META-INF")) {
            List<CompilerModelWire.ProducedMember> partial = tree.members.stream().filter(m -> !m.path().equals(omitted)).toList();
            Path jar = jar("omitted-" + (++index) + ".jar", output, partial, Map.of(), false);
            assertThrows(WorkerFailure.class, () -> inspectProducedArchive(jar, tree.members));
        }
        Map<String, byte[]> extra = Map.of("p/Extra.class", bytes("SYNTHETIC extra not in sealed tree"));
        Path withExtra = jar("extra.jar", output, tree.members, extra, false);
        assertThrows(WorkerFailure.class, () -> inspectProducedArchive(withExtra, tree.members));
    }

    @Test void byteChangedModuleAndClassCannotBorrowAnOtherwiseEqualInventory() throws Exception {
        Path output = outputTree(); ProducedTree tree = ProducedTree.capture(observe(output));
        int index = 0;
        for (String changed : List.of("META-INF/main.kotlin_module", "p/Api.class")) {
            Path jar = jar("changed-" + (++index) + ".jar", output, tree.members,
                Map.of(changed, bytes("SYNTHETIC different bytes")), false);
            assertThrows(WorkerFailure.class, () -> inspectProducedArchive(jar, tree.members));
        }
        Path compressed = jar("compressed.jar", output, tree.members, Map.of(), true);
        assertThrows(WorkerFailure.class, () -> inspectProducedArchive(compressed, tree.members));
    }

    @Test void acceptedTreeReceiptRefusesNewEntryAndByteEqualFileReplacement() throws Exception {
        Path output = outputTree(); ProducedTree tree = ProducedTree.capture(observe(output));
        Files.writeString(output.resolve("p/Extra.class"), "SYNTHETIC extra");
        assertThrows(WorkerFailure.class, tree::verify);
        Path other = outputTree("replacement-tree"); ProducedTree retained = ProducedTree.capture(observe(other));
        Path current = other.resolve("p/Api.class"); Path replacement = root.resolve("replacement.class");
        Files.copy(current, replacement);
        assumeTrue(!key(current).equals(key(replacement)), "Distinct replacement file identity required by this control");
        Files.move(replacement, current, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(WorkerFailure.class, retained::verify);
    }

    @Test void arbitraryResourcesSourcesEmptyTreeAndUnproducedMetadataDoNotBecomeCompilerOutputs() throws Exception {
        Path output = outputTree();
        Files.writeString(output.resolve("p/Hidden.kt"), "SYNTHETIC source must not leak into archive");
        assertThrows(ApplicationWire.ProtocolException.class, () -> ProducedTree.capture(observe(output)));
        Path resources = outputTree("resource-tree");
        Files.writeString(resources.resolve("config.json"), "{}");
        assertThrows(ApplicationWire.ProtocolException.class, () -> ProducedTree.capture(observe(resources)));
        Path empty = Files.createDirectory(root.resolve("empty-tree"));
        assertThrows(ApplicationWire.ProtocolException.class, () -> ProducedTree.capture(observe(empty)));
        Path original = outputTree("original-tree"); ProducedTree tree = ProducedTree.capture(observe(original));
        Path sourceArchive = jar("source-entry.jar", original, tree.members, Map.of("Hidden.kt", bytes("source")), false);
        assertThrows(ApplicationWire.ProtocolException.class, () -> inspectProducedArchive(sourceArchive, tree.members));
    }

    private Path outputTree() throws Exception { return outputTree("output"); }
    private Path outputTree(String name) throws Exception {
        Path output = Files.createDirectory(root.resolve(name));
        Files.createDirectory(output.resolve("META-INF")); Files.createDirectory(output.resolve("p"));
        Files.writeString(output.resolve("META-INF/main.kotlin_module"), "SYNTHETIC module metadata; not loadable");
        Files.writeString(output.resolve("p/Api.class"), "SYNTHETIC class bytes; not loadable");
        Files.writeString(output.resolve("p/ApiKt.class"), "SYNTHETIC facade bytes; not loadable");
        return output;
    }
    private Path jar(String name, Path output, List<CompilerModelWire.ProducedMember> members,
                     Map<String, byte[]> replacementsAndExtras, boolean compressed) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (CompilerModelWire.ProducedMember member : members) entries.put(member.path() + (member.directory() ? "/" : ""),
            member.directory() ? new byte[0] : Files.readAllBytes(output.resolve(member.path())));
        entries.putAll(replacementsAndExtras);
        Path path = root.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> value : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(value.getKey()); byte[] bytes = value.getValue();
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                if (!compressed) {
                    entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
                    CRC32 crc = new CRC32(); crc.update(bytes); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
            }
        }
        return path;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String key(Path path) throws Exception {
        Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        assumeTrue(key != null && !key.toString().isBlank(), "Local file-key receipts unavailable; no portability claim");
        return key.toString();
    }
    private static ApplicationWire.Root observe(Path path) throws Exception { return new ApplicationWire.Root(path.toString(), key(path)); }
}
