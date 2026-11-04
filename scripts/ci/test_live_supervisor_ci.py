#!/usr/bin/env python3
"""Synthetic tests of live CI reporting; never import the real native fixture.

Only repository source declaration inventory is read. Executed cases, compiler
identities, fixture custody and imported binding controls below are synthetic.
No compiler, native helper, syscall observer or outer worker process launches.
"""

from __future__ import annotations

import ast
from contextlib import ExitStack, contextmanager
from dataclasses import replace
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

if __package__:
    from . import live_supervisor_test_runner as runner
else:
    import live_supervisor_test_runner as runner


COMMIT = "a" * 40
IDENTITY = {"commit": COMMIT, "dirty": False, "working_tree_digest": "sha256:" + "b" * 64,
            "file_count": 9, "digest_scope": "synthetic fixture bytes only", "unsupported_directories": 0}
HOSTS = {"linux-x86_64": ("Linux", "x86_64", "Linux"), "linux-aarch64": ("Linux", "aarch64", "Linux"),
         "windows-x86_64": ("Windows", "AMD64", "Windows"), "macos-aarch64": ("Darwin", "arm64", "macOS")}


def write(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)


class Fixture:
    def __init__(self, root: Path, target="linux-x86_64", callbacks=None, setup_error=False, unknown_custody=False):
        self.root, self.target = root, target
        self.system, self.machine, runner_os = HOSTS[target]
        self.host = runner.v1.normalized_host(self.system, self.machine)
        write(root / ".git/HEAD", (COMMIT + "\n").encode())
        for relative in runner.INPUTS.values():
            write(root / relative, b"# Synthetic identity bytes; never the actual native source.\n")
        write(root / runner.NATIVE / "test_supervisor.py", b"# Explicit independent v1 sibling.\n")
        self.cc = root / "PRIVATE selected compiler/cc"
        write(self.cc, b"synthetic driver identity; never execute\n")
        self.cc.chmod(0o755)
        self.config = runner.Config(root, root / "build/ci-evidence/live.json", root / "build/ci-evidence/live.xml", self.cc,
            COMMIT, {"run_id": 1, "run_attempt": 2, "job": "synthetic", "host": "synthetic-host", "runner_os": runner_os}, True)
        self.directory = root / "synthetic native attempt"
        self.attempt = SimpleNamespace(root=self.directory, pending=[], retained=False)
        if unknown_custody:
            write(self.directory / "retained-sentinel", b"synthetic unresolved custody\n")
            self.attempt.retained = True
            self.attempt.pending = [SimpleNamespace(kill=lambda: self.fail_if_called())]
        self.classes = {}
        callbacks = callbacks or {}
        for name, methods in runner.METHODS.items():
            attributes = {"__module__": "test_live_supervisor"}
            for method in methods:
                callback = callbacks.get(f"test_live_supervisor.{name}.{method}")
                def body(case, callback=callback):
                    if callback is not None:
                        callback(case)
                attributes[method] = body
            if name == "NativeLiveSupervisorTest":
                attributes.update(root=self.directory, attempt=self.attempt)
                if target not in runner.TARGETS:
                    attributes.update(__unittest_skip__=True, __unittest_skip_why__="PRIVATE skipped native text")
                if setup_error:
                    def failed_setup(cls):
                        raise RuntimeError("PRIVATE setup exception /private/fixture")
                    attributes["setUpClass"] = classmethod(failed_setup)
            self.classes[name] = type(name, (unittest.TestCase,), attributes)
        self.suite = unittest.TestSuite(unittest.TestLoader().loadTestsFromTestCase(self.classes[name]) for name in sorted(self.classes))
        builder = SimpleNamespace(PROTOCOL=runner.PROTOCOL, SUPPORTED=runner.TARGETS, host_identity=lambda: self.host)
        self.module = SimpleNamespace(builder=builder, CC=self.cc, NativeLiveSupervisorTest=self.classes["NativeLiveSupervisorTest"])
        identity = {"state": "available", "digest": "sha256:" + "c" * 64, "size_bytes": 40,
                    "driver_identity_only": True, "private_path_retained": False, "version_probe_performed": False}
        self.tools = {"python": identity, "compiler": identity if target in runner.TARGETS else {"state": "not_applicable"},
                      "syscall_observer": identity if target in runner.TARGETS else {"state": "not_applicable"}}

    @staticmethod
    def fail_if_called(*_, **__):
        raise AssertionError("synthetic reporter test attempted a process or outer cleanup")

    @contextmanager
    def context(self):
        with ExitStack() as stack:
            stack.enter_context(patch.object(runner.platform, "system", return_value=self.system))
            stack.enter_context(patch.object(runner.platform, "machine", return_value=self.machine))
            stack.enter_context(patch.object(runner.v1, "source_identity", return_value=IDENTITY))
            stack.enter_context(patch.object(runner, "tool_snapshot", return_value=self.tools))
            stack.enter_context(patch.object(runner, "load_fixture", return_value=self.module))
            stack.enter_context(patch.object(runner, "fixture_binding_verified", return_value=True))
            discovery = stack.enter_context(patch.object(runner.recorder.RecordingLoader, "discover", return_value=self.suite))
            stack.enter_context(patch.object(runner.recorder, "run_checks", side_effect=self.fail_if_called))
            stack.enter_context(patch.object(runner.recorder, "stop_worker", side_effect=self.fail_if_called))
            stack.enter_context(patch.object(runner.recorder.subprocess, "Popen", side_effect=self.fail_if_called))
            yield discovery

    def run(self):
        original_loader = runner.recorder.RecordingLoader
        with self.context() as discovery:
            result = runner.run(self.config)
        if runner.recorder.RecordingLoader is not original_loader:
            raise AssertionError("shared recording loader was not restored")
        discovery.assert_called_once_with(str(self.root / runner.NATIVE), pattern=runner.TEST_NAME)
        return result


class LiveInventoryTests(unittest.TestCase):
    def test_frozen_inventory_matches_source_declarations_without_importing_native(self):
        source = (runner.recorder.REPO_ROOT / runner.INPUTS["tests"]).read_text(encoding="utf-8")
        document = ast.parse(source)
        actual = {f"test_live_supervisor.{node.name}.{method.name}" for node in document.body if isinstance(node, ast.ClassDef)
                  for method in node.body if isinstance(method, ast.FunctionDef) and method.name.startswith("test_")}
        self.assertEqual(set(runner.CASES), actual)
        self.assertEqual((94, 45, 49), (len(runner.CASES), sum(scope == "python" for scope in runner.CASES.values()),
                                     sum(scope == "native" for scope in runner.CASES.values())))
        self.assertFalse(set(runner.CASES) & set(runner.v1.CASES))

    def test_live_inputs_require_the_explicit_sibling_inventory_and_bind_only_live_roles(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            original = runner.input_snapshot(fixture.root)
            write(fixture.root / runner.NATIVE / "test_supervisor.py", b"# Changed independent v1 fixture.\n")
            self.assertEqual(original, runner.input_snapshot(fixture.root))
            write(fixture.root / runner.NATIVE / "test_unknown.py", b"# Unknown fixture.\n")
            with self.assertRaises((ValueError, runner.recorder.GuardError)):
                runner.input_snapshot(fixture.root)


class LiveExecutionTests(unittest.TestCase):
    def test_linux_complete_callbacks_have_distinct_native_scope_and_junit_binding(self):
        for target in runner.TARGETS:
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target)
                result = fixture.run()
                self.assertEqual("passed", result["state"])
                self.assertTrue(result["results_complete"] and result["persistence_complete"] and result["source_verified"])
                self.assertEqual((94, 94, 0), (result["counts"]["tests_run"], result["counts"]["successful_tests"], result["counts"]["skipped"]))
                self.assertEqual((45, 49), (result["control_scopes"]["python"]["successful_methods"], result["control_scopes"]["native"]["successful_methods"]))
                self.assertEqual("closed_by_original_harness", result["native_fixture_custody"]["state"])
                self.assertFalse(any(result["qualification"].values()))
                self.assertEqual(result, json.loads(fixture.config.output.read_bytes()))
                xml = fixture.config.junit.read_bytes()
                self.assertEqual(runner.recorder.digest(xml), result["junit_report"]["digest"])
                self.assertEqual(94, len(ET.fromstring(xml).findall("testcase")))
                self.assertNotIn("PRIVATE", json.dumps(result))
                self.assertNotIn(str(fixture.root), json.dumps(result))

    def test_windows_and_macos_pass_python_controls_with_explicit_native_unavailability(self):
        for target in ("windows-x86_64", "macos-aarch64"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(target=target):
                fixture = Fixture(Path(temporary), target)
                result = fixture.run()
                self.assertEqual("passed", result["state"])
                self.assertEqual((94, 45, 49), (result["counts"]["tests_run"], result["counts"]["successful_tests"], result["counts"]["skipped"]))
                self.assertEqual("passed", result["control_scopes"]["python"]["state"])
                native = result["control_scopes"]["native"]
                self.assertEqual(("unavailable", "unsupported_host", 0, 49), (native["state"], native["reason"], native["successful_methods"], native["skipped_test_cases"]))
                self.assertFalse(result["native_execution_supported_on_host"])
                self.assertEqual("not_applicable", result["native_fixture_custody"]["state"])
                self.assertEqual("not_applicable", result["tools_before"]["compiler"]["state"])
                self.assertNotIn("PRIVATE", fixture.config.output.read_text())
                self.assertNotIn("PRIVATE", fixture.config.junit.read_text())

    def test_unknown_custody_never_becomes_a_pass_from_successful_test_callbacks(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), unknown_custody=True)
            result = fixture.run()
            self.assertEqual("incomplete", result["state"])
            self.assertEqual(("passed", 0, True), (result["standard_unittest"]["state"], result["test_execution_exit_code"], result["results_complete"]))
            self.assertEqual("incomplete", result["control_scopes"]["native"]["state"])
            self.assertEqual("unknown", result["native_fixture_custody"]["state"])
            self.assertEqual(1, result["native_fixture_custody"]["unresolved_obtained_handle_count"])
            self.assertEqual(b"synthetic unresolved custody\n", (fixture.directory / "retained-sentinel").read_bytes())

    def test_setup_failure_retains_blocked_selections_and_real_started_denominator(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), setup_error=True)
            result = fixture.run()
            self.assertEqual(("failed", 1, 45, 1), (result["state"], result["test_execution_exit_code"], result["counts"]["tests_run"], result["counts"]["errors"]))
            self.assertEqual(49, result["test_denominators"]["setup_blocked_test_cases"])
            self.assertEqual(1, len(result["framework_events"]))
            self.assertEqual("failed", result["control_scopes"]["native"]["state"])
            self.assertNotIn("PRIVATE", fixture.config.output.read_text())

    def test_partial_failure_then_interrupt_retains_callback_prefix_and_never_passes(self):
        def failure(_case):
            raise AssertionError("PRIVATE assertion value")
        def interrupt(_case):
            raise KeyboardInterrupt("PRIVATE interruption")
        first, second = sorted(runner.CASES)[:2]
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), callbacks={first: failure, second: interrupt})
            result = fixture.run()
            self.assertEqual(("incomplete", 2, 2, 1), (result["state"], result["test_execution_exit_code"], result["counts"]["tests_run"], result["counts"]["failures"]))
            self.assertFalse(result["results_complete"])
            self.assertEqual(92, result["test_denominators"]["pending_selected_test_cases"])
            self.assertEqual("failure", result["cases"][0]["events"][0]["kind"])
            self.assertNotIn("PRIVATE", fixture.config.output.read_text())
            self.assertNotIn("PRIVATE", fixture.config.junit.read_text())

    def test_missing_duplicate_or_extra_selection_does_not_receive_inventory_credit(self):
        for alteration in ("missing", "duplicate", "extra", "nonstandard", "aliased"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                executed = []
                fixture = Fixture(Path(temporary), callbacks={name: lambda _case: executed.append("body") for name in runner.CASES})
                for cls in fixture.classes.values():
                    cls.setUpClass = classmethod(lambda _cls: executed.append("setup"))
                leaves = [case for suite in fixture.suite for case in suite]
                if alteration == "missing":
                    leaves.pop()
                elif alteration == "duplicate":
                    leaves[-1] = leaves[0]
                elif alteration == "extra":
                    cls = type("UnexpectedTest", (unittest.TestCase,), {"__module__": "test_live_supervisor", "test_extra": lambda self: executed.append("unexpected")})
                    leaves.append(cls("test_extra"))
                fixture.suite = unittest.TestSuite(leaves)
                if alteration == "nonstandard":
                    class NonstandardSuite(unittest.TestSuite):
                        pass
                    fixture.suite = NonstandardSuite(leaves)
                elif alteration == "aliased":
                    alias = unittest.TestSuite([leaves[0]])
                    fixture.suite = unittest.TestSuite([alias, alias, *leaves[1:]])
                result = fixture.run()
                self.assertEqual("incomplete", result["state"])
                self.assertFalse(result["control_scopes"]["inventory_verified"])
                self.assertEqual(0, result["counts"]["tests_run"])
                self.assertEqual([], executed)
                self.assertEqual(len(leaves) + (alteration == "aliased"), result["method_selection_guard"]["discovered_method_count"])

    def test_linux_skip_is_not_native_execution_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            cls = fixture.classes["NativeLiveSupervisorTest"]
            cls.__unittest_skip__, cls.__unittest_skip_why__ = True, "PRIVATE unexpected skip"
            result = fixture.run()
            self.assertEqual("incomplete", result["state"])
            self.assertEqual("incomplete", result["control_scopes"]["native"]["state"])
            self.assertEqual(0, result["control_scopes"]["native"]["successful_methods"])


class LiveFailurePathTests(unittest.TestCase):
    def test_native_source_drift_retains_full_callbacks_but_invalidates_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            first = sorted(runner.CASES)[0]
            callback = lambda _case: write(fixture.root / runner.INPUTS["source"], b"changed synthetic C input\n")
            fixture = Fixture(fixture.root, callbacks={first: callback})
            result = fixture.run()
            self.assertEqual(("incomplete", 94, "passed"), (result["state"], result["counts"]["tests_run"], result["standard_unittest"]["state"]))
            self.assertNotEqual(result["inputs_before"], result["inputs_after"])
            self.assertFalse(result["results_complete"])
            self.assertEqual({"incomplete"}, {scope["state"] for name, scope in result["control_scopes"].items() if name != "inventory_verified"})

    def test_post_input_unavailability_still_retains_complete_original_callbacks(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            before = runner.input_snapshot(fixture.root)
            with fixture.context(), patch.object(runner, "input_snapshot", side_effect=[before, OSError("PRIVATE post-input failure")]):
                result = runner.run(fixture.config)
            self.assertEqual(("incomplete", 94, "passed"), (result["state"], result["counts"]["tests_run"], result["standard_unittest"]["state"]))
            self.assertIsNone(result["inputs_after"])
            self.assertFalse(result["results_complete"])
            self.assertEqual(("incomplete", "passed"), (result["control_scopes"]["native"]["state"], result["control_scopes"]["native"]["callback_state"]))
            self.assertNotIn("PRIVATE", fixture.config.output.read_text())

    def test_changed_driver_or_private_configuration_digest_cannot_pass(self):
        for alteration in ("driver", "configuration", "fixture_binding", "worker_exit", "checkpoint"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary))
                with fixture.context(), ExitStack() as stack:
                    if alteration == "driver":
                        stack.enter_context(patch.object(runner, "tool_snapshot", side_effect=[fixture.tools, {"changed": "sha256:" + "d" * 64}]))
                    elif alteration == "configuration":
                        stack.enter_context(patch.object(runner, "configuration_digest", side_effect=["sha256:" + "d" * 64, "sha256:" + "e" * 64]))
                    elif alteration == "fixture_binding":
                        stack.enter_context(patch.object(runner, "fixture_binding_verified", side_effect=[True, False]))
                    else:
                        worker = runner.recorder.worker_run
                        def wrong_finalization(config, final, progress):
                            worker(config, final, progress)
                            if alteration == "checkpoint":
                                final.unlink()
                                return 0
                            return 1
                        stack.enter_context(patch.object(runner.recorder, "worker_run", side_effect=wrong_finalization))
                    result = runner.run(fixture.config)
                self.assertEqual("incomplete", result["state"])
                self.assertEqual(94, result["counts"]["tests_run"])
                self.assertEqual("incomplete", result["control_scopes"]["python"]["state"])
                self.assertEqual("incomplete", result["control_scopes"]["native"]["state"])

    def test_missing_linux_compiler_or_observer_refuses_before_fixture_or_discovery(self):
        snapshot = runner.tool_snapshot
        for missing in ("compiler", "observer"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(missing=missing):
                fixture = Fixture(Path(temporary))
                if missing == "compiler":
                    fixture.config = replace(fixture.config, cc=None)
                def observe(path):
                    if path == Path("/usr/bin/strace"):
                        raise FileNotFoundError("PRIVATE missing syscall observer")
                    return {"state": "available"}
                with fixture.context() as discovery, patch.object(runner, "tool_snapshot", new=snapshot), \
                        patch.object(runner, "tool_identity", side_effect=observe), \
                        patch.object(runner, "load_fixture", side_effect=fixture.fail_if_called):
                    result = runner.run(fixture.config)
                discovery.assert_not_called()
                self.assertEqual("incomplete", result["state"])
                self.assertIsNone(result["counts"])
                self.assertEqual("incomplete", result["control_scopes"]["native"]["state"])
                self.assertNotIn("PRIVATE", fixture.config.output.read_text())

    def test_console_overflow_discards_text_without_stopping_original_test_callbacks(self):
        with tempfile.TemporaryDirectory() as temporary:
            first = sorted(runner.CASES)[0]
            fixture = Fixture(Path(temporary), callbacks={first: lambda _case: print("PRIVATE" + "x" * 200)})
            fixture.config = replace(fixture.config, max_output_bytes=32)
            result = fixture.run()
            self.assertEqual(("incomplete", 94, "passed"), (result["state"], result["counts"]["tests_run"], result["standard_unittest"]["state"]))
            self.assertTrue(result["raw_console"]["truncated"] and result["raw_console"]["capture_closed"])
            self.assertEqual(32, sum(stream["retained_bytes"] for stream in result["raw_console"]["streams"].values()))
            self.assertNotIn("PRIVATE", fixture.config.output.read_text())
            self.assertNotIn("PRIVATE", fixture.config.junit.read_text())

    def test_missing_ci_context_persists_incomplete_evidence_without_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            fixture.config = replace(fixture.config, ci_context=None)
            with fixture.context() as discovery:
                result = runner.run(fixture.config)
            discovery.assert_not_called()
            self.assertEqual("incomplete", result["state"])
            self.assertTrue(result["persistence_complete"])
            self.assertIsNone(result["counts"])
            self.assertEqual(result, json.loads(fixture.config.output.read_bytes()))

    def test_existing_report_is_preserved_without_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            write(fixture.config.output, b"earlier report bytes\n")
            with fixture.context() as discovery:
                result = runner.run(fixture.config)
            discovery.assert_not_called()
            self.assertEqual("incomplete", result["state"])
            self.assertEqual(b"earlier report bytes\n", fixture.config.output.read_bytes())
            self.assertFalse(result["persistence_complete"])

    def test_json_publication_failure_leaves_xml_nonstandalone_and_wrapper_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            publish = runner.recorder.publish_exclusive
            def fail_json(path, raw):
                if path == fixture.config.output:
                    raise OSError("PRIVATE JSON publication failure")
                publish(path, raw)
            with fixture.context(), patch.object(runner.recorder, "publish_exclusive", side_effect=fail_json):
                result = runner.run(fixture.config)
            self.assertEqual("incomplete", result["state"])
            self.assertFalse(result["persistence_complete"])
            self.assertFalse(fixture.config.output.exists())
            for scope in ("python", "native"):
                self.assertEqual(("incomplete", "passed"),
                                 (result["control_scopes"][scope]["state"], result["control_scopes"][scope]["callback_state"]))
            properties = {row.attrib["name"]: row.attrib["value"] for row in ET.parse(fixture.config.junit).findall("./properties/property")}
            self.assertEqual("true", properties["requires_matching_complete_json_and_successful_wrapper_exit"])


class LiveBindingTests(unittest.TestCase):
    def test_exact_hashed_source_import_and_explicit_compiler_binding_without_native_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            builder = f"PROTOCOL = {runner.PROTOCOL!r}\nSUPPORTED = {set(runner.TARGETS)!r}\ndef host_identity(): return {fixture.host!r}\n".encode()
            write(fixture.root / runner.INPUTS["builder"], builder)
            write(fixture.root / runner.INPUTS["tests"], b"import build_live_supervisor as builder\nCC = None\n")
            inputs = runner.input_snapshot(fixture.root)
            with patch.dict(sys.modules):
                sys.modules.pop("build_live_supervisor", None)
                sys.modules.pop("test_live_supervisor", None)
                loaded = runner.load_fixture(fixture.root, inputs, fixture.cc)
                self.assertTrue(runner.fixture_binding_verified(loaded, fixture.cc, fixture.host))
                self.assertEqual(fixture.cc, loaded.CC)
                self.assertEqual(fixture.root / runner.INPUTS["builder"], Path(loaded.builder.__file__))
                loaded.CC = Path("different-synthetic-compiler")
                self.assertFalse(runner.fixture_binding_verified(loaded, fixture.cc, fixture.host))

    def test_ambient_module_and_changed_source_are_rejected_before_fixture_execution(self):
        for alteration in ("ambient_builder", "ambient_tests", "changed_source"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(alteration=alteration):
                fixture = Fixture(Path(temporary))
                inputs = runner.input_snapshot(fixture.root)
                with patch.dict(sys.modules):
                    sys.modules.pop("build_live_supervisor", None)
                    sys.modules.pop("test_live_supervisor", None)
                    if alteration.startswith("ambient"):
                        name = "build_live_supervisor" if alteration == "ambient_builder" else "test_live_supervisor"
                        sys.modules[name] = SimpleNamespace(private="PRIVATE ambient module")
                    else:
                        write(fixture.root / runner.INPUTS["builder"], b"raise AssertionError('must not import changed source')\n")
                    with self.assertRaises(runner.recorder.GuardError):
                        runner.load_fixture(fixture.root, inputs, fixture.cc)

    def test_tool_identity_retains_bounded_digest_and_no_private_path_or_version_probe(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary))
            identity = runner.tool_identity(fixture.cc)
            self.assertEqual(runner.recorder.digest(fixture.cc.read_bytes()), identity["digest"])
            self.assertFalse(identity["version_probe_performed"])
            self.assertNotIn("PRIVATE", json.dumps(identity))
            with self.assertRaises(runner.recorder.GuardError):
                runner.tool_identity(Path("cc"))
            fixture.cc.write_bytes(b"")
            with self.assertRaises(runner.recorder.GuardError):
                runner.tool_identity(fixture.cc)

    def test_unsupported_host_tool_snapshot_never_selects_a_compiler_or_syscall_observer(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Fixture(Path(temporary), "windows-x86_64")
            with patch.object(runner, "tool_identity", return_value={"state": "available"}) as observe:
                result = runner.tool_snapshot(fixture.config, fixture.host)
            observe.assert_called_once_with(Path(sys.executable))
            self.assertEqual({"state": "not_applicable"}, result["compiler"])
            self.assertEqual({"state": "not_applicable"}, result["syscall_observer"])

    def test_cli_exit_distinguishes_wrapper_evidence_from_native_support(self):
        for state, persistence, code in (("passed", True, 0), ("failed", True, 1), ("incomplete", True, 2), ("passed", False, 2)):
            value = {"state": state, "persistence_complete": persistence, "control_scopes": {"native": {"state": "unavailable"}}}
            with patch.object(runner, "run", return_value=value), patch.object(sys, "stdout", io.StringIO()):
                self.assertEqual(code, runner.main(["--output", "build/ci-evidence/live.json", "--junit", "build/ci-evidence/live.xml"]))


if __name__ == "__main__":
    unittest.main()
