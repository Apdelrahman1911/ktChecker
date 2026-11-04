package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedProcessContainment
import dev.ktchecker.providers.runtime.OwnedSupervisorAvailability
import kotlinx.serialization.json.*

/** Installed resource inspection only. A byte match is neither execution nor cleanup proof. */
internal fun containmentAvailability(
    selected: OwnedSupervisorAvailability = OwnedProcessContainment.inspect(),
): JsonObject = buildJsonObject {
    put("state", selected.status)
    put("target", selected.target)
    put("bundledBytesVerified", selected.available)
    put("executionVerified", false)
    put("releaseQualified", false)
    putJsonArray("reasons") { selected.reasons.forEach { add(it) } }
    selected.identity?.let { identity ->
        putJsonObject("identity") {
            put("protocolVersion", identity.protocolVersion)
            put("target", identity.target)
            put("sourceDigest", identity.sourceDigest)
            put("binaryDigest", identity.binaryDigest)
        }
    }
    put("scope", "Host-specific cooperative owned-child cleanup; not a sandbox or a Windows Job Objects implementation")
}
