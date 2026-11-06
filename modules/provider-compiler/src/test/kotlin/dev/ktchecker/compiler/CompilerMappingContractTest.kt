package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

/** Synthetic identity/profile and pre-allocation controls only; no compiler/native execution. */
class CompilerMappingContractTest {
    private fun resource(): ByteArray = checkNotNull(CompilerRuleRegistry::class.java
        .getResourceAsStream("/compiler-rule-map.json")).use { it.readBytes() }

    @Test fun identityBindsExactLoadedBytesNotOnlyCountsOrSemanticRows() {
        val bytes = resource()
        val original = CompilerRuleRegistry.parse(bytes)
        val whitespace = CompilerRuleRegistry.parse(bytes + " ".toByteArray())
        assertEquals(CompilerRuleRegistry.MAPPING_CONTRACT, original.mappingIdentity.contractId)
        assertEquals(Digests.sha256(bytes), original.mappingIdentity.resourceDigest)
        assertEquals(original.mappings, whitespace.mappings)
        assertNotEquals(original.mappingIdentity, whitespace.mappingIdentity)
        bytes[0] = '?'.code.toByte()
        assertEquals(445, original.mappings.size)
        assertNotEquals(Digests.sha256(bytes), original.mappingIdentity.resourceDigest)
    }

    @Test fun missingForeignContractWrongScopeAndMalformedUtf8CannotLoadAs445() {
        val bytes = resource()
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val changed = listOf(
            JsonObject(root - "mapping_contract"),
            JsonObject(root + ("mapping_contract" to JsonPrimitive(CompilerFixtureMappingContract.LEGACY443.contractId))),
            JsonObject(root + ("mappings" to JsonArray(root.getValue("mappings").jsonArray.dropLast(2)))),
        )
        changed.forEach { candidate -> assertFails { CompilerRuleRegistry.parse(candidate.toString()) } }
        assertFails { CompilerRuleRegistry.parse(bytes + byteArrayOf(0x80.toByte())) }
    }

    @Test fun legacyAndSuccessorIdentityCannotBeRelabeledInEitherDirection() {
        val successor = CompilerRuleRegistry.load().mappingIdentity
        val legacy = CompilerFixtureMappingContract.LEGACY443
        for ((expected, actual) in listOf(legacy to successor, successor to legacy,
            successor to successor.copy(resourceDigest = Digests.sha256("different bytes with the same contract")))) {
            assertEquals(CompilerFixtureMappingContract.REFUSAL,
                assertFailsWith<IllegalStateException> { CompilerFixtureMappingContract.requireExact(expected, actual) }.message)
        }
        for ((expected, actual) in listOf(null to successor, successor to null, null to null)) {
            assertFailsWith<IllegalStateException> { CompilerFixtureMappingContract.requireExact(expected, actual) }
        }
        // A pure equality check is not a runnable445 fixture selector or a process receipt.
        CompilerFixtureMappingContract.requireExact(successor, successor)
        assertFailsWith<IllegalStateException> { CompilerFixtureMappingContract.requireLegacy(successor) }
    }

    @Test fun everyExistingRunnerSelectorRefuses445BeforeEvenResolvingOrAllocatingPaths() {
        val root = Files.createTempDirectory("ktc-mapping-refusal-unit-")
        try {
            val absent = root.resolve("must-not-exist/providers/compiler-fixtures")
            for (cohort in CompilerFixtureSettings.COHORTS.sorted()) {
                assertEquals(CompilerFixtureMappingContract.REFUSAL,
                    assertFailsWith<IllegalStateException> {
                        CompilerFixtureRunner.main(arrayOf("--cohort=$cohort", absent.toString()))
                    }.message, cohort)
                assertFalse(Files.exists(root.resolve("must-not-exist")), cohort)
                assertFalse(Files.exists(root.resolve("build")), cohort)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun mappingByteIdentityBindsProfileAnalysisKeyAndDeniesAnOlderExactPermitBeforeSnapshotOrRunner() {
        val root = Files.createTempDirectory("ktc-mapping-profile-unit-")
        try {
            val jar = root.resolve("kotlin-compiler-embeddable-2.4.21.jar")
            Files.writeString(jar, "not a compiler; profile-only unit bytes")
            val artifact = ArtifactIdentity("unit:compiler", Digests.sha256(Files.readAllBytes(jar)), jar.toString())
            val toolchain = KotlinJvmToolchain(SyntheticJdk.create(root.resolve("synthetic-jdk")), listOf(artifact))
            val bytes = resource()
            val original = CompilerRuleRegistry.parse(bytes)
            val changed = CompilerRuleRegistry.parse(bytes + " ".toByteArray())
            val source = "fun ok() = 1\n"
            val input = AnalysisInput(SnapshotManifest(Digests.sha256("mapping-profile-unit"), "workspace", "worktree",
                listOf(SnapshotFile("source.kt", Digests.sha256(source), source.toByteArray().size.toLong()))), root.toString(),
                AnalysisContext(compilerVersion = "2.4.21", compilationFiles = listOf("source.kt")),
                listOf("ktc.compiler.common.error_suppression"))
            val before = KotlinJvmCompilerProvider(toolchain, registry = original).executionProfile(input)
            val after = KotlinJvmCompilerProvider(toolchain, registry = changed).executionProfile(input)
            assertTrue("-Dktchecker.compiler.mapping=${original.mappingIdentity.contractId}:${original.mappingIdentity.resourceDigest}" in before.arguments)
            assertTrue("-Dktchecker.compiler.mapping=${changed.mappingIdentity.contractId}:${changed.mappingIdentity.resourceDigest}" in after.arguments)
            assertNotEquals(before, after)
            val prepared = input.copy(execution = before)
            assertNotEquals(prepared.analysisKey(), input.copy(execution = after).analysisKey())
            var snapshotChecked = false
            val authority = ExactExecutionAuthority(ExecutionPermit(before, prepared.snapshot.snapshotId, root,
                prepared.analysisKey(), { snapshotChecked = true; error("Profile-only unit forbids source preparation or execution") }))
            val result = KotlinJvmCompilerProvider(toolchain, authority, registry = changed).analyze(prepared)
            assertEquals(listOf("execution_profile_mismatch"), result.reasons)
            assertFalse(snapshotChecked)
            assertTrue(result.findings.isEmpty())
            assertTrue(result.coverage.all { it.provenance == "not_executed" && !it.implemented && !it.tested && !it.qualified })
            assertFalse(result.complete())
        } finally { root.toFile().deleteRecursively() }
    }
}
