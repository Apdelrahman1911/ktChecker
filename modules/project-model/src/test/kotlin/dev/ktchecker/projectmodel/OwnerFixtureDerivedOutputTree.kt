package dev.ktchecker.projectmodel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Path

/** Declaration/evidence data only. No tree in this file can be an input artifact or execution receipt. */
@Serializable internal data class OwnerJvmOutputSpec(
    val compilationId: String,
    val taskPath: String,
    val purpose: String,
    val ownerRootRole: String,
    val relativePath: String,
)

@Serializable internal enum class OwnerJvmTreeEntryKind { DIRECTORY, FILE, SYMLINK, OTHER }
@Serializable internal data class OwnerJvmTreeEntry(val relativePath: String, val kind: OwnerJvmTreeEntryKind,
    val size: Long? = null, val digest: String? = null)

/** Caller-declared observations are distinguishable, but never prove freshness or quiescence. */
@Serializable internal sealed interface OwnerJvmTreePresence {
    @Serializable data class Unobserved(val reason: String) : OwnerJvmTreePresence
    @Serializable data class Absent(val parentAnchor: String, val relativeName: String) : OwnerJvmTreePresence
    @Serializable data class EmptyDirectory(val rootAnchor: String) : OwnerJvmTreePresence
    @Serializable data class Tree(val rootAnchor: String, val entries: List<OwnerJvmTreeEntry>) : OwnerJvmTreePresence
}

@Serializable internal data class OwnerJvmIntendedOutput(val spec: OwnerJvmOutputSpec, val intendedPath: String,
    val specDigest: String, val compilerAttemptId: String? = null,
    val presence: OwnerJvmTreePresence = OwnerJvmTreePresence.Unobserved("No compiler attempt or output-tree observation was made."))

@Serializable internal data class OwnerJvmOutputDeclaration(val declarationId: String, val inputCoreIdentity: String,
    val snapshotId: String, val launchRecipeIdentity: String, val attemptId: String,
    val root: String, val spec: OwnerJvmOutputSpec, val initialPresence: OwnerJvmTreePresence,
    val state: String = "declaration_data_only")

internal object OwnerFixtureDerivedOutputTree {
    const val RELATIVE_PATH = "gradle-build/10557602b86b871f06774e27e6fa94f53ef2b8e12a902e6d301ae14933f78cd8/classes/kotlin/main"
    const val MAX_ENTRIES = 1_024
    const val MAX_DEPTH = 32
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    fun spec() = OwnerJvmOutputSpec(":lib||main", ":lib:compileKotlin", "kotlin-classes", "owner-isolated-output", RELATIVE_PATH)

    /** The original destination comes from the owner's exporter output root, not a report-chosen prefix. */
    fun intended(ownerExporterOutput: Path): OwnerJvmIntendedOutput {
        ownerJvmAbsolutePath(ownerExporterOutput.toString())
        val spec = spec()
        val digest = Digests.sha256("ktchecker/owner-jvm-output-spec/1\u0000" + ContractJson.encodeToString(spec))
        return OwnerJvmIntendedOutput(spec, ownerExporterOutput.resolve(spec.relativePath).toString(), digest)
    }

    /** A future owner may predeclare an attempt. This pure function neither observes nor creates its tree.
     * Input-core identity includes the output spec, NOT this declaration or future content: no hash cycle.
     * Deliberately no method accepts ProcessOutcome/JSON and produces a successful derivation certificate. */
    fun declare(inputCoreIdentity: String, snapshotId: String, launchRecipeIdentity: String, attemptId: String,
        outputRoot: Path, sourceRoot: Path, initialPresence: OwnerJvmTreePresence): OwnerJvmOutputDeclaration {
        listOf(inputCoreIdentity, snapshotId, launchRecipeIdentity).forEach {
            ownerJvmRequire(Digests.valid(it), "owner_jvm_output_identity_invalid", "output")
        }
        ownerJvmRequire(attemptId.length in 1..128 && attemptId.none { it.isISOControl() },
            "owner_jvm_output_attempt_invalid", "output")
        ownerJvmAbsolutePath(outputRoot.toString()); ownerJvmAbsolutePath(sourceRoot.toString())
        ownerJvmRequire(!outputRoot.startsWith(sourceRoot) && !sourceRoot.startsWith(outputRoot),
            "owner_jvm_output_source_overlap", "output")
        validatePresence(initialPresence)
        // A present or unknown tree cannot be silently deleted/rebaselined as a fresh attempt.
        ownerJvmRequire(initialPresence is OwnerJvmTreePresence.Absent && initialPresence.relativeName == RELATIVE_PATH,
            "owner_jvm_output_fresh_absence_required", "output")
        val declaration = OwnerJvmOutputDeclaration("", inputCoreIdentity, snapshotId, launchRecipeIdentity, attemptId,
            outputRoot.toString(), spec(), initialPresence)
        val identity = Digests.sha256("ktchecker/owner-jvm-output-declaration/1\u0000" + ContractJson.encodeToString(declaration))
        return declaration.copy(declarationId = identity)
    }

    /** Bounded canonical observation identity, never a derivation or ABI identity. */
    fun presenceIdentity(presence: OwnerJvmTreePresence): String {
        val detached = if (presence is OwnerJvmTreePresence.Tree) presence.copy(entries = immutable(presence.entries)) else presence
        validatePresence(detached)
        return Digests.sha256("ktchecker/owner-jvm-tree-presence-data/1\u0000" + ContractJson.encodeToString(detached))
    }

    fun validatePresence(presence: OwnerJvmTreePresence) {
        fun anchor(value: String) = ownerJvmRequire(value.length in 1..512 && value.none { it.isISOControl() },
            "owner_jvm_output_anchor_invalid", "output")
        when (presence) {
            is OwnerJvmTreePresence.Unobserved -> ownerJvmRequire(presence.reason.length in 1..512,
                "owner_jvm_output_observation_invalid", "output")
            is OwnerJvmTreePresence.Absent -> { anchor(presence.parentAnchor); relative(presence.relativeName) }
            is OwnerJvmTreePresence.EmptyDirectory -> anchor(presence.rootAnchor)
            is OwnerJvmTreePresence.Tree -> {
                anchor(presence.rootAnchor)
                ownerJvmRequire(presence.entries.size in 1..MAX_ENTRIES, "owner_jvm_output_tree_bound", "output")
                val names = presence.entries.map { it.relativePath }
                ownerJvmRequire(names == names.distinct().sorted(), "owner_jvm_output_tree_noncanonical", "output")
                var totalBytes = 0L
                var textBudget = 0L
                val directories = mutableSetOf<String>()
                presence.entries.forEach { entry ->
                    relative(entry.relativePath)
                    textBudget += entry.relativePath.length * 6L + 256
                    ownerJvmRequire(textBudget <= OWNER_JVM_PLAN_EVIDENCE_LIMIT, "owner_jvm_output_tree_bound", "output")
                    val parent = entry.relativePath.substringBeforeLast('/', "")
                    ownerJvmRequire(parent.isEmpty() || parent in directories, "owner_jvm_output_tree_parent_missing", "output")
                    when (entry.kind) {
                        OwnerJvmTreeEntryKind.DIRECTORY -> {
                            ownerJvmRequire(entry.size == null && entry.digest == null, "owner_jvm_output_directory_invalid", "output")
                            directories += entry.relativePath
                        }
                        OwnerJvmTreeEntryKind.FILE -> {
                            val size = entry.size
                            ownerJvmRequire(size != null && size in 0..MAX_FILE_BYTES && entry.digest?.let(Digests::valid) == true,
                                "owner_jvm_output_file_invalid", "output")
                            totalBytes += requireNotNull(size)
                            ownerJvmRequire(totalBytes <= MAX_TOTAL_BYTES, "owner_jvm_output_tree_bound", "output")
                        }
                        else -> throw OwnerJvmPlanProblem("owner_jvm_output_entry_unsafe", "output")
                    }
                }
            }
        }
    }

    private fun relative(path: String) {
        ownerJvmRequire(path.length in 1..4096 && !path.startsWith('/') && '\\' !in path &&
            path.none { it.isISOControl() } && !Regex("^[A-Za-z]:").containsMatchIn(path) &&
            path.split('/').let { parts -> parts.size <= MAX_DEPTH && parts.none { it.isEmpty() || it == "." || it == ".." } },
            "owner_jvm_output_path_unsafe", "output")
    }
}
