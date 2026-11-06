#!/usr/bin/env python3
"""Run the existing Native semantic driver, retaining only validated metadata.

The Apple library/FIR feasibility gate does not require Android or a simulator
runtime. It never downloads a Native distribution/dependency or substitutes JVM
compilation. Missing existing prerequisites and failed real attempts stay visible.
"""

import argparse
from datetime import datetime
import os
from pathlib import Path
import platform
import re
import stat
import sys
import zipfile

if __package__:
    from . import provision_native as native
    from .ci_common import REPO_ROOT, digest_bytes, digest_file, gradle_result, source_identity, utc_now, write_json
    from .run_library_checks import regular_input, root_relative, safe_command, strict_json
else:
    import provision_native as native
    from ci_common import REPO_ROOT, digest_bytes, digest_file, gradle_result, source_identity, utc_now, write_json
    from run_library_checks import regular_input, root_relative, safe_command, strict_json


TASK = ":semantic-facts:semanticFixtures"
TARGETS = {"iosArm64": "ios_arm64", "iosSimulatorArm64": "ios_simulator_arm64"}
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
CODE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.:-]{0,159}")
RUNNER_VALUE = re.compile(r"[A-Za-z0-9._+() -]{1,160}")
RUNNER_KEYS = {"os", "architecture", "fixtureHostJava", "ImageOS", "ImageVersion", "RUNNER_OS", "RUNNER_ARCH",
               "GITHUB_SHA", "GITHUB_RUN_ID", "projectJavaVersion", "projectJavaRuntimeVersion", "projectJavaVendor"}
COUNTS = {"visitedSourceInputs", "shards", "declarations", "symbolFacts", "uniqueSymbolIds", "calls", "typeUses",
          "expectActualCandidates", "unknownFacts"}
POSITIVE_ASSERTIONS = {
    "actual_compiler_succeeded", "supported_fact_vocabulary_complete", "both_overloads_resolve_to_distinct_signatures",
    "import_alias_resolves_external_symbol", "local_shadow_is_not_top_level_overload", "same_spelled_local_generic_owners_are_distinct",
    "separate_binary_dependency_call_resolved", "inferred_dependency_generic_type_resolved", "intermediate_actual_matches_common_expect",
    "actual_typealias_expansion_is_semantic", "resolved_annotation_identity", "CRLF_UTF8_call_span_exact",
    "native_is_not_jvm_substitution", "facts_do_not_qualify_rules",
}
NEGATIVE_ASSERTIONS = {"actual_compiler_rejected_source", "compiler_failure_not_successful_facts",
                       "source_error_not_process_failure", "no_guessed_missing_function_symbol"}
ASSERTIONS = {"positive": POSITIVE_ASSERTIONS, "malformed": NEGATIVE_ASSERTIONS,
              "unresolved": NEGATIVE_ASSERTIONS | {"explicit_unknown_or_unvisited_scope"},
              "missing-dependency": NEGATIVE_ASSERTIONS | {"missing_binary_not_a_fabricated_resolved_target"}}
LABELS = {"dependency", *ASSERTIONS}
NATIVE_EXPECT_ACTUAL_ASSERTIONS = {
    "actual_compiler_rejected_incompatible_actual", "compiler_failure_not_successful_facts",
    "exact_incompatible_return_type_diagnostic_point", "native_graph_and_request_bound",
    "native_leaf_fir_context_observed", "expect_actual_sources_and_return_types_exact",
    "expect_actual_candidate_relation_observed", "facts_do_not_qualify_rules",
}
NATIVE_ASSERTIONS = {**ASSERTIONS, "incompatible-actual": NATIVE_EXPECT_ACTUAL_ASSERTIONS}
NATIVE_LABELS = {"dependency", *NATIVE_ASSERTIONS}
# Closed public failure vocabulary emitted by the selected one-shot runtime.
# Preserve causal diagnostics without accepting arbitrary exception text or
# treating a child exit (including zero) as a successful semantic execution.
RUNTIME_FAILURE_REASONS = {
    "analysis_descriptor_changed", "cancelled_before_execution", "executable_changed",
    "executable_digest_mismatch", "executable_unavailable", "execution_arguments_invalid",
    "execution_cancelled", "execution_limits_invalid", "execution_not_authorized", "execution_timeout",
    "output_identity_unavailable", "output_root_required", "output_source_overlap",
    "owned_process_group_shutdown_unverified", "process_group_containment_unavailable",
    "process_output_invalid_utf8", "process_output_limit", "process_output_unreliable",
    "process_start_failed", "process_start_unconfirmed", "process_supervisor_arguments_invalid",
    "process_supervisor_binary_digest_mismatch", "process_supervisor_binary_invalid",
    "process_supervisor_bundle_changed", "process_supervisor_bundle_limit",
    "process_supervisor_bundle_origin_ambiguous", "process_supervisor_bundle_origin_unavailable",
    "process_supervisor_bundle_unavailable", "process_supervisor_cancellation_failed",
    "process_supervisor_capacity_unavailable", "process_supervisor_control_failed",
    "process_supervisor_diagnostics_limit", "process_supervisor_diagnostics_unexpected",
    "process_supervisor_diagnostics_unreliable", "process_supervisor_environment_unavailable",
    "process_supervisor_exit_failed", "process_supervisor_image_changed", "process_supervisor_image_cleanup_failed",
    "process_supervisor_manifest_invalid", "process_supervisor_ownership_lost",
    "process_supervisor_parent_disconnected", "process_supervisor_private_io_unavailable",
    "process_supervisor_protocol_error", "process_supervisor_start_failed", "process_supervisor_transport_timeout",
    "relative_path_invalid", "report_limit", "report_missing_or_unsafe", "report_not_regular_or_oversized",
    "report_root_invalid", "report_root_replaced", "report_unavailable", "secure_report_io_unavailable",
    "snapshot_binding_mismatch", "snapshot_changed_during_execution", "snapshot_content_mismatch",
    "snapshot_duplicate_path", "snapshot_materialization_changed", "snapshot_provenance_unavailable",
    "snapshot_root_invalid", "snapshot_verification_failed",
}
REASONS = {"semantic_compilation_failed", "semantic_facts_unknown", "semantic_sources_not_visited",
           "semantic_fixture_driver_failed", "semantic_native_home_required", "semantic_native_distribution_incomplete",
           "semantic_selected_artifact_changed", "semantic_compiler_version_mismatch", "semantic_fixture_assertion_failed",
           "apple_native_execution_host_missing", "native_target_host_unavailable", "execution_not_authorized",
           "process_group_containment_unavailable", "execution_artifact_changed", "process_start_failed",
           "process_timeout", "process_cancelled", "output_limit_exceeded", "unstructured_detail_omitted"} | RUNTIME_FAILURE_REASONS
UNKNOWN_REASONS = {"unresolved_or_error_reference", "compiler_error_type", "unsupported_semantic_type",
                   "unresolved_annotation", "unstructured_detail_omitted"}
LIMITATIONS = [
    "Synthetic compiler/FIR feasibility only; no custom-rule, corpus, precision, or release qualification.",
    "Native library compilation is not framework linking, cinterop, simulator execution, or target runtime evidence.",
    "Selected Native/JDK/component hashes do not attest the entire external LLVM/libffi/sysroot/cache closure.",
    "No raw results, compiler commands, symbols, source paths/text, streams, machine paths, or private host state are uploaded.",
    "The private evidence digest binds a retained local artifact; it is not publicly inspectable fact evidence.",
    "Failure markers are fixed observations of bounded digest-bound fresh streams, not root-cause attribution.",
]
SUMMARY_KEYS = {"schemaVersion", "evidenceKind", "metadataProjection", "adapterId", "state", "mode", "target",
                "observedAt", "testedCommit", "runner", "artifacts", "cases", "executions", "reasons",
                "privateEvidenceDigest", "tupleQualified", "customRulesQualified", "limitations"}
CASE_KEYS = {"name", "passed", "assertions", "snapshotId", "requestDigest", "analysisId", "state", "compilerExitCode",
             "reasons", "completeForSupportedVocabulary", "observedCompilerVersions", "counts", "unknownReasonCounts"}
EXECUTION_KEYS = {"label", "snapshotId", "analysisKey", "status", "exitCode", "reasons", "metrics", "stdoutDigest", "stderrDigest"}
FAILURE_STREAM_BYTE_LIMIT = 65_536
SECURE_FAILURE_STREAM_IO = os.open in os.supports_dir_fd and all(
    hasattr(os, flag) for flag in ("O_DIRECTORY", "O_NOFOLLOW", "O_NONBLOCK"))
COMPILER_EXCEPTION_HEADER = re.compile(
    r'(?:exception: |Caused by: |Exception in thread "[^"\r\n]{1,80}" )?'
    r'([A-Za-z_$][A-Za-z0-9_.$]{0,199})(?:: ?([^\r\n]*))?')
# Fixed type markers only. Neither an arbitrary exception name nor its message,
# stack, compiler arguments, source text, or source locations may be projected.
COMPILER_EXCEPTION_MARKERS = {
    "java.lang.NoSuchMethodError": "jvm_no_such_method_error",
    "java.lang.NoSuchFieldError": "jvm_no_such_field_error",
    "java.lang.AbstractMethodError": "jvm_abstract_method_error",
    "java.lang.IncompatibleClassChangeError": "jvm_incompatible_class_change_error",
    "java.lang.NoClassDefFoundError": "jvm_no_class_def_found_error",
    "java.lang.ClassNotFoundException": "jvm_class_not_found_exception",
    "java.lang.ExceptionInInitializerError": "jvm_exception_in_initializer_error",
    "java.lang.UnsupportedClassVersionError": "jvm_unsupported_class_version_error",
    "java.lang.IllegalAccessError": "jvm_illegal_access_error",
    "java.lang.LinkageError": "jvm_linkage_error",
    "java.util.ServiceConfigurationError": "jvm_service_configuration_error",
    "java.lang.OutOfMemoryError": "jvm_out_of_memory_error",
    "java.lang.StackOverflowError": "jvm_stack_overflow_error",
    "java.lang.AssertionError": "jvm_assertion_error",
    "java.lang.ClassCastException": "jvm_class_cast_exception",
    "java.lang.IllegalArgumentException": "jvm_illegal_argument_exception",
    "java.lang.IllegalStateException": "jvm_illegal_state_exception",
    "org.jetbrains.kotlin.util.FileAnalysisException": "kotlin_file_analysis_exception",
    "org.jetbrains.kotlin.backend.common.BackendException": "kotlin_backend_exception",
    "dev.ktchecker.semantic.SemanticFactException": "semantic_fact_exception",
}
# SemanticFactException.code is NOT included in Throwable.toString(). Compare
# only exact authored fixed messages from SemanticPlugin.kt; never infer codes
# from arbitrary messages, request-problem lists, or a substring in a source line.
SEMANTIC_EXCEPTION_MESSAGES = {
    "Unknown semantic plugin option": "semantic_plugin_option_unknown",
    "Semantic request is required": "semantic_request_missing",
    "Semantic request must be a bounded regular file": "semantic_request_unsafe",
    "Semantic request changed after authorization": "semantic_request_digest_mismatch",
    "This adapter requires exact Kotlin 2.4.21": "semantic_compiler_version_mismatch",
    "Executing compiler implementation was not selected": "semantic_compiler_artifact_unselected",
    "A separate output directory is required": "semantic_output_missing",
    "Executing artifact changed after selection": "semantic_artifact_digest_mismatch",
    "Selected artifact is not a regular file": "semantic_artifact_unsafe",
}


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and DIGEST.fullmatch(value) is not None


def choice(value: object, allowed: set) -> bool:
    return isinstance(value, str) and value in allowed


def selected_file(base: Path, relative: str) -> Path:
    if not isinstance(relative, str) or not relative or len(relative) > 1600 \
            or any(not re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", part) or part in {".", ".."} for part in relative.split("/")):
        raise ValueError("unsafe selected component")
    current = base
    for part in relative.split("/"):
        current /= part
        if current.is_symlink():
            raise ValueError("selected component uses a descendant symlink")
    if not current.is_file():
        raise ValueError("selected component is unavailable")
    return current


def artifact_coordinate(value: object, mode: str, target: str) -> bool:
    if not isinstance(value, str):
        return False
    if value in {"ktchecker:kotlin-fir-2.4.21/v1", "selected-semantic-jdk:bin/java", "selected-semantic-jdk:release", "selected-semantic-jdk:lib/modules",
                 f"fixture-dependency:{'klib' if mode == 'native' else 'jar'}:{target}"}:
        return True
    if mode == "jvm":
        return re.fullmatch(r"selected-kotlin-compiler:[A-Za-z0-9_.+-]{1,200}\.jar", value) is not None
    prefix = "selected-native-2.4.21:"
    if not value.startswith(prefix):
        return False
    relative = value[len(prefix):]
    return len(relative) <= 1600 and any(relative.startswith(root) for root in
        ("bin/", "tools/", "konan/", "klib/common/", f"klib/platform/{TARGETS.get(target, 'invalid')}/")) \
        and all(re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", part) and part not in {".", ".."} for part in relative.split("/"))


def safe_reasons(value: object, unknown: bool = False) -> list[str]:
    if not isinstance(value, list):
        return []
    allowed = UNKNOWN_REASONS if unknown else REASONS
    return sorted({item if isinstance(item, str) and item in allowed else "unrecognized_reason_omitted" for item in value[:64]})


def fixture_assertions(mode: str) -> dict:
    """The existing JVM suite remains four cases; only Native adds the paired rejection."""
    return NATIVE_ASSERTIONS if mode == "native" else ASSERTIONS


def metadata_projection(value: object, mode: str, target: str) -> dict | None:
    """Explicit allowlist, including failure paths; never copy the original JSON."""
    if not isinstance(value, dict):
        return None
    case_assertions = fixture_assertions(mode)
    labels = {"dependency", *case_assertions}
    projected = {key: value[key] for key in ("schemaVersion", "evidenceKind", "metadataProjection", "adapterId", "mode", "target")
                 if value.get(key) == {"schemaVersion": 1, "evidenceKind": "executed_semantic_fact_fixtures", "metadataProjection": "metadata-only-v1",
                                       "adapterId": "kotlin-fir-2.4.21/v1", "mode": mode, "target": target}[key]}
    projected.update(state=value.get("state") if choice(value.get("state"), {"passed", "failed", "unavailable"}) else "invalid",
                     tupleQualified=value.get("tupleQualified") if type(value.get("tupleQualified")) is bool else None,
                     customRulesQualified=value.get("customRulesQualified") if type(value.get("customRulesQualified")) is bool else None,
                     limitations=LIMITATIONS,
                     reasons=safe_reasons(value.get("reasons")), artifacts=[], cases=[], executions=[])
    for key in ("observedAt", "testedCommit", "privateEvidenceDigest"):
        item = value.get(key)
        if key == "testedCommit" and isinstance(item, str) and re.fullmatch(r"[0-9a-f]{40,64}", item):
            projected[key] = item
        elif key == "privateEvidenceDigest" and valid_digest(item):
            projected[key] = item
        elif key == "observedAt" and isinstance(item, str) and len(item) <= 40:
            try:
                if datetime.fromisoformat(item.replace("Z", "+00:00")).utcoffset() is not None:
                    projected[key] = item
            except ValueError:
                pass
    runner = value.get("runner")
    projected["runner"] = {key: item for key, item in runner.items() if key in RUNNER_KEYS and isinstance(item, str) and RUNNER_VALUE.fullmatch(item)} \
        if isinstance(runner, dict) else {}
    for row in value.get("artifacts", [])[:20_000] if isinstance(value.get("artifacts"), list) else []:
        if not isinstance(row, dict):
            continue
        coordinate = row.get("coordinate")
        if artifact_coordinate(coordinate, mode, target) and valid_digest(row.get("digest")):
            projected["artifacts"].append({"coordinate": coordinate, "digest": row["digest"], "omittedCoordinateDigest": None})
        else:
            projected["artifacts"].append({"coordinate": "unrecognized_coordinate_omitted",
                                           "digest": row["digest"] if valid_digest(row.get("digest")) else "invalid_digest",
                                           "omittedCoordinateDigest": digest_bytes(coordinate.encode("utf-8")) if isinstance(coordinate, str) else None})
    for row in value.get("cases", [])[:len(case_assertions)] if isinstance(value.get("cases"), list) else []:
        if not isinstance(row, dict) or not choice(row.get("name"), set(case_assertions)):
            continue
        output = {"name": row["name"], "passed": row.get("passed") is True, "assertions": {},
                  "state": row.get("state") if choice(row.get("state"), {"extracted", "incomplete", "failed", "unavailable"}) else "invalid",
                  "compilerExitCode": row.get("compilerExitCode") if type(row.get("compilerExitCode")) is int and -1 <= row["compilerExitCode"] <= 255 else None,
                  "completeForSupportedVocabulary": row.get("completeForSupportedVocabulary") is True,
                  "reasons": safe_reasons(row.get("reasons")), "counts": {}, "unknownReasonCounts": {},
                  "observedCompilerVersions": ["2.4.21"] if row.get("observedCompilerVersions") == ["2.4.21"] else []}
        for key in ("snapshotId", "requestDigest", "analysisId"):
            output[key] = row[key] if valid_digest(row.get(key)) else "invalid_digest"
        assertions = row.get("assertions")
        if isinstance(assertions, dict):
            output["assertions"] = {key: item for key, item in assertions.items() if key in case_assertions[row["name"]] and type(item) is bool}
        counts = row.get("counts")
        if isinstance(counts, dict):
            output["counts"] = {key: item for key, item in counts.items() if key in COUNTS and type(item) is int and 0 <= item <= 1_000_000}
        counts = row.get("unknownReasonCounts")
        if isinstance(counts, dict):
            for key, item in counts.items():
                if type(item) is int and 0 <= item <= 1_000_000:
                    safe_key = key if key in UNKNOWN_REASONS else "unrecognized_reason_omitted"
                    output["unknownReasonCounts"][safe_key] = output["unknownReasonCounts"].get(safe_key, 0) + item
        projected["cases"].append(output)
    for row in value.get("executions", [])[:len(labels)] if isinstance(value.get("executions"), list) else []:
        if not isinstance(row, dict) or not choice(row.get("label"), labels):
            continue
        output = {"label": row["label"], "status": row.get("status") if choice(row.get("status"), {"executed", "failed", "unavailable", "not_executed", "cancelled", "timeout"}) else "invalid",
                  "exitCode": row.get("exitCode") if type(row.get("exitCode")) is int and -1 <= row["exitCode"] <= 255 else None,
                  "reasons": safe_reasons(row.get("reasons")), "metrics": {}}
        for key in ("snapshotId", "analysisKey", "stdoutDigest", "stderrDigest"):
            output[key] = row[key] if valid_digest(row.get(key)) else "invalid_digest"
        metrics = row.get("metrics")
        if isinstance(metrics, dict):
            output["metrics"] = {key: item for key, item in metrics.items() if key in {"elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"}
                                 and (item is None or type(item) is int and 0 <= item <= 1_000_000_000_000)}
        projected["executions"].append(output)
    return projected


def valid_reason_list(value: object) -> bool:
    return isinstance(value, list) and len(value) <= 64 and all(isinstance(item, str) and CODE.fullmatch(item) for item in value)


def valid_count_map(value: object, keys: set | None = None) -> bool:
    return isinstance(value, dict) and len(value) <= 64 and (keys is None or set(value) == keys) \
        and all(isinstance(key, str) and CODE.fullmatch(key) and type(item) is int and 0 <= item <= 1_000_000 for key, item in value.items())


def valid_case_metadata(row: object, mode: str = "jvm") -> bool:
    case_assertions = fixture_assertions(mode)
    if not isinstance(row, dict) or set(row) != CASE_KEYS or not choice(row.get("name"), set(case_assertions)):
        return False
    assertions = row.get("assertions")
    versions = row.get("observedCompilerVersions")
    return type(row.get("passed")) is bool and type(row.get("completeForSupportedVocabulary")) is bool \
        and isinstance(assertions, dict) and set(assertions) == case_assertions[row["name"]] and all(type(item) is bool for item in assertions.values()) \
        and valid_count_map(row.get("counts"), COUNTS) and valid_count_map(row.get("unknownReasonCounts")) \
        and sum(row["unknownReasonCounts"].values()) == row["counts"]["unknownFacts"] \
        and valid_reason_list(row.get("reasons")) and choice(row.get("state"), {"extracted", "incomplete", "failed", "unavailable"}) \
        and (row.get("compilerExitCode") is None or type(row["compilerExitCode"]) is int and -1 <= row["compilerExitCode"] <= 255) \
        and isinstance(versions, list) and len(versions) <= 4 and all(isinstance(item, str) and CODE.fullmatch(item) for item in versions) \
        and all(valid_digest(row.get(key)) for key in ("snapshotId", "requestDigest", "analysisId"))


def valid_execution_metadata(row: object, mode: str = "jvm") -> bool:
    labels = {"dependency", *fixture_assertions(mode)}
    if not isinstance(row, dict) or set(row) != EXECUTION_KEYS or not choice(row.get("label"), labels):
        return False
    metrics = row.get("metrics")
    return choice(row.get("status"), {"executed", "failed", "unavailable", "not_executed", "cancelled", "timeout"}) \
        and valid_reason_list(row.get("reasons")) \
        and (row.get("exitCode") is None or type(row["exitCode"]) is int and -1 <= row["exitCode"] <= 255) \
        and isinstance(metrics, dict) and set(metrics) == {"elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"} \
        and all(item is None and key in {"cpuMillis", "peakRssBytes"} or type(item) is int and 0 <= item <= 1_000_000_000_000
                for key, item in metrics.items()) \
        and all(valid_digest(row.get(key)) for key in ("snapshotId", "analysisKey", "stdoutDigest", "stderrDigest"))


def validate_summary(value: object, mode: str, target: str, commit: str, project_java: dict) -> list[str]:
    errors = []
    projection = metadata_projection(value, mode, target)
    if not isinstance(value, dict) or projection is None:
        return ["semantic_summary_not_object"]
    if set(value) != SUMMARY_KEYS or type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1 \
            or value.get("evidenceKind") != "executed_semantic_fact_fixtures" or value.get("metadataProjection") != "metadata-only-v1" \
            or value.get("adapterId") != "kotlin-fir-2.4.21/v1" or value.get("mode") != mode or value.get("target") != target \
            or value.get("tupleQualified") is not False or value.get("customRulesQualified") is not False \
            or value.get("testedCommit") != commit or not valid_digest(value.get("privateEvidenceDigest")) or "observedAt" not in projection \
            or not valid_reason_list(value.get("reasons")):
        errors.append("semantic_summary_identity_or_qualification_invalid")
    runner = value.get("runner")
    if not isinstance(runner, dict) or runner != projection["runner"] \
            or mode == "native" and (runner.get("os") not in {"Mac OS X", "Darwin"} or runner.get("architecture") not in {"aarch64", "arm64"}) \
            or not re.fullmatch(r"21(?:\.[0-9]+)+(?:[+_-][A-Za-z0-9._+-]+)?", runner.get("fixtureHostJava", "") if isinstance(runner, dict) else ""):
        errors.append("semantic_summary_runner_invalid")
    elif any(runner.get(key) != item for key, item in project_java.items()):
        errors.append("semantic_summary_project_jvm_differs")
    artifacts = value.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) > 20_000 or len(artifacts) != len(projection["artifacts"]) \
            or any(not isinstance(row, dict) or set(row) != {"coordinate", "digest", "omittedCoordinateDigest"}
                   or not artifact_coordinate(row.get("coordinate"), mode, target) or not valid_digest(row.get("digest"))
                   or row.get("omittedCoordinateDigest") is not None for row in artifacts) \
            or len({row["coordinate"] for row in projection["artifacts"]}) != len(projection["artifacts"]):
        errors.append("semantic_summary_artifacts_invalid")
    cases, executions = value.get("cases"), value.get("executions")
    case_assertions = fixture_assertions(mode)
    labels = {"dependency", *case_assertions}
    if not isinstance(cases, list) or len(cases) > len(case_assertions) or len(cases) != len(projection["cases"]) \
            or len({row["name"] for row in projection["cases"]}) != len(projection["cases"]) \
            or any(not valid_case_metadata(row, mode) for row in cases):
        errors.append("semantic_summary_case_metadata_invalid")
    if not isinstance(executions, list) or len(executions) > len(labels) or len(executions) != len(projection["executions"]) \
            or len({row["label"] for row in projection["executions"]}) != len(projection["executions"]) \
            or any(not valid_execution_metadata(row, mode) for row in executions):
        errors.append("semantic_summary_execution_metadata_invalid")
    if value.get("state") != "passed":
        errors.append("semantic_fixture_assertions_did_not_pass")
        return errors
    if value.get("reasons") != [] or {row["name"] for row in projection["cases"]} != set(case_assertions) \
            or {row["label"] for row in projection["executions"]} != labels:
        errors.append("semantic_summary_missing_exact_fixture_cases")
    expected_artifacts = {"ktchecker:kotlin-fir-2.4.21/v1", "selected-semantic-jdk:bin/java", "selected-semantic-jdk:release", "selected-semantic-jdk:lib/modules",
                          f"fixture-dependency:{'klib' if mode == 'native' else 'jar'}:{target}"}
    expected_artifacts |= {"selected-native-2.4.21:bin/konanc", "selected-native-2.4.21:konan/konan.properties",
                           "selected-native-2.4.21:konan/lib/kotlin-native-compiler-embeddable.jar"} if mode == "native" else \
                          {"selected-kotlin-compiler:kotlin-compiler-embeddable-2.4.21.jar"}
    if not expected_artifacts.issubset({row["coordinate"] for row in projection["artifacts"]}):
        errors.append("semantic_summary_missing_selected_compiler_inputs")
    for row in projection["cases"]:
        positive = row["name"] == "positive"
        if row["passed"] is not True or set(row["assertions"]) != case_assertions[row["name"]] or not all(row["assertions"].values()) \
                or row["observedCompilerVersions"] != ["2.4.21"] or row["compilerExitCode"] != (0 if positive else 1) \
                or row["state"] != ("extracted" if positive else "incomplete") or row["completeForSupportedVocabulary"] is not positive \
                or set(row["counts"]) != COUNTS or any(not valid_digest(row[key]) for key in ("snapshotId", "requestDigest", "analysisId")) \
                or positive and (row["reasons"] or row["counts"]["unknownFacts"] != 0 or row["counts"]["visitedSourceInputs"] != 5
                                 or row["counts"]["shards"] != 5 or row["counts"]["calls"] == 0) \
                or not positive and "semantic_compilation_failed" not in row["reasons"] \
                or row["name"] == "incompatible-actual" and (row["counts"]["visitedSourceInputs"] != 2 or row["counts"]["shards"] != 2
                    or row["counts"]["declarations"] < 2 or row["counts"]["expectActualCandidates"] < 1):
            errors.append("semantic_summary_fixture_assertions_invalid")
            break
    for row in projection["executions"]:
        if row["status"] != "executed" or row["exitCode"] != (0 if row["label"] in {"dependency", "positive"} else 1) \
                or row["reasons"] or any(not valid_digest(row[key]) for key in ("snapshotId", "analysisKey", "stdoutDigest", "stderrDigest")) \
                or set(row["metrics"]) != {"elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"} \
                or row["metrics"].get("elapsedMillis") is None or row["metrics"].get("outputBytes") is None:
            errors.append("semantic_summary_actual_execution_invalid")
            break
    return sorted(set(errors))


def compiler_failure_markers(text: str) -> list[str]:
    markers = set()
    for line in text.split("\n"):
        header = COMPILER_EXCEPTION_HEADER.fullmatch(line.removesuffix("\r"))
        if header is None:
            continue
        exception, message = header.groups()
        marker = COMPILER_EXCEPTION_MARKERS.get(exception)
        if marker is not None:
            markers.add(marker)
        if exception == "dev.ktchecker.semantic.SemanticFactException":
            reason = SEMANTIC_EXCEPTION_MESSAGES.get(message)
            if reason is not None:
                markers.add(reason)
    return sorted(markers)


def failure_stream_metadata(private: Path, root: Path, label: str, stream: str, expected_digest: str) -> dict:
    """Read only a closed fresh-driver filename, below no-follow directory FDs."""
    if label not in NATIVE_LABELS or stream not in {"stdout", "stderr"} or not valid_digest(expected_digest):
        raise ValueError("invalid closed compiler stream selection")
    result = {"stream": stream, "digest": expected_digest, "digest_verified": False,
              "status": "unsafe_or_unavailable", "markers": []}
    try:
        relative = root_relative(private / f"{label}-{stream}.txt", root)
        if relative.parts[:1] != ("build",) or relative.parts[-3:-1] != ("raw", "native-semantic-driver"):
            return result
        if not SECURE_FAILURE_STREAM_IO:
            result["status"] = "secure_read_unavailable"
            return result
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        directory = os.open(root.resolve(strict=True), flags)
        try:
            for part in relative.parts[:-1]:
                child = os.open(part, flags, dir_fd=directory)
                os.close(directory)
                directory = child
            descriptor = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            try:
                info = os.fstat(descriptor)
                if not stat.S_ISREG(info.st_mode):
                    return result
                if info.st_size > FAILURE_STREAM_BYTE_LIMIT:
                    result["status"] = "oversized"
                    return result
                remaining, chunks = FAILURE_STREAM_BYTE_LIMIT + 1, []
                while remaining:
                    chunk = os.read(descriptor, remaining)
                    if not chunk:
                        break
                    chunks.append(chunk)
                    remaining -= len(chunk)
                raw = b"".join(chunks)
            finally:
                os.close(descriptor)
        finally:
            os.close(directory)
    except (OSError, ValueError):
        return result
    if len(raw) > FAILURE_STREAM_BYTE_LIMIT:
        result["status"] = "oversized"
    elif digest_bytes(raw) != expected_digest:
        result["status"] = "digest_mismatch"
    else:
        result["digest_verified"] = True
        try:
            result["markers"] = compiler_failure_markers(raw.decode("utf-8"))
            result["status"] = "markers_retained" if result["markers"] else "no_allowlisted_marker" if raw else "empty"
        except UnicodeDecodeError:
            result["status"] = "invalid_utf8"
    return result


def compiler_failure_diagnostics(summary: dict, private: Path, root: Path) -> dict:
    """Supplement validated failed metadata, without changing any execution gate."""
    result = {"schema_version": 1, "projection": "fixed-exception-markers-v1",
              "stream_byte_limit": FAILURE_STREAM_BYTE_LIMIT, "raw_logs_uploaded": False,
              "root_cause_identified": False, "executions": []}
    if summary["state"] == "passed":
        return result
    passed_cases = {row["name"] for row in summary["cases"] if row["passed"]}
    for row in sorted(summary["executions"], key=lambda item: item["label"]):
        failed = row["status"] in {"failed", "cancelled", "timeout"} or (
            row["status"] == "executed" and type(row["exitCode"]) is int and row["exitCode"] != 0)
        if failed and row["label"] not in passed_cases:
            result["executions"].append({"label": row["label"], "streams": [
                failure_stream_metadata(private, root, row["label"], stream, row[f"{stream}Digest"])
                for stream in ("stdout", "stderr")]})
    return result


def dependency_inventory(home: Path, data_dir: Path, target: str, host_build: str) -> dict:
    path = selected_file(home, "konan/konan.properties")
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("Native configuration oversized")
    properties = {}
    for line in path.read_bytes().decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and not line.lstrip().startswith(("#", "!")):
            properties[key.strip()] = value.strip()
    result = {"konan_properties_digest": digest_file(path), "autodownload_disabled": True,
              "external_dependency_closure_qualified": False, "dependencies": [],
              "configuration_scope": "raw_internal_configuration_not_effective_local_xcode_sdk",
              "effective_apple_selection": "public_local_xcode", "cache_entries_required_for_library_gate": False}
    for kind, key in (("target_sysroot", f"targetSysRoot.{TARGETS[target]}"), ("llvm", "llvmHome.macos_arm64"), ("libffi", "libffiDir.macos_arm64")):
        value = properties.get(key, "")
        for _ in range(8):
            if re.fullmatch(r"\$[A-Za-z0-9_.]+", value):
                value = properties.get(value[1:], "")
            else:
                break
        safe = re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", value) and value not in {".", ".."}
        cached = data_dir / "dependencies" / value if safe else None
        present = cached is not None and cached.is_dir() and not cached.is_symlink() and not cached.parent.is_symlink()
        row = {"kind": kind, "configured_package": value if safe else "unrecognized_configured_package_omitted", "cache_entry_present": bool(present)}
        if kind == "target_sysroot":
            build = re.search(r"(?:^|[-_])xcode_[0-9.]+_([A-Za-z0-9]+)-", value) if safe else None
            row.update(configured_xcode_build=build[1] if build else None,
                       selected_host_xcode_build=host_build, configured_build_matches_selected_host=bool(build and build[1] == host_build))
        result["dependencies"].append(row)
    return result


def verify_selected_artifacts(summary: dict, home: Path, java_home: Path, root: Path) -> None:
    plugin = root / "modules/semantic-facts/build/libs/semantic-facts-0.1.0-dev.1-kotlin-2.4.21-plugin.jar"
    for row in summary["artifacts"]:
        coordinate = row["coordinate"]
        if coordinate.startswith("selected-native-2.4.21:"):
            path = selected_file(home, coordinate.split(":", 1)[1])
        elif coordinate.startswith("selected-semantic-jdk:"):
            path = selected_file(java_home, coordinate.split(":", 1)[1])
        elif coordinate == "ktchecker:kotlin-fir-2.4.21/v1":
            regular_input(plugin, root)
            path = plugin
        elif coordinate.startswith("fixture-dependency:"):
            # Its only public identity is the digest. The driver verifies the
            # actual private child-produced KLIB before every dependent child.
            continue
        else:
            raise ValueError("unrecognized selected artifact")
        if digest_file(path) != row["digest"]:
            raise ValueError("selected artifact changed since the actual driver")


def run(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    source = source_identity(root)
    result = {"schema_version": 1, "evidence_kind": "native_semantic_fixture_attempt", "state": "unavailable",
              "recorded_at": utc_now(), "source": source, "mode": "native", "target": args.target,
              "gradle_attempt_executed": False, "compiler_execution_count": 0, "errors": [],
              "native_distribution_downloaded_by_wrapper": False, "native_dependency_autodownload_disabled": True,
              "android_required_for_this_gate": False, "simulator_runtime_required_for_this_gate": False,
              "tuple_qualified": False, "custom_rules_qualified": False, "release_qualified": False,
              "raw_logs_uploaded": False, "limitations": LIMITATIONS}
    try:
        environment, java_home, selected_xcode = native.observed_prerequisites(args.environment, root, source)
        java = environment["java"]
        home, data_dir, reference = native.consume_selection(args.native_selection, args.native_provisioning, args.target,
                                                            root, source, java_home, selected_xcode)
        result.update(native_distribution=home.name, compiler_version="2.4.21",
                      selected_xcode=selected_xcode, native_provisioning=reference,
                      native_prerequisites=dependency_inventory(home, data_dir, args.target, "17E202"))
    except native.NativePrerequisiteError as error:
        result["errors"].append(error.code)
        return result
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RuntimeError, zipfile.BadZipFile):
        result["errors"].append("native_semantic_prerequisite_unclassified_failure")
        return result
    private = args.output.parent / "raw" / "native-semantic-driver"
    try:
        relative = root_relative(private, root)
        if not relative.parts or relative.parts[0] != "build":
            raise ValueError("private evidence must remain in the ignored repository build directory")
        current = root.resolve()
        for part in relative.parts:
            current /= part
            if current.is_symlink():
                raise ValueError("private evidence output must not use descendant symlinks")
        private.mkdir(parents=True, exist_ok=True)
    except (OSError, ValueError):
        result["errors"].append("native_semantic_output_directory_unsafe")
        return result
    raw_result, raw_summary = private / "results.json", private / "summary.json"
    if any(private.iterdir()):
        result["errors"].append("native_semantic_output_directory_not_fresh")
        return result
    monitored = (raw_result, raw_summary) + tuple(private / f"{label}-{stream}.txt" for label in NATIVE_LABELS for stream in ("stdout", "stderr"))
    command, _ = gradle_result(
        ["--rerun-tasks", "--no-build-cache", TASK, "-PsemanticMode=native", f"-PsemanticTarget={args.target}",
         f"-PsemanticNativeHome={home}", f"-PsemanticJavaHome={java_home}", f"-PsemanticResult={raw_result.absolute()}"],
        root=root, log_base=args.output.parent / "raw" / "native-semantic-gradle", timeout=args.timeout,
        require_tasks=(TASK,), additional_output_paths=monitored,
    )
    result.update(gradle_attempt_executed=command.get("return_code") is not None, gradle=safe_command(command),
                  required_task=TASK, actual_task_outcome=command.get("tasks", {}).get(TASK, "absent"), state="failed")
    try:
        summary_bytes = regular_input(raw_summary, root)
        summary = strict_json(summary_bytes, limit=8 * 1024 * 1024)
        result["driver_summary_digest"] = digest_bytes(summary_bytes)
        result["driver_summary"] = metadata_projection(summary, "native", args.target)
        if raw_result.is_symlink() or not raw_result.is_file() or raw_result.stat().st_size > 20 * 1024 * 1024 \
                or digest_file(raw_result) != summary.get("privateEvidenceDigest"):
            raise ValueError("private evidence digest does not bind the actual driver result")
        result["private_evidence_digest_verified"] = True
        releases = java.get("release_properties", {})
        project_java = {"projectJavaVersion": releases.get("JAVA_VERSION"), "projectJavaRuntimeVersion": releases.get("JAVA_RUNTIME_VERSION"),
                        "projectJavaVendor": releases.get("IMPLEMENTOR")}
        validation = validate_summary(summary, "native", args.target, source["commit"], project_java)
        result["errors"].extend(validation)
        result["driver_summary_metadata_valid"] = not (set(validation) - {"semantic_fixture_assertions_did_not_pass"})
        if not result["driver_summary_metadata_valid"] and result["driver_summary"] is not None:
            result["driver_summary"]["state"] = "invalid"
        if result["driver_summary"] is not None:
            result["compiler_execution_count"] = sum(row["status"] in {"executed", "failed", "timeout", "cancelled"}
                                                     for row in result["driver_summary"]["executions"])
            if summary.get("state") == "unavailable":
                result["state"] = "unavailable"
        verify_selected_artifacts(summary, home, java_home, root)
        native.consume_selection(args.native_selection, args.native_provisioning, args.target, root, source, java_home, selected_xcode)
        if result["driver_summary_metadata_valid"]:
            result["compiler_failure_diagnostics"] = compiler_failure_diagnostics(summary, private, root)
    except native.NativePrerequisiteError as error:
        result["errors"].append(error.code)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        result["errors"].append("native_semantic_driver_evidence_invalid_or_missing")
    if command.get("state") != "passed":
        result["errors"].append("native_semantic_gradle_gate_failed")
    if source_identity(root)["working_tree_digest"] != source["working_tree_digest"]:
        result["errors"].append("source_changed_during_native_semantic_attempt")
    if not result["errors"]:
        result["state"] = "passed"
    result["errors"] = sorted(set(result["errors"]))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--environment", type=Path, default=REPO_ROOT / "build/ci-evidence/environment.json")
    parser.add_argument("--native-selection", type=Path, default=REPO_ROOT / "build/ci-evidence/raw/native-provisioning/selection.json")
    parser.add_argument("--native-provisioning", type=Path, default=REPO_ROOT / "build/ci-evidence/native.json")
    parser.add_argument("--target", choices=tuple(TARGETS), default="iosSimulatorArm64")
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 2400:
        parser.error("timeout must be between 1 and 2400 seconds")
    result = run(args)
    write_json(args.output, result)
    print(f"Native semantic gate: {result['state']}; target={args.target}; actual compiler attempts={result['compiler_execution_count']}; no qualified tuples/rules")
    for code in result["errors"]:
        print(code, file=sys.stderr)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
