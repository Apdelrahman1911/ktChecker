#!/usr/bin/env python3
"""Retain separate source-bound preview-2 unittest evidence on each CI host.

The shared standard-unittest recorder runs the original exact fixture file in
this process. There is no outer worker timeout, process-group kill or attempt
directory cleanup. Native deadlines and custody remain with the original
fixtures. Console bytes stay in an owner-local bounded raw directory.
"""

from __future__ import annotations

import argparse
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import platform
import stat
import sys
import threading
import uuid

if __package__:
    from . import run_python_checks as recorder
    from . import run_supervisor_checks as v1
else:
    import run_python_checks as recorder
    import run_supervisor_checks as v1


PROTOCOL = "ktchecker-owned-child-supervisor/preview-2"
TARGETS = frozenset({"linux-x86_64", "linux-aarch64"})
NATIVE = "modules/provider-runtime/native"
TEST_NAME = "test_live_supervisor.py"
INPUTS = {
    "source": NATIVE + "/owned_live_process_supervisor.c",
    "builder": NATIVE + "/build_live_supervisor.py",
    "probe": NATIVE + "/live_supervisor_probe.c",
    "tests": NATIVE + "/" + TEST_NAME,
    "runner": "scripts/ci/live_supervisor_test_runner.py",
    "recorder": "scripts/ci/run_python_checks.py",
    "inventory_guard": "scripts/ci/run_supervisor_checks.py",
    "source_identity": "scripts/ci/ci_common.py",
}
METHODS = {
    "WireValidationTest": (
        "test_final_roundtrip_and_separate_nonzero_child_exit",
        "test_impossible_final_fields_are_rejected",
    ),
    "BuildReceiptTest": (
        "test_separate_v2_linux_only_protocol_prefix_and_schema",
        "test_unsupported_hosts_emit_unavailable_without_compiler_or_v1_edits",
        "test_failed_build_clears_only_v2_generated_resources",
        "test_private_compiler_paths_and_all_banners_are_redacted",
    ),
    "BuildDriverFailureTest": (
        "test_invalid_timeout_never_launches_a_driver",
        "test_constructor_exception_is_launch_unknown_not_no_child_or_retry",
        "test_post_launch_clock_or_selector_setup_failures_retire_obtained_driver",
        "test_missing_pipe_fails_explicitly_and_still_retires_obtained_driver",
        "test_selector_and_pipe_close_failures_cannot_skip_retirement_or_each_other",
        "test_poll_or_kill_error_still_attempts_wait_and_both_closes",
        "test_unobserved_reap_is_cleanup_unknown_even_with_simultaneous_close_failure",
        "test_late_constructor_is_retired_without_selector_setup",
        "test_late_exit_or_late_descriptor_close_cannot_return_success",
        "test_successful_already_reaped_driver_is_not_signaled",
        "test_diagnostic_limit_is_checked_before_retention",
    ),
    "CompilerBuildCustodyTest": (
        "test_success_holds_guard_through_publication_then_allows_rebuild",
        "test_unsupported_host_also_guards_clear_and_releases_only_after_writes",
        "test_version_unknown_retains_pending_guard_without_cleanup_or_compile",
        "test_compile_unknown_preserves_candidate_and_original_reason",
        "test_preflight_validation_failures_conservatively_retain_without_launch",
        "test_post_version_ordinary_and_base_exceptions_do_not_release_guard",
        "test_existing_directory_malformed_state_or_file_refuses_before_clear",
        "test_guard_acquisition_errors_never_clear_or_launch",
        "test_parent_creation_failure_is_unavailable_without_touching_output",
        "test_preflight_phase_failure_or_short_write_guards_unchanged_output",
        "test_invalid_phase_is_rejected_without_persisting_caller_text",
        "test_direct_return_phase_write_failure_prevents_next_stage_and_retains",
        "test_second_caller_at_acquisition_refuses_before_first_destructive_clear",
        "test_second_unsupported_caller_during_compile_preserves_first_candidate",
        "test_native_and_native_live_sibling_custody_are_isolated_both_directions",
        "test_binary_rejection_and_input_drift_retain_direct_return_phase",
        "test_receipt_limit_failure_retains_guard_after_output_installation",
        "test_publication_write_exceptions_preserve_guard_and_original_exception",
        "test_short_publication_write_never_releases_guard",
        "test_pre_removal_release_failures_preserve_existing_guard",
        "test_unexpected_residue_prevents_release_without_recursive_attempt_cleanup",
        "test_resource_clear_failure_preserves_guard_and_original_exception",
    ),
    "NativeHarnessCustodyTest": (
        "test_failed_obtained_process_registration_retains_attempt_without_pending_entry",
        "test_unknown_process_or_close_retains_exact_attempt_and_trace",
        "test_known_exit_and_all_closed_streams_allow_only_exact_attempt_removal",
        "test_failed_channel_construction_keeps_obtained_process_guarded",
        "test_channel_registration_failure_closes_its_partial_selector",
        "test_trace_read_has_an_explicit_allocation_bound",
    ),
    "NativeLiveSupervisorTest": (
        "test_faulted_poll_retires_before_any_ordinary_acceptance_but_preserves_cancel_eof",
        "test_cleanup_proof_requires_fresh_time_before_deadline_and_never_rehabilitates_late_reap",
        "test_complete_preamble_still_requires_fresh_nonexpired_uninterrupted_time",
        "test_full_final_write_at_or_after_deadline_or_clock_failure_cannot_return_zero",
        "test_probe_entry_resets_and_unblocks_inherited_sigalrm_before_arming_expiry",
        "test_built_receipt_binds_new_source_builder_driver_and_binary",
        "test_real_duplex_reply_then_later_dynamic_request_on_same_live_child",
        "test_immediate_arm_after_bound_never_races_before_fork",
        "test_complete_cancel_with_preamble_prevents_fork_and_proves_no_child",
        "test_ordinary_before_bound_is_not_buffered_or_accepted",
        "test_fragmented_preamble_header_and_data_body",
        "test_partial_data_is_never_speculatively_forwarded",
        "test_partial_data_then_owner_eof_is_not_counted_or_forwarded",
        "test_bad_wrong_nonce_and_truncated_preambles_never_fork",
        "test_incomplete_preamble_obeys_immutable_lifetime",
        "test_exact_literal_argv_nonzero_exit_and_separate_output",
        "test_child_stdin_close_is_distinct_from_owner_control_eof",
        "test_closed_stdin_allows_watchdog_bookkeeping_but_not_more_data_or_close",
        "test_cancel_then_eof_is_terminal_request_not_owner_loss",
        "test_unsolicited_owner_eof_retires_and_cleans",
        "test_cancel_prefix_eof_is_owner_loss_not_cancellation",
        "test_control_headers_sequences_and_transitions_fail_closed",
        "test_global_credit_rejects_pipelined_ordinary_control",
        "test_arm_cannot_replace_or_extend_active_epoch",
        "test_expired_disarm_cannot_erase_timeout",
        "test_generation_lifetime_continues_while_disarmed_and_idle",
        "test_long_arm_is_subordinate_to_short_generation",
        "test_input_limit_rejects_without_counting_or_acknowledging_excess",
        "test_output_limit_counts_only_one_bounded_overshoot",
        "test_pending_partial_input_does_not_block_cancel",
        "test_pending_partial_input_does_not_block_epoch_deadline",
        "test_close_cannot_bypass_outstanding_partial_data",
        "test_normal_leader_exit_with_unwritten_accepted_data_is_io_failure",
        "test_all_control_requests_share_finite_quota_without_ack_queue_growth",
        "test_duplicate_cancel_flood_does_not_extend_cleanup",
        "test_blocked_helper_stdout_still_accepts_cancel_and_cleans_before_unknown_exit",
        "test_blocked_helper_stdout_cannot_disable_epoch_deadline",
        "test_cancel_is_still_read_with_both_child_stdin_and_helper_stdout_blocked",
        "test_broken_owner_stdout_still_runs_owned_cleanup",
        "test_fast_leader_exit_kills_term_resistant_descendant_before_late_write",
        "test_unrelated_retained_group_is_not_signaled",
        "test_helper_term_int_hup_are_cleanup_requests_not_immediate_helper_death",
        "test_exec_failure_records_actual_exit_without_confirmation",
        "test_signaled_and_reserved_126_exits_never_confirm_exec",
        "test_inherited_ignored_sigchld_is_reset_before_owned_fork",
        "test_invalid_launch_fields_and_v1_discriminator_never_fork",
        "test_actual_owner_death_closes_control_and_native_still_closes_owned_generation",
        "test_missing_quiescence_is_reported_without_numeric_pid_or_helper_kill_recovery",
        "test_syscalls_final_real_signal_precedes_any_helper_reap",
    ),
}
CASES = {f"test_live_supervisor.{name}.{method}": "native" if name == "NativeLiveSupervisorTest" else "python"
         for name, methods in METHODS.items() for method in methods}
MAX_INPUT_BYTES = 1_048_576
MAX_TOOL_BYTES = 128 * 1024 * 1024
LIVE_GUARD_CODES = frozenset({
    "live_fixture_file_inventory_mismatch", "live_fixture_empty_input", "live_tool_selection_not_absolute",
    "live_tool_unavailable", "live_tool_byte_limit", "live_tool_changed_during_hash", "live_explicit_linux_compiler_required",
    "live_fixture_module_already_loaded", "live_fixture_input_changed_before_import", "live_fixture_builder_binding_mismatch",
    "live_earlier_report_already_exists", "live_required_ci_context_missing_or_invalid", "live_ci_runner_os_mismatch",
    "live_fixture_initial_binding_mismatch", "live_worker_protocol_unavailable_or_invalid",
})
LIMITATIONS = [
    "Only the original tiny preview-2 fixtures execute. Pure/mocked controls and Linux native controls retain separate denominators; no v1, provider, application-readiness or compiler-warming credit.",
    "The recorder runs directly in this process. This entrypoint enforces no suite wall timeout and never kills a helper/worker/group or removes a native attempt directory. Original fixture deadlines and unresolved custody remain authoritative.",
    "Externally interrupted CI jobs may have only private callback checkpoints or no final reports. Missing/incomplete final evidence cannot pass; external job termination is not cleanup proof.",
    "Source and driver before/after hashes are observations, not atomic capture, loaded native binary reproducibility or a transitive compiler/interpreter/library/SDK closure attestation.",
    "The original fixture assertions own generation-closure checks. Observing the fixture's final custody state does not independently reconstruct syscall traces or prove arbitrary descendant absence.",
    "Python console text is redirected privately and truncated at a combined byte bound without interrupting fixture cleanup. Chmod is not Windows ACL proof, and native trace allocation bounds are not disk quotas. Raw traces, compiler transcripts, generated binaries, exception/skip text and environment values are not published.",
    "JSON and JUnit publish separately. Require the matching JSON, XML digest and successful wrapper exit; XML alone is not complete evidence. Local filesystem operations are synchronous, not hard kernel-stall or crash-durability guarantees.",
]


@dataclass(frozen=True)
class Config:
    root: Path
    output: Path
    junit: Path
    cc: Path | None = None
    expected_commit: str | None = None
    ci_context: dict | None = None
    require_ci_context: bool = False
    max_output_bytes: int = 1024 * 1024
    max_report_bytes: int = recorder.MAX_REPORT_BYTES


def configuration_digest(config: Config) -> str:
    private = {key: str(getattr(config, key)) for key in ("root", "output", "junit")}
    private.update(cc=str(config.cc) if config.cc is not None else None, expected_commit=config.expected_commit,
                   ci_context=config.ci_context, require_ci_context=config.require_ci_context,
                   max_output_bytes=config.max_output_bytes, max_report_bytes=config.max_report_bytes,
                   selection=TEST_NAME, outer_worker_timeout=False)
    return recorder.digest(json.dumps(private, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode())


def input_snapshot(root: Path) -> dict:
    if v1.native_test_file_inventory(root) != (TEST_NAME, "test_supervisor.py"):
        raise recorder.GuardError("live_fixture_file_inventory_mismatch")
    result = {}
    for role, relative in INPUTS.items():
        path = root / relative
        recorder.reject_symlink_ancestors(path, root)
        raw = recorder.read_small(path, MAX_INPUT_BYTES)
        if not raw:
            raise recorder.GuardError("live_fixture_empty_input")
        result[role] = {"path": relative, "digest": recorder.digest(raw), "size_bytes": len(raw),
                        "executable": bool(path.stat().st_mode & 0o111)}
    return result


def tool_identity(path: Path) -> dict:
    if not path.is_absolute():
        raise recorder.GuardError("live_tool_selection_not_absolute")
    resolved = path.resolve(strict=True)
    info = resolved.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= MAX_TOOL_BYTES or not os.access(resolved, os.X_OK):
        raise recorder.GuardError("live_tool_unavailable")
    hasher, total = hashlib.sha256(), 0
    with resolved.open("rb") as source:
        for raw in iter(lambda: source.read(65_536), b""):
            total += len(raw)
            if total > MAX_TOOL_BYTES:
                raise recorder.GuardError("live_tool_byte_limit")
            hasher.update(raw)
    if total != info.st_size:
        raise recorder.GuardError("live_tool_changed_during_hash")
    return {"state": "available", "digest": "sha256:" + hasher.hexdigest(), "size_bytes": total,
            "driver_identity_only": True, "version_probe_performed": False, "private_path_retained": False}


def tool_snapshot(config: Config, host: dict) -> dict:
    supported = host["target"] in TARGETS
    if supported and config.cc is None:
        raise recorder.GuardError("live_explicit_linux_compiler_required")
    python = tool_identity(Path(sys.executable))
    python["version"] = ".".join(str(part) for part in sys.version_info[:3])
    return {"python": python,
            "compiler": tool_identity(config.cc) if supported else {"state": "not_applicable"},
            "syscall_observer": tool_identity(Path("/usr/bin/strace")) if supported else {"state": "not_applicable"}}


def load_fixture(root: Path, inputs: dict, cc: Path | None):
    """Load exactly the hashed source bytes, without an ambient module or pyc."""
    if any(name in sys.modules for name in ("build_live_supervisor", "test_live_supervisor")):
        raise recorder.GuardError("live_fixture_module_already_loaded")
    modules = {}
    for name, role in (("build_live_supervisor", "builder"), ("test_live_supervisor", "tests")):
        path = root / INPUTS[role]
        raw = recorder.read_small(path, MAX_INPUT_BYTES)
        if recorder.digest(raw) != inputs[role]["digest"]:
            raise recorder.GuardError("live_fixture_input_changed_before_import")
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        modules[name] = module
        exec(compile(raw, str(path), "exec"), module.__dict__)
    fixture = modules["test_live_supervisor"]
    if fixture.builder is not modules["build_live_supervisor"] or fixture.builder.PROTOCOL != PROTOCOL \
            or set(fixture.builder.SUPPORTED) != TARGETS:
        raise recorder.GuardError("live_fixture_builder_binding_mismatch")
    if cc is not None:
        fixture.CC = cc
    return fixture


def fixture_binding_verified(fixture, cc: Path | None, host: dict) -> bool:
    builder = sys.modules.get("build_live_supervisor")
    return fixture is sys.modules.get("test_live_supervisor") and fixture.builder is builder \
        and builder.PROTOCOL == PROTOCOL and set(builder.SUPPORTED) == TARGETS \
        and builder.host_identity() == host and (host["target"] not in TARGETS or fixture.CC == cc)


def guarded_worker_run(config: recorder.Config, final: Path, progress: Path, guard: dict) -> int:
    """Check the closed original selection before setup/body execution."""
    original_loader = recorder.RecordingLoader

    class ExactLoader(original_loader):
        def discover(self, *args, **kwargs):
            suite = super().discover(*args, **kwargs)
            inventory, _, standard = recorder.discovery_inventory(suite)
            actual = [row["id"] for row in inventory]
            exact = len(actual) == len(CASES) and set(actual) == set(CASES)
            guard.update(discovery_completed=True, discovered_method_count=len(actual),
                         execution_shape_supported=standard, inventory_verified=exact and standard,
                         duplicate_occurrences=len(actual) - len(set(actual)),
                         unexpected_unique_methods=len(set(actual) - set(CASES)))
            if not standard or not exact:
                # Reuse existing fixed worker protocol diagnostics. The outer
                # selection guard retains the exact mismatch scope/counts.
                raise recorder.GuardError("unsupported_discovery_suite" if not standard else "unsupported_discovery_leaf")
            return suite

    recorder.RecordingLoader = ExactLoader
    try:
        return recorder.worker_run(config, final, progress)
    finally:
        recorder.RecordingLoader = original_loader


class PrivateConsole:
    """Discard excess text without raising inside an original cleanup path."""
    class Writer(io.TextIOBase):
        def __init__(self, owner, name):
            self.owner, self.name = owner, name

        def write(self, value):
            with self.owner.lock:
                entry = self.owner.entries[self.name]
                for index in range(0, len(value), 8192):
                    raw = value[index:index + 8192].encode("utf-8", errors="replace")
                    entry["observed_bytes"] += len(raw)
                    retained = raw[:max(0, self.owner.limit - self.owner.retained)]
                    if retained:
                        entry["handle"].write(retained)
                        entry["hasher"].update(retained)
                        entry["retained_bytes"] += len(retained)
                        self.owner.retained += len(retained)
            return len(value)

        def flush(self):
            with self.owner.lock:
                handle = self.owner.entries[self.name]["handle"]
                if not handle.closed:
                    handle.flush()

    def __init__(self, directory: Path, limit: int):
        self.limit, self.retained, self.lock, self.entries = limit, 0, threading.Lock(), {}
        try:
            for name in ("stdout", "stderr"):
                path = directory / (name + ".log")
                handle = path.open("xb")
                self.entries[name] = {"handle": handle, "hasher": hashlib.sha256(),
                                      "observed_bytes": 0, "retained_bytes": 0}
                os.chmod(path, 0o600)
        except BaseException:
            for entry in self.entries.values():
                try:
                    entry["handle"].close()
                except BaseException:
                    pass
            raise
        self.stdout, self.stderr = self.Writer(self, "stdout"), self.Writer(self, "stderr")

    def finish(self) -> dict:
        result = {"uploaded": False, "truncated": False, "capture_closed": True, "streams": {}}
        with self.lock:
            for name, entry in self.entries.items():
                try:
                    entry["handle"].close()
                except BaseException:
                    result["capture_closed"] = False
                result["streams"][name] = {key: entry[key] for key in ("observed_bytes", "retained_bytes")}
                result["streams"][name]["digest"] = "sha256:" + entry["hasher"].hexdigest()
                result["truncated"] |= entry["observed_bytes"] != entry["retained_bytes"]
        return result


def custody_observation(fixture, host: dict) -> dict:
    result = {"state": "not_applicable" if host["target"] not in TARGETS else "unknown",
              "unresolved_obtained_handle_count": None, "original_attempt_directory_absence_observed": False,
              "native_process_closure_independently_verified_by_wrapper": False}
    if host["target"] not in TARGETS or fixture is None:
        return result
    cls = getattr(fixture, "NativeLiveSupervisorTest", None)
    attempt, directory = getattr(cls, "attempt", None), getattr(cls, "root", None)
    if attempt is None or not isinstance(directory, Path) or getattr(attempt, "root", None) != directory:
        return result
    pending = getattr(attempt, "pending", None)
    if not isinstance(pending, list) or len(pending) > recorder.MAX_CASES:
        return result
    result["unresolved_obtained_handle_count"] = len(pending)
    absent = not directory.is_symlink() and not directory.exists()
    result["original_attempt_directory_absence_observed"] = absent
    if getattr(attempt, "retained", None) is False and not pending and absent:
        result["state"] = "closed_by_original_harness"
    return result


def retain_execution(report: dict, value: dict) -> None:
    for key in ("discovery", "counts", "cases", "framework_events"):
        report[key] = value[key]
    report["standard_unittest"] = {key: value[key] for key in ("phase", "state", "results_complete")}
    report["worker_source_before"], report["worker_source_after"] = value["source_before"], value["source_after"]
    report["counts_scope"] = "final standard-unittest callbacks" if value["results_complete"] else "callback prefix; not a claimed final suite total"
    report["test_denominators"] = {
        "discovered_test_cases": value["discovery"]["tests_selected"] if value["discovery"]["completed"] else None,
        "started_test_cases": value["counts"]["tests_run"],
        "stop_callbacks_observed": sum(case["stop_callback_observed"] for case in value["cases"]),
        "completed_test_cases": sum(case["complete"] for case in value["cases"]),
        "setup_blocked_test_cases": sum(row["status"] == "setup_blocked" for row in value["discovery"]["test_inventory"]),
        "pending_selected_test_cases": sum(row["status"] == "pending" for row in value["discovery"]["test_inventory"]) if value["discovery"]["completed"] else None,
        "unbound_started_test_cases": sum(case["discovery_ordinal"] is None for case in value["cases"]),
        "fixture_or_framework_events": len(value["framework_events"]),
    }
    report["diagnostics"].extend(value["diagnostics"])


def control_scopes(value: dict | None, host: dict, custody: dict, bindings_verified=False) -> dict:
    discovery_complete = value is not None and value["discovery"]["completed"]
    inventory = value["discovery"]["test_inventory"] if value is not None else []
    actual = [row["id"] for row in inventory]
    exact = len(actual) == len(CASES) and set(actual) == set(CASES)
    scopes = {"inventory_verified": exact}
    for scope in ("python", "native"):
        expected = {name for name, kind in CASES.items() if kind == scope}
        cases = [row for row in value["cases"] if row["id"] in expected] if value is not None else []
        selected = [row for row in inventory if row["id"] in expected]
        success = sum([event["kind"] for event in row["events"]] == ["success"] for row in cases)
        skips = sum([event["kind"] for event in row["events"]] == ["skipped"] for row in cases)
        classes = {name.rsplit(".", 1)[0] for name in expected}
        fixture_events = [event for event in value["framework_events"]
                          if event["id"] in classes or event["id"] == "test_live_supervisor"] if value is not None else []
        state = "incomplete"
        callback_state = "incomplete"
        unavailable = scope == "native" and host["target"] not in TARGETS
        if value is None and unavailable:
            state = "unavailable"
        if exact and value["results_complete"]:
            if any(event["kind"] in {"failure", "error", "expected_failure", "unexpected_success"}
                   for row in cases for event in row["events"]) or fixture_events:
                callback_state = "failed"
            elif unavailable and len(cases) == skips == len(expected):
                callback_state = "skipped"
            elif not unavailable and len(cases) == success == len(expected):
                callback_state = "passed"
            if bindings_verified:
                if callback_state == "failed":
                    state = "failed"
                elif callback_state == "skipped" and unavailable:
                    state = "unavailable"
                elif callback_state == "passed":
                    state = "passed" if scope == "python" or custody["state"] == "closed_by_original_harness" else "incomplete"
        scopes[scope] = {"state": state, "callback_state": callback_state, "bindings_verified": bindings_verified,
                         "reason": "unsupported_host" if unavailable else None,
                         "declared_methods": len(expected), "discovered_methods": len(selected) if discovery_complete else None,
                         "unittest_start_callbacks": len(cases),
                         "completed_test_cases": sum(row["complete"] for row in cases),
                         "successful_methods": success, "skipped_test_cases": skips,
                         "setup_blocked_methods": sum(row["status"] == "setup_blocked" for row in selected),
                         "pending_selected_methods": sum(row["status"] == "pending" for row in selected) if discovery_complete else None}
    return scopes


def run(original: Config) -> dict:
    host = v1.normalized_host(platform.system(), platform.machine())
    report = {"schema_version": 1, "evidence_kind": "owned_live_supervisor_controlled_tests", "protocol_version": PROTOCOL,
              "state": "incomplete", "started_at": recorder.utc_now(), "finished_at": None, "host": host,
              "native_execution_supported_on_host": host["target"] in TARGETS,
              "source": None, "source_after": None, "source_verified": False, "ci_context": None,
              "inputs_before": None, "inputs_after": None, "tools_before": None, "tools_after": None,
              "configuration_before_digest": None, "configuration_after_digest": None,
              "configuration": {"selected_test_file": INPUTS["tests"], "outer_worker_timeout_enforced": False,
                                "max_console_bytes": original.max_output_bytes, "max_report_bytes_each": original.max_report_bytes,
                                "private_configuration_values_retained": False},
              "worker_source_before": None, "worker_source_after": None, "standard_unittest": None,
              "method_selection_guard": {"expected_method_count": len(CASES), "discovery_completed": False,
                  "discovered_method_count": None, "execution_shape_supported": None, "inventory_verified": False,
                  "duplicate_occurrences": None, "unexpected_unique_methods": None},
              "test_execution_exit_code": None, "selection": {"start_directory": NATIVE, "pattern": TEST_NAME},
              "discovery": None, "counts": None, "cases": [], "framework_events": [], "test_denominators": None,
              "counts_scope": "unavailable", "results_complete": False, "control_scopes": control_scopes(None, host, custody_observation(None, host)),
              "native_fixture_custody": custody_observation(None, host), "raw_console": None, "diagnostics": [],
              "junit_report": None, "persistence_complete": False,
              "unsafe_text_output_locals_and_environment_omitted": True,
              "qualification": {"native_abi_qualified": False, "release_qualified": False, "rule_qualified": False,
                                "provider_controls_executed": False, "default_routing_enabled": False, "v1_execution_credit": False,
                                "transitive_toolchain_verified": False, "production_native_supervision_qualified": False},
              "limitations": LIMITATIONS.copy()}
    config = console = fixture = value = None
    final = progress = None
    before = after = None
    publication_allowed = False
    try:
        config = recorder.normalized(recorder.Config(original.root, original.root / NATIVE, original.output, original.junit,
            TEST_NAME, 2400, original.max_output_bytes, original.max_report_bytes, original.expected_commit,
            original.ci_context, original.require_ci_context))
        for destination in (config.output, config.junit):
            if destination.exists():
                raise recorder.GuardError("live_earlier_report_already_exists")
            destination.parent.mkdir(parents=True, exist_ok=True)
        publication_allowed = True
        if original.require_ci_context and (not recorder.context_valid(config.ci_context) or config.expected_commit is None) \
                or config.ci_context is not None and not recorder.context_valid(config.ci_context):
            raise recorder.GuardError("live_required_ci_context_missing_or_invalid")
        report["ci_context"] = config.ci_context
        if config.ci_context is not None and config.ci_context["runner_os"] != {"linux": "Linux", "macos": "macOS", "windows": "Windows"}.get(host["os"]):
            raise recorder.GuardError("live_ci_runner_os_mismatch")
        report["configuration_before_digest"] = configuration_digest(original)
        report["source"] = v1.source_identity(config.root)
        before = recorder.source_snapshot(config)
        report["inputs_before"] = input_snapshot(config.root)
        report["tools_before"] = tool_snapshot(original, host)
        directory = config.output.parent / "raw" / ("live-supervisor-" + uuid.uuid4().hex)
        recorder.reject_symlink_ancestors(directory, config.root)
        directory.mkdir(parents=True, mode=0o700)
        final, progress = directory / "worker-result.json", directory / "worker-progress.json"
        console = PrivateConsole(directory, config.max_output_bytes)
        with redirect_stdout(console.stdout), redirect_stderr(console.stderr):
            fixture = load_fixture(config.root, report["inputs_before"], original.cc)
            if not fixture_binding_verified(fixture, original.cc, host):
                raise recorder.GuardError("live_fixture_initial_binding_mismatch")
            report["test_execution_exit_code"] = guarded_worker_run(config, final, progress, report["method_selection_guard"])
        report["raw_console"] = console.finish()
        console = None
        report["configuration_after_digest"] = configuration_digest(original)
        report["source_after"] = v1.source_identity(config.root)
        after = recorder.source_snapshot(config)
        report["inputs_after"] = input_snapshot(config.root)
        report["tools_after"] = tool_snapshot(original, host)
        for chosen in (final, progress):
            try:
                value = recorder.validate_worker(recorder.strict_json(recorder.read_small(chosen, config.max_report_bytes), config.max_report_bytes), before, after, config)
                break
            except (OSError, ValueError, TypeError, recorder.GuardError):
                if chosen == progress:
                    raise recorder.GuardError("live_worker_protocol_unavailable_or_invalid") from None
        retain_execution(report, value)
        report["source_verified"] = v1.matching_source(report["source_after"], report["source"]) \
            and report["source"]["commit"] == before["commit"] and report["source_after"]["commit"] == after["commit"] and before == after \
            and before == value["source_before"] and after == value["source_after"]
        integrity = report["source_verified"] and report["inputs_before"] == report["inputs_after"] \
            and report["tools_before"] == report["tools_after"] \
            and report["configuration_before_digest"] == report["configuration_after_digest"]
        expected_exit = {"passed": 0, "failed": 1, "incomplete": 2}[value["state"]]
        if not integrity:
            report["diagnostics"].append(recorder.diagnostic("live_input_source_or_configuration_changed", None, config.root))
        if not fixture_binding_verified(fixture, original.cc, host):
            report["diagnostics"].append(recorder.diagnostic("live_fixture_final_binding_mismatch", None, config.root))
        if value["phase"] != "final" or report["test_execution_exit_code"] != expected_exit:
            report["diagnostics"].append(recorder.diagnostic("live_execution_finalization_incomplete", None, config.root))
    except BaseException as error:
        root = config.root if config else original.root
        code = error.code if isinstance(error, recorder.GuardError) and isinstance(error.code, str) and error.code in LIVE_GUARD_CODES else "live_runner_exception"
        report["diagnostics"].append(recorder.diagnostic(code, error, root))
    finally:
        if console is not None:
            report["raw_console"] = console.finish()
        if value is None and config is not None and report["source"] is not None and final is not None:
            for chosen in (final, progress):
                try:
                    value = recorder.validate_worker(recorder.strict_json(recorder.read_small(chosen, config.max_report_bytes), config.max_report_bytes), before, after, config)
                    retain_execution(report, value)
                    break
                except BaseException:
                    pass
        try:
            report["native_fixture_custody"] = custody_observation(fixture, host)
        except (OSError, TypeError, ValueError):
            report["state"] = "incomplete"
            report["diagnostics"].append(recorder.diagnostic("live_custody_observation_unavailable", None, config.root if config else original.root))
        report["finished_at"] = recorder.utc_now()
    if report["raw_console"] is not None and (report["raw_console"]["truncated"] or not report["raw_console"]["capture_closed"]):
        report["diagnostics"].append(recorder.diagnostic("live_console_capture_incomplete", None, config.root))
    if value is not None:
        actual = [row["id"] for row in value["discovery"]["test_inventory"]]
        if not report["method_selection_guard"]["inventory_verified"] or len(actual) != len(CASES) or set(actual) != set(CASES):
            report["diagnostics"].append(recorder.diagnostic("live_fixture_method_inventory_mismatch", None, config.root))
        report["control_scopes"] = control_scopes(value, host, report["native_fixture_custody"], not report["diagnostics"])
        if not report["diagnostics"] and value["results_complete"]:
            report["results_complete"] = True
            scopes = report["control_scopes"]
            if "failed" in {scopes["python"]["state"], scopes["native"]["state"]}:
                report["state"] = "failed"
            elif scopes["python"]["state"] == "passed" and scopes["native"]["state"] == ("passed" if host["target"] in TARGETS else "unavailable"):
                report["state"] = "passed"
    if config is not None and publication_allowed:
        try:
            xml = recorder.junit_bytes(report)
            if len(xml) > config.max_report_bytes:
                raise recorder.GuardError("live_junit_report_limit")
            recorder.publish_exclusive(config.junit, xml)
            report["junit_report"] = {"path": config.junit.relative_to(config.root).as_posix(), "size_bytes": len(xml), "digest": recorder.digest(xml)}
            report["persistence_complete"] = True
            recorder.publish_exclusive(config.output, recorder.encoded(report, config.max_report_bytes))
        except BaseException as error:
            report.update(state="incomplete", results_complete=False, persistence_complete=False)
            for scope in ("python", "native"):
                if report["control_scopes"][scope]["state"] in {"passed", "failed"}:
                    report["control_scopes"][scope]["state"] = "incomplete"
            report["diagnostics"].append(recorder.diagnostic("live_report_persistence_incomplete", error, config.root))
            if not config.output.exists():
                try:
                    recorder.publish_exclusive(config.output, recorder.encoded(report, config.max_report_bytes))
                except BaseException:
                    pass
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--junit", required=True, type=Path)
    parser.add_argument("--cc", type=Path)
    parser.add_argument("--host")
    parser.add_argument("--require-ci-context", action="store_true")
    parser.add_argument("--max-output-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--max-report-bytes", type=int, default=recorder.MAX_REPORT_BYTES)
    args = parser.parse_args(argv)
    result = run(Config(recorder.REPO_ROOT, recorder.REPO_ROOT / args.output, recorder.REPO_ROOT / args.junit, args.cc,
                        os.environ.get("GITHUB_SHA"), recorder.ci_context_from_environment(args.host), args.require_ci_context,
                        args.max_output_bytes, args.max_report_bytes))
    native = result["control_scopes"]["native"]["state"] if result["control_scopes"] else "unavailable"
    print(f"Live preview-2 controlled-test evidence: {result['state']}; native controls: {native}; no qualification")
    return 0 if result["state"] == "passed" and result["persistence_complete"] else 1 if result["state"] == "failed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
