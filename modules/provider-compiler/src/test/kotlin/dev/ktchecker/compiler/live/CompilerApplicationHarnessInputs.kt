package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.KotlinJvmRuntimeImage
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import dev.ktchecker.providers.runtime.OwnedApplicationSessions
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** Paths are explicit canonical selections, never silently resolved from the CWD or PATH. */
internal fun harnessPath(value: String): Path {
    harnessCheck(value.length in 1..4096 && value.none { it.code < 32 || it == '\u007f' }, HarnessCode.PATH)
    val path = try { Path.of(value) } catch (_: Exception) { throw HarnessProblem(HarnessCode.PATH) }
    harnessCheck(path.isAbsolute && path.normalize() == path && path.toString() == value &&
        value.indexOf(File.pathSeparatorChar) < 0, HarnessCode.PATH)
    return path
}

internal fun privateHarnessDirectory(path: Path) {
    harnessCheck(path.fileSystem.supportedFileAttributeViews().containsAll(setOf("posix", "unix")), HarnessCode.UNSUPPORTED_FILESYSTEM)
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
}

internal data class HarnessNode(val path: Path, val key: String, val directory: Boolean) {
    fun verify(): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        harnessCheck(!attributes.isSymbolicLink && !attributes.isOther &&
            (if (directory) attributes.isDirectory else attributes.isRegularFile) &&
            attributes.fileKey()?.toString() == key && path.toRealPath() == path, HarnessCode.ROOT_DRIFT)
        if (!directory) harnessCheck((Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L,
            HarnessCode.ALIAS)
        return attributes
    }
    companion object {
        fun capture(path: Path, directory: Boolean): HarnessNode {
            harnessCheck(path.fileSystem.supportedFileAttributeViews().containsAll(setOf("posix", "unix")), HarnessCode.UNSUPPORTED_FILESYSTEM)
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val key = attributes.fileKey()?.toString()
            harnessCheck(key != null && key.length in 1..256 && key.none { it.code < 32 }, HarnessCode.UNSUPPORTED_FILESYSTEM)
            return HarnessNode(path, checkNotNull(key), directory).also { it.verify() }
        }
    }
}

internal class HarnessRootPin private constructor(val path: Path, private val nodes: List<HarnessNode>,
    private val permissions: Set<PosixFilePermission>) {
    val root: Wire.Root = Wire.Root(path.toString(), nodes.last().key)
    fun verify() {
        nodes.forEach { it.verify() }
        harnessCheck(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions, HarnessCode.ROOT_DRIFT)
    }
    fun json(): JsonObject = buildJsonObject {
        put("root", rootJson(root)); put("posix_permissions", PosixFilePermissions.toString(permissions))
        put("ancestors", JsonArray(nodes.map { node -> buildJsonObject {
            put("path", node.path.toString()); put("file_key", node.key)
        } }))
    }
    companion object {
        fun capture(path: Path, expectedKey: String? = null, expectedPermissions: String? = null): HarnessRootPin {
            harnessPath(path.toString())
            val paths = generateSequence(path) { it.parent }.toList().asReversed()
            harnessCheck(paths.size <= 128, HarnessCode.LIMIT)
            val nodes = harnessList(paths.map { HarnessNode.capture(it, true) })
            harnessCheck(expectedKey == null || nodes.last().key == expectedKey, HarnessCode.ROOT_DRIFT)
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
            harnessCheck(expectedPermissions == null || permissions == PosixFilePermissions.fromString(expectedPermissions), HarnessCode.ROOT_DRIFT)
            return HarnessRootPin(path, nodes, permissions).also { it.verify() }
        }
    }
}

private fun executableBits(path: Path): Int {
    val bits = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
    return (if (PosixFilePermission.OWNER_EXECUTE in bits) 4 else 0) +
        (if (PosixFilePermission.GROUP_EXECUTE in bits) 2 else 0) +
        (if (PosixFilePermission.OTHERS_EXECUTE in bits) 1 else 0)
}

/** File keys, ancestor identity, exact bytes and pre/post metadata; no link/hardlink fallback. */
internal class HarnessFilePin private constructor(
    val path: Path,
    val size: Long,
    val digest: String,
    private val parent: HarnessRootPin,
    private val node: HarnessNode,
    private val modified: FileTime,
    private val mode: Int,
    private val permissions: Set<PosixFilePermission>,
) {
    val key: String get() = node.key
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); parent.verify()
        val before = node.verify()
        harnessCheck(before.size() == size && before.lastModifiedTime() == modified && executableBits(path) == mode &&
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions, HarnessCode.FILE_DRIFT)
        harnessCheck(hash(path, size, checkpoint) == digest, HarnessCode.FILE_DRIFT)
        val after = node.verify()
        harnessCheck(after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime() &&
            executableBits(path) == mode && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions, HarnessCode.FILE_DRIFT)
        parent.verify(); checkpoint()
    }
    fun readBounded(maximum: Int, checkpoint: () -> Unit): ByteArray {
        harnessCheck(size <= maximum && size <= Int.MAX_VALUE - 1L, HarnessCode.LIMIT)
        verify(checkpoint)
        val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        harnessCheck(bytes.size.toLong() == size && Digests.sha256(bytes) == digest, HarnessCode.FILE_DRIFT)
        verify(checkpoint)
        return bytes
    }
    fun json(): JsonObject = buildJsonObject {
        put("path", path.toString()); put("file_key", key); put("size", size); put("digest", digest)
        put("modified", modified.toString()); put("executable_bits", mode)
        put("posix_permissions", PosixFilePermissions.toString(permissions)); put("parent", parent.json())
    }
    companion object {
        fun capture(path: Path, size: Long, digest: String, checkpoint: () -> Unit): HarnessFilePin {
            harnessCheck(size in 0..536_870_912L && Digests.valid(digest), HarnessCode.LIMIT)
            checkpoint(); harnessPath(path.toString())
            val parent = HarnessRootPin.capture(checkNotNull(path.parent))
            val node = HarnessNode.capture(path, false)
            val before = node.verify()
            harnessCheck(before.size() == size, HarnessCode.FILE_DRIFT)
            return HarnessFilePin(path, size, digest, parent, node, before.lastModifiedTime(), executableBits(path),
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)).also { it.verify(checkpoint) }
        }
        fun observe(path: Path, maximum: Long, checkpoint: () -> Unit): HarnessFilePin {
            checkpoint()
            val parent = HarnessRootPin.capture(checkNotNull(path.parent))
            val node = HarnessNode.capture(path, false)
            val before = node.verify()
            harnessCheck(before.size() in 0..maximum, HarnessCode.LIMIT)
            val mode = executableBits(path)
            val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
            val digest = hash(path, before.size(), checkpoint)
            val after = node.verify()
            harnessCheck(before.lastModifiedTime() == after.lastModifiedTime() && before.size() == after.size() &&
                mode == executableBits(path) && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == permissions, HarnessCode.FILE_DRIFT)
            parent.verify(); checkpoint()
            return HarnessFilePin(path, before.size(), digest, parent, node, before.lastModifiedTime(), mode, permissions)
        }
        private fun hash(path: Path, size: Long, checkpoint: () -> Unit): String {
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val buffer = ByteArray(65_536)
            Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
                while (true) {
                    checkpoint()
                    val read = input.read(buffer)
                    if (read < 0) break
                    harnessCheck(read > 0 && read.toLong() <= size - total, HarnessCode.FILE_DRIFT)
                    md.update(buffer, 0, read); total += read
                }
            }
            harnessCheck(total == size, HarnessCode.FILE_DRIFT); checkpoint()
            return "sha256:" + HexFormat.of().formatHex(md.digest())
        }
    }
}

internal fun harnessNames(root: Path, maximum: Int, checkpoint: () -> Unit): Set<String> {
    checkpoint()
    val names = linkedSetOf<String>()
    Files.newDirectoryStream(root).use { stream ->
        for (path in stream) {
            checkpoint()
            harnessCheck(names.size < maximum && names.add(path.fileName.toString()), HarnessCode.MEMBER_DRIFT)
        }
    }
    checkpoint()
    return Collections.unmodifiableSet(names)
}

internal enum class HarnessTreeUse { HOST, OUTPUT, TEMP }
internal data class HarnessTreeEntry(val relative: String, val directory: Boolean, val key: String,
    val size: Long, val digest: String?, val modified: FileTime, val executableBits: Int) {
    fun contentJson(): JsonObject = buildJsonObject {
        put("path", relative); put("kind", if (directory) "directory" else "file"); put("size", size)
        put("digest", digest?.let(::JsonPrimitive) ?: JsonNull); put("executable_bits", executableBits)
    }
    fun custodyJson(): JsonObject = JsonObject(contentJson() + mapOf("file_key" to JsonPrimitive(key), "modified" to JsonPrimitive(modified.toString())))
}

/** Complete bounded tree, including empty directories. Fresh output is not assumed from a hash. */
internal class HarnessTreePin private constructor(val root: HarnessRootPin, val entries: List<HarnessTreeEntry>,
    private val maximumEntries: Int, private val maximumBytes: Long, private val use: HarnessTreeUse) {
    val size: Long = entries.sumOf { it.size }
    val digest: String = Digests.sha256(Digests.canonical(contentJson()))
    fun contentJson(): JsonObject = buildJsonObject {
        put("schema", "ktchecker/compiler-application-harness-tree/1")
        put("entries", JsonArray(entries.map { it.contentJson() }))
    }
    fun json(): JsonObject = buildJsonObject {
        put("root", root.json()); put("content_digest", digest); put("size", size)
        put("entries", JsonArray(entries.map { it.custodyJson() }))
    }
    fun verify(checkpoint: () -> Unit) {
        root.verify()
        val current = capture(root.path, maximumEntries, maximumBytes, use, checkpoint)
        harnessCheck(current.root.root == root.root && current.entries == entries, HarnessCode.FILE_DRIFT)
        root.verify(); checkpoint()
    }
    companion object {
        fun capture(path: Path, maximumEntries: Int, maximumBytes: Long, use: HarnessTreeUse, checkpoint: () -> Unit): HarnessTreePin {
            checkpoint()
            val root = HarnessRootPin.capture(path)
            val values = ArrayList<HarnessTreeEntry>()
            val seen = HashSet<String>()
            var total = 0L
            var names = 0L
            fun add(nodePath: Path, directory: Boolean) {
                checkpoint()
                val relative = if (nodePath == path) "" else path.relativize(nodePath).toString().replace(File.separatorChar, '/')
                harnessCheck(values.size < maximumEntries && relative.length <= 4096 &&
                    (nodePath == path || path.relativize(nodePath).nameCount <= 64) &&
                    relative.none { it.code < 32 || it == '\\' } && nodePath.normalize() == nodePath && nodePath.startsWith(path), HarnessCode.LIMIT)
                names += relative.toByteArray(Charsets.UTF_8).size
                harnessCheck(names <= 4_194_304, HarnessCode.LIMIT)
                val node = HarnessNode.capture(nodePath, directory)
                harnessCheck(seen.add(node.key), HarnessCode.ALIAS)
                val attributes = node.verify()
                val beforeMode = executableBits(nodePath)
                val file = if (directory) null else HarnessFilePin.observe(nodePath, minOf(maximumBytes, 268_435_456L), checkpoint)
                total += file?.size ?: 0
                harnessCheck(total <= maximumBytes, HarnessCode.LIMIT)
                if (!directory && use == HarnessTreeUse.OUTPUT) harnessCheck(relative.endsWith(".class") || relative.endsWith(".kotlin_module"), HarnessCode.MEMBER_DRIFT)
                if (use == HarnessTreeUse.HOST) harnessCheck(!forbiddenHostEntry(relative), HarnessCode.HOST_CLOSURE)
                val after = node.verify()
                harnessCheck(attributes.lastModifiedTime() == after.lastModifiedTime() && attributes.size() == after.size() &&
                    executableBits(nodePath) == beforeMode, HarnessCode.FILE_DRIFT)
                values += HarnessTreeEntry(relative, directory, node.key, file?.size ?: 0, file?.digest, attributes.lastModifiedTime(), beforeMode)
            }
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    add(dir, true); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    add(file, false); return FileVisitResult.CONTINUE
                }
            })
            for (value in values) {
                checkpoint()
                val nodePath = if (value.relative.isEmpty()) path else path.resolve(value.relative)
                val actual = HarnessNode(nodePath, value.key, value.directory).verify()
                harnessCheck(actual.lastModifiedTime() == value.modified && executableBits(nodePath) == value.executableBits &&
                    (value.directory || actual.size() == value.size), HarnessCode.FILE_DRIFT)
            }
            root.verify(); checkpoint()
            return HarnessTreePin(root, harnessList(values.sortedBy { it.relative }), maximumEntries, maximumBytes, use)
        }
    }
}

internal data class HarnessArtifact(val coordinate: String, val path: Path, val digest: String, val size: Long) {
    fun identity(): ArtifactIdentity = ArtifactIdentity(coordinate, digest, path.toString())
}
internal data class HarnessHostEntry(val path: Path, val kind: String, val digest: String, val size: Long, val entries: Int)

/** Strict owner-pinned data. Its bytes alone still confer no permission to run the harness. */
internal class HarnessManifest private constructor(
    val document: JsonObject,
    val namespace: CompilerApplicationNamespace,
    val retainedParent: Path,
    val retainedParentKey: String,
    val bridge: HarnessArtifact,
    val javaExecutable: Path,
    val executableDigest: String,
    val runtimeDigest: String,
    val toolchainDigest: String,
    val compiler: List<HarnessArtifact>,
    val dependencies: List<Int>,
    val hostClasspath: List<HarnessHostEntry>,
    val hostVmArguments: List<String>,
    val anchors: Map<String, Int>,
    val registryIndex: Int,
) {
    val implementationDigest: String = Wire.digest("ktchecker/compiler-application-harness-host-closure/1",
        Digests.canonical(buildJsonObject { put("host", document.getValue("host")); put("jdk", document.getValue("jdk")) }).toByteArray(Charsets.UTF_8))
    companion object {
        const val MAX_BYTES = 65_536
        fun parse(bytes: ByteArray, expectedDigest: String): HarnessManifest {
            harnessCheck(bytes.size in 1..MAX_BYTES && Digests.valid(expectedDigest) && Digests.sha256(bytes) == expectedDigest, HarnessCode.EXPECTED_PIN)
            val text = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { throw HarnessProblem(HarnessCode.MANIFEST) }
            boundJsonLexically(text)
            val root = try { ContractJson.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            harnessCheck(root != null && Digests.canonical(root) == text, HarnessCode.MANIFEST)
            val data = checkNotNull(root)
            data.fields("schema", "namespace", "retained_parent", "bridge", "jdk", "compiler", "dependencies", "host")
            harnessCheck(data.string("schema") == "ktchecker/compiler-application-harness-inputs/1", HarnessCode.MANIFEST)
            val ns = data.obj("namespace").also { it.fields("owner_id", "workspace_id", "worktree_id") }
            val namespace = CompilerApplicationNamespace(ns.string("owner_id"), ns.string("workspace_id"), ns.string("worktree_id"))
            val parent = data.obj("retained_parent").also { it.fields("path", "file_key") }
            val parentPath = harnessPath(parent.string("path"))
            val parentKey = parent.string("file_key").also { harnessCheck(it.length in 1..256, HarnessCode.MANIFEST) }
            fun artifact(element: JsonElement, maximum: Long): HarnessArtifact {
                val value = (element as? JsonObject) ?: throw HarnessProblem(HarnessCode.MANIFEST)
                value.fields("coordinate", "path", "digest", "size")
                val path = harnessPath(value.string("path"))
                harnessCheck(path.fileName.toString().matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")), HarnessCode.MANIFEST)
                val digest = value.digest("digest")
                val size = value.number("size").also { harnessCheck(it in 1..maximum, HarnessCode.LIMIT) }
                val coordinate = value.string("coordinate").also { harnessCheck(it.length in 1..128, HarnessCode.MANIFEST) }
                return HarnessArtifact(coordinate, path, digest, size)
            }
            val bridge = artifact(data.getValue("bridge"), CompilerWire.MAX_BRIDGE_BYTES)
            val jdk = data.obj("jdk").also { it.fields("java_executable", "executable_digest", "runtime_digest", "toolchain_digest") }
            val java = harnessPath(jdk.string("java_executable"))
            harnessCheck(java.fileName.toString() == "java" && java.parent?.fileName?.toString() == "bin", HarnessCode.TOOLCHAIN)
            val compiler = data.array("compiler", CompilerWire.MAX_ARTIFACTS).map { artifact(it, CompilerWire.MAX_ARTIFACT_BYTES) }
            harnessCheck(compiler.isNotEmpty() && compiler.sumOf { it.size } <= CompilerWire.MAX_ARTIFACT_TOTAL_BYTES &&
                compiler.map { it.path }.distinct().size == compiler.size && compiler.map { it.coordinate }.distinct().size == compiler.size &&
                compiler.map { it.path.fileName.toString() }.distinct().size == compiler.size && compiler.none { it.path == bridge.path }, HarnessCode.MANIFEST)
            val dependencies = data.array("dependencies", 3).map { strictLong(it).also { n ->
                harnessCheck(n >= 0 && n < compiler.size, HarnessCode.MANIFEST)
            }.toInt() }
            harnessCheck(dependencies.isNotEmpty() && dependencies == dependencies.distinct().sorted(), HarnessCode.MANIFEST)
            harnessCheck(compiler.count { it.path.fileName.toString() == "kotlin-compiler-embeddable-2.4.21.jar" && it.digest == CompilerWire.COMPILER_SHA256 } == 1 &&
                dependencies.any { compiler[it].path.fileName.toString() == "kotlin-stdlib-2.4.21.jar" } && dependencies.all {
                    compiler[it].path.fileName.toString().let { name -> name == "kotlin-stdlib-2.4.21.jar" || name.matches(Regex("annotations-[0-9.]+\\.jar")) }
                }, HarnessCode.TOOLCHAIN)
            val host = data.obj("host").also { it.fields("classpath", "vm_arguments", "anchors", "registry_index") }
            val hostClasspath = host.array("classpath", 128).map { raw ->
                val value = (raw as? JsonObject) ?: throw HarnessProblem(HarnessCode.MANIFEST)
                value.fields("path", "kind", "digest", "size", "entries")
                val kind = value.string("kind").also { harnessCheck(it == "jar" || it == "directory", HarnessCode.MANIFEST) }
                val size = value.number("size").also { harnessCheck(it in 1..536_870_912L, HarnessCode.LIMIT) }
                val count = value.number("entries").also { harnessCheck(it in 1L..20_000L && (kind != "jar" || it == 1L), HarnessCode.LIMIT) }.toInt()
                HarnessHostEntry(harnessPath(value.string("path")), kind, value.digest("digest"), size, count)
            }
            harnessCheck(hostClasspath.isNotEmpty() && hostClasspath.sumOf { it.size } <= 1_073_741_824 &&
                hostClasspath.sumOf { it.entries } <= 40_000 && hostClasspath.indices.all { i ->
                    hostClasspath.indices.all { j -> i == j || !hostClasspath[i].path.startsWith(hostClasspath[j].path) }
                }, HarnessCode.HOST_CLOSURE)
            val vm = host.array("vm_arguments", 32).map { strictString(it).also { arg -> harnessCheck(allowedHostVmArgument(arg), HarnessCode.HOST_CLOSURE) } }
            val anchorsObject = host.obj("anchors")
            harnessCheck(anchorsObject.keys == hostAnchors().keys, HarnessCode.HOST_CLOSURE)
            val anchors = anchorsObject.mapValues { (_, value) -> strictLong(value).also { harnessCheck(it >= 0 && it < hostClasspath.size, HarnessCode.HOST_CLOSURE) }.toInt() }
            val registryIndex = host.number("registry_index").also { harnessCheck(it >= 0 && it < hostClasspath.size, HarnessCode.HOST_CLOSURE) }.toInt()
            // Inputs and immutable host entries cannot overlap future retained writes.
            val immutablePaths = listOf(java.parent.parent, bridge.path) + compiler.map { it.path } + hostClasspath.map { it.path }
            harnessCheck(immutablePaths.none { it.startsWith(parentPath) || parentPath.startsWith(it) }, HarnessCode.ALIAS)
            return HarnessManifest(data, namespace, parentPath, parentKey, bridge, java, jdk.digest("executable_digest"),
                jdk.digest("runtime_digest"), jdk.digest("toolchain_digest"), harnessList(compiler), harnessList(dependencies),
                harnessList(hostClasspath), harnessList(vm), Collections.unmodifiableMap(anchors), registryIndex)
        }
    }
}

private fun JsonObject.fields(vararg names: String) = harnessCheck(keys == names.toSet(), HarnessCode.MANIFEST)
private fun JsonObject.obj(name: String): JsonObject = get(name) as? JsonObject ?: throw HarnessProblem(HarnessCode.MANIFEST)
private fun JsonObject.string(name: String): String = strictString(get(name) ?: throw HarnessProblem(HarnessCode.MANIFEST))
private fun JsonObject.number(name: String): Long = strictLong(get(name) ?: throw HarnessProblem(HarnessCode.MANIFEST))
private fun JsonObject.digest(name: String): String = string(name).also { harnessCheck(Digests.valid(it), HarnessCode.MANIFEST) }
private fun JsonObject.array(name: String, maximum: Int): JsonArray = (get(name) as? JsonArray)?.also {
    harnessCheck(it.size <= maximum, HarnessCode.LIMIT)
} ?: throw HarnessProblem(HarnessCode.MANIFEST)
private fun strictString(value: JsonElement): String {
    val primitive = value as? JsonPrimitive ?: throw HarnessProblem(HarnessCode.MANIFEST)
    harnessCheck(primitive.isString && primitive.content.length in 1..4096 && primitive.content.none { it.code < 32 }, HarnessCode.MANIFEST)
    return primitive.content
}
private fun strictLong(value: JsonElement): Long {
    val primitive = value as? JsonPrimitive ?: throw HarnessProblem(HarnessCode.MANIFEST)
    harnessCheck(!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]{0,18}")), HarnessCode.MANIFEST)
    return primitive.longOrNull ?: throw HarnessProblem(HarnessCode.MANIFEST)
}

/** Bounded BEFORE recursive JSON parsing/canonicalization; canonical comparison rejects duplicate keys. */
internal fun boundJsonLexically(text: String) {
    var depth = 0
    var string = false
    var escaped = false
    var length = 0
    for (c in text) {
        if (string) {
            length++
            harnessCheck(length <= 24_576, HarnessCode.LIMIT) // Allows a bounded 4096-code-unit escaped string.
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') string = false
        } else when (c) {
            '"' -> { string = true; length = 0 }
            '{', '[' -> { depth++; harnessCheck(depth <= 16, HarnessCode.LIMIT) }
            '}', ']' -> { depth--; harnessCheck(depth >= 0, HarnessCode.MANIFEST) }
        }
    }
    harnessCheck(!string && !escaped && depth == 0, HarnessCode.MANIFEST)
}

internal fun allowedHostVmArgument(value: String): Boolean = value in setOf("-ea", "-Dfile.encoding=UTF-8", "-Duser.language=en",
    "-Duser.country=US", "-Djava.awt.headless=true", "-Dkotlin.environment.keepalive=false", "-Dkotlin.colors.enabled=false") ||
    value.matches(Regex("-Xm[sx][1-9][0-9]{0,4}[mMgG]"))

private fun forbiddenHostEntry(name: String): Boolean {
    val logical = if (name.startsWith("META-INF/versions/")) name.split('/', limit = 4).getOrElse(3) { name } else name
    return logical.startsWith("org/jetbrains/kotlin/") ||
        logical == "dev/ktchecker/compilerworker/live/CompilerApplicationMain.class" || name == "META-INF/INDEX.LIST"
}

/** Static anchors only: no project-selected class loading, reflection method calls or compiler import. */
internal fun hostAnchors(): Map<String, Class<*>> = linkedMapOf(
    "harness" to CompilerApplicationIntegrationHarness::class.java,
    "harness_inputs" to HarnessManifest::class.java,
    "harness_evidence" to CompilerApplicationHarnessReport::class.java,
    "compiler_application" to KotlinJvmCompilerApplication::class.java,
    "compiler_authority" to CompilerAttemptPermit::class.java,
    "compiler_provider" to KotlinJvmLiveCompilerProvider::class.java,
    "compiler_toolchain" to KotlinJvmToolchain::class.java,
    "compiler_runtime_image" to KotlinJvmRuntimeImage::class.java,
    "compiler_registry" to CompilerRuleRegistry::class.java,
    "application_runtime" to OwnedApplicationSessions::class.java,
    "application_deadline" to OwnedApplicationDeadline::class.java,
    "application_wire" to Wire::class.java,
    "compiler_wire" to CompilerWire::class.java,
    "contracts" to AnalysisInput::class.java,
    "contract_digests" to Digests::class.java,
    "kotlin" to Unit::class.java,
    "serialization_json" to Json::class.java,
    "serialization_core" to kotlinx.serialization.Serializable::class.java,
)

private val bridgeFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire", "dev/ktchecker/workerprotocol/CompilerJobWire",
    "dev/ktchecker/compilerworker/live/CompilerApplicationMain", "dev/ktchecker/compilerworker/live/PinnedKotlinCompilerAdapter")
internal fun allowedHarnessBridgeEntry(name: String, directory: Boolean): Boolean {
    if (directory) return name == "META-INF/" || bridgeFamilies.any { base ->
        name.endsWith('/') && name != "/" && base.startsWith(name)
    }
    return name == "META-INF/MANIFEST.MF" || bridgeFamilies.any { base ->
        name == "$base.class" || (name.startsWith("$base\$") && name.endsWith(".class") &&
            name.substring(base.length + 1, name.length - 6).matches(Regex("[A-Za-z0-9_$]+")))
    }
}

/** JDK manifest lookup recognizes case aliases; only the exact canonical file may be inspected. */
internal fun allowedHarnessManifestEntrySpelling(name: String): Boolean =
    !name.trimEnd('/').equals("META-INF/MANIFEST.MF", ignoreCase = true) || name == "META-INF/MANIFEST.MF"

/** Read-only archive inspection; never extraction, class loading or execution. */
internal fun inspectHarnessArchive(pin: HarnessFilePin, bridge: Boolean, checkpoint: () -> Unit): JsonObject {
    pin.verify(checkpoint)
    val names = linkedSetOf<String>()
    val entries = ArrayList<JsonElement>()
    var total = 0L
    try {
        ZipFile(pin.path.toFile()).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                checkpoint()
                val entry = iterator.nextElement()
                val name = entry.name
                harnessCheck(names.size < (if (bridge) 256 else 20_000) && name.length in 1..4096 && names.add(name) &&
                    !name.startsWith('/') && '\\' !in name && name.split('/').none { it == "." || it == ".." } &&
                    name.none { it.code < 32 } && entry.size in 0..(if (bridge) 1_048_576L else 67_108_864L), HarnessCode.ARCHIVE)
                total += entry.size
                harnessCheck(total <= (if (bridge) 4_194_304L else 536_870_912L), HarnessCode.LIMIT)
                harnessCheck(allowedHarnessManifestEntrySpelling(name), HarnessCode.ARCHIVE)
                harnessCheck(if (bridge) allowedHarnessBridgeEntry(name, entry.isDirectory) else !forbiddenHostEntry(name), HarnessCode.ARCHIVE)
                var content: ByteArray? = null
                if ((bridge && !entry.isDirectory) || name == "META-INF/MANIFEST.MF") {
                    harnessCheck(entry.size <= if (name == "META-INF/MANIFEST.MF") 65_536 else 1_048_576, HarnessCode.ARCHIVE)
                    content = zip.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }
                    harnessCheck(content.size.toLong() == entry.size, HarnessCode.ARCHIVE)
                }
                if (name == "META-INF/MANIFEST.MF") {
                    val manifest = Manifest(checkNotNull(content).inputStream())
                    val attrs = manifest.mainAttributes
                    harnessCheck(attrs.getValue(Attributes.Name.CLASS_PATH) == null && manifest.entries.values.all {
                        it.getValue(Attributes.Name.CLASS_PATH) == null
                    } && listOf("Premain-Class", "Agent-Class", "Launcher-Agent-Class").all { attrs.getValue(it) == null }, HarnessCode.ARCHIVE)
                    if (bridge) harnessCheck(manifest.entries.isEmpty() && attrs.keys.map { it.toString() }.toSet() ==
                        setOf("Manifest-Version", "Main-Class") && attrs.getValue("Manifest-Version") == "1.0" &&
                        attrs.getValue("Main-Class") == CompilerWire.MAIN_CLASS, HarnessCode.ARCHIVE)
                }
                if (bridge && name.endsWith(".class")) {
                    val bytes = checkNotNull(content)
                    harnessCheck(bytes.size >= 8 && ByteBuffer.wrap(bytes).int == 0xcafebabe.toInt() &&
                        ((bytes[6].toInt() and 255) * 256 + (bytes[7].toInt() and 255)) == 65, HarnessCode.ARCHIVE)
                }
                if (bridge) entries += buildJsonObject {
                    put("path", name); put("directory", entry.isDirectory); put("size", entry.size)
                    put("digest", content?.let { JsonPrimitive(Digests.sha256(it)) } ?: JsonNull)
                }
            }
        }
    } catch (failure: Throwable) {
        if (failure is HarnessProblem) throw failure
        throw HarnessProblem(HarnessCode.ARCHIVE)
    }
    if (bridge) harnessCheck("META-INF/MANIFEST.MF" in names && bridgeFamilies.all { "$it.class" in names }, HarnessCode.ARCHIVE)
    pin.verify(checkpoint)
    return buildJsonObject {
        put("artifact", pin.json()); put("entry_count", names.size); put("uncompressed_bytes", total)
        if (bridge) put("entries", JsonArray(entries))
    }
}

/** Independently expected pins are supplied by the owner, not learned and approved by this run. */
internal class HarnessClosure private constructor(
    val manifest: HarnessManifest,
    val manifestPin: HarnessFilePin,
    val parent: HarnessRootPin,
    val installation: KotlinJvmToolchain,
    val jdkRoot: HarnessRootPin,
    val executable: HarnessFilePin,
    val bridge: HarnessFilePin,
    val compiler: List<HarnessFilePin>,
    private val hostFiles: List<HarnessFilePin>,
    private val hostTrees: List<HarnessTreePin>,
    val bridgeInventory: JsonObject,
    val hostInventory: JsonArray,
) {
    fun verify(checkpoint: () -> Unit) {
        checkpoint(); manifestPin.verify(checkpoint); parent.verify(); jdkRoot.verify(); executable.verify(checkpoint)
        installation.verify(checkpoint); bridge.verify(checkpoint); compiler.forEach { it.verify(checkpoint) }
        hostFiles.forEach { it.verify(checkpoint) }; hostTrees.forEach { it.verify(checkpoint) }
        verifyHostProcess(manifest, checkpoint); checkpoint()
    }
    fun installationJson(checkpoint: () -> Unit): JsonObject {
        checkpoint()
        val view = installation.installationView()
        return buildJsonObject {
            put("provenance", "owner_verified_disk_closure_not_loaded_code_OS_JNI_or_same_UID_attestation")
            put("toolchain_digest", installation.digest); put("jdk_root", jdkRoot.json()); put("java_executable", executable.json())
            put("runtime", buildJsonObject {
                put("root", view.runtime.root.toString()); put("digest", view.runtime.digest)
                put("java_version", view.runtime.javaVersion); put("os_name", view.runtime.osName); put("architecture", view.runtime.architecture)
                put("entries", JsonArray(view.runtime.entries.map { entry ->
                    checkpoint()
                    buildJsonObject {
                        put("path", entry.relativePath); put("kind", entry.kind.name); put("size", entry.size)
                        put("digest", entry.digest?.let(::JsonPrimitive) ?: JsonNull); put("executable", entry.executable)
                        put("executable_bits", entry.executableBits?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }))
            })
            put("compiler", JsonArray(compiler.map { it.json() })); put("bridge", bridgeInventory)
            put("host", hostInventory); put("host_implementation_digest", manifest.implementationDigest)
        }.also { checkpoint() }
    }
    companion object {
        fun capture(ingress: CompilerApplicationHarnessIngress, checkpoint: () -> Unit): HarnessClosure {
            checkpoint()
            val manifestPath = harnessPath(ingress.manifestPath)
            val manifestPin = HarnessFilePin.observe(manifestPath, HarnessManifest.MAX_BYTES.toLong(), checkpoint)
            harnessCheck(manifestPin.digest == ingress.manifestDigest, HarnessCode.EXPECTED_PIN)
            val manifest = HarnessManifest.parse(manifestPin.readBounded(HarnessManifest.MAX_BYTES, checkpoint), ingress.manifestDigest)
            checkpoint()
            val parent = HarnessRootPin.capture(manifest.retainedParent, manifest.retainedParentKey, "rwx------")
            val bridge = HarnessFilePin.capture(manifest.bridge.path, manifest.bridge.size, manifest.bridge.digest, checkpoint)
            val bridgeInventory = inspectHarnessArchive(bridge, true, checkpoint)
            val compiler = manifest.compiler.map { HarnessFilePin.capture(it.path, it.size, it.digest, checkpoint) }
            harnessCheck((compiler + bridge).map { it.key }.distinct().size == compiler.size + 1, HarnessCode.ALIAS)
            checkpoint()
            // This committed helper internally scans a bounded whole image. Surround its constructor
            // with the SAME original clock; its own scan clock cannot authorize an overrun here.
            val installation = KotlinJvmToolchain(manifest.javaExecutable, manifest.compiler.map { it.identity() })
            checkpoint()
            harnessCheck(installation.javaExecutable == manifest.javaExecutable && installation.digest == manifest.toolchainDigest &&
                installation.executableDigest == manifest.executableDigest && installation.runtimeImage.digest == manifest.runtimeDigest,
                HarnessCode.TOOLCHAIN)
            val jdkRoot = HarnessRootPin.capture(installation.runtimeImage.root)
            val executable = HarnessFilePin.observe(manifest.javaExecutable, 67_108_864, checkpoint)
            harnessCheck(executable.digest == manifest.executableDigest, HarnessCode.TOOLCHAIN)
            val hostFiles = ArrayList<HarnessFilePin>()
            val hostTrees = ArrayList<HarnessTreePin>()
            var hostTreeNameBytes = 0L
            val hostInventory = manifest.hostClasspath.map { entry ->
                checkpoint()
                if (entry.kind == "jar") {
                    val file = HarnessFilePin.capture(entry.path, entry.size, entry.digest, checkpoint)
                    hostFiles += file
                    inspectHarnessArchive(file, false, checkpoint)
                } else {
                    val tree = HarnessTreePin.capture(entry.path, entry.entries, entry.size, HarnessTreeUse.HOST, checkpoint)
                    harnessCheck(tree.entries.size == entry.entries && tree.size == entry.size && tree.digest == entry.digest, HarnessCode.EXPECTED_PIN)
                    hostTreeNameBytes += tree.entries.sumOf { it.relative.toByteArray(Charsets.UTF_8).size.toLong() }
                    harnessCheck(hostTreeNameBytes <= 4_194_304, HarnessCode.LIMIT)
                    hostTrees += tree
                    tree.json()
                }
            }
            val closure = HarnessClosure(manifest, manifestPin, parent, installation, jdkRoot, executable, bridge,
                harnessList(compiler), harnessList(hostFiles), harnessList(hostTrees), bridgeInventory, JsonArray(hostInventory))
            closure.verify(checkpoint)
            return closure
        }
    }
}

// The pinned baseline registry is 1,376,206 bytes. This finite bound is shared by
// both resource-origin branches; a size match alone never admits its containing entry.
internal const val HARNESS_MAX_REGISTRY_BYTES: Long = 2_097_152L
internal fun harnessRegistrySizeAllowed(size: Long): Boolean = size in 1L..HARNESS_MAX_REGISTRY_BYTES

private fun verifyHostProcess(manifest: HarnessManifest, checkpoint: () -> Unit) {
    checkpoint()
    harnessCheck(System.getProperty("java.home") == manifest.javaExecutable.parent.parent.toString() &&
        Runtime.version().feature() == 21, HarnessCode.HOST_CLOSURE)
    val classpath = System.getProperty("java.class.path") ?: throw HarnessProblem(HarnessCode.HOST_CLOSURE)
    harnessCheck(classpath.length <= 131_072 && classpath.split(File.pathSeparatorChar) == manifest.hostClasspath.map { it.path.toString() }, HarnessCode.HOST_CLOSURE)
    val arguments = ManagementFactory.getRuntimeMXBean().inputArguments
    harnessCheck(arguments == manifest.hostVmArguments && arguments.all(::allowedHostVmArgument), HarnessCode.HOST_CLOSURE)
    val forbiddenProperties = listOf("java.system.class.loader", "java.ext.dirs", "java.endorsed.dirs", "sun.boot.class.path",
        "jdk.module.path", "jdk.module.upgrade.path", "jdk.module.main")
    harnessCheck(forbiddenProperties.all { System.getProperty(it).isNullOrEmpty() } &&
        System.getProperties().stringPropertyNames().none { it.startsWith("jdk.module.patch.") }, HarnessCode.HOST_CLOSURE)
    val forbiddenEnvironment = listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "LD_PRELOAD", "LD_LIBRARY_PATH",
        "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "DYLD_FRAMEWORK_PATH")
    harnessCheck(forbiddenEnvironment.all { System.getenv(it).isNullOrEmpty() }, HarnessCode.HOST_CLOSURE)
    val loader = ClassLoader.getSystemClassLoader()
    harnessCheck(loader.parent === ClassLoader.getPlatformClassLoader() && loader.parent.parent == null &&
        Thread.currentThread().contextClassLoader === loader, HarnessCode.HOST_CLOSURE)
    hostAnchors().forEach { (name, type) ->
        checkpoint()
        harnessCheck(type.classLoader === loader && !type.module.isNamed, HarnessCode.HOST_CLOSURE)
        val source = type.protectionDomain?.codeSource?.location ?: throw HarnessProblem(HarnessCode.HOST_CLOSURE)
        harnessCheck(source.protocol == "file", HarnessCode.HOST_CLOSURE)
        val actual = Path.of(source.toURI())
        harnessCheck(actual == manifest.hostClasspath[manifest.anchors.getValue(name)].path && actual.toRealPath() == actual, HarnessCode.HOST_CLOSURE)
    }
    // Registry.load uses this resource lookup. Exactly one admitted resource, not a counts-only ID guess.
    val resources = loader.getResources("compiler-rule-map.json")
    harnessCheck(resources.hasMoreElements(), HarnessCode.HOST_CLOSURE)
    val selected = resources.nextElement()
    harnessCheck(!resources.hasMoreElements(), HarnessCode.HOST_CLOSURE)
    val entry = manifest.hostClasspath[manifest.registryIndex]
    if (entry.kind == "jar") {
        harnessCheck(selected.protocol == "jar", HarnessCode.HOST_CLOSURE)
        // Parse data only. Never open a URL connection or resource-selected implementation.
        val external = selected.toExternalForm()
        val suffix = "!/compiler-rule-map.json"
        harnessCheck(external.startsWith("jar:file:") && external.endsWith(suffix), HarnessCode.HOST_CLOSURE)
        val archive = java.net.URI.create(external.substring(4, external.length - suffix.length))
        harnessCheck(archive.scheme == "file" && archive.query == null && archive.fragment == null &&
            Path.of(archive) == entry.path, HarnessCode.HOST_CLOSURE)
        ZipFile(entry.path.toFile()).use { zip ->
            val resource = zip.getEntry("compiler-rule-map.json") ?: throw HarnessProblem(HarnessCode.HOST_CLOSURE)
            harnessCheck(!resource.isDirectory && harnessRegistrySizeAllowed(resource.size), HarnessCode.LIMIT)
        }
    } else {
        val resourcePath = entry.path.resolve("compiler-rule-map.json")
        harnessCheck(selected.protocol == "file" && Path.of(selected.toURI()) == resourcePath &&
            harnessRegistrySizeAllowed(Files.size(resourcePath)), HarnessCode.HOST_CLOSURE)
    }
    checkpoint()
}

/** CREATE_NEW independent copy. Byte equality alone does not permit a source/compiler alias. */
internal fun copyHarnessFile(source: HarnessFilePin, destination: Path, checkpoint: () -> Unit): HarnessFilePin {
    source.verify(checkpoint)
    Files.newInputStream(source.path, READ, NOFOLLOW_LINKS).use { input ->
        Files.newOutputStream(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            val bytes = ByteArray(65_536)
            var count = 0L
            while (true) {
                checkpoint()
                val size = input.read(bytes)
                if (size < 0) break
                harnessCheck(size > 0 && size.toLong() <= source.size - count, HarnessCode.FILE_DRIFT)
                output.write(bytes, 0, size); count += size
            }
            harnessCheck(count == source.size, HarnessCode.FILE_DRIFT)
        }
    }
    Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("r--------"))
    val copy = HarnessFilePin.capture(destination, source.size, source.digest, checkpoint)
    harnessCheck(copy.key != source.key, HarnessCode.ALIAS)
    source.verify(checkpoint); copy.verify(checkpoint)
    return copy
}
