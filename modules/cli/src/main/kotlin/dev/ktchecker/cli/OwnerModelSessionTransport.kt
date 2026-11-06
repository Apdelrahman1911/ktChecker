package dev.ktchecker.cli

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.kernel.ContextReceipt
import dev.ktchecker.kernel.ObservationReceipt
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.Reference
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

internal data class OwnerModelTransportCliSelection(val request: Path, val digest: String)

/** Pure option validation before streams, selected-file reads, directories or owner construction. */
internal fun ownerModelTransportOptions(options: Arguments): OwnerModelTransportCliSelection {
    ownerModelRequire(options.words == listOf("owner-model-session") &&
        options.hasOnly(setOf("owner-action", "authorize-execution", "request", "selection-digest")) &&
        options.has("owner-action") && options.has("authorize-execution"), "owner_transport_explicit_options_required")
    val request = Path.of(options.required("request"))
    val digest = options.required("selection-digest")
    ownerModelRequire(request.isAbsolute && request.normalize() == request && request.parent != null && Digests.valid(digest),
        "owner_transport_selection_argument_invalid")
    return OwnerModelTransportCliSelection(request, digest)
}

/** Actual entry, not ordinary RPC or a factory supplied by a model. The additional JVM startup
 * cap begins BEFORE setup. The host has already started its original120 BEFORE file IO/spawn.
 * Invalid options throw before effects. After owner setup is ATTEMPTED this NEVER returns to
 * Main.exitProcess. Only the monotone gate's proven pre-owner denial may return terminal78.
 */
internal fun runOwnerModelSession(options: Arguments): Int {
    val startup = OwnedApplicationDeadline.afterMillis(OWNER_MODEL_ORIGINAL_MILLIS) // FIRST statement.
    val selection = ownerModelTransportOptions(options)
    ownerModelCheckpoint(startup)
    // PrintStream swallows IO failures. Own raw descriptors, never close them as cleanup/signals.
    val transport = OwnerModelSessionTransport(selection, startup,
        FileInputStream(FileDescriptor.`in`), FileOutputStream(FileDescriptor.out))
    return transport.serve()
}

/** Strict DATA detachment/roundtrip, no authority constructors and no external callbacks. Missing
 * defaults, numeric strings, extra fields and coercions may not silently rewrite selected DATA.
 */
internal inline fun <reified T> ownerModelTransportExact(value: JsonElement, maximum: Int = OWNER_MODEL_REQUEST_BYTES): T {
    val bytes = ownerModelStrictBytes(value)
    ownerModelRequire(bytes.size in 1..maximum, "owner_transport_parameter_bound")
    val copied = ContractJson.decodeFromString<T>(strictJsonDocument(bytes, "owner_transport_parameter_invalid"))
    ownerModelRequire(ContractJson.encodeToJsonElement(copied) == value, "owner_transport_parameter_not_exact")
    return copied
}

/** Compare JSON as DATA, then return the SAME already-retained original credential object.
 * No JSON-to-ClientCredential/AdapterCredential decode path is used by this private bridge.
 */
internal inline fun <reified T : Any> ownerModelTransportOriginalCredential(params: JsonObject, original: T): T {
    ownerModelRequire(params["credential"] == ContractJson.encodeToJsonElement(original), "owner_transport_credential_mismatch")
    return original
}

/** One real Workbench/registration per NEW launch. The reader and observer know only local
 * framed DATA/denial state, never call owner/provider/config functions and never own its gate.
 * All lifetime primitives, retention references and callbacks exist BEFORE any application.
 */
internal class OwnerModelSessionTransport(
    private val cli: OwnerModelTransportCliSelection,
    startup: OwnedApplicationDeadline,
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val random = SecureRandom()
    private val session = randomToken()
    private val usedTokens = mutableSetOf(session) // No collision retry or cross-role nonce reuse.
    private val state = OwnerModelTransportState(session, startup)
    private val preowner = OwnerModelTransportPreownerGate() // Preallocated, never reset or inferred from owner nullness.
    private val retainedOwner = AtomicReference<OwnerModelCommandSession?>()
    private val idle = CountDownLatch(1) // NEVER released; no allocation on the uncertainty path.
    private val observerTick = CountDownLatch(1)
    private var prepared: OwnerModelTransportPreparedSelection? = null
    private var overall: OwnerModelTransportPhase? = null
    private var firstExport: OwnerModelTransportPhase? = null
    private var policyAttempted = false
    private var configured = false
    private var submitted = 0L
    private val patchIds = mutableSetOf<String>() // Failed admitted patches remain tombstones.
    private val handles = linkedMapOf<String, OwnerModelDeliveryHandle>() // <=32, never evicted.
    private val pump = Thread({ state.pump(input) }, "ktchecker-owner-session-reader").apply { isDaemon = true }
    private val observer = Thread({ observeOriginalDenials() }, "ktchecker-owner-session-deadlines").apply { isDaemon = true }

    fun serve(): Int {
        try {
            try {
                pump.start(); observer.start()
                OwnerModelTransportFrames.write(output, OwnerModelTransportFrames.encode(buildJsonObject {
                    put("kind", "hello"); put("protocol", OWNER_MODEL_TRANSPORT_PROTOCOL); put("session", session)
                    put("profile", OWNER_MODEL_LINEAGE_PROFILE); put("maxFrameBytes", OWNER_MODEL_TRANSPORT_FRAME_BYTES)
                }), state)
                while (true) {
                    val request = state.take()
                    val result = dispatch(request)
                    // dispatch retained the ACTUAL returned object before producing any result DATA.
                    ownerModelTransportRespond(state, request, output, result)
                }
            } catch (_: Throwable) {
                // No failure-frame retry: its previous delivery may itself be unknown. The peer's
                // original caps deny waits; a successful partial write/EOF is NOT a cleanup receipt.
                try {
                    state.poison("owner_transport_outcome_unknown")
                    // Same gate as the first setup statement below. Only untouched framing may
                    // return to Main's existing exitProcess(78); reader/observer are daemon DATA
                    // threads. A lost/partial setup with retainedOwner still null NEVER qualifies.
                    if (preowner.claimTerminalBeforeOwner()) return OWNER_MODEL_TRANSPORT_PREOWNER_EXIT
                    retainedOwner.get()?.quarantine() // Worker only; after the owner operation unwinds.
                } catch (_: Throwable) { /* Keep the owner-bearing lifetime stack, even if quarantine fails. */ }
            }
            idleForever()
        } finally {
            // Also covers an indefinitely blocked writer or quarantine. JIT stack liveness cannot
            // discard this object's actual owner/current/queued/original returned-object references.
            Reference.reachabilityFence(this)
        }
    }

    private fun idleForever(): Nothing {
        while (true) { try { idle.await() } catch (_: Throwable) { /* No cleanup, retirement, signal or retry. */ } }
    }
    private fun observeOriginalDenials() {
        try {
            while (!state.poisoned) {
                state.observeDeadlines()
                observerTick.await(25L, TimeUnit.MILLISECONDS) // Sampling only; no refreshed phase budget.
            }
        } catch (_: Throwable) { state.poison("owner_transport_original_cap_expired") }
    }
    private fun randomToken(): String = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))
    private fun token(): String = randomToken().also {
        ownerModelRequire(usedTokens.add(it), "owner_transport_nonce_collision")
    }
    private fun challenge(request: OwnerModelTransportRequest, kind: OwnerModelTransportClockKind, ordinal: Long): OwnerModelTransportPhase {
        val phase = state.challenge(request, kind, ordinal, token())
        OwnerModelTransportFrames.write(output, OwnerModelTransportFrames.encode(phase.challengeFrame(session)), state)
        state.awaitClock(phase)
        return phase
    }
    private fun owner(): OwnerModelCommandSession = retainedOwner.get()
        ?: throw CliProblem("owner_transport_open_required", "The exact original owner has not been opened.")

    private fun dispatch(request: OwnerModelTransportRequest): JsonObject {
        state.observeDeadlines()
        if (request.method == "owner.open") return open(request)
        val owner = owner()
        // Broker/status is overall/channel-only. NEVER touch an old model/export/review clock
        // merely to audit it; the TS runner independently retains its active original300 wait cap.
        state.fence(request, listOf(requireNotNull(overall)))
        return when (request.method) {
            "owner.configureCompilerPolicy" -> configure(request, owner)
            "owner.submitCompletedPatch" -> submit(request, owner)
            "owner.status" -> {
                ownerModelTransportKeys(request.params, setOf("handle"))
                val id = ownerModelTransportString(request.params, "handle")
                val handle = handles[id] ?: throw CliProblem("owner_transport_handle_unknown", "No exact original delivery handle is retained.")
                state.observeDeadlines()
                val actual = owner.status(handle)
                request.returned.set(actual) // BEFORE status projection/serialization or a late denial.
                buildJsonObject {
                    put("parent", ContractJson.encodeToJsonElement(actual.parent))
                    put("children", ContractJson.encodeToJsonElement(actual.children))
                }
            }
            "review.events" -> {
                ownerModelRequire(request.params.keys.all { it in setOf("credential", "cursor", "limit") } &&
                    "credential" in request.params, "owner_transport_frame_fields")
                val credential = ownerModelTransportOriginalCredential(request.params, owner.registration.client)
                val cursor = when (request.params["cursor"]) {
                    null, JsonNull -> null
                    else -> ownerModelTransportString(request.params, "cursor")
                }
                val limit = if ("limit" in request.params) ownerModelTransportInteger(request.params, "limit", 1, 500).toInt() else 100
                state.observeDeadlines()
                val actual = owner.broker.events(credential, cursor, limit)
                request.returned.set(actual)
                ContractJson.encodeToJsonElement(actual).jsonObject
            }
            "review.ack" -> {
                ownerModelTransportKeys(request.params, setOf("credential", "eventIds", "receipt"))
                val credential = ownerModelTransportOriginalCredential(request.params, owner.registration.adapter)
                val ids = eventIds(request.params)
                val receipt = ownerModelTransportExact<ContextReceipt>(request.params.getValue("receipt"))
                state.observeDeadlines()
                val actual = owner.broker.acknowledge(credential, ids, receipt)
                request.returned.set(actual)
                ContractJson.encodeToJsonElement(actual).jsonObject
            }
            "review.observe" -> {
                ownerModelTransportKeys(request.params, setOf("credential", "eventIds", "receipt"))
                val credential = ownerModelTransportOriginalCredential(request.params, owner.registration.adapter)
                val ids = eventIds(request.params)
                val receipt = ownerModelTransportExact<ObservationReceipt>(request.params.getValue("receipt"))
                state.observeDeadlines()
                val actual = owner.broker.observe(credential, ids, receipt)
                request.returned.set(actual)
                ContractJson.encodeToJsonElement(actual).jsonObject
            }
            "gate.prepare" -> {
                ownerModelTransportKeys(request.params, setOf("credential"))
                val credential = ownerModelTransportOriginalCredential(request.params, owner.registration.client)
                state.observeDeadlines()
                val actual = owner.broker.prepareGate(credential)
                request.returned.set(actual)
                ContractJson.encodeToJsonElement(actual).jsonObject
            }
            "gate.complete" -> {
                ownerModelTransportKeys(request.params, setOf("credential", "cutId"))
                val credential = ownerModelTransportOriginalCredential(request.params, owner.registration.client)
                val cut = ownerModelTransportString(request.params, "cutId")
                state.observeDeadlines()
                // No guarded action, publication or final-answer authority is accepted on this channel.
                val actual = owner.broker.completeGate(credential, cut)
                request.returned.set(actual)
                ContractJson.encodeToJsonElement(actual).jsonObject
            }
            else -> throw CliProblem("owner_transport_method_unavailable", "No such private owner operation.")
        }
    }

    private fun open(request: OwnerModelTransportRequest): JsonObject {
        ownerModelTransportKeys(request.params, emptySet())
        ownerModelRequire(request.id == 1L && retainedOwner.get() == null && prepared == null && overall == null && firstExport == null,
            "owner_transport_open_replayed")
        val overallPhase = challenge(request, OwnerModelTransportClockKind.OVERALL, 0)
        overall = overallPhase
        val exportPhase = challenge(request, OwnerModelTransportClockKind.EXPORT, 1)
        firstExport = exportPhase
        // Irrevocable BEFORE selected-file IO, directory/copy/Workbench effects or actual open.
        // A terminal claimant excludes every queued/racing later setup; failure never rearms it.
        preowner.beginOwnerSetup()
        val choice = OwnerModelTransportPreparedSelection.read(cli.request, cli.digest, state::observeDeadlines)
        prepared = choice
        val actual = OwnerModelCommandSession.openLineage(choice.selection.registration, choice.invocation,
            Path.of(choice.selection.ownerStateDirectory), originalOverall = overallPhase,
            invocationRoot = Path.of(choice.selection.invocationRoot), originalExportFence = exportPhase)
        retainedOwner.set(actual) // FIRST: retain the ACTUAL owner before ANY image/copy check/projection.
        request.returned.set(actual)
        actual.bindTransportInstallation(choice) // Actual runtime installation, not a request echo.
        state.observeDeadlines()
        return buildJsonObject { put("profile", actual.profile); put("registration", ContractJson.encodeToJsonElement(actual.registration)) }
    }

    private fun configure(request: OwnerModelTransportRequest, owner: OwnerModelCommandSession): JsonObject {
        ownerModelRequire(!policyAttempted && !configured && submitted == 0L, "owner_transport_policy_replayed_or_late")
        policyAttempted = true
        val selection = ownerModelTransportExact<OwnerModelCompilerPolicySelection>(request.params, 131_072)
        state.fence(request, listOf(requireNotNull(overall), requireNotNull(firstExport)))
        state.observeDeadlines()
        val actual = owner.configureCompilerPolicy(selection) // Separate explicit owner-policy action.
        request.returned.set(actual)
        configured = true
        return actual
    }

    private fun submit(request: OwnerModelTransportRequest, owner: OwnerModelCommandSession): JsonObject {
        ownerModelTransportKeys(request.params, setOf("patch", "selection"))
        // Detach the ENTIRE completed caller DATA before any challenge/IO/installation check.
        val decoded = ownerModelTransportExact<OwnerModelCompletedPatch>(request.params.getValue("patch"))
        val patch = decoded.copy(roots = Collections.unmodifiableList(decoded.roots.toList()),
            files = Collections.unmodifiableList(decoded.files.toList()))
        val rawSelection = ownerModelTransportExact<OwnerModelInvocationSelection>(request.params.getValue("selection"))
        val selection = rawSelection.copy(compilerArtifactPaths = Collections.unmodifiableList(rawSelection.compilerArtifactPaths.toList()))
        val choice = requireNotNull(prepared)
        ownerModelRequire(configured && submitted < OWNER_MODEL_MAX_PATCHES && patch.ordinal == submitted + 1L &&
            patch.patchId !in patchIds && choice.sameInvocation(selection), "owner_transport_patch_replayed_or_selection_changed")
        patchIds.add(patch.patchId); submitted++ // One admitted entry, never rearmed on any failure.
        if (submitted == 1L) state.fence(request, listOf(requireNotNull(overall), requireNotNull(firstExport)))
        val review = challenge(request, OwnerModelTransportClockKind.REVIEW, submitted)
        val export = if (submitted == 1L) requireNotNull(firstExport)
            else challenge(request, OwnerModelTransportClockKind.EXPORT, submitted)
        val originalOverall = requireNotNull(overall)
        state.fence(request, listOf(originalOverall, review, export))
        val scope = OwnerModelClockScope(originalOverall, review, originalExport = export)
        scope.checkpoint()
        choice.verifyCopiedArtifacts(scope::checkpoint) // Private copies only; NEVER original seed recapture.
        state.observeDeadlines()
        val actual = owner.submitCompletedPatch(patch, choice.invocation, scope)
        request.returned.set(actual) // BEFORE random handle allocation/registry insert or DATA wrapping.
        val id = token()
        ownerModelRequire(handles.size < OWNER_MODEL_MAX_PATCHES && id !in handles, "owner_transport_handle_bound")
        handles[id] = actual.deliveryHandle // Exact actual object retained in this SAME channel only.
        return buildJsonObject { put("data", actual.data); put("handle", id) }
    }

    private fun eventIds(params: JsonObject): List<String> {
        val values = ownerModelTransportExact<List<String>>(params.getValue("eventIds"))
        ownerModelRequire(values.size in 1..500 && values.distinct().size == values.size, "owner_transport_event_ids_invalid")
        values.forEach(::ownerModelIdentity)
        return Collections.unmodifiableList(values.toList())
    }
}
