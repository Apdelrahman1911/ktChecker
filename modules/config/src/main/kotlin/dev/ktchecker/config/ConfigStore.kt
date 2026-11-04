package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ClientIdentity
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Policy storage, not a source editor. stateRoot is explicitly chosen broker/control
 * state, outside reviewed sources. Shared settings apply to the registered project;
 * local overrides are isolated by owner, worktree AND lineage. No review API mutates
 * policy, grants authority, runs project code, or treats absence as a verified fix.
 */
class ConfigStore(
    stateRoot: Path,
    private val aliases: RuleAliases = RuleAliases.NONE,
    private val authority: MutationAuthority = MutationAuthority.DENY_ALL,
    identityVerifier: FindingIdentityVerifier = FindingIdentityVerifier.NONE,
    lifecycleVerifier: FindingLifecycleVerifier = FindingLifecycleVerifier.NONE,
    private val clock: Clock = Clock.systemUTC(),
    writeObserver: ConfigWriteObserver = ConfigWriteObserver.NONE,
) {
    private val persistence = ConfigPersistence(stateRoot, writeObserver)
    private val matcher = PolicyMatcher(aliases, identityVerifier, lifecycleVerifier)

    fun storagePaths(binding: ConfigBinding): ConfigStoragePaths {
        validateBinding(binding)
        return persistence.paths(binding)
    }

    fun inspect(binding: ConfigBinding): ConfigSnapshot = capture(binding)

    /** Freeze this policy/digest beside an active review's immutable AnalysisInput. */
    fun capture(binding: ConfigBinding): ConfigSnapshot {
        validateBinding(binding)
        return persistence.withLock(binding) { paths -> loadSnapshot(binding, paths, clock.millis()) }
    }

    /** Serializable archive of the EXACT captured policy, not whatever is current on restart. */
    fun exportSnapshot(snapshot: ConfigSnapshot): FrozenConfig {
        if (snapshot.registryDigest != aliases.digest) throw ConfigValidationException("Cannot archive a snapshot from another rule registry.")
        return FrozenConfig(binding = snapshot.binding, revisionToken = snapshot.revisionToken,
            registryDigest = snapshot.registryDigest, capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
            sharedDocument = snapshot.sharedDocument, localDocument = snapshot.localDocument)
    }

    /** A trusted UI may explicitly export this for version control; review never writes it. */
    fun exportShared(snapshot: ConfigSnapshot): SharedProjectConfig = SharedProjectConfig(
        ruleSettings = immutableList(snapshot.shared.ruleSettings),
        suppressions = immutableList(snapshot.shared.suppressions.map { it.spec }),
        baselines = immutableList(snapshot.shared.baselines.map { it.spec }),
    )

    /**
     * Trusted recovery API, not a model/RPC input. expected must come from the
     * authoritative stored review identity. Content hashes do not authorize edits.
     * A missing old registry/version is explicitly unavailable, never replaced with
     * the latest policy. This performs no current-configuration writes.
     */
    fun restoreSnapshot(frozen: FrozenConfig, expected: ConfigRevisionToken): ConfigSnapshot {
        validateBinding(frozen.binding)
        if (frozen.schemaVersion != 1 || frozen.registryDigest != aliases.digest || frozen.capturedAtEpochMillis < 0 ||
            frozen.revisionToken != expected || expected.binding != frozen.binding) {
            throw ConfigReadException("Captured policy identity/schema/registry is unavailable or mismatched.")
        }
        fun parse(raw: String?): LoadedLayer {
            if (raw == null) return LoadedLayer(null, LayerRevision(0, null, Digests.sha256("ktchecker/config/absent/1")), null)
            val bytes = raw.toByteArray(Charsets.UTF_8)
            if (bytes.size !in 1..(16 * 1024 * 1024)) throw ConfigReadException("Archived policy exceeds its size bound.")
            try {
                val doc = ContractJson.decodeFromString<PolicyDocument>(raw)
                return LoadedLayer(doc, LayerRevision(doc.revision, doc.generationId, Digests.sha256(bytes)), raw)
            } catch (e: Exception) { throw ConfigReadException("Invalid archived policy.", e) }
        }
        val restored = buildSnapshot(frozen.binding, parse(frozen.sharedDocument), parse(frozen.localDocument), frozen.capturedAtEpochMillis)
        if (restored.revisionToken != expected) throw ConfigReadException("Archived policy contents do not match the stored review identity.")
        return restored
    }

    fun findingSelector(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding): PolicySelector.ExactFinding =
        matcher.findingSelector(snapshot, input, finding)

    fun explain(snapshot: ConfigSnapshot, input: AnalysisInput, finding: Finding): SuppressionExplanation = matcher.explain(snapshot, input, finding)

    /** Original status/coverage/failure evidence is retained verbatim. */
    fun apply(snapshot: ConfigSnapshot, input: AnalysisInput, result: ProviderResult): ProviderResult = matcher.apply(snapshot, input, result)

    fun mutate(request: ConfigMutationRequest, permit: MutationPermit): ConfigSnapshot {
        // Detach collection-bearing import payloads before verification/use. A client
        // cannot change mutable lists after a payload-bound permit has been checked.
        val sealed = try { ContractJson.decodeFromString<ConfigMutationRequest>(ContractJson.encodeToString(request)) }
        catch (e: Exception) { throw ConfigValidationException("Configuration request could not be frozen: ${e.javaClass.simpleName}") }
        return mutateSealed(sealed, permit)
    }

    private fun mutateSealed(request: ConfigMutationRequest, permit: MutationPermit): ConfigSnapshot {
        validateRequest(request)
        checkPrincipal(authority.verify(request, permit, clock.instant()), request.binding)
        return persistence.withLock(request.binding) { paths ->
            val now = clock.instant()
            val principal = authority.verify(request, permit, now)
            checkPrincipal(principal, request.binding)
            val current = loadSnapshot(request.binding, paths, now.toEpochMilli())
            if (current.revisionToken != request.expected) throw ConfigConflictException(current.revisionToken)
            val loaded = persistence.load(if (request.layer == ConfigLayer.SHARED) paths.shared else paths.local)
            val old = loaded.document ?: newDocument(request.binding, request.layer)
            if (old.revision >= MAX_AUDIT_EVENTS) throw ConfigValidationException("Configuration history limit reached; explicit migration is required.")
            val changed = change(old, current, request, principal, now)
            val revision = Math.addExact(old.revision, 1)
            val event = ConfigAuditEvent(
                revision, request.requestId, request.digest(), principal, now.toEpochMilli(),
                old.auditEvents.lastOrNull()?.let { eventDigest(it) },
            )
            val committed = changed.copy(revision = revision, auditEvents = old.auditEvents + event)
            validateDocument(committed, request.binding, request.layer)
            persistence.replace(if (request.layer == ConfigLayer.SHARED) paths.shared else paths.local, committed)
            loadSnapshot(request.binding, paths, clock.millis())
        }
    }

    /**
     * Short final-gate fence. Lock order is CONFIG → SOURCE/SNAPSHOT → KERNEL DB.
     * No database transaction should be open when entering this method. Never run
     * analysis or recursive config operations inside block. Read check.matches in
     * the innermost callback: an expiration may pass while acquiring source locks.
     */
    fun <T> withCurrentConfig(binding: ConfigBinding, expected: ConfigRevisionToken, block: (ConfigFenceCheck) -> T): T {
        validateBinding(binding)
        return persistence.withLock(binding) { paths ->
            val current = loadSnapshot(binding, paths, clock.millis())
            val reasons = mutableListOf<String>()
            if (expected.binding != binding) reasons += "configuration_binding_changed"
            if (expected.shared != current.revisionToken.shared) reasons += "shared_configuration_changed"
            if (expected.local != current.revisionToken.local) reasons += "local_configuration_changed"
            if (expected.policyDigest != current.policyDigest) reasons += "effective_policy_changed"
            block(ConfigFenceCheck(current, reasons.isEmpty(), immutableList(reasons), clock))
        }
    }

    /** Cooperative revision-only convenience; full tokens are required for strict gates. */
    fun <T> withCurrentConfig(binding: ConfigBinding, expectedSharedRev: Long, expectedLocalRev: Long, block: (ConfigFenceCheck) -> T): T {
        validateBinding(binding)
        return persistence.withLock(binding) { paths ->
            val current = loadSnapshot(binding, paths, clock.millis())
            val reasons = mutableListOf<String>()
            if (expectedSharedRev != current.shared.revision.revision) reasons += "shared_configuration_changed"
            if (expectedLocalRev != current.local.revision.revision) reasons += "local_configuration_changed"
            block(ConfigFenceCheck(current, reasons.isEmpty(), immutableList(reasons), clock))
        }
    }

    private fun change(old: PolicyDocument, current: ConfigSnapshot, request: ConfigMutationRequest,
                       principal: ClientIdentity, now: Instant): PolicyDocument = when (val action = request.action) {
        is ConfigAction.AddSuppression -> {
            val spec = normalizeSuppression(action.spec, request.binding, request.layer, true)
            requireFutureExpiration(spec.expiresAtEpochMillis, now)
            if (old.suppressions.any { it.spec.id == spec.id }) throw ConfigValidationException("Suppression ID already exists in this layer.")
            old.copy(suppressions = (old.suppressions + SuppressionRecord(spec, now.toEpochMilli(), principal)).sortedBy { it.spec.id })
        }
        is ConfigAction.RemoveSuppression -> {
            validateIdentifier(action.id, "suppression")
            if (old.suppressions.none { it.spec.id == action.id }) throw ConfigValidationException("Suppression is absent from this layer; masking shared is a separate explicit action.")
            old.copy(suppressions = old.suppressions.filterNot { it.spec.id == action.id })
        }
        is ConfigAction.MaskSharedSuppression -> {
            requireLocal(request.layer)
            validateIdentifier(action.id, "suppression")
            if (current.shared.suppressions.none { it.spec.id == action.id }) throw ConfigValidationException("No inherited shared suppression has this ID.")
            if (action.id in old.suppressionMasks) throw ConfigValidationException("Shared suppression is already masked.")
            old.copy(suppressionMasks = (old.suppressionMasks + action.id).sorted())
        }
        is ConfigAction.UnmaskSharedSuppression -> {
            requireLocal(request.layer)
            if (action.id !in old.suppressionMasks) throw ConfigValidationException("No such shared suppression mask.")
            old.copy(suppressionMasks = old.suppressionMasks.filterNot { it == action.id })
        }
        is ConfigAction.SetRuleEnabled -> {
            val rule = aliases.requireRule(action.ruleId)
            if (!action.enabled && (!rule.disablingAllowed || rule.failureProtected)) throw ConfigValidationException("This rule cannot be disabled.")
            if (old.ruleSettings.any { it.ruleId == rule.canonicalId && it.enabled == action.enabled }) throw ConfigValidationException("Rule setting is unchanged.")
            old.copy(ruleSettings = (old.ruleSettings.filterNot { it.ruleId == rule.canonicalId } + RuleSetting(rule.canonicalId, action.enabled)).sortedBy { it.ruleId })
        }
        is ConfigAction.ResetRuleOverride -> {
            val canonicalId = aliases.requireRule(action.ruleId).canonicalId
            if (old.ruleSettings.none { it.ruleId == canonicalId }) throw ConfigValidationException("No rule override exists in this layer.")
            old.copy(ruleSettings = old.ruleSettings.filterNot { it.ruleId == canonicalId })
        }
        is ConfigAction.AddBaseline -> {
            val selector = normalizeSelector(action.spec.selector, request.binding, request.layer, true) as PolicySelector.ExactFinding
            validateJustification(action.spec.justification)
            validateIdentifier(action.spec.id, "baseline")
            requireFutureExpiration(action.spec.expiresAtEpochMillis, now)
            val rule = aliases.requireRule(selector.ruleId)
            if (!rule.suppressionAllowed || rule.failureProtected) throw ConfigValidationException("This rule cannot be baselined.")
            if (old.baselines.any { it.spec.id == action.spec.id }) throw ConfigValidationException("Baseline ID already exists in this layer.")
            old.copy(baselines = (old.baselines + BaselineRecord(action.spec.copy(selector = selector), now.toEpochMilli(), principal)).sortedBy { it.spec.id })
        }
        is ConfigAction.RemoveBaseline -> {
            if (old.baselines.none { it.spec.id == action.id }) throw ConfigValidationException("No such baseline in this layer.")
            old.copy(baselines = old.baselines.filterNot { it.spec.id == action.id })
        }
        is ConfigAction.ImportSharedConfig -> {
            if (request.layer != ConfigLayer.SHARED || action.config.schemaVersion != 1) throw ConfigValidationException("Only a supported shared project config can be imported.")
            val imported = action.config
            if (imported.ruleSettings.size > MAX_ENTRIES || imported.suppressions.size > MAX_ENTRIES || imported.baselines.size > MAX_ENTRIES) {
                throw ConfigValidationException("Shared project configuration entry limit exceeded.")
            }
            val settings = imported.ruleSettings.map { setting ->
                val rule = aliases.requireRule(setting.ruleId)
                if (!setting.enabled && (!rule.disablingAllowed || rule.failureProtected)) throw ConfigValidationException("Imported rule cannot be disabled.")
                setting.copy(ruleId = rule.canonicalId)
            }.sortedBy { it.ruleId }
            val suppressions = imported.suppressions.map { normalizeSuppression(it, request.binding, request.layer, true) }.sortedBy { it.id }
            val baselines = imported.baselines.map { spec ->
                validateIdentifier(spec.id, "baseline"); validateJustification(spec.justification); validateExpiration(spec.expiresAtEpochMillis)
                val selector = normalizeSelector(spec.selector, request.binding, request.layer, true) as PolicySelector.ExactFinding
                val rule = aliases.requireRule(selector.ruleId)
                if (!rule.suppressionAllowed || rule.failureProtected) throw ConfigValidationException("Imported rule cannot be baselined.")
                spec.copy(selector = selector)
            }.sortedBy { it.id }
            requireUnique(settings.map { it.ruleId }, "imported rule overrides")
            requireUnique(suppressions.map { it.id }, "imported suppressions")
            requireUnique(baselines.map { it.id }, "imported baselines")
            if (settings == old.ruleSettings && suppressions == old.suppressions.map { it.spec } && baselines == old.baselines.map { it.spec }) {
                throw ConfigValidationException("Shared project configuration is unchanged.")
            }
            old.copy(ruleSettings = settings, suppressions = suppressions.map { SuppressionRecord(it, now.toEpochMilli(), principal) },
                baselines = baselines.map { BaselineRecord(it, now.toEpochMilli(), principal) })
        }
    }

    private fun loadSnapshot(binding: ConfigBinding, paths: ConfigStoragePaths, now: Long): ConfigSnapshot {
        val shared = persistence.load(paths.shared)
        val local = persistence.load(paths.local)
        return buildSnapshot(binding, shared, local, now)
    }

    private fun buildSnapshot(binding: ConfigBinding, shared: LoadedLayer, local: LoadedLayer, now: Long): ConfigSnapshot {
        shared.document?.let { validateDocument(it, binding, ConfigLayer.SHARED) }
        local.document?.let { validateDocument(it, binding, ConfigLayer.LOCAL) }
        val sharedView = inspection(shared, ConfigLayer.SHARED)
        val localView = inspection(local, ConfigLayer.LOCAL)
        val localIds = localView.suppressions.map { it.spec.id }.toSet()
        val masks = localView.suppressionMasks.toSet()
        val resolved = sharedView.suppressions.map { record ->
            ResolvedSuppression(record, ConfigLayer.SHARED, when {
                record.spec.id in masks -> "masked"
                record.spec.id in localIds -> "overridden"
                expired(record.spec.expiresAtEpochMillis, now) -> "expired"
                else -> "active"
            })
        } + localView.suppressions.map { record ->
            ResolvedSuppression(record, ConfigLayer.LOCAL, if (expired(record.spec.expiresAtEpochMillis, now)) "expired" else "active")
        }
        val localBaselineIds = localView.baselines.map { it.spec.id }.toSet()
        val baselines = sharedView.baselines.map { ResolvedBaseline(it, ConfigLayer.SHARED,
            it.spec.id !in localBaselineIds && !expired(it.spec.expiresAtEpochMillis, now)) } +
            localView.baselines.map { ResolvedBaseline(it, ConfigLayer.LOCAL, !expired(it.spec.expiresAtEpochMillis, now)) }
        val settings = (sharedView.ruleSettings + localView.ruleSettings).associateBy { it.ruleId }
        val futureExpirations = resolved.filter { it.activity == "active" }.mapNotNull { it.record.spec.expiresAtEpochMillis } +
            baselines.filter { it.active }.mapNotNull { it.record.spec.expiresAtEpochMillis }
        val policyDigest = Digests.sha256(Digests.canonical(buildJsonObject {
            put("schema", "ktchecker/effective-policy-1")
            put("binding", ContractJson.encodeToJsonElement(binding))
            put("registry", aliases.digest)
            put("shared", ContractJson.encodeToJsonElement(shared.revision))
            put("local", ContractJson.encodeToJsonElement(local.revision))
            put("suppressionActivity", JsonArray(resolved.sortedWith(compareBy<ResolvedSuppression> { it.layer.name }.thenBy { it.record.spec.id })
                .map { JsonPrimitive("${it.layer.name}:${it.record.spec.id}:${it.activity}") }))
            put("baselineActivity", JsonArray(baselines.sortedWith(compareBy<ResolvedBaseline> { it.layer.name }.thenBy { it.record.spec.id })
                .map { JsonPrimitive("${it.layer.name}:${it.record.spec.id}:${it.active}") }))
        }))
        return ConfigSnapshot(binding, ConfigRevisionToken(binding, shared.revision, local.revision, policyDigest), sharedView, localView,
            settings, resolved, baselines, now, futureExpirations.minOrNull(), aliases.digest, shared.rawText, local.rawText)
    }

    private fun inspection(layer: LoadedLayer, name: ConfigLayer): LayerInspection {
        val doc = layer.document
        return LayerInspection(name, layer.revision, immutableList(doc?.ruleSettings.orEmpty()), immutableList(doc?.suppressions.orEmpty()),
            immutableList(doc?.suppressionMasks.orEmpty()), immutableList(doc?.baselines.orEmpty()), immutableList(doc?.auditEvents.orEmpty()))
    }

    private fun validateDocument(doc: PolicyDocument, binding: ConfigBinding, layer: ConfigLayer) {
        try {
            if (doc.schemaVersion != 1 || doc.layer != layer || doc.ownerId != binding.ownerId || doc.workspaceId != binding.workspaceId ||
                (layer == ConfigLayer.LOCAL && (doc.worktreeId != binding.worktreeId || doc.lineageId != binding.lineageId)) ||
                (layer == ConfigLayer.SHARED && (doc.worktreeId != null || doc.lineageId != null || doc.suppressionMasks.isNotEmpty()))) {
                throw ConfigValidationException("Configuration document namespace/layer/schema mismatch.")
            }
            if (doc.revision !in 1..MAX_AUDIT_EVENTS || doc.auditEvents.size.toLong() != doc.revision) throw ConfigValidationException("Invalid configuration revision/history.")
            UUID.fromString(doc.generationId)
            if (doc.ruleSettings.size > MAX_ENTRIES || doc.suppressions.size > MAX_ENTRIES || doc.baselines.size > MAX_ENTRIES || doc.suppressionMasks.size > MAX_ENTRIES) {
                throw ConfigValidationException("Configuration entry limit exceeded.")
            }
            requireUnique(doc.ruleSettings.map { it.ruleId }, "rule overrides")
            requireUnique(doc.suppressions.map { it.spec.id }, "suppression IDs")
            requireUnique(doc.baselines.map { it.spec.id }, "baseline IDs")
            requireUnique(doc.suppressionMasks, "suppression masks")
            doc.suppressionMasks.forEach { validateIdentifier(it, "suppression mask") }
            doc.ruleSettings.forEach { setting ->
                val rule = aliases.requireRule(setting.ruleId)
                if (rule.canonicalId != setting.ruleId || (!setting.enabled && (!rule.disablingAllowed || rule.failureProtected))) {
                    throw ConfigValidationException("Invalid canonical rule override.")
                }
            }
            doc.suppressions.forEach { record ->
                if (normalizeSuppression(record.spec, binding, layer, false) != record.spec) throw ConfigValidationException("Stored aliases must be canonical.")
                validateRecordActor(record.createdBy, record.createdAtEpochMillis, binding)
            }
            doc.baselines.forEach { record ->
                validateIdentifier(record.spec.id, "baseline")
                validateJustification(record.spec.justification)
                validateExpiration(record.spec.expiresAtEpochMillis)
                if (normalizeSelector(record.spec.selector, binding, layer, false) != record.spec.selector) throw ConfigValidationException("Stored baseline alias is not canonical.")
                validateRecordActor(record.createdBy, record.createdAtEpochMillis, binding)
            }
            var previous: String? = null
            doc.auditEvents.forEachIndexed { index, event ->
                if (event.revision != index + 1L || event.previousEventDigest != previous || !Digests.valid(event.requestDigest)) {
                    throw ConfigValidationException("Invalid configuration audit chain.")
                }
                validateIdentifier(event.requestId, "mutation request")
                validateRecordActor(event.actor, event.appliedAtEpochMillis, binding)
                previous = eventDigest(event)
            }
        } catch (e: ConfigReadException) {
            throw e
        } catch (e: Exception) {
            throw ConfigReadException("Invalid stored configuration; review policy is unavailable, not empty.", e)
        }
    }

    private fun normalizeSuppression(spec: SuppressionSpec, binding: ConfigBinding, layer: ConfigLayer, creating: Boolean): SuppressionSpec {
        validateIdentifier(spec.id, "suppression")
        validateJustification(spec.justification)
        validateExpiration(spec.expiresAtEpochMillis)
        val normalizedSelector = normalizeSelector(spec.selector, binding, layer, creating)
        val rule = aliases.requireRule(normalizedSelector.ruleId)
        if (creating && (!rule.suppressionAllowed || rule.failureProtected)) throw ConfigValidationException("This rule cannot be suppressed.")
        if (creating && isNarrow(normalizedSelector, spec.scope) && rule.implementationDigest == null) {
            throw ConfigValidationException("Narrow suppression requires actual rule/provider implementation identity.")
        }
        val selector = if (creating && normalizedSelector is PolicySelector.Rule && isNarrow(normalizedSelector, spec.scope)) {
            normalizedSelector.copy(ruleIdentityDigest = rule.implementationDigest)
        } else normalizedSelector
        validateScope(spec.scope, selector.ruleId, binding, layer, creating)
        return spec.copy(selector = selector)
    }

    private fun normalizeSelector(selector: PolicySelector, binding: ConfigBinding, layer: ConfigLayer, creating: Boolean): PolicySelector {
        val rule = aliases.requireRule(selector.ruleId)
        return when (selector) {
            is PolicySelector.Rule -> {
                selector.ruleIdentityDigest?.let { identity ->
                    if (!Digests.valid(identity)) throw ConfigValidationException("Invalid rule selector implementation digest.")
                    if (creating && identity != rule.implementationDigest) {
                        throw ConfigValidationException("Rule selector implementation identity is unavailable or changed.")
                    }
                }
                selector.copy(ruleId = rule.canonicalId)
            }
            is PolicySelector.ExactFinding -> {
                val anchor = selector.anchor
                if (creating && (rule.implementationDigest == null || anchor.ruleIdentityDigest != rule.implementationDigest)) {
                    throw ConfigValidationException("Narrow selector rule implementation is unavailable or changed.")
                }
                validateBinding(anchor.binding)
                if (anchor.canonicalRuleId != rule.canonicalId || anchor.binding.ownerId != binding.ownerId || anchor.binding.workspaceId != binding.workspaceId ||
                    ((creating || layer == ConfigLayer.LOCAL) && anchor.binding != binding)) throw ConfigValidationException("Narrow selector identity belongs to another rule/project/worktree/lineage.")
                validateIdentifier(anchor.providerId, "provider")
                listOf(anchor.context.module, anchor.context.sourceSet, anchor.context.target, anchor.context.compilation).forEach { validateIdentifier(it, "rule context") }
                listOf(anchor.severity, anchor.findingKind, anchor.certainty).forEach { validateIdentifier(it, "finding classification") }
                validatePath(anchor.path)
                validateRange(anchor.line, anchor.column, anchor.endLine, anchor.endColumn)
                listOf(anchor.ruleIdentityDigest, anchor.fileDigest, anchor.evidenceDigest).forEach { if (!Digests.valid(it)) throw ConfigValidationException("Invalid narrow selector digest.") }
                anchor.verifiedIdentity?.let { identity ->
                    validateIdentityBinding(identity, rule.canonicalId, anchor.binding, ConfigLayer.LOCAL, true)
                    if (identity.providerId != anchor.providerId) throw ConfigValidationException("Verified provider identity mismatch.")
                }
                if (!Digests.valid(selector.fingerprint) || selector.fingerprint != fingerprint(anchor)) throw ConfigValidationException("Fingerprint must equal the canonical finding anchor.")
                selector.copy(ruleId = rule.canonicalId)
            }
        }
    }

    private fun validateScope(scope: SuppressionScope, ruleId: String, binding: ConfigBinding, layer: ConfigLayer, creating: Boolean) {
        when (scope) {
            SuppressionScope.Project -> Unit
            is SuppressionScope.Target -> validateIdentifier(scope.target, "target")
            is SuppressionScope.Module -> validateIdentifier(scope.module, "module")
            is SuppressionScope.SourceSet -> {
                validateIdentifier(scope.module, "module"); validateIdentifier(scope.sourceSet, "source set")
                scope.target?.let { validateIdentifier(it, "target") }
            }
            is SuppressionScope.File -> {
                validatePath(scope.path)
                if (!Digests.valid(scope.fileDigest)) throw ConfigValidationException("File scope needs its raw-byte digest.")
                scope.verifiedIdentity?.let { validateIdentityBinding(it, ruleId, binding, layer, creating) }
            }
            is SuppressionScope.Symbol -> validateIdentityBinding(scope.identity, ruleId, binding, layer, creating)
            is SuppressionScope.Range -> {
                validatePath(scope.path)
                validateRange(scope.line, scope.column, scope.endLine, scope.endColumn)
                if (!Digests.valid(scope.fileDigest)) throw ConfigValidationException("Range scope needs its raw-byte digest.")
                scope.verifiedIdentity?.let { validateIdentityBinding(it, ruleId, binding, layer, creating) }
            }
        }
    }

    private fun validateIdentityBinding(identity: VerifiedFindingIdentity, ruleId: String, binding: ConfigBinding, layer: ConfigLayer, creating: Boolean) {
        validateVerifiedIdentity(identity)
        if (identity.canonicalRuleId != ruleId || identity.ownerId != binding.ownerId || identity.workspaceId != binding.workspaceId ||
            ((creating || layer == ConfigLayer.LOCAL) && (identity.worktreeId != binding.worktreeId || identity.lineageId != binding.lineageId))) {
            throw ConfigValidationException("Verified scope identity belongs to another rule/project/worktree/lineage.")
        }
    }

    private fun newDocument(binding: ConfigBinding, layer: ConfigLayer) = PolicyDocument(
        layer = layer, ownerId = binding.ownerId, workspaceId = binding.workspaceId,
        worktreeId = if (layer == ConfigLayer.LOCAL) binding.worktreeId else null,
        lineageId = if (layer == ConfigLayer.LOCAL) binding.lineageId else null,
        revision = 0, generationId = UUID.randomUUID().toString(),
    )

    private fun validateRequest(request: ConfigMutationRequest) {
        validateBinding(request.binding)
        validateIdentifier(request.requestId, "mutation request")
        if (request.expected.binding != request.binding || !Digests.valid(request.expected.policyDigest)) throw ConfigValidationException("CAS token is not bound to this configuration namespace.")
        listOf(request.expected.shared, request.expected.local).forEach {
            if (it.revision < 0 || !Digests.valid(it.documentDigest) || (it.revision == 0L) != (it.generationId == null)) throw ConfigValidationException("Invalid CAS revision token.")
        }
    }

    private fun checkPrincipal(principal: ClientIdentity, binding: ConfigBinding) {
        if (principal.ownerId != binding.ownerId || principal.clientId.isBlank() || principal.sessionId.isBlank()) throw ConfigAuthorizationException("Authority did not authenticate the bound owner session.")
    }

    private fun validateRecordActor(actor: ClientIdentity, createdAt: Long, binding: ConfigBinding) {
        checkPrincipal(actor, binding)
        if (createdAt < 0) throw ConfigValidationException("Invalid policy timestamp.")
    }

    private fun requireLocal(layer: ConfigLayer) {
        if (layer != ConfigLayer.LOCAL) throw ConfigValidationException("Shared suppression masks belong only to local owner overrides.")
    }

    private fun requireUnique(values: List<String>, description: String) {
        if (values.distinct().size != values.size) throw ConfigValidationException("Duplicate $description.")
    }

    private fun requireFutureExpiration(expiration: Long?, now: Instant) {
        validateExpiration(expiration)
        if (expiration != null && expiration <= now.toEpochMilli()) throw ConfigValidationException("New policy expiration must be in the future.")
    }

    private fun validateExpiration(expiration: Long?) {
        if (expiration != null && expiration < 0) throw ConfigValidationException("Invalid expiration timestamp.")
    }

    private fun validateJustification(text: String) {
        if (text.isBlank() || text.length > 8192 || text.indexOf('\u0000') >= 0) throw ConfigValidationException("A nonempty, bounded policy justification is required.")
    }

    private fun expired(expiration: Long?, now: Long): Boolean = expiration != null && expiration <= now
    private fun eventDigest(event: ConfigAuditEvent): String = Digests.sha256(Digests.canonical(ContractJson.encodeToJsonElement(event)))

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_AUDIT_EVENTS = 10_000L
    }
}

internal fun validateBinding(binding: ConfigBinding) {
    listOf(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId).forEach { validateIdentifier(it, "configuration binding") }
}
