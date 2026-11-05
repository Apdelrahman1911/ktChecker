package dev.ktchecker.cli

import dev.ktchecker.compiler.JvmRuntimeEntryKind
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import dev.ktchecker.snapshot.MaterializedSnapshot
import kotlinx.serialization.encodeToString

/**
 * Unit-only observed importer. Real registered captures; SYNTHETIC ordinary JAR/JDK bytes and
 * receipt callbacks. No Gradle/compiler/native launch, complete closure proof, pinned produced
 * graph, model generation, attempt or Accepted output is created by this helper.
 */
internal fun OwnerModelFixtures.importObservedUnit(capture: MaterializedSnapshot, configurationDigest: String,
    invocation: ObservedExportInvocation, graph: GradleProjectExport = graph(capture),
    observationCheckpoint: () -> Unit = {}): ObservedProjectModel {
    val runtime = installation.runtimeImage.view().entries.filter { it.kind == JvmRuntimeEntryKind.FILE }.map {
        ModelArtifact("compilation-jdk:${it.relativePath}", installation.runtimeImage.root.resolve(it.relativePath).toString(), it.digest, it.size)
    }
    // Independent fixture registry is fixed before report serialization and ingress.
    val registered = (compilerArtifacts + dependencies + runtime).associateBy { it.path }
    val bytes = ContractJson.encodeToString(graph).toByteArray()
    val expected = ContractJson.decodeFromString<GradleProjectExport>(bytes.toString(Charsets.UTF_8))
    val request = ModelImportRequest(namespace, capture.manifest.toContractManifest(), capture.root.toString(),
        configurationDigest, Digests.sha256(bytes), GradleModelExporter.scriptDigest(), invocation.identity)
    val permit = ObservedExportPermit(request, invocation,
        Digests.sha256("SYNTHETIC unit runtime receipt; no launch"),
        Digests.sha256("SYNTHETIC observations only; complete configuration closure unproven"),
        verifySnapshot = {
            check(snapshots.snapshot(snapshotIdentity, capture.manifest.snapshotId) == capture.manifest)
            check(snapshots.verifyMaterialization(capture).valid)
        }, verifyExecutedExport = { check(request.exportDigest == Digests.sha256(bytes)) },
        verifyArtifact = { check(registered[it.path] == it) },
        verifyObservedInputs = { observationCheckpoint(); check(it == expected) })
    return ObservedProjectModelImporter(ExactObservedExportAuthority(permit)).import(request, bytes)
}
