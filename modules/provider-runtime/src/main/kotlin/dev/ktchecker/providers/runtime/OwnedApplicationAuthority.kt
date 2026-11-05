package dev.ktchecker.providers.runtime

import dev.ktchecker.workerprotocol.ApplicationWire
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trusted owner-issued, exact, single-use generation capability. No serialized descriptor grants it.
 * Hooks are mandatory trusted/cooperative host callbacks; callback overrun can never be success.
 * The original clock object is retained, including time spent constructing/freeze-copying this grant.
 * Another invocation cannot obtain permission or revoke the first consumer by replaying this grant.
 */
class OwnedApplicationGenerationPermit(
    internal val owner: OwnedApplicationOwner,
    launch: ApplicationWire.Launch,
    internal val originalDeadline: OwnedApplicationDeadline,
    internal val verifyBeforeStart: () -> Unit,
    internal val validateHello: (ApplicationWire.Hello) -> Unit,
    internal val verifyLive: () -> Unit,
) {
    internal val launch: ApplicationWire.Launch = ApplicationWire.decodeLaunch(ApplicationWire.encodeLaunch(launch))
    private val consumed = AtomicBoolean()
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)
}

/**
 * One actual returned session OBJECT, not a generation-label lookup or an interchangeable digest.
 * T must be complete bounded detached host data. For a provider it is the entire ProviderResult:
 * decoding, diagnostics, suppression and coverage construction all belong INSIDE validateResult.
 * Only the actual target session may consume the grant; rejection never revokes another invocation.
 */
class OwnedApplicationJobPermit<T : Any>(
    internal val generation: OwnedApplicationSession,
    job: ApplicationWire.Job,
    internal val originalDeadline: OwnedApplicationDeadline,
    internal val verifyBeforeWrite: () -> Unit,
    internal val validateResult: (ApplicationWire.Result) -> T,
    internal val verifyBeforePublication: () -> Unit,
) {
    internal val job: ApplicationWire.Job = ApplicationWire.decode(ApplicationWire.encode(job)) as ApplicationWire.Job
    private val consumed = AtomicBoolean()
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)
}
