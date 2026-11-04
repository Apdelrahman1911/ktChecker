package dev.ktchecker.cli

import dev.ktchecker.contracts.PREVIEW_CONTRACT
import kotlinx.serialization.json.*

internal fun doctor(): JsonObject = buildJsonObject {
    put("contractVersion", PREVIEW_CONTRACT)
    put("command", "doctor")
    put("version", "0.1.0-dev.1")
    put("status", "preview")
    put("releaseQualified", false)
    putJsonObject("runtime") {
        put("javaVersion", System.getProperty("java.version"))
        put("os", System.getProperty("os.name"))
        put("arch", System.getProperty("os.arch"))
    }
    putJsonArray("capabilities") {
        add("immutable-overlay-capture")
        add("durable-review-ledger")
        add("independent-client-delivery")
        add("owner-authorized-suppression-controls")
        add("isolated-provider-adapters")
        add("mcp-sdk-bridge-separate-installation")
    }
    putJsonArray("gaps") {
        add("Full included rule inventory has not passed per-context qualification gates.")
        add("Provider availability and execution require explicit owner-selected toolchain profiles.")
        add("No arbitrary project model or caller fullProject flag establishes complete target coverage.")
        add("Strict live-source completion needs a tested cooperative edit controller and actual host model-request observation.")
        add("This doctor invocation does not compile Android, Desktop or iOS targets.")
        add("The warm 1–3 second useful-result target is not a general performance guarantee.")
    }
}
