package dev.ktchecker.providers.runtime

/** Side-by-side v2 names only; preview-1 is not a fallback and remains independently installed. */
internal object OwnedLiveSupervisorBundleFormat {
    const val VERSION = "ktchecker-owned-child-supervisor/preview-2"
    const val PREFIX = "dev/ktchecker/providers/runtime/native-live/"
    const val MANIFEST = PREFIX + "manifest"
    val targets: Set<String> = java.util.Collections.unmodifiableSet(setOf("linux-x86_64", "linux-aarch64"))
}

/** Byte-only loading is separate from extraction: extraction failure has no automatic cleanup receipt. */
internal fun interface OwnedSessionImageSource {
    fun load(): OwnedSessionImagePlan
}

internal fun interface OwnedSessionImagePlan {
    fun extract(): SupervisorImageHandle
}

internal object InstalledOwnedSessionImageSource : OwnedSessionImageSource {
    override fun load(): OwnedSessionImagePlan {
        val bundle = OwnedLiveSupervisorImageLoader().load()
        return OwnedSessionImagePlan { bundle.extract() }
    }
}

/** Same exact code-source origin and immutable private extraction as v1, never an arbitrary helper path. */
internal class OwnedLiveSupervisorImageLoader(
    private val target: String = SupervisorBundleFormat.target(),
    private val resources: () -> SupervisorResources = { supervisorInstalledResources() },
) {
    fun inspect(): OwnedSupervisorAvailability = try {
        val bundle = load()
        OwnedSupervisorAvailability("available", target, bundle.identity)
    } catch (_: Exception) {
        OwnedSupervisorAvailability("unavailable", target, reasons = listOf(OwnedSessionReason.BUNDLE_UNAVAILABLE.code))
    }

    fun load(): VerifiedSupervisorBundle = try {
        if (target !in OwnedLiveSupervisorBundleFormat.targets) liveImageProblem()
        val source = resources()
        val manifest = source.read(OwnedLiveSupervisorBundleFormat.MANIFEST, SupervisorBundleFormat.MAX_MANIFEST)
            ?: liveImageProblem()
        if (manifest.size > SupervisorBundleFormat.MAX_MANIFEST) liveImageProblem()
        val lines = supervisorUtf8(manifest).split('\n')
        if (lines.size != 5 || lines.last() != "" || lines[0] != OwnedLiveSupervisorBundleFormat.VERSION ||
            lines[1] != target || !SupervisorBundleFormat.digest.matches(lines[2]) ||
            (lines[3] != "unavailable" && !SupervisorBundleFormat.digest.matches(lines[3]))) liveImageProblem()
        if (lines[3] == "unavailable") liveImageProblem()
        val bytes = source.read(OwnedLiveSupervisorBundleFormat.PREFIX + target + "/supervisor", SupervisorBundleFormat.MAX_BINARY)
            ?: liveImageProblem()
        if (bytes.isEmpty() || bytes.size > SupervisorBundleFormat.MAX_BINARY || supervisorDigest(bytes) != lines[3]) liveImageProblem()
        val reread = source.read(OwnedLiveSupervisorBundleFormat.MANIFEST, SupervisorBundleFormat.MAX_MANIFEST)
        if (reread == null || !manifest.contentEquals(reread)) liveImageProblem()
        VerifiedSupervisorBundle(OwnedSupervisorIdentity(lines[0], target, lines[2], lines[3]), bytes)
    } catch (error: ExecutionProblem) {
        throw error
    } catch (_: Exception) {
        liveImageProblem()
    }
}

private fun liveImageProblem(): Nothing = throw ExecutionProblem(
    OwnedSessionReason.BUNDLE_UNAVAILABLE.code, "The installed owned live supervisor bundle is unavailable or changed.",
)
