package dev.ktchecker.cli

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.PREVIEW_CONTRACT
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Explicit trusted-owner pipe, NOT an ordinary review RPC or model tool. */
internal fun parseOwnerCaptureSessionOptions(arguments: Arguments): WorkbenchOptions {
    val allowed = setOf("state", "compiler-dir", "compiler-java-home", "workers", "owner-action")
    if (arguments.words != listOf("owner-capture-session") || !arguments.hasOnly(allowed) ||
        !arguments.has("owner-action") || arguments.integer("workers", 0) != 0) {
        throw CliProblem("invalid_arguments", "Owner capture requires a closed explicit owner selection and zero workers.")
    }
    fun selectedPath(name: String): Path {
        val raw = arguments.required(name)
        if (raw.isBlank()) throw CliProblem("invalid_arguments", "An explicit nonempty owner path is required.")
        return try { Path.of(raw) } catch (_: InvalidPathException) {
            throw CliProblem("invalid_arguments", "An explicit valid owner path is required.")
        }
    }
    return WorkbenchOptions(state = selectedPath("state"), compilerDirectory = selectedPath("compiler-dir"),
        compilerJavaHome = selectedPath("compiler-java-home"), workerCount = 0)
}

internal fun runOwnerCaptureSession(arguments: Arguments): Int {
    val options = parseOwnerCaptureSessionOptions(arguments) // Before owner creation or state mutation.
    val directory = checkNotNull(options.compilerDirectory)
    // A second actual typed selection avoids exposing or changing Workbench's private installation.
    // A digest from a frame cannot reconstruct this capability. Unsupported selections do not fall back.
    val installation = selectCompiler(directory, checkNotNull(options.compilerJavaHome)).toolchain
        ?: throw CliProblem("owner_capture_toolchain_unavailable", "The selected complete compiler installation is unavailable.")
    return Workbench(options).use { workbench ->
        val compiler = workbench.providerAvailability()["compiler"] as? JsonObject
        if (compiler?.get("state") != JsonPrimitive("toolchain_bound") ||
            compiler?.get("toolchainDigest") != JsonPrimitive(installation.digest)) {
            throw CliProblem("owner_capture_toolchain_changed", "The two locally selected installations differ.")
        }
        val operations = WorkbenchCaptureOperations(workbench, installation, directory)
        if (OwnerCaptureSession(operations, CompilerRuleRegistry.load().jvmRuleIds).serve(System.`in`, System.out)) 0 else 2
    }
}

/** The test seam is typed local composition only; it is never decoded from frames. */
internal interface OwnerCaptureOperations {
    fun verifyEnvironment(expectedToolchainDigest: String)
    fun overlay(document: OverlayDocument): JsonObject
    fun prepare(document: PreparationSpec, authorizeExecution: Boolean): JsonObject
}

private class WorkbenchCaptureOperations(
    private val workbench: Workbench,
    private val installation: KotlinJvmToolchain,
    private val compilerDirectory: Path,
) : OwnerCaptureOperations {
    override fun verifyEnvironment(expectedToolchainDigest: String) {
        if (expectedToolchainDigest != installation.digest) {
            throw CliProblem("owner_capture_toolchain_changed", "The exact owner-selected installation is required on every frame.")
        }
        workbench.verifyOwnerCode()
        // Check the complete selected JAR roster too: hashing only old members would miss additions.
        if (installedCompilerArtifacts(compilerDirectory) != installation.compilerClasspath) {
            throw CliProblem("owner_capture_toolchain_changed", "The compiler artifact selection changed.")
        }
        installation.verify() // Full JDK image and compiler bytes, never cached mtime/stat approval.
    }

    override fun overlay(document: OverlayDocument): JsonObject = result(workbench.overlay(document))

    override fun prepare(document: PreparationSpec, authorizeExecution: Boolean): JsonObject {
        // Keep fresh snapshot/materialization/configuration capture and exact policy admission in Workbench.
        val prepared = workbench.prepare(document, authorizeExecution)
        if (prepared.executionAuthorized != authorizeExecution ||
            prepared.input.context.toolchainDigest != installation.digest ||
            prepared.profiles.keys.any { it != CompilerRuleRegistry.PROVIDER_ID }) {
            throw CliProblem("owner_capture_preparation_changed", "Prepared selection differs from this exact compiler capture.")
        }
        return buildJsonObject {
            put("prepared", result(prepared)); put("installedProviders", workbench.providerAvailability())
        }
    }
}

/**
 * One explicitly launched owner may retain capture machinery, never a blanket execution grant.
 * The only operations are immutable overlay capture and explicit compiler-subset preparation.
 * No registration, submit, config mutation, model session, arbitrary command or Workbench.call forwarding.
 * Any protocol/operation/identity failure poisons the pipe. An uncertain mutation is NOT rolled back,
 * retried, reconnected or replayed. The launch flag never substitutes for each prepare Boolean.
 */
internal class OwnerCaptureSession(
    private val operations: OwnerCaptureOperations,
    compilerRuleIds: Set<String>,
    private val maxFrameBytes: Int = 16_777_216,
) {
    private val compilerRules = compilerRuleIds.toSet()
    private var failed = false
    private val rpc = RpcServer(RpcHandler(::dispatch), maxFrameBytes, maxFrameBytes)

    internal fun handle(line: String): JsonObject {
        if (failed) return failure("", "owner_capture_session_failed")
        val response = rpc.handle(line)
        val bounded = if (response.toString().toByteArray(Charsets.UTF_8).size <= maxFrameBytes) response
            else failure(response["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), "response_too_large")
        if ("error" in bounded) failed = true // Includes envelope/profile rejection before dispatch.
        return bounded
    }

    fun serve(input: InputStream, output: OutputStream): Boolean {
        try {
            while (!failed) {
                val response = try {
                    val bytes = readFrame(input) ?: return true
                    handle(strictJsonDocument(bytes, "invalid_request"))
                } catch (_: Exception) {
                    failed = true
                    failure("", "invalid_request")
                }
                writeJsonLine(response, output)
            }
            return false
        } finally { failed = true } // EOF/output failure also ends this session; it is never reusable.
    }

    private fun dispatch(method: String, params: JsonObject): JsonObject {
        if (failed) throw CliProblem("owner_capture_session_failed", "Start no further work after an uncertain owner operation.")
        val allowed = when (method) {
            "owner.overlay" -> setOf("expectedToolchainDigest", "document")
            "owner.prepare" -> setOf("expectedToolchainDigest", "document", "authorizeExecution")
            else -> throw CliProblem("method_not_found", "Only explicit owner overlay and compiler preparation are available.")
        }
        if (params.keys != allowed) throw CliProblem("invalid_request", "Exact owner capture parameters are required.")
        val expected = params["expectedToolchainDigest"] as? JsonPrimitive
        if (expected == null || !expected.isString || !expected.content.matches(Regex("sha256:[0-9a-f]{64}"))) {
            throw CliProblem("invalid_request", "An exact expected compiler installation digest is required.")
        }
        val document = params["document"] as? JsonObject
            ?: throw CliProblem("invalid_request", "An explicit owner document is required.")
        // Decode/validate the entire frame BEFORE any environment or mutation operation.
        val operation: () -> JsonObject = when (method) {
            "owner.overlay" -> {
                val overlay = decodeDocument<OverlayDocument>(document)
                val action: () -> JsonObject = { operations.overlay(overlay) }
                action
            }
            else -> {
                val consent = params["authorizeExecution"] as? JsonPrimitive
                if (consent == null || consent.isString || consent.booleanOrNull == null) {
                    throw CliProblem("invalid_request", "Every preparation requires an explicit JSON Boolean execution decision.")
                }
                val prepare = decodeDocument<PreparationSpec>(document)
                val rules = prepare.requiredRuleIds
                if (prepare.compilerVersion != CompilerRuleRegistry.VERSION || prepare.scope.target.lowercase() !in setOf("jvm", "desktop", "android") ||
                    rules.isNullOrEmpty() || rules.distinct().size != rules.size || rules.any { it !in compilerRules }) {
                    throw CliProblem("invalid_request", "This owner session accepts only explicit bundled JVM compiler rule subsets.")
                }
                val authorizeExecution = consent.boolean
                val action: () -> JsonObject = { operations.prepare(prepare, authorizeExecution) }
                action
            }
        }
        operations.verifyEnvironment(expected.content)
        val response = operation()
        operations.verifyEnvironment(expected.content)
        return response
    }

    private inline fun <reified T> decodeDocument(document: JsonObject): T = try {
        ContractJson.decodeFromJsonElement(document)
    } catch (_: Exception) {
        throw CliProblem("invalid_request", "The owner document does not match the required closed schema.")
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (bytes.size() == 0) return null
                throw CliProblem("invalid_request", "An owner frame must end with a newline.")
            }
            if (next == '\n'.code) return bytes.toByteArray()
            if (bytes.size() >= maxFrameBytes) throw CliProblem("request_too_large", "Owner frame exceeds its byte bound.")
            bytes.write(next)
        }
    }

    private fun failure(id: String, code: String): JsonObject = buildJsonObject {
        put("contractVersion", PREVIEW_CONTRACT); put("id", id)
        putJsonObject("error") {
            put("code", code); put("message", "Owner capture session ended; inspect private state without automatic retry or replay.")
        }
    }
}
