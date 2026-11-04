package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import java.util.Collections
import kotlinx.serialization.encodeToString

/** A future owner-authorized check, not a claim that emitting a diagnostic verified a fix. */
internal object CompilerVerification {
    fun recompile(
        input: AnalysisInput,
        upstreamId: String,
        toolchainDigest: String,
        compilationPaths: List<String>,
    ): List<Verification> {
        val flags = ContractJson.encodeToString(input.context.compilerArguments)
        val classpathDigest = Digests.sha256(ContractJson.encodeToString(input.context.classpath))
        val membershipDigest = Digests.sha256(ContractJson.encodeToString(compilationPaths.sorted()))
        val membership = if (input.context.compilationFiles == null) "the same limited sealed source batch" else
            "the same owner-imported compilation membership"
        return Collections.unmodifiableList(listOf(Verification(
            kind = "exact_context_recompile",
            expected = "After edits, capture a new immutable snapshot and recompile $membership " +
                "(membership digest $membershipDigest) in JVM context ${input.context.scope.id}. " +
                "Use pinned Kotlin${CompilerRuleRegistry.VERSION}, the same toolchain $toolchainDigest, " +
                "explicit compiler flags $flags and exact dependency classpath digest $classpathDigest. " +
                "The owner must grant a new non-serializable execution permit bound to the new snapshot and " +
                "new AnalysisInput.analysisKey(); the baseline permit/key ${input.analysisKey()} must not be reused. " +
                "Use fresh isolated outputs. Resolution requires actual exit-zero compilation with a matching " +
                "compiler banner, non-truncated/non-cancelled comparable diagnostic transport, and no " +
                "$upstreamId at the edited site within the equivalent checked scope while its checker stays enabled. " +
                "Added or changed suppressions, disabled checkers, omitted sources/dependencies, unparsed output, " +
                "or failed/unexecuted compilation are not proof of resolution. Re-import and explicitly compare " +
                "any changed compilation settings before comparing diagnostics. This emission is baseline " +
                "evidence only, not fix verification or whole-project qualification.",
            // An old job's paths/permit are not a valid post-edit command, and no Gradle task is inferred.
            command = emptyList(),
            state = "not_run",
        )))
    }
}
