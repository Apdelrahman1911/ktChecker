"""Synthetic CI policy/metadata tests; no resolver downloads or library/JVM execution."""

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

try:
    from . import run_library_checks as checks
except ImportError:
    import run_library_checks as checks


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}


def integration_xml(provider, state="passed", unexpected_skip=False):
    definition = checks.PROVIDERS[provider]
    root = ET.Element("testsuite", name=definition["class"], tests="2", failures="2" if state == "failure" else "0",
                      errors="0", skipped="2" if state == "skipped" else "0")
    for name, message in definition["cases"].items():
        case = ET.SubElement(root, "testcase", name=name, classname=definition["class"])
        if state == "skipped":
            ET.SubElement(case, "skipped", type="org.opentest4j.TestAbortedException",
                          message="PRIVATE arbitrary unavailable" if unexpected_skip else
                          "org.opentest4j.TestAbortedException: Assumption failed: " + message)
        elif state == "failure":
            ET.SubElement(case, "failure", message="PRIVATE source and raw stack").text = "PRIVATE transcript"
    return ET.tostring(root)


def write_reports(root, state="passed"):
    for provider, definition in checks.PROVIDERS.items():
        path = root / f"modules/provider-{provider}/build/test-results/test/TEST-{definition['class']}.xml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(integration_xml(provider, state))


def args(root, profile="supported"):
    return argparse.Namespace(profile=profile, output=root / "build/ci-evidence/library-result.json", timeout=1,
                              environment=root / "build/ci-evidence/environment.json",
                              prerequisites=root / "build/ci-evidence/library-prerequisites.json",
                              build=root / "build/ci-evidence/host-build.json")


def prerequisite(root, profile="supported"):
    system = "Windows" if profile == "windows-portability" else "Linux"
    value = checks.base_result(profile, IDENTITY, system, "prerequisites")
    value["state"] = "passed"
    value["providers"] = [{"provider_id": provider, "integration_opt_in": profile == "supported",
                           "controls_executed": False, "availability": "available" if profile == "supported" else "unavailable",
                           "reason": checks.UNAVAILABLE if profile != "supported" else "pinned_prerequisites_verified_not_provider_execution",
                           "resolver_executed": profile == "supported"} for provider in checks.PROVIDERS]
    argument = args(root, profile)
    argument.prerequisites.parent.mkdir(parents=True, exist_ok=True)
    argument.prerequisites.write_text(json.dumps(value))
    argument.build.write_text(json.dumps({"state": "passed", "source": IDENTITY,
                                         "tasks": {f":provider-{provider}:test": "EXECUTED" for provider in checks.PROVIDERS}}))
    return argument


class LibraryPolicyTests(unittest.TestCase):
    def test_supported_libraries_do_not_require_the_replaced_setsid_perl_kill_chain(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = args(root)
            with patch.object(checks.platform, "system", return_value="Linux"), \
                    patch.object(checks, "source_identity", return_value=IDENTITY), \
                    patch.object(checks, "environment_input", return_value={}), \
                    patch.object(checks, "manifest", return_value=([], "sha256:" + "3" * 64)), \
                    patch.object(checks, "run_logged", return_value=({"state": "completed", "return_code": 0}, "{}", "")), \
                    patch.object(checks, "resolver_projection", return_value=[]), \
                    patch.object(Path, "is_file", side_effect=AssertionError("obsolete launcher path inspected")):
                result = checks.provision(argument, root)
            self.assertEqual("passed", result["state"])
            self.assertFalse(result["provider_controls_executed"])
            self.assertEqual(2, len(result["providers"]))

    def test_windows_prerequisite_policy_does_not_execute_downloads_or_claim_controls(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = args(root, "windows-portability")
            with patch.object(checks.platform, "system", return_value="Windows"), \
                    patch.object(checks, "source_identity", return_value=IDENTITY), \
                    patch.object(checks, "environment_input", return_value={}), \
                    patch.object(checks, "run_logged") as command:
                result = checks.provision(argument, root)
            command.assert_not_called()
            self.assertEqual("passed", result["state"])
            self.assertEqual("windows_portability_only", result["execution_scope"])
            self.assertFalse(result["provider_controls_executed"])
            self.assertEqual(4, sum(row["expected_skipped_methods"] for row in result["providers"]))
            self.assertTrue(all(row["availability"] == "unavailable" and row["reason"] == checks.UNAVAILABLE
                                and not row["resolver_executed"] for row in result["providers"]))

    def test_wrong_profile_cannot_enable_windows_or_disable_posix_controls(self):
        for profile, system in (("supported", "Windows"), ("windows-portability", "Linux"),
                                ("windows-portability", "Darwin"), ("supported", "Unknown")):
            with self.subTest(profile=profile, system=system), self.assertRaises(ValueError):
                checks.host_scope(profile, system)
        checks.host_scope("supported", "Darwin")
        checks.host_scope("supported", "Linux")

    def test_strict_metadata_rejects_duplicate_nonfinite_invalid_utf8_and_oversize(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}', b'{"a":"\xff"}', b'[]', b''):
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                checks.strict_json(raw)
        with self.assertRaises(ValueError):
            checks.strict_json(b'{"a":1}', limit=3)

    def test_trusted_outer_root_alias_never_allows_traversal_or_descendant_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve(strict=True)
            root = parent / "actual-root"
            root.mkdir()
            path = root / "public.json"
            path.write_bytes(b"public synthetic evidence")
            (root / "nested").mkdir()
            self.assertEqual(path.read_bytes(), checks.regular_input(path, root))
            for unsafe in (root / "nested/../public.json", root / "../outside.json", parent / "outside.json"):
                with self.subTest(unsafe=unsafe), self.assertRaises(ValueError):
                    checks.regular_input(unsafe, root)
            if os.name != "nt":
                # macOS's trusted /var -> /private/var outer alias has the same
                # semantics; no permission-dependent Windows symlink is needed.
                alias = parent / "trusted-outer-alias"
                alias.symlink_to(root, target_is_directory=True)
                self.assertEqual(path.read_bytes(), checks.regular_input(alias / path.name, alias))
                self.assertEqual(path.read_bytes(), checks.regular_input(path, alias))
                descendant = root / "descendant.json"
                descendant.symlink_to(path)
                with self.assertRaises(ValueError):
                    checks.regular_input(alias / descendant.name, alias)

    def test_environment_requires_observed_matching_jvm21_and_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "environment.json"
            value = {"state": "passed", "source": IDENTITY, "host": {"os": "Linux"}, "java": {"major": 21}}
            path.write_text(json.dumps(value))
            self.assertEqual(value, checks.environment_input(path, root, IDENTITY, "Linux"))
            for key, replacement in (("state", "failed"), ("java", {"major": True}), ("java", {"major": 25}),
                                     ("host", {"os": "Windows"}), ("source", {"working_tree_digest": "sha256:" + "3" * 64})):
                invalid = dict(value, **{key: replacement})
                path.write_text(json.dumps(invalid))
                with self.subTest(key=key, replacement=replacement), self.assertRaises(ValueError):
                    checks.environment_input(path, root, IDENTITY, "Linux")

    def test_resolver_projection_independently_hashes_and_omits_private_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / ".plan-deps/detekt/fixture.jar"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"synthetic jar identity")
            pin = {"coordinate": "example:fixture:1", "filename": path.name, "size": path.stat().st_size,
                   "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "upstream_checksum": hashlib.sha1(path.read_bytes()).hexdigest()}
            value = {"schema_version": 1, "artifacts": [{"coordinate": pin["coordinate"], "digest": "sha256:" + pin["sha256"],
                                                        "path": str(path), "private": "PRIVATE body"}], "private": "PRIVATE root"}
            result = checks.resolver_projection("detekt", json.dumps(value), [pin], root)
            self.assertEqual(pin["coordinate"], result[0]["coordinate"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertNotIn(str(root), json.dumps(result))
            for field, replacement in (("digest", "sha256:" + "3" * 64), ("path", str(root / "elsewhere.jar")),
                                       ("coordinate", "example:other:1")):
                invalid = copy.deepcopy(value)
                invalid["artifacts"][0][field] = replacement
                with self.subTest(field=field), self.assertRaises(ValueError):
                    checks.resolver_projection("detekt", json.dumps(invalid), [pin], root)
            path.write_bytes(b"different bytes")
            with self.assertRaises(ValueError):
                checks.resolver_projection("detekt", json.dumps(value), [pin], root)

    def test_unrecognized_duplicate_or_absent_resolver_identities_fail(self):
        for value in ({"schema_version": True, "artifacts": []}, {"schema_version": 1, "artifacts": []},
                      {"schema_version": 1, "artifacts": [{"coordinate": "unknown:jar:1"}]},
                      {"schema_version": 1, "artifacts": [None]}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                checks.resolver_projection("ktlint", json.dumps(value), [{"coordinate": "a:b:1"}], Path("unused"))

    def test_manifest_requires_exact_unique_pinned_identity_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "modules/provider-ktlint/src/main/resources/ktlint-artifacts.json"
            path.parent.mkdir(parents=True)
            pins = [{"coordinate": f"example:fixture{i}:1", "filename": f"fixture{i}.jar", "size": 1,
                     "sha256": "1" * 64, "upstream_checksum_algorithm": "sha1", "upstream_checksum": "2" * 40} for i in range(3)]
            path.write_text(json.dumps({"schema_version": 1, "artifacts": pins}))
            self.assertEqual(pins, checks.manifest("ktlint", root)[0])
            for invalid in (pins[:2], pins[:2] + [pins[0]], pins[:2] + [dict(pins[2], filename="../escape.jar")],
                            pins[:2] + [dict(pins[2], size=True)], pins[:2] + [dict(pins[2], sha256="bad")]):
                path.write_text(json.dumps({"schema_version": 1, "artifacts": invalid}))
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    checks.manifest("ktlint", root)

    def test_commands_never_project_arguments_raw_failure_messages_or_paths(self):
        result = checks.safe_command({"state": "completed", "return_code": 2, "command": ["PRIVATE source"],
                                      "reason": "PRIVATE secret", "stdout_digest": "sha256:" + "1" * 64,
                                      "machinePath": "PRIVATE path", "raw_logs_uploaded": False})
        self.assertEqual(2, result["return_code"])
        self.assertNotIn("PRIVATE", json.dumps(result))


class LibraryControlTests(unittest.TestCase):
    def collect(self, root, argument):
        system = "Windows" if argument.profile == "windows-portability" else "Linux"
        with patch.object(checks, "source_identity", return_value=IDENTITY), \
                patch.object(checks.platform, "system", return_value=system):
            return checks.collect_controls(argument, root)

    def test_supported_controls_require_exact_actual_methods_without_skips(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = prerequisite(root)
            write_reports(root)
            result = self.collect(root, argument)
            self.assertEqual("passed", result["state"])
            self.assertTrue(result["provider_controls_executed"])
            self.assertEqual({"tests": 4, "passed": 4, "failure": 0, "error": 0, "skipped": 0}, result["counts"])
            self.assertTrue(all(row["state"] == "executed" and not row["qualified"] for row in result["providers"]))

    def test_windows_scope_pass_preserves_actual_four_skips_and_provider_unavailability(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = prerequisite(root, "windows-portability")
            write_reports(root, "skipped")
            result = self.collect(root, argument)
            self.assertEqual("passed", result["state"])
            self.assertFalse(result["provider_controls_executed"])
            self.assertEqual(4, result["counts"]["skipped"])
            self.assertEqual(0, result["counts"]["passed"])
            self.assertTrue(all(row["state"] == "unavailable" and row["reason"] == checks.UNAVAILABLE for row in result["providers"]))

    def test_missing_failed_or_cached_build_does_not_pass_optional_control_gate(self):
        for state, outcome in (("failed", "EXECUTED"), ("passed", "SKIPPED"), ("passed", "FROM-CACHE"), ("passed", "absent")):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                argument = prerequisite(root)
                write_reports(root)
                build = json.loads(argument.build.read_text())
                build["state"] = state
                build["tasks"][":provider-detekt:test"] = outcome
                argument.build.write_text(json.dumps(build))
                with self.subTest(state=state, outcome=outcome):
                    self.assertEqual("failed", self.collect(root, argument)["state"])

    def test_source_and_prerequisite_availability_must_bind_the_selected_scope(self):
        for field, replacement in (("source", {"working_tree_digest": "sha256:" + "3" * 64}), ("profile", "windows-portability"),
                                   ("state", "failed"), ("host_os", "Windows"), ("providers", [])):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                argument = prerequisite(root)
                write_reports(root)
                value = json.loads(argument.prerequisites.read_text())
                value[field] = replacement
                argument.prerequisites.write_text(json.dumps(value))
                with self.subTest(field=field):
                    self.assertEqual("failed", self.collect(root, argument)["state"])

    def test_malformed_host_build_task_object_records_failure_instead_of_crashing(self):
        for tasks in (None, [], "PRIVATE malformed task object"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                argument = prerequisite(root)
                write_reports(root)
                value = json.loads(argument.build.read_text())
                value["tasks"] = tasks
                argument.build.write_text(json.dumps(value))
                with self.subTest(tasks=tasks):
                    result = self.collect(root, argument)
                    self.assertEqual("failed", result["state"])
                    self.assertFalse(result["provider_controls_executed"])
                    self.assertNotIn("PRIVATE", json.dumps(result))

    def test_successful_unit_suite_cannot_substitute_missing_real_integration_methods(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = prerequisite(root)
            result = self.collect(root, argument)
            self.assertEqual("failed", result["state"])
            self.assertEqual(0, result["counts"]["tests"])
            self.assertFalse(result["provider_controls_executed"])

    def test_supported_skips_and_unexpected_windows_skip_messages_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_reports(root, "skipped")
            with self.assertRaises(ValueError):
                checks.integration_report("detekt", root, True)
            path = root / f"modules/provider-detekt/build/test-results/test/TEST-{checks.PROVIDERS['detekt']['class']}.xml"
            path.write_bytes(integration_xml("detekt", "skipped", unexpected_skip=True))
            with self.assertRaises(ValueError):
                checks.integration_report("detekt", root, False)

    def test_duplicate_forged_names_and_declared_empty_counts_are_rejected(self):
        for alteration in ("duplicate", "unknown", "declared", "empty", "dtd"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                write_reports(root)
                path = root / f"modules/provider-detekt/build/test-results/test/TEST-{checks.PROVIDERS['detekt']['class']}.xml"
                xml = ET.fromstring(path.read_bytes())
                if alteration == "duplicate":
                    xml.findall("testcase")[1].set("name", xml.findall("testcase")[0].get("name"))
                elif alteration == "unknown":
                    xml.findall("testcase")[0].set("name", "PRIVATE invented case")
                elif alteration == "declared":
                    xml.set("tests", "100")
                elif alteration == "empty":
                    for case in xml.findall("testcase"):
                        xml.remove(case)
                path.write_bytes((b'<!DOCTYPE testsuite [<!ENTITY secret "PRIVATE">]>' if alteration == "dtd" else b'') + ET.tostring(xml))
                with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                    checks.integration_report("detekt", root, True)

    def test_failed_control_counts_remain_visible_without_raw_source_or_stack(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            argument = prerequisite(root)
            write_reports(root, "failure")
            result = self.collect(root, argument)
            self.assertEqual("failed", result["state"])
            self.assertEqual(4, result["counts"]["failure"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertFalse(result["provider_controls_executed"])


if __name__ == "__main__":
    unittest.main()
