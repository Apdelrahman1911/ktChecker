package dev.ktchecker.cli

import java.util.concurrent.atomic.AtomicReference

internal const val OWNER_MODEL_TRANSPORT_PREOWNER_EXIT = 78

/** Local lifecycle proof, not a wire credential or a cleanup/retirement receipt. Only framed
 * DATA/challenges precede setup. Every selected-file/setup attempt must first consume this gate.
 * A failed/partial attempt, missing owner reference or caught Throwable can NEVER reset it.
 * Terminal and setup claims share one CAS, so neither a queued nor racing caller can reopen it.
 */
internal class OwnerModelTransportPreownerGate {
    private enum class State { FRAMING_ONLY, OWNER_SETUP_ATTEMPTED, TERMINAL_BEFORE_OWNER }
    private val state = AtomicReference(State.FRAMING_ONLY)

    fun beginOwnerSetup() {
        ownerModelRequire(state.compareAndSet(State.FRAMING_ONLY, State.OWNER_SETUP_ATTEMPTED),
            "owner_transport_setup_replayed_or_terminal")
    }

    fun claimTerminalBeforeOwner(): Boolean =
        state.compareAndSet(State.FRAMING_ONLY, State.TERMINAL_BEFORE_OWNER)
}
