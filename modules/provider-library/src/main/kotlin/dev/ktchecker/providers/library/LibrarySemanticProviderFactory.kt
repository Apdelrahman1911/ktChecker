package dev.ktchecker.providers.library

import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.VerifiedProjectModel
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque live owner registration. Closing revokes a not-yet-dispatched registration; it cannot
 * stop an already-dispatched analysis. The worker's original CancellationToken owns that lifetime.
 * No serialized approval, request digest or decoded copy can construct this handle or a permit.
 */
class LibrarySemanticRegistration internal constructor(private val revoke: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) revoke() }
}

/**
 * Reusable owner-side routes for the THREE EXISTING providers, not a compiler or a new authority.
 * A trusted owner registers each exact coordinator child input after its real semantic producer
 * has retained the original compiler request/output and selected the actual dependency/source-set
 * and explicit interop/test-policy context. Existing mandatory authorities must verify those
 * immutable receipts; the factory neither issues permits nor substitutes a DTO-shaped success.
 *
 * Registration structurally freezes the selected facts through each existing provider's prepare
 * path. No authority callback or source reader runs until analyze. The exact registration is
 * consumed at most once even on failure; archived inputs alone do not re-enroll an attempt.
 *
 * Install providers into owner routes without changing their IDs, supported rule slices or result
 * coverage. Missing enrollment is unavailable, never not-applicable, clean, complete or delivered.
 * Nothing here expands current rule applicability (including friends/project/deferred/auxiliary
 * compilation inputs), discovers tests, installs the semantic plugin or wires CLI/Workbench.
 * Pending capacity bounds simultaneous live handoffs, not the project's declared rule scope.
 */
class LibrarySemanticProviderFactory(private val maximumPendingRegistrations: Int = 64) : AutoCloseable {
    init { require(maximumPendingRegistrations in 1..256) { "library_runtime_pending_limit_invalid" } }

    private data class Key(val providerId: String, val analysisKey: String)
    private class Registered(val input: AnalysisInput, val provider: AnalysisProvider)
    private val lock = Any()
    private val pending = linkedMapOf<Key, Registered>()
    private var closed = false

    private val runBlocking = Route(LIBRARY_PROVIDER_ID, LIBRARY_PROVIDER_VERSION, setOf(RUNBLOCKING_IN_SUSPEND))
    private val refinement = Route(COROUTINE_REFINEMENT_PROVIDER_ID, COROUTINE_REFINEMENT_VERSION,
        setOf(SLEEP_IN_COROUTINE, RESULT_SWALLOWING_CANCELLATION))
    private val emptyAssertion = Route(EMPTY_ASSERTION_PROVIDER_ID, EMPTY_ASSERTION_PROVIDER_VERSION, setOf(EMPTY_ASSERT_FAILS_WITH))

    /** Fixed implementation roster only; registration or catalog membership is not qualification. */
    val providers: List<AnalysisProvider> = libraryList(listOf(runBlocking, refinement, emptyAssertion))

    fun registerRunBlocking(input: AnalysisInput, model: VerifiedProjectModel, selection: RunBlockingFactSelection,
                            authority: LibraryFactAuthority): LibrarySemanticRegistration =
        register(runBlocking, input) { frozen -> RunBlockingInSuspendProvider(model, selection, authority).freezeForRuntime(frozen) }

    fun registerCoroutineRefinement(input: AnalysisInput, model: VerifiedProjectModel, selection: CoroutineRefinementSelection,
                                   authority: CoroutineRefinementAuthority): LibrarySemanticRegistration =
        register(refinement, input) { frozen -> CoroutineRefinementProvider(model, selection, authority).freezeForRuntime(frozen) }

    fun registerEmptyAssertion(input: AnalysisInput, model: VerifiedProjectModel, selection: EmptyAssertionFactSelection,
                               authority: EmptyAssertionAuthority): LibrarySemanticRegistration =
        register(emptyAssertion, input) { frozen -> EmptyAssertFailsWithProvider(model, selection, authority).freezeForRuntime(frozen) }

    private fun register(route: Route, input: AnalysisInput,
                         prepare: (AnalysisInput) -> Pair<AnalysisInput, AnalysisProvider>): LibrarySemanticRegistration {
        val selected = freezeInput(input)
        require(route.accepts(selected.requiredRuleIds)) { "library_runtime_exact_child_rule_selection_required" }
        val (bound, provider) = prepare(selected)
        // Existing provider preparation must not normalize/rewrite any owner-selected input field.
        require(bound == selected && provider.id == route.id && provider.version == route.version &&
            provider.supportedRuleIds == route.supportedRuleIds) { "library_runtime_prepared_binding_mismatch" }
        val key = Key(route.id, bound.analysisKey())
        val registered = Registered(bound, provider)
        synchronized(lock) {
            check(!closed) { "library_runtime_closed" }
            check(key !in pending) { "library_runtime_registration_already_pending" }
            check(pending.size < maximumPendingRegistrations) { "library_runtime_pending_limit_reached" }
            pending[key] = registered
        }
        return LibrarySemanticRegistration {
            synchronized(lock) {
                // A stale handle must never revoke a freshly enrolled attempt with the same key.
                if (pending[key] === registered) pending.remove(key)
            }
        }
    }

    /** Drops only pending handoffs. Active providers still use their original worker cancellation. */
    override fun close() { synchronized(lock) { closed = true; pending.clear() } }

    private inner class Route(override val id: String, override val version: String, rules: Set<String>) : AnalysisProvider {
        override val supportedRuleIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(rules))

        fun accepts(rules: List<String>): Boolean = rules.isNotEmpty() && rules.size == rules.distinct().size &&
            rules.all { it in supportedRuleIds }

        override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
            // Freeze before ANY owner callback, including a caller-owned cancellation predicate.
            val selected = try { freezeInput(input) } catch (_: Exception) {
                return ProviderResult(id, "partial", reasons = libraryList(listOf("library_runtime_input_invalid_or_unbounded")),
                    providerVersion = version)
            }
            if (!accepts(selected.requiredRuleIds)) return unavailable(selected, "library_runtime_exact_child_rule_selection_required")
            if (cancellation.isCancelled()) return unavailable(selected, "library_runtime_cancelled", true)
            val key = Key(id, selected.analysisKey())
            val registered = synchronized(lock) { if (closed) null else pending.remove(key) }
                ?: return unavailable(selected, "library_runtime_owner_registration_unavailable")
            // Exact data equality supplements the digest lookup; no rule/context/key projection.
            if (registered.input != selected) return unavailable(selected, "library_runtime_registration_binding_mismatch")
            return registered.provider.analyze(registered.input, cancellation)
        }

        private fun unavailable(input: AnalysisInput, reason: String, cancelled: Boolean = false): ProviderResult =
            ProviderResult(id, if (cancelled) "cancelled" else "partial", coverage = libraryList(input.requiredRuleIds.distinct().map { rule ->
                Coverage(rule, input.context.scope, if (cancelled) "cancelled" else if (rule in supportedRuleIds) "unavailable" else "unsupported",
                    reason, depth = if (rule == RESULT_SWALLOWING_CANCELLATION) "graph" else "semantic")
            }), reasons = libraryList(listOf(reason)), providerVersion = version)
    }

    private companion object {
        /** Bounded deep DATA copy, not source/artifact IO or a model/extraction attestation. */
        fun freezeInput(input: AnalysisInput): AnalysisInput {
            // Match the existing providers' per-scope envelope guard BEFORE any unavailable
            // rows can repeat the scope. The aggregate input budget alone cannot bound that.
            require(with(input.context.scope) {
                listOf(target, sourceSet, module, compilation).all { it.length <= 4096 }
            }) { "library_runtime_scope_bound_exceeded" }
            LibraryTextBudget().input(input)
            return input.copy(snapshot = input.snapshot.copy(files = libraryList(input.snapshot.files),
                declaredRoots = libraryList(input.snapshot.declaredRoots), scopeExclusions = libraryList(input.snapshot.scopeExclusions),
                unknownInputs = libraryList(input.snapshot.unknownInputs)), context = input.context.copy(
                classpath = libraryList(input.context.classpath), compilerArguments = libraryList(input.context.compilerArguments),
                libraries = Collections.unmodifiableMap(input.context.libraries.toMap()), policies = Collections.unmodifiableMap(input.context.policies.toMap()),
                compilationFiles = input.context.compilationFiles?.let(::libraryList)),
                requiredRuleIds = libraryList(input.requiredRuleIds), changedPaths = libraryList(input.changedPaths),
                execution = input.execution?.let { it.copy(arguments = libraryList(it.arguments),
                    environment = Collections.unmodifiableMap(it.environment.toMap())) })
        }
    }
}
