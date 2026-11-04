package dev.ktchecker.cli

import dev.ktchecker.config.*
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.*
import dev.ktchecker.snapshot.SnapshotException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

private const val HELP = """ktChecker 0.1.0-dev.1 — executable preview, not a qualified release

ktchecker doctor --format json
ktchecker providers [--compiler-java-home DIR]
ktchecker rules [--id ktc.rule.id]
ktchecker register --workspace ID --worktree ID --client ID --session ID --lineage ID --out PRIVATE.json
ktchecker overlay --request overlay.json [--out result.json]
ktchecker prepare --request compilation.json [--authorize-execution] --out prepared.json
ktchecker submit --prepared prepared.json [--wait-ms 10000]
ktchecker status|events|cancel --client ID [--review ID] [--cursor CURSOR] [--limit 20]
ktchecker suppressions inspect --client ID [--out policy.json]
ktchecker suppressions add --client ID --request suppression.json --expected revision.json --owner-action
ktchecker suppressions remove --client ID --id SUPPRESSION --expected revision.json --owner-action
ktchecker suppressions export --client ID --out shared-policy.json
ktchecker suppressions import --client ID --request shared-policy.json --expected revision.json --owner-action
ktchecker suppressions apply --client ID --request mutation.json --owner-action
ktchecker suppressions selector --client ID --prepared prepared.json --finding finding.json [--out selector.json]
ktchecker suppressions explain --client ID --prepared prepared.json --finding finding.json
ktchecker rpc

Global options: --state DIR --compiler-dir DIR --compiler-java-home DIR --detekt-artifacts DIR --ktlint-artifacts DIR --workers 0|1|2
Review commands never edit project source or grant execution/suppression permission.
Only explicit owner prepare with --authorize-execution permits the pinned provider profiles.
Compiler execution requires a complete link-free JDK 21 image. A rejected image is unavailable, not a launcher-only fallback.
RPC accepts private NDJSON; use bridges/mcp for actual MCP. Do not send owner/adapter credentials to a model.
Unavailable/partial analysis is not passing. Exit 0 on submit without waiting means accepted only.
"""

internal class Arguments(args: Array<String>) {
    val words = mutableListOf<String>()
    private val options = linkedMapOf<String, String?>()
    init {
        val booleans = setOf("authorize-execution", "owner-action", "help")
        var i = 0
        while (i < args.size) {
            val item = args[i++]
            if (!item.startsWith("--")) { words += item; continue }
            val key = item.removePrefix("--")
            if (options.containsKey(key)) throw CliProblem("invalid_arguments", "Duplicate option --$key.")
            if (key in booleans) options[key] = null
            else {
                if (i >= args.size || args[i].startsWith("--")) throw CliProblem("invalid_arguments", "Option --$key requires a value.")
                options[key] = args[i++]
            }
        }
        val allowed = setOf("state", "compiler-dir", "compiler-java-home", "detekt-artifacts", "ktlint-artifacts", "workers", "format", "id", "workspace", "worktree",
            "client", "session", "lineage", "out", "request", "prepared", "wait-ms", "review", "cursor", "limit", "finding",
            "expected", "layer", "rule") + booleans
        if ((options.keys - allowed).isNotEmpty()) throw CliProblem("invalid_arguments", "Unknown command option.")
        if (options["format"]?.let { it != "json" } == true) throw CliProblem("invalid_arguments", "This preview supports --format json.")
    }
    operator fun get(name: String): String? = options[name]
    fun has(name: String): Boolean = name in options
    fun required(name: String): String = options[name] ?: throw CliProblem("invalid_arguments", "Option --$name is required.")
    fun integer(name: String, default: Int): Int = if (has(name)) required(name).toIntOrNull()
        ?: throw CliProblem("invalid_arguments", "Option --$name requires an integer.") else default
    fun long(name: String, default: Long): Long = if (has(name)) required(name).toLongOrNull()
        ?: throw CliProblem("invalid_arguments", "Option --$name requires an integer.") else default
}

fun main(args: Array<String>) { exitProcess(runCli(args)) }

internal fun runCli(args: Array<String>): Int {
    return try {
        val options = Arguments(args)
        val command = options.words.firstOrNull()
        if (options.has("help") || command in setOf(null, "help", "--help")) { println(HELP); return 0 }
        if (command == "doctor") { emit(doctor(), options["out"]); return 0 }
        if (command == "rules") { emit(Catalog().inspect(options["id"]), options["out"]); return 0 }
        val workers = options.integer("workers", 2)
        if (workers !in 0..2) throw CliProblem("invalid_arguments", "Preview supports zero to two persistent worker threads per process.")
        Workbench(WorkbenchOptions(options["state"]?.let(Path::of) ?: PrivateState.defaultPath(),
            options["compiler-dir"]?.let(Path::of), options["detekt-artifacts"]?.let(Path::of), workers,
            options["ktlint-artifacts"]?.let(Path::of), options["compiler-java-home"]?.let(Path::of))).use { workbench ->
            when (command) {
                "providers" -> { emit(workbench.providerAvailability(), options["out"]); 0 }
                "rpc" -> { RpcServer(workbench).serve(System.`in`, System.out); 0 }
                "register" -> {
                    val out = options.required("out")
                    val registration = workbench.register(options.required("workspace"), options.required("worktree"),
                        options.required("client"), options.required("session"), options.required("lineage"))
                    saveOwnerFile(Path.of(out), result(registration))
                    emit(buildJsonObject { put("contractVersion", PREVIEW_CONTRACT); put("clientId", registration.binding.clientId)
                        put("state", "registered"); put("credentialFile", out); put("containsPrivateCredentials", true) })
                    0
                }
                "overlay" -> {
                    val committed = workbench.overlay(readOwnerFile(options.required("request")))
                    emit(result(committed), options["out"]); 0
                }
                "prepare" -> {
                    val out = options.required("out")
                    val prepared = workbench.prepare(readOwnerFile(options.required("request")), options.has("authorize-execution"))
                    saveOwnerFile(Path.of(out), result(prepared))
                    emit(buildJsonObject { put("contractVersion", PREVIEW_CONTRACT); put("state", "prepared")
                        put("snapshotId", prepared.input.snapshot.snapshotId); put("preparedFile", out)
                        put("coverageScope", prepared.coverageScope); put("releaseQualified", false)
                        put("executionAuthorized", prepared.executionAuthorized)
                        put("installedProviders", workbench.providerAvailability())
                        put("providerProfiles", ContractJson.encodeToJsonElement(prepared.profiles.keys.sorted())) })
                    0
                }
                "submit" -> {
                    // Validate before acceptance: a rejected CLI invocation must not secretly submit work.
                    val wait = options.long("wait-ms", 0)
                    if (wait !in 0..300_000) throw CliProblem("invalid_arguments", "wait-ms must be between zero and 300000.")
                    val prepared = readOwnerFile<PreparedReview>(options.required("prepared"))
                    val credential = workbench.registration(prepared.binding.clientId).client
                    var status = workbench.submit(credential, prepared.patchId, prepared.idempotencyKey, prepared.input)
                    val deadline = System.nanoTime() + wait * 1_000_000
                    while (wait > 0 && status.result == null && System.nanoTime() < deadline) {
                        Thread.sleep(25); status = workbench.kernel.status(credential, status.reviewId)
                    }
                    emit(result(status), options["out"])
                    if (wait == 0L) 0 else reviewExit(status)
                }
                "status", "events", "cancel" -> {
                    val credential = workbench.registration(options.required("client")).client
                    val response = when (command) {
                        "events" -> result(workbench.kernel.events(credential, options["cursor"], options.integer("limit", 20)))
                        "cancel" -> result(workbench.kernel.cancel(credential, options.required("review")))
                        else -> result(workbench.kernel.status(credential, options.required("review")))
                    }
                    emit(response, options["out"]); 0
                }
                "suppressions" -> {
                    val clientId = options.required("client")
                    val response = when (options.words.getOrNull(1)) {
                        "inspect" -> workbench.inspectConfig(clientId)
                        "export" -> workbench.exportSharedConfig(clientId)
                        "add", "remove", "import", "enable", "disable" -> {
                            if (!options.has("owner-action")) throw CliProblem("owner_authority_required", "Suppression changes require explicit --owner-action; review never grants it.")
                            val expectedDoc = readOwnerFile<JsonObject>(options.required("expected"))
                            val expected: ConfigRevisionToken = ContractJson.decodeFromJsonElement(expectedDoc["revision"] ?: expectedDoc)
                            val subcommand = options.words[1]
                            val layer = if (subcommand == "import") ConfigLayer.SHARED else when (options["layer"] ?: "shared") {
                                "shared" -> ConfigLayer.SHARED; "local" -> ConfigLayer.LOCAL
                                else -> throw CliProblem("invalid_arguments", "Layer is shared or local.")
                            }
                            val action = when (subcommand) {
                                "add" -> ConfigAction.AddSuppression(readOwnerFile(options.required("request")))
                                "remove" -> ConfigAction.RemoveSuppression(options.required("id"))
                                "import" -> ConfigAction.ImportSharedConfig(readOwnerFile(options.required("request")))
                                "enable", "disable" -> ConfigAction.SetRuleEnabled(options.required("rule"), subcommand == "enable")
                                else -> error("unreachable")
                            }
                            workbench.mutateConfig(clientId, layer, expected, action)
                        }
                        "apply" -> {
                            if (!options.has("owner-action")) throw CliProblem("owner_authority_required", "Suppression changes require explicit --owner-action; review never grants it.")
                            val mutation = readOwnerFile<JsonObject>(options.required("request"))
                            requireKeys(mutation, setOf("layer", "expected", "action"))
                            workbench.mutateConfig(clientId, mutation.decode("layer"), mutation.decode("expected"), mutation.decode("action"))
                        }
                        "selector", "explain" -> {
                            val prepared = readOwnerFile<PreparedReview>(options.required("prepared"))
                            val finding = readOwnerFile<Finding>(options.required("finding"))
                            if (options.words[1] == "selector") workbench.findingSelector(clientId, prepared.input, finding)
                            else workbench.explain(clientId, prepared.input, finding)
                        }
                        else -> throw CliProblem("invalid_arguments", "Use suppressions inspect, add, remove, import, export, enable, disable, apply, selector or explain.")
                    }
                    emit(response, options["out"]); 0
                }
                else -> throw CliProblem("invalid_arguments", "Unknown command; use ktchecker help.")
            }
        }
    } catch (e: Exception) {
        val (code, message) = when (e) {
            is CliProblem -> e.code to e.message
            is KernelException -> e.code to (e.message ?: "Review operation failed.")
            is SnapshotException -> e.code to "Snapshot operation failed; no coherent accepted capture is claimed."
            is ConfigConflictException -> "configuration_conflict" to "Policy changed; inspect its current revision before retrying."
            is ConfigAuthorizationException -> "permission_denied" to "This operation lacks explicit owner configuration authority."
            is ConfigException -> "configuration_invalid" to "Configuration operation failed; inspect trusted local policy evidence."
            else -> "operation_failed" to "Operation failed; no passing analysis is claimed. Check the selected toolchain and private state."
        }
        emit(buildJsonObject { put("contractVersion", PREVIEW_CONTRACT); put("status", "failed")
            putJsonObject("error") { put("code", code); put("message", message) } })
        3
    }
}

internal fun reviewExit(status: ReviewStatus): Int = when {
    status.result == null -> 2
    status.state != "complete" || status.cancelled || status.reasons.isNotEmpty() -> 2
    status.result?.reasons?.contains("preview_not_release_qualified") == true -> 2
    status.result?.complete() != true -> 2
    status.result?.findings?.any { it.disposition == "open" && it.severity in setOf("warning", "error") } == true -> 1
    else -> 0
}

internal inline fun <reified T> readOwnerFile(path: String): T {
    val file = Path.of(path)
    rejectSymlink(file)
    if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.size(file) > 8_388_608) throw CliProblem("invalid_owner_file", "Expected a bounded regular explicitly selected file.")
    val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(8_388_609) }
    if (bytes.size > 8_388_608) throw CliProblem("invalid_owner_file", "Selected file exceeds the input limit.")
    val document = strictJsonDocument(bytes, "invalid_owner_file")
    return try { ContractJson.decodeFromString(document) } catch (_: Exception) {
        throw CliProblem("invalid_owner_file", "Selected file does not match the required document schema.")
    }
}

/** Explicit CLI output only; never chmod or rewrite the parent/project directory. */
internal fun saveOwnerFile(path: Path, value: JsonObject) {
    rejectSymlink(path)
    val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
    FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
        privatePermissions(path, directory = false)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
}

internal fun emit(value: JsonObject, out: String? = null) {
    if (out == null) writeJsonLine(value, System.out) else saveOwnerFile(Path.of(out), value)
}

/** Machine output is UTF-8 on every OS, independent of the terminal's PrintStream encoding. */
internal fun writeJsonLine(value: JsonObject, output: java.io.OutputStream) {
    output.write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
    output.flush()
}
