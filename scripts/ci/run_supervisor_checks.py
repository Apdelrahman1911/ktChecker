#!/usr/bin/env python3
"""Observe native build inputs, run tiny native controls and inspect installed bytes.

The real Gradle resource task compiles the distribution helper. Preflight and
collection do not execute it. The separate controls phase runs the explicitly
selected C/Python synthetic fixture harness on supported POSIX hosts only. No
provider runs, downloads, universal binaries or Windows native passes are invented.
"""

import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import sys
import zipfile
import zlib

if __package__:
    from .ci_common import REPO_ROOT, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from .run_library_checks import regular_input, regular_path, root_relative, safe_command, strict_json
    from .smoke_distribution import SUPERVISOR_PROTOCOL, SUPERVISOR_TARGETS, validate_containment
else:
    from ci_common import REPO_ROOT, digest_bytes, digest_file, run_logged, source_identity, utc_now, write_json
    from run_library_checks import regular_input, regular_path, root_relative, safe_command, strict_json
    from smoke_distribution import SUPERVISOR_PROTOCOL, SUPERVISOR_TARGETS, validate_containment


TASK = ":provider-runtime:buildOwnedProcessSupervisor"
TEST_TASK = ":provider-runtime:test"
PREFIX = "dev/ktchecker/providers/runtime/native/"
MODULE = "modules/provider-runtime"
SOURCE = MODULE + "/native/owned_process_supervisor.c"
BUILDER = MODULE + "/native/build_supervisor.py"
PROBE = MODULE + "/native/supervisor_probe.c"
TEST_FILE = MODULE + "/native/test_supervisor.py"
TEST_RUNNER = "scripts/ci/supervisor_test_runner.py"
RESOURCES = MODULE + "/build/generated/nativeResources"
JAR = "provider-runtime-0.1.0-dev.1.jar"
FLAGS = ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pedantic"]
ARGV = ["<compiler>", *FLAGS, "<source>", "-o", "<output>"]
BUILD_ENV = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
PUBLIC_COMPILER_ROOTS = (
    "/bin/", "/usr/bin/", "/usr/lib/", "/usr/libexec/",
    "/Library/Developer/CommandLineTools/usr/bin/",
    "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/",
)
UNQUALIFIED = (
    "native_helper_executed_by_wrapper", "provider_controls_executed_by_wrapper", "execution_verified",
    "native_abi_qualified", "release_qualified", "transitive_compiler_toolchain_verified",
    "source_to_binary_reproducibility_established", "windows_job_objects_implemented", "raw_logs_uploaded",
)
LIMITATIONS = [
    "Host-specific preview helper only; the separate tiny C/Python controls are synthetic fixtures, not provider execution.",
    "Preflight and installed-byte collection do not execute a helper; authored bytes or a passing doctor do not establish process supervision.",
    "Compiler driver/version and observed SDK metadata do not pin transitive cc1, linker, libraries or the entire SDK closure.",
    "No source-to-binary reproducibility, native ABI/runtime, Windows Job Objects, custom-rule or release qualification.",
    "Raw version streams, machine/compiler paths and original command arguments are not uploaded.",
    "The CI version-probe runner is neither a sandbox nor proof of the reviewed runtime's process-tree supervision.",
    "Unittest outcome metadata does not retain or reconstruct the original syscall traces or compiler transcripts.",
]
RECEIPT_KEYS = {"schemaVersion", "protocolVersion", "status", "reason", "host", "compiler", "inputs", "invocation", "output",
                "compilerDriverIdentityOnly", "transitiveCompilerToolchainVerified", "sourceToBinaryReproducibilityEstablished", "privacy"}
BUILDER_METHODS = (
    "test_normalized_host_targets", "test_unsupported_host_writes_explicit_manifest_without_compiler",
    "test_failed_rebuild_does_not_reuse_stale_resources", "test_private_paths_and_untrusted_banner_are_not_retained",
)
NATIVE_METHODS = (
    "test_built_receipt_binds_current_source_builder_driver_and_binary",
    "test_exact_argv_nonzero_exit_and_separate_output_channels",
    "test_fast_leader_exit_kills_term_resistant_orphan_before_late_write",
    "test_other_retained_process_group_is_not_signaled",
    "test_c_then_eof_is_cancellation_not_parent_loss_across_grace",
    "test_timeout_is_autonomous_with_control_kept_open",
    "test_output_limit_is_combined_and_never_clean_success",
    "test_unsolicited_control_eof_reports_parent_loss_and_cleans_child",
    "test_actual_owner_death_closes_control_and_helper_cleans_owned_child",
    "test_helper_term_int_hup_run_cleanup_instead_of_dying",
    "test_invalid_control_byte_is_protocol_failure_with_cleanup",
    "test_exec_failure_retains_exit_but_never_confirms_start",
    "test_signaled_and_reserved_126_exits_are_start_unconfirmed",
    "test_broken_owner_output_still_runs_native_cleanup",
    "test_blocked_owner_output_returns_bounded_unknown_not_success",
    "test_inherited_ignored_sigchld_is_reset_before_fork",
    "test_invalid_limits_exit_before_start_without_fake_terminal",
    "test_syscalls_every_real_group_signal_precedes_any_leader_reap",
)
CASES = {f"test_supervisor.{class_name}.{method}": scope
         for class_name, methods, scope in (("BuildReceiptTest", BUILDER_METHODS, "builder_metadata"),
                                           ("NativeSupervisorTest", NATIVE_METHODS, "owned_child_runtime"))
         for method in methods}
LINUX_ONLY = {
    "test_supervisor.NativeSupervisorTest.test_actual_owner_death_closes_control_and_helper_cleans_owned_child":
        ("Linux subreaper test harness owns the orphaned helper", "linux_subreaper_harness_not_applicable"),
    "test_supervisor.NativeSupervisorTest.test_syscalls_every_real_group_signal_precedes_any_leader_reap":
        ("Linux-only syscall observation, not a Darwin proof", "linux_syscall_trace_not_applicable"),
}


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and DIGEST.fullmatch(value) is not None


def normalized_host(system: str, architecture: str) -> dict:
    host_os = {"linux": "linux", "darwin": "macos", "windows": "windows"}.get(system.lower(), "unsupported")
    cpu = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}.get(architecture.lower(), "unsupported")
    return {"os": host_os, "architecture": cpu,
            "target": f"{host_os}-{cpu}" if "unsupported" not in (host_os, cpu) else "unsupported"}


def matching_source(value: object, source: dict) -> bool:
    return isinstance(value, dict) and value.get("commit") == source.get("commit") \
        and valid_digest(value.get("working_tree_digest")) and value["working_tree_digest"] == source.get("working_tree_digest")


def public_compiler_path(path: Path) -> str:
    value = str(path)
    if re.fullmatch(r"[A-Za-z0-9_./+\-]+", value) and ".." not in path.parts \
            and any(value.startswith(root) for root in PUBLIC_COMPILER_ROOTS):
        return value
    return "<private-compiler>"


def compiler_version_metadata(raw: bytes) -> tuple[str, str, str]:
    first = raw.decode("utf-8").splitlines()[0]
    version = r"([0-9]{1,3}\.[0-9]{1,3}(?:\.[0-9]{1,3})?)(?=\s|\(|$)"
    if first.startswith("Apple clang version "):
        vendor, observed = "apple-clang", re.match(r"Apple clang version " + version, first)
    elif "clang version " in first:
        vendor, observed = "clang", re.search(r"(?:^| )clang version " + version, first)
    elif re.match(r"^(?:cc|gcc|[A-Za-z0-9_.+\-]+-gcc)(?: |$)", first):
        vendor, observed = "gcc", re.search(r"\) +" + version, first)
        if observed is None:
            observed = re.match(r"(?:cc|gcc|[A-Za-z0-9_.+\-]+-gcc) +" + version, first)
    else:
        raise ValueError("compiler version identity unrecognized")
    if observed is None:
        raise ValueError("compiler driver version unobserved")
    return vendor, observed.group(1), digest_bytes(first.encode("utf-8"))


def input_digests(root: Path) -> dict:
    result = {}
    for role, path in (("source", SOURCE), ("builder", BUILDER)):
        raw = regular_input(root / path, root)
        if not 0 < len(raw) <= 1_048_576:
            raise ValueError("native source/builder outside their byte bound")
        result[role] = digest_bytes(raw)
    return result


def native_test_inputs(root: Path) -> dict:
    result = input_digests(root)
    for role, path in (("probe", PROBE), ("tests", TEST_FILE), ("runner", TEST_RUNNER)):
        raw = regular_input(root / path, root)
        if not 0 < len(raw) <= 1_048_576:
            raise ValueError("native fixture input outside its byte bound")
        result[role] = digest_bytes(raw)
    actual = {path.name for path in (root / MODULE / "native").glob("test_*.py")}
    if actual != {"test_supervisor.py"}:
        raise ValueError("native fixture file inventory changed")
    return result


def syscall_observer(target: str) -> dict:
    if not target.startswith("linux-"):
        return {"state": "not_applicable", "required_for_linux_native_controls": False}
    trace = Path("/usr/bin/strace")
    available = trace.is_file() and os.access(trace, os.X_OK)
    return {"state": "available" if available else "unavailable", "required_for_linux_native_controls": True,
            "driver_digest": digest_file(trace.resolve(strict=True)) if available else None, "version_probe_performed": False}


def base(phase: str, root: Path) -> dict:
    host = normalized_host(platform.system(), platform.machine())
    target = host["target"]
    return {"schema_version": 1, "evidence_kind": f"owned_child_supervisor_{phase}", "recorded_at": utc_now(),
            "state": "failed", "source": source_identity(root), "host": host, "target": target, "errors": [],
            "native_helper_executed_by_wrapper": False, "provider_controls_executed_by_wrapper": False,
            "execution_verified": False, "native_abi_qualified": False, "release_qualified": False,
            "transitive_compiler_toolchain_verified": False, "source_to_binary_reproducibility_established": False,
            "windows_job_objects_implemented": False, "raw_logs_uploaded": False, "limitations": LIMITATIONS}


def probe_version(argv: list[str], root: Path, log: Path) -> tuple[dict, bytes]:
    result, _, _ = run_logged(argv, cwd=root, log_base=log, timeout=30, max_output_bytes=65536,
                              env=BUILD_ENV, separate_stderr=False)
    if result.get("state") != "completed" or type(result.get("return_code")) is not int or result["return_code"] != 0:
        raise ValueError("bounded version probe failed")
    # Match the builder's combined pipe ordering, not stdout + stderr afterwards.
    with log.with_suffix(".stdout.log").open("rb") as stream:
        raw = stream.read(65537)
    if not raw or len(raw) > 65536:
        raise ValueError("bounded version metadata unavailable")
    raw.decode("utf-8")
    return safe_command(result), raw


def preflight(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    result = base("build_prerequisites", root)
    target = result["target"]
    result.update(native_compiler_probe_attempted=False, native_compiler_probe_executed=False, compiler=None, apple_build_sdk=None,
                  linux_syscall_observer={"state": "not_applicable", "required_for_linux_native_controls": False},
                  helper_availability="unavailable" if target not in SUPERVISOR_TARGETS else "not_built",
                  execution_scope="supported_posix_build" if target in SUPERVISOR_TARGETS else "unsupported_host_manifest_only")
    try:
        environment_bytes = regular_input(args.environment, root)
        environment = strict_json(environment_bytes)
        if environment.get("state") != "passed" or not matching_source(environment.get("source"), result["source"]) \
                or environment.get("host", {}).get("os") != platform.system() \
                or environment.get("host", {}).get("arch") != platform.machine() \
                or environment.get("host", {}).get("python") != platform.python_version():
            raise ValueError("observed host/source preflight differs")
        result["environment_digest"] = digest_bytes(environment_bytes)
        result["build_inputs"] = input_digests(root)
        selected_python = shutil.which(args.python_command)
        interpreter = Path(sys.executable).resolve(strict=True)
        if args.python_command not in {"python", "python3"} or selected_python is None \
                or Path(selected_python).resolve(strict=True) != interpreter or sys.version_info[:2] != (3, 12):
            raise ValueError("selected Python command differs from this observed Python3.12")
        result["python"] = {"command": args.python_command, "version": platform.python_version(), "launcher_digest": digest_file(interpreter)}
        result["linux_syscall_observer"] = syscall_observer(target)
        if target in SUPERVISOR_TARGETS:
            cc = Path(args.cc)
            if not cc.is_absolute() or re.search(r"[\x00-\x1f\x7f]", str(cc)) or not cc.is_file() or not os.access(cc, os.X_OK):
                raise ValueError("absolute native compiler unavailable")
            driver = cc.resolve(strict=True)
            if not 0 < driver.stat().st_size <= 256 * 1024 * 1024:
                raise ValueError("compiler driver outside its byte bound")
            initial_digest = digest_file(driver)
            result["native_compiler_probe_attempted"] = True
            command, raw = probe_version([str(cc), "--version"], root, args.output.parent / "raw/supervisor-cc-version")
            result["native_compiler_probe_executed"] = True
            if digest_file(cc.resolve(strict=True)) != initial_digest:
                raise ValueError("compiler driver changed during version probe")
            vendor, version, first_digest = compiler_version_metadata(raw)
            result["compiler"] = {"selection": "explicit_absolute_owner_cc", "driver_digest": initial_digest,
                                  "selected_path_digest": digest_bytes(str(cc).encode("utf-8")),
                                  "resolved_path_digest": digest_bytes(str(driver).encode("utf-8")),
                                  "selected_path_projection_digest": digest_bytes(public_compiler_path(cc).encode("utf-8")),
                                  "resolved_path_projection_digest": digest_bytes(public_compiler_path(driver).encode("utf-8")),
                                  "version_output_digest": digest_bytes(raw), "version_output_bytes": len(raw),
                                  "version_first_line_digest": first_digest,
                                  "version_projection_digest": digest_bytes(b"<redacted>"),
                                  "version_first_line_redacted": True,
                                  "vendor": vendor, "reported_version": version, "probe": command,
                                  "driver_identity_only": True}
            if target.startswith("macos-"):
                _, sdk = probe_version(["/usr/bin/xcrun", "--sdk", "macosx", "--show-sdk-version"], root,
                                       args.output.parent / "raw/supervisor-macos-sdk-version")
                _, xcode = probe_version(["/usr/bin/xcodebuild", "-version"], root, args.output.parent / "raw/supervisor-xcode-version")
                sdk_version = sdk.decode("utf-8").strip()
                xcode_text = xcode.decode("utf-8")
                version = re.findall(r"^Xcode ([0-9.]+)$", xcode_text, re.MULTILINE)
                build = re.findall(r"^Build version ([A-Za-z0-9.]+)$", xcode_text, re.MULTILINE)
                if not re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){0,2}", sdk_version) or len(version) != 1 or len(build) != 1:
                    raise ValueError("actual default compiler SDK selection unobserved")
                result["apple_build_sdk"] = {"sdk": "macosx", "version": sdk_version, "xcode_version": version[0], "xcode_build": build[0],
                                             "probe_environment": "same_minimal_C_locale_as_builder", "sdk_closure_verified": False,
                                             "DEVELOPER_DIR_forwarded_to_compiler": False}
    except (OSError, ValueError, TypeError, AttributeError, KeyError):
        result["errors"].append("supervisor_build_prerequisites_unavailable_or_invalid")
    if not matching_source(source_identity(root), result["source"]):
        result["errors"].append("source_changed_during_supervisor_preflight")
    result["state"] = "passed" if not result["errors"] else "failed"
    if result["state"] == "passed":
        try:
            validate_prerequisites(result, result["source"], result["host"])
        except (ValueError, TypeError, AttributeError, KeyError):
            result["errors"].append("supervisor_build_prerequisites_unavailable_or_invalid")
            result["state"] = "failed"
    return result


def validate_prerequisites(value: object, source: dict, host: dict) -> dict:
    supported = host["target"] in SUPERVISOR_TARGETS
    if not isinstance(value, dict) or type(value.get("schema_version")) is not int or value["schema_version"] != 1 \
            or value.get("evidence_kind") != "owned_child_supervisor_build_prerequisites" or value.get("state") != "passed" \
            or value.get("host") != host or value.get("target") != host["target"] or not matching_source(value.get("source"), source) \
            or any(value.get(key) is not False for key in UNQUALIFIED) \
            or value.get("native_compiler_probe_executed") is not supported \
            or value.get("native_compiler_probe_attempted") is not supported \
            or value.get("execution_scope") != ("supported_posix_build" if supported else "unsupported_host_manifest_only") \
            or value.get("helper_availability") != ("not_built" if supported else "unavailable"):
        raise ValueError("native build preflight identity/scope differs")
    inputs = value.get("build_inputs")
    if not isinstance(inputs, dict) or set(inputs) != {"source", "builder"} or any(not valid_digest(item) for item in inputs.values()) \
            or not valid_digest(value.get("environment_digest")):
        raise ValueError("native build preflight input identities invalid")
    python = value.get("python")
    if not isinstance(python, dict) or set(python) != {"command", "version", "launcher_digest"} \
            or python.get("command") not in {"python", "python3"} or not isinstance(python.get("version"), str) \
            or not re.fullmatch(r"3\.12\.[0-9]+", python["version"]) or not valid_digest(python.get("launcher_digest")):
        raise ValueError("native build Python identity differs")
    compiler = value.get("compiler")
    if supported:
        hashes = ("driver_digest", "selected_path_digest", "resolved_path_digest", "selected_path_projection_digest",
                  "resolved_path_projection_digest", "version_output_digest", "version_first_line_digest", "version_projection_digest")
        if not isinstance(compiler, dict) or any(not valid_digest(compiler.get(key)) for key in hashes) \
                or compiler.get("selection") != "explicit_absolute_owner_cc" or compiler.get("driver_identity_only") is not True \
                or compiler.get("version_first_line_redacted") is not True or compiler.get("version_projection_digest") != digest_bytes(b"<redacted>") \
                or type(compiler.get("version_output_bytes")) is not int or not 1 <= compiler["version_output_bytes"] <= 65536 \
                or compiler.get("vendor") not in {"gcc", "clang", "apple-clang"} \
                or not isinstance(compiler.get("reported_version"), str) \
                or not re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,3}(?:\.[0-9]{1,3})?", compiler["reported_version"]):
            raise ValueError("native compiler preflight differs")
    elif compiler is not None or value.get("apple_build_sdk") is not None:
        raise ValueError("unsupported host cannot claim a compiler or Apple SDK")
    if host["os"] == "macos" and supported:
        sdk = value.get("apple_build_sdk")
        if not isinstance(sdk, dict) or set(sdk) != {"sdk", "version", "xcode_version", "xcode_build", "probe_environment",
                                                   "sdk_closure_verified", "DEVELOPER_DIR_forwarded_to_compiler"} \
                or sdk.get("sdk") != "macosx" or sdk.get("probe_environment") != "same_minimal_C_locale_as_builder" \
                or sdk.get("sdk_closure_verified") is not False or sdk.get("DEVELOPER_DIR_forwarded_to_compiler") is not False \
                or any(not isinstance(sdk.get(key), str) or not re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){0,2}", sdk[key])
                       for key in ("version", "xcode_version")) \
                or not isinstance(sdk.get("xcode_build"), str) or not re.fullmatch(r"[A-Za-z0-9.]{1,40}", sdk["xcode_build"]):
            raise ValueError("actual default macOS SDK observation invalid")
    elif value.get("apple_build_sdk") is not None:
        raise ValueError("non-macOS target cannot claim an Apple build SDK")
    observer = value.get("linux_syscall_observer")
    if host["target"].startswith("linux-"):
        if not isinstance(observer, dict) or set(observer) != {"state", "required_for_linux_native_controls", "driver_digest", "version_probe_performed"} \
                or observer.get("state") not in {"available", "unavailable"} or observer.get("required_for_linux_native_controls") is not True \
                or observer.get("version_probe_performed") is not False \
                or (not valid_digest(observer.get("driver_digest")) if observer["state"] == "available" else observer.get("driver_digest") is not None):
            raise ValueError("Linux syscall observer identity invalid")
    elif observer != {"state": "not_applicable", "required_for_linux_native_controls": False}:
        raise ValueError("non-Linux host cannot claim Linux syscall observation")
    return value


def outcome_metadata(value: object, source: dict, host: dict, inputs: dict) -> dict:
    if not isinstance(value, dict) or type(value.get("schema_version")) is not int or value["schema_version"] != 1 \
            or value.get("evidence_kind") != "native_supervisor_unittest_outcomes" \
            or value.get("state") not in {"passed", "failed"} or value.get("host") != host \
            or not matching_source(value.get("source"), source) or value.get("test_inputs") != inputs \
            or value.get("raw_exception_messages_retained") is not False or value.get("provider_controls_executed") is not False \
            or value.get("native_abi_qualified") is not False or value.get("release_qualified") is not False \
            or type(value.get("selected_method_count")) is not int or value["selected_method_count"] != len(CASES) \
            or type(value.get("tests_run")) is not int or not 0 <= value["tests_run"] <= len(CASES) \
            or type(value.get("fixture_errors")) is not int or not 0 <= value["fixture_errors"] <= len(CASES) + 1:
        raise ValueError("native unittest report identity invalid")
    cases = value.get("cases")
    if not isinstance(cases, list) or len(cases) != len(CASES):
        raise ValueError("native unittest method inventory invalid")
    seen, counts, projected = set(), {state: 0 for state in ("passed", "failed", "error", "skipped", "not_run")}, []
    for row in cases:
        if not isinstance(row, dict) or set(row) != {"id", "state", "skip_reason"} or not isinstance(row.get("id"), str) \
                or row["id"] not in CASES or row["id"] in seen or row.get("state") not in counts:
            raise ValueError("native unittest method identity/outcome invalid")
        reason = row.get("skip_reason")
        if row["state"] == "skipped":
            if reason not in {"unrecognized_skip_reason", "native_supervisor_unavailable_on_host"} | {item[1] for item in LINUX_ONLY.values()}:
                raise ValueError("native unittest skip reason invalid")
        elif reason is not None:
            raise ValueError("non-skipped native unittest method has a skip reason")
        seen.add(row["id"])
        counts[row["state"]] += 1
        projected.append({"id": row["id"], "scope": CASES[row["id"]], "state": row["state"], "skip_reason": reason})
    if not isinstance(value.get("counts"), dict) or set(value["counts"]) != set(counts) \
            or any(type(item) is not int for item in value["counts"].values()) \
            or value["counts"] != counts or value["tests_run"] != len(CASES) - counts["not_run"]:
        raise ValueError("native unittest counts disagree")
    expected_skips = LINUX_ONLY if host["os"] == "macos" else {}
    passed = host["target"] in SUPERVISOR_TARGETS and value["fixture_errors"] == 0 and all(
        row["state"] == ("skipped" if row["id"] in expected_skips else "passed")
        and row["skip_reason"] == (expected_skips[row["id"]][1] if row["id"] in expected_skips else None) for row in cases)
    if (value["state"] == "passed") != passed:
        raise ValueError("native unittest success disagrees with actual host-scoped controls")
    return {"state": value["state"], "selected_method_count": len(CASES), "tests_run": value["tests_run"], "counts": counts,
            "fixture_errors": value["fixture_errors"], "cases": sorted(projected, key=lambda row: row["id"]),
            "native_methods_executed": sum(row["scope"] == "owned_child_runtime" and row["state"] in {"passed", "failed", "error"}
                                           for row in projected),
            "builder_metadata_methods_passed": sum(row["scope"] == "builder_metadata" and row["state"] == "passed" for row in projected),
            "linux_syscall_and_owner_death_controls_passed": passed and host["os"] == "linux",
            "raw_exception_messages_retained": False, "original_test_report_uploaded": False}


def controls(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    result = base("native_fixture_controls", root)
    supported = result["target"] in SUPERVISOR_TARGETS
    result.update(execution_scope="tiny_native_synthetic_controls" if supported else "unsupported_host_availability_policy_only",
                  native_fixture_control_command_attempted=False,
                  native_fixture_controls_executed=False, native_fixture_controls_passed=False,
                  native_helper_executed_by_test_harness=False,
                  controls={"state": "not_executed", "selected_method_count": len(CASES), "tests_run": 0})
    try:
        prerequisite_bytes = regular_input(args.prerequisites, root)
        prerequisites = validate_prerequisites(strict_json(prerequisite_bytes), result["source"], result["host"])
        if prerequisites.get("build_inputs") != input_digests(root) or prerequisites["python"]["command"] != args.python_command \
                or prerequisites["python"]["launcher_digest"] != digest_file(Path(sys.executable).resolve(strict=True)):
            raise ValueError("native fixture interpreter/source differs from preflight")
        result["test_inputs"] = native_test_inputs(root)
        result["prerequisites_digest"] = digest_bytes(prerequisite_bytes)
        result["linux_syscall_observer"] = prerequisites.get("linux_syscall_observer")
        if not supported:
            result["controls"] = {"state": "unavailable", "reason": "process_group_containment_unavailable",
                                  "selected_method_count": len(CASES), "tests_run": 0, "methods_not_executed": len(CASES),
                                  "native_methods_not_executed": len(NATIVE_METHODS), "builder_metadata_methods_not_executed": len(BUILDER_METHODS)}
        else:
            cc = Path(args.cc)
            if not cc.is_absolute() or digest_bytes(str(cc).encode("utf-8")) != prerequisites["compiler"]["selected_path_digest"] \
                    or digest_file(cc.resolve(strict=True)) != prerequisites["compiler"]["driver_digest"]:
                raise ValueError("native fixture compiler differs from preflight")
            report = args.output.parent / "raw/supervisor-unittest.json"
            root_relative(report, root)
            if report.exists() or report.is_symlink():
                raise ValueError("native fixture requires a fresh report")
            result["native_fixture_control_command_attempted"] = True
            result["controls"]["state"] = "unverified"
            command, _, _ = run_logged([sys.executable, str(root / TEST_RUNNER), "--output", str(report), "--cc", str(cc)],
                                       cwd=root, log_base=args.output.parent / "raw/supervisor-unittest-command", timeout=180,
                                       max_output_bytes=2 * 1024 * 1024, additional_output_paths=(report,))
            result["fixture_command"] = safe_command(command)
            report_bytes = regular_input(report, root)
            result["controls"] = outcome_metadata(strict_json(report_bytes, limit=256 * 1024), result["source"], result["host"], result["test_inputs"])
            result["test_report_digest"] = digest_bytes(report_bytes)
            result["native_fixture_controls_executed"] = result["controls"]["native_methods_executed"] > 0
            result["native_helper_executed_by_test_harness"] = any(row["scope"] == "owned_child_runtime" and row["state"] == "passed"
                                                                 for row in result["controls"]["cases"])
            if command.get("state") != "completed" or type(command.get("return_code")) is not int or command["return_code"] != 0 \
                    or result["controls"]["state"] != "passed" \
                    or digest_file(cc.resolve(strict=True)) != prerequisites["compiler"]["driver_digest"] \
                    or syscall_observer(result["target"]) != prerequisites["linux_syscall_observer"] \
                    or result["target"].startswith("linux-") and prerequisites["linux_syscall_observer"]["state"] != "available" \
                    or native_test_inputs(root) != result["test_inputs"]:
                raise ValueError("native fixture controls did not all match the actual host scope")
            result["native_fixture_controls_passed"] = True
    except (OSError, ValueError, TypeError, AttributeError, KeyError):
        result["errors"].append("native_supervisor_fixture_controls_unavailable_or_failed")
    if not matching_source(source_identity(root), result["source"]):
        result["errors"].append("source_changed_during_native_supervisor_fixture_controls")
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def manifest(raw: bytes, target: str, source_digest: str) -> str:
    if not raw or len(raw) > 512 or not valid_digest(source_digest) \
            or target not in SUPERVISOR_TARGETS | {"windows-x86_64", "windows-aarch64", "unsupported"}:
        raise ValueError("manifest size invalid")
    lines = raw.decode("utf-8").split("\n")
    if len(lines) != 5 or lines[-1] != "" or lines[0] != SUPERVISOR_PROTOCOL or lines[1] != target or lines[2] != source_digest \
            or (target in SUPERVISOR_TARGETS and not valid_digest(lines[3])) or (target not in SUPERVISOR_TARGETS and lines[3] != "unavailable"):
        raise ValueError("exact host/source manifest invalid")
    return lines[3]


def binary_format(raw: bytes, target: str) -> str:
    if target not in SUPERVISOR_TARGETS or not 64 <= len(raw) <= 1_048_576:
        raise ValueError("native binary size invalid")
    if target.startswith("linux-"):
        if raw[:7] != b"\x7fELF\x02\x01\x01" or int.from_bytes(raw[16:18], "little") not in {2, 3} \
                or int.from_bytes(raw[20:24], "little") != 1 or int.from_bytes(raw[52:54], "little") != 64 \
                or int.from_bytes(raw[18:20], "little") != (62 if target.endswith("x86_64") else 183):
            raise ValueError("not the selected 64-bit ELF executable")
        return "elf64-executable-header"
    if target.startswith("macos-"):
        if raw[:4] != b"\xcf\xfa\xed\xfe" or int.from_bytes(raw[4:8], "little") != (0x1000007 if target.endswith("x86_64") else 0x100000C) \
                or int.from_bytes(raw[12:16], "little") != 2:
            raise ValueError("not the selected thin 64-bit Mach-O executable")
        return "thin-macho64-executable-header"
    raise ValueError("no native executable is supported on this host")


def receipt_metadata(value: object, prerequisites: dict, binary_digest: str, binary_size: int) -> dict:
    target = prerequisites["target"]
    supported = target in SUPERVISOR_TARGETS
    if not isinstance(value, dict) or set(value) != RECEIPT_KEYS or value.get("schemaVersion") != "ktchecker-owned-child-supervisor-build/1" \
            or value.get("protocolVersion") != SUPERVISOR_PROTOCOL or value.get("status") != ("built" if supported else "unavailable") \
            or value.get("reason") != (None if supported else "unsupported_host") or value.get("compilerDriverIdentityOnly") is not True \
            or value.get("transitiveCompilerToolchainVerified") is not False or value.get("sourceToBinaryReproducibilityEstablished") is not False:
        raise ValueError("build receipt identity/qualification invalid")
    host, inputs, privacy = value.get("host"), value.get("inputs"), value.get("privacy")
    if not isinstance(host, dict) or set(host) != {"os", "architecture", "target"} or host.get("target") != target \
            or host != prerequisites.get("host") or host.get("os") not in {"linux", "macos", "windows", "unsupported"} \
            or host.get("architecture") not in {"x86_64", "aarch64", "unsupported"}:
        raise ValueError("build receipt host differs")
    if not isinstance(inputs, dict) or set(inputs) != {"source", "builder"}:
        raise ValueError("build receipt inputs missing")
    for role, path in (("source", "native/owned_process_supervisor.c"), ("builder", "native/build_supervisor.py")):
        row = inputs[role]
        if not isinstance(row, dict) or set(row) != {"path", "sha256"} or row.get("path") != path \
                or row.get("sha256") != prerequisites["build_inputs"][role]:
            raise ValueError("build receipt source/builder differs")
    if not isinstance(privacy, dict) or set(privacy) != {"privatePathsRetained", "rawCompilerTranscriptRetained", "versionFirstLineRedacted"} \
            or privacy.get("privatePathsRetained") is not False or privacy.get("rawCompilerTranscriptRetained") is not False \
            or type(privacy.get("versionFirstLineRedacted")) is not bool:
        raise ValueError("build receipt privacy invalid")
    compiler, invocation, output = value.get("compiler"), value.get("invocation"), value.get("output")
    result = {"schema": value["schemaVersion"], "status": value["status"], "reason": value["reason"], "target": target,
              "source_digest": inputs["source"]["sha256"], "builder_digest": inputs["builder"]["sha256"], "compiler_driver_identity_only": True,
              "transitive_compiler_toolchain_verified": False, "source_to_binary_reproducibility_established": False,
              "raw_compiler_transcript_retained": False, "private_paths_retained": False, "original_receipt_uploaded": False}
    if not supported:
        if compiler is not None or invocation is not None or output is not None or prerequisites.get("native_compiler_probe_executed") is not False \
                or prerequisites.get("compiler") is not None or binary_digest != "unavailable" or binary_size \
                or privacy["versionFirstLineRedacted"] is not False:
            raise ValueError("unsupported host cannot claim a compiler/binary")
        return result
    observed = prerequisites["compiler"]
    if not isinstance(compiler, dict) or set(compiler) != {"selectedPath", "resolvedPath", "driverSha256", "versionFirstLine", "versionOutputSha256", "versionOutputBytes"} \
            or compiler.get("driverSha256") != observed["driver_digest"] or compiler.get("versionOutputSha256") != observed["version_output_digest"] \
            or type(compiler.get("versionOutputBytes")) is not int or compiler["versionOutputBytes"] != observed["version_output_bytes"] \
            or not isinstance(compiler.get("versionFirstLine"), str) or not 1 <= len(compiler["versionFirstLine"]) <= 240 \
            or any(not isinstance(compiler.get(key), str) or not 1 <= len(compiler[key]) <= 2048 for key in ("selectedPath", "resolvedPath")):
        raise ValueError("actual compiler receipt identity differs")
    for field, digest_key in (("selectedPath", "selected_path_projection_digest"), ("resolvedPath", "resolved_path_projection_digest")):
        path = compiler[field]
        if path != "<private-compiler>" and public_compiler_path(Path(path)) != path \
                or digest_bytes(path.encode("utf-8")) != observed[digest_key]:
            raise ValueError("compiler receipt path privacy/selection differs")
    if privacy["versionFirstLineRedacted"] is not True or observed["version_first_line_redacted"] is not True \
            or digest_bytes(compiler["versionFirstLine"].encode("utf-8")) != observed["version_projection_digest"]:
        raise ValueError("compiler receipt version privacy differs")
    if compiler["versionFirstLine"] != "<redacted>":
        raise ValueError("redacted compiler version invalid")
    if not isinstance(invocation, dict) or set(invocation) != {"argv", "flags", "exactArgvSha256", "exactArgvEncoding", "privateArgvRetained"} \
            or invocation.get("argv") != ARGV or invocation.get("flags") != FLAGS or not valid_digest(invocation.get("exactArgvSha256")) \
            or invocation.get("exactArgvEncoding") != "utf8-json-compact-ascii" or invocation.get("privateArgvRetained") is not False:
        raise ValueError("build invocation projection invalid")
    if not isinstance(output, dict) or set(output) != {"path", "sha256", "bytes"} or output.get("path") != PREFIX + target + "/supervisor" \
            or output.get("sha256") != binary_digest or type(output.get("bytes")) is not int or output["bytes"] != binary_size:
        raise ValueError("build receipt output differs")
    result.update(compiler_driver_digest=compiler["driverSha256"], compiler_version_output_digest=compiler["versionOutputSha256"],
                  compiler_version_output_bytes=compiler["versionOutputBytes"], compiler_vendor=observed["vendor"], compiler_reported_version=observed["reported_version"],
                  flags=FLAGS, exact_private_argv_digest=invocation["exactArgvSha256"], exact_private_argv_independently_reconstructed=False,
                  binary_digest=binary_digest, binary_bytes=binary_size)
    return result


def jar_resources(path: Path, expected: dict[str, bytes], root: Path) -> dict:
    selected = regular_path(path, root)
    maximum = 128 * 1024 * 1024
    if not 0 < selected.stat().st_size <= maximum:
        raise ValueError("packaged runtime JAR oversized")
    def stream_digest(stream) -> str:
        stream.seek(0)
        value, total = hashlib.sha256(), 0
        for chunk in iter(lambda: stream.read(65_536), b""):
            total += len(chunk)
            if total > maximum:
                raise ValueError("packaged runtime grew beyond its byte bound")
            value.update(chunk)
        return "sha256:" + value.hexdigest()

    allowed_dirs = {PREFIX}
    for name in expected:
        parent = name.rsplit("/", 1)[0] + "/"
        if parent.startswith(PREFIX):
            allowed_dirs.add(parent)
    with selected.open("rb") as stream:
        initial_digest = stream_digest(stream)
        with zipfile.ZipFile(stream) as archive:
            entries = archive.infolist()
            if len(entries) > 100_000 or len({entry.filename for entry in entries}) != len(entries):
                raise ValueError("packaged runtime JAR identities ambiguous")
            for entry in entries:
                # ZipInfo can truncate NULs and normalize Windows separators.
                # orig_filename is the decoded central-directory name, not proof
                # that unopened local headers or raw Unicode Path extras are
                # canonical. Reject original/effective aliases at this boundary.
                name = entry.orig_filename
                parts = name.rstrip("/").split("/")
                if name != entry.filename or not name or name.startswith("/") or any(part in {"", ".", ".."} for part in parts) \
                        or re.search(r"[\\:\x00-\x1f\x7f]", name) or stat.S_ISLNK(entry.external_attr >> 16) \
                        or entry.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED} \
                        or name.rstrip("/") == PREFIX.rstrip("/") and not entry.is_dir() \
                        or name.startswith(PREFIX) and entry.is_dir() and name not in allowed_dirs:
                    raise ValueError("packaged runtime JAR contains an unsafe or foreign resource path")
            actual = {entry.filename for entry in entries if entry.filename.startswith(PREFIX) and not entry.is_dir()}
            if actual != set(expected):
                raise ValueError("packaged native resources include missing/foreign targets")
            for name, raw in expected.items():
                entry = archive.getinfo(name)
                if entry.file_size != len(raw) or entry.file_size > 1_048_576 or archive.read(entry) != raw:
                    raise ValueError("installed native resource bytes differ")
        if stream_digest(stream) != initial_digest:
            raise ValueError("packaged runtime changed during byte inspection")
    return {"path": root_relative(path, root).as_posix(), "digest": initial_digest,
            "native_resources": [{"path": name, "digest": digest_bytes(raw), "bytes": len(raw)} for name, raw in sorted(expected.items())]}


def collect(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    result = base("build_and_installed_bytes", root)
    result.update(native_compilation_recorded=False, helper_availability="unavailable", native_binary_present=False)
    try:
        prerequisite_bytes, build_bytes, smoke_bytes, control_bytes = (regular_input(path, root) for path in
                                                                      (args.prerequisites, args.build, args.smoke, args.controls))
        prerequisites = validate_prerequisites(strict_json(prerequisite_bytes), result["source"], result["host"])
        build, smoke, tested = strict_json(build_bytes), strict_json(smoke_bytes), strict_json(control_bytes)
        if any(not matching_source(row.get("source"), result["source"]) for row in (build, smoke, tested)) \
                or build.get("state") != "passed" or not isinstance(build.get("tasks"), dict) \
                or any(build["tasks"].get(task) != "EXECUTED" for task in (TASK, TEST_TASK)) \
                or smoke.get("state") != "passed" or smoke.get("evidence_kind") != "installed_distribution_smoke" \
                or smoke.get("execution", {}).get("state") != "completed" \
                or type(smoke.get("execution", {}).get("return_code")) is not int or smoke["execution"]["return_code"] != 0 \
                or tested.get("state") != "passed" or tested.get("evidence_kind") != "owned_child_supervisor_native_fixture_controls" \
                or tested.get("host") != result["host"] or tested.get("test_inputs") != native_test_inputs(root) \
                or tested.get("prerequisites_digest") != digest_bytes(prerequisite_bytes) \
                or tested.get("linux_syscall_observer") != prerequisites["linux_syscall_observer"]:
            raise ValueError("actual build/task/smoke source gate did not pass")
        supported = result["target"] in SUPERVISOR_TARGETS
        if tested.get("native_fixture_controls_executed") is not supported or tested.get("native_fixture_controls_passed") is not supported \
                or tested.get("native_fixture_control_command_attempted") is not supported \
                or tested.get("native_helper_executed_by_test_harness") is not supported \
                or tested.get("controls", {}).get("state") != ("passed" if supported else "unavailable") \
                or result["target"].startswith("linux-") and prerequisites["linux_syscall_observer"]["state"] != "available" \
                or any(tested.get(key) is not False for key in UNQUALIFIED):
            raise ValueError("native fixture scope differs from actual host")
        if supported:
            # Revalidate the exact sanitized method inventory, not a lone pass bit.
            tested_report = dict(tested["controls"], schema_version=1, evidence_kind="native_supervisor_unittest_outcomes",
                                 source=tested["source"], host=tested["host"], test_inputs=tested["test_inputs"],
                                 raw_exception_messages_retained=False, provider_controls_executed=False,
                                 native_abi_qualified=False, release_qualified=False)
            tested_report["cases"] = [{key: row[key] for key in ("id", "state", "skip_reason")} for row in tested_report["cases"]]
            outcome_metadata(tested_report, result["source"], result["host"], tested["test_inputs"])
        elif tested["controls"].get("tests_run") != 0 or tested["controls"].get("methods_not_executed") != len(CASES):
            raise ValueError("unsupported host cannot claim native fixture execution")
        inputs = input_digests(root)
        if prerequisites.get("build_inputs") != inputs:
            raise ValueError("selected build inputs changed")
        generated = root / RESOURCES
        expected = {PREFIX + "manifest": regular_input(generated / (PREFIX + "manifest"), root),
                    PREFIX + "build-provenance.json": regular_input(generated / (PREFIX + "build-provenance.json"), root)}
        binary_digest = manifest(expected[PREFIX + "manifest"], result["target"], inputs["source"])
        binary = b""
        if result["target"] in SUPERVISOR_TARGETS:
            name = PREFIX + result["target"] + "/supervisor"
            binary = regular_input(generated / name, root)
            if digest_bytes(binary) != binary_digest:
                raise ValueError("generated binary digest differs")
            result["native_binary_format"] = binary_format(binary, result["target"])
            expected[name] = binary
        actual = []
        allowed_dirs = {"/".join(name.split("/")[:index]) for name in expected for index in range(1, len(name.split("/")))}
        for index, path in enumerate(generated.rglob("*"), 1):
            relative = path.relative_to(generated).as_posix()
            if path.is_symlink():
                raise ValueError("generated resources contain an alias")
            if path.is_file():
                actual.append(relative)
            elif not path.is_dir() or relative not in allowed_dirs:
                raise ValueError("generated resources contain a nonregular or foreign directory entry")
            if index > 64 or len(actual) > 16:
                raise ValueError("generated native resources exceed their bound")
        if set(actual) != set(expected):
            raise ValueError("generated resources contain absent/foreign targets")
        receipt_bytes = expected[PREFIX + "build-provenance.json"]
        if not receipt_bytes.endswith(b"\n") or b"\r" in receipt_bytes:
            raise ValueError("native build receipt must use LF-terminated UTF-8 JSON")
        receipt = strict_json(receipt_bytes, limit=16 * 1024)
        result["build_receipt"] = receipt_metadata(receipt, prerequisites, binary_digest, len(binary))
        result["build_receipt_digest"] = digest_bytes(receipt_bytes)
        result["module_jar"] = jar_resources(root / MODULE / "build/libs" / JAR, expected, root)
        result["installed_jar"] = jar_resources(root / "modules/cli/build/install/ktchecker/lib" / JAR, expected, root)
        if result["module_jar"]["digest"] != result["installed_jar"]["digest"]:
            raise ValueError("installed runtime differs from built runtime")
        smoke_files = smoke.get("installation_files")
        if not isinstance(smoke_files, list) or [row for row in smoke_files if isinstance(row, dict) and row.get("name") == "lib/" + JAR] \
                != [{"name": "lib/" + JAR, "digest": result["installed_jar"]["digest"]}]:
            raise ValueError("doctor did not observe these exact installed runtime bytes")
        containment = smoke.get("doctor", {}).get("processContainment")
        if validate_containment(containment, result["target"]):
            raise ValueError("installed doctor containment metadata invalid")
        if binary and containment.get("identity") != {"protocolVersion": SUPERVISOR_PROTOCOL, "target": result["target"],
                                                       "sourceDigest": inputs["source"], "binaryDigest": binary_digest}:
            raise ValueError("installed doctor bundle identity differs")
        result.update(actual_task_outcome="EXECUTED", required_task=TASK,
                      runtime_test_task=TEST_TASK, runtime_test_task_outcome="EXECUTED",
                      native_compilation_recorded=bool(binary), native_binary_present=bool(binary),
                      helper_availability="available" if binary else "unavailable", installed_bundle_bytes_verified=bool(binary),
                      prerequisites_digest=digest_bytes(prerequisite_bytes), native_controls_digest=digest_bytes(control_bytes),
                      host_build_digest=digest_bytes(build_bytes), smoke_digest=digest_bytes(smoke_bytes))
    except (OSError, ValueError, TypeError, AttributeError, KeyError, RuntimeError, zipfile.BadZipFile, zlib.error):
        result["errors"].append("supervisor_actual_build_or_installed_byte_gate_failed")
    if not matching_source(source_identity(root), result["source"]):
        result["errors"].append("source_changed_during_supervisor_collection")
    result["state"] = "passed" if not result["errors"] else "failed"
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("preflight", "controls", "collect"), required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--environment", type=Path, default=REPO_ROOT / "build/ci-evidence/environment.json")
    parser.add_argument("--prerequisites", type=Path, default=REPO_ROOT / "build/ci-evidence/supervisor-prerequisites.json")
    parser.add_argument("--build", type=Path, default=REPO_ROOT / "build/ci-evidence/host-build.json")
    parser.add_argument("--smoke", type=Path, default=REPO_ROOT / "build/ci-evidence/distribution-smoke.json")
    parser.add_argument("--controls", type=Path, default=REPO_ROOT / "build/ci-evidence/supervisor-controls.json")
    parser.add_argument("--cc", default="/usr/bin/cc")
    parser.add_argument("--python-command", choices=("python3", "python"), default="python3")
    args = parser.parse_args()
    result = {"preflight": preflight, "controls": controls, "collect": collect}[args.phase](args)
    write_json(args.output, result)
    print(f"Supervisor {args.phase}: {result['state']}; target={result['target']}; execution/ABI/release unqualified")
    for code in result["errors"]:
        print(code, file=sys.stderr)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
