"""Synthetic native CI metadata controls; no C compilation or helper execution."""

import argparse
import ast
from contextlib import ExitStack, contextmanager
import copy
from functools import partial
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import warnings
import zipfile

try:
    from . import run_supervisor_checks as checks
    from . import supervisor_test_runner as runner
    from .smoke_distribution import CONTAINMENT_SCOPE
except ImportError:
    import run_supervisor_checks as checks
    import supervisor_test_runner as runner
    from smoke_distribution import CONTAINMENT_SCOPE


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
HOSTS = {"linux-x86_64": ("Linux", "x86_64"), "linux-aarch64": ("Linux", "aarch64"),
         "macos-x86_64": ("Darwin", "x86_64"), "macos-aarch64": ("Darwin", "arm64"),
         "windows-x86_64": ("Windows", "AMD64"), "windows-aarch64": ("Windows", "ARM64"),
         "unsupported": ("FreeBSD", "x86_64")}


def write(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)


def write_json(path, value):
    write(path, (json.dumps(value) + "\n").encode("utf-8"))


def binary(target):
    raw = bytearray(96)
    if target.startswith("linux-"):
        raw[:7] = b"\x7fELF\x02\x01\x01"
        raw[16:18] = (3).to_bytes(2, "little")
        raw[18:20] = (62 if target.endswith("x86_64") else 183).to_bytes(2, "little")
        raw[20:24] = (1).to_bytes(4, "little")
        raw[52:54] = (64).to_bytes(2, "little")
    elif target.startswith("macos-"):
        raw[:4] = b"\xcf\xfa\xed\xfe"
        raw[4:8] = (0x1000007 if target.endswith("x86_64") else 0x100000C).to_bytes(4, "little")
        raw[12:16] = (2).to_bytes(4, "little")
    else:
        return b""
    return bytes(raw)


class Fixture:
    def __init__(self, root, target="linux-x86_64"):
        self.root, self.target = root, target
        self.system, self.machine = HOSTS[target]
        self.host = checks.normalized_host(self.system, self.machine)
        self.python = root / "PRIVATE launcher/python"
        self.cc = root / "PRIVATE compiler/cc"
        for path in (self.python, self.cc):
            write(path, b"synthetic executable identity; never execute")
            path.chmod(0o755)
        for relative in (checks.SOURCE, checks.BUILDER, checks.PROBE, checks.TEST_FILE, checks.TEST_RUNNER):
            write(root / relative, b"PRIVATE synthetic source bytes; never execute")
        self.args = argparse.Namespace(output=root / "build/ci-evidence/supervisor-result.json",
                                       environment=root / "build/ci-evidence/environment.json",
                                       prerequisites=root / "build/ci-evidence/supervisor-prerequisites.json",
                                       controls=root / "build/ci-evidence/supervisor-controls.json",
                                       build=root / "build/ci-evidence/host-build.json",
                                       smoke=root / "build/ci-evidence/distribution-smoke.json", cc=str(self.cc),
                                       python_command="python" if target.startswith("windows-") else "python3")
        self.environment = {"state": "passed", "source": IDENTITY,
                            "host": {"os": self.system, "arch": self.machine, "python": "3.12.9"},
                            "PRIVATE": "PRIVATE environment paths"}
        write_json(self.args.environment, self.environment)
        self.observer = {"state": "available", "required_for_linux_native_controls": True,
                         "driver_digest": "sha256:" + "3" * 64, "version_probe_performed": False} if target.startswith("linux-") else \
            {"state": "not_applicable", "required_for_linux_native_controls": False}

    @contextmanager
    def context(self):
        with ExitStack() as stack:
            stack.enter_context(patch.object(checks.platform, "system", return_value=self.system))
            stack.enter_context(patch.object(checks.platform, "machine", return_value=self.machine))
            stack.enter_context(patch.object(checks.platform, "python_version", return_value="3.12.9"))
            stack.enter_context(patch.object(checks, "source_identity", return_value=IDENTITY))
            stack.enter_context(patch.object(checks.sys, "executable", str(self.python)))
            stack.enter_context(patch.object(checks.sys, "version_info", (3, 12, 9)))
            stack.enter_context(patch.object(checks.shutil, "which", return_value=str(self.python)))
            stack.enter_context(patch.object(checks, "syscall_observer", return_value=self.observer))
            yield

    def probe(self, argv, *_):
        raw = b"26.4\n" if argv[0] == "/usr/bin/xcrun" else b"Xcode 26.4.1\nBuild version 17E202\n" if argv[0] == "/usr/bin/xcodebuild" else \
            b"cc (Synthetic) 1.2.3 PRIVATE banner-token\nPRIVATE transcript\n"
        return {"state": "completed", "return_code": 0, "raw_logs_uploaded": False}, raw

    def prerequisite(self):
        with self.context(), patch.object(checks, "probe_version", side_effect=self.probe):
            result = checks.preflight(self.args, self.root)
        write_json(self.args.prerequisites, result)
        return result

    def outcomes(self, state="passed"):
        inputs = checks.native_test_inputs(self.root)
        cases = [{"id": name, "state": "skipped" if self.host["os"] == "macos" and name in checks.LINUX_ONLY else "passed",
                  "skip_reason": checks.LINUX_ONLY[name][1] if self.host["os"] == "macos" and name in checks.LINUX_ONLY else None}
                 for name in sorted(checks.CASES)]
        if state == "failed":
            cases[0]["state"], cases[0]["skip_reason"] = "failed", None
        return {"schema_version": 1, "evidence_kind": "native_supervisor_unittest_outcomes", "state": state,
                "source": IDENTITY, "host": self.host, "test_inputs": inputs, "selected_method_count": len(checks.CASES),
                "tests_run": len(checks.CASES), "fixture_errors": 0, "cases": cases,
                "counts": {key: sum(row["state"] == key for row in cases) for key in ("passed", "failed", "error", "skipped", "not_run")},
                "raw_exception_messages_retained": False, "provider_controls_executed": False,
                "native_abi_qualified": False, "release_qualified": False, "PRIVATE": "PRIVATE exception body"}

    def receipt(self, prerequisites):
        supported = self.target in checks.SUPERVISOR_TARGETS
        raw = binary(self.target)
        value = {"schemaVersion": "ktchecker-owned-child-supervisor-build/1", "protocolVersion": checks.SUPERVISOR_PROTOCOL,
                 "status": "built" if supported else "unavailable", "reason": None if supported else "unsupported_host",
                 "host": self.host, "compiler": None, "invocation": None, "output": None,
                 "inputs": {role: {"path": path, "sha256": prerequisites["build_inputs"][role]}
                            for role, path in (("source", "native/owned_process_supervisor.c"), ("builder", "native/build_supervisor.py"))},
                 "compilerDriverIdentityOnly": True, "transitiveCompilerToolchainVerified": False,
                 "sourceToBinaryReproducibilityEstablished": False,
                 "privacy": {"privatePathsRetained": False, "rawCompilerTranscriptRetained": False, "versionFirstLineRedacted": supported}}
        if supported:
            observed = prerequisites["compiler"]
            value["compiler"] = {"selectedPath": "<private-compiler>", "resolvedPath": "<private-compiler>",
                                 "driverSha256": observed["driver_digest"], "versionFirstLine": "<redacted>",
                                 "versionOutputSha256": observed["version_output_digest"], "versionOutputBytes": observed["version_output_bytes"]}
            value["invocation"] = {"argv": checks.ARGV, "flags": checks.FLAGS, "exactArgvSha256": "sha256:" + "4" * 64,
                                   "exactArgvEncoding": "utf8-json-compact-ascii", "privateArgvRetained": False}
            value["output"] = {"path": checks.PREFIX + self.target + "/supervisor", "sha256": checks.digest_bytes(raw), "bytes": len(raw)}
        return value

    def bundle(self):
        prerequisites = self.prerequisite()
        supported, raw = self.target in checks.SUPERVISOR_TARGETS, binary(self.target)
        source_digest = prerequisites["build_inputs"]["source"]
        binary_digest = checks.digest_bytes(raw) if supported else "unavailable"
        expected = {checks.PREFIX + "manifest": f"{checks.SUPERVISOR_PROTOCOL}\n{self.target}\n{source_digest}\n{binary_digest}\n".encode(),
                    checks.PREFIX + "build-provenance.json": (json.dumps(self.receipt(prerequisites)) + "\n").encode()}
        if supported:
            expected[checks.PREFIX + self.target + "/supervisor"] = raw
        for name, value in expected.items():
            write(self.root / checks.RESOURCES / name, value)
        self.expected = expected
        self.module_jar = self.root / checks.MODULE / "build/libs" / checks.JAR
        self.installed_jar = self.root / "modules/cli/build/install/ktchecker/lib" / checks.JAR
        self.jar(self.module_jar, expected)
        write(self.installed_jar, self.module_jar.read_bytes())
        containment = {"state": "available" if supported else "unavailable", "target": self.target,
                       "bundledBytesVerified": supported, "executionVerified": False, "releaseQualified": False,
                       "reasons": [] if supported else ["process_group_containment_unavailable"], "scope": CONTAINMENT_SCOPE}
        if supported:
            containment["identity"] = {"protocolVersion": checks.SUPERVISOR_PROTOCOL, "target": self.target,
                                       "sourceDigest": source_digest, "binaryDigest": binary_digest}
        smoke = {"state": "passed", "evidence_kind": "installed_distribution_smoke", "source": IDENTITY,
                 "execution": {"state": "completed", "return_code": 0}, "doctor": {"processContainment": containment},
                 "installation_files": [{"name": "lib/" + checks.JAR, "digest": checks.digest_file(self.installed_jar)}],
                 "PRIVATE": "PRIVATE smoke body"}
        write_json(self.args.smoke, smoke)
        write_json(self.args.build, {"state": "passed", "source": IDENTITY, "tasks": {checks.TASK: "EXECUTED", checks.TEST_TASK: "EXECUTED"},
                                     "PRIVATE": "PRIVATE terminal transcript"})
        with self.context():
            tested = checks.base("native_fixture_controls", self.root)
        tested.update(state="passed", test_inputs=checks.native_test_inputs(self.root),
                      prerequisites_digest=checks.digest_file(self.args.prerequisites), linux_syscall_observer=self.observer,
                      native_fixture_control_command_attempted=supported,
                      native_fixture_controls_executed=supported, native_fixture_controls_passed=supported,
                      native_helper_executed_by_test_harness=supported)
        tested["controls"] = checks.outcome_metadata(self.outcomes(), IDENTITY, self.host, tested["test_inputs"]) if supported else \
            {"state": "unavailable", "tests_run": 0, "methods_not_executed": len(checks.CASES)}
        write_json(self.args.controls, tested)
        return prerequisites

    @staticmethod
    def jar(path, expected, extras=()):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
            for name, value in expected.items():
                archive.writestr(name, value)
            for name, value in extras:
                archive.writestr(name, value)

    def collect(self):
        with self.context(), patch.object(checks, "run_logged") as command:
            result = checks.collect(self.args, self.root)
        command.assert_not_called()
        return result


class NativeFixtureInventoryTests(unittest.TestCase):
    def test_explicit_live_sibling_preserves_v1_input_and_method_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            original = checks.native_test_inputs(fixture.root)
            self.assertEqual(("test_supervisor.py",), checks.native_test_file_inventory(fixture.root))
            write(fixture.root / checks.MODULE / "native/test_live_supervisor.py", b"synthetic independent v2 fixture\n")
            self.assertEqual(("test_live_supervisor.py", "test_supervisor.py"), checks.native_test_file_inventory(fixture.root))
            self.assertEqual(original, checks.native_test_inputs(fixture.root))
            self.assertEqual({"source", "builder", "probe", "tests", "runner"}, set(original))
            self.assertEqual(29, len(checks.CASES))

    def test_unknown_sibling_and_missing_v1_file_are_rejected(self):
        for alteration in ("unknown_sibling", "missing_v1"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary))
                if alteration == "unknown_sibling":
                    write(fixture.root / checks.MODULE / "native/test_future_supervisor.py", b"synthetic unknown fixture\n")
                else:
                    (fixture.root / checks.TEST_FILE).unlink()
                with self.assertRaises((OSError, ValueError)):
                    checks.native_test_inputs(fixture.root)

    def test_known_sibling_must_be_nonempty_bounded_regular_source(self):
        for alteration in ("empty", "oversized", "directory"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary))
                sibling = fixture.root / checks.MODULE / "native/test_live_supervisor.py"
                if alteration == "directory":
                    sibling.mkdir()
                else:
                    write(sibling, b"" if alteration == "empty" else b"x" * (1_048_576 + 1))
                with self.assertRaises((OSError, ValueError)):
                    checks.native_test_inputs(fixture.root)


class NativeFormatTests(unittest.TestCase):
    def test_host_identities_are_normalized_without_unknown_aliases(self):
        for target, (system, machine) in HOSTS.items():
            self.assertEqual(target, checks.normalized_host(system, machine)["target"])
        self.assertEqual({"os": "linux", "architecture": "unsupported", "target": "unsupported"}, checks.normalized_host("Linux", "riscv64"))

    def test_manifest_requires_exact_protocol_host_source_digest_and_four_lf_lines(self):
        source = "sha256:" + "a" * 64
        for target in HOSTS:
            output = "sha256:" + "b" * 64 if target in checks.SUPERVISOR_TARGETS else "unavailable"
            raw = f"{checks.SUPERVISOR_PROTOCOL}\n{target}\n{source}\n{output}\n".encode()
            self.assertEqual(output, checks.manifest(raw, target, source))
            for changed in (raw.rstrip(b"\n"), raw + b"\n", raw.replace(b"\n", b"\r\n"), raw.replace(source.encode(), b"sha256:" + b"c" * 64),
                            raw.replace(checks.SUPERVISOR_PROTOCOL.encode(), b"preview-old")):
                with self.subTest(target=target), self.assertRaises(ValueError):
                    checks.manifest(changed, target, source)
        with self.assertRaises(ValueError):
            checks.manifest(b"", "linux-unknown", source)

    def test_supported_binary_headers_are_host_specific_not_execution_or_abi(self):
        for target in checks.SUPERVISOR_TARGETS:
            self.assertEqual("elf64-executable-header" if target.startswith("linux-") else "thin-macho64-executable-header",
                             checks.binary_format(binary(target), target))
            other = target.replace("x86_64", "aarch64") if target.endswith("x86_64") else target.replace("aarch64", "x86_64")
            with self.assertRaises(ValueError):
                checks.binary_format(binary(target), other)

    def test_32bit_fat_foreign_nonexecutables_and_oversized_headers_fail(self):
        for target in checks.SUPERVISOR_TARGETS:
            raw = binary(target)
            for altered in (b"", raw[:63], b"\xca\xfe\xba\xbe" + raw[4:], b"X" * (1_048_576 + 1), b"\x7fELF\x01" + raw[5:]):
                with self.subTest(target=target), self.assertRaises(ValueError):
                    checks.binary_format(altered, target)
            field = 16 if target.startswith("linux-") else 12
            altered = bytearray(raw); altered[field:field + 4] = b"\x01\0\0\0"
            with self.assertRaises(ValueError):
                checks.binary_format(bytes(altered), target)
        with self.assertRaises(ValueError):
            checks.binary_format(binary("linux-x86_64"), "windows-x86_64")


class NativePreflightTests(unittest.TestCase):
    def test_compiler_version_is_driver_version_not_vendor_build_numbers_or_private_banner(self):
        for raw, vendor, version in ((b"Apple clang version 17.0.0 (clang-1700.4.4)\n", "apple-clang", "17.0.0"),
                                     (b"Ubuntu clang version 20.1.8\nPRIVATE tail", "clang", "20.1.8"),
                                     (b"cc (Ubuntu 13.3.0-6ubuntu2 24.04.1) 13.3.0\n", "gcc", "13.3.0"),
                                     (b"aarch64-linux-gnu-gcc (GCC) 14.2.1 2026\n", "gcc", "14.2.1")):
            self.assertEqual((vendor, version), checks.compiler_version_metadata(raw)[:2])
        for raw in (b"PRIVATE unrecognized banner", b"gcc 123456789.1.2\n", b"clang version PRIVATE\n"):
            with self.assertRaises(ValueError): checks.compiler_version_metadata(raw)

    def test_selected_python_cc_and_inputs_are_observed_without_compilation_or_private_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            with fixture.context(), patch.object(checks, "probe_version", side_effect=fixture.probe) as probe, patch.object(checks, "run_logged") as command:
                result = checks.preflight(fixture.args, fixture.root)
            self.assertEqual("passed", result["state"])
            probe.assert_called_once_with([str(fixture.cc), "--version"], fixture.root, fixture.args.output.parent / "raw/supervisor-cc-version")
            command.assert_not_called()
            self.assertEqual("gcc", result["compiler"]["vendor"])
            self.assertTrue(result["compiler"]["version_first_line_redacted"])
            self.assertFalse(result["native_helper_executed_by_wrapper"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertNotIn(str(fixture.root), json.dumps(result))

    def test_mac_observes_actual_default_sdk_without_forwarding_developer_dir(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "macos-aarch64")
            with fixture.context(), patch.object(checks, "probe_version", side_effect=fixture.probe) as probe:
                result = checks.preflight(fixture.args, fixture.root)
            self.assertEqual("passed", result["state"])
            self.assertEqual(3, probe.call_count)
            self.assertEqual("26.4", result["apple_build_sdk"]["version"])
            self.assertFalse(result["apple_build_sdk"]["DEVELOPER_DIR_forwarded_to_compiler"])
            self.assertFalse(result["apple_build_sdk"]["sdk_closure_verified"])

    def test_windows_prerequisite_is_explicit_unavailability_without_any_compiler_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "windows-x86_64")
            fixture.args.cc = "PRIVATE nonexistent compiler"
            with fixture.context(), patch.object(checks, "probe_version") as probe, patch.object(checks, "run_logged") as command:
                result = checks.preflight(fixture.args, fixture.root)
            probe.assert_not_called(); command.assert_not_called()
            self.assertEqual("passed", result["state"])
            self.assertEqual("unsupported_host_manifest_only", result["execution_scope"])
            self.assertIsNone(result["compiler"])
            self.assertFalse(result["native_compiler_probe_executed"])

    def test_stale_source_host_python_selection_and_missing_inputs_fail_structurally(self):
        for alteration in ("source", "commit", "host", "arch", "python", "launcher", "cc", "missing", "schema"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary))
                changed = copy.deepcopy(fixture.environment)
                if alteration == "source": changed["source"]["working_tree_digest"] = "sha256:" + "f" * 64
                elif alteration == "commit": changed["source"]["commit"] = "f" * 40
                elif alteration in {"host", "arch", "python"}: changed["host"][{"host": "os", "arch": "arch", "python": "python"}[alteration]] = "PRIVATE wrong"
                elif alteration == "launcher": fixture.args.python_command = "PRIVATE launcher"
                elif alteration == "cc": fixture.args.cc = "cc"
                elif alteration == "missing": (fixture.root / checks.SOURCE).unlink()
                else: changed["source"] = None
                write_json(fixture.args.environment, changed)
                result = fixture.prerequisite()
                self.assertEqual("failed", result["state"])
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_version_probe_hashes_the_bounded_combined_stream_under_exact_builder_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, raw = Path(temporary), b"cc 1.2.3\ninterleaved stderr metadata\n"
            log = root / "version"
            write(log.with_suffix(".stdout.log"), raw)
            write(log.with_suffix(".stderr.log"), b"")
            observed = {"state": "completed", "return_code": 0, "command": ["PRIVATE compiler"], "raw_logs_uploaded": False}
            with patch.object(checks, "run_logged", return_value=(observed, "ignored", "ignored")) as command:
                projected, result = checks.probe_version(["/usr/bin/cc", "--version"], root, log)
            self.assertEqual(raw, result)
            self.assertFalse(command.call_args.kwargs["separate_stderr"])
            self.assertEqual(checks.BUILD_ENV, command.call_args.kwargs["env"])
            self.assertNotIn("DEVELOPER_DIR", command.call_args.kwargs["env"])
            self.assertNotIn("PRIVATE", json.dumps(projected))

    def test_failed_oversized_invalid_utf8_and_boolean_version_exit_do_not_pass(self):
        for raw, state, code in ((b"", "completed", 0), (b"x" * 65537, "completed", 0), (b"\xff", "completed", 0),
                                 (b"cc 1.2.3", "timeout", 0), (b"cc 1.2.3", "completed", False)):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(state=state, code=code):
                root = Path(temporary); log = root / "version"
                write(log.with_suffix(".stdout.log"), raw)
                with patch.object(checks, "run_logged", return_value=({"state": state, "return_code": code}, "", "")), self.assertRaises(ValueError):
                    checks.probe_version(["/usr/bin/cc", "--version"], root, log)


class NativeReceiptTests(unittest.TestCase):
    def test_built_receipt_projects_only_exact_digests_flags_and_unqualified_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite()
            result = checks.receipt_metadata(fixture.receipt(prerequisite), prerequisite, checks.digest_bytes(binary(fixture.target)), 96)
            self.assertEqual(checks.FLAGS, result["flags"])
            self.assertFalse(result["exact_private_argv_independently_reconstructed"])
            self.assertFalse(result["source_to_binary_reproducibility_established"])
            self.assertFalse(result["transitive_compiler_toolchain_verified"])
            self.assertNotIn("PRIVATE", json.dumps(result)); self.assertNotIn(str(fixture.root), json.dumps(result))

    def test_windows_receipt_cannot_claim_any_binary_compiler_invocation_or_banner(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "windows-x86_64"); prerequisite = fixture.prerequisite(); receipt = fixture.receipt(prerequisite)
            self.assertEqual("unavailable", checks.receipt_metadata(receipt, prerequisite, "unavailable", 0)["status"])
            for field, value in (("compiler", {}), ("output", {}), ("invocation", {})):
                changed = copy.deepcopy(receipt); changed[field] = value
                with self.subTest(field=field), self.assertRaises(ValueError):
                    checks.receipt_metadata(changed, prerequisite, "unavailable", 0)
            changed = copy.deepcopy(receipt); changed["privacy"]["versionFirstLineRedacted"] = True
            with self.assertRaises(ValueError): checks.receipt_metadata(changed, prerequisite, "unavailable", 0)

    def test_exact_schema_normalized_host_input_identity_and_qualification_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite(); receipt = fixture.receipt(prerequisite)
            for alteration in ("extra", "schema", "protocol", "host", "arch", "source", "builder", "closure", "reproducibility", "driver", "privacy"):
                changed = copy.deepcopy(receipt)
                if alteration == "extra": changed["PRIVATE"] = "PRIVATE raw source"
                elif alteration == "schema": changed["schemaVersion"] = "old"
                elif alteration == "protocol": changed["protocolVersion"] = "old"
                elif alteration == "host": changed["host"]["os"] = "Linux"
                elif alteration == "arch": changed["host"]["architecture"] = "amd64"
                elif alteration in {"source", "builder"}: changed["inputs"][alteration]["sha256"] = "sha256:" + "f" * 64
                elif alteration == "closure": changed["transitiveCompilerToolchainVerified"] = True
                elif alteration == "reproducibility": changed["sourceToBinaryReproducibilityEstablished"] = True
                elif alteration == "driver": changed["compilerDriverIdentityOnly"] = 1
                else: changed["privacy"]["privatePathsRetained"] = True
                with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                    checks.receipt_metadata(changed, prerequisite, checks.digest_bytes(binary(fixture.target)), 96)

    def test_compiler_stream_identity_and_always_redacted_banner_are_mandatory(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite(); receipt = fixture.receipt(prerequisite)
            for field, value in (("driverSha256", "sha256:" + "f" * 64), ("versionOutputSha256", "sha256:" + "f" * 64),
                                 ("versionOutputBytes", True), ("versionFirstLine", "gcc PRIVATE token"), ("versionFirstLine", "cc 1.2.3")):
                changed = copy.deepcopy(receipt); changed["compiler"][field] = value
                with self.subTest(field=field), self.assertRaises(ValueError):
                    checks.receipt_metadata(changed, prerequisite, checks.digest_bytes(binary(fixture.target)), 96)
            changed = copy.deepcopy(receipt); changed["privacy"]["versionFirstLineRedacted"] = False
            with self.assertRaises(ValueError): checks.receipt_metadata(changed, prerequisite, checks.digest_bytes(binary(fixture.target)), 96)

    def test_compiler_public_paths_reject_private_traversal_controls_and_wrong_selection(self):
        for path in ("/home/PRIVATE/compiler", "/usr/bin/../PRIVATE/compiler", "/usr/bin/cc\nPRIVATE", "/usr/bin/cc with-space"):
            self.assertEqual("<private-compiler>", checks.public_compiler_path(PurePosixPath(path)))
        self.assertEqual("/usr/bin/cc", checks.public_compiler_path(PurePosixPath("/usr/bin/cc")))
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite(); receipt = fixture.receipt(prerequisite)
            for field in ("selectedPath", "resolvedPath"):
                changed = copy.deepcopy(receipt); changed["compiler"][field] = "/usr/bin/cc"
                with self.assertRaises(ValueError): checks.receipt_metadata(changed, prerequisite, checks.digest_bytes(binary(fixture.target)), 96)

    def test_invocation_digest_encoding_flags_and_output_identity_are_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite(); receipt = fixture.receipt(prerequisite)
            for role, field, value in (("invocation", "argv", ["PRIVATE command"]), ("invocation", "flags", ["-O0"]),
                                       ("invocation", "exactArgvSha256", "0" * 64), ("invocation", "exactArgvEncoding", "shell-string"),
                                       ("invocation", "privateArgvRetained", True), ("output", "bytes", 95),
                                       ("output", "sha256", "sha256:" + "f" * 64), ("output", "path", "PRIVATE/output")):
                changed = copy.deepcopy(receipt); changed[role][field] = value
                with self.subTest(role=role, field=field), self.assertRaises(ValueError):
                    checks.receipt_metadata(changed, prerequisite, checks.digest_bytes(binary(fixture.target)), 96)

    def test_preflight_success_bit_cannot_replace_exact_source_host_python_and_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); prerequisite = fixture.prerequisite()
            checks.validate_prerequisites(prerequisite, IDENTITY, fixture.host)
            for field, value in (("schema_version", True), ("native_helper_executed_by_wrapper", True), ("release_qualified", True),
                                 ("execution_scope", "release_qualified"), ("host", {}), ("python", {}), ("compiler", {})):
                changed = copy.deepcopy(prerequisite); changed[field] = value
                with self.subTest(field=field), self.assertRaises(ValueError): checks.validate_prerequisites(changed, IDENTITY, fixture.host)


class NativeOutcomeTests(unittest.TestCase):
    def test_frozen_inventory_matches_authored_native_methods_without_executing_them(self):
        document = ast.parse((checks.REPO_ROOT / checks.TEST_FILE).read_text(encoding="utf-8"))
        methods = {f"test_supervisor.{node.name}.{method.name}" for node in document.body if isinstance(node, ast.ClassDef)
                   for method in node.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")}
        self.assertEqual(set(checks.CASES), methods)
        self.assertEqual((4, 18, 7, 29), (len(checks.BUILDER_METHODS), len(checks.NATIVE_METHODS),
                                           len(checks.MOCKED_SHUTDOWN_METHODS), len(checks.CASES)))

    def test_exact_linux_passes_and_darwin_linux_only_skips_have_distinct_scope(self):
        for target, passed, skipped in (("linux-x86_64", 29, 0), ("macos-aarch64", 27, 2)):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target)
                result = checks.outcome_metadata(fixture.outcomes(), IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))
                self.assertEqual((passed, skipped), (result["counts"]["passed"], result["counts"]["skipped"]))
                self.assertEqual(target.startswith("linux-"), result["linux_syscall_and_owner_death_controls_passed"])
                self.assertEqual(18 - skipped, result["native_methods_executed"])
                self.assertEqual(7, result["mocked_shutdown_methods_executed"])
                self.assertEqual(7, sum(row["scope"] == "mocked_shutdown_state" for row in result["cases"]))
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_mocked_shutdown_passes_cannot_replace_unrun_native_methods(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); value = fixture.outcomes()
            for row in value["cases"]:
                if checks.CASES[row["id"]] == "owned_child_runtime":
                    row.update(state="not_run", skip_reason=None)
            value["state"] = "failed"
            value["tests_run"] -= len(checks.NATIVE_METHODS)
            value["counts"] = {state: sum(row["state"] == state for row in value["cases"])
                               for state in ("passed", "failed", "error", "skipped", "not_run")}
            result = checks.outcome_metadata(value, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))
            self.assertEqual("failed", result["state"])
            self.assertEqual(0, result["native_methods_executed"])
            self.assertEqual(7, result["mocked_shutdown_methods_executed"])
            self.assertFalse(result["linux_syscall_and_owner_death_controls_passed"])
            value["state"] = "passed"
            with self.assertRaises(ValueError):
                checks.outcome_metadata(value, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))

    def test_missing_duplicate_wrong_skip_boolean_counts_and_qualification_cannot_be_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "macos-aarch64"); original = fixture.outcomes()
            for alteration in ("missing", "duplicate", "skip", "raw", "qualification", "count", "boolean", "unknown"):
                changed = copy.deepcopy(original)
                if alteration == "missing": changed["cases"].pop()
                elif alteration == "duplicate": changed["cases"][1] = copy.deepcopy(changed["cases"][0])
                elif alteration == "skip": next(row for row in changed["cases"] if row["state"] == "skipped")["skip_reason"] = "unrecognized_skip_reason"
                elif alteration == "raw": changed["raw_exception_messages_retained"] = True
                elif alteration == "qualification": changed["release_qualified"] = True
                elif alteration == "count": changed["counts"]["passed"] -= 1
                elif alteration == "boolean": changed["counts"]["failed"] = False
                else: changed["cases"][0]["id"] = "PRIVATE unknown test"
                with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                    checks.outcome_metadata(changed, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))

    def test_failed_outcomes_are_retained_without_raw_exception_messages(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            result = checks.outcome_metadata(fixture.outcomes("failed"), IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))
            self.assertEqual("failed", result["state"])
            self.assertEqual(1, result["counts"]["failed"])
            self.assertEqual({}, result["failure_diagnostics"])
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_reporter_synthetic_tests_do_not_import_or_execute_the_actual_native_fixture(self):
        for target in ("linux-x86_64", "macos-aarch64"):
            with tempfile.TemporaryDirectory() as temporary:
                fixture = Fixture(Path(temporary), target)
                write(fixture.root / checks.MODULE / "native/test_live_supervisor.py", b"synthetic independent v2 fixture\n")
                class FakeCase(unittest.TestCase):
                    def __init__(self, name): super().__init__("runTest"); self.name = name
                    def id(self): return self.name
                    def runTest(self):
                        if fixture.host["os"] == "macos" and self.name in checks.LINUX_ONLY:
                            self.skipTest(checks.LINUX_ONLY[self.name][0])
                suite = unittest.TestSuite(FakeCase(name) for name in sorted(checks.CASES))
                with fixture.context(), patch.object(runner.unittest.TestLoader, "discover", return_value=suite) as discovery, \
                        patch.dict(sys.modules, {"test_supervisor": SimpleNamespace()}), patch.object(runner.sys, "stdout", io.StringIO()):
                    result = runner.run(fixture.args.output, fixture.cc, fixture.root)
                self.assertEqual("passed", result["state"])
                self.assertEqual(29, result["tests_run"])
                discovery.assert_called_once_with(str(fixture.root / checks.MODULE / "native"), pattern="test_supervisor.py")
                checks.outcome_metadata(result, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root))

    def test_reporter_maps_unknown_skip_and_setup_failure_to_metadata_not_private_text(self):
        stream = io.StringIO(); result = runner.OutcomeResult(stream, False, 1)
        fake = SimpleNamespace(id=lambda: next(iter(checks.CASES)))
        result.record(fake, "skipped", "unrecognized_skip_reason")
        result.record(SimpleNamespace(id=lambda: "PRIVATE setup path"), "error")
        self.assertEqual(1, result.fixture_errors)
        self.assertNotIn("PRIVATE", json.dumps(result.outcomes))


class NativeFailureDiagnosticTests(unittest.TestCase):
    @staticmethod
    def diagnostic(fixture, *, event="assertion_failure", role="tests", line=1):
        return {"event": event, "source": role, "source_digest": checks.native_test_inputs(fixture.root)[role], "line": line}

    @staticmethod
    def synthetic_case(fixture, source, role="tests"):
        # Only this authored Python stub is compiled in memory. The actual C
        # fixtures, native unittest module and compiler are never imported/run.
        path = fixture.root / checks.DIAGNOSTIC_SOURCES[role]
        write(path, source.encode("utf-8"))
        namespace = {}
        exec(compile(source, str(path), "exec"), namespace)
        name = sorted(checks.CASES)[0]
        case_type = type("SyntheticCase", (unittest.TestCase,), {"runTest": namespace["runTest"], "id": lambda _: name})
        sources = checks.native_diagnostic_sources(checks.native_test_inputs(fixture.root), fixture.root)
        return case_type(), partial(runner.OutcomeResult, diagnostic_sources=sources)

    def test_first_bound_assertion_line_survives_real_reporter_and_sanitized_projection(self):
        for target, passed, skipped in (("linux-x86_64", 28, 0), ("macos-aarch64", 26, 2)):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target)
                failed, _ = self.synthetic_case(fixture, "def nested(case):\n    PRIVATE_LOCAL = 'PRIVATE local'\n"
                                                "    case.fail('PRIVATE assertion body')\n\n"
                                                "def runTest(self):\n    nested(self)\n")
                class FakeCase(unittest.TestCase):
                    def __init__(self, name): super().__init__("runTest"); self.name = name
                    def id(self): return self.name
                    def runTest(self):
                        if fixture.host["os"] == "macos" and self.name in checks.LINUX_ONLY:
                            self.skipTest(checks.LINUX_ONLY[self.name][0])
                suite = unittest.TestSuite(failed if name == failed.id() else FakeCase(name) for name in sorted(checks.CASES))
                with fixture.context(), patch.object(runner.unittest.TestLoader, "discover", return_value=suite), \
                        patch.dict(sys.modules, {"test_supervisor": SimpleNamespace()}), patch.object(runner.sys, "stdout", io.StringIO()):
                    raw = runner.run(fixture.args.output, fixture.cc, fixture.root)
                projected = checks.outcome_metadata(raw, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root), fixture.root)
                self.assertEqual("failed", raw["state"])
                self.assertEqual(29, raw["tests_run"])
                self.assertEqual({"passed": passed, "failed": 1, "error": 0, "skipped": skipped, "not_run": 0}, projected["counts"])
                self.assertEqual({failed.id(): self.diagnostic(fixture, line=3)}, projected["failure_diagnostics"])
                self.assertFalse(raw["native_abi_qualified"])
                self.assertFalse(raw["release_qualified"])
                self.assertNotIn("PRIVATE", json.dumps(raw))
                self.assertNotIn(str(fixture.root), json.dumps(projected))

    def test_builder_location_is_bound_and_unknown_paths_messages_and_overlong_traces_are_not_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            case, result_class = self.synthetic_case(fixture, "def runTest(self):\n    raise RuntimeError('PRIVATE builder error')\n", "builder")
            outcome = unittest.TextTestRunner(stream=io.StringIO(), resultclass=result_class).run(case)
            self.assertEqual({case.id(): self.diagnostic(fixture, event="test_error", role="builder", line=2)}, outcome.failure_diagnostics)
            result = result_class(io.StringIO(), False, 0)
            missing = {"event": "assertion_failure", "source": None, "source_digest": None, "line": None}
            frame = SimpleNamespace(f_code=SimpleNamespace(co_filename=str(fixture.root / checks.TEST_FILE)))
            class PrivateException(Exception):
                def __str__(self): raise AssertionError("diagnostic formatted a private exception")
                def __repr__(self): raise AssertionError("diagnostic formatted a private exception")
            trace = None
            for _ in range(64):
                trace = SimpleNamespace(tb_frame=frame, tb_lineno=1, tb_next=trace)
            error = (PrivateException, PrivateException("PRIVATE exception"), trace)
            self.assertEqual(self.diagnostic(fixture), result.failure_diagnostic("assertion_failure", error))
            trace = SimpleNamespace(tb_frame=frame, tb_lineno=1, tb_next=trace)
            self.assertEqual(missing, result.failure_diagnostic("assertion_failure", (*error[:2], trace)))
            for filename in ("PRIVATE/test_supervisor.py", str(fixture.root / "PRIVATE/test_supervisor.py"), checks.TEST_FILE):
                unknown = SimpleNamespace(tb_frame=SimpleNamespace(f_code=SimpleNamespace(co_filename=filename)), tb_lineno=1, tb_next=None)
                self.assertEqual(missing, result.failure_diagnostic("assertion_failure", (*error[:2], unknown)))
            self.assertNotIn("PRIVATE", json.dumps(outcome.failure_diagnostics))

    def test_subtests_keep_first_event_and_error_precedence_without_values_or_invocation_claims(self):
        for first_error in (False, True):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(first_error=first_error):
                fixture = Fixture(Path(temporary))
                first = "raise RuntimeError('PRIVATE first')" if first_error else "self.fail('PRIVATE first')"
                case, result_class = self.synthetic_case(fixture, "def runTest(self):\n    with self.subTest(value='PRIVATE first'):\n"
                                                        f"        {first}\n    with self.subTest(value='PRIVATE second'):\n"
                                                        "        raise RuntimeError('PRIVATE second')\n"
                                                        "    with self.subTest(value='PRIVATE third'):\n        self.fail('PRIVATE third')\n")
                outcome = unittest.TextTestRunner(stream=io.StringIO(), resultclass=result_class).run(case)
                self.assertEqual("error", outcome.outcomes[case.id()]["state"])
                self.assertEqual(0, outcome.fixture_errors)
                self.assertEqual({case.id(): self.diagnostic(fixture, event="test_error" if first_error else "assertion_failure", line=3)},
                                 outcome.failure_diagnostics)
                self.assertNotIn("PRIVATE", json.dumps(outcome.failure_diagnostics))
                report = fixture.outcomes("failed")
                report["cases"][0]["state"] = "error"
                report["counts"].update(failed=0, error=1)
                report["failure_diagnostics"] = outcome.failure_diagnostics
                self.assertEqual("failed", checks.outcome_metadata(report, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root), fixture.root)["state"])

    def test_expected_failure_and_unexpected_success_remain_failed_with_fixed_events(self):
        for failing in (False, True):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(failing=failing):
                fixture = Fixture(Path(temporary))
                case, result_class = self.synthetic_case(fixture, "def runTest(self):\n    " + ("self.fail('PRIVATE expected failure')" if failing else "pass") + "\n")
                case.__class__.runTest = unittest.expectedFailure(case.__class__.runTest)
                outcome = unittest.TextTestRunner(stream=io.StringIO(), resultclass=result_class).run(case)
                self.assertEqual("failed", outcome.outcomes[case.id()]["state"])
                expected = self.diagnostic(fixture, event="expected_failure", line=2) if failing else \
                    {"event": "unexpected_success", "source": None, "source_digest": None, "line": None}
                self.assertEqual({case.id(): expected}, outcome.failure_diagnostics)
                report = fixture.outcomes("failed"); report["failure_diagnostics"] = outcome.failure_diagnostics
                self.assertEqual("failed", checks.outcome_metadata(report, IDENTITY, fixture.host, checks.native_test_inputs(fixture.root), fixture.root)["state"])

    def test_unknown_fixture_errors_have_no_diagnostic_method_label(self):
        result = runner.OutcomeResult(io.StringIO(), False, 0)
        result.record(SimpleNamespace(id=lambda: "PRIVATE setup fixture"), "error", event="test_error")
        self.assertEqual(1, result.fixture_errors)
        self.assertEqual({}, result.failure_diagnostics)

    def test_diagnostic_validation_rejects_unbound_lines_values_codes_and_outcome_disagreement(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); original = fixture.outcomes("failed")
            name = original["cases"][0]["id"]
            original["failure_diagnostics"] = {name: self.diagnostic(fixture)}
            inputs = checks.native_test_inputs(fixture.root)
            self.assertEqual(original["failure_diagnostics"], checks.outcome_metadata(original, IDENTITY, fixture.host, inputs, fixture.root)["failure_diagnostics"])
            invalid = (("event", "PRIVATE event"), ("event", ["assertion_failure"]), ("event", "test_error"),
                       ("event", "unexpected_success"), ("source", "PRIVATE path"), ("source", []), ("source", None),
                       ("source_digest", "sha256:" + "0" * 64), ("source_digest", None), ("source_digest", {}),
                       ("line", True), ("line", 0), ("line", -1), ("line", 2), ("line", 1.0), ("line", "1"), ("line", None),
                       ("message", "PRIVATE body"), ("filename", "PRIVATE/test_supervisor.py"), ("locals", {"PRIVATE": "value"}))
            for field, value in invalid:
                changed = copy.deepcopy(original); changed["failure_diagnostics"][name][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    checks.outcome_metadata(changed, IDENTITY, fixture.host, inputs, fixture.root)
            for diagnostics in (None, [], {name: None}, {name: {}}, {"PRIVATE unknown method": self.diagnostic(fixture)},
                                {original["cases"][1]["id"]: self.diagnostic(fixture)}):
                changed = copy.deepcopy(original); changed["failure_diagnostics"] = diagnostics
                with self.subTest(diagnostics=diagnostics), self.assertRaises(ValueError):
                    checks.outcome_metadata(changed, IDENTITY, fixture.host, inputs, fixture.root)
            missing = {"event": "assertion_failure", "source": None, "source_digest": None, "line": None}
            original["failure_diagnostics"][name] = missing
            self.assertEqual({name: missing}, checks.outcome_metadata(original, IDENTITY, fixture.host, inputs, fixture.root)["failure_diagnostics"])

    def test_changed_source_bytes_refuse_diagnostic_binding_and_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); report = fixture.outcomes("failed")
            report["failure_diagnostics"] = {report["cases"][0]["id"]: self.diagnostic(fixture)}
            inputs = checks.native_test_inputs(fixture.root)
            write(fixture.root / checks.TEST_FILE, b"PRIVATE changed source\n")
            with self.assertRaises(ValueError): checks.native_diagnostic_sources(inputs, fixture.root)
            with self.assertRaises(ValueError): checks.outcome_metadata(report, IDENTITY, fixture.host, inputs, fixture.root)
            with fixture.context(), patch.object(checks, "native_test_inputs", return_value=inputs), \
                    patch.object(runner.unittest.TestLoader, "discover") as discover:
                result = runner.run(fixture.args.output, fixture.cc, fixture.root)
            discover.assert_not_called()
            self.assertEqual("failed", result["state"])
            self.assertEqual(1, result["fixture_errors"])
            self.assertEqual(0, result["tests_run"])
            self.assertEqual({}, result["failure_diagnostics"])

    def test_failed_wrapper_retains_only_validated_diagnostics_and_collect_cannot_drop_them(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); fixture.prerequisite()
            report = fixture.outcomes("failed")
            report["failure_diagnostics"] = {report["cases"][0]["id"]: self.diagnostic(fixture)}
            def command(argv, **_):
                write_json(Path(argv[argv.index("--output") + 1]), report)
                return {"state": "completed", "return_code": 1, "raw_logs_uploaded": False}, "PRIVATE stdout", ""
            with fixture.context(), patch.object(checks, "run_logged", side_effect=command):
                result = checks.controls(fixture.args, fixture.root)
            self.assertEqual("failed", result["state"])
            self.assertEqual(report["failure_diagnostics"], result["controls"]["failure_diagnostics"])
            self.assertEqual(1, result["controls"]["counts"]["failed"])
            self.assertFalse(result["native_fixture_controls_passed"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            fixture.bundle()
            installed = checks.strict_json(fixture.args.controls.read_bytes())
            installed["controls"]["failure_diagnostics"] = report["failure_diagnostics"]
            write_json(fixture.args.controls, installed)
            self.assertEqual("failed", fixture.collect()["state"])


class NativeControlOrchestrationTests(unittest.TestCase):
    def execute(self, fixture, report_state="passed", command_state="completed", command_code=0):
        def command(argv, **_):
            report = Path(argv[argv.index("--output") + 1]); write_json(report, fixture.outcomes(report_state))
            return {"state": command_state, "return_code": command_code, "command": ["PRIVATE command"], "raw_logs_uploaded": False}, "PRIVATE stdout", ""
        with fixture.context(), patch.object(checks, "run_logged", side_effect=command) as invoked:
            result = checks.controls(fixture.args, fixture.root)
        return result, invoked

    def test_posix_actual_orchestration_is_separate_from_byte_inspection_and_provider_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); fixture.prerequisite()
            result, invoked = self.execute(fixture)
            self.assertEqual("passed", result["state"])
            self.assertTrue(result["native_fixture_controls_passed"])
            self.assertEqual(str(fixture.cc), invoked.call_args.args[0][-1])
            self.assertFalse(result["provider_controls_executed_by_wrapper"])
            self.assertFalse(result["execution_verified"])
            self.assertNotIn("PRIVATE", json.dumps(result)); self.assertNotIn(str(fixture.root), json.dumps(result))

    def test_windows_availability_policy_is_not_a_native_pass_and_spawns_nothing(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "windows-x86_64"); fixture.prerequisite()
            with fixture.context(), patch.object(checks, "run_logged") as command:
                result = checks.controls(fixture.args, fixture.root)
            command.assert_not_called()
            self.assertEqual("passed", result["state"])
            self.assertEqual("unavailable", result["controls"]["state"])
            self.assertEqual(29, result["controls"]["methods_not_executed"])
            self.assertEqual(18, result["controls"]["native_methods_not_executed"])
            self.assertEqual(7, result["controls"]["mocked_shutdown_methods_not_executed"])
            self.assertFalse(result["native_fixture_controls_passed"])
            self.assertFalse(result["native_helper_executed_by_test_harness"])

    def test_failed_native_reports_command_failures_and_absent_strace_remain_visible(self):
        for report_state, command_state, code, trace in (("failed", "completed", 1, True), ("passed", "timeout", 0, True),
                                                        ("passed", "completed", False, True), ("passed", "completed", 0, False)):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report_state, command=command_state, code=code, trace=trace):
                fixture = Fixture(Path(temporary))
                if not trace: fixture.observer.update(state="unavailable", driver_digest=None)
                fixture.prerequisite(); result, _ = self.execute(fixture, report_state, command_state, code)
                self.assertEqual("failed", result["state"])
                self.assertFalse(result["native_fixture_controls_passed"])
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_stale_report_changed_compiler_or_unknown_test_files_are_rejected_before_execution(self):
        for alteration in ("report", "compiler", "test_inventory"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary)); fixture.prerequisite()
                if alteration == "report": write(fixture.args.output.parent / "raw/supervisor-unittest.json", b"{}")
                elif alteration == "compiler": fixture.cc.write_bytes(b"changed synthetic compiler")
                else: write(fixture.root / checks.MODULE / "native/test_unexpected.py", b"PRIVATE source")
                with fixture.context(), patch.object(checks, "run_logged") as command:
                    result = checks.controls(fixture.args, fixture.root)
                command.assert_not_called(); self.assertEqual("failed", result["state"])


class NativeInstalledByteTests(unittest.TestCase):
    def test_all_supported_host_bundles_match_receipt_architecture_jars_and_doctor_without_execution(self):
        for target in checks.SUPERVISOR_TARGETS:
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target); fixture.bundle(); result = fixture.collect()
                self.assertEqual("passed", result["state"])
                self.assertTrue(result["native_compilation_recorded"])
                self.assertTrue(result["installed_bundle_bytes_verified"])
                self.assertFalse(result["native_helper_executed_by_wrapper"])
                self.assertFalse(result["execution_verified"]); self.assertFalse(result["native_abi_qualified"]); self.assertFalse(result["release_qualified"])
                self.assertNotIn("PRIVATE", json.dumps(result)); self.assertNotIn(str(fixture.root), json.dumps(result))

    def test_windows_and_unknown_hosts_have_no_binary_compiler_or_native_controls_pass(self):
        for target in ("windows-x86_64", "windows-aarch64", "unsupported"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target); fixture.bundle(); result = fixture.collect()
                self.assertEqual("passed", result["state"])
                self.assertFalse(result["native_binary_present"]); self.assertFalse(result["native_compilation_recorded"])
                self.assertEqual("unavailable", result["helper_availability"])
                self.assertEqual("unavailable", result["build_receipt"]["status"])

    def test_failed_cached_missing_runtime_task_or_malformed_build_is_not_actual_execution(self):
        for alteration in ("failed", "cached", "no_source", "skipped", "missing", "malformed"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary)); fixture.bundle()
                value = checks.strict_json(fixture.args.build.read_bytes())
                if alteration == "failed": value["state"] = "failed"
                elif alteration == "missing": del value["tasks"][checks.TEST_TASK]
                elif alteration == "malformed": value["tasks"] = None
                else: value["tasks"][checks.TASK] = {"cached": "FROM-CACHE", "no_source": "NO-SOURCE", "skipped": "SKIPPED"}[alteration]
                write_json(fixture.args.build, value); self.assertEqual("failed", fixture.collect()["state"])

    def test_missing_stale_or_failed_build_smoke_and_native_controls_are_not_fabricated(self):
        for filename in ("prerequisites", "build", "smoke", "controls"):
            for alteration in ("missing", "source", "failed"):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(file=filename, alteration=alteration):
                    fixture = Fixture(Path(temporary)); fixture.bundle(); path = getattr(fixture.args, filename)
                    if alteration == "missing": path.unlink()
                    else:
                        value = checks.strict_json(path.read_bytes())
                        if alteration == "source": value["source"] = dict(IDENTITY, working_tree_digest="sha256:" + "f" * 64)
                        else: value["state"] = "failed"
                        write_json(path, value)
                    self.assertEqual("failed", fixture.collect()["state"])

    def test_generated_foreign_targets_bad_digests_and_non_lf_receipts_fail(self):
        for alteration in ("foreign", "foreign_empty_directory", "binary", "manifest", "receipt_lf", "receipt_crlf", "receipt_private"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary)); fixture.bundle(); generated = fixture.root / checks.RESOURCES
                if alteration == "foreign": write(generated / checks.PREFIX / "windows-x86_64/supervisor", b"foreign")
                elif alteration == "foreign_empty_directory": (generated / checks.PREFIX / "windows-x86_64").mkdir()
                elif alteration == "binary": write(generated / checks.PREFIX / (fixture.target + "/supervisor"), b"wrong bytes")
                elif alteration == "manifest": write(generated / checks.PREFIX / "manifest", b"invalid")
                else:
                    path = generated / checks.PREFIX / "build-provenance.json"; raw = path.read_bytes()
                    if alteration == "receipt_lf": raw = raw.rstrip(b"\n")
                    elif alteration == "receipt_crlf": raw = raw.replace(b"\n", b"\r\n")
                    else: value = checks.strict_json(raw); value["PRIVATE"] = "PRIVATE compiler command"; raw = (json.dumps(value) + "\n").encode()
                    write(path, raw)
                result = fixture.collect(); self.assertEqual("failed", result["state"]); self.assertNotIn("PRIVATE", json.dumps(result))

    def test_packaged_missing_foreign_duplicate_unsafe_paths_and_symlink_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); fixture.bundle()
            for name in (checks.PREFIX + "windows-x86_64/supervisor", checks.PREFIX + "windows-x86_64/", checks.PREFIX + "manifest",
                         "../private", "/absolute/private", "C:/private", "dev//private", "dev/./private"):
                with self.subTest(name=name), warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    fixture.jar(fixture.module_jar, fixture.expected, extras=((name, b"PRIVATE unrelated resource"),))
                    with self.assertRaises(ValueError): checks.jar_resources(fixture.module_jar, fixture.expected, fixture.root)
            entry = zipfile.ZipInfo(checks.PREFIX + "alias"); entry.create_system = 3; entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            fixture.jar(fixture.module_jar, fixture.expected, extras=((entry, b"PRIVATE target"),))
            with self.assertRaises(ValueError): checks.jar_resources(fixture.module_jar, fixture.expected, fixture.root)
            absent = dict(fixture.expected); absent.pop(checks.PREFIX + "manifest"); fixture.jar(fixture.module_jar, absent)
            with self.assertRaises(ValueError): checks.jar_resources(fixture.module_jar, fixture.expected, fixture.root)

    def test_packaged_raw_backslash_and_nul_names_are_rejected_but_canonical_name_is_allowed(self):
        for name in ("dev/private", "dev\\private", "dev/private\x00suffix"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(name=name):
                fixture = Fixture(Path(temporary)); fixture.bundle()
                encoded = name.encode("ascii")
                placeholder = b"raw" + b"_" * (len(encoded) - 3)
                self.assertEqual(len(encoded), len(placeholder))
                payload = b"PRIVATE raw-name fixture payload"
                fixture.jar(fixture.module_jar, fixture.expected, extras=((placeholder.decode("ascii"), payload),))
                raw = bytearray(fixture.module_jar.read_bytes())
                original_size = len(raw)
                self.assertEqual(2, raw.count(placeholder))
                local_name = raw.index(placeholder)
                central_name = raw.index(placeholder, local_name + len(placeholder))
                # writestr sanitizes backslashes on Windows and NULs everywhere.
                # Patch only equally sized local/central filename fields, not a
                # ZipInfo object or unchecked occurrences in arbitrary ZIP data.
                for offset, header_size, signature, length_field in (
                        (local_name, 30, b"PK\x03\x04", 26), (central_name, 46, b"PK\x01\x02", 28)):
                    self.assertGreaterEqual(offset, header_size)
                    header = offset - header_size
                    self.assertEqual(signature, raw[header:header + 4])
                    self.assertEqual(len(placeholder), int.from_bytes(raw[header + length_field:header + length_field + 2], "little"))
                    self.assertEqual(0, int.from_bytes(raw[header + length_field + 2:header + length_field + 4], "little"))
                self.assertEqual(local_name - 30, int.from_bytes(raw[central_name - 4:central_name], "little"))
                for offset in (local_name, central_name):
                    raw[offset:offset + len(placeholder)] = encoded
                self.assertEqual(original_size, len(raw))
                self.assertEqual(2, raw.count(encoded))
                self.assertNotIn(placeholder, raw)
                fixture.module_jar.write_bytes(raw)
                with zipfile.ZipFile(fixture.module_jar) as archive:
                    entries = [entry for entry in archive.infolist() if entry.orig_filename == name]
                    self.assertEqual(1, len(entries))
                    # Prove the raw central name and matching local header can
                    # be read; BadZipFile is not evidence of policy rejection.
                    self.assertEqual(name, entries[0].orig_filename)
                    self.assertEqual(payload, archive.read(entries[0]))
                if name == "dev/private":
                    result = checks.jar_resources(fixture.module_jar, fixture.expected, fixture.root)
                    self.assertEqual(set(fixture.expected), {entry["path"] for entry in result["native_resources"]})
                else:
                    with self.assertRaisesRegex(ValueError, "unsafe or foreign resource path"):
                        checks.jar_resources(fixture.module_jar, fixture.expected, fixture.root)

    def test_installed_jar_smoke_input_and_doctor_identity_must_bind_the_same_bytes(self):
        for alteration in ("installed", "smoke_digest", "doctor_source", "doctor_binary", "doctor_qualified", "exit_boolean"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary)); fixture.bundle()
                if alteration == "installed": fixture.jar(fixture.installed_jar, fixture.expected, extras=(("extra", b"same native bytes different JAR"),))
                else:
                    value = checks.strict_json(fixture.args.smoke.read_bytes())
                    if alteration == "smoke_digest": value["installation_files"][0]["digest"] = "sha256:" + "f" * 64
                    elif alteration == "doctor_source": value["doctor"]["processContainment"]["identity"]["sourceDigest"] = "sha256:" + "f" * 64
                    elif alteration == "doctor_binary": value["doctor"]["processContainment"]["identity"]["binaryDigest"] = "sha256:" + "f" * 64
                    elif alteration == "exit_boolean": value["execution"]["return_code"] = False
                    else: value["doctor"]["processContainment"]["executionVerified"] = True
                    write_json(fixture.args.smoke, value)
                self.assertEqual("failed", fixture.collect()["state"])

    def test_native_controls_success_requires_exact_method_inventory_and_false_qualification(self):
        for alteration in ("methods", "scope", "qualification", "inputs", "linux_observer"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary)); fixture.bundle(); value = checks.strict_json(fixture.args.controls.read_bytes())
                if alteration == "methods": value["controls"]["cases"].pop()
                elif alteration == "scope": value["native_fixture_controls_executed"] = False
                elif alteration == "qualification": value["release_qualified"] = True
                elif alteration == "inputs": value["test_inputs"]["tests"] = "sha256:" + "f" * 64
                else: value["linux_syscall_observer"] = {"state": "unavailable"}
                write_json(fixture.args.controls, value); self.assertEqual("failed", fixture.collect()["state"])

    @unittest.skipIf(os.name == "nt", "Windows symlink capability is not required for this synthetic POSIX alias control")
    def test_generated_or_packaged_descendant_aliases_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary)); fixture.bundle()
            generated = fixture.root / checks.RESOURCES / checks.PREFIX / "manifest"
            raw = generated.read_bytes(); generated.unlink(); target = fixture.root / "outside-manifest"; target.write_bytes(raw); generated.symlink_to(target)
            self.assertEqual("failed", fixture.collect()["state"])
            alias = fixture.root / "alias.jar"; alias.symlink_to(fixture.module_jar)
            with self.assertRaises(ValueError): checks.jar_resources(alias, fixture.expected, fixture.root)


if __name__ == "__main__":
    unittest.main()
