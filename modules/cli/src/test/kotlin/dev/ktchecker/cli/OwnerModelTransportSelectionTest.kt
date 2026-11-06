package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.AdapterCredential
import dev.ktchecker.kernel.ClientCredential
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Explicit selection DATA projections only. Synthetic pins/credentials are never supplied to
 * an owner, actual KotlinJvmToolchain, kernel, provider, compiler, model or installed image. */
class OwnerModelTransportSelectionTest {
    @TempDir lateinit var directory: Path
    private val state = Path.of("/SYNTHETIC-NOT-OPENED/owner-state")
    private fun pair(name: String, index: Int = 1, bytes: Long = 10): OwnerModelTransportArtifactPair = OwnerModelTransportArtifactPair(
        "/SYNTHETIC-NOT-OPENED/installed/$name-1.jar",
        "modules-2/files-2.1/unit.group/$name/1/${"a".repeat(40)}/$name-1.jar", Digests.sha256("INERT-$name"), bytes,
        "11:${index + 100}", "22:${index + 100}")
    private fun image(pairs: List<OwnerModelTransportArtifactPair>) = pairs.mapIndexed { index, p ->
        OwnerModelTransportImagePin("installed/$index", p.installedAbsolutePath, p.digest, p.bytes)
    }
    private fun mapped(pairs: List<OwnerModelTransportArtifactPair>, paths: List<String> = pairs.map { it.installedAbsolutePath },
                       pins: List<OwnerModelTransportImagePin> = image(pairs)) =
        OwnerModelTransportPreparedSelection.mapCopiedArtifacts(state, paths, pairs, pins)
    private fun selection(): OwnerModelTransportSelection {
        val pairs = listOf(pair("a"), pair("b", 2))
        val java = OwnerModelTransportImagePin("jdk/bin/java", "/SYNTHETIC-NOT-OPENED/jdk/bin/java", Digests.sha256("inert-java"), 1)
        val jar = OwnerModelTransportImagePin("lib/cli.jar", "/SYNTHETIC-NOT-OPENED/dist/lib/cli.jar", Digests.sha256("inert-cli"), 1)
        return OwnerModelTransportSelection("ktchecker-owner-session-selection/1", "INERT-attempt", state.toString(),
            state.resolve("model-command/runs").toString(), OwnerModelLineageRegistration("w", "t", "c", "s", "l"),
            OwnerModelInvocationSelection("/SYNTHETIC-NOT-OPENED/gradle", "/SYNTHETIC-NOT-OPENED/seed", "/SYNTHETIC-NOT-OPENED/jdk",
                pairs.map { it.installedAbsolutePath }, true), "/SYNTHETIC-NOT-OPENED/dist", java, listOf(jar),
            "/SYNTHETIC-NOT-OPENED/image.json", Digests.sha256("inert-image-selection"), Digests.sha256("inert-code-image"), pairs)
    }

    @Test fun fiveFieldAbsoluteSelectionMapsOnlyTheExactOrderedDeclaredCachePairs() {
        val pairs = listOf(pair("z"), pair("a", 2))
        val values = mapped(pairs)
        assertEquals(listOf("unit.group:z:1", "unit.group:a:1"), values.map { it.coordinate })
        assertEquals(pairs.map { it.digest }, values.map { it.digest })
        assertEquals(pairs.map { state.resolve("model-command/artifacts/gradle-home/caches").resolve(it.cacheRelativePath).toString() },
            values.map { it.path })
        assertFailsWith<CliProblem> { mapped(pairs, paths = pairs.reversed().map { it.installedAbsolutePath }) }
        assertFailsWith<CliProblem> { mapped(pairs, paths = listOf(pairs.first().installedAbsolutePath)) }
        assertFailsWith<CliProblem> { mapped(listOf(pairs.first(), pairs.first())) }
        assertEquals(16, ModelWire.MAX_ARTIFACTS)
        assertEquals(67_108_864L, ModelWire.MAX_ARTIFACT_BYTES); assertEquals(134_217_728L, ModelWire.MAX_ARTIFACT_TOTAL_BYTES)
    }

    @Test fun layoutRejectsAbsoluteTraversalDuplicateSeparatorAlternateNameAndDigestSegments() {
        val good = pair("a").cacheRelativePath
        assertEquals(Path.of(good), OwnerModelTransportPreparedSelection.relativeArtifact(good))
        val invalid = listOf("/$good", "../$good", good.replace("files-2.1/", "files-2.1//"), good.replace("unit.group/", "../"),
            good.replace("modules-2", "other"), good.replace("a".repeat(40), "a".repeat(39)),
            good.replace("a".repeat(40), "A".repeat(40)), good.replace("a-1.jar", "a-1.zip"),
            good.replace("unit.group", "unit:group"), good.replace("unit.group/", "unit.group\\"))
        invalid.forEach { value -> assertFailsWith<CliProblem>(value) { OwnerModelTransportPreparedSelection.relativeArtifact(value) } }
    }

    @Test fun pairDigestSizeIdentityAndExactImageMembershipRemainIndependentRequirements() {
        val original = pair("a")
        val variants = listOf(original.copy(digest = original.digest.removePrefix("sha256:")), original.copy(bytes = 0),
            original.copy(bytes = ModelWire.MAX_ARTIFACT_BYTES + 1), original.copy(installedIdentity = "(dev=11,ino=101)"),
            original.copy(cacheIdentity = original.installedIdentity), original.copy(cacheIdentity = "22:-101"),
            original.copy(installedAbsolutePath = "/SYNTHETIC-NOT-OPENED/installed/wrong.jar"),
            original.copy(installedAbsolutePath = "relative/a-1.jar"), original.copy(installedAbsolutePath = "/selected/../a-1.jar"))
        variants.forEach { changed -> assertFailsWith<CliProblem> { mapped(listOf(changed), pins = image(listOf(original))) } }
        assertFailsWith<CliProblem> { mapped(listOf(original), pins = emptyList()) }
        assertFailsWith<CliProblem> { mapped(listOf(original), pins = image(listOf(original, original))) }
        assertFailsWith<CliProblem> { mapped(listOf(original), pins = image(listOf(original.copy(digest = Digests.sha256("different"))))) }
        assertFailsWith<CliProblem> { mapped(listOf(original), pins = image(listOf(original.copy(bytes = original.bytes + 1)))) }
    }

    @Test fun aggregateCountCoordinateAndFilenameAliasesCannotBeHiddenByDifferentPaths() {
        assertFailsWith<CliProblem> { mapped((1..17).map { pair("a$it", it) }) }
        assertFailsWith<CliProblem> { mapped((1..3).map { pair("a$it", it, ModelWire.MAX_ARTIFACT_BYTES) }) }
        val first = pair("a")
        val second = first.copy(installedAbsolutePath = "/another/a-1.jar", cacheRelativePath = first.cacheRelativePath.replace("a".repeat(40), "b".repeat(40)),
            installedIdentity = "33:101", cacheIdentity = "44:101")
        assertFailsWith<CliProblem> { mapped(listOf(first, second)) }
        val otherCoordinate = second.copy(cacheRelativePath = second.cacheRelativePath.replace("unit.group/a/", "unit.group/b/"))
        assertFailsWith<CliProblem> { mapped(listOf(first, otherCoordinate)) }
    }

    @Test fun selectedTransportDtoRequiresExactFieldAndPrimitiveTypesWithoutDefaultOrNumericStringCoercion() {
        val selected = selection()
        val json = ContractJson.encodeToJsonElement(selected).jsonObject
        val copied = ownerModelTransportExact<OwnerModelTransportSelection>(json)
        assertEquals(selected, copied); assertNotSame(selected, copied)
        assertNotSame(selected.compilerArtifacts, copied.compilerArtifacts)
        val badPin = transportTestChange(json.getValue("jvmExecutable").jsonObject, "bytes", JsonPrimitive("1"))
        val badInvocation = transportTestChange(json.getValue("invocationSelection").jsonObject, "authorizeExecution", JsonPrimitive("true"))
        val malformed = listOf(JsonObject(json + ("implicitPolicy" to JsonPrimitive(true))), JsonObject(json - "registration"),
            transportTestChange(json, "jvmExecutable", badPin), transportTestChange(json, "invocationSelection", badInvocation))
        malformed.forEach { value -> assertFailsWith<Exception> { ownerModelTransportExact<OwnerModelTransportSelection>(value) } }
    }

    @Test fun fullImageDtoAlsoRejectsNestedPinNumericStringsAndUnselectedSchemaFields() {
        val pin = selection().jvmExecutable
        val image = OwnerModelTransportImageSelection("ktchecker-current-image-owner-selection/1", "INERT-approval", listOf(pin), listOf(pin),
            listOf(pin), listOf(pin.path), pin, "24.22.0", pin, pin, Digests.sha256("inert-code-image"))
        val json = ContractJson.encodeToJsonElement(image).jsonObject
        assertEquals(image, ownerModelTransportExact<OwnerModelTransportImageSelection>(json, 2_097_152))
        val badPin = transportTestChange(ContractJson.encodeToJsonElement(pin).jsonObject, "bytes", JsonPrimitive("1"))
        assertFailsWith<Exception> { ownerModelTransportExact<OwnerModelTransportImageSelection>(
            transportTestChange(json, "installedOutputs", JsonArray(listOf(badPin))), 2_097_152) }
        assertFailsWith<Exception> { ownerModelTransportExact<OwnerModelTransportImageSelection>(JsonObject(json + ("authority" to JsonNull)), 2_097_152) }
    }

    @Test fun patchRoundtripRejectsMissingDefaultBindingAndDetachesWholeInputBeforeExternalChecks() {
        val original = commandTestPatch(files = listOf(commandTestFile("A.kt"), commandTestFile("B.kt")))
        val json = ContractJson.encodeToJsonElement(original).jsonObject
        val copied = ownerModelTransportExact<OwnerModelCompletedPatch>(json)
        assertEquals(original, copied); assertNotSame(original.files, copied.files)
        val reducedBinding = JsonObject(json.getValue("binding").jsonObject - "generation")
        assertFailsWith<Exception> { ownerModelTransportExact<OwnerModelCompletedPatch>(transportTestChange(json, "binding", reducedBinding)) }
        assertFailsWith<CliProblem> { ownerModelTransportExact<OwnerModelCompletedPatch>(json, 1) }
    }

    @Test fun credentialJsonComparisonReturnsTheSameOriginalObjectNotADecodedCredential() {
        val client = ClientCredential("INERT-client", "INERT-unregistered-client-token")
        val adapter = AdapterCredential("INERT-client", "INERT-unregistered-adapter-token")
        val clientParams = buildJsonObject { put("credential", ContractJson.encodeToJsonElement(client.copy())) }
        val adapterParams = buildJsonObject { put("credential", ContractJson.encodeToJsonElement(adapter.copy())) }
        assertSame(client, ownerModelTransportOriginalCredential(clientParams, client))
        assertSame(adapter, ownerModelTransportOriginalCredential(adapterParams, adapter))
        assertFailsWith<CliProblem> { ownerModelTransportOriginalCredential(adapterParams, client) }
        assertFailsWith<CliProblem> { ownerModelTransportOriginalCredential(clientParams, adapter) }
        val extra = buildJsonObject { put("credential", JsonObject(clientParams.getValue("credential").jsonObject + ("authority" to JsonNull))) }
        assertFailsWith<CliProblem> { ownerModelTransportOriginalCredential(extra, client) }
    }

    @Test fun commandMissingExplicitFlagsOrExtraRoutesFailsBeforeSelectedFileOrStateEffects() {
        val request = directory.resolve("DOES-NOT-EXIST.json").toString()
        val digest = Digests.sha256("INERT-selected-document")
        val required = listOf("owner-model-session", "--owner-action", "--authorize-execution", "--request", request, "--selection-digest", digest)
        for (missing in listOf("--owner-action", "--authorize-execution")) {
            assertFailsWith<CliProblem> { runOwnerModelSession(Arguments(required.filter { it != missing }.toTypedArray())) }
        }
        for (missing in listOf(setOf("--request", request), setOf("--selection-digest", digest))) {
            assertFailsWith<CliProblem> { runOwnerModelSession(Arguments(required.filter { it !in missing }.toTypedArray())) }
        }
        for (extra in listOf(listOf("--state", directory.resolve("must-not-be-created").toString()), listOf("--out", "out.json"),
            listOf("--workers", "1"), listOf("extra-word"), listOf("--unknown", "value"))) {
            assertFailsWith<CliProblem> { runOwnerModelSession(Arguments((required + extra).toTypedArray())) }
        }
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test fun exactCliSelectionIsPureAndBareDigestsFailBeforeAnySelectedPathRead() {
        val request = directory.resolve("NOT-READ.json")
        val digest = Digests.sha256("INERT-selection")
        val args = arrayOf("owner-model-session", "--owner-action", "--authorize-execution", "--request", request.toString(), "--selection-digest", digest)
        assertEquals(OwnerModelTransportCliSelection(request, digest), ownerModelTransportOptions(Arguments(args)))
        val bare = args.copyOf().also { it[it.lastIndex] = digest.removePrefix("sha256:") }
        assertFailsWith<CliProblem> { runOwnerModelSession(Arguments(bare)) }
        var checkpoints = 0
        assertEquals("owner_transport_selection_digest", assertFailsWith<CliProblem> {
            OwnerModelTransportPreparedSelection.read(request, digest.removePrefix("sha256:")) { checkpoints++ }
        }.code)
        assertEquals(1, checkpoints); Files.list(directory).use { assertEquals(0L, it.count()) }
    }
}
