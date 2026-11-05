package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.RuleContext
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

/** Serialization/identity controls only; synthetic audit facts are NOT completion authority. */
class IntegratedCompletionAuditCodecTest {
    private val defaults = IntegratedCompletionLimits()
    private val codec = IntegratedCompletionAuditCodec(defaults)
    private val id = "joint-v2:00000000-0000-0000-0000-000000000001"

    private fun digest(label: String) = Digests.sha256("synthetic-compact-audit:$label")
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size
    private fun assertCode(code: String, block: () -> Unit) =
        assertEquals(code, assertFailsWith<KernelException>(block = block).code)

    private fun sample(
        contexts: List<RuleContext> = listOf(RuleContext(module = ":a"), RuleContext(module = ":b")),
        rules: List<String> = listOf("rule-a", "rule-b", "rule-c"),
    ): IntegratedCompletionAudit {
        val bindings = listOf("a", "b").map { ClientBinding("owner", "workspace", "tree-$it", it, "lineage-$it", 1, "session-$it") }
        val scope = IntegratedCompletionScope(bindings.first(), "a", "parent-a", digest("snapshot"),
            "route", digest("route"), digest("catalog"), bindings.map {
                IntegratedClientScope(it, "parent-${it.clientId}", digest("plan-${it.clientId}"),
                    digest("snapshot"), digest("source-${it.clientId}"), digest("generated"))
            }, contexts.sortedBy { canonical(it) }.flatMap { context -> rules.sorted().map { IntegratedCoverageRequirement(context, it) } })
        val scopeDigest = integratedScopeDigest(scope)
        val frontiers = bindings.map {
            IntegratedClientFrontier(it.clientId, 7, 2, 4, listOf("accepted-${it.clientId}", "result-${it.clientId}"), digest("history-${it.clientId}"))
        }
        val obligations = bindings.flatMap { binding -> listOf("review", "parent_plan").map { kind ->
            IntegratedPatchObligation(binding.clientId, kind, "$kind-${binding.clientId}", "patch-${binding.clientId}",
                digest("request-${binding.clientId}"), digest("input-or-plan-${binding.clientId}"), digest("snapshot"), 2)
        } }
        val request = IntegratedFenceRequest(scope, scopeDigest, frontiers, obligations,
            listOf(IntegratedRouteIdentity("route", digest("route"))))
        return IntegratedCompletionAudit(IntegratedCompletionAuditCodec.CURRENT_FORMAT,
            IntegratedCompletionCut(id, "completion", scope, scopeDigest, true, emptyList()),
            digest("registration"), digest("action"), request,
            IntegratedSnapshotCheck(reasons = listOf("synthetic-audit-is-not-live-authority")))
    }

    private fun scope(value: IntegratedCompletionAudit, replacement: IntegratedCompletionScope): IntegratedCompletionAudit {
        val digest = integratedScopeDigest(replacement)
        return value.copy(cut = value.cut.copy(scope = replacement, scopeDigest = digest),
            request = value.request.copy(scope = replacement, scopeDigest = digest))
    }

    private fun receipt(value: IntegratedCompletionAudit) =
        IntegratedActionReceipt(value.cut.cutId, value.cut.scopeDigest, digest("verified-receipt"))

    private fun root(encoded: String) = ContractJson.parseToJsonElement(encoded).jsonObject
    private fun replacing(value: JsonObject, key: String, element: JsonElement): JsonObject =
        JsonObject(value.toMutableMap().also { it[key] = element })
    private fun encoded(value: JsonObject) = Digests.canonical(value)
    private fun matrix(source: String, transform: (JsonObject) -> JsonObject): String {
        val value = root(source)
        return encoded(replacing(value, "scope", transform(value.getValue("scope").jsonObject)))
    }

    @Test fun compactRoundTripRetainsExactFull1663ScopeAndAllHistoryWithoutChangingSemanticDigest() {
        val resource = assertNotNull(javaClass.getResourceAsStream("/joint-completion-full-catalog-1663.txt")).use { it.readBytes() }
        assertEquals("sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", Digests.sha256(resource))
        val rules = resource.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
        assertEquals(1663, rules.size); assertEquals(rules.distinct().sorted(), rules)
        val contexts = (0 until 9).map { RuleContext(module = ":features:capacity-module-$it") }
        val value = sample(contexts, rules)
        val compact = codec.encode(value)
        assertTrue(bytes(canonical(value)) > defaults.maxAuditBytes) // Old doubled scope cannot fit.
        assertTrue(bytes(compact) < defaults.maxAuditBytes)
        val decoded = codec.decode(id, compact)
        assertEquals(value, decoded)
        assertEquals(14967, decoded.cut.scope.requiredCoverage.size)
        assertEquals(value.cut.scopeDigest, integratedScopeDigest(decoded.cut.scope))
        assertEquals(value.request.historicalObligations, decoded.request.historicalObligations)
        assertEquals(value.request.frontiers, decoded.request.frontiers)
        assertEquals(value.request.routes, decoded.request.routes)
        val objectValue = root(compact)
        assertFalse("scope" in objectValue.getValue("cut").jsonObject)
        assertFalse("scope" in objectValue.getValue("request").jsonObject)
        val matrix = objectValue.getValue("scope").jsonObject
        assertEquals("all_declared_contexts_x_full_catalog", matrix.getValue("coverageMode").jsonPrimitive.content)
        assertEquals(9, matrix.getValue("contexts").jsonArray.size)
        assertEquals(rules, matrix.getValue("ruleIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun encodingRejectsReorderedMissingDuplicateAndNonCartesianCoverageInsteadOfNormalizing() {
        val value = sample()
        val pairs = value.cut.scope.requiredCoverage
        val variants = listOf(pairs.reversed(), pairs.dropLast(1), pairs + pairs.first(),
            pairs.toMutableList().also { it[1] = pairs.first() },
            pairs.toMutableList().also { it[0] = pairs[1]; it[1] = pairs[0] },
            pairs.toMutableList().also { it[0] = it[0].copy(ruleId = "unrequested-rule") })
        variants.forEach { coverage ->
            // Recompute a valid digest so a matrix/order error, not a stale digest, must reject.
            val changed = scope(value, value.cut.scope.copy(requiredCoverage = coverage))
            assertCode("joint_audit_invalid") { codec.encode(changed) }
        }
        assertCode("joint_history_limit") { codec.encode(scope(value, value.cut.scope.copy(requiredCoverage = emptyList()))) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(request = value.request.copy(scopeDigest = digest("other")))) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(request = value.request.copy(scope = value.cut.scope.copy(integrationClientId = "b")))) }
    }

    @Test fun strictVersionedDecoderRejectsUnknownDuplicateOmittedAndAlteredEncoding() {
        val compact = codec.encode(sample())
        val value = root(compact)
        listOf(
            encoded(replacing(value, "format", JsonPrimitive("ktchecker/joint-completion-cut/3"))),
            encoded(replacing(value, "format", JsonPrimitive(2))),
            encoded(JsonObject(value - "format")),
            encoded(replacing(value, "unknown", JsonPrimitive(true))),
            " "+compact,
            "{\"format\":\"${IntegratedCompletionAuditCodec.CURRENT_FORMAT}\","+compact.drop(1),
            matrix(compact) { JsonObject(it - "coverageMode") },
            matrix(compact) { replacing(it, "coverageMode", JsonPrimitive("selected_pairs_only")) },
            matrix(compact) { replacing(it, "contexts", JsonArray(it.getValue("contexts").jsonArray.reversed())) },
            matrix(compact) { replacing(it, "ruleIds", JsonArray(it.getValue("ruleIds").jsonArray.reversed())) },
            matrix(compact) { replacing(it, "contexts", JsonArray(emptyList())) },
            matrix(compact) { replacing(it, "ruleIds", JsonArray(emptyList())) },
            matrix(compact) { replacing(it, "contexts", JsonArray(it.getValue("contexts").jsonArray + it.getValue("contexts").jsonArray.first())) },
            matrix(compact) { replacing(it, "ruleIds", JsonArray(it.getValue("ruleIds").jsonArray + it.getValue("ruleIds").jsonArray.first())) },
            matrix(compact) { replacing(it, "catalogDigest", JsonPrimitive(digest("different-catalog"))) },
            matrix(compact) { replacing(it, "requiredCoverage", JsonArray(emptyList())) },
        ).forEach { changed -> assertCode("joint_audit_invalid") { codec.decode(id, changed) } }
        // The requested key version selects exactly one serializer; embedded identity cannot
        // reroute the read to another version or another valid cut. There is no fallback.
        val otherId = id.dropLast(1) + "2"
        val wrongCut = encoded(replacing(value, "cut",
            replacing(value.getValue("cut").jsonObject, "cutId", JsonPrimitive(otherId))))
        assertCode("joint_audit_invalid") { codec.decode(id, wrongCut) }
        assertCode("joint_audit_invalid") { codec.decode(otherId, compact) }
        assertCode("joint_audit_invalid") { codec.decode(id.replace("joint-v2:", "joint-v1:"), compact) }
        assertCode("joint_cut_not_found") { codec.decode(id.replace("joint-v2:", "joint-v3:"), compact) }
        assertEquals(sample(), codec.decode(id, compact)) // A rejected variant never alters the source.
    }

    @Test fun reconstructedRequestByteLimitIsExactForEscapingAndUnicodeBeforePairMaterialization() {
        val contexts = listOf(RuleContext(target = "", module = ":feature/\"east\\west/東京"),
            RuleContext(sourceSet = "máin", module = ":another-🌍"), RuleContext(module = ":last"))
        val rules = (0 until 100).map { "rule-${it.toString().padStart(3, '0')}-\"\\é" }
        val value = sample(contexts, rules)
        val compact = codec.encode(value)
        val requestBytes = bytes(canonical(value.request))
        val terminalBytes = bytes(codec.encode(value.copy(state = "outcome_unknown", receipt = receipt(value))))
        assertTrue(terminalBytes < requestBytes)
        val exact = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = requestBytes))
        assertEquals(value, exact.decode(id, exact.encode(value)))
        val below = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = requestBytes - 1))
        assertCode("joint_audit_limit") { below.decode(id, compact) }
        assertCode("joint_audit_limit") { below.encode(value) }

        // Small dictionary, huge implied byte expansion. A scope-digest mismatch would be a
        // different error: the BYTE preflight must reject before expanding/hashing that scope.
        val bomb = matrix(compact) { original ->
            val large = listOf(RuleContext(module = ":a" + "x".repeat(180_000)),
                RuleContext(module = ":b" + "x".repeat(180_000))).sortedBy { canonical(it) }
            replacing(replacing(original, "contexts", ContractJson.encodeToJsonElement(large)),
                "ruleIds", JsonArray((0 until 15).map { JsonPrimitive("rule-${it.toString().padStart(2, '0')}") }))
        }
        assertTrue(bytes(bomb) < defaults.maxAuditBytes)
        assertCode("joint_audit_limit") { codec.decode(id, bomb) }
    }

    @Test fun compactInputStillHasUnchangedEncodedAndCartesianPairCeilings() {
        assertEquals(65_536, defaults.maxCoveragePairs)
        assertEquals(4 * 1024 * 1024, defaults.maxAuditBytes)
        assertEquals(32L * 1024 * 1024, defaults.maxHistoryBytes)
        val value = sample()
        val compact = codec.encode(value)
        val bytesLimited = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = bytes(compact) - 1))
        assertCode("joint_audit_limit") { bytesLimited.decode(id, compact) }
        val pairsLimited = IntegratedCompletionAuditCodec(defaults.copy(maxCoveragePairs = 5))
        assertCode("joint_history_limit") { pairsLimited.encode(value) }
        assertCode("joint_history_limit") { pairsLimited.decode(id, compact) }
        val tooManyPairs = matrix(compact) { replacing(it, "ruleIds",
            JsonArray((0 until 32_769).map { index -> JsonPrimitive("rule-${index.toString().padStart(5, '0')}") })) }
        assertTrue(bytes(tooManyPairs) < defaults.maxAuditBytes)
        assertCode("joint_history_limit") { codec.decode(id, tooManyPairs) }
        val tooManyRules = matrix(compact) { replacing(replacing(it, "contexts",
            JsonArray(it.getValue("contexts").jsonArray.take(1))), "ruleIds",
            JsonArray((0 until 50_001).map { index -> JsonPrimitive("rule-${index.toString().padStart(5, '0')}") })) }
        assertCode("joint_history_limit") { codec.decode(id, tooManyRules) }

        // Each {} cheaply decodes to a full default RuleContext. Cardinality must reject
        // BEFORE canonical reencoding expands those defaults (which would instead report
        // noncanonical bytes), and before any Cartesian vector is materialized.
        val defaultContextBomb = matrix(compact) { replacing(replacing(it, "contexts",
            JsonArray(List(65_537) { JsonObject(emptyMap()) })), "ruleIds", JsonArray(listOf(JsonPrimitive("rule")))) }
        assertTrue(bytes(defaultContextBomb) < defaults.maxAuditBytes)
        assertCode("joint_history_limit") { codec.decode(id, defaultContextBomb) }

        // Persisted checks must obey every original live-callback cardinality cap before
        // their omitted defaults are canonically expanded. No reasons are trimmed to fit.
        val objectValue = root(compact)
        val check = objectValue.getValue("check").jsonObject
        val local = buildJsonObject { put("current", JsonPrimitive(false)) }
        val member = buildJsonObject {
            put("binding", objectValue.getValue("scope").jsonObject.getValue("origin"))
            put("planDigest", JsonPrimitive(digest("check-plan")))
            put("check", local)
        }
        val disposition = buildJsonObject {
            put("obligationDigest", JsonPrimitive(digest("obligation")))
            put("disposition", JsonPrimitive("incorporated"))
            put("evidenceDigest", JsonPrimitive(digest("evidence")))
        }
        val identity = buildJsonObject {
            put("context", JsonObject(emptyMap()))
            listOf("modelDigest", "dependencyDigest", "toolchainDigest", "compilerVersion",
                "compilerArgumentsDigest", "classpathDigest", "librariesDigest", "policiesDigest",
                "compilationFilesDigest", "configurationDigest").forEach { put(it, JsonPrimitive(digest(it))) }
        }
        val excessReasons = JsonArray(List(65) { JsonPrimitive("retained-reason") })
        listOf(
            replacing(check, "members", JsonArray(List(defaults.maxMembers + 1) { member })),
            replacing(check, "patchDispositions", JsonArray(List(defaults.maxReviews + defaults.maxParents + 1) { disposition })),
            replacing(check, "reasons", excessReasons),
            replacing(check, "members", JsonArray(listOf(replacing(member, "check",
                replacing(local, "contextIdentities", JsonArray(List(513) { identity })))))),
            replacing(check, "members", JsonArray(listOf(replacing(member, "check", replacing(local, "reasons", excessReasons))))),
        ).forEach { oversized ->
            val changed = encoded(replacing(objectValue, "check", oversized))
            assertTrue(bytes(changed) < defaults.maxAuditBytes)
            assertCode("joint_attestation_limit") { codec.decode(id, changed) }
        }
    }

    @Test fun preparedAuditReservesLargestTerminalReceiptAndEveryTerminalStateReadsBackWithinSameBound() {
        val value = sample(listOf(RuleContext()), listOf("rule"))
        val prepared = codec.encode(value)
        val terminal = value.copy(state = "outcome_unknown", receipt = receipt(value))
        val terminalBytes = bytes(codec.encode(terminal))
        assertTrue(bytes(prepared) < terminalBytes)
        assertTrue(bytes(canonical(value.request)) < terminalBytes)
        val below = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = terminalBytes - 1))
        assertCode("joint_audit_limit") { below.encode(value) }
        assertCode("joint_audit_limit") { below.decode(id, prepared) }
        val exact = IntegratedCompletionAuditCodec(defaults.copy(maxAuditBytes = terminalBytes))
        listOf(value, value.copy(state = "action_claimed"), value.copy(state = "complete", receipt = receipt(value)),
            value.copy(state = "outcome_unknown"), terminal).forEach {
            val encoded = exact.encode(it)
            assertTrue(bytes(encoded) <= terminalBytes)
            assertEquals(it, exact.decode(id, encoded))
        }
        assertCode("joint_audit_invalid") { codec.encode(terminal.copy(receipt = receipt(value).copy(cutId = "another-cut"))) }
        assertCode("joint_audit_invalid") { codec.encode(terminal.copy(receipt = receipt(value).copy(scopeDigest = digest("other-scope")))) }
        assertCode("joint_audit_invalid") { codec.encode(terminal.copy(receipt = receipt(value).copy(receiptDigest = "invalid"))) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(receipt = receipt(value))) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(state = "action_claimed", receipt = receipt(value))) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(state = "complete")) }
        assertNull(codec.decode(id, prepared).receipt) // The reserve placeholder was NEVER persisted.
    }

    @Test fun legacyCanonicalAuditsRemainReadableAcrossAllStatesWithoutIdentityMigration() {
        val value = sample()
        val legacy = value.copy(format = IntegratedCompletionAuditCodec.LEGACY_FORMAT,
            cut = value.cut.copy(cutId = value.cut.cutId.replace("joint-v2:", "joint-v1:")))
        listOf("prepared", "action_claimed", "outcome_unknown", "complete").forEach { state ->
            val record = legacy.copy(state = state,
                receipt = if (state in setOf("complete", "outcome_unknown")) receipt(legacy) else null)
            val encoded = canonical(record)
            assertEquals(encoded, codec.encode(record))
            assertEquals(record, codec.decode(legacy.cut.cutId, encoded))
            assertTrue(IntegratedCompletionAuditCodec.key(codec.decode(legacy.cut.cutId, encoded).cut.cutId)
                .startsWith(IntegratedCompletionAuditCodec.LEGACY_KEY_PREFIX))
            assertCode("joint_audit_invalid") { codec.decode(id, encoded) }
        }
        assertCode("joint_audit_invalid") { codec.encode(legacy.copy(format = IntegratedCompletionAuditCodec.CURRENT_FORMAT)) }
        assertCode("joint_audit_invalid") { codec.encode(value.copy(format = IntegratedCompletionAuditCodec.LEGACY_FORMAT)) }
        assertCode("joint_cut_not_found") { IntegratedCompletionAuditCodec.key("joint-v3:00000000-0000-0000-0000-000000000001") }
    }
}
