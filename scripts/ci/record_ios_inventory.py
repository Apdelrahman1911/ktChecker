#!/usr/bin/env python3
"""Optional bounded iOS availability diagnostics, never target selection."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import platform
import re
import selectors
import subprocess
import tempfile
import time

try:
    from .ci_common import REPO_ROOT, source_identity, utc_now
    from .record_environment import (
        JSON_MAX_DEPTH, JSON_MAX_TOKENS, PROBE_CLEANUP_SECONDS,
        SIMCTL_JSON_MAX_BYTES, probe_json, validate_simctl_inventory,
    )
except ImportError:
    from ci_common import REPO_ROOT, source_identity, utc_now
    from record_environment import (
        JSON_MAX_DEPTH, JSON_MAX_TOKENS, PROBE_CLEANUP_SECONDS,
        SIMCTL_JSON_MAX_BYTES, probe_json, validate_simctl_inventory,
    )

EXPECTED_DEVELOPER_DIR = "/Applications/Xcode_26.4.1.app/Contents/Developer"
EXPECTED_XCODE_VERSION = "26.4.1"
EXPECTED_XCODE_BUILD = "17E202"
EXPECTED_SDK_VERSION = "26.4"
PINNED_RUNTIME_VERSIONS = {"26.4.1"}
# This is the Apple simulator runtime build, not the selected Xcode build.
PINNED_RUNTIME_BUILD = "23E254a"
PINNED_DISPLAY_NAME = "iPhone 17"
PINNED_DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
TEXT_PROBES = {
    "xcode_version": ("/usr/bin/xcodebuild", "-version"),
    "iphoneos_sdk": ("/usr/bin/xcrun", "--sdk", "iphoneos", "--show-sdk-version"),
    "iphonesimulator_sdk": ("/usr/bin/xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"),
}
JSON_PROBES = {
    "runtimes": ("/usr/bin/xcrun", "simctl", "list", "runtimes", "--json"),
    # Unlike the execution preflight, diagnostics must observe unavailable rows.
    "devices": ("/usr/bin/xcrun", "simctl", "list", "devices", "--json"),
}
MAX_TEXT_BYTES = 16 * 1024
PROBE_TIMEOUT_SECONDS = 30
MAX_RUNTIME_ROWS = 1024
MAX_DEVICE_GROUPS = 256
MAX_DEVICE_ROWS = 4096
MAX_RUNTIME_SUMMARIES = 64
MAX_RECEIPT_BYTES = 64 * 1024
VERSION = re.compile(r"[1-9][0-9]?(?:\.(?:0|[1-9][0-9]?)){1,2}")
IOS_IDENTIFIER = re.compile(
    r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-([1-9][0-9]?)-(0|[1-9][0-9]?)(?:-(0|[1-9][0-9]?))?"
)
APPLE_BUILD = re.compile(r"[0-9]{1,3}[A-Z]{1,2}[0-9]{1,5}[a-z]?")
UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
PROBE_REASONS = {
    "pipe_probe_unavailable", "start_failure", "timeout", "stderr_output", "stdout_overflow",
    "nonzero_exit", "stream_failure", "cleanup_unverified", "invalid_utf8", "json_depth_limit",
    "json_token_limit", "malformed_json", "duplicate_json_keys", "nonfinite_json", "json_not_object",
    "invalid_inventory_schema", "inventory_identity_ambiguous", "inventory_row_bound_exceeded",
    "inventory_summary_bound_exceeded", "invalid_version_text", "probe_metadata_invalid", "probe_unavailable",
}
LIMITATIONS = [
    "Availability-only observation commands; no compilation, linking, simulator test execution or Native/Apple rule qualification.",
    "The simulator policy requires exact reported version 26.4.1/build 23E254a and a canonical observed iOS identifier. Target/SDK/device and Native FIR gates remain required; no fallback is selected.",
    "Display-name equality is an existing selector predicate, not hardware identity. Canonical device type is separately observed only when reported.",
    "Runtime version/build must match on the same observed row; the identifier suffix is not inferred from the version. Device state values are not simulator selection predicates; boot/shutdown states do not prove test execution.",
    "Only complete bounded stdout-only probes with accepted schemas support zero/absence counts; failed/not-probed remains unobserved.",
    "Trusted fresh/quiescent same-UID hosted state is assumed. Environment runner labels and path checks are not host attestation or race-proof binding.",
    "Each pipe probe has a cooperative 30-second deadline and at most two seconds of direct-child cleanup; process creation and descendant cleanup are not attested.",
    "Source binding reuses the unchanged repository identity helper and its bounded Git subprocess timeouts; the workflow step adds a four-minute outer timeout.",
    "Unknown/custom names, UUIDs, paths, raw streams and availability errors are never published; no runtime download, device creation or boot is requested.",
    "This optional standalone inventory is not an input to the collector's required command-pass gate and cannot qualify a tuple or release.",
]


class InventoryError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def not_probed(kind):
    result = {"kind": kind, "state": "unobserved", "reason": "not_probed", "return_code": None,
            "stdout_bytes_observed": 0, "stderr_bytes_observed": 0,
            "stdout_complete": False, "stderr_complete": False,
            "stdout_limit_bytes": MAX_TEXT_BYTES if kind in TEXT_PROBES else SIMCTL_JSON_MAX_BYTES,
            "timeout_seconds": PROBE_TIMEOUT_SECONDS, "cleanup_timeout_seconds": PROBE_CLEANUP_SECONDS,
            "cleanup_state": "not_started", "terminated_by_probe": False,
            "raw_output_retained": False, "elapsed_seconds": 0.0}
    if kind in JSON_PROBES:
        result.update(json_depth_limit=JSON_MAX_DEPTH, json_token_limit=JSON_MAX_TOKENS, json_stream="stdout")
    return result


def probe_text(kind):
    """Truly bound text while reading; never capture then clip an entire pipe."""
    if kind not in TEXT_PROBES:
        raise InventoryError("ios_inventory_probe_not_allowlisted")
    metadata = not_probed(kind)
    metadata.update(state="unavailable", reason=None)
    if os.name != "posix":
        metadata["reason"] = "pipe_probe_unavailable"
        return metadata, None
    started = time.monotonic()
    deadline = started + PROBE_TIMEOUT_SECONDS
    try:
        process = subprocess.Popen(list(TEXT_PROBES[kind]), stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0, shell=False)
    except OSError:
        metadata.update(reason="start_failure", elapsed_seconds=round(time.monotonic() - started, 3))
        return metadata, None
    reason = None
    stdout = bytearray()
    try:
        with selectors.DefaultSelector() as selector:
            for name, stream in (("stdout", process.stdout), ("stderr", process.stderr)):
                if stream is None:
                    raise ValueError("missing_pipe")
                os.set_blocking(stream.fileno(), False)
                selector.register(stream, selectors.EVENT_READ, name)
            while selector.get_map() and reason is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    reason = "timeout"
                    break
                for key, _ in selector.select(remaining):
                    size = min(16384, MAX_TEXT_BYTES - len(stdout) + 1) if key.data == "stdout" else 4096
                    try:
                        chunk = os.read(key.fd, size)
                    except BlockingIOError:
                        continue
                    metadata[key.data + "_bytes_observed"] += len(chunk)
                    if not chunk:
                        metadata[key.data + "_complete"] = True
                        selector.unregister(key.fileobj)
                    elif key.data == "stderr":
                        reason = "stderr_output"
                        break
                    elif len(stdout) + len(chunk) > MAX_TEXT_BYTES:
                        reason = "stdout_overflow"
                        break
                    else:
                        stdout.extend(chunk)
        if reason is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                reason = "timeout"
            else:
                metadata["return_code"] = process.wait(timeout=remaining)
                metadata["cleanup_state"] = "direct_child_reaped"
                if metadata["return_code"] != 0:
                    reason = "nonzero_exit"
    except subprocess.TimeoutExpired:
        reason = "timeout"
    except (OSError, ValueError):
        reason = "stream_failure"
    finally:
        for stream in (process.stdout, process.stderr):
            if stream is not None:
                try:
                    stream.close()
                except OSError:
                    reason = reason or "stream_failure"
        if metadata["cleanup_state"] != "direct_child_reaped":
            try:
                if process.poll() is None:
                    metadata["terminated_by_probe"] = True
                    process.kill()
                metadata["return_code"] = process.wait(timeout=PROBE_CLEANUP_SECONDS)
                metadata["cleanup_state"] = "direct_child_reaped"
            except (OSError, subprocess.TimeoutExpired):
                metadata["cleanup_state"] = "direct_child_cleanup_unverified"
                reason = reason or "cleanup_unverified"
    value = None
    if reason is None:
        try:
            value = bytes(stdout).decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            reason = "invalid_utf8"
        if time.monotonic() >= deadline:
            reason, value = "timeout", None
    metadata.update(state="passed" if reason is None else "unavailable", reason=reason,
                    elapsed_seconds=round(time.monotonic() - started, 3))
    return metadata, value if reason is None else None


def project_probe(kind, metadata):
    """Whitelist typed transport facts; do not forward arbitrary tool metadata."""
    result = not_probed(kind)
    if not isinstance(metadata, dict):
        result.update(state="unavailable", reason="probe_metadata_invalid")
        return result
    limit = result["stdout_limit_bytes"]
    integers = {"return_code": (-255, 255), "stdout_bytes_observed": (0, limit + 1),
                "stderr_bytes_observed": (0, 4096)}
    state = metadata.get("state")
    valid = isinstance(state, str) and state in {"passed", "unavailable"} and metadata.get("raw_output_retained") is False
    for name, (minimum, maximum) in integers.items():
        value = metadata.get(name)
        if name == "return_code" and value is None:
            result[name] = None
        elif type(value) is int and minimum <= value <= maximum:
            result[name] = value
        else:
            valid = False
    for name in ("stdout_complete", "stderr_complete", "terminated_by_probe"):
        value = metadata.get(name)
        if type(value) is bool:
            result[name] = value
        else:
            valid = False
    cleanup = metadata.get("cleanup_state")
    if isinstance(cleanup, str) and cleanup in {"not_started", "direct_child_reaped", "direct_child_cleanup_unverified"}:
        result["cleanup_state"] = cleanup
    else:
        valid = False
    elapsed = metadata.get("elapsed_seconds")
    if type(elapsed) in (int, float) and 0 <= elapsed <= 3600 and math.isfinite(elapsed):
        result["elapsed_seconds"] = round(elapsed, 3)
    else:
        valid = False
    for name, expected in (("stdout_limit_bytes", limit), ("timeout_seconds", PROBE_TIMEOUT_SECONDS),
                           ("cleanup_timeout_seconds", PROBE_CLEANUP_SECONDS)):
        if type(metadata.get(name)) is not int or metadata[name] != expected:
            valid = False
    if kind in JSON_PROBES:
        for name, expected in (("json_depth_limit", JSON_MAX_DEPTH), ("json_token_limit", JSON_MAX_TOKENS)):
            if type(metadata.get(name)) is not int or metadata[name] != expected:
                valid = False
        if metadata.get("json_stream") != "stdout":
            valid = False
    complete = valid and metadata.get("state") == "passed" and metadata.get("reason") is None \
        and result["return_code"] == 0 and result["stdout_complete"] and result["stderr_complete"] \
        and cleanup == "direct_child_reaped" and not result["terminated_by_probe"]
    reason = metadata.get("reason")
    result.update(state="passed" if complete else "unavailable",
                  reason=None if complete else (reason if isinstance(reason, str) and reason in PROBE_REASONS else
                                               "probe_unavailable" if valid else "probe_metadata_invalid"))
    return result


def parse_version_text(kind, text):
    if not isinstance(text, str):
        raise InventoryError("invalid_version_text")
    try:
        if len(text.encode("utf-8")) > MAX_TEXT_BYTES:
            raise InventoryError("invalid_version_text")
    except UnicodeError:
        raise InventoryError("invalid_version_text") from None
    text = text.replace("\r\n", "\n")
    if kind == "xcode_version":
        match = re.fullmatch(r"Xcode ([^\n]+)\nBuild version ([^\n]+)\n?", text)
        if not match or not VERSION.fullmatch(match[1]) or not APPLE_BUILD.fullmatch(match[2]):
            raise InventoryError("invalid_version_text")
        return {"version": match[1], "build": match[2]}
    if kind not in {"iphoneos_sdk", "iphonesimulator_sdk"}:
        raise InventoryError("ios_inventory_probe_not_allowlisted")
    match = re.fullmatch(r"([^\n]+)\n?", text)
    if not match or not VERSION.fullmatch(match[1]):
        raise InventoryError("invalid_version_text")
    return {"version": match[1]}


def recognized_runtime(row):
    return IOS_IDENTIFIER.fullmatch(row["identifier"]) is not None and VERSION.fullmatch(row["version"]) is not None


def validate_inventory(value, kind):
    if kind not in JSON_PROBES:
        raise InventoryError("ios_inventory_probe_not_allowlisted")
    try:
        validate_simctl_inventory(value, kind)
    except ValueError:
        raise InventoryError("invalid_inventory_schema") from None
    if kind == "runtimes":
        rows = value["runtimes"]
        if len(rows) > MAX_RUNTIME_ROWS:
            raise InventoryError("inventory_row_bound_exceeded")
        if len({row["identifier"] for row in rows}) != len(rows):
            raise InventoryError("inventory_identity_ambiguous")
        if sum(recognized_runtime(row) for row in rows) > MAX_RUNTIME_SUMMARIES:
            raise InventoryError("inventory_summary_bound_exceeded")
    elif kind == "devices":
        groups = value["devices"]
        rows = [row for group in groups.values() for row in group]
        if len(groups) > MAX_DEVICE_GROUPS or len(rows) > MAX_DEVICE_ROWS:
            raise InventoryError("inventory_row_bound_exceeded")
        if len({row["udid"].casefold() for row in rows}) != len(rows):
            raise InventoryError("inventory_identity_ambiguous")
        for row in rows:
            identifier = row.get("deviceTypeIdentifier")
            if identifier is not None and not (isinstance(identifier, str) and 0 < len(identifier) <= 256 and identifier.isprintable()):
                raise InventoryError("invalid_inventory_schema")
    else:
        raise InventoryError("ios_inventory_probe_not_allowlisted")


def runtime_summary(value):
    if value is None:
        return {"state": "unobserved", "complete": False, "total_rows": None,
                "recognized_ios_rows": None, "unpublished_rows": None, "rows": None}
    rows = value["runtimes"]
    public = []
    for row in sorted((row for row in rows if recognized_runtime(row)), key=lambda row: row["identifier"]):
        match = IOS_IDENTIFIER.fullmatch(row["identifier"])
        identifier_version = tuple(int(component) for component in match.groups() if component is not None)
        version = tuple(int(component) for component in row["version"].split("."))
        public.append({"identifier": row["identifier"], "version": row["version"], "available": row["isAvailable"],
                       "pinned_build_match": row.get("buildversion") == PINNED_RUNTIME_BUILD,
                       "version_identifier_consistent": (version + (0,))[:3] == (identifier_version + (0,))[:3]})
    return {"state": "observed", "complete": True, "total_rows": len(rows),
            "recognized_ios_rows": len(public), "unpublished_rows": len(rows) - len(public), "rows": public}


def device_summary(value):
    if value is None:
        return {"state": "unobserved", "complete": False, "groups": None, "total_rows": None,
                "available_rows": None, "unpublished_display_name_rows": None,
                "pinned_type_rows": None, "unpublished_type_rows": None, "missing_type_rows": None,
                "states": {key: None for key in ("Booted", "Shutdown", "other", "unobserved")}}
    groups = value["devices"]
    rows = [row for group in groups.values() for row in group]
    states = {key: 0 for key in ("Booted", "Shutdown", "other", "unobserved")}
    for row in rows:
        state = row.get("state")
        states[state if state in {"Booted", "Shutdown"} else "unobserved" if state is None else "other"] += 1
    return {"state": "observed", "complete": True, "groups": len(groups), "total_rows": len(rows),
            "available_rows": sum(row["isAvailable"] for row in rows),
            "unpublished_display_name_rows": sum(row["name"] != PINNED_DISPLAY_NAME for row in rows),
            "pinned_type_rows": sum(row.get("deviceTypeIdentifier") == PINNED_DEVICE_TYPE for row in rows),
            "unpublished_type_rows": sum(row.get("deviceTypeIdentifier") not in {None, PINNED_DEVICE_TYPE} for row in rows),
            "missing_type_rows": sum(row.get("deviceTypeIdentifier") is None for row in rows), "states": states}


def diagnose_simulator(runtimes, devices):
    keys = ("runtime_rows", "version_rows", "identifier_rows", "version_identifier_rows", "build_rows",
            "version_identifier_build_rows", "available_runtime_rows",
            "matching_device_groups", "device_group_rows", "display_name_rows", "available_display_name_rows", "valid_uuid_rows")
    counts = {key: None for key in keys}
    eligible, names = set(), []
    if runtimes is not None:
        rows = runtimes["runtimes"]
        version_rows = [row for row in rows if row["version"] in PINNED_RUNTIME_VERSIONS]
        intersections = [row for row in version_rows if IOS_IDENTIFIER.fullmatch(row["identifier"]) is not None]
        build_matches = [row for row in intersections if row.get("buildversion") == PINNED_RUNTIME_BUILD]
        available = [row for row in build_matches if row["isAvailable"]]
        eligible = {row["identifier"] for row in available}
        counts.update(runtime_rows=len(rows), version_rows=len(version_rows),
                      identifier_rows=sum(IOS_IDENTIFIER.fullmatch(row["identifier"]) is not None for row in rows),
                      version_identifier_rows=len(intersections),
                      build_rows=sum(row.get("buildversion") == PINNED_RUNTIME_BUILD for row in rows),
                      version_identifier_build_rows=len(build_matches), available_runtime_rows=len(available))
    if runtimes is not None and devices is not None:
        groups = devices["devices"]
        rows = [row for identifier in eligible for row in groups.get(identifier, [])]
        names = [row for row in rows if row["name"] == PINNED_DISPLAY_NAME]
        available = [row for row in names if row["isAvailable"]]
        counts.update(matching_device_groups=sum(identifier in groups for identifier in eligible), device_group_rows=len(rows),
                      display_name_rows=len(names), available_display_name_rows=len(available),
                      valid_uuid_rows=sum(UUID.fullmatch(row["udid"]) is not None for row in available))
    code = None
    if runtimes is None or devices is None:
        state = "unobserved"
        code = "runtime_inventory_unobserved" if runtimes is None else "device_inventory_unobserved"
    else:
        ordered = (("version_rows", "pinned_runtime_version_absent"),
                   ("version_identifier_rows", "pinned_runtime_identifier_mismatch"),
                   ("version_identifier_build_rows", "pinned_runtime_build_mismatch"),
                   ("available_runtime_rows", "pinned_runtime_unavailable"),
                   ("matching_device_groups", "pinned_device_runtime_group_absent"),
                   ("display_name_rows", "pinned_device_name_absent"),
                   ("available_display_name_rows", "pinned_device_unavailable"),
                   ("valid_uuid_rows", "pinned_device_uuid_invalid"))
        code = next((reason for field, reason in ordered if counts[field] == 0), None)
        state = "available" if code is None else "unavailable"
    canonical = {"state": "unobserved", "reported_rows": None, "pinned_type_rows": None,
                 "other_type_rows": None, "missing_type_rows": None, "used_by_existing_selector": False}
    if runtimes is not None and devices is not None:
        missing = sum(row.get("deviceTypeIdentifier") is None for row in names)
        recognized = sum(row.get("deviceTypeIdentifier") == PINNED_DEVICE_TYPE for row in names)
        canonical.update(state="complete" if recognized == len(names) and names else "partial" if recognized else "unobserved",
                         reported_rows=len(names) - missing,
                         pinned_type_rows=sum(row.get("deviceTypeIdentifier") == PINNED_DEVICE_TYPE for row in names),
                         other_type_rows=sum(row.get("deviceTypeIdentifier") not in {None, PINNED_DEVICE_TYPE} for row in names),
                         missing_type_rows=missing)
    return {"state": state, "fixed_mismatch_code": code, "predicate_counts": counts,
            "canonical_device_type": canonical,
            "scope": "Exact observed runtime version/build and iOS identifier structure; device groups join to that exact observed identifier. Display name is not hardware identity; device state is not a predicate."}


def availability_alternatives(runtimes, devices):
    result = {"state": "unobserved", "rows": None, "fallback_selected": False,
              "scope": "Reported runtime/device availability only; owner must choose and separately validate any revised tuple."}
    if runtimes is None or devices is None:
        return result
    rows = []
    for runtime in runtime_summary(runtimes)["rows"]:
        group = devices["devices"].get(runtime["identifier"], [])
        rows.append({**runtime, "device_rows": len(group), "available_device_rows": sum(row["isAvailable"] for row in group),
                     "available_pinned_name_rows": sum(row["isAvailable"] and row["name"] == PINNED_DISPLAY_NAME for row in group),
                     "available_pinned_type_rows": sum(row["isAvailable"] and row.get("deviceTypeIdentifier") == PINNED_DEVICE_TYPE for row in group),
                     "missing_device_type_rows": sum(row.get("deviceTypeIdentifier") is None for row in group)})
    result.update(state="observed", rows=rows)
    return result


def hosted_run_binding():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" \
            or os.environ.get("RUNNER_OS") != "macOS" or os.environ.get("RUNNER_ARCH") != "ARM64":
        raise InventoryError("ios_inventory_hosted_context_required")
    values = {key: os.environ.get(variable, "") for key, variable in
              (("id", "GITHUB_RUN_ID"), ("attempt", "GITHUB_RUN_ATTEMPT"), ("job", "GITHUB_JOB"))}
    if any(not re.fullmatch(r"[1-9][0-9]{0,19}", values[key]) for key in ("id", "attempt")) or values["job"] != "ios-native":
        raise InventoryError("ios_inventory_run_binding_unavailable")
    return values


def bound_source(value):
    if not isinstance(value, dict) or not isinstance(value.get("commit"), str) \
            or not re.fullmatch(r"[0-9a-f]{40}(?:[0-9a-f]{24})?", value["commit"]) \
            or not isinstance(value.get("working_tree_digest"), str) \
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", value["working_tree_digest"]) \
            or type(value.get("dirty")) is not bool or type(value.get("file_count")) is not int \
            or not 0 <= value["file_count"] <= 100000:
        raise InventoryError("ios_inventory_source_binding_unavailable")
    return {key: value[key] for key in ("commit", "working_tree_digest", "dirty", "file_count")}


def observe(root=REPO_ROOT):
    kinds = (*TEXT_PROBES, *JSON_PROBES)
    result = {"schema_version": 1, "evidence_kind": "optional_ios_availability_inventory",
              "recorded_at": utc_now(), "state": "unavailable", "errors": [],
              "diagnostic_completion": {"state": "unobserved", "complete_probe_count": 0, "required_probe_count": 5,
                                        "binding_complete": False},
              "source_binding": {"state": "unobserved"}, "run_binding": {"state": "unobserved"},
              "host": {"os": "Darwin" if platform.system() == "Darwin" else "other",
                       "arch": "arm64" if platform.machine() in {"arm64", "aarch64"} else "other"},
              "expected": {"xcode_version": EXPECTED_XCODE_VERSION, "xcode_build": EXPECTED_XCODE_BUILD,
                           "iphoneos_sdk": EXPECTED_SDK_VERSION, "iphonesimulator_sdk": EXPECTED_SDK_VERSION,
                           "runtime_versions": sorted(PINNED_RUNTIME_VERSIONS), "runtime_build": PINNED_RUNTIME_BUILD,
                           "runtime_identifier_policy": "canonical iOS identifier observed in bounded trusted inventory; not derived from version",
                           "legacy_display_name": PINNED_DISPLAY_NAME, "canonical_device_type": PINNED_DEVICE_TYPE},
              "selected_xcode": {"developer_dir_matches_expected": False, "version": None, "build": None,
                                 "pinned_version_build_match": None, "iphoneos_sdk": None, "iphonesimulator_sdk": None,
                                 "pinned_sdk_match": None},
              "probes": {kind: not_probed(kind) for kind in kinds},
              "runtime_inventory": runtime_summary(None), "device_inventory": device_summary(None),
              "simulator_selector": diagnose_simulator(None, None),
              "availability_alternatives": availability_alternatives(None, None),
              "pinned_tuple": {"state": "unobserved", "fixed_mismatch_code": "inventory_not_completed"},
              "limits": {"text_stdout_bytes": MAX_TEXT_BYTES, "json_stdout_bytes": SIMCTL_JSON_MAX_BYTES,
                         "json_depth": JSON_MAX_DEPTH, "json_tokens": JSON_MAX_TOKENS,
                         "probe_timeout_seconds": PROBE_TIMEOUT_SECONDS, "cleanup_seconds": PROBE_CLEANUP_SECONDS,
                         "runtime_rows": MAX_RUNTIME_ROWS, "device_groups": MAX_DEVICE_GROUPS, "device_rows": MAX_DEVICE_ROWS,
                         "runtime_summaries": MAX_RUNTIME_SUMMARIES, "receipt_bytes": MAX_RECEIPT_BYTES},
              "collector_required_input": False, "included_in_collector_command_results": False,
              "target_selector_changed": False, "matrix_changed": False,
              "fallback_selected": False, "runtime_download_requested": False, "device_creation_requested": False,
              "simulator_boot_requested": False, "target_compilation_executed": False, "target_runtime_executed": False,
              "tuple_qualified": False, "custom_rules_qualified": False, "release_qualified": False,
              "raw_output_retained": False, "limitations": LIMITATIONS}
    inventories = {"runtimes": None, "devices": None}
    try:
        if result["host"]["os"] != "Darwin":
            raise InventoryError("ios_inventory_host_os_unavailable")
        if result["host"]["arch"] != "arm64":
            raise InventoryError("ios_inventory_host_arch_unavailable")
        run = hosted_run_binding()
        result.update(run=run)
        result["run_binding"] = {"state": "pending", "before": run}
        if os.environ.get("DEVELOPER_DIR") != EXPECTED_DEVELOPER_DIR:
            raise InventoryError("ios_inventory_explicit_xcode_selection_required")
        result["selected_xcode"]["developer_dir_matches_expected"] = True
        source = bound_source(source_identity(root))
        result.update(source=source)
        result["source_binding"] = {"state": "pending", "before": source}
        for kind in TEXT_PROBES:
            metadata, value = not_probed(kind), None
            try:
                transport, text = probe_text(kind)
                metadata = project_probe(kind, transport)
                if metadata["state"] == "passed":
                    value = parse_version_text(kind, text)
            except InventoryError:
                # Parsing is distinct from transport: preserve observed typed
                # byte/EOF/reaping facts, but never accept a clipped prefix.
                metadata.update(state="unavailable", reason="invalid_version_text")
            except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, OverflowError, subprocess.SubprocessError):
                metadata.update(state="unavailable", reason="probe_unavailable")
            result["probes"][kind] = metadata
            if value is not None:
                if kind == "xcode_version":
                    result["selected_xcode"].update(version=value["version"], build=value["build"],
                        pinned_version_build_match=value["version"] == EXPECTED_XCODE_VERSION and value["build"] == EXPECTED_XCODE_BUILD)
                else:
                    result["selected_xcode"][kind] = value["version"]
        sdk = result["selected_xcode"]
        if sdk["iphoneos_sdk"] is not None and sdk["iphonesimulator_sdk"] is not None:
            sdk["pinned_sdk_match"] = sdk["iphoneos_sdk"] == sdk["iphonesimulator_sdk"] == EXPECTED_SDK_VERSION
        for kind, argv in JSON_PROBES.items():
            metadata, value = not_probed(kind), None
            try:
                transport, value = probe_json(list(argv), timeout=PROBE_TIMEOUT_SECONDS, max_stdout_bytes=SIMCTL_JSON_MAX_BYTES)
                metadata = project_probe(kind, transport)
                if metadata["state"] == "passed":
                    try:
                        validate_inventory(value, kind)
                    except InventoryError as error:
                        metadata.update(state="unavailable", reason=error.code)
                        value = None
                else:
                    value = None
            except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, OverflowError, subprocess.SubprocessError):
                value = None
                metadata.update(state="unavailable", reason="probe_unavailable")
            result["probes"][kind], inventories[kind] = metadata, value
        runtimes, devices = inventories["runtimes"], inventories["devices"]
        result.update(runtime_inventory=runtime_summary(runtimes), device_inventory=device_summary(devices),
                      simulator_selector=diagnose_simulator(runtimes, devices),
                      availability_alternatives=availability_alternatives(runtimes, devices))
        after = bound_source(source_identity(root))
        result["source_binding"]["after"] = after
        if source != after:
            result["source_binding"]["state"] = "changed"
            raise InventoryError("ios_inventory_source_changed_during_observation")
        after_run = hosted_run_binding()
        result["run_binding"]["after"] = after_run
        if run != after_run:
            result["run_binding"]["state"] = "changed"
            raise InventoryError("ios_inventory_run_changed_during_observation")
        if os.environ.get("DEVELOPER_DIR") != EXPECTED_DEVELOPER_DIR:
            result["selected_xcode"]["developer_dir_matches_expected"] = False
            raise InventoryError("ios_inventory_xcode_selection_changed_during_observation")
        result["source_binding"]["state"] = result["run_binding"]["state"] = "bound"
        complete = sum(metadata["state"] == "passed" for metadata in result["probes"].values())
        result["diagnostic_completion"].update(state="complete" if complete == 5 else "incomplete", complete_probe_count=complete,
                                              binding_complete=True)
        if complete == 5:
            result["state"] = "passed"
        else:
            result["errors"].append("ios_inventory_query_incomplete")
        if sdk["pinned_version_build_match"] is None or sdk["pinned_sdk_match"] is None:
            result["pinned_tuple"] = {"state": "unobserved", "fixed_mismatch_code": "pinned_toolchain_inventory_unobserved"}
        elif not sdk["pinned_version_build_match"]:
            result["pinned_tuple"] = {"state": "unavailable", "fixed_mismatch_code": "pinned_xcode_version_or_build_mismatch"}
        elif not sdk["pinned_sdk_match"]:
            result["pinned_tuple"] = {"state": "unavailable", "fixed_mismatch_code": "pinned_ios_sdk_mismatch"}
        else:
            result["pinned_tuple"] = {key: result["simulator_selector"][key] for key in ("state", "fixed_mismatch_code")}
    except InventoryError as error:
        result["errors"].append(error.code)
        result["pinned_tuple"] = {"state": "unobserved", "fixed_mismatch_code": error.code}
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, OverflowError, subprocess.SubprocessError):
        result["errors"].append("ios_inventory_observation_unavailable")
        result["pinned_tuple"] = {"state": "unobserved", "fixed_mismatch_code": "ios_inventory_observation_unavailable"}
    complete = sum(metadata["state"] == "passed" for metadata in result["probes"].values())
    result["diagnostic_completion"]["complete_probe_count"] = complete
    if result["errors"] and any(metadata["state"] != "unobserved" for metadata in result["probes"].values()):
        result["diagnostic_completion"]["state"] = "incomplete"
    result["availability_alternatives"]["binding_state"] = "bound" if result["diagnostic_completion"]["binding_complete"] else "unbound"
    result["finished_at"] = utc_now()
    return result


def output_path(path, root=REPO_ROOT):
    root = root.resolve(strict=True)
    path = path if path.is_absolute() else root / path
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        raise InventoryError("ios_inventory_output_path_invalid") from None
    if len(parts) != 3 or parts[:2] != ("build", "ci-evidence") or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}\.json", parts[2]):
        raise InventoryError("ios_inventory_output_path_invalid")
    current = root
    for part in parts[:-1]:
        current = current / part
        if current.is_symlink() or current.exists() and not current.is_dir():
            raise InventoryError("ios_inventory_output_path_invalid")
    if path.exists() or path.is_symlink():
        raise InventoryError("ios_inventory_output_not_fresh")
    return path


def publish(path, value):
    try:
        data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + "\n").encode("utf-8")
    except (ValueError, TypeError, OverflowError, RecursionError):
        raise InventoryError("ios_inventory_publication_failed") from None
    if len(data) > MAX_RECEIPT_BYTES:
        raise InventoryError("ios_inventory_receipt_bound_exceeded")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() or path.is_symlink():
        raise InventoryError("ios_inventory_output_not_fresh")
    temporary = None
    try:
        descriptor, name = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".pending", dir=path.parent)
        temporary = Path(name)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        os.link(temporary, path)
    except FileExistsError:
        raise InventoryError("ios_inventory_output_not_fresh") from None
    except (OSError, ValueError):
        raise InventoryError("ios_inventory_publication_failed") from None
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except OSError:
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/ci-evidence/ios-inventory.json"))
    args = parser.parse_args()
    try:
        path = output_path(args.output)
        result = observe()
        publish(path, result)
    except InventoryError as error:
        print(error.code)
        return 1
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, OverflowError):
        print("ios_inventory_publication_failed")
        return 1
    print("Optional iOS availability inventory: " + result["diagnostic_completion"]["state"] +
          "; pinned tuple " + result["pinned_tuple"]["state"] + "; no execution/qualification/fallback")
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
