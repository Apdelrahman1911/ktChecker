#!/usr/bin/env python3
"""Mock-only controls for optional diagnostics; never invoke an Apple tool."""

import ast
from contextlib import ExitStack, redirect_stderr, redirect_stdout
import copy
import io
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

if __package__:
    from . import record_ios_inventory as inventory
    from . import record_environment as environment
else:
    import record_ios_inventory as inventory
    import record_environment as environment

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
OTHER_RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-3"
UDID = "11111111-2222-3333-4444-555555555555"
PRIVATE = "PRIVATE_DIAGNOSTIC_OWNER_DO_NOT_PUBLISH"
PRIVATE_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
SOURCE = {"commit": "a" * 40, "working_tree_digest": "sha256:" + "b" * 64,
          "dirty": False, "file_count": 10, "private_extra": PRIVATE}
HOSTED = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "macOS",
          "RUNNER_ARCH": "ARM64", "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_JOB": "ios-native",
          "DEVELOPER_DIR": inventory.EXPECTED_DEVELOPER_DIR}
TEXT = {"xcode_version": "Xcode 26.4.1\nBuild version 17E202\n",
        "iphoneos_sdk": "26.4\n", "iphonesimulator_sdk": "26.4\n"}
MISSING = object()


def runtime(identifier=RUNTIME, version="26.4", available=True, **extra):
    return {"identifier": identifier, "version": version, "isAvailable": available, **extra}


def device(name="iPhone 17", available=True, udid=UDID, **extra):
    return {"name": name, "isAvailable": available, "udid": udid, **extra}


def transport(kind, **extra):
    result = inventory.not_probed(kind)
    result.update(state="passed", reason=None, return_code=0, stdout_bytes_observed=100,
                  stdout_complete=True, stderr_complete=True, cleanup_state="direct_child_reaped")
    result.update(extra)
    return result


class IOSInventoryTests(unittest.TestCase):
    def observe_case(self, *, runtimes=MISSING, devices=MISSING, text_values=None, text_metadata=None,
                     json_metadata=None, probe_errors=None, source_values=None, source_error=None,
                     env_updates=None, system="Darwin", machine="arm64", during_json=None):
        if runtimes is MISSING:
            runtimes = {"runtimes": [runtime()]}
        if devices is MISSING:
            devices = {"devices": {RUNTIME: [device(deviceTypeIdentifier=inventory.PINNED_DEVICE_TYPE)]}}
        values = dict(TEXT, **(text_values or {}))
        text_metadata, json_metadata, probe_errors = text_metadata or {}, json_metadata or {}, probe_errors or {}
        queries = []

        def text_probe(kind):
            queries.append(("text", kind))
            if kind in probe_errors:
                raise probe_errors[kind]
            return copy.deepcopy(text_metadata.get(kind, transport(kind))), values[kind]

        def json_probe(argv, *, timeout, max_stdout_bytes):
            kind = next(kind for kind, expected in inventory.JSON_PROBES.items() if tuple(argv) == expected)
            queries.append(("json", tuple(argv), timeout, max_stdout_bytes))
            if during_json:
                during_json(kind)
            if kind in probe_errors:
                raise probe_errors[kind]
            value = runtimes if kind == "runtimes" else devices
            metadata = json_metadata.get(kind, transport(kind) if value is not None else
                                         transport(kind, state="unavailable", reason="timeout", return_code=-9,
                                                   stdout_complete=False, terminated_by_probe=True))
            return copy.deepcopy(metadata), copy.deepcopy(value)

        with ExitStack() as stack:
            stack.enter_context(mock.patch.dict(os.environ, dict(HOSTED, **(env_updates or {})), clear=True))
            stack.enter_context(mock.patch.object(inventory.platform, "system", return_value=system))
            stack.enter_context(mock.patch.object(inventory.platform, "machine", return_value=machine))
            stack.enter_context(mock.patch.object(inventory, "utc_now", return_value="2026-11-04T00:00:00+00:00"))
            source = stack.enter_context(mock.patch.object(inventory, "source_identity",
                                         side_effect=source_error if source_error else source_values,
                                         return_value=copy.deepcopy(SOURCE)))
            stack.enter_context(mock.patch.object(inventory, "probe_text", side_effect=text_probe))
            stack.enter_context(mock.patch.object(inventory, "probe_json", side_effect=json_probe))
            result = inventory.observe(ROOT)
            return result, queries, source.call_count

    def check_mismatch(self, runtimes, devices, code):
        result, _, _ = self.observe_case(runtimes=runtimes, devices=devices)
        self.assertEqual(result["state"], "passed")
        self.assertEqual(result["diagnostic_completion"]["complete_probe_count"], 5)
        self.assertEqual(result["pinned_tuple"], {"state": "unavailable", "fixed_mismatch_code": code})
        self.assertFalse(result["tuple_qualified"])

    def test_exactly_five_fixed_read_only_queries_and_all_devices(self):
        self.assertEqual(inventory.TEXT_PROBES, {
            "xcode_version": ("/usr/bin/xcodebuild", "-version"),
            "iphoneos_sdk": ("/usr/bin/xcrun", "--sdk", "iphoneos", "--show-sdk-version"),
            "iphonesimulator_sdk": ("/usr/bin/xcrun", "--sdk", "iphonesimulator", "--show-sdk-version")})
        self.assertEqual(inventory.JSON_PROBES, {
            "runtimes": ("/usr/bin/xcrun", "simctl", "list", "runtimes", "--json"),
            "devices": ("/usr/bin/xcrun", "simctl", "list", "devices", "--json")})
        result, queries, source_calls = self.observe_case()
        self.assertEqual(queries, [("text", kind) for kind in inventory.TEXT_PROBES] +
                         [("json", argv, 30, 1048576) for argv in inventory.JSON_PROBES.values()])
        self.assertEqual(source_calls, 2)
        self.assertEqual(result["state"], "passed")
        self.assertNotIn("available", inventory.JSON_PROBES["devices"])

    def test_non_darwin_or_non_arm64_never_probes(self):
        for system, machine, code in [("Linux", "aarch64", "ios_inventory_host_os_unavailable"),
                                      (PRIVATE, PRIVATE, "ios_inventory_host_os_unavailable"),
                                      ("Darwin", "x86_64", "ios_inventory_host_arch_unavailable")]:
            with self.subTest(system=system, machine=machine):
                result, queries, count = self.observe_case(system=system, machine=machine)
                self.assertEqual(queries, [])
                self.assertEqual(count, 0)
                self.assertEqual(result["errors"], [code])
                self.assertIsNone(result["runtime_inventory"]["total_rows"])
                self.assertNotIn(PRIVATE, json.dumps(result))

    def test_explicit_xcode_selection_is_required_before_source_or_queries(self):
        for value in ("", "/Applications/" + PRIVATE):
            result, queries, count = self.observe_case(env_updates={"DEVELOPER_DIR": value})
            self.assertEqual(result["errors"], ["ios_inventory_explicit_xcode_selection_required"])
            self.assertEqual((queries, count), ([], 0))
            self.assertNotIn(PRIVATE, json.dumps(result))

    def test_hosted_runner_context_is_required(self):
        for key, value in [("GITHUB_ACTIONS", "false"), ("RUNNER_ENVIRONMENT", "self-hosted"),
                           ("RUNNER_OS", "Linux"), ("RUNNER_ARCH", "X64")]:
            with self.subTest(key=key):
                result, queries, count = self.observe_case(env_updates={key: value})
                self.assertEqual(result["errors"], ["ios_inventory_hosted_context_required"])
                self.assertEqual((queries, count), ([], 0))

    def test_run_binding_identifiers_are_bounded_and_exact(self):
        for key, value in [("GITHUB_RUN_ID", "0"), ("GITHUB_RUN_ID", "1" * 21), ("GITHUB_RUN_ID", PRIVATE),
                           ("GITHUB_RUN_ATTEMPT", "01"), ("GITHUB_RUN_ATTEMPT", ""), ("GITHUB_JOB", PRIVATE)]:
            with self.subTest(key=key, value=value):
                result, queries, count = self.observe_case(env_updates={key: value})
                self.assertEqual(result["errors"], ["ios_inventory_run_binding_unavailable"])
                self.assertEqual((queries, count), ([], 0))
                self.assertNotIn(PRIVATE, json.dumps(result))

    def test_source_binding_validates_types_and_bounds_before_queries(self):
        for key, value in [("commit", PRIVATE), ("working_tree_digest", PRIVATE), ("dirty", 0),
                           ("file_count", True), ("file_count", -1), ("file_count", 100001)]:
            with self.subTest(key=key):
                source = dict(SOURCE, **{key: value})
                result, queries, count = self.observe_case(source_values=[source])
                self.assertEqual(result["errors"], ["ios_inventory_source_binding_unavailable"])
                self.assertEqual((queries, count), ([], 1))
                self.assertNotIn(PRIVATE, json.dumps(result))

    def test_source_and_run_projection_has_only_fixed_fields(self):
        result, _, _ = self.observe_case(env_updates={"GITHUB_REPOSITORY": PRIVATE, "OWNER_SECRET": PRIVATE})
        self.assertEqual(result["source_binding"]["state"], "bound")
        self.assertEqual(result["run_binding"]["state"], "bound")
        self.assertEqual(set(result["source"]), {"commit", "working_tree_digest", "dirty", "file_count"})
        self.assertEqual(result["run"], {"id": "123", "attempt": "1", "job": "ios-native"})
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_source_change_preserves_probe_completion_but_unbinds_tuple(self):
        after = dict(SOURCE, working_tree_digest="sha256:" + "c" * 64)
        result, queries, _ = self.observe_case(source_values=[SOURCE, after])
        self.assertEqual(len(queries), 5)
        self.assertEqual(result["state"], "unavailable")
        self.assertEqual(result["source_binding"]["state"], "changed")
        self.assertEqual(result["diagnostic_completion"], {"state": "incomplete", "complete_probe_count": 5,
                                                         "required_probe_count": 5, "binding_complete": False})
        self.assertEqual(result["pinned_tuple"]["state"], "unobserved")
        self.assertEqual(result["availability_alternatives"]["binding_state"], "unbound")
        self.assertEqual(result["runtime_inventory"]["total_rows"], 1)

    def test_source_failures_before_and_after_are_fixed_and_sanitized(self):
        for sequence, expected_queries in [([OSError(PRIVATE)], 0), ([SOURCE, RuntimeError(PRIVATE)], 5)]:
            result, queries, _ = self.observe_case(source_values=sequence)
            self.assertEqual(len(queries), expected_queries)
            self.assertEqual(result["errors"], ["ios_inventory_observation_unavailable"])
            self.assertFalse(result["diagnostic_completion"]["binding_complete"])
            self.assertNotIn(PRIVATE, json.dumps(result))

    def test_run_change_during_queries_is_not_a_bound_receipt(self):
        def change(kind):
            if kind == "devices":
                os.environ["GITHUB_RUN_ATTEMPT"] = "2"
        result, _, _ = self.observe_case(during_json=change)
        self.assertEqual(result["errors"], ["ios_inventory_run_changed_during_observation"])
        self.assertEqual(result["run_binding"]["state"], "changed")
        self.assertEqual(result["diagnostic_completion"]["complete_probe_count"], 5)
        self.assertEqual(result["pinned_tuple"]["state"], "unobserved")

    def test_xcode_selection_change_during_queries_is_unobserved(self):
        def change(kind):
            if kind == "devices":
                os.environ["DEVELOPER_DIR"] = "/Applications/" + PRIVATE
        result, _, _ = self.observe_case(during_json=change)
        self.assertEqual(result["errors"], ["ios_inventory_xcode_selection_changed_during_observation"])
        self.assertFalse(result["selected_xcode"]["developer_dir_matches_expected"])
        self.assertFalse(result["diagnostic_completion"]["binding_complete"])
        self.assertEqual(result["pinned_tuple"]["state"], "unobserved")
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_complete_available_observation_is_not_qualification(self):
        result, _, _ = self.observe_case()
        self.assertEqual(result["pinned_tuple"], {"state": "available", "fixed_mismatch_code": None})
        self.assertEqual(result["diagnostic_completion"], {"state": "complete", "complete_probe_count": 5,
                                                         "required_probe_count": 5, "binding_complete": True})
        for key in ("collector_required_input", "included_in_collector_command_results", "target_selector_changed",
                    "matrix_changed", "fallback_selected", "runtime_download_requested", "device_creation_requested",
                    "simulator_boot_requested", "target_compilation_executed", "target_runtime_executed", "tuple_qualified",
                    "custom_rules_qualified", "release_qualified", "raw_output_retained"):
            self.assertIs(result[key], False, key)
        self.assertLess(len(json.dumps(result, ensure_ascii=True, indent=2).encode("utf-8")), 65536)

    def test_toolchain_mismatch_can_be_a_complete_successful_diagnostic(self):
        for values, code in [({"xcode_version": "Xcode 26.4.2\nBuild version 17E203\n"}, "pinned_xcode_version_or_build_mismatch"),
                             ({"iphoneos_sdk": "26.3\n"}, "pinned_ios_sdk_mismatch")]:
            result, _, _ = self.observe_case(text_values=values)
            self.assertEqual(result["state"], "passed")
            self.assertEqual(result["diagnostic_completion"]["state"], "complete")
            self.assertEqual(result["pinned_tuple"], {"state": "unavailable", "fixed_mismatch_code": code})
            self.assertFalse(result["release_qualified"])

    def test_invalid_version_preserves_complete_transport_facts_not_value(self):
        result, _, _ = self.observe_case(text_values={"xcode_version": "Xcode 26.4.1\nBuild version " + PRIVATE + "\n"})
        metadata = result["probes"]["xcode_version"]
        self.assertEqual(metadata["reason"], "invalid_version_text")
        self.assertEqual(metadata["stdout_bytes_observed"], 100)
        self.assertTrue(metadata["stdout_complete"])
        self.assertTrue(metadata["stderr_complete"])
        self.assertEqual(metadata["cleanup_state"], "direct_child_reaped")
        self.assertEqual(result["diagnostic_completion"]["complete_probe_count"], 4)
        self.assertIsNone(result["selected_xcode"]["version"])
        self.assertEqual(result["pinned_tuple"]["state"], "unobserved")
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_probe_exceptions_are_sanitized_and_other_probes_continue(self):
        for kind in (*inventory.TEXT_PROBES, *inventory.JSON_PROBES):
            with self.subTest(kind=kind):
                result, queries, _ = self.observe_case(probe_errors={kind: RuntimeError(PRIVATE)})
                self.assertEqual(len(queries), 5)
                self.assertEqual(result["probes"][kind]["reason"], "probe_unavailable")
                self.assertEqual(result["diagnostic_completion"]["complete_probe_count"], 4)
                self.assertEqual(result["state"], "unavailable")
                self.assertNotIn(PRIVATE, json.dumps(result))

    def test_failed_json_probe_discards_even_plausible_parsed_payload(self):
        for kind in inventory.JSON_PROBES:
            for reason in ("timeout", "stdout_overflow", "invalid_utf8", "malformed_json", "stderr_output", "nonzero_exit"):
                with self.subTest(kind=kind, reason=reason):
                    metadata = transport(kind, state="unavailable", reason=reason)
                    result, _, _ = self.observe_case(json_metadata={kind: metadata})
                    summary = result["runtime_inventory" if kind == "runtimes" else "device_inventory"]
                    self.assertEqual(summary["state"], "unobserved")
                    self.assertIsNone(summary["total_rows"])
                    self.assertIsNone(result["availability_alternatives"]["rows"])
                    self.assertEqual(result["pinned_tuple"]["state"], "unobserved")

    def test_invalid_inventory_schemas_are_not_complete_zero_counts(self):
        for kind, value in [("runtimes", {}), ("runtimes", {"runtimes": [dict(runtime(), isAvailable=1)]}),
                            ("devices", {}), ("devices", {"devices": []}),
                            ("devices", {"devices": {RUNTIME: [dict(device(), name=None)]}})]:
            with self.subTest(kind=kind, shape=type(value).__name__):
                result, _, _ = self.observe_case(**{kind: value})
                self.assertEqual(result["probes"][kind]["reason"], "invalid_inventory_schema")
                self.assertEqual(result["probes"][kind]["stdout_bytes_observed"], 100)
                self.assertIsNone(result["runtime_inventory" if kind == "runtimes" else "device_inventory"]["total_rows"])

    def test_unknown_fields_and_private_names_uuid_paths_errors_are_not_projected(self):
        runtimes = {"runtimes": [runtime(name=PRIVATE, buildversion=PRIVATE, availabilityError=PRIVATE,
                                        root="/Users/" + PRIVATE),
                                  runtime(identifier=PRIVATE, version="26.4", name=PRIVATE)], "private": PRIVATE}
        devices = {"devices": {RUNTIME: [device(udid=PRIVATE_UUID, state=PRIVATE, availabilityError=PRIVATE,
                                                     deviceTypeIdentifier=PRIVATE),
                                           device(name=PRIVATE, udid="22222222-3333-4444-5555-666666666666")],
                               PRIVATE: [device(name=PRIVATE, udid="33333333-4444-5555-6666-777777777777")]}, "private": PRIVATE}
        result, _, _ = self.observe_case(runtimes=runtimes, devices=devices)
        public = json.dumps(result)
        self.assertNotIn(PRIVATE, public)
        self.assertNotIn(PRIVATE_UUID, public)
        self.assertNotIn(UDID, public)
        self.assertEqual(result["runtime_inventory"]["unpublished_rows"], 1)
        self.assertEqual(result["device_inventory"]["total_rows"], 3)
        self.assertEqual(result["device_inventory"]["unpublished_display_name_rows"], 2)
        self.assertEqual(result["device_inventory"]["unpublished_type_rows"], 1)
        self.assertEqual(result["device_inventory"]["states"]["other"], 1)
        self.assertEqual(result["simulator_selector"]["canonical_device_type"]["state"], "unobserved")
        self.assertEqual(result["pinned_tuple"]["state"], "available")

    def test_complete_empty_inventories_support_zero_counts_only(self):
        result, _, _ = self.observe_case(runtimes={"runtimes": []}, devices={"devices": {}})
        self.assertEqual(result["state"], "passed")
        self.assertTrue(result["runtime_inventory"]["complete"])
        self.assertEqual(result["runtime_inventory"]["rows"], [])
        self.assertEqual(result["runtime_inventory"]["total_rows"], 0)
        self.assertEqual(result["device_inventory"]["total_rows"], 0)
        self.assertEqual(result["pinned_tuple"]["fixed_mismatch_code"], "pinned_runtime_version_absent")
        failed, _, _ = self.observe_case(runtimes=None, devices=None)
        self.assertIsNone(failed["runtime_inventory"]["rows"])
        self.assertIsNone(failed["runtime_inventory"]["total_rows"])
        self.assertIsNone(failed["device_inventory"]["total_rows"])
        self.assertIsNone(failed["simulator_selector"]["predicate_counts"]["version_rows"])

    def test_runtime_and_device_row_bounds_reject_without_projecting_absence(self):
        values = [ ("runtimes", {"runtimes": [runtime(identifier=str(i)) for i in range(1025)]}),
                   ("devices", {"devices": {str(i): [] for i in range(257)}}),
                   ("devices", {"devices": {RUNTIME: [device(udid=str(i)) for i in range(4097)]}}) ]
        for kind, value in values:
            with self.subTest(kind=kind):
                result, _, _ = self.observe_case(**{kind: value})
                self.assertEqual(result["probes"][kind]["reason"], "inventory_row_bound_exceeded")
                self.assertIsNone(result["runtime_inventory" if kind == "runtimes" else "device_inventory"]["total_rows"])

    def test_runtime_summary_cap_accepts_boundary_and_rejects_overflow(self):
        for count in (64, 65):
            value = {"runtimes": [runtime(identifier=f"com.apple.CoreSimulator.SimRuntime.iOS-26-{i}", version=f"26.{i}")
                                  for i in range(count)]}
            result, _, _ = self.observe_case(runtimes=value)
            if count == 64:
                self.assertEqual(result["runtime_inventory"]["recognized_ios_rows"], 64)
                self.assertEqual(result["state"], "passed")
            else:
                self.assertEqual(result["probes"]["runtimes"]["reason"], "inventory_summary_bound_exceeded")
                self.assertIsNone(result["runtime_inventory"]["rows"])

    def test_duplicate_runtime_identity_is_unobserved_not_absent(self):
        result, _, _ = self.observe_case(runtimes={"runtimes": [runtime(), runtime()]})
        self.assertEqual(result["probes"]["runtimes"]["reason"], "inventory_identity_ambiguous")
        self.assertIsNone(result["runtime_inventory"]["total_rows"])
        self.assertEqual(result["pinned_tuple"]["state"], "unobserved")

    def test_duplicate_device_identity_is_case_insensitively_ambiguous(self):
        for identity in (PRIVATE_UUID, PRIVATE):
            result, _, _ = self.observe_case(devices={"devices": {RUNTIME: [device(udid=identity)],
                                                                OTHER_RUNTIME: [device(udid=identity.upper())]}})
            self.assertEqual(result["probes"]["devices"]["reason"], "inventory_identity_ambiguous")
            self.assertIsNone(result["device_inventory"]["total_rows"])
            self.assertNotIn(identity, json.dumps(result))

    def test_invalid_optional_type_field_rejects_schema(self):
        for value in (True, [], {}, "", "x" * 257, "not\nprintable"):
            result, _, _ = self.observe_case(devices={"devices": {RUNTIME: [device(deviceTypeIdentifier=value)]}})
            self.assertEqual(result["probes"]["devices"]["reason"], "invalid_inventory_schema")
            self.assertIsNone(result["device_inventory"]["pinned_type_rows"])

    def test_runtime_version_absence_is_distinct(self):
        self.check_mismatch({"runtimes": [runtime(version="26.3")]}, {"devices": {}}, "pinned_runtime_version_absent")

    def test_runtime_identifier_mismatch_is_distinct(self):
        self.check_mismatch({"runtimes": [runtime(identifier=OTHER_RUNTIME)]}, {"devices": {}}, "pinned_runtime_identifier_mismatch")

    def test_runtime_unavailability_is_distinct(self):
        self.check_mismatch({"runtimes": [runtime(available=False)]}, {"devices": {RUNTIME: [device()]}}, "pinned_runtime_unavailable")

    def test_device_runtime_group_absence_is_distinct(self):
        self.check_mismatch({"runtimes": [runtime()]}, {"devices": {OTHER_RUNTIME: [device()]}}, "pinned_device_runtime_group_absent")

    def test_pinned_display_name_absence_is_distinct(self):
        self.check_mismatch({"runtimes": [runtime()]}, {"devices": {RUNTIME: [device(name=PRIVATE)]}}, "pinned_device_name_absent")

    def test_device_unavailability_is_distinct_and_still_in_inventory(self):
        devices = {"devices": {RUNTIME: [device(available=False, availabilityError=PRIVATE)]}}
        self.check_mismatch({"runtimes": [runtime()]}, devices, "pinned_device_unavailable")
        result, _, _ = self.observe_case(devices=devices)
        self.assertEqual(result["device_inventory"]["total_rows"], 1)
        self.assertEqual(result["device_inventory"]["available_rows"], 0)
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_invalid_uuid_is_distinct_and_never_published(self):
        self.check_mismatch({"runtimes": [runtime()]}, {"devices": {RUNTIME: [device(udid=PRIVATE)]}}, "pinned_device_uuid_invalid")

    def test_runtime_version_and_identifier_must_intersect_on_same_row(self):
        runtimes = {"runtimes": [runtime(version="26.3"), runtime(identifier=OTHER_RUNTIME)]}
        self.check_mismatch(runtimes, {"devices": {RUNTIME: [device()]}}, "pinned_runtime_identifier_mismatch")

    def test_canonical_type_missing_or_unknown_is_not_hardware_absence_or_selector_gate(self):
        for extra in ({}, {"deviceTypeIdentifier": None}, {"deviceTypeIdentifier": PRIVATE}):
            result, _, _ = self.observe_case(devices={"devices": {RUNTIME: [device(**extra)]}})
            canonical = result["simulator_selector"]["canonical_device_type"]
            self.assertEqual(canonical["state"], "unobserved")
            self.assertFalse(canonical["used_by_existing_selector"])
            self.assertEqual(result["pinned_tuple"]["state"], "available")
            self.assertNotIn(PRIVATE, json.dumps(result))

    def test_canonical_type_completeness_is_reported_separately(self):
        for rows, state, expected in [([device(deviceTypeIdentifier=inventory.PINNED_DEVICE_TYPE)], "complete", (1, 0, 0)),
                                     ([device(deviceTypeIdentifier=inventory.PINNED_DEVICE_TYPE),
                                       device(udid=PRIVATE_UUID, deviceTypeIdentifier=PRIVATE),
                                       device(udid="22222222-3333-4444-5555-666666666666")], "partial", (1, 1, 1))]:
            result, _, _ = self.observe_case(devices={"devices": {RUNTIME: rows}})
            canonical = result["simulator_selector"]["canonical_device_type"]
            self.assertEqual(canonical["state"], state)
            self.assertEqual(tuple(canonical[key] for key in ("pinned_type_rows", "other_type_rows", "missing_type_rows")), expected)
            self.assertEqual(result["pinned_tuple"]["state"], "available")

    def test_runtime_build_and_device_state_do_not_change_selector(self):
        for build, state in [(None, None), (PRIVATE, PRIVATE), ("23F77", "Booted"), ("23F77", "Shutdown")]:
            result, _, _ = self.observe_case(runtimes={"runtimes": [runtime(buildversion=build)]},
                                            devices={"devices": {RUNTIME: [device(state=state)]}})
            self.assertEqual(result["pinned_tuple"]["state"], "available")
            self.assertNotIn(PRIVATE, json.dumps(result))
            self.assertNotIn("23F77", json.dumps(result))

    def test_runtime_version_identifier_consistency_is_bounded_and_separate(self):
        for version, identifier, consistent in [("26.4", RUNTIME, True), ("26.4.0", RUNTIME, True),
                                                 ("26.4", RUNTIME + "-0", True), ("26.3", RUNTIME, False)]:
            result, _, _ = self.observe_case(runtimes={"runtimes": [runtime(identifier=identifier, version=version)]})
            row = result["runtime_inventory"]["rows"][0]
            self.assertIs(row["version_identifier_consistent"], consistent)
            self.assertNotIn("buildversion", row)

    def test_alternatives_are_only_bounded_known_ios_availability_no_fallback(self):
        runtimes = {"runtimes": [runtime(identifier=OTHER_RUNTIME, version="26.3"), runtime(identifier=PRIVATE, version="26.4")]}
        devices = {"devices": {OTHER_RUNTIME: [device(deviceTypeIdentifier=inventory.PINNED_DEVICE_TYPE)]}}
        result, _, _ = self.observe_case(runtimes=runtimes, devices=devices)
        alternatives = result["availability_alternatives"]
        self.assertEqual(len(alternatives["rows"]), 1)
        self.assertEqual(alternatives["rows"][0]["identifier"], OTHER_RUNTIME)
        self.assertEqual(alternatives["rows"][0]["available_pinned_type_rows"], 1)
        self.assertEqual(alternatives["binding_state"], "bound")
        self.assertFalse(alternatives["fallback_selected"])
        self.assertFalse(result["fallback_selected"])
        self.assertEqual(result["pinned_tuple"]["state"], "unavailable")
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_unknown_runtime_and_version_values_are_counted_never_dumped(self):
        rows = [runtime(identifier=PRIVATE, version="26.4"), runtime(version=PRIVATE),
                runtime(identifier="com.apple.CoreSimulator.SimRuntime.tvOS-26-4")]
        result, _, _ = self.observe_case(runtimes={"runtimes": rows})
        self.assertEqual(result["runtime_inventory"]["total_rows"], 3)
        self.assertEqual(result["runtime_inventory"]["unpublished_rows"], 3)
        self.assertEqual(result["runtime_inventory"]["rows"], [])
        self.assertNotIn(PRIVATE, json.dumps(result))
        self.assertNotIn("tvOS-26-4", json.dumps(result))

    def test_version_text_parser_is_exact_bounded_and_not_a_prefix_match(self):
        self.assertEqual(inventory.parse_version_text("xcode_version", "Xcode 26.4.1\r\nBuild version 17E202\r\n"),
                         {"version": "26.4.1", "build": "17E202"})
        self.assertEqual(inventory.parse_version_text("iphoneos_sdk", "26.4"), {"version": "26.4"})
        for text in ("26.4\n" + PRIVATE, "26.4\n\n", "026.4", "0.4", "26", "26.4.0.1", "26.400", "26.4\r", "x" * 16385, "\ud800"):
            with self.subTest(length=len(text)):
                with self.assertRaises(inventory.InventoryError) as raised:
                    inventory.parse_version_text("iphoneos_sdk", text)
                self.assertEqual(raised.exception.code, "invalid_version_text")
        with self.assertRaises(inventory.InventoryError):
            inventory.parse_version_text("xcode_version", "Xcode 26.4.1\nBuild version " + PRIVATE + "\n")

    def test_probe_projection_whitelists_complete_typed_transport_only(self):
        raw = transport("devices", private=PRIVATE, stdout=PRIVATE, stderr=PRIVATE, command=[PRIVATE])
        projected = inventory.project_probe("devices", raw)
        self.assertEqual(projected["state"], "passed")
        self.assertEqual(projected["kind"], "devices")
        self.assertNotIn(PRIVATE, json.dumps(projected))
        self.assertEqual(projected["json_stream"], "stdout")
        self.assertIs(projected["raw_output_retained"], False)

    def test_malformed_probe_metadata_cannot_pass(self):
        for key, value in [("state", []), ("stdout_bytes_observed", True), ("stdout_bytes_observed", -1),
                           ("stdout_bytes_observed", 16386), ("stderr_bytes_observed", 4097), ("return_code", True),
                           ("stdout_complete", 1), ("cleanup_state", PRIVATE), ("elapsed_seconds", float("nan")),
                           ("elapsed_seconds", float("inf")), ("elapsed_seconds", -1), ("raw_output_retained", True),
                           ("timeout_seconds", True), ("stdout_limit_bytes", 999)]:
            projected = inventory.project_probe("xcode_version", transport("xcode_version", **{key: value}))
            self.assertEqual(projected["state"], "unavailable", key)
            self.assertEqual(projected["reason"], "probe_metadata_invalid", key)
            self.assertNotIn(PRIVATE, json.dumps(projected))
        self.assertEqual(inventory.project_probe("runtimes", None)["reason"], "probe_metadata_invalid")

    def test_incomplete_or_terminated_transport_is_not_accepted(self):
        for extra in ({"stdout_complete": False}, {"stderr_complete": False}, {"return_code": 1},
                      {"return_code": None}, {"terminated_by_probe": True}, {"cleanup_state": "direct_child_cleanup_unverified"}):
            self.assertEqual(inventory.project_probe("devices", transport("devices", **extra))["state"], "unavailable")

    def test_json_metadata_limits_and_stdout_channel_are_exact_and_typed(self):
        for key, value in [("json_depth_limit", 31), ("json_depth_limit", True), ("json_token_limit", 32769),
                           ("json_stream", "stderr"), ("json_stream", PRIVATE)]:
            projected = inventory.project_probe("devices", transport("devices", **{key: value}))
            self.assertEqual(projected["reason"], "probe_metadata_invalid")
            self.assertEqual(projected["json_depth_limit"], 32)
            self.assertEqual(projected["json_token_limit"], 32768)
            self.assertEqual(projected["json_stream"], "stdout")
            self.assertNotIn(PRIVATE, json.dumps(projected))
        self.assertIs(inventory.probe_json, environment.probe_json)
        self.assertIs(inventory.validate_simctl_inventory, environment.validate_simctl_inventory)

    def test_arbitrary_tool_reasons_are_never_forwarded(self):
        for reason in (PRIVATE, [], {"private": PRIVATE}):
            projected = inventory.project_probe("runtimes", transport("runtimes", state="unavailable", reason=reason))
            self.assertEqual(projected["reason"], "probe_unavailable")
            self.assertNotIn(PRIVATE, json.dumps(projected))

    def test_unobserved_summaries_do_not_fabricate_zero_absence(self):
        self.assertIsNone(inventory.runtime_summary(None)["total_rows"])
        self.assertIsNone(inventory.device_summary(None)["available_rows"])
        self.assertTrue(all(value is None for value in inventory.device_summary(None)["states"].values()))
        selector = inventory.diagnose_simulator(None, None)
        self.assertTrue(all(value is None for value in selector["predicate_counts"].values()))
        self.assertIsNone(selector["canonical_device_type"]["pinned_type_rows"])
        self.assertIsNone(inventory.availability_alternatives(None, None)["rows"])

    def test_diagnostic_predicates_match_unchanged_selector_on_synthetic_product(self):
        source = ast.parse((ROOT / "scripts/ci/run_platform_checks.py").read_text(encoding="utf-8"))
        selector = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == "select_simulator")
        namespace = {"re": re, "validate_simctl_inventory": environment.validate_simctl_inventory}
        exec(compile(ast.Module(body=[selector], type_ignores=[]), "<unchanged-selector-synthetic-control>", "exec"), namespace)
        for version, identifier, runtime_available, group, name, available, identity in itertools.product(
                ("26.4", "26.4.0", "26.3"), (RUNTIME, RUNTIME + "-0", OTHER_RUNTIME), (True, False),
                (RUNTIME, RUNTIME + "-0", OTHER_RUNTIME), ("iPhone 17", PRIVATE), (True, False), (UDID, PRIVATE)):
            runtimes = {"runtimes": [runtime(identifier=identifier, version=version, available=runtime_available)]}
            devices = {"devices": {group: [device(name=name, available=available, udid=identity, state=PRIVATE,
                                                 deviceTypeIdentifier=PRIVATE)]}}
            try:
                namespace["select_simulator"](runtimes, devices)
                selected = True
            except ValueError:
                selected = False
            self.assertEqual(inventory.diagnose_simulator(runtimes, devices)["state"] == "available", selected)

    def test_workflow_diagnostic_is_optional_and_not_a_required_collector_input(self):
        workflow = (ROOT / ".github/workflows/platform-validation.yml").read_text(encoding="utf-8")
        ios = workflow.split("  ios-native:\n", 1)[1]
        command = "run: python scripts/ci/record_ios_inventory.py --output build/ci-evidence/ios-inventory.json"
        self.assertEqual(workflow.count(command), 1)
        before, after = ios.split(command, 1)
        step = before.rsplit("      - name:", 1)[1]
        self.assertIn("if: ${{ !cancelled() }}", step)
        self.assertIn("continue-on-error: true", step)
        self.assertIn("timeout-minutes: 4", step)
        self.assertNotIn("--command-result build/ci-evidence/ios-inventory.json", workflow)
        self.assertNotIn("--environment build/ci-evidence/ios-inventory.json", workflow)
        self.assertIn("path: build/ci-evidence/*.json", after)
        self.assertIn("DEVELOPER_DIR: /Applications/Xcode_26.4.1.app/Contents/Developer", ios)
        self.assertIn("--require-xcode 26.4.1 --require-xcode-build 17E202", before)
        self.assertIn("--targets iosArm64 iosSimulatorArm64", after)
        self.assertIn("--native-selection build/ci-evidence/raw/native-provisioning/selection.json", after)

    def test_limitations_explicitly_disclaim_execution_qualification_and_host_attestation(self):
        text = " ".join(inventory.LIMITATIONS)
        for term in ("no compilation", "qualification", "not hardware identity", "not simulator selection predicates",
                     "failed/not-probed", "not host attestation", "cooperative", "descendant cleanup", "four-minute",
                     "no runtime download", "required command-pass gate"):
            self.assertIn(term, text)


class BoundedProbeTests(unittest.TestCase):
    def pipe(self, stdout=b"26.4\n", stderr=b"", *, kind="iphoneos_sdk", json_probe=False, return_code=0,
             start_error=None, stream_error=None, missing_stdout=False, close_error=False, wait_error=False,
             cleanup_error=False, read_blocks_once=False, initial_timeout=False, late_timeout=False):
        clock = [0.0]
        reads = []
        buffers = {101: bytearray(stdout), 102: bytearray(stderr)}
        blocked = [False]

        def read(fd, size):
            reads.append((fd, size))
            if stream_error:
                raise OSError(PRIVATE)
            if read_blocks_once and fd == 101 and not blocked[0]:
                blocked[0] = True
                raise BlockingIOError()
            value = bytes(buffers[fd][:size])
            del buffers[fd][:size]
            return value

        class Selector:
            def __init__(self):
                self.rows = {}
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def register(self, stream, event, name):
                self.rows[stream] = SimpleNamespace(fileobj=stream, fd=stream.fileno(), data=name)
            def unregister(self, stream):
                del self.rows[stream]
            def get_map(self):
                return self.rows
            def select(self, remaining):
                return [(key, 1) for key in list(self.rows.values())]

        out = mock.Mock()
        out.fileno.return_value = 101
        err = mock.Mock()
        err.fileno.return_value = 102
        if close_error:
            out.close.side_effect = OSError(PRIVATE)
        process = mock.Mock(stdout=None if missing_stdout else out, stderr=err)
        process.poll.return_value = None
        waiting = [0]

        def wait(timeout):
            waiting[0] += 1
            if (wait_error and waiting[0] == 1) or cleanup_error:
                raise subprocess.TimeoutExpired(PRIVATE, timeout)
            if late_timeout:
                clock[0] = 31.0
            return return_code

        process.wait.side_effect = wait
        times = [0]

        def monotonic():
            times[0] += 1
            if initial_timeout and times[0] > 1:
                clock[0] = 31.0
            return clock[0]

        with ExitStack() as stack:
            popen = stack.enter_context(mock.patch.object(inventory.subprocess, "Popen", side_effect=start_error,
                                                        return_value=process))
            stack.enter_context(mock.patch.object(inventory.selectors, "DefaultSelector", Selector))
            stack.enter_context(mock.patch.object(inventory.os, "set_blocking"))
            stack.enter_context(mock.patch.object(inventory.os, "read", side_effect=read))
            stack.enter_context(mock.patch.object(inventory.time, "monotonic", side_effect=monotonic))
            group_signal = stack.enter_context(mock.patch.object(inventory.os, "killpg"))
            metadata, value = inventory.probe_json(list(inventory.JSON_PROBES[kind])) if json_probe else inventory.probe_text(kind)
        return metadata, value, process, popen, reads, group_signal

    def test_allowlisted_text_probes_use_argv_no_shell_or_stdin(self):
        for kind, argv in inventory.TEXT_PROBES.items():
            metadata, _, _, popen, _, _ = self.pipe(kind=kind)
            self.assertEqual(popen.call_args.args, (list(argv),))
            self.assertEqual(popen.call_args.kwargs, {"stdin": subprocess.DEVNULL, "stdout": subprocess.PIPE,
                                                    "stderr": subprocess.PIPE, "bufsize": 0, "shell": False})
            self.assertEqual(metadata["state"], "passed")

    def test_unknown_text_probe_never_launches(self):
        with mock.patch.object(inventory.subprocess, "Popen") as popen:
            with self.assertRaises(inventory.InventoryError) as raised:
                inventory.probe_text(PRIVATE)
        popen.assert_not_called()
        self.assertEqual(raised.exception.code, "ios_inventory_probe_not_allowlisted")

    def test_non_posix_probe_does_not_launch(self):
        with mock.patch.object(inventory.os, "name", "nt"), mock.patch.object(inventory.subprocess, "Popen") as popen:
            metadata, value = inventory.probe_text("iphoneos_sdk")
        popen.assert_not_called()
        self.assertEqual(metadata["reason"], "pipe_probe_unavailable")
        self.assertIsNone(value)

    def test_start_failure_is_fixed_and_not_raw(self):
        metadata, value, _, _, _, _ = self.pipe(start_error=OSError(PRIVATE))
        self.assertEqual(metadata["reason"], "start_failure")
        self.assertEqual(metadata["cleanup_state"], "not_started")
        self.assertIsNone(value)
        self.assertNotIn(PRIVATE, json.dumps(metadata))

    def test_success_requires_complete_eof_zero_exit_and_reaping(self):
        metadata, value, process, _, _, group_signal = self.pipe()
        self.assertEqual(value, "26.4\n")
        self.assertEqual(metadata["state"], "passed")
        self.assertTrue(metadata["stdout_complete"])
        self.assertTrue(metadata["stderr_complete"])
        self.assertEqual(metadata["cleanup_state"], "direct_child_reaped")
        process.kill.assert_not_called()
        process.poll.assert_not_called()
        group_signal.assert_not_called()
        self.assertFalse(metadata["raw_output_retained"])

    def test_text_exact_byte_cap_is_transport_complete_not_a_valid_version_prefix(self):
        metadata, value, _, _, reads, _ = self.pipe(stdout=b"26.4\n" + b"x" * (16384 - 5))
        self.assertEqual(metadata["state"], "passed")
        self.assertEqual(metadata["stdout_bytes_observed"], 16384)
        self.assertLessEqual(max(size for _, size in reads), 16384)
        with self.assertRaises(inventory.InventoryError):
            inventory.parse_version_text("iphoneos_sdk", value)

    def test_text_overflow_reads_one_extra_byte_and_discards_clipped_prefix(self):
        metadata, value, process, _, reads, _ = self.pipe(stdout=b"26.4\n" + b"x" * 17000)
        self.assertEqual(metadata["reason"], "stdout_overflow")
        self.assertEqual(metadata["stdout_bytes_observed"], 16385)
        self.assertFalse(metadata["stdout_complete"])
        self.assertIsNone(value)
        self.assertEqual([size for fd, size in reads if fd == 101], [16384, 1])
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=2)

    def test_stderr_is_rejected_without_retaining_private_bytes(self):
        metadata, value, process, _, _, _ = self.pipe(stderr=PRIVATE.encode())
        self.assertEqual(metadata["reason"], "stderr_output")
        self.assertEqual(metadata["stderr_bytes_observed"], len(PRIVATE))
        self.assertIsNone(value)
        self.assertNotIn(PRIVATE, json.dumps(metadata))
        process.wait.assert_called_once_with(timeout=2)

    def test_nonzero_child_is_reaped_without_signaling(self):
        metadata, value, process, _, _, group_signal = self.pipe(return_code=7)
        self.assertEqual(metadata["reason"], "nonzero_exit")
        self.assertEqual(metadata["return_code"], 7)
        self.assertIsNone(value)
        process.kill.assert_not_called()
        process.poll.assert_not_called()
        group_signal.assert_not_called()

    def test_invalid_utf8_is_not_replaced_or_published(self):
        metadata, value, process, _, _, _ = self.pipe(stdout=b"26.4\n\xff")
        self.assertEqual(metadata["reason"], "invalid_utf8")
        self.assertTrue(metadata["stdout_complete"])
        self.assertEqual(metadata["stdout_bytes_observed"], 6)
        self.assertIsNone(value)
        process.kill.assert_not_called()

    def test_cooperative_timeout_terminates_and_only_waits_two_cleanup_seconds(self):
        metadata, value, process, _, reads, group_signal = self.pipe(initial_timeout=True)
        self.assertEqual(metadata["reason"], "timeout")
        self.assertTrue(metadata["terminated_by_probe"])
        self.assertEqual(reads, [])
        self.assertIsNone(value)
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=2)
        group_signal.assert_not_called()

    def test_wait_timeout_and_failed_cleanup_never_pass(self):
        metadata, value, process, _, _, group_signal = self.pipe(wait_error=True, cleanup_error=True)
        self.assertEqual(metadata["reason"], "timeout")
        self.assertEqual(metadata["cleanup_state"], "direct_child_cleanup_unverified")
        self.assertIsNone(value)
        self.assertEqual(process.wait.call_args_list[-1], mock.call(timeout=2))
        group_signal.assert_not_called()
        self.assertNotIn(PRIVATE, json.dumps(metadata))

    def test_stream_failure_and_missing_pipe_are_sanitized(self):
        for options in ({"stream_error": True}, {"missing_stdout": True}):
            metadata, value, _, _, _, _ = self.pipe(**options)
            self.assertEqual(metadata["reason"], "stream_failure")
            self.assertIsNone(value)
            self.assertNotIn(PRIVATE, json.dumps(metadata))

    def test_close_failure_after_reaping_does_not_signal(self):
        metadata, value, process, _, _, group_signal = self.pipe(close_error=True)
        self.assertEqual(metadata["reason"], "stream_failure")
        self.assertIsNone(value)
        process.kill.assert_not_called()
        group_signal.assert_not_called()

    def test_blocking_read_retry_can_complete_without_launching_anything(self):
        metadata, value, _, _, reads, _ = self.pipe(read_blocks_once=True)
        self.assertEqual(metadata["state"], "passed")
        self.assertEqual(value, "26.4\n")
        self.assertEqual(len([row for row in reads if row[0] == 101]), 3)

    def test_post_wait_deadline_disallows_an_otherwise_valid_complete_prefix(self):
        metadata, value, process, _, _, _ = self.pipe(late_timeout=True)
        self.assertEqual(metadata["reason"], "timeout")
        self.assertIsNone(value)
        self.assertTrue(metadata["stdout_complete"])
        process.kill.assert_not_called()

    def test_unchanged_json_probe_accepts_all_device_rows_no_available_filter(self):
        payload = {"devices": {RUNTIME: [device(available=False, availabilityError=PRIVATE)]}}
        metadata, value, _, popen, _, _ = self.pipe(stdout=json.dumps(payload).encode(), kind="devices", json_probe=True)
        self.assertEqual(metadata["state"], "passed")
        self.assertEqual(value, payload)
        self.assertEqual(popen.call_args.args, (["/usr/bin/xcrun", "simctl", "list", "devices", "--json"],))
        self.assertNotIn(PRIVATE, json.dumps(metadata))
        inventory.validate_inventory(value, "devices")

    def test_unchanged_json_probe_rejects_invalid_streams_without_payload(self):
        cases = [(b'{} ' + b'x' * 1048576, "stdout_overflow"), (b'{"runtimes":[],"runtimes":[]}', "duplicate_json_keys"),
                 (b'{"private":NaN}', "nonfinite_json"), (b'{"private":1e999}', "nonfinite_json"),
                 (b'[]', "json_not_object"), (b'{"private":' + b'[' * 33, "json_depth_limit"),
                 (b'{' + b'"x":0,' * 12000, "json_token_limit"), (b'{"runtimes":[]}' + b'\xff', "invalid_utf8"),
                 (b'{"private":', "malformed_json")]
        for raw, reason in cases:
            with self.subTest(reason=reason):
                metadata, value, _, _, reads, _ = self.pipe(stdout=raw, kind="runtimes", json_probe=True)
                self.assertEqual(metadata["reason"], reason)
                self.assertIsNone(value)
                self.assertLessEqual(metadata["stdout_bytes_observed"], 1048577)
                self.assertLessEqual(max(size for _, size in reads), 16384)


class PublicationTests(unittest.TestCase):
    def main_case(self, argv, result, root):
        original = inventory.output_path
        terminal = io.StringIO()
        with mock.patch.object(sys, "argv", ["record_ios_inventory.py", *argv]), \
                mock.patch.object(inventory, "output_path", side_effect=lambda path: original(path, root)), \
                mock.patch.object(inventory, "observe", return_value=result) as observer, redirect_stdout(terminal):
            code = inventory.main()
        return code, terminal.getvalue(), observer

    @staticmethod
    def result(state="passed", completion="complete", tuple_state="unavailable"):
        return {"state": state, "diagnostic_completion": {"state": completion}, "pinned_tuple": {"state": tuple_state},
                "tuple_qualified": False, "release_qualified": False}

    def test_output_path_is_only_a_fresh_bounded_ci_evidence_json_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("ios-inventory.json", "x.json", "X_-19.json", "x" * 80 + ".json"):
                path = inventory.output_path(Path("build/ci-evidence") / name, root)
                self.assertEqual(path, root / "build/ci-evidence" / name)
                self.assertEqual(inventory.output_path(path, root), path)

    def test_output_path_rejects_outside_nested_parent_and_unbounded_names(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("outside.json", "build/ci-evidence/x.txt", "build/ci-evidence/.x.json", "build/ci-evidence/x/y.json",
                         "build/ci-evidence/../ci-evidence/x.json", "build/ci-evidence/" + "x" * 81 + ".json", "/tmp/outside.json"):
                with self.assertRaises(inventory.InventoryError) as raised:
                    inventory.output_path(Path(name), root)
                self.assertEqual(raised.exception.code, "ios_inventory_output_path_invalid")

    def test_output_path_rejects_symlink_parents_and_broken_final_symlink(self):
        for parent in ("build", "build/ci-evidence", "build/ci-evidence/ios-inventory.json"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                target = root / parent
                target.parent.mkdir(parents=True, exist_ok=True)
                target.symlink_to(root / "missing", target_is_directory=parent != "build/ci-evidence/ios-inventory.json")
                with self.assertRaises(inventory.InventoryError) as raised:
                    inventory.output_path(Path("build/ci-evidence/ios-inventory.json"), root)
                self.assertIn(raised.exception.code, {"ios_inventory_output_path_invalid", "ios_inventory_output_not_fresh"})

    def test_existing_file_is_never_replaced_or_read_as_observed_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/ios-inventory.json"
            path.parent.mkdir(parents=True)
            path.write_bytes(PRIVATE.encode())
            with self.assertRaises(inventory.InventoryError) as raised:
                inventory.publish(path, self.result())
            self.assertEqual(raised.exception.code, "ios_inventory_output_not_fresh")
            self.assertEqual(path.read_bytes(), PRIVATE.encode())
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_unknown_cli_options_do_not_observe_or_publish(self):
        for argv in (['--profile', 'ios'], ['--developer-dir', PRIVATE], ['--fallback'], ['--output', 'x.json', '--force']):
            with mock.patch.object(sys, "argv", ["record_ios_inventory.py", *argv]), \
                    mock.patch.object(inventory, "observe") as observer, mock.patch.object(inventory, "publish") as publisher, \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                inventory.main()
            self.assertEqual(raised.exception.code, 2)
            observer.assert_not_called()
            publisher.assert_not_called()

    def test_cli_complete_tuple_unavailable_publishes_and_returns_success_without_qualification(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = self.result()
            code, terminal, observer = self.main_case([], value, root)
            self.assertEqual(code, 0)
            observer.assert_called_once_with()
            self.assertEqual(json.loads((root / "build/ci-evidence/ios-inventory.json").read_text()), value)
            self.assertIn("pinned tuple unavailable; no execution/qualification/fallback", terminal)

    def test_cli_incomplete_inventory_publishes_then_returns_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = self.result(state="unavailable", completion="incomplete", tuple_state="unobserved")
            code, terminal, _ = self.main_case(["--output", "build/ci-evidence/diagnostic.json"], value, root)
            self.assertEqual(code, 1)
            self.assertEqual(json.loads((root / "build/ci-evidence/diagnostic.json").read_text()), value)
            self.assertIn("incomplete", terminal)

    def test_cli_collision_does_not_observe_or_expose_the_existing_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "build/ci-evidence/ios-inventory.json"
            path.parent.mkdir(parents=True)
            path.write_text(PRIVATE)
            code, terminal, observer = self.main_case([], self.result(), root)
            self.assertEqual(code, 1)
            self.assertEqual(terminal, "ios_inventory_output_not_fresh\n")
            observer.assert_not_called()
            self.assertEqual(path.read_text(), PRIVATE)

    def test_receipt_byte_bound_rejects_before_creating_any_directory_or_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/x.json"
            with self.assertRaises(inventory.InventoryError) as raised:
                inventory.publish(path, {"private": "x" * 65536})
            self.assertEqual(raised.exception.code, "ios_inventory_receipt_bound_exceeded")
            self.assertFalse(path.parent.exists())

    def test_encoding_failures_are_fixed_and_do_not_publish(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/x.json"
            for value in ({"private": float("nan")}, {"private": object()}):
                with self.assertRaises(inventory.InventoryError) as raised:
                    inventory.publish(path, value)
                self.assertEqual(raised.exception.code, "ios_inventory_publication_failed")
                self.assertFalse(path.parent.exists())

    def test_racing_link_collision_preserves_winner_and_removes_private_pending_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/x.json"
            original = os.link
            def collision(source, target):
                path.write_bytes(PRIVATE.encode())
                original(source, target)
            with mock.patch.object(inventory.os, "link", side_effect=collision), self.assertRaises(inventory.InventoryError) as raised:
                inventory.publish(path, self.result())
            self.assertEqual(raised.exception.code, "ios_inventory_output_not_fresh")
            self.assertEqual(path.read_bytes(), PRIVATE.encode())
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_link_error_is_fixed_and_leaves_no_partial_or_pending_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/x.json"
            with mock.patch.object(inventory.os, "link", side_effect=OSError(PRIVATE)), self.assertRaises(inventory.InventoryError) as raised:
                inventory.publish(path, self.result())
            self.assertEqual(str(raised.exception), "ios_inventory_publication_failed")
            self.assertFalse(path.exists())
            self.assertEqual(list(path.parent.iterdir()), [])

    def test_successful_publication_is_exclusive_restrictive_and_has_no_pending_sibling(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "build/ci-evidence/x.json"
            value = self.result()
            inventory.publish(path, value)
            self.assertEqual(json.loads(path.read_text()), value)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(list(path.parent.iterdir()), [path])


if __name__ == "__main__":
    unittest.main()
