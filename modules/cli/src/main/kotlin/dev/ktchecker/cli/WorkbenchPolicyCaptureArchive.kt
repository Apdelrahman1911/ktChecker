package dev.ktchecker.cli

import dev.ktchecker.config.FrozenConfig
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/** This archive holds private policy documents, not model-facing event payloads or credentials. */
@Serializable
private data class ArchivedWorkbenchPolicy(
    val policy: FrozenConfig,
    val format: String = "ktchecker/workbench-policy-archive/1",
)

internal data class PolicyCaptureArchiveLimits(
    val maxDocuments: Int = 2048,
    val maxBytes: Long = 256L * 1024 * 1024,
    val maxDocumentBytes: Int = 8_388_608,
) {
    init {
        require(maxDocuments in 1..100_000 && maxBytes > 0 && maxDocumentBytes in 1..8_388_608)
    }
}

/**
 * Immutable content-addressed captures in cooperative owner-private storage. Publication uses a
 * short archive-quota lock, never a project analysis/build lock. Both old and new full captures
 * must be durable before an authority returns to the kernel; a failed two-document write may
 * leave an unused capture, but cannot accept a policy event or manufacture a replacement.
 * Missing captures on restore are unavailable, never filled from current project configuration.
 * PrivateState's documented platform durability limits apply; this is not a power-loss proof.
 */
internal class WorkbenchPolicyCaptureArchive(
    private val state: PrivateState,
    private val limits: PolicyCaptureArchiveLimits = PolicyCaptureArchiveLimits(),
) {
    private val directory: Path get() = state.root.resolve("policy-captures")

    fun retain(original: FrozenConfig, current: FrozenConfig): Pair<String, String> {
        val documents = listOf(document(original), document(current))
        val keyed = documents.associateBy(::identity)
        state.locked("policy-capture-archive") {
            val root = state.namespace("policy-captures")
            var count = 0
            var bytes = 0L
            Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    if (++count > limits.maxDocuments || !entry.fileName.toString().matches(Regex("[0-9a-f]{64}\\.json")) ||
                        !Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                        throw CliProblem("policy_archive_unavailable", "Policy capture archive inventory is invalid or exceeds its bound; no history was removed.")
                    }
                    val size = Files.size(entry)
                    if (size !in 1..limits.maxDocumentBytes.toLong() || bytes > limits.maxBytes - size) {
                        throw CliProblem("policy_archive_limit", "Policy capture archive exceeds its byte bound; no history was removed.")
                    }
                    bytes += size
                }
            }
            val additions = keyed.filter { (id, value) ->
                val file = path(id)
                if (Files.exists(file, NOFOLLOW_LINKS)) {
                    if (checkedDocument(id) != value) {
                        throw CliProblem("policy_archive_conflict", "A capture identity already has different immutable bytes.")
                    }
                    false
                } else true
            }
            val extraBytes = additions.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
            if (count.toLong() + additions.size > limits.maxDocuments || bytes > limits.maxBytes - extraBytes) {
                throw CliProblem("policy_archive_limit", "Retained policy capture quota is full; no accepted capture was evicted.")
            }
            additions.forEach { (id, value) -> state.writeBytes(path(id), value.toByteArray(Charsets.UTF_8)) }
        }
        return identity(documents[0]) to identity(documents[1])
    }

    /** No directory creation, migration, live capture, write or repair on a missing reference. */
    fun restore(id: String): FrozenConfig {
        val document = checkedDocument(id)
        return ContractJson.decodeFromString<ArchivedWorkbenchPolicy>(document).policy
    }

    private fun checkedDocument(id: String): String {
        if (!Digests.valid(id) || !Files.isDirectory(directory, NOFOLLOW_LINKS) ||
            !Files.isRegularFile(path(id), NOFOLLOW_LINKS)) {
            throw CliProblem("policy_archive_missing", "The exact retained policy capture is unavailable.")
        }
        // Existing-directory permission validation does not create or repair a missing archive.
        privateDirectory(directory)
        val text = state.readText(path(id))
        if (text.toByteArray(Charsets.UTF_8).size > limits.maxDocumentBytes || identity(text) != id) {
            throw CliProblem("policy_archive_identity_mismatch", "Retained policy bytes do not match their capture identity.")
        }
        val decoded = try { ContractJson.decodeFromString<ArchivedWorkbenchPolicy>(text) }
        catch (_: Exception) { throw CliProblem("policy_archive_invalid", "Retained policy schema is unavailable.") }
        if (decoded.format != "ktchecker/workbench-policy-archive/1" || document(decoded.policy) != text) {
            throw CliProblem("policy_archive_invalid", "Retained policy is not an exact canonical owner capture.")
        }
        return text
    }

    private fun document(policy: FrozenConfig): String {
        if (policy.binding.ownerId != state.ownerId) {
            throw CliProblem("permission_denied", "Policy capture belongs to another owner namespace.")
        }
        val value = Digests.canonical(ContractJson.encodeToJsonElement(ArchivedWorkbenchPolicy(policy)))
        if (value.toByteArray(Charsets.UTF_8).size !in 1..limits.maxDocumentBytes) {
            throw CliProblem("policy_archive_document_limit", "Full policy capture exceeds the retained document bound.")
        }
        return value
    }

    private fun path(id: String): Path {
        if (!Digests.valid(id)) throw CliProblem("policy_archive_identity_invalid", "A bounded content-addressed policy reference is required.")
        return directory.resolve(id.removePrefix("sha256:") + ".json")
    }

    private fun identity(document: String): String = Digests.sha256("ktchecker/workbench-policy-capture/1\n" + document)
}
