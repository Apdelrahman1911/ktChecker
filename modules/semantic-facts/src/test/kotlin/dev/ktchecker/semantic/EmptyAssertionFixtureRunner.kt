package dev.ktchecker.semantic

import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.io.File
import java.nio.file.Path

internal const val EMPTY_ASSERTION_FIXTURE_VERSION = 1
internal const val EMPTY_ASSERTION_FIXTURE_SOURCE_SET = "emptyAssertionFixture"
internal const val EMPTY_ASSERTION_LIBRARY_PROPERTY = "ktchecker.semantic.emptyAssertion.libraries"
internal const val EMPTY_ASSERTION_KOTLIN_TEST = "org.jetbrains.kotlin:kotlin-test:2.4.21"
private const val ASSERT_FAILS_WITH = "kotlin.test.assertFailsWith"

internal data class EmptyAssertionFixtureInput(val path: String, val digest: String, val size: Long)
internal val EMPTY_ASSERTION_FIXTURE_INPUTS = listOf(
    EmptyAssertionFixtureInput("empty-assertion/OrdinaryEmptyTest.kt",
        "sha256:3cdaf01fe13d600a0817dcf7a5e031d84f0e3aa85a0353fa78aecce801cc96f2", 831),
    EmptyAssertionFixtureInput("empty-assertion/ContextControls.kt",
        "sha256:1ba69f48621a9bd38af9af6dbcc0c6e43e458a8ca21cc26961c3ea440ebd2db2", 1694),
)

/**
 * REAL selected JVM compiler action, not an automatically discovered/executed JUnit test suite.
 * All three intentionally failing assertion examples must produce their actual FIR prerequisites;
 * no fixture succeeds merely by abstaining. The second input supplies genuine negative contexts.
 *
 * MISSING INTEGRATION TIER: this runner does not construct VerifiedProjectModel, authorize an
 * extraction or callable ownership, enroll production tests, or issue EmptyAssertionPermit. The
 * owner still needs an actual project semantic producer + exact artifact custody + runner-policy
 * binding before provider activation. Injected provider unit tests are not CLI/Workbench delivery.
 * A physical FIR origin observation and selected Gradle coordinates are not those owner receipts.
 */
object EmptyAssertionFixtureRunner {
    @JvmStatic fun main(args: Array<String>) {
        SemanticFixtureRunner.run(args, emptyAssertionFixtureVersion = EMPTY_ASSERTION_FIXTURE_VERSION)
    }
}

internal fun emptyAssertionFixtureEnabled(version: Int?, mode: String, target: String, originOption: String?): Boolean {
    if (version == null) return false
    require(version == EMPTY_ASSERTION_FIXTURE_VERSION) { "Unsupported empty-assertion fixture version" }
    require(mode == "jvm" && target == "jvm") { "Empty-assertion v1 requires the exact JVM fixture mode/target" }
    require(originOption == null || originOption == "true") {
        "Empty-assertion v1 requires JVM binary-origin observations; an explicit false/unknown option is not accepted"
    }
    return true
}

internal data class EmptyAssertionFixtureLibrary(val coordinate: String, val path: String)
private val emptyAssertionRequiredLibraries = mapOf(
    "org.jetbrains.kotlin:kotlin-test" to "2.4.21",
    "org.jetbrains.kotlin:kotlin-test-junit5" to "2.4.21",
    "org.jetbrains.kotlin:kotlin-stdlib" to "2.4.21",
    "org.junit.jupiter:junit-jupiter-api" to "5.11.4",
)
private val emptyAssertionTransitiveModules = setOf(
    "org.jetbrains:annotations", "org.junit.platform:junit-platform-commons",
    "org.opentest4j:opentest4j", "org.apiguardian:apiguardian-api",
)

/**
 * Bounded DATA parser for the closed Gradle configuration's complete resolved compile vector.
 * Transitive versions are retained exactly as resolved, not guessed here. This parser performs
 * no resolution, file read, archive inspection, provenance attestation or execution authorization.
 */
internal fun emptyAssertionFixtureLibraries(metadata: String?): List<EmptyAssertionFixtureLibrary> {
    val value = requireNotNull(metadata) { "Selected empty-assertion Gradle library metadata is required" }
    require(value.length in 1..65_536 && '\r' !in value && !value.endsWith('\n'))
    val rows = value.split('\n')
    require(rows.size in 4..8 && rows == rows.sorted()) { "Expected a bounded, coordinate-sorted artifact vector" }
    val libraries = rows.map { row ->
        val parts = row.split('\t')
        require(parts.size == 2)
        val coordinate = parts[0]
        val fields = coordinate.split(':')
        require(fields.size == 3 && fields.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) })
        val module = fields.take(2).joinToString(":")
        require(module in emptyAssertionRequiredLibraries || module in emptyAssertionTransitiveModules) {
            "Unexpected empty-assertion compiler dependency module"
        }
        emptyAssertionRequiredLibraries[module]?.let { require(fields[2] == it) { "Selected API version differs" } }
        val path = parts[1]
        require(path.length in 1..4096 && path.none(Char::isISOControl))
        val parsed = Path.of(path)
        require(parsed.isAbsolute && parsed.normalize().toString() == path) { "Expected an exact absolute artifact path" }
        EmptyAssertionFixtureLibrary(coordinate, path)
    }
    require(libraries.map { it.coordinate.substringBeforeLast(':') }.distinct().size == libraries.size)
    require(libraries.map { it.path }.distinct().size == libraries.size)
    require(emptyAssertionRequiredLibraries.all { (module, version) -> libraries.any { it.coordinate == "$module:$version" } })
    return libraries
}

internal data class EmptyAssertionFixtureShape(
    val method: String, val ownerSpans: Set<SemanticSpan>, val call: SemanticSpan, val block: SemanticSpan,
)

/** Exact expectations for PINNED fixture text, not a general Kotlin recognizer or test discovery. */
internal fun emptyAssertionFixtureShapes(coordinates: SourceCoordinates): List<EmptyAssertionFixtureShape> {
    fun shape(method: String, call: String, block: String): EmptyAssertionFixtureShape {
        val function = "@Test fun $method() { $call }"
        val start = coordinates.text.indexOf(function)
        require(start >= 0 && coordinates.text.indexOf(function, start + 1) < 0)
        val callStart = start + function.indexOf(call)
        val blockStart = callStart + call.indexOf(block)
        return EmptyAssertionFixtureShape(method,
            setOf(coordinates.fixtureSpan(start, start + function.length),
                coordinates.fixtureSpan(start + "@Test ".length, start + function.length)),
            coordinates.fixtureSpan(callStart, callStart + call.length),
            coordinates.fixtureSpan(blockStart, blockStart + block.length))
    }
    return listOf(
        shape("missingAction", "assertFailsWith<IllegalArgumentException> { }", "{ }"),
        shape("commentsOnly", "assertFailsWith<IllegalArgumentException> { /* outer /* nested */ end */ }",
            "{ /* outer /* nested */ end */ }"),
        shape("importedAlias", "expectFailure<IllegalArgumentException>() { }", "{ }"),
    )
}

private fun SourceCoordinates.fixtureSpan(start: Int, end: Int): SemanticSpan {
    fun point(offset: Int): Pair<Int, Int> {
        val prefix = text.substring(0, offset)
        return prefix.count { it == '\n' } to (offset - prefix.lastIndexOf('\n') - 1)
    }
    val (line, column) = point(start)
    val (endLine, endColumn) = point(end)
    return span(line, column, endLine, endColumn)
}

private fun emptyAssertionPlain(type: TypeFact?, classifier: String, nullable: Boolean = false): Boolean = type?.let {
    it.kind == "class" && it.classifierId == classifier && it.nullable == nullable && it.arguments.isEmpty() &&
        it.components.isEmpty() && it.known
} == true

/** Must agree with the rule's genuinely supported public reified Any?-block API, not hidden Unit/KClass forms. */
internal fun emptyAssertionFixtureSignature(symbol: SymbolFact?): Boolean {
    if (symbol == null || symbol.kind != "function" || symbol.qualifiedName != ASSERT_FAILS_WITH || symbol.origin != "Library" ||
        symbol.local || symbol.source != null || symbol.receiverType != null || symbol.dispatchReceiverType != null ||
        symbol.contextParameters.isNotEmpty() || symbol.typeParameterCount != 1 || symbol.parameters.size != 2 ||
        "visibility:public" !in symbol.flags || "inline" !in symbol.flags || "suspend" in symbol.flags) return false
    val result = symbol.returnType ?: return false
    val message = symbol.parameters[0]
    val block = symbol.parameters[1]
    if (result.kind != "type_parameter" || result.parameterIndex != 0 || result.nullable != false || !result.known ||
        message.index != 0 || message.name != "message" || message.vararg || !emptyAssertionPlain(message.type, "kotlin.String", true) ||
        block.index != 1 || block.name != "block" || block.vararg) return false
    return block.type.kind == "class" && block.type.classifierId == "kotlin.Function0" && block.type.nullable == false &&
        block.type.known && block.type.arguments.size == 1 && block.type.arguments.single().variance == "invariant" &&
        emptyAssertionPlain(block.type.arguments.single().type, "kotlin.Any", true)
}

/** The provider also rejects known-but-flexible, nullable and Nothing call results. */
internal fun emptyAssertionFixtureCallResult(type: TypeFact?): Boolean = type != null && type.known &&
    type.kind == "class" && type.nullable == false && type.classifierId != "kotlin.Nothing"

private fun emptyAssertionOwner(owner: SymbolFact?, declarations: List<String>): Boolean = owner != null &&
    owner.id in declarations && owner.kind == "function" && owner.origin == "Source" && !owner.local &&
    owner.source?.synthetic == false && owner.parameters.isEmpty() && owner.contextParameters.isEmpty() &&
    owner.receiverType == null && owner.typeParameterCount == 0 &&
    owner.flags.none { it in setOf("suspend", "inline", "external", "abstract", "expect") } &&
    emptyAssertionPlain(owner.returnType, "kotlin.Unit")

private fun emptyAssertionLambda(lambda: SymbolFact?, declarations: List<String>): Boolean = lambda != null &&
    lambda.id in declarations && lambda.kind == "lambda" && lambda.origin == "Source" && lambda.local &&
    lambda.source?.synthetic == false && lambda.parameters.isEmpty() && lambda.contextParameters.isEmpty() &&
    lambda.receiverType == null && lambda.dispatchReceiverType == null && lambda.typeParameterCount == 0 &&
    "suspend" !in lambda.flags && emptyAssertionPlain(lambda.returnType, "kotlin.Unit")

private fun contains(outer: SemanticSpan?, inner: SemanticSpan): Boolean = outer != null &&
    outer.path == inner.path && outer.digest == inner.digest && outer.sourceSet == inner.sourceSet &&
    outer.startByte <= inner.startByte && outer.endByte >= inner.endByte

private data class EmptyAssertionPositive(
    val method: String, val shape: EmptyAssertionFixtureShape?, val shard: SemanticFactShard?,
    val owner: SymbolFact?, val call: CallFact?, val target: SymbolFact?, val lambda: SymbolFact?,
)
private data class EmptyAssertionInspection(val assertions: Map<String, Boolean>, val positives: List<EmptyAssertionPositive>)

private fun inspectEmptyAssertionFacts(request: SemanticFactsRequest, bundle: SemanticFactBundle,
                                       readSource: (SemanticSource) -> ByteArray): EmptyAssertionInspection {
    val coordinates = EMPTY_ASSERTION_FIXTURE_INPUTS.associate { pin ->
        val input = request.sources.singleOrNull { it.path == pin.path && it.digest == pin.digest && it.size == pin.size &&
            it.sourceSet == EMPTY_ASSERTION_FIXTURE_SOURCE_SET }
        pin.path to input?.let { runCatching { SourceCoordinates(it, readSource(it)) }.getOrNull() }
    }
    val ordinaryPath = EMPTY_ASSERTION_FIXTURE_INPUTS[0].path
    val controlPath = EMPTY_ASSERTION_FIXTURE_INPUTS[1].path
    val ordinary = bundle.shards.singleOrNull { it.input.path == ordinaryPath }
    val controls = bundle.shards.singleOrNull { it.input.path == controlPath }
    val shapes = coordinates[ordinaryPath]?.let { runCatching { emptyAssertionFixtureShapes(it) }.getOrNull() }.orEmpty()
    fun symbol(shard: SemanticFactShard?, id: String?): SymbolFact? = shard?.symbols?.singleOrNull { it.id == id }
    val positives = listOf("missingAction", "commentsOnly", "importedAlias").map { name ->
        val owner = ordinary?.symbols?.singleOrNull { it.qualifiedName == "fixture.emptyassert.OrdinaryEmptyTest.$name" }
        val call = ordinary?.calls?.singleOrNull { it.containingSymbolId == owner?.id &&
            symbol(ordinary, it.targetSymbolId)?.qualifiedName == ASSERT_FAILS_WITH }
        val block = call?.arguments?.singleOrNull { it.parameterIndex == 1 && it.parameterName == "block" }?.source
        EmptyAssertionPositive(name, shapes.singleOrNull { it.method == name }, ordinary, owner, call,
            symbol(ordinary, call?.targetSymbolId), ordinary?.symbols?.singleOrNull { it.kind == "lambda" && it.source == block })
    }
    val assertions = linkedMapOf(
        "empty-assertion-source-vector-explicit-and-pinned" to (request.target == "jvm" && request.compilerVersion == "2.4.21" &&
            request.sourceSets == listOf(SemanticSourceSet(EMPTY_ASSERTION_FIXTURE_SOURCE_SET)) &&
            request.leafSourceSet == EMPTY_ASSERTION_FIXTURE_SOURCE_SET &&
            request.sources.map { it.path } == EMPTY_ASSERTION_FIXTURE_INPUTS.map { it.path } && coordinates.values.all { it != null }),
        "empty-assertion-real-complete-fact-bundle" to (bundle.state == "extracted" && bundle.compilerExitCode == 0 &&
            bundle.completeForSupportedVocabulary && bundle.reasons.isEmpty() && bundle.requestDigest == request.digest() &&
            bundle.shards.size == 2 && bundle.shards.map { it.input }.toSet() == request.sources.toSet()),
        "empty-assertion-remains-unqualified" to (!bundle.tupleQualified && !bundle.customRulesQualified),
        "empty-assertion-jvm-origin-vocabulary-requested" to request.observeJvmBinaryOrigins,
    )
    positives.forEach { positive ->
        val prefix = "empty-assertion-${positive.method}"
        val declared = positive.shard?.declarations.orEmpty()
        val shape = positive.shape
        val call = positive.call
        val owner = positive.owner
        assertions["$prefix-captured-whole-owner"] = shape != null && emptyAssertionOwner(owner, declared) && owner?.source in shape.ownerSpans
        // The resolved annotation is observed as a fixture fact, NOT used as production test enrollment.
        assertions["$prefix-resolved-junit-annotation"] = owner?.annotationClassIds?.contains("org.junit.jupiter.api.Test") == true
        assertions["$prefix-real-reified-any-block-api"] = emptyAssertionFixtureSignature(positive.target)
        assertions["$prefix-direct-call-source-and-owner"] = call != null && owner != null && shape != null &&
            call.kind == "function_call" && call.containingSymbolId == owner.id && call.source == shape.call &&
            emptyAssertionFixtureCallResult(call.resultType) && call.dispatchReceiverType == null && call.extensionReceiverType == null
        assertions["$prefix-omitted-message-binds-block-one"] = shape != null && call?.arguments == listOf(ArgumentBindingFact(shape.block, 1, "block"))
        assertions["$prefix-declared-empty-unit-lambda"] = shape != null && emptyAssertionLambda(positive.lambda, declared) &&
            positive.lambda?.source == shape.block && positive.shard?.calls?.none { contains(shape.block, it.source) } == true
    }

    fun controlOwner(name: String): SymbolFact? = controls?.symbols?.singleOrNull {
        it.qualifiedName == "fixture.emptyassert.controls.ContextControls.$name" && emptyAssertionOwner(it, controls?.declarations.orEmpty())
    }
    fun directCalls(owner: SymbolFact?): List<CallFact> = if (owner == null) emptyList() else
        controls?.calls?.filter { it.kind == "function_call" && it.containingSymbolId == owner.id }.orEmpty()
    fun realCall(owner: SymbolFact?): CallFact? = directCalls(owner).singleOrNull { symbol(controls, it.targetSymbolId)?.qualifiedName == ASSERT_FAILS_WITH }
    fun lambda(call: CallFact?): SymbolFact? = call?.arguments?.singleOrNull { it.parameterName == "block" }?.source?.let { span ->
        controls?.symbols?.singleOrNull { it.kind == "lambda" && it.source == span && it.id in controls?.declarations.orEmpty() }
    }
    fun sourceText(span: SemanticSpan?): String? = span?.let { location ->
        coordinates[location.path]?.let { source -> runCatching {
            require(source.span(location.startLine, location.startColumn, location.endLine, location.endColumn, location.synthetic) == location)
            source.text.toByteArray(Charsets.UTF_8).copyOfRange(location.startByte, location.endByte).toString(Charsets.UTF_8)
        }.getOrNull() }
    }
    listOf("expectedThrow" to "throw IllegalArgumentException()", "unknownEffect" to "operation()",
        "unitOnlyUnsupported" to "Unit").forEach { (name, body) ->
        val call = realCall(controlOwner(name))
        val block = lambda(call)
        assertions["empty-assertion-$name-real-nonempty-control"] = call != null && block != null &&
            emptyAssertionFixtureSignature(symbol(controls, call.targetSymbolId)) && sourceText(block.source) == "{ $body }"
    }
    val nestedOwner = controlOwner("nestedCalibration")
    val outer = realCall(nestedOwner)
    val outerBlock = lambda(outer)
    val inner = controls?.calls?.singleOrNull { outerBlock != null && it.kind == "function_call" &&
        it.containingSymbolId == outerBlock.id && contains(outerBlock.source, it.source) &&
        symbol(controls, it.targetSymbolId)?.qualifiedName == ASSERT_FAILS_WITH }
    assertions["empty-assertion-nested-inner-owner-is-declared-lambda-not-test"] = nestedOwner != null && outer != null &&
        outerBlock != null && outerBlock.local && outerBlock.origin == "Source" && inner != null &&
        inner.containingSymbolId != nestedOwner.id && sourceText(lambda(inner)?.source) == "{ }" &&
        emptyAssertionFixtureSignature(symbol(controls, outer.targetSymbolId)) && emptyAssertionFixtureSignature(symbol(controls, inner.targetSymbolId))
    val caught = controlOwner("caughtCalibration")
    val caughtCall = realCall(caught)
    assertions["empty-assertion-caught-call-is-within-larger-test-body"] = caught != null && caughtCall != null &&
        emptyAssertionFixtureSignature(symbol(controls, caughtCall.targetSymbolId)) && contains(caught.source, caughtCall.source) &&
        sourceText(caught.source)?.contains("try {") == true && sourceText(caught.source)?.contains("catch (_: AssertionError)") == true
    val homonym = directCalls(controlOwner("sourceHomonym")).singleOrNull {
        symbol(controls, it.targetSymbolId)?.qualifiedName == "fixture.emptyassert.controls.assertFailsWith"
    }?.let { symbol(controls, it.targetSymbolId) }
    assertions["empty-assertion-source-homonym-resolves-to-captured-source-not-library"] = homonym != null &&
        homonym.origin == "Source" && homonym.source?.path == controlPath && homonym.id in controls?.declarations.orEmpty() &&
        !emptyAssertionFixtureSignature(homonym)
    val kClass = realCall(controlOwner("kClassUnsupported"))?.let { symbol(controls, it.targetSymbolId) }
    assertions["empty-assertion-kclass-real-overload-is-outside-reified-signature"] = kClass != null &&
        kClass.origin == "Library" && kClass.source == null && kClass.parameters.firstOrNull()?.name == "exceptionClass" &&
        !emptyAssertionFixtureSignature(kClass)
    listOf("notAnEnrolledTest", "approvedCalibration").forEach { name ->
        val owner = controlOwner(name)
        val call = realCall(owner)
        assertions["empty-assertion-$name-same-api-does-not-establish-test-policy"] = owner != null &&
            owner.annotationClassIds.isEmpty() && call != null && emptyAssertionFixtureSignature(symbol(controls, call.targetSymbolId)) &&
            sourceText(lambda(call)?.source) == "{ }"
    }
    return EmptyAssertionInspection(assertions, positives)
}

/** DATA-only guard tests use this function. A caller-created bundle cannot prove any execution. */
internal fun emptyAssertionFactAssertions(request: SemanticFactsRequest, bundle: SemanticFactBundle,
                                          readSource: (SemanticSource) -> ByteArray): Map<String, Boolean> =
    inspectEmptyAssertionFacts(request, bundle, readSource).assertions

/** Physical observation comparison only; even a matching caller-created DTO is not authority. */
internal fun emptyAssertionObservedContainer(survey: JvmBinaryOriginSurvey?, symbolId: String?, copiedJar: Path?): Boolean {
    if (survey == null || symbolId == null || copiedJar == null || survey.schemaVersion != 1 ||
        survey.observerId != JVM_BINARY_ORIGIN_OBSERVER_ID) return false
    val observed = survey.observations.singleOrNull { it.symbolId == symbolId } ?: return false
    val container = observed.container ?: return false
    return observed.state == "observed" && observed.route == "kotlin_package_part" && observed.reason == null &&
        container.protocol == "jar" && container.jdkModule == null && Digests.valid(container.classByteDigest) &&
        container.classByteSize in 8..JVM_ORIGIN_MAX_CLASS_BYTES.toLong() &&
        jvmBinaryLocation(container.protocol, container.vfsPath) == JvmBinaryLocation(container.containerPath, container.entryPath, null) &&
        runCatching { Path.of(container.containerPath) == copiedJar.toAbsolutePath().normalize() }.getOrDefault(false)
}

/** Called ONLY with the original returned process object in the existing serialized fixture driver. */
internal fun emptyAssertionFixtureAssertions(request: SemanticFactsRequest, bundle: SemanticFactBundle,
                                             outcome: ProcessOutcome, readSource: (SemanticSource) -> ByteArray): Map<String, Boolean> {
    val inspection = inspectEmptyAssertionFacts(request, bundle, readSource)
    val assertions = LinkedHashMap(inspection.assertions)
    assertions["empty-assertion-original-successful-compiler-process"] = outcome.status == "executed" &&
        outcome.exitCode == 0 && outcome.executed && outcome.executionConfirmed && outcome.successfulExecution &&
        !outcome.cancelled && !outcome.timedOut && !outcome.truncated
    val dependencies = request.dependencyArtifacts
    val selected = runCatching { emptyAssertionFixtureLibraries(dependencies.joinToString("\n") { "${it.coordinate}\t${it.path}" }) }.getOrNull()
    assertions["empty-assertion-complete-selected-library-vector"] = selected != null &&
        selected.size == dependencies.size && dependencies.all { Digests.valid(it.digest) }
    val index = dependencies.indices.singleOrNull { dependencies[it].coordinate == EMPTY_ASSERTION_KOTLIN_TEST }
    val output = outcome.outputDirectory
    val expectedClasspath = output?.let { root -> dependencies.indices.joinToString(File.pathSeparator) { root.resolve("dependencies/$it.jar").toString() } }
    val classpathIndex = outcome.command.indices.singleOrNull { outcome.command[it] == "-classpath" }
    assertions["empty-assertion-original-command-exact-copied-classpath"] = expectedClasspath != null && classpathIndex != null &&
        outcome.command.getOrNull(classpathIndex + 1) == expectedClasspath && "-no-stdlib" in outcome.command && "-no-reflect" in outcome.command
    val copiedKotlinTest = if (output != null && index != null) output.resolve("dependencies/$index.jar") else null
    inspection.positives.forEach { positive ->
        assertions["empty-assertion-${positive.method}-supplying-kotlin-test-container-observed"] =
            emptyAssertionObservedContainer(positive.shard?.jvmBinaryOrigins, positive.target?.id, copiedKotlinTest)
    }
    return assertions
}
