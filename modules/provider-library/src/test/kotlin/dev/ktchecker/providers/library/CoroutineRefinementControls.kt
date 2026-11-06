package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import dev.ktchecker.semantic.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * SYNTHETIC controls ONLY. Reuses the existing immutable-snapshot/model-ingress fixture, whose
 * artifact bytes are deliberately non-JAR text. All semantic facts/ownership/execution/policy
 * receipts here are fabricated test inputs; no fixture application or compiler is ever invoked.
 * Original sealed source BYTES are retained, but this synthetic fixture relocates the one file
 * to LibraryRuleFixtures.source.path. Its model/source-set is explicitly synthetic main, not
 * evidence of the original example's Gradle source membership or an admitted actual tuple.
 */
internal class CoroutineRefinementControls(val f: LibraryRuleFixtures) {
    val model: VerifiedProjectModel = f.model()
    val trace = mutableListOf<String>()

    fun input(rules: List<String> = listOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION)): AnalysisInput = f.input(model, rules)

    private fun bind(symbol: SymbolFact): SymbolFact = symbol.copy(id = resolvedSymbolIdentity(symbol))

    fun owner(name: String = "waiting", suspended: Boolean = true, returnType: TypeFact = plain("kotlin.Unit")): SymbolFact {
        val prefix = if (suspended) "suspend fun $name" else "fun $name"
        val start = f.text.lastIndexOf(prefix)
        check(start >= 0)
        return bind(f.owner(name, suspend = suspended, source = f.span(start, f.text.trimEnd().length)).copy(returnType = returnType))
    }

    fun lambda(name: String, span: SemanticSpan, returns: TypeFact = plain("kotlin.Int")): SymbolFact =
        bind(f.owner(name, "lambda", suspend = false, local = true, source = span).copy(returnType = returns))

    fun target(role: CoroutineCallableRole, transform: (SymbolFact) -> SymbolFact = { it }): SymbolFact {
        val parameterOwner = Digests.sha256("SYNTHETIC-generic-owner:$role")
        fun parameter(index: Int) = TypeFact("type_parameter", nullable = false, parameterOwner = parameterOwner, parameterIndex = index)
        val r = parameter(0)
        val t = parameter(1)
        val (name, parameters, result) = when (role) {
            CoroutineCallableRole.THREAD_SLEEP_LONG -> Triple("java.lang.Thread.sleep",
                listOf(SemanticParameter(0, "arg0", plain("kotlin.Long"))), plain("kotlin.Unit"))
            CoroutineCallableRole.DELAY_LONG -> Triple("kotlinx.coroutines.delay",
                listOf(SemanticParameter(0, "timeMillis", plain("kotlin.Long"))), plain("kotlin.Unit"))
            CoroutineCallableRole.RUN_CATCHING -> Triple("kotlin.runCatching",
                listOf(SemanticParameter(0, "block", applied("kotlin.Function0", r))), applied("kotlin.Result", r))
            CoroutineCallableRole.GET_OR_ELSE -> Triple("kotlin.getOrElse",
                listOf(SemanticParameter(0, "onFailure", applied("kotlin.Function1", plain("kotlin.Throwable"), r))), r)
            CoroutineCallableRole.GET_OR_THROW -> Triple("kotlin.getOrThrow", emptyList(), r)
        }
        val generic = when (role) {
            CoroutineCallableRole.GET_OR_ELSE -> 2
            CoroutineCallableRole.GET_OR_THROW, CoroutineCallableRole.RUN_CATCHING -> 1
            else -> 0
        }
        val receiver = when (role) {
            CoroutineCallableRole.GET_OR_ELSE -> applied("kotlin.Result", t)
            CoroutineCallableRole.GET_OR_THROW -> applied("kotlin.Result", r)
            else -> null
        }
        val extraFlags = when (role) {
            CoroutineCallableRole.DELAY_LONG -> listOf("suspend")
            CoroutineCallableRole.THREAD_SLEEP_LONG -> emptyList()
            else -> listOf("inline")
        }
        return bind(transform(SymbolFact("", "function", name, "SYNTHETIC-binary-module",
            if (role == CoroutineCallableRole.THREAD_SLEEP_LONG) "Java.Library" else "Library", false,
            returnType = result, receiverType = receiver, parameters = parameters, typeParameterCount = generic,
            flags = listOf("visibility:public") + extraFlags)))
    }

    fun selected(role: CoroutineCallableRole, symbol: SymbolFact = target(role)): CoroutineCallableSelection =
        CoroutineCallableSelection(role, symbol.id, when (role) {
            CoroutineCallableRole.THREAD_SLEEP_LONG -> null
            CoroutineCallableRole.DELAY_LONG -> f.coroutines
            else -> f.stdlib
        })

    fun call(target: SymbolFact, owner: SymbolFact?, span: SemanticSpan, arguments: List<SemanticSpan>,
             result: TypeFact = plain("kotlin.Unit"), extensionReceiver: TypeFact? = null): CallFact =
        CallFact(span, "function_call", target.id, owner?.id, result, extensionReceiverType = extensionReceiver,
            arguments = arguments.mapIndexed { index, source -> ArgumentBindingFact(source, index, target.parameters[index].name) })

    fun selection(owners: List<SymbolFact>, targets: List<SymbolFact>, calls: List<CallFact>,
                  selections: List<CoroutineCallableSelection>, exemptions: List<BlockingInteropExemption> = emptyList()): CoroutineRefinementSelection {
        val context = input().context
        val request = SemanticFactsRequest(f.snapshot.snapshotId, Digests.sha256("SYNTHETIC-coroutine-extraction"),
            requireNotNull(context.toolchainDigest), listOf(f.compiler), f.plugin, f.root, listOf(f.source),
            listOf(SemanticSourceSet(f.sourceSetId)), f.sourceSetId, "jvm", f.compilationId,
            context.compilerArguments, context.classpath)
        val shard = SemanticFactShard(request.digest(), request.snapshotId, request.analysisId, request.toolchainId,
            request.pluginArtifact.digest, SEMANTIC_COMPILER_VERSION, f.source,
            CompilerSourceContext("SYNTHETIC-source-module", "JVM", false, emptyList()), owners.map { it.id },
            owners + targets, calls, emptyList(), emptyList(), emptyList())
        return CoroutineRefinementSelection(request, listOf(shard), 0, CoroutineRefinementApplicability(selections, exemptions))
    }

    fun sleep(suspended: Boolean = true, exempt: Boolean = false, alias: Boolean = false,
              role: CoroutineCallableRole = CoroutineCallableRole.THREAD_SLEEP_LONG): CoroutineRefinementSelection {
        val owner = owner(suspended = suspended)
        val target = target(role)
        val expression = when {
            role == CoroutineCallableRole.DELAY_LONG -> "kotlinx.coroutines.delay(1L)"
            alias -> "pause(1L)"
            else -> "java.lang.Thread.sleep(1L)"
        }
        return selection(listOf(owner), listOf(target), listOf(call(target, owner, f.span(expression), listOf(f.span("1L")))),
            listOf(selected(role, target)), if (exempt) listOf(BlockingInteropExemption(owner.id, requireNotNull(owner.source))) else emptyList())
    }

    fun sleepHomonym(selectAsJdk: Boolean = false): CoroutineRefinementSelection {
        val owner = owner(returnType = plain("kotlin.Long"))
        val target = target(CoroutineCallableRole.THREAD_SLEEP_LONG) {
            it.copy(qualifiedName = "fixture.sleep.homonym.Thread.sleep", compilerModule = "SYNTHETIC-source-module",
                origin = "Source", source = f.span("fun sleep(milliseconds: Long): Long = milliseconds"),
                returnType = plain("kotlin.Long"), parameters = listOf(SemanticParameter(0, "milliseconds", plain("kotlin.Long"))))
        }
        return selection(listOf(owner, target), emptyList(),
            listOf(call(target, owner, f.span("Thread.sleep(1L)"), listOf(f.span("1L")), plain("kotlin.Long"))),
            if (selectAsJdk) listOf(selected(CoroutineCallableRole.THREAD_SLEEP_LONG, target)) else emptyList())
    }

    fun result(terminal: CoroutineCallableRole? = CoroutineCallableRole.GET_OR_ELSE,
               runName: String = "runCatching", delayName: String = "delay", terminalName: String? = null,
               positiveLiteral: String = "1L", homonym: Boolean = false): CoroutineRefinementSelection {
        val resultType = applied("kotlin.Result", plain("kotlin.Int"))
        val owner = owner("recoverOrPropagate", returnType = if (terminal == null && !homonym) resultType else plain("kotlin.Int"))
        val run = target(CoroutineCallableRole.RUN_CATCHING) { candidate ->
            if (!homonym) candidate else {
                val t = requireNotNull(candidate.returnType?.arguments?.singleOrNull()?.type)
                candidate.copy(qualifiedName = "fixture.cancellation.resulthomonym.runCatching",
                    origin = "Source", compilerModule = "SYNTHETIC-source-module",
                    source = f.span("private suspend fun <T> runCatching(block: suspend () -> T): T = block()"),
                    returnType = t, parameters = listOf(SemanticParameter(0, "block", applied("kotlin.coroutines.SuspendFunction0", t))),
                    flags = listOf("visibility:private", "suspend"))
            }
        }
        val delay = target(CoroutineCallableRole.DELAY_LONG)
        val runStart = f.text.lastIndexOf("$runName {")
        val open = f.text.indexOf('{', runStart)
        val close = f.text.indexOf('}', open)
        check(runStart >= 0 && close > open)
        val block = f.span(open, close + 1)
        val blockOwner = lambda("runBlock", block).let { original ->
            if (homonym) bind(original.copy(flags = original.flags + "suspend")) else original
        }
        val runSpan = f.span(runStart, close + 1)
        val delaySpan = f.span("$delayName($positiveLiteral)")
        val owners = mutableListOf(owner, blockOwner)
        val targets = mutableListOf(run, delay)
        val calls = mutableListOf(call(run, owner, runSpan, listOf(block), if (homonym) plain("kotlin.Int") else resultType),
            call(delay, blockOwner, delaySpan, listOf(f.span(positiveLiteral))))
        val selections = mutableListOf(selected(CoroutineCallableRole.DELAY_LONG, delay))
        if (!homonym) selections += selected(CoroutineCallableRole.RUN_CATCHING, run)
        else { owners += run; targets.remove(run) }
        if (terminal != null) {
            val target = target(terminal)
            val name = terminalName ?: if (terminal == CoroutineCallableRole.GET_OR_ELSE) "getOrElse" else "getOrThrow"
            val fragment = if (terminal == CoroutineCallableRole.GET_OR_ELSE) "$name { 0 }" else "$name()"
            val end = f.text.lastIndexOf(fragment) + fragment.length
            check(end > close)
            val arguments = if (terminal == CoroutineCallableRole.GET_OR_ELSE) listOf(f.span("{ 0 }")) else emptyList()
            if (arguments.isNotEmpty()) owners += lambda("failureBlock", arguments.single())
            targets += target
            calls += call(target, owner, f.span(runStart, end), arguments, plain("kotlin.Int"), resultType)
            selections += selected(terminal, target)
        }
        return selection(owners, targets, calls, selections)
    }

    /** Every callback is deliberately SYNTHETIC. They must never enter a production host registry. */
    fun permit(request: CoroutineRefinementAuthorizationRequest, failAt: String? = null,
               sourceBytes: () -> ByteArray = { f.sourceBytes.copyOf() }): CoroutineRefinementPermit {
        fun mark(stage: String) {
            trace += stage
            if (stage == failAt) error("SYNTHETIC confidential callback text must not escape")
        }
        return CoroutineRefinementPermit(model, request,
            verifySnapshot = { mark("snapshot"); check(it.input.snapshot == f.snapshot) },
            verifyExtraction = { mark("extraction"); check(it == request) },
            verifyArtifact = { artifact ->
                mark("artifact")
                check(artifact in listOf(f.compiler, f.plugin, f.stdlib, f.coroutines, f.runtime))
                check(Digests.sha256(Files.readAllBytes(Path.of(artifact.path))) == artifact.digest)
            },
            verifyRuntimeImage = { mark("runtime"); check(it.runtimeArtifacts == listOf(f.runtime)) },
            verifyCallableOwnership = { symbol, actual ->
                mark("ownership")
                check(symbol in actual.applicability.callables) // Fake registry, NOT real binary provenance.
            },
            verifyBlockingBridgePolicy = { mark("policy"); check(it.applicability == request.applicability) },
            readSourceBytes = { mark("source"); check(it == f.source); sourceBytes() })
    }

    fun analyze(selection: CoroutineRefinementSelection, input: AnalysisInput = input(),
                failAt: String? = null, bytes: () -> ByteArray = { f.sourceBytes.copyOf() }): ProviderResult {
        val request = CoroutineRefinementProvider(model, selection).authorizationRequest(input)
        val permit = permit(request, failAt, bytes)
        return CoroutineRefinementProvider(model, selection, ExactCoroutineRefinementAuthority(permit)).analyze(input)
    }

    companion object {
        fun plain(name: String) = TypeFact("class", name, false)
        fun applied(name: String, vararg arguments: TypeFact) = TypeFact("class", name, false,
            arguments.map { TypeProjectionFact("invariant", it) })
        val sealedBytes: ByteArray get() = requireNotNull(CoroutineRefinementControls::class.java
            .getResourceAsStream("/coroutine-refinement/sealed19/cases.json")).use { it.readBytes() }
        val sealedCases: List<JsonObject> get() = ContractJson.parseToJsonElement(sealedBytes.toString(Charsets.UTF_8))
            .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }
        fun source(caseId: String): String = sealedCases.single { it.getValue("id").jsonPrimitive.content == caseId }
            .getValue("sources").jsonObject.values.single().jsonPrimitive.content
    }
}
