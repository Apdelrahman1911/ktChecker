package dev.ktchecker.projectmodel

/** Closed module-private routing, not a caller-supplied script/version allowlist.
 * The default and observed importers keep their original adapter. Only the explicit
 * strict importer entrypoint can select the separately pinned 2.4.10 resource. */
internal enum class ModelExporterAdapter {
    LEGACY_2_4_21,
    EXPERIMENTAL_2_4_10;

    val exporterId: String get() = when (this) {
        LEGACY_2_4_21 -> GRADLE_MODEL_EXPORTER_ID
        EXPERIMENTAL_2_4_10 -> KGP_2410_MODEL_EXPORTER_ID
    }

    fun scriptDigest(): String = when (this) {
        LEGACY_2_4_21 -> GradleModelExporter.scriptDigest()
        EXPERIMENTAL_2_4_10 -> Kgp2410ModelExporter.scriptDigest()
    }

    fun requireOrigin(origin: ModelImportOrigin) {
        if (this == EXPERIMENTAL_2_4_10 && origin !is ModelImportOrigin.Complete) {
            throw ProjectModelException("model_exporter_origin_mismatch",
                "The experimental exporter has no observed-to-complete or observed importer route.")
        }
    }

    fun requirePayload(graph: GradleProjectExport) {
        if (this == LEGACY_2_4_21) return // Preserve the existing validation order and role path.
        if (graph.schemaVersion != 1 || graph.exporterId != exporterId) {
            throw ProjectModelException("model_export_schema", "Unknown or mixed experimental exporter report.")
        }
        if (graph.compilations.any { it.jvmArgumentRoles != null || it.deferredJvmOutputs.isNotEmpty() }) {
            throw ProjectModelException("model_exporter_foreign_jvm_payload",
                "The 2.4.10 exporter never emits the separate 2.4.21 argument-role/deferred-output vocabulary.")
        }
    }

    /** Defense-in-depth applicability gaps, in addition to every original exporter issue.
     * No selected-compilation filtering, issue repair, new artifact origin or closure proof. */
    fun additionalIssues(graph: GradleProjectExport): List<ModelIssue> {
        if (this == LEGACY_2_4_21) return emptyList()
        val issues = mutableListOf<ModelIssue>()
        graph.compilations.forEach { compilation ->
            if (compilation.toolchain?.compilerVersion != "2.4.10") issues += ModelIssue(
                "kotlin_compiler_version_unsupported", compilation.id, "This explicit exporter requires exact compiler 2.4.10.")
            if (compilation.platform != "jvm") issues += ModelIssue("kgp2410_compilation_domain_unsupported", compilation.id,
                "The initial independently reviewed 2.4.10 producer vocabulary is ordinary JVM only.")
            if (compilation.associatedCompilationIds.isNotEmpty() || compilation.friendArtifacts.isNotEmpty() ||
                compilation.projectDependencies.isNotEmpty()) issues += ModelIssue("kgp2410_produced_inputs_unsupported", compilation.id,
                "Association, friend and project-produced input facts remain unsupported; no deferred roles are manufactured.")
            if (compilation.auxiliaryInputFiles.isNotEmpty() || compilation.auxiliaryArtifacts.isNotEmpty() ||
                compilation.toolchain?.compilerPlugins?.isNotEmpty() == true) issues += ModelIssue("kgp2410_additional_inputs_unsupported", compilation.id,
                "Nonempty compiler plugin, Java, script or auxiliary domains require a separately reviewed exporter.")
        }
        graph.sourceSets.forEach { sourceSet ->
            if (sourceSet.dependsOn.isNotEmpty() || sourceSet.resources.isNotEmpty()) issues += ModelIssue(
                "kgp2410_source_domain_unsupported", sourceSet.id,
                "The initial 2.4.10 route retains but does not support nonempty refinement/resource domains.")
        }
        graph.generatedInputs.forEach { generated ->
            issues += ModelIssue("kgp2410_generated_inputs_unsupported", generated.taskPath,
                "Generated inputs are retained as facts, never silently accepted as an empty domain.")
        }
        return issues
    }
}
