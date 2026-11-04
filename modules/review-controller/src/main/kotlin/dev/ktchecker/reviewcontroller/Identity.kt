package dev.ktchecker.reviewcontroller

import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.PlanContextIdentity
import java.util.Collections
import kotlinx.serialization.json.encodeToJsonElement

internal fun problem(code: String, message: String): Nothing = throw ReviewControllerException(code, message)
internal fun requireIdentifier(value: String, label: String) {
    if (value.isBlank() || value.length > 512 || value.any { it == '\u0000' || it.isISOControl() }) {
        problem("identity_invalid", "$label must be a bounded explicit identity.")
    }
}
internal fun requireDigest(value: String, label: String) {
    if (!Digests.valid(value)) problem("identity_invalid", "$label must be an exact digest.")
}
internal inline fun <reified T> canonical(value: T): String = Digests.canonical(ContractJson.encodeToJsonElement(value))
internal inline fun <reified T> digest(value: T): String = Digests.sha256(canonical(value))
internal fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())
internal fun freezeSnapshot(value: SnapshotManifest): SnapshotManifest = value.copy(files = immutable(value.files),
    declaredRoots = immutable(value.declaredRoots), scopeExclusions = immutable(value.scopeExclusions), unknownInputs = immutable(value.unknownInputs))
internal fun freezeExecution(value: TrustedExecution): TrustedExecution = value.copy(arguments = immutable(value.arguments), environment = immutableMap(value.environment))
internal fun freezeProfiles(values: Map<String, TrustedExecution>): Map<String, TrustedExecution> = immutableMap(values.mapValues { freezeExecution(it.value) })
internal fun freezeInput(value: AnalysisInput): AnalysisInput = value.copy(snapshot = freezeSnapshot(value.snapshot), context = value.context.copy(
    classpath = immutable(value.context.classpath), compilerArguments = immutable(value.context.compilerArguments),
    libraries = immutableMap(value.context.libraries), policies = immutableMap(value.context.policies),
    compilationFiles = value.context.compilationFiles?.let(::immutable)), requiredRuleIds = immutable(value.requiredRuleIds),
    changedPaths = immutable(value.changedPaths), execution = value.execution?.let(::freezeExecution))

/** Exact kernel PlanContextIdentity recipe. Computing it does NOT establish model/source authority. */
fun exactPlanContextIdentity(input: AnalysisInput): PlanContextIdentity? {
    val context = input.context
    val compilerVersion = context.compilerVersion ?: return null
    val identities = listOf(context.modelDigest, context.dependencyDigest, context.toolchainDigest, context.configurationDigest)
    if (compilerVersion.isBlank() || identities.any { it == null || !Digests.valid(it) }) return null
    val paths = context.compilationFiles ?: return null
    if (paths.distinct().size != paths.size || context.classpath.any { !Digests.valid(it.digest) }) return null
    val files = input.snapshot.files.associateBy { it.path }
    if (paths.any { it !in files }) return null
    return PlanContextIdentity(context.scope, context.modelDigest!!, context.dependencyDigest!!, context.toolchainDigest!!,
        compilerVersion, digest(context.compilerArguments), digest(context.classpath), digest(context.libraries), digest(context.policies),
        digest(paths.map { files.getValue(it) }), context.configurationDigest!!)
}
