package dev.ktchecker.semantic

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DATA/source guard tests only. No compiler launch, original ProcessOutcome, owner permit or model is fabricated. */
class EmptyAssertionFixtureRunnerTest {
    private fun digest(value: String) = Digests.sha256("synthetic-fixture-data-only:$value")
    private fun absoluteFile(name: String) = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve(name)
    private fun checkout() = Path.of(requireNotNull(System.getProperty("ktchecker.semantic.fixture.checkout")))
    private fun sourceBytes(input: SemanticSource): ByteArray = Files.readAllBytes(checkout().resolve("providers/semantic-fixtures").resolve(input.path))
    private fun libraries() = listOf(
        "org.jetbrains.kotlin:kotlin-test:2.4.21" to "deliberately-not-kotlin-test.jar",
        "org.jetbrains.kotlin:kotlin-test-junit5:2.4.21" to "adapter.jar",
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.21" to "standard.jar",
        "org.junit.jupiter:junit-jupiter-api:5.11.4" to "annotations.jar",
    ).map { (coordinate, name) -> "$coordinate\t${absoluteFile(name)}" }.sorted()
    private fun metadata(rows: List<String> = libraries()) = rows.sorted().joinToString("\n")

    @Test fun separateEntrypointIsMandatoryAndDoesNotEnableNativeOrUnknownVersions() {
        assertFalse(emptyAssertionFixtureEnabled(null, "jvm", "jvm", null))
        assertTrue(emptyAssertionFixtureEnabled(1, "jvm", "jvm", null))
        assertTrue(emptyAssertionFixtureEnabled(1, "jvm", "jvm", "true"))
        listOf(0, 2, -1).forEach { version ->
            assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureEnabled(version, "jvm", "jvm", null) }
        }
        listOf("native" to "linuxX64", "jvm" to "iosArm64", "JVM" to "jvm").forEach { (mode, target) ->
            assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureEnabled(1, mode, target, null) }
        }
    }

    @Test fun selectedFixtureCannotDisableItsRequiredPhysicalOriginObservations() {
        listOf("false", "TRUE", "1", "").forEach { option ->
            assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureEnabled(1, "jvm", "jvm", option) }
        }
    }

    @Test fun resolvedCoordinatesNotFileNamesSelectTheFixtureApi() {
        val selected = emptyAssertionFixtureLibraries(metadata())
        assertEquals(4, selected.size)
        val api = selected.single { it.coordinate == EMPTY_ASSERTION_KOTLIN_TEST }
        assertEquals(absoluteFile("deliberately-not-kotlin-test.jar").toString(), api.path)
        val transitive = "org.apiguardian:apiguardian-api:1.1.2\t${absoluteFile("guard.jar")}"
        assertTrue(emptyAssertionFixtureLibraries(metadata(libraries() + transitive)).any { it.coordinate == "org.apiguardian:apiguardian-api:1.1.2" })
    }

    @Test fun incompleteUnselectedOrVersionDriftedLibraryMetadataRejects() {
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(null) }
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries("") }
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(metadata(libraries().drop(1))) }
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(metadata().replace("kotlin-test:2.4.21", "kotlin-test:2.4.20")) }
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(metadata(libraries() + "example:lookalike:1\t${absoluteFile("fake.jar")}")) }
        assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(metadata().replace("junit-jupiter-api:5.11.4", "junit-jupiter-api:5.11.3")) }
    }

    @Test fun ambiguousUnboundedNonCanonicalAndControlBearingMetadataRejects() {
        val rows = libraries()
        val variants = listOf(
            (rows + rows.first()).sorted().joinToString("\n"),
            rows.reversed().joinToString("\n"),
            metadata() + "\n",
            metadata().replace("\t", "\t\t"),
            metadata().replace("\t", "\tbad\u0000"),
            rows.map { it.substringBefore('\t') + "\t" + absoluteFile("same.jar") }.joinToString("\n"),
            rows.map { it.substringBefore('\t') + "\trelative.jar" }.joinToString("\n"),
            "x".repeat(65_537),
        )
        variants.forEach { input -> assertFailsWith<IllegalArgumentException> { emptyAssertionFixtureLibraries(input) } }
    }

    @Test fun realCompilerInputCopiesAreByteIdenticalToTheProviderResourcesAndPinned() {
        EMPTY_ASSERTION_FIXTURE_INPUTS.forEach { pin ->
            val compilerInput = checkout().resolve("providers/semantic-fixtures").resolve(pin.path)
            val resource = checkout().resolve("modules/provider-library/src/test/resources/empty-assert-fails-with").resolve(Path.of(pin.path).fileName)
            assertTrue(Files.isRegularFile(compilerInput, NOFOLLOW_LINKS))
            assertTrue(Files.isRegularFile(resource, NOFOLLOW_LINKS))
            val bytes = Files.readAllBytes(compilerInput)
            assertEquals(pin.size, bytes.size.toLong())
            assertEquals(pin.digest, Digests.sha256(bytes))
            assertContentEquals(Files.readAllBytes(resource), bytes)
        }
    }

    @Test fun fixtureOracleRequiresThreeWholeFunctionsAndExactBlockAndAliasSpans() {
        val input = request().sources.first()
        val bytes = sourceBytes(input)
        val shapes = emptyAssertionFixtureShapes(SourceCoordinates(input, bytes))
        assertEquals(listOf("missingAction", "commentsOnly", "importedAlias"), shapes.map { it.method })
        fun text(span: SemanticSpan) = bytes.copyOfRange(span.startByte, span.endByte).toString(Charsets.UTF_8)
        shapes.forEach { shape ->
            assertEquals(2, shape.ownerSpans.size)
            assertEquals(setOf("@Test fun ${shape.method}() { ${text(shape.call)} }", "fun ${shape.method}() { ${text(shape.call)} }"),
                shape.ownerSpans.map(::text).toSet())
            assertTrue(shape.call.startByte < shape.block.startByte)
            assertEquals(shape.call.endByte, shape.block.endByte)
        }
        assertEquals("{ /* outer /* nested */ end */ }", text(shapes[1].block))
        assertEquals("expectFailure<IllegalArgumentException>() { }", text(shapes[2].call))
    }

    private fun plain(classifier: String, nullable: Boolean = false) = TypeFact("class", classifierId = classifier, nullable = nullable)
    private fun signature(): SymbolFact = SymbolFact(
        id = digest("selected signature"), kind = "function", qualifiedName = "kotlin.test.assertFailsWith",
        compilerModule = "<synthetic library signature guard>", origin = "Library", local = false,
        returnType = TypeFact("type_parameter", nullable = false, parameterOwner = digest("T owner"), parameterIndex = 0),
        parameters = listOf(SemanticParameter(0, "message", plain("kotlin.String", true)),
            SemanticParameter(1, "block", TypeFact("class", classifierId = "kotlin.Function0", nullable = false,
                arguments = listOf(TypeProjectionFact("invariant", plain("kotlin.Any", true)))))),
        typeParameterCount = 1, flags = listOf("visibility:public", "inline"),
    )

    @Test fun apiShapeRequiresAnyNullableRatherThanTheHiddenUnitOverload() {
        val correct = signature()
        assertTrue(emptyAssertionFixtureSignature(correct)) // Synthetic signature comparison only.
        val hidden = correct.copy(parameters = correct.parameters.dropLast(1) + correct.parameters.last().copy(
            type = correct.parameters.last().type.copy(arguments = listOf(TypeProjectionFact("invariant", plain("kotlin.Unit"))))))
        assertFalse(emptyAssertionFixtureSignature(hidden))
    }

    @Test fun callResultMustSatisfyTheProviderGuardNotMerelyBeKnown() {
        val exception = plain("java.lang.IllegalArgumentException")
        assertTrue(emptyAssertionFixtureCallResult(exception))
        val variants = listOf(null, exception.copy(nullable = true), plain("kotlin.Nothing"),
            TypeFact("flexible", components = listOf(exception, exception.copy(nullable = true))),
            TypeFact("type_parameter", nullable = false, parameterOwner = digest("call T owner"), parameterIndex = 0),
            TypeFact("unknown", reason = "synthetic guard"))
        variants.forEach { assertFalse(emptyAssertionFixtureCallResult(it)) }
    }

    @Test fun signatureCannotBeAcceptedByNameOnlyOrWithoutExactOmittedMessagePosition() {
        val api = signature()
        val variants = listOf(api.copy(origin = "Source"), api.copy(local = true), api.copy(qualifiedName = "fixture.assertFailsWith"),
            api.copy(receiverType = plain("kotlin.Any")), api.copy(dispatchReceiverType = plain("kotlin.Any")),
            api.copy(flags = listOf("visibility:public")), api.copy(flags = api.flags + "suspend"), api.copy(typeParameterCount = 0),
            api.copy(parameters = api.parameters.reversed()),
            api.copy(parameters = listOf(api.parameters[0].copy(name = "exceptionClass"), api.parameters[1])),
            api.copy(parameters = listOf(api.parameters[0].copy(type = plain("kotlin.String")), api.parameters[1])),
            api.copy(parameters = listOf(api.parameters[0], api.parameters[1].copy(index = 0))),
            api.copy(parameters = listOf(api.parameters[0], api.parameters[1].copy(vararg = true))),
            api.copy(returnType = api.returnType?.copy(nullable = true)),
            api.copy(returnType = api.returnType?.copy(parameterOwner = null)),
        )
        assertFalse(emptyAssertionFixtureSignature(null))
        variants.forEach { assertFalse(emptyAssertionFixtureSignature(it)) }
    }

    private fun survey(copied: Path): JvmBinaryOriginSurvey {
        val entry = "kotlin/test/SyntheticDataOnly.class"
        val container = JvmBinaryContainerObservation("jar", "$copied!/$entry", copied.toString(), entry,
            "kotlin/test/SyntheticDataOnly", 8, digest("not actual class bytes"))
        return JvmBinaryOriginSurvey(listOf(JvmBinaryOriginObservation(digest("selected signature"), "observed", "kotlin_package_part", container)))
    }

    @Test fun physicalComparisonRequiresTheCopiedJarNotItsOriginalGradleCachePath() {
        val copied = absoluteFile("output/dependencies/2.jar")
        val data = survey(copied)
        assertTrue(emptyAssertionObservedContainer(data, digest("selected signature"), copied))
        // Above is intentionally only a synthetic DTO comparison: it cannot mint ANY owner receipt.
        assertFalse(emptyAssertionObservedContainer(data, digest("selected signature"), absoluteFile("gradle-cache/library.jar")))
        assertFalse(emptyAssertionObservedContainer(data, digest("other symbol"), copied))
    }

    @Test fun absentUnknownAmbiguousOrMalformedOriginObservationsDoNotPass() {
        val copied = absoluteFile("output/dependencies/2.jar")
        val data = survey(copied)
        val row = data.observations.single()
        val variants = listOf<JvmBinaryOriginSurvey?>(null, data.copy(observations = emptyList()),
            data.copy(observerId = "unselected-observer"), data.copy(schemaVersion = 2),
            data.copy(observations = listOf(row, row)),
            data.copy(observations = listOf(JvmBinaryOriginObservation(row.symbolId, "unknown", reason = "kotlin_binary_vfs_unavailable"))),
            data.copy(observations = listOf(row.copy(route = "java_method_class"))),
            data.copy(observations = listOf(row.copy(container = row.container?.copy(classByteDigest = "invalid")))),
            data.copy(observations = listOf(row.copy(container = row.container?.copy(vfsPath = "relative!/bad.class")))))
        variants.forEach { assertFalse(emptyAssertionObservedContainer(it, row.symbolId, copied)) }
        assertFalse(emptyAssertionObservedContainer(data, row.symbolId, null))
    }

    private fun request(): SemanticFactsRequest {
        val artifact = ArtifactIdentity("fixture:synthetic-data-only:1", digest("artifact"), absoluteFile("not-an-artifact.jar").toString())
        return SemanticFactsRequest(snapshotId = digest("snapshot"), analysisId = digest("analysis"), toolchainId = digest("toolchain"),
            compilerArtifacts = listOf(artifact), pluginArtifact = artifact, sourceRoot = checkout().resolve("providers/semantic-fixtures").toString(),
            sources = EMPTY_ASSERTION_FIXTURE_INPUTS.map { SemanticSource(it.path, it.digest, it.size, EMPTY_ASSERTION_FIXTURE_SOURCE_SET) },
            sourceSets = listOf(SemanticSourceSet(EMPTY_ASSERTION_FIXTURE_SOURCE_SET)), leafSourceSet = EMPTY_ASSERTION_FIXTURE_SOURCE_SET,
            target = "jvm", compilationId = "fixture-synthetic-data-guard", compilerArguments = emptyList(), observeJvmBinaryOrigins = true)
    }
    private fun emptyBundle(request: SemanticFactsRequest) = SemanticFactBundle(request.digest(), request.snapshotId, request.analysisId,
        request.target, request.compilationId, "unavailable", null, listOf("semantic_execution_unavailable"), emptyList(), false)

    @Test fun anAllAbstainOrCallerClaimedCompleteResultCannotSatisfyRealPositiveJoins() {
        val request = request()
        listOf(emptyBundle(request), emptyBundle(request).copy(state = "extracted", compilerExitCode = 0,
            reasons = emptyList(), completeForSupportedVocabulary = true)).forEach { bundle ->
            val assertions = emptyAssertionFactAssertions(request, bundle, ::sourceBytes)
            assertEquals(31, assertions.size)
            assertFalse(assertions.values.all { it })
            listOf("missingAction", "commentsOnly", "importedAlias").forEach { name ->
                assertFalse(assertions.getValue("empty-assertion-$name-real-reified-any-block-api"))
                assertFalse(assertions.getValue("empty-assertion-$name-direct-call-source-and-owner"))
                assertFalse(assertions.getValue("empty-assertion-$name-declared-empty-unit-lambda"))
            }
        }
    }

    @Test fun sourceOrScopeDriftCannotBeHiddenByValidLookingMetadata() {
        val request = request()
        val unobserved = request.copy(observeJvmBinaryOrigins = false)
        assertFalse(emptyAssertionFactAssertions(unobserved, emptyBundle(unobserved), ::sourceBytes)
            .getValue("empty-assertion-jvm-origin-vocabulary-requested"))
        val wrongSet = request.copy(leafSourceSet = "jvmMain")
        assertFalse(emptyAssertionFactAssertions(wrongSet, emptyBundle(wrongSet), ::sourceBytes)
            .getValue("empty-assertion-source-vector-explicit-and-pinned"))
        assertFalse(emptyAssertionFactAssertions(request, emptyBundle(request)) { sourceBytes(it) + byteArrayOf(32) }
            .getValue("empty-assertion-source-vector-explicit-and-pinned"))
    }
}
