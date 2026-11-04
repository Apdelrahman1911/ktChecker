package dev.ktchecker.config

import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A wire proof, not a client-controlled `approved` boolean. */
@Serializable
data class MutationPermit(
    val issuerId: String,
    val principal: ClientIdentity,
    val requestDigest: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String,
    val signature: String,
)

/**
 * Trusted controller integration point. An implementation is trusted code and MUST
 * validate an authenticated explicit owner action, its complete payload and scope.
 * This interface is deliberately not an RPC/tool argument.
 */
fun interface MutationAuthority {
    fun verify(request: ConfigMutationRequest, permit: MutationPermit, now: Instant): ClientIdentity

    companion object {
        val DENY_ALL = MutationAuthority { _, _, _ ->
            throw ConfigAuthorizationException("Only an explicit authorized owner action may change configuration.")
        }
    }
}

/** Owner session enrollment comes from a trusted controller, not request JSON. */
data class OwnerSessionRegistration(
    val principal: ClientIdentity,
    val bindings: Set<ConfigBinding>,
)

@Serializable
private data class PermitPayload(
    val issuerId: String,
    val principal: ClientIdentity,
    val requestDigest: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val nonce: String,
)

/**
 * A small controller/explicit-owner-CLI authority. Keep this object and its secret
 * outside model/project execution capabilities. Same-UID unrestricted shell can
 * steal a locally stored secret; HMAC and file permissions are NOT a sandbox.
 * Models and ordinary review clients must never be enrolled as owner sessions.
 */
class HmacOwnerAuthority(
    secret: ByteArray,
    registrations: Collection<OwnerSessionRegistration>,
    private val issuerId: String = "owner-control/1",
) : MutationAuthority {
    private val key = secret.copyOf()
    private val ownerSessions: Map<ClientIdentity, Set<ConfigBinding>> = registrations.associate { registration ->
        registration.principal to registration.bindings.toSet()
    }

    init {
        require(key.size >= 32) { "Owner control requires at least a 256-bit secret." }
        require(issuerId.isNotBlank()) { "An issuer ID is required." }
        require(registrations.map { it.principal }.distinct().size == registrations.size) { "Duplicate owner session enrollment." }
        registrations.forEach { registration ->
            require(registration.principal.ownerId.isNotBlank() && registration.principal.clientId.isNotBlank() &&
                registration.principal.sessionId.isNotBlank()) { "Owner session identity must be complete." }
            require(registration.bindings.isNotEmpty()) { "An owner session needs explicit authorized bindings." }
            require(registration.bindings.all { it.ownerId == registration.principal.ownerId }) { "Cross-owner enrollment is prohibited." }
        }
    }

    /** Call only after the trusted UI/controller has identified an explicit owner action. */
    fun issueForExplicitOwnerAction(
        principal: ClientIdentity,
        request: ConfigMutationRequest,
        now: Instant = Instant.now(),
        validFor: Duration = Duration.ofMinutes(5),
    ): MutationPermit {
        requireEnrolled(principal, request.binding)
        val validity = validFor.toMillis()
        if (validity !in 1..MAX_VALIDITY_MILLIS) {
            throw ConfigAuthorizationException("Owner permits must expire within five minutes.")
        }
        val issuedAt = now.toEpochMilli()
        val expiresAt = try { Math.addExact(issuedAt, validity) } catch (_: ArithmeticException) {
            throw ConfigAuthorizationException("Invalid permit expiration.")
        }
        val payload = PermitPayload(issuerId, principal, request.digest(), issuedAt, expiresAt, UUID.randomUUID().toString())
        return MutationPermit(
            payload.issuerId, payload.principal, payload.requestDigest,
            payload.issuedAtEpochMillis, payload.expiresAtEpochMillis, payload.nonce, signature(payload),
        )
    }

    override fun verify(request: ConfigMutationRequest, permit: MutationPermit, now: Instant): ClientIdentity {
        requireEnrolled(permit.principal, request.binding)
        if (permit.issuerId != issuerId || permit.requestDigest != request.digest()) {
            throw ConfigAuthorizationException("Owner permit does not authorize this exact configuration request.")
        }
        val time = now.toEpochMilli()
        val validity = try { Math.subtractExact(permit.expiresAtEpochMillis, permit.issuedAtEpochMillis) } catch (_: ArithmeticException) { -1 }
        if (time < permit.issuedAtEpochMillis || time >= permit.expiresAtEpochMillis || validity !in 1..MAX_VALIDITY_MILLIS) {
            throw ConfigAuthorizationException("Owner permit is expired or not yet valid.")
        }
        if (permit.nonce.length !in 1..128 || permit.signature.length > 128) {
            throw ConfigAuthorizationException("Malformed owner permit.")
        }
        val payload = PermitPayload(
            permit.issuerId, permit.principal, permit.requestDigest,
            permit.issuedAtEpochMillis, permit.expiresAtEpochMillis, permit.nonce,
        )
        val expected = signature(payload).toByteArray(StandardCharsets.US_ASCII)
        val actual = permit.signature.toByteArray(StandardCharsets.US_ASCII)
        if (!MessageDigest.isEqual(expected, actual)) {
            throw ConfigAuthorizationException("Owner permit signature is invalid.")
        }
        return permit.principal
    }

    private fun requireEnrolled(principal: ClientIdentity, binding: ConfigBinding) {
        if (principal.ownerId != binding.ownerId || binding !in ownerSessions[principal].orEmpty()) {
            throw ConfigAuthorizationException("This principal/session has no owner authority for this project, worktree and lineage.")
        }
    }

    private fun signature(payload: PermitPayload): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val canonical = Digests.canonical(ContractJson.encodeToJsonElement(payload)).toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical))
    }

    private companion object { const val MAX_VALIDITY_MILLIS = 300_000L }
}
