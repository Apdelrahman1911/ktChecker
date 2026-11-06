package dev.ktchecker.providers.buildcontracts

import dev.ktchecker.contracts.Digests
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Properties
import java.util.zip.ZipInputStream

/** This is an exact byte-identified local consumer, not version support inferred from a URL. */
internal object WrapperConsumerProfile {
    const val PROFILE_ID = "local-wrapper-7a9ce74c-properties-sha256-exact/1"
    const val ARTIFACT_DIGEST = "sha256:7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
    private val classDigests = mapOf(
        "org/gradle/wrapper/Install.class" to "sha256:e3a1406e36909e29423807e6bd0aca05d0173433857e3509a6a292bac5d2f8ee",
        "org/gradle/wrapper/WrapperExecutor.class" to "sha256:e4392708fabd56cdad10f6dcd6e8ae1b13e3f747245de8fd92a224d8465913e0",
    )

    /** Never loads/reflects on/executes a class. Exact artifact digest precedes bounded ZIP reads. */
    fun verify(bytes: ByteArray) {
        val reason = WrapperUnavailableReason.CONSUMER_PROFILE_UNAVAILABLE
        requireWrapper(bytes.size <= MAX_WRAPPER_JAR_BYTES && Digests.sha256(bytes) == ARTIFACT_DIGEST, reason)
        val observed = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                requireWrapper(++entries <= 256, reason)
                if (entry.name in classDigests) {
                    requireWrapper(entry.name !in observed, reason)
                    val content = zip.readNBytes(1_048_577)
                    requireWrapper(content.size <= 1_048_576, reason)
                    observed[entry.name] = Digests.sha256(content)
                }
                zip.closeEntry()
            }
        }
        requireWrapper(observed == classDigests, reason)
    }

    /** The inspected consumer pads lowercase byte hex and compares with String.equals, not ignoreCase. */
    fun acceptsChecksumShape(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
}

internal data class ParsedWrapperProperties(val checksum: String?, val distributionUrlDigest: String)

/** JDK Properties is the exact parser called by the pinned consumer; no custom line parser. */
internal fun parseWrapperProperties(bytes: ByteArray): ParsedWrapperProperties {
    requireWrapper(bytes.size <= MAX_PROPERTIES_BYTES, WrapperUnavailableReason.ANALYSIS_ENVELOPE_BOUND_EXCEEDED)
    val properties = Properties()
    try {
        ByteArrayInputStream(bytes).use { properties.load(it) }
    } catch (_: Exception) {
        throw WrapperContractProblem(WrapperUnavailableReason.PROPERTIES_PARSER_UNAVAILABLE)
    }
    val distribution = properties.getProperty("distributionUrl")
        ?: throw WrapperContractProblem(WrapperUnavailableReason.DISTRIBUTION_SELECTION_UNAVAILABLE)
    try {
        // Only syntax is checked. A URI/path/name is not version or distribution artifact provenance.
        requireWrapper(distribution.isNotEmpty(), WrapperUnavailableReason.DISTRIBUTION_SELECTION_UNAVAILABLE)
        URI(distribution)
    } catch (_: Exception) {
        throw WrapperContractProblem(WrapperUnavailableReason.DISTRIBUTION_SELECTION_UNAVAILABLE)
    }
    return ParsedWrapperProperties(properties.getProperty("distributionSha256Sum"), Digests.sha256(distribution))
}
