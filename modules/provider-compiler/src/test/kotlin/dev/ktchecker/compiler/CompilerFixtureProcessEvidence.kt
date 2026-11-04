package dev.ktchecker.compiler

import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome

/** Receipt fields from the actual observer outcome, never an availability-inspector result. */
internal object CompilerFixtureProcessEvidence {
    fun fields(actual: ProcessOutcome?, expected: OwnedSupervisorIdentity): Map<String, Any?> = mapOf(
        "execution_confirmed" to (actual?.executionConfirmed ?: false),
        "executed" to (actual?.executed ?: false),
        "successful_execution" to (actual?.successfulExecution ?: false),
        "supervisor_identity" to actual?.supervisorIdentity?.let { identity ->
            CompilerFixtureProvenance.supervisorIdentityFields(identity) + mapOf(
                "identity_origin" to "actual_launched_outcome_not_inspector",
                "source_digest_scope" to "Installed build-manifest claim, not source-to-binary reproducibility proof.",
                "native_qualified" to false,
            )
        },
        "supervisor_identity_matches_start_capture" to actual?.let { it.supervisorIdentity == expected },
        "execution_proof_scope" to "A fork-child exit, nonempty output, or START availability alone is not proof that the selected compiler executable started.",
    )
}
