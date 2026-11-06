"""Synthetic, local unittest-reporter controls; no hosted, JVM or provider run."""

from contextlib import redirect_stdout
from dataclasses import replace
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import textwrap
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

try:
    from . import run_python_checks as runner
except ImportError:
    import run_python_checks as runner


COMMIT = "1" * 40
CANARY = "SYNTHETIC_DO_NOT_UPLOAD_THIS_VALUE"
CONTEXT = {"run_id": 123, "run_attempt": 2, "job": "synthetic-job", "host": "synthetic-host", "runner_os": "Linux"}
PASS_SOURCE = """import unittest
class Cases(unittest.TestCase):
    def test_ok(self):
        self.assertEqual(2, 1 + 1)
"""
REPORT_KEYS = {
    "schema_version", "evidence_kind", "state", "started_at", "finished_at", "elapsed_seconds", "source", "source_after",
    "worker_source_before", "worker_source_after", "ci_context", "selection", "discovery", "counts", "cases", "framework_events",
    "diagnostics", "results_complete", "counts_scope", "test_denominators", "raw_console", "worker_return_code", "junit_report",
    "persistence_complete", "unsafe_text_output_locals_and_environment_omitted", "qualification", "limitations", "bounds", "cleanup",
}
CASE_KEYS = {"ordinal", "discovery_ordinal", "id", "complete", "stop_callback_observed", "elapsed_seconds", "events"}
OCCURRENCE_KEYS = {"ordinal", "id", "class_id", "module_id", "status", "case_ordinal", "blocking_fixture_event_ordinal"}


class PythonReporterControls(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="synthetic-python-reporter-")
        self.base = Path(self.temporary.name).resolve()
        self.counter = 0
        self.config = self.fixture()

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, **changes):
        self.counter += 1
        root = self.base / ("repo_" + str(self.counter))
        (root / ".git").mkdir(parents=True, mode=0o700)
        (root / ".git" / "HEAD").write_text(COMMIT + "\n", encoding="ascii")
        start = root / "checks"
        start.mkdir()
        evidence = root / "build" / "ci-evidence"
        return replace(runner.Config(root, start, evidence / "python-checks.json", evidence / "python-checks.xml",
            timeout=3.0, max_output_bytes=8192, expected_commit=COMMIT, ci_context=CONTEXT.copy(), require_ci_context=True), **changes)

    def source(self, config, text, name="test_fake.py"):
        path = config.start / name
        path.write_text(textwrap.dedent(text).lstrip(), encoding="utf-8")
        return path

    def execute(self, text=PASS_SOURCE, config=None):
        config = config or self.config
        self.source(config, text)
        report = runner.run_checks(config)
        if config.output.exists():
            self.assertEqual(report, json.loads(config.output.read_bytes()))
        self.assert_closed(report)
        self.assert_private_text_absent(report, config)
        return report

    def assert_closed(self, report):
        self.assertEqual(REPORT_KEYS, set(report))
        self.assertEqual(1, report["schema_version"])
        self.assertIs(True, report["unsafe_text_output_locals_and_environment_omitted"])
        self.assertEqual(7, len(report["qualification"]))
        self.assertTrue(all(value is False for value in report["qualification"].values()))
        self.assertIs(False, report["cleanup"]["descendant_absence_verified"])
        for case in report["cases"]:
            self.assertEqual(CASE_KEYS, set(case))
            self.assertEqual(runner.identifier(case["id"]), case["id"])
            for event in case["events"]:
                self.assertEqual({"kind", "subtest", "exception_type", "frames"}, set(event))
                self.assert_frames(event["frames"])
        for event in report["framework_events"]:
            self.assertEqual({"ordinal", "id", "fixture", "kind", "blocked_discovery_ordinals", "exception_type", "frames"}, set(event))
            self.assert_frames(event["frames"])
        if report["discovery"] is not None:
            discovery = report["discovery"]
            self.assertEqual({"completed", "tests_selected", "loader_errors", "import_errors", "import_failure_details",
                              "execution_shape_supported", "test_inventory", "original_suite_shape_unchanged"}, set(discovery))
            self.assertEqual(discovery["tests_selected"], len(discovery["test_inventory"]))
            for ordinal, row in enumerate(discovery["test_inventory"], 1):
                self.assertEqual(OCCURRENCE_KEYS, set(row))
                self.assertEqual(ordinal, row["ordinal"])
                if row["status"] == "started":
                    case = report["cases"][row["case_ordinal"] - 1]
                    self.assertEqual(ordinal, case["discovery_ordinal"])
                    self.assertEqual(row["id"], case["id"])
                    self.assertIsNone(row["blocking_fixture_event_ordinal"])
                elif row["status"] == "setup_blocked":
                    self.assertIsNone(row["case_ordinal"])
                    event = report["framework_events"][row["blocking_fixture_event_ordinal"] - 1]
                    self.assertIn(event["fixture"], {"setUpClass", "setUpModule"})
                    self.assertIn(event["kind"], {"error", "skipped"})
                    self.assertIn(ordinal, event["blocked_discovery_ordinals"])
                    self.assertEqual(row["class_id"] if event["fixture"] == "setUpClass" else row["module_id"], event["id"])
                else:
                    self.assertEqual("pending", row["status"])
                    self.assertIsNone(row["case_ordinal"])
                    self.assertIsNone(row["blocking_fixture_event_ordinal"])
        for row in report["diagnostics"]:
            self.assertEqual({"code", "exception_type", "frames"}, set(row))
            self.assert_frames(row["frames"])
        if report["counts"] is not None:
            self.assertEqual(set(runner.COUNTS), set(report["counts"]))
            self.assertEqual(len(report["cases"]), report["counts"]["tests_run"])
            denominators = report["test_denominators"]
            self.assertEqual(report["counts"]["tests_run"], denominators["started_test_cases"])
            self.assertEqual(sum(case["stop_callback_observed"] for case in report["cases"]), denominators["stop_callbacks_observed"])
            self.assertEqual(sum(case["complete"] for case in report["cases"]), denominators["completed_test_cases"])
            self.assertEqual(sum(row["status"] == "setup_blocked" for row in report["discovery"]["test_inventory"]), denominators["setup_blocked_test_cases"])
            self.assertEqual(sum(case["discovery_ordinal"] is None for case in report["cases"]), denominators["unbound_started_test_cases"])
        if report["raw_console"] is not None:
            self.assertIs(False, report["raw_console"]["raw_console_uploaded"])
            self.assertLessEqual(report["raw_console"]["retained_total_bytes"], report["bounds"]["max_console_bytes"])
        if report["state"] in {"passed", "failed"}:
            self.assertIs(True, report["results_complete"])
            self.assertIs(True, report["persistence_complete"])
            self.assertEqual(report["source"], report["source_after"])
            self.assertEqual(report["source"], report["worker_source_before"])
            self.assertEqual(report["source"], report["worker_source_after"])
            self.assertGreater(report["counts"]["tests_run"], 0)
            self.assertIs(True, report["discovery"]["execution_shape_supported"])
            self.assertIs(True, report["discovery"]["original_suite_shape_unchanged"])
            self.assertTrue(all(row["status"] != "pending" for row in report["discovery"]["test_inventory"]))
            self.assertTrue(all(case["discovery_ordinal"] is not None for case in report["cases"]))

    def assert_frames(self, frames):
        for frame in frames:
            self.assertEqual({"path", "line", "function"}, set(frame))
            self.assertTrue(runner.portable_relative(frame["path"]))
            self.assertGreater(frame["line"], 0)
            self.assertNotIn(str(self.base), frame["path"])

    def assert_private_text_absent(self, report, config):
        raw = json.dumps(report)
        self.assertNotIn(CANARY, raw)
        self.assertNotIn(str(config.root), raw)
        if config.junit.exists():
            xml = config.junit.read_bytes()
            self.assertNotIn(CANARY.encode(), xml)
            self.assertNotIn(str(config.root).encode(), xml)
            ET.fromstring(xml)

    def codes(self, report):
        return {row["code"] for row in report["diagnostics"]}

    def xml_properties(self, config=None):
        tree = ET.fromstring((config or self.config).junit.read_bytes())
        return {node.attrib["name"]: node.attrib["value"] for node in tree.findall("properties/property")}

    def test_all_unittest_outcomes_and_subtests_are_actual_callbacks_not_console_text(self):
        # Eight cases and every outcome fsync a real checkpoint. This checks
        # callback accounting, not three-second process/filesystem throughput.
        # Genuine deadline controls below retain their explicit short budgets.
        report = self.execute(f"""
            import os, sys, unittest
            class PrivateLabel:
                def __repr__(self):
                    raise RuntimeError({CANARY!r})
            class Cases(unittest.TestCase):
                def test_a_success(self):
                    print("Ran 999 tests; FAILED (failures=888, errors=777)")
                    print({CANARY!r}, file=sys.stderr)
                def test_b_failure(self):
                    private_local = {CANARY!r}
                    self.fail(private_local)
                def test_c_error(self):
                    raise ValueError({CANARY!r})
                @unittest.skip({CANARY!r})
                def test_d_skip(self):
                    self.fail("must not execute")
                @unittest.expectedFailure
                def test_e_expected_failure(self):
                    self.fail({CANARY!r})
                @unittest.expectedFailure
                def test_f_unexpected_success(self):
                    pass
                def test_g_subtests(self):
                    with self.subTest(private=PrivateLabel()):
                        self.fail({CANARY!r})
                    with self.subTest(private={CANARY!r}):
                        raise RuntimeError({CANARY!r})
                    with self.subTest(private={CANARY!r}):
                        raise unittest.SkipTest({CANARY!r})
                def test_h_runtime_skip(self):
                    raise unittest.SkipTest({CANARY!r})
        """, config=replace(self.config, timeout=10.0))
        self.assertEqual("failed", report["state"])
        self.assertEqual({"tests_run": 8, "successful_tests": 1, "failures": 2, "errors": 2, "skipped": 3,
            "expected_failures": 1, "unexpected_successes": 1}, report["counts"])
        self.assertEqual(8, report["discovery"]["tests_selected"])
        subtests = report["cases"][6]["events"]
        self.assertEqual(["failure", "error", "skipped"], [event["kind"] for event in subtests])
        self.assertTrue(all(event["subtest"] for event in subtests))
        self.assertEqual("builtins.ValueError", report["cases"][2]["events"][0]["exception_type"])
        self.assertEqual("checks/test_fake.py", report["cases"][2]["events"][0]["frames"][-1]["path"])
        xml = ET.fromstring(self.config.junit.read_bytes())
        self.assertEqual("8", xml.attrib["tests"])
        self.assertEqual(9, sum(len(row) for row in xml.findall("testcase")))
        properties = self.xml_properties()
        self.assertEqual("3", properties["unittest_skipped"])
        self.assertEqual("1", properties["unittest_expected_failures"])
        self.assertEqual("1", properties["unittest_unexpected_successes"])
        self.assertEqual(1, report["worker_return_code"])

    def test_console_cannot_create_a_failure_or_test_count(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    print("Ran 999 tests; FAILED (failures=888, errors=777)")
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(0, report["counts"]["failures"])
        self.assertEqual(0, report["counts"]["errors"])

    def test_custom_test_id_is_never_called_or_uploaded(self):
        report = self.execute(f"""
            import unittest
            class Cases(unittest.TestCase):
                def id(self):
                    raise RuntimeError({CANARY!r})
                def test_ok(self):
                    pass
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual("test_fake.Cases.test_ok", report["cases"][0]["id"])

    def test_worker_preserves_parent_optimization_mode(self):
        report = self.execute(f"""
            import sys, unittest
            class Cases(unittest.TestCase):
                def test_optimization(self):
                    self.assertEqual({runner.sys.flags.optimize}, sys.flags.optimize)
        """)
        self.assertEqual("passed", report["state"])

    def test_expected_failures_and_real_skips_are_retained_without_becoming_failures(self):
        report = self.execute(f"""
            import unittest
            class Cases(unittest.TestCase):
                @unittest.expectedFailure
                def test_expected(self):
                    self.fail({CANARY!r})
                @unittest.skip({CANARY!r})
                def test_skip(self):
                    pass
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual(2, report["counts"]["tests_run"])
        self.assertEqual(0, report["counts"]["successful_tests"])
        self.assertEqual(1, report["counts"]["expected_failures"])
        self.assertEqual(1, report["counts"]["skipped"])

    def test_zero_discovered_tests_is_not_success(self):
        report = self.execute("value = 1\n")
        self.assertEqual("incomplete", report["state"])
        self.assertIn("zero_tests_discovered", self.codes(report))
        self.assertEqual(0, report["counts"]["tests_run"])
        self.assertEqual(0, report["test_denominators"]["discovered_test_cases"])
        self.assertIs(False, report["results_complete"])

    def test_original_syntax_import_location_is_retained_without_loader_error_text(self):
        self.source(self.config, "def broken(\n", "test_syntax.py")
        report = self.execute()
        self.assertEqual("failed", report["state"])
        self.assertEqual(1, report["discovery"]["import_errors"])
        detail = report["discovery"]["import_failure_details"][0]
        self.assertEqual("builtins.SyntaxError", detail["exception_type"])
        self.assertIn({"path": "checks/test_syntax.py", "line": 1, "function": "<module>"}, detail["frames"])
        self.assertEqual(1, report["counts"]["errors"])
        self.assertEqual("builtins.SyntaxError", self.xml_properties()["import_failure_1_exception_type"])

    def test_multiple_original_import_errors_and_successful_modules_are_all_preserved(self):
        self.source(self.config, f"raise LookupError({CANARY!r})\n", "test_a_import.py")
        self.source(self.config, f"raise ValueError({CANARY!r})\n", "test_b_import.py")
        report = self.execute()
        self.assertEqual("failed", report["state"])
        self.assertEqual(3, report["counts"]["tests_run"])
        self.assertEqual(2, report["counts"]["errors"])
        self.assertEqual(2, report["discovery"]["loader_errors"])
        self.assertEqual(["builtins.LookupError", "builtins.ValueError"],
            [row["exception_type"] for row in report["discovery"]["import_failure_details"]])
        self.assertEqual(["checks/test_a_import.py", "checks/test_b_import.py"],
            [row["frames"][-1]["path"] for row in report["discovery"]["import_failure_details"]])

    def test_load_tests_discovery_failure_retains_actual_exception_and_location(self):
        report = self.execute(f"""
            def load_tests(loader, tests, pattern):
                raise ValueError({CANARY!r})
        """)
        self.assertEqual("failed", report["state"])
        self.assertEqual(1, report["discovery"]["loader_errors"])
        self.assertEqual(0, report["discovery"]["import_errors"])
        event = report["cases"][0]["events"][0]
        self.assertEqual("builtins.ValueError", event["exception_type"])
        self.assertIn({"path": "checks/test_fake.py", "line": 2, "function": "load_tests"}, event["frames"])

    def test_fixture_errors_and_skips_do_not_fabricate_started_tests(self):
        report = self.execute(f"""
            import unittest
            class ASetupFailure(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise RuntimeError({CANARY!r})
                def test_a(self):
                    pass
                def test_b(self):
                    pass
            class BSetupSkip(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest({CANARY!r})
                def test_one(self):
                    pass
            class CGoodWithTeardownFailure(unittest.TestCase):
                def test_ok(self):
                    pass
                @classmethod
                def tearDownClass(cls):
                    raise ValueError({CANARY!r})
        """)
        self.assertEqual("failed", report["state"])
        self.assertEqual(4, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(2, report["counts"]["errors"])
        self.assertEqual(1, report["counts"]["skipped"])
        self.assertEqual(["setUpClass", "setUpClass", "tearDownClass"], [event["fixture"] for event in report["framework_events"]])
        self.assertEqual(["error", "skipped", "error"], [event["kind"] for event in report["framework_events"]])
        self.assertEqual(1, len(report["cases"]))
        self.assertEqual(["setup_blocked", "setup_blocked", "setup_blocked", "started"],
                         [row["status"] for row in report["discovery"]["test_inventory"]])
        self.assertEqual([[1, 2], [3], []], [event["blocked_discovery_ordinals"] for event in report["framework_events"]])
        self.assertEqual(4, report["cases"][0]["discovery_ordinal"])
        self.assertEqual(3, report["test_denominators"]["setup_blocked_test_cases"])
        self.assertEqual(0, report["test_denominators"]["pending_selected_test_cases"])
        xml = ET.fromstring(self.config.junit.read_bytes())
        self.assertEqual("1", xml.attrib["tests"])
        self.assertEqual("2", xml.attrib["errors"])
        self.assertEqual("true", self.xml_properties()["framework_events_are_not_started_tests"])
        self.assertEqual("builtins.RuntimeError", self.xml_properties()["framework_event_1_exception_type"])
        self.assertEqual("test_fake.ASetupFailure.test_b", self.xml_properties()["discovered_case_2_id"])
        self.assertEqual("setup_blocked", self.xml_properties()["discovered_case_2_status"])
        self.assertEqual("1,2", self.xml_properties()["framework_event_1_blocked_discovery_ordinals"])

    def test_module_setup_errors_or_skips_with_zero_executed_tests_never_pass(self):
        for exception in ("RuntimeError", "unittest.SkipTest"):
            with self.subTest(exception=exception):
                config = self.fixture()
                report = self.execute(f"""
                    import unittest
                    def setUpModule():
                        raise {exception}({CANARY!r})
                    class Cases(unittest.TestCase):
                        def test_ok(self):
                            pass
                """, config)
                self.assertEqual("incomplete", report["state"])
                self.assertEqual(1, report["test_denominators"]["discovered_test_cases"])
                self.assertEqual(0, report["test_denominators"]["started_test_cases"])
                self.assertEqual(0, report["test_denominators"]["completed_test_cases"])
                self.assertIn("zero_tests_executed", self.codes(report))
                self.assertEqual("setUpModule", report["framework_events"][0]["fixture"])
                self.assertEqual([], report["cases"])

    def test_module_teardown_failure_is_retained_after_successful_test(self):
        report = self.execute(f"""
            import unittest
            def tearDownModule():
                raise RuntimeError({CANARY!r})
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
        """)
        self.assertEqual("failed", report["state"])
        self.assertEqual(1, report["counts"]["successful_tests"])
        self.assertEqual(1, report["counts"]["errors"])
        self.assertEqual("tearDownModule", report["framework_events"][0]["fixture"])
        self.assertEqual([], report["framework_events"][0]["blocked_discovery_ordinals"])

    def test_module_setup_skip_accounts_only_visited_module_occurrences(self):
        self.source(self.config, f"""
            import unittest
            def setUpModule():
                raise unittest.SkipTest({CANARY!r})
            class ABlocked(unittest.TestCase):
                def test_one(self):
                    self.fail("must not execute")
            class BBlocked(unittest.TestCase):
                def test_two(self):
                    self.fail("must not execute")
        """, "test_a_blocked.py")
        report = self.execute()
        self.assertEqual("passed", report["state"])
        self.assertEqual(3, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["skipped"])
        self.assertEqual(["setup_blocked", "setup_blocked", "started"],
                         [row["status"] for row in report["discovery"]["test_inventory"]])
        self.assertEqual([1, 2], report["framework_events"][0]["blocked_discovery_ordinals"])
        self.assertEqual("test_a_blocked.BBlocked.test_two", self.xml_properties()["discovered_case_2_id"])
        self.assertEqual(1, len(ET.fromstring(self.config.junit.read_bytes()).findall("testcase")))

    def test_repeated_test_object_has_distinct_discovery_occurrences_and_starts(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
            def load_tests(loader, tests, pattern):
                case = Cases("test_ok")
                return unittest.TestSuite([case, case])
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual(2, report["counts"]["tests_run"])
        self.assertEqual([1, 2], [case["discovery_ordinal"] for case in report["cases"]])
        self.assertEqual([1, 2], [row["case_ordinal"] for row in report["discovery"]["test_inventory"]])
        self.assertEqual(1, len({row["id"] for row in report["discovery"]["test_inventory"]}))
        self.assertEqual("test_fake.Cases.test_ok", self.xml_properties()["discovered_case_2_id"])

    def test_repeated_setup_blocked_object_is_counted_only_as_two_visited_selections(self):
        report = self.execute(f"""
            import unittest
            class ABlocked(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest({CANARY!r})
                def test_no_run(self):
                    self.fail("must not execute")
            class BGood(unittest.TestCase):
                def test_ok(self):
                    pass
            def load_tests(loader, tests, pattern):
                case = ABlocked("test_no_run")
                return unittest.TestSuite([case, case, BGood("test_ok")])
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual(3, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["skipped"])
        self.assertEqual([1, 2], report["framework_events"][0]["blocked_discovery_ordinals"])
        self.assertEqual(3, report["cases"][0]["discovery_ordinal"])

    def test_extra_start_cannot_consume_a_later_occurrence_of_the_same_object(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                extra_run = True
                def run(self, result):
                    unittest.TestCase.run(self, result)
                    if self.extra_run:
                        self.extra_run = False
                        unittest.TestCase.run(self, result)
                    return result
                def test_ok(self):
                    pass
            def load_tests(loader, tests, pattern):
                case = Cases("test_ok")
                return unittest.TestSuite([case, case])
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(3, report["counts"]["tests_run"])
        self.assertEqual([1, None, 2], [case["discovery_ordinal"] for case in report["cases"]])
        self.assertEqual([1, 3], [row["case_ordinal"] for row in report["discovery"]["test_inventory"]])
        self.assertIn("unsupported_selection_callback", self.codes(report))
        self.assertEqual(1, report["test_denominators"]["unbound_started_test_cases"])

    def test_custom_error_holder_label_is_omitted_and_cannot_qualify(self):
        report = self.execute(f"""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
            class CustomSuite(unittest.TestSuite):
                def run(self, result):
                    holder = unittest.suite._ErrorHolder("setUpModule ({CANARY})")
                    result.addSkip(holder, {CANARY!r})
                    return super().run(result)
            def load_tests(loader, tests, pattern):
                return CustomSuite(tests)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("unsupported_framework_callback", self.codes(report))
        self.assertEqual("unidentified", report["framework_events"][0]["id"])
        self.assertEqual(1, report["counts"]["skipped"])
        self.assertEqual(1, report["counts"]["tests_run"])

    def test_timeout_preserves_prior_failure_and_started_incomplete_later_case(self):
        config = replace(self.config, timeout=0.7)
        report = self.execute(f"""
            import time, unittest
            class Cases(unittest.TestCase):
                def test_a_failure(self):
                    self.fail({CANARY!r})
                def test_b_timeout(self):
                    time.sleep(60)
        """, config)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("worker_wall_timeout", self.codes(report))
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual(2, report["counts"]["tests_run"])
        self.assertEqual(1, report["test_denominators"]["stop_callbacks_observed"])
        self.assertEqual(1, report["test_denominators"]["completed_test_cases"])
        self.assertIs(False, report["cases"][1]["complete"])
        self.assertIs(False, report["cases"][1]["stop_callback_observed"])
        self.assertIn("prefix", report["counts_scope"])
        self.assertLess(report["elapsed_seconds"], config.timeout + runner.CLEANUP_WAIT_SECONDS + runner.PIPE_DRAIN_SECONDS + 1)

    def test_timeout_preserves_active_failure_before_hanging_teardown(self):
        config = replace(self.config, timeout=0.7)
        report = self.execute(f"""
            import time, unittest
            class Cases(unittest.TestCase):
                def test_failure(self):
                    self.fail({CANARY!r})
                def tearDown(self):
                    time.sleep(60)
        """, config)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("worker_wall_timeout", self.codes(report))
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual(0, report["test_denominators"]["stop_callbacks_observed"])
        self.assertEqual(0, report["test_denominators"]["completed_test_cases"])
        self.assertEqual("started", report["discovery"]["test_inventory"][0]["status"])
        self.assertIs(False, report["cases"][0]["complete"])
        self.assertIs(False, report["cases"][0]["stop_callback_observed"])
        event = report["cases"][0]["events"][0]
        self.assertEqual("failure", event["kind"])
        self.assertEqual("builtins.AssertionError", event["exception_type"])
        self.assertEqual("test_failure", event["frames"][-1]["function"])
        xml = ET.fromstring(config.junit.read_bytes())
        self.assertEqual("false", xml.find("testcase").attrib["complete"])
        self.assertEqual(1, len(xml.findall("testcase/failure")))
        self.assertLess(report["elapsed_seconds"], config.timeout + runner.CLEANUP_WAIT_SECONDS + runner.PIPE_DRAIN_SECONDS + 1)

    def test_timeout_preserves_active_failed_subtest_before_body_hang(self):
        config = replace(self.config, timeout=0.7)
        report = self.execute(f"""
            import time, unittest
            class Cases(unittest.TestCase):
                def test_subtest(self):
                    with self.subTest(private={CANARY!r}):
                        self.fail({CANARY!r})
                    time.sleep(60)
        """, config)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("worker_wall_timeout", self.codes(report))
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual(0, report["test_denominators"]["stop_callbacks_observed"])
        self.assertEqual(0, report["test_denominators"]["completed_test_cases"])
        self.assertIs(False, report["cases"][0]["complete"])
        self.assertIs(True, report["cases"][0]["events"][0]["subtest"])
        node = ET.fromstring(config.junit.read_bytes()).find("testcase/failure")
        self.assertEqual("true", node.attrib["subtest"])
        self.assertLess(report["elapsed_seconds"], config.timeout + runner.CLEANUP_WAIT_SECONDS + runner.PIPE_DRAIN_SECONDS + 1)

    def test_discovery_timeout_retains_original_earlier_import_failure(self):
        config = replace(self.config, timeout=0.7)
        self.source(config, f"raise ValueError({CANARY!r})\n", "test_a_import.py")
        self.source(config, "import time\ntime.sleep(60)\n", "test_b_timeout.py")
        report = runner.run_checks(config)
        self.assert_closed(report)
        self.assert_private_text_absent(report, config)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("worker_wall_timeout", self.codes(report))
        self.assertIs(False, report["discovery"]["completed"])
        self.assertIsNone(report["test_denominators"]["discovered_test_cases"])
        self.assertEqual(0, report["counts"]["tests_run"])
        self.assertEqual("builtins.ValueError", report["discovery"]["import_failure_details"][0]["exception_type"])
        self.assertEqual("checks/test_a_import.py", report["discovery"]["import_failure_details"][0]["frames"][-1]["path"])

    def test_worker_crash_retains_callback_prefix_and_is_not_a_test_failure_exit(self):
        report = self.execute(f"""
            import os, unittest
            class Cases(unittest.TestCase):
                def test_a_failure(self):
                    self.fail({CANARY!r})
                def test_b_crash(self):
                    os._exit(7)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(7, report["worker_return_code"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertIn("worker_finalization_incomplete", self.codes(report))
        self.assertIs(False, report["cases"][-1]["complete"])

    def test_worker_keyboard_interrupt_preserves_failure_and_stop_observation(self):
        report = self.execute(f"""
            import unittest
            class Cases(unittest.TestCase):
                def test_a_failure(self):
                    self.fail({CANARY!r})
                def test_b_interrupt(self):
                    raise KeyboardInterrupt({CANARY!r})
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual(2, report["test_denominators"]["stop_callbacks_observed"])
        self.assertEqual(1, report["test_denominators"]["completed_test_cases"])
        row = next(row for row in report["diagnostics"] if row["code"] == "worker_or_discovery_exception")
        self.assertEqual("builtins.KeyboardInterrupt", row["exception_type"])

    def test_parent_interrupt_still_retains_validated_prior_failure_checkpoint(self):
        self.source(self.config, f"""
            import time, unittest
            class Cases(unittest.TestCase):
                def test_a_failure(self):
                    self.fail({CANARY!r})
                def test_b_timeout(self):
                    time.sleep(60)
        """)
        real_sleep, interrupted = time.sleep, False

        def interrupt_after_failure(delay):
            nonlocal interrupted
            real_sleep(delay)
            if interrupted:
                return
            for path in self.config.output.parent.glob("raw/python-checks-*/worker-progress.json"):
                if json.loads(path.read_bytes())["counts"]["failures"]:
                    interrupted = True
                    raise KeyboardInterrupt(CANARY)

        with patch.object(runner.time, "sleep", side_effect=interrupt_after_failure):
            report = runner.run_checks(self.config)
        self.assertTrue(interrupted)
        self.assert_closed(report)
        self.assert_private_text_absent(report, self.config)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertIn("wrapper_exception", self.codes(report))

    def test_standard_result_stop_does_not_claim_full_selection_success(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_a_stop(self):
                    self._outcome.result.stop()
                def test_b_not_run(self):
                    self.fail("must not execute")
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("worker_stopped_early", self.codes(report))
        self.assertEqual(2, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])

    def test_suite_that_returns_without_executing_selection_cannot_pass(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
            class EmptyExecution(unittest.TestSuite):
                def run(self, result):
                    return result
            def load_tests(loader, tests, pattern):
                return EmptyExecution(tests)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["discovery"]["tests_selected"])
        self.assertEqual(0, report["counts"]["tests_run"])
        self.assertIn("selected_cases_not_accounted_for", self.codes(report))

    def test_legitimate_fixture_skip_cannot_cover_an_omitted_failing_class(self):
        report = self.execute(f"""
            import unittest
            class ABlocked(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest({CANARY!r})
                def test_one(self):
                    self.fail("must not execute")
            class BGood(unittest.TestCase):
                def test_ok(self):
                    pass
            class COmittedFailure(unittest.TestCase):
                def test_bad(self):
                    self.fail({CANARY!r})
            class PartialExecution(unittest.TestSuite):
                def run(self, result):
                    return unittest.TestSuite(list(self)[:2]).run(result)
            def load_tests(loader, tests, pattern):
                return PartialExecution(tests)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(3, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["successful_tests"])
        self.assertEqual(1, report["counts"]["skipped"])
        self.assertEqual(0, report["counts"]["failures"])
        self.assertIn("unsupported_discovery_suite", self.codes(report))
        self.assertIn("selected_cases_not_accounted_for", self.codes(report))
        self.assertNotIn("worker_stopped_early", self.codes(report))
        self.assertIs(False, report["discovery"]["execution_shape_supported"])
        self.assertEqual(["pending"] * 3, [row["status"] for row in report["discovery"]["test_inventory"]])
        self.assertEqual("test_fake.COmittedFailure.test_bad", report["discovery"]["test_inventory"][2]["id"])
        self.assertEqual([], report["framework_events"][0]["blocked_discovery_ordinals"])
        self.assertEqual("pending", self.xml_properties()["discovered_case_3_status"])
        self.assertEqual("test_fake.COmittedFailure.test_bad", self.xml_properties()["discovered_case_3_id"])

    def test_teardown_event_cannot_cover_an_omitted_selected_case(self):
        report = self.execute(f"""
            import unittest
            class AGoodWithTeardownFailure(unittest.TestCase):
                def test_ok(self):
                    pass
                @classmethod
                def tearDownClass(cls):
                    raise ValueError({CANARY!r})
            class BOmittedFailure(unittest.TestCase):
                def test_bad(self):
                    self.fail({CANARY!r})
            class PartialExecution(unittest.TestSuite):
                def run(self, result):
                    return unittest.TestSuite(list(self)[:1]).run(result)
            def load_tests(loader, tests, pattern):
                return PartialExecution(tests)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(2, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["errors"])
        self.assertEqual("tearDownClass", report["framework_events"][0]["fixture"])
        self.assertEqual([], report["framework_events"][0]["blocked_discovery_ordinals"])
        self.assertEqual("pending", report["discovery"]["test_inventory"][1]["status"])
        self.assertIn("selected_cases_not_accounted_for", self.codes(report))

    def test_custom_suite_remains_nonqualifying_even_when_all_counts_match(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
            class CustomExecution(unittest.TestSuite):
                def run(self, result):
                    return super().run(result)
            def load_tests(loader, tests, pattern):
                return CustomExecution(tests)
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(report["discovery"]["tests_selected"], report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["successful_tests"])
        self.assertIs(False, report["discovery"]["execution_shape_supported"])
        self.assertIsNone(report["cases"][0]["discovery_ordinal"])
        self.assertEqual(1, report["test_denominators"]["unbound_started_test_cases"])
        self.assertIn("unsupported_discovery_suite", self.codes(report))

    def test_exact_suite_with_instance_run_is_not_silently_rewritten(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    pass
            def load_tests(loader, tests, pattern):
                suite = unittest.TestSuite(tests)
                suite.run = lambda result: result
                return suite
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["discovery"]["tests_selected"])
        self.assertEqual(0, report["counts"]["tests_run"])
        self.assertIs(False, report["discovery"]["execution_shape_supported"])
        self.assertIn("unsupported_discovery_suite", self.codes(report))

    def test_aliased_suite_objects_execute_original_semantics_but_never_qualify(self):
        for cleanup in (True, False):
            with self.subTest(cleanup=cleanup):
                config = self.fixture()
                report = self.execute(f"""
                    import unittest
                    class Cases(unittest.TestCase):
                        def test_ok(self):
                            pass
                    def load_tests(loader, tests, pattern):
                        child = unittest.TestSuite([Cases("test_ok")])
                        child._cleanup = {cleanup!r}
                        return unittest.TestSuite([child, child])
                """, config)
                self.assertEqual("incomplete", report["state"])
                self.assertIs(False, report["discovery"]["execution_shape_supported"])
                self.assertIsNone(report["discovery"]["original_suite_shape_unchanged"])
                self.assertEqual(2, report["discovery"]["tests_selected"])
                self.assertEqual(1 if cleanup else 2, report["counts"]["tests_run"])
                self.assertEqual(report["counts"]["tests_run"], report["counts"]["successful_tests"])
                self.assertEqual(["pending", "pending"], [row["status"] for row in report["discovery"]["test_inventory"]])
                self.assertIn("unsupported_discovery_suite", self.codes(report))
                if cleanup:
                    self.assertIn("worker_or_discovery_exception", self.codes(report))

    def test_original_suite_added_failing_case_cannot_be_silently_qualified(self):
        report = self.execute(f"""
            import unittest
            original = None
            class Cases(unittest.TestCase):
                def test_a_change(self):
                    original.addTest(Cases("test_b_unselected_failure"))
                def test_b_unselected_failure(self):
                    self.fail({CANARY!r})
            def load_tests(loader, tests, pattern):
                global original
                original = unittest.TestSuite([Cases("test_a_change")])
                return original
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])
        self.assertEqual(1, report["counts"]["successful_tests"])
        self.assertEqual(0, report["counts"]["failures"])
        self.assertIs(False, report["discovery"]["original_suite_shape_unchanged"])
        self.assertIn("selected_suite_changed", self.codes(report))
        self.assertEqual("false", self.xml_properties()["discovery_original_suite_shape_unchanged"])
        self.assertIs(False, report["results_complete"])

    def test_original_suite_change_retains_already_observed_failure_and_stop(self):
        report = self.execute(f"""
            import unittest
            original = None
            class Cases(unittest.TestCase):
                def test_a_change_and_fail(self):
                    original.addTest(Cases("test_b_unselected_failure"))
                    self.fail({CANARY!r})
                def test_b_unselected_failure(self):
                    self.fail({CANARY!r})
            def load_tests(loader, tests, pattern):
                global original
                original = unittest.TestSuite([Cases("test_a_change_and_fail")])
                return original
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual(1, report["test_denominators"]["stop_callbacks_observed"])
        self.assertEqual(1, report["test_denominators"]["completed_test_cases"])
        self.assertIs(True, report["cases"][0]["complete"])
        self.assertIs(False, report["discovery"]["original_suite_shape_unchanged"])
        self.assertIn("selected_suite_changed", self.codes(report))

    def test_original_suite_child_removal_reorder_or_container_replacement_never_qualifies(self):
        for change in ("original._tests.pop()", "original._tests.reverse()", "original._tests = list(original._tests)"):
            with self.subTest(change=change):
                config = self.fixture()
                report = self.execute(f"""
                    import unittest
                    original = None
                    class Cases(unittest.TestCase):
                        def test_a_change(self):
                            {change}
                        def test_b_ok(self):
                            pass
                    def load_tests(loader, tests, pattern):
                        global original
                        original = unittest.TestSuite([Cases("test_a_change"), Cases("test_b_ok")])
                        return original
                """, config)
                self.assertEqual("incomplete", report["state"])
                self.assertEqual(2, report["counts"]["tests_run"])
                self.assertEqual(2, report["counts"]["successful_tests"])
                self.assertIs(False, report["discovery"]["original_suite_shape_unchanged"])
                self.assertIn("selected_suite_changed", self.codes(report))

    def test_original_selection_change_is_sticky_after_later_restoration(self):
        report = self.execute("""
            import unittest
            original = None
            class Cases(unittest.TestCase):
                def test_a_add(self):
                    original.addTest(Cases("test_c_not_selected"))
                def test_b_restore(self):
                    original._tests.pop()
                def test_c_not_selected(self):
                    self.fail("must not execute")
            def load_tests(loader, tests, pattern):
                global original
                original = unittest.TestSuite([Cases("test_a_add"), Cases("test_b_restore")])
                return original
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(2, report["discovery"]["tests_selected"])
        self.assertEqual(2, report["counts"]["tests_run"])
        self.assertEqual(2, report["counts"]["successful_tests"])
        self.assertIs(False, report["discovery"]["original_suite_shape_unchanged"])
        self.assertIn("selected_suite_changed", self.codes(report))

    def test_unvisited_same_class_occurrence_stays_pending_after_fixture_and_stop(self):
        report = self.execute(f"""
            import unittest
            class ABlocked(unittest.TestCase):
                @classmethod
                def setUpClass(cls):
                    raise unittest.SkipTest({CANARY!r})
                def test_one(self):
                    self.fail("must not execute")
            class BStop(unittest.TestCase):
                def test_stop(self):
                    self._outcome.result.stop()
            def load_tests(loader, tests, pattern):
                case = ABlocked("test_one")
                return unittest.TestSuite([case, BStop("test_stop"), case])
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(["setup_blocked", "started", "pending"],
                         [row["status"] for row in report["discovery"]["test_inventory"]])
        self.assertEqual([1], report["framework_events"][0]["blocked_discovery_ordinals"])
        self.assertEqual(1, report["test_denominators"]["setup_blocked_test_cases"])
        self.assertEqual(1, report["test_denominators"]["pending_selected_test_cases"])
        self.assertIn("worker_stopped_early", self.codes(report))
        self.assertIn("selected_cases_not_accounted_for", self.codes(report))

    def test_output_overflow_is_bounded_and_preserves_failure(self):
        config = replace(self.config, max_output_bytes=128)
        report = self.execute(f"""
            import sys, unittest
            class Cases(unittest.TestCase):
                def test_a_failure(self):
                    self.fail({CANARY!r})
                def test_b_output(self):
                    print({CANARY!r} + "x" * 4096, flush=True)
                    print("y" * 4096, file=sys.stderr, flush=True)
        """, config)
        self.assertEqual("incomplete", report["state"])
        self.assertIn("console_output_limit", self.codes(report))
        self.assertEqual(1, report["counts"]["failures"])
        self.assertIs(True, report["raw_console"]["truncated"])
        paths = list(config.output.parent.glob("raw/python-checks-*/console.*.log"))
        self.assertEqual(2, len(paths))
        self.assertLessEqual(sum(path.stat().st_size for path in paths), 128)

    def test_structured_report_byte_limit_never_leaves_a_success_report(self):
        config = replace(self.config, max_report_bytes=512)
        report = self.execute(config=config)
        self.assertEqual("incomplete", report["state"])
        self.assertIs(False, report["persistence_complete"])
        for path in (config.output, config.junit):
            self.assertFalse(path.exists())
        for path in config.output.parent.glob("raw/python-checks-*/worker-*.json"):
            if path.name != "worker-config.json":
                self.assertLessEqual(path.stat().st_size, 512)

    def test_source_byte_change_invalidates_success_but_retains_failure(self):
        report = self.execute(f"""
            from pathlib import Path
            import unittest
            class Cases(unittest.TestCase):
                def test_change(self):
                    path = Path(__file__)
                    path.write_text(path.read_text() + "# changed\\n")
                    self.fail({CANARY!r})
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertIn("selected_source_changed", self.codes(report))
        self.assertNotEqual(report["source"]["selected_test_tree_digest"], report["source_after"]["selected_test_tree_digest"])

    def test_commit_change_invalidates_success_but_does_not_discard_failure(self):
        report = self.execute(f"""
            from pathlib import Path
            import unittest
            class Cases(unittest.TestCase):
                def test_change(self):
                    (Path(__file__).parents[1] / ".git" / "HEAD").write_text({("2" * 40)!r})
                    self.fail({CANARY!r})
        """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertIsNone(report["source_after"])
        self.assertIsNone(report["worker_source_after"])
        self.assertIn("parent_source_verification_incomplete", self.codes(report))

    def test_parent_post_source_verification_failure_retains_complete_worker_failure(self):
        real_snapshot, calls = runner.source_snapshot, 0

        def interrupted_snapshot(*args):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise runner.GuardError("source_verification_timeout")
            return real_snapshot(*args)

        with patch.object(runner, "source_snapshot", side_effect=interrupted_snapshot):
            report = self.execute(f"""
                import unittest
                class Cases(unittest.TestCase):
                    def test_failure(self):
                        self.fail({CANARY!r})
            """)
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(1, report["counts"]["failures"])
        self.assertEqual("final standard-unittest callbacks", report["counts_scope"])
        self.assertIs(False, report["results_complete"])
        self.assertIsNone(report["source_after"])

    def test_private_raw_directory_files_and_junit_digest_are_verified(self):
        report = self.execute(f"""
            import unittest
            class Cases(unittest.TestCase):
                def test_ok(self):
                    print({CANARY!r})
        """)
        self.assertEqual("passed", report["state"])
        directories = list(self.config.output.parent.glob("raw/python-checks-*"))
        self.assertEqual(1, len(directories))
        directory = directories[0]
        if os.name != "nt":
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            for path in list(directory.iterdir()) + [self.config.output, self.config.junit]:
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertIn(CANARY.encode(), (directory / "console.stdout.log").read_bytes())
        for name, stream in report["raw_console"]["streams"].items():
            raw = (directory / ("console." + name + ".log")).read_bytes()
            self.assertEqual(len(raw), stream["bytes"])
            self.assertEqual(runner.digest(raw), stream["digest"])
            self.assertTrue(stream["eof_observed"])
        raw = self.config.junit.read_bytes()
        self.assertEqual(runner.digest(raw), report["junit_report"]["digest"])
        self.assertEqual(len(raw), report["junit_report"]["size_bytes"])

    def test_existing_output_or_xml_is_preserved_without_starting_worker(self):
        for which in ("output", "junit"):
            with self.subTest(which=which):
                config = self.fixture()
                self.source(config, PASS_SOURCE)
                target = getattr(config, which)
                target.parent.mkdir(parents=True)
                original = b"earlier owner-local bytes must not be replaced"
                target.write_bytes(original)
                with patch.object(runner.subprocess, "Popen") as spawn:
                    report = runner.run_checks(config)
                spawn.assert_not_called()
                self.assertEqual(original, target.read_bytes())
                self.assertFalse(getattr(config, "junit" if which == "output" else "output").exists())
                self.assertEqual("incomplete", report["state"])
                self.assertIn("earlier_report_already_exists", self.codes(report))
                self.assertIs(False, report["persistence_complete"])

    def test_xml_publication_failure_persists_only_an_incomplete_json(self):
        real_publish = runner.publish_exclusive

        def fail_xml(path, raw):
            if path == self.config.junit:
                raise OSError(CANARY)
            return real_publish(path, raw)

        with patch.object(runner, "publish_exclusive", side_effect=fail_xml):
            report = self.execute()
        self.assertEqual("incomplete", report["state"])
        self.assertIs(False, report["persistence_complete"])
        self.assertIsNone(report["junit_report"])
        self.assertFalse(self.config.junit.exists())
        self.assertIn("report_persistence_incomplete", self.codes(report))

    def test_json_publication_failure_leaves_xml_explicitly_nonstandalone(self):
        real_publish, attempts = runner.publish_exclusive, 0

        def fail_json(path, raw):
            nonlocal attempts
            if path == self.config.output:
                attempts += 1
                raise OSError(CANARY)
            return real_publish(path, raw)

        with patch.object(runner, "publish_exclusive", side_effect=fail_json):
            report = self.execute()
        self.assertEqual("incomplete", report["state"])
        self.assertEqual(2, attempts)
        self.assertIs(False, report["results_complete"])
        self.assertIs(False, report["persistence_complete"])
        self.assertFalse(self.config.output.exists())
        self.assertTrue(self.config.junit.exists())
        self.assertEqual("true", self.xml_properties()["requires_matching_complete_json_and_successful_wrapper_exit"])

    def test_spawn_failure_is_sanitized_and_never_has_test_counts(self):
        with patch.object(runner.subprocess, "Popen", side_effect=OSError(CANARY)):
            report = self.execute()
        self.assertEqual("incomplete", report["state"])
        self.assertIsNone(report["counts"])
        self.assertIsNone(report["discovery"])
        self.assertIsNone(report["worker_return_code"])
        self.assertIn("wrapper_exception", self.codes(report))

    def test_missing_or_invalid_required_ci_context_cannot_pass_or_leak(self):
        variants = ({"ci_context": None}, {"expected_commit": None},
            {"ci_context": {**CONTEXT, "unapproved": CANARY}}, {"expected_commit": "2" * 40})
        for changes in variants:
            with self.subTest(keys=tuple(changes)):
                config = self.fixture(**changes)
                report = self.execute(config=config)
                self.assertEqual("incomplete", report["state"])
                self.assertIsNone(report["counts"])
                self.assertIsNone(report["worker_return_code"])
                self.assertFalse(list(config.output.parent.glob("raw/python-checks-*")))

    def test_local_context_is_explicitly_unverified_not_invented(self):
        config = replace(self.config, expected_commit=None, ci_context=None, require_ci_context=False)
        report = self.execute(config=config)
        self.assertEqual("passed", report["state"])
        self.assertIsNone(report["ci_context"])
        self.assertIsNone(report["source"]["expected_commit"])
        self.assertIs(False, report["source"]["expected_commit_verified"])

    def test_only_public_ci_environment_allowlist_is_read(self):
        class PublicEnvironment(dict):
            def get(self, key, default=None):
                self.reads.append(key)
                return super().get(key, default)

        environment = PublicEnvironment(GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="2", GITHUB_JOB="synthetic-job", RUNNER_OS="Linux")
        environment.reads = []
        with patch.object(runner.os, "environ", environment):
            context = runner.ci_context_from_environment("synthetic-host")
        self.assertEqual(CONTEXT, context)
        self.assertEqual({"GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB", "RUNNER_OS"}, set(environment.reads))

    def test_cli_preserves_pass_failure_and_incomplete_exit_codes(self):
        for source, expected in ((PASS_SOURCE, 0), ("import unittest\nclass Cases(unittest.TestCase):\n def test_bad(self): self.fail('omitted')\n", 1),
                                 ("value = 1\n", 2)):
            with self.subTest(expected=expected):
                config = self.fixture()
                self.source(config, source)
                environment = {"GITHUB_SHA": COMMIT, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_JOB": "synthetic-job", "RUNNER_OS": "Linux"}
                output = io.StringIO()
                with patch.object(runner, "REPO_ROOT", config.root), patch.dict(os.environ, environment), redirect_stdout(output):
                    code = runner.main(["--start-directory", "checks", "--output", "build/ci-evidence/python-checks.json",
                        "--junit", "build/ci-evidence/python-checks.xml", "--timeout", "3", "--host", "synthetic-host", "--require-ci-context"])
                self.assertEqual(expected, code)
                self.assertNotIn("omitted", output.getvalue())
                self.assertNotIn(CANARY, output.getvalue())

    def valid_protocol(self):
        source = runner.source_snapshot(self.config)
        return {"schema_version": 1, "phase": "final", "state": "passed", "source_before": source, "source_after": source,
            "ci_context": CONTEXT.copy(), "selection": {"start_directory": "checks", "pattern": "test_*.py"},
            "discovery": {"completed": True, "tests_selected": 1, "loader_errors": 0, "import_errors": 0, "import_failure_details": [],
                          "execution_shape_supported": True, "original_suite_shape_unchanged": True,
                          "test_inventory": [{"ordinal": 1, "id": "test_fake.Cases.test_ok",
                              "class_id": "test_fake.Cases", "module_id": "test_fake", "status": "started", "case_ordinal": 1,
                              "blocking_fixture_event_ordinal": None}]},
            "counts": {key: 1 if key in {"tests_run", "successful_tests"} else 0 for key in runner.COUNTS},
            "cases": [{"ordinal": 1, "discovery_ordinal": 1, "id": "test_fake.Cases.test_ok", "complete": True, "stop_callback_observed": True,
                       "elapsed_seconds": 0.1, "events": [{"kind": "success", "subtest": False, "exception_type": None, "frames": []}]}],
            "framework_events": [], "diagnostics": [], "results_complete": True}

    def test_strict_worker_schema_rejects_extra_keys_wrong_counts_and_completion_claims(self):
        self.source(self.config, PASS_SOURCE)
        valid = self.valid_protocol()
        self.assertEqual(valid, runner.validate_worker(valid, valid["source_before"], valid["source_after"], self.config))
        mutations = (
            lambda v: v.update(message=CANARY),
            lambda v: v.update(schema_version=True),
            lambda v: v["cases"][0].update(message=CANARY),
            lambda v: v["cases"][0].update(ordinal=True),
            lambda v: v["cases"][0].update(discovery_ordinal=True),
            lambda v: v["cases"][0].update(discovery_ordinal=None),
            lambda v: v["cases"][0].update(elapsed_seconds=float("nan")),
            lambda v: v["cases"][0].update(stop_callback_observed=False),
            lambda v: v["cases"][0]["events"][0].update(reason=CANARY),
            lambda v: v["cases"][0]["events"][0].update(kind=[]),
            lambda v: v["counts"].update(tests_run=True),
            lambda v: v["counts"].update(errors=1),
            lambda v: v["discovery"].update(import_errors=1),
            lambda v: v["discovery"].update(tests_selected=2),
            lambda v: v["discovery"].update(completed=False),
            lambda v: v["discovery"].update(original_suite_shape_unchanged=False),
            lambda v: v["discovery"].update(original_suite_shape_unchanged=None),
            lambda v: v["discovery"].update(original_suite_shape_unchanged=1),
            lambda v: v["discovery"]["test_inventory"][0].update(ordinal=True),
            lambda v: v["discovery"]["test_inventory"][0].update(case_ordinal=True),
            lambda v: v["discovery"]["test_inventory"][0].update(class_id="different.Class"),
            lambda v: v["discovery"]["test_inventory"][0].update(status="pending"),
            lambda v: v["source_after"].update(scope=CANARY),
            lambda v: v.update(results_complete=False),
            lambda v: v.update(cases=[], counts={key: 0 for key in runner.COUNTS}),
            lambda v: v["diagnostics"].append({"code": CANARY, "exception_type": None, "frames": []}),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                value = copy.deepcopy(valid)
                mutate(value)
                with self.assertRaises(runner.GuardError):
                    runner.validate_worker(value, valid["source_before"], valid["source_after"], self.config)

    def test_protocol_requires_exact_occurrence_to_setup_event_accounting(self):
        self.source(self.config, PASS_SOURCE)
        valid = self.valid_protocol()
        valid["discovery"]["tests_selected"] = 2
        valid["discovery"]["test_inventory"].append({"ordinal": 2, "id": "test_fake.Blocked.test_one",
            "class_id": "test_fake.Blocked", "module_id": "test_fake", "status": "setup_blocked", "case_ordinal": None,
            "blocking_fixture_event_ordinal": 1})
        valid["framework_events"].append({"ordinal": 1, "id": "test_fake.Blocked", "fixture": "setUpClass", "kind": "skipped",
            "blocked_discovery_ordinals": [2], "exception_type": None, "frames": []})
        valid["counts"]["skipped"] = 1
        self.assertEqual(valid, runner.validate_worker(valid, valid["source_before"], valid["source_after"], self.config))

        def unsupported_shape(value):
            value["discovery"]["execution_shape_supported"] = False
            value["discovery"]["original_suite_shape_unchanged"] = None
            for row in value["discovery"]["test_inventory"]:
                row.update(status="pending", case_ordinal=None, blocking_fixture_event_ordinal=None)
            value["cases"][0]["discovery_ordinal"] = None
            value["framework_events"][0]["blocked_discovery_ordinals"] = []

        mutations = (
            lambda v: v["framework_events"][0].update(blocked_discovery_ordinals=[]),
            lambda v: v["framework_events"][0].update(blocked_discovery_ordinals=[True]),
            lambda v: v["framework_events"][0].update(blocked_discovery_ordinals=[2, 2]),
            lambda v: v["framework_events"][0].update(blocked_discovery_ordinals=[1, 2]),
            lambda v: v["framework_events"][0].update(fixture="tearDownClass"),
            lambda v: v["framework_events"][0].update(id="test_fake.Unselected"),
            lambda v: v["framework_events"][0].update(id="test_fake.Cases"),
            lambda v: v["discovery"]["test_inventory"][1].update(blocking_fixture_event_ordinal=True),
            lambda v: v["discovery"]["test_inventory"][1].update(blocking_fixture_event_ordinal=2),
            lambda v: v["discovery"]["test_inventory"][1].update(status="pending", blocking_fixture_event_ordinal=None),
            lambda v: v["cases"][0].update(discovery_ordinal=2),
            lambda v: v["cases"][0].update(complete=False),
            unsupported_shape,
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                value = copy.deepcopy(valid)
                mutate(value)
                with self.assertRaises(runner.GuardError):
                    runner.validate_worker(value, valid["source_before"], valid["source_after"], self.config)

    def test_protocol_rejects_absolute_traversal_raw_stack_data_and_wrong_types(self):
        self.source(self.config, PASS_SOURCE)
        valid = self.valid_protocol()
        for frame in ({"path": "/private/file.py", "line": 1, "function": "test"},
                      {"path": "../private.py", "line": 1, "function": "test"},
                      {"path": "C:/private.py", "line": 1, "function": "test"},
                      {"path": "checks/test_fake.py", "line": True, "function": "test"},
                      {"path": "checks/test_fake.py", "line": 1, "function": "test", "locals": CANARY}):
            with self.subTest(frame_keys=tuple(frame)):
                value = copy.deepcopy(valid)
                value["cases"][0]["events"] = [{"kind": "error", "subtest": False, "exception_type": "builtins.ValueError", "frames": [frame]}]
                value["counts"].update(successful_tests=0, errors=1)
                value["state"] = "failed"
                with self.assertRaises(runner.GuardError):
                    runner.validate_worker(value, valid["source_before"], valid["source_after"], self.config)

    def test_strict_json_rejects_duplicates_nonfinite_values_and_byte_overflow(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":{"y":1,"y":2}}', b'{"x":NaN}', b'{"x":Infinity}', b" " * 100):
            with self.subTest(raw_size=len(raw)), self.assertRaises(runner.GuardError):
                runner.strict_json(raw, 64)

    def test_private_worker_control_schema_and_destination_fail_closed(self):
        directory = self.base / "private_control"
        directory.mkdir(mode=0o700)
        control = directory / "worker-config.json"
        for value in ({"message": CANARY}, {"root": str(self.config.root), "final": str(self.base / "outside.json")}):
            control.write_text(json.dumps(value), encoding="utf-8")
            self.assertEqual(2, runner.main(["--_worker", str(control)]))
        self.assertFalse((self.base / "outside.json").exists())

    def test_source_inventory_covers_python_helpers_and_excludes_cache_bytes(self):
        self.source(self.config, PASS_SOURCE)
        helper = self.source(self.config, "value = 1\n", "helper.py")
        cache = self.config.start / "__pycache__"
        cache.mkdir()
        (cache / "not-enrolled.py").write_text(CANARY, encoding="utf-8")
        before = runner.source_snapshot(self.config)
        self.assertEqual(2, before["python_file_count"])
        helper.write_text("value = 2\n", encoding="utf-8")
        after = runner.source_snapshot(self.config)
        self.assertNotEqual(before["selected_test_tree_digest"], after["selected_test_tree_digest"])
        self.assertNotIn(CANARY, json.dumps(after))

    def test_source_limits_walk_errors_and_changed_checkout_fail_closed(self):
        self.source(self.config, PASS_SOURCE)
        self.source(self.config, "value = 1\n", "helper.py")
        for constant, limit in (("MAX_SOURCE_FILES", 1), ("MAX_SOURCE_BYTES", 1), ("MAX_SOURCE_FILE_BYTES", 1)):
            with self.subTest(constant=constant), patch.object(runner, constant, limit), self.assertRaises(runner.GuardError):
                runner.source_snapshot(self.config)

        def broken_walk(*args, **kwargs):
            kwargs["onerror"](OSError(CANARY))

        with patch.object(runner.os, "walk", side_effect=broken_walk), self.assertRaises(runner.GuardError):
            runner.source_snapshot(self.config)
        with patch.object(runner, "checkout_commit", side_effect=[COMMIT, "2" * 40]), self.assertRaises(runner.GuardError):
            runner.source_snapshot(self.config)
        with self.assertRaises(runner.GuardError):
            runner.source_snapshot(self.config, time.monotonic() - 1)

    def test_checkout_reads_detached_loose_or_unique_packed_ref_only(self):
        self.assertEqual(COMMIT, runner.checkout_commit(self.config.root))
        git = self.config.root / ".git"
        (git / "HEAD").write_text("ref: refs/heads/synthetic\n", encoding="ascii")
        (git / "refs" / "heads").mkdir(parents=True)
        ref = git / "refs" / "heads" / "synthetic"
        ref.write_text(COMMIT + "\n", encoding="ascii")
        self.assertEqual(COMMIT, runner.checkout_commit(self.config.root))
        ref.unlink()
        packed = git / "packed-refs"
        packed.write_text(f"# ignored {CANARY}\n{COMMIT} refs/heads/synthetic\n", encoding="ascii")
        self.assertEqual(COMMIT, runner.checkout_commit(self.config.root))
        packed.write_text(f"{COMMIT} refs/heads/synthetic\n{COMMIT} refs/heads/synthetic\n", encoding="ascii")
        with self.assertRaises(runner.GuardError):
            runner.checkout_commit(self.config.root)
        for reference in ("refs/heads/../private", "refs/heads/./synthetic", "refs/heads//synthetic"):
            (git / "HEAD").write_text("ref: " + reference, encoding="ascii")
            with self.subTest(reference=reference), self.assertRaises(runner.GuardError):
                runner.checkout_commit(self.config.root)

    def symlink(self, target, link, is_directory=False, host_name=None):
        try:
            link.symlink_to(target, target_is_directory=is_directory)
        except OSError as error:
            if (os.name if host_name is None else host_name) == "nt" and getattr(error, "winerror", None) == 1314:
                self.skipTest("Windows denied real symlink privilege; portable rejection seams run separately, no ACL qualification")
            raise

    def marked_symlinks(self, *paths):
        """An owned lexical is_symlink seam; no link creation privilege needed."""
        real_is_symlink = type(self.base).is_symlink

        def is_symlink(path):
            return path in paths or real_is_symlink(path)

        return patch.object(type(self.base), "is_symlink", autospec=True, side_effect=is_symlink)

    def marked_junctions(self, *paths):
        """Portable junction-detection seam; no native junction or ACL proof."""
        real_is_junction = type(self.base).is_junction

        def is_junction(path):
            return path in paths or real_is_junction(path)

        return patch.object(type(self.base), "is_junction", autospec=True, side_effect=is_junction)

    def test_portable_checkout_junction_is_rejected_before_reading_metadata(self):
        git = self.config.root / ".git"
        with self.marked_junctions(git), patch.object(runner, "read_small") as read:
            with self.assertRaises(runner.GuardError) as caught:
                runner.checkout_commit(self.config.root)
        self.assertEqual("unsupported_checkout_metadata", caught.exception.code)
        read.assert_not_called()

    def test_portable_checkout_ref_junction_is_rejected_without_reading_ref(self):
        git = self.config.root / ".git"
        (git / "HEAD").write_text("ref: refs/heads/synthetic\n", encoding="ascii")
        heads = git / "refs" / "heads"
        heads.mkdir(parents=True)
        (heads / "synthetic").write_text(COMMIT, encoding="ascii")
        with self.marked_junctions(heads), patch.object(runner, "read_small", wraps=runner.read_small) as read:
            with self.assertRaises(runner.GuardError) as caught:
                runner.checkout_commit(self.config.root)
        self.assertEqual("junctioned_path_ancestor", caught.exception.code)
        read.assert_called_once_with(git / "HEAD", 4096)

    def test_portable_selected_path_junction_seams_never_follow_aliases(self):
        for kind, code in (("start", "junctioned_path_ancestor"),
                           ("directory", "junctioned_test_directory"), ("output", "junctioned_path_ancestor")):
            with self.subTest(kind=kind):
                config = self.fixture()
                self.source(config, PASS_SOURCE)
                if kind == "start":
                    marked, call = config.start, lambda: runner.normalized(config)
                elif kind == "directory":
                    marked = config.start / "marked_directory"
                    marked.mkdir()
                    (marked / "foreign.py").write_bytes(b"foreign source must not be enrolled\n")
                    call = lambda: runner.source_snapshot(config)
                else:
                    marked = config.output.parent
                    marked.mkdir(parents=True)
                    config.output.write_bytes(b"owner bytes remain")
                    call = lambda: runner.normalized(config)
                with self.marked_junctions(marked), patch.object(runner, "read_small", wraps=runner.read_small) as read:
                    with self.assertRaises(runner.GuardError) as caught:
                        call()
                self.assertEqual(code, caught.exception.code)
                if kind == "directory":
                    read.assert_called_once_with(config.root / ".git" / "HEAD", 4096)
                else:
                    read.assert_not_called()
                if kind == "output":
                    self.assertEqual(b"owner bytes remain", config.output.read_bytes())

    def test_symlink_helper_propagates_unrelated_errors_instead_of_skipping(self):
        errors = (OSError(5, CANARY), FileExistsError(CANARY), PermissionError(CANARY), NotImplementedError(CANARY))
        for error in errors:
            with self.subTest(error_type=type(error).__name__):
                link = SimpleNamespace(symlink_to=Mock(side_effect=error))
                with self.assertRaises(type(error)) as caught:
                    self.symlink(self.config.start, link, True, host_name="nt")
                self.assertIs(error, caught.exception)
        denial = OSError(CANARY)
        denial.winerror = 5
        with self.assertRaises(OSError) as caught:
            self.symlink(self.config.start, SimpleNamespace(symlink_to=Mock(side_effect=denial)), host_name="nt")
        self.assertIs(denial, caught.exception)

    def test_symlink_capability_skip_is_only_windows_privilege_denial(self):
        privilege = OSError(CANARY)
        privilege.winerror = 1314
        link = SimpleNamespace(symlink_to=Mock(side_effect=privilege))
        with self.assertRaises(unittest.SkipTest):
            self.symlink(self.config.start, link, host_name="nt")
        with self.assertRaises(OSError) as caught:
            self.symlink(self.config.start, link, host_name="posix")
        self.assertIs(privilege, caught.exception)

    def test_portable_checkout_ref_symlink_seam_rejects_without_reading_ref(self):
        git = self.config.root / ".git"
        (git / "HEAD").write_text("ref: refs/heads/synthetic\n", encoding="ascii")
        heads = git / "refs" / "heads"
        heads.mkdir(parents=True)
        ref = heads / "synthetic"
        ref.write_text(COMMIT, encoding="ascii")
        with self.marked_symlinks(heads), patch.object(runner, "read_small", wraps=runner.read_small) as read:
            with self.assertRaises(runner.GuardError) as caught:
                runner.checkout_commit(self.config.root)
        self.assertEqual("symlinked_path_ancestor", caught.exception.code)
        read.assert_called_once_with(git / "HEAD", 4096)

    def test_portable_selected_path_symlink_seams_run_without_link_capability(self):
        for kind, code in (("start", "symlinked_path_ancestor"), ("file", "nonregular_input"),
                           ("directory", "symlinked_test_directory"), ("output", "symlinked_path_ancestor")):
            with self.subTest(kind=kind):
                config = self.fixture()
                self.source(config, PASS_SOURCE)
                if kind == "start":
                    marked, call = config.start, lambda: runner.normalized(config)
                elif kind == "file":
                    marked = self.source(config, "value = 1\n", "helper.py")
                    call = lambda: runner.source_snapshot(config)
                elif kind == "directory":
                    marked = config.start / "marked_directory"
                    marked.mkdir()
                    call = lambda: runner.source_snapshot(config)
                else:
                    marked = config.output
                    marked.parent.mkdir(parents=True)
                    marked.write_bytes(b"owner bytes remain")
                    call = lambda: runner.normalized(config)
                with self.marked_symlinks(marked), self.assertRaises(runner.GuardError) as caught:
                    call()
                self.assertEqual(code, caught.exception.code)
                if kind == "output":
                    self.assertEqual(b"owner bytes remain", marked.read_bytes())

    def test_checkout_ref_ancestor_symlink_is_rejected_without_reading_target(self):
        git = self.config.root / ".git"
        (git / "HEAD").write_text("ref: refs/heads/synthetic\n", encoding="ascii")
        (git / "refs").mkdir()
        target = self.base / "other_refs"
        target.mkdir()
        (target / "synthetic").write_text(COMMIT, encoding="ascii")
        self.symlink(target, git / "refs" / "heads", True)
        with self.assertRaises(runner.GuardError) as caught:
            runner.checkout_commit(self.config.root)
        self.assertEqual("symlinked_path_ancestor", caught.exception.code)

    def test_selected_tree_file_directory_and_destination_symlinks_are_rejected(self):
        for kind in ("start", "file", "directory", "output"):
            with self.subTest(kind=kind):
                config = self.fixture()
                self.source(config, PASS_SOURCE)
                if kind == "start":
                    link = config.root / "linked_checks"
                    self.symlink(config.start, link, True)
                    config = replace(config, start=link)
                    call = lambda: runner.normalized(config)
                elif kind == "file":
                    self.symlink(config.start / "test_fake.py", config.start / "helper.py")
                    call = lambda: runner.source_snapshot(config)
                elif kind == "directory":
                    self.symlink(config.start, config.start / "linked", True)
                    call = lambda: runner.source_snapshot(config)
                else:
                    config.output.parent.mkdir(parents=True)
                    target = self.base / ("owner_bytes_" + str(self.counter))
                    target.write_bytes(b"not replaced")
                    self.symlink(target, config.output)
                    call = lambda: runner.normalized(config)
                with self.assertRaises(runner.GuardError):
                    call()

    def test_execution_and_report_destination_bounds_fail_closed(self):
        variants = ({"timeout": float("nan")}, {"timeout": True}, {"timeout": 0}, {"timeout": 2401},
            {"max_output_bytes": 0}, {"max_output_bytes": runner.MAX_REPORT_BYTES + 1},
            {"max_report_bytes": 511}, {"max_report_bytes": runner.MAX_REPORT_BYTES + 1},
            {"pattern": "../test_*.py"}, {"expected_commit": "not-a-sha"}, {"output": self.base / "outside.json"},
            {"junit": self.config.output})
        for changes in variants:
            with self.subTest(keys=tuple(changes)), self.assertRaises(runner.GuardError):
                runner.normalized(replace(self.config, **changes))

    def test_exclusive_publish_preserves_previous_bytes_and_rolls_back_failed_readback(self):
        path = self.base / "receipt.json"
        path.write_bytes(b"earlier")
        with self.assertRaises(FileExistsError):
            runner.publish_exclusive(path, b"new")
        self.assertEqual(b"earlier", path.read_bytes())
        path.unlink()
        with patch.object(runner, "read_small", return_value=b"wrong"), self.assertRaises(runner.GuardError):
            runner.publish_exclusive(path, b"new")
        self.assertFalse(path.exists())
        self.assertFalse(list(self.base.glob("receipt.json.*.tmp")))

    def test_case_and_event_limits_do_not_fabricate_success_or_extra_counts(self):
        class Cases(unittest.TestCase):
            def runTest(self):
                self.fail("omitted")

        result = runner.RecordingResult(self.config.root, lambda: None)
        with patch.object(runner, "MAX_CASES", 1):
            Cases().run(result)
            with self.assertRaises(runner.ReportLimitReached):
                Cases().run(result)
        self.assertEqual(1, result.testsRun)
        self.assertEqual(1, len(result.failures))
        self.assertEqual(0, result.actual_counts()["successful_tests"])
        with patch.object(runner, "MAX_EVENTS", 1), self.assertRaises(runner.ReportLimitReached):
            result.event(Cases(), "skipped")
        self.assertEqual(1, result.event_count)

    def test_discovery_occurrence_shape_limits_fail_without_partial_inventory_claims(self):
        case = unittest.FunctionTestCase(lambda: None)
        with patch.object(runner, "MAX_CASES", 1), self.assertRaises(runner.GuardError) as caught:
            runner.discovery_inventory(unittest.TestSuite([case, case]))
        self.assertEqual("discovery_case_limit", caught.exception.code)
        nested = unittest.TestSuite([unittest.TestSuite([case])])
        with patch.object(runner, "MAX_SUITE_DEPTH", 0), self.assertRaises(runner.GuardError) as caught:
            runner.discovery_inventory(nested)
        self.assertEqual("discovery_suite_shape_limit", caught.exception.code)
        with self.assertRaises(runner.GuardError) as caught:
            runner.discovery_inventory(unittest.TestSuite([lambda result: None]))
        self.assertEqual("unsupported_discovery_leaf", caught.exception.code)

    def test_custom_case_count_is_not_the_discovery_denominator(self):
        report = self.execute("""
            import unittest
            class Cases(unittest.TestCase):
                def countTestCases(self):
                    return 999
                def test_ok(self):
                    pass
        """)
        self.assertEqual("passed", report["state"])
        self.assertEqual(1, report["discovery"]["tests_selected"])
        self.assertEqual(1, report["counts"]["tests_run"])

    def test_original_selection_guard_error_is_sticky_without_discarding_failure(self):
        def fail():
            raise AssertionError(CANARY)

        original = unittest.TestSuite([unittest.FunctionTestCase(fail)])
        inventory, selected, supported = runner.discovery_inventory(original)
        clone, guard = runner.recording_suite(original, selected)
        result = runner.RecordingResult(self.config.root, lambda: None, inventory, selected, supported)
        result.selection_guard = guard
        self.assertIs(True, result.observe_original_selection())
        clone.run(result)
        self.assertEqual(1, result.actual_counts()["failures"])
        self.assertIs(True, result.cases[0]["complete"])
        result.selection_guard = Mock(side_effect=OSError(CANARY))
        self.assertIs(False, result.observe_original_selection())
        result.selection_guard.assert_called_once_with()
        result.selection_guard = guard
        self.assertIs(False, result.observe_original_selection())
        self.assertIs(True, guard())
        self.assertEqual(1, result.actual_counts()["failures"])
        self.assertIs(True, result.cases[0]["complete"])
        self.assertNotIn(CANARY, json.dumps({"counts": result.actual_counts(), "cases": result.cases}))

    def test_every_outcome_checkpoint_observes_updated_counts_while_case_is_active(self):
        snapshots = []
        result = runner.RecordingResult(self.config.root,
            lambda: snapshots.append((result.actual_counts(), copy.deepcopy(result.cases))))

        class Outcomes(unittest.TestCase):
            def test_a_success(self):
                pass

            def test_b_failure(self):
                self.fail(CANARY)

            def test_c_error(self):
                raise ValueError(CANARY)

            @unittest.skip(CANARY)
            def test_d_skip(self):
                pass

            @unittest.expectedFailure
            def test_e_expected_failure(self):
                self.fail(CANARY)

            @unittest.expectedFailure
            def test_f_unexpected_success(self):
                pass

            def test_g_subtests(self):
                with self.subTest(private=CANARY):
                    self.fail(CANARY)
                with self.subTest(private=CANARY):
                    raise ValueError(CANARY)
                with self.subTest(private=CANARY):
                    raise unittest.SkipTest(CANARY)
                with self.subTest(private=CANARY):
                    pass

        unittest.defaultTestLoader.loadTestsFromTestCase(Outcomes).run(result)
        mapping = {"success": "successful_tests", "failure": "failures", "error": "errors", "skipped": "skipped",
                   "expected_failure": "expected_failures", "unexpected_success": "unexpected_successes"}
        active_outcomes, active_subtests = set(), set()
        for counts, cases in snapshots:
            observed = {key: 0 for key in runner.COUNTS}
            observed["tests_run"] = len(cases)
            for case in cases:
                for event in case["events"]:
                    observed[mapping[event["kind"]]] += 1
            self.assertEqual(observed, counts)
            if cases[-1]["events"] and not cases[-1]["stop_callback_observed"]:
                self.assertIs(False, cases[-1]["complete"])
                event = cases[-1]["events"][-1]
                active_outcomes.add(event["kind"])
                if event["subtest"]:
                    active_subtests.add(event["kind"])
        self.assertEqual(set(runner.EVENTS), active_outcomes)
        self.assertEqual({"failure", "error", "skipped"}, active_subtests)
        self.assertEqual({"tests_run": 7, "successful_tests": 1, "failures": 2, "errors": 2, "skipped": 2,
                          "expected_failures": 1, "unexpected_successes": 1}, result.actual_counts())

    def test_pipe_drain_has_a_fixed_join_bound_without_claiming_eof(self):
        directory = self.base / "capture"
        directory.mkdir(mode=0o700)
        release = threading.Event()

        class SlowPipe:
            def read1(self, _):
                release.wait(2)
                return b""

            def close(self):
                pass

        capture = runner.ConsoleCapture(directory, 64)
        capture.attach(SimpleNamespace(stdout=SlowPipe(), stderr=SlowPipe()))
        try:
            started = time.monotonic()
            with patch.object(runner, "PIPE_DRAIN_SECONDS", 0.02):
                report = capture.finish()
            self.assertLess(time.monotonic() - started, 0.5)
            self.assertIs(False, report["drain_completed"])
            self.assertTrue(all(not stream["eof_observed"] for stream in report["streams"].values()))
        finally:
            release.set()
            for thread in capture.threads:
                thread.join(1)

    def test_unfinished_pipe_drain_forces_incomplete_even_after_passing_worker(self):
        real_finish = runner.ConsoleCapture.finish

        def unfinished(capture):
            report = real_finish(capture)
            report["drain_completed"] = False
            return report

        with patch.object(runner.ConsoleCapture, "finish", unfinished):
            report = self.execute()
        self.assertEqual("incomplete", report["state"])
        self.assertIn("console_capture_incomplete", self.codes(report))
        self.assertIs(False, report["results_complete"])

    def cleanup_record(self):
        return {"process_group_kill_requested": False, "direct_worker_kill_requested": False,
            "kill_request_failed": False, "direct_worker_wait_complete": True, "descendant_absence_verified": False}

    def test_posix_cleanup_has_one_best_effort_group_signal_never_descendant_proof(self):
        process = SimpleNamespace(pid=424242, poll=Mock(return_value=None), kill=Mock(), wait=Mock())
        cleanup = self.cleanup_record()
        local_os, local_signal = SimpleNamespace(name="posix", killpg=Mock()), SimpleNamespace(SIGKILL=9)
        with patch.object(runner, "os", local_os), patch.object(runner, "signal", local_signal):
            runner.stop_worker(process, cleanup)
        local_os.killpg.assert_called_once_with(424242, 9)
        process.kill.assert_not_called()
        process.wait.assert_called_once_with(timeout=runner.CLEANUP_WAIT_SECONDS)
        self.assertIs(True, cleanup["process_group_kill_requested"])
        self.assertIs(False, cleanup["descendant_absence_verified"])

    def test_windows_cleanup_only_targets_direct_worker_and_preserves_wait_failure(self):
        process = SimpleNamespace(pid=424242, poll=Mock(return_value=None), kill=Mock(),
            wait=Mock(side_effect=subprocess.TimeoutExpired("synthetic", 2)))
        cleanup = self.cleanup_record()
        local_os = SimpleNamespace(name="nt", killpg=Mock())
        with patch.object(runner, "os", local_os), patch.object(runner, "signal", SimpleNamespace()):
            runner.stop_worker(process, cleanup)
        process.kill.assert_called_once_with()
        local_os.killpg.assert_not_called()
        self.assertIs(True, cleanup["direct_worker_kill_requested"])
        self.assertIs(False, cleanup["direct_worker_wait_complete"])
        self.assertIs(False, cleanup["descendant_absence_verified"])

    def test_reaped_worker_is_never_signaled_again(self):
        for platform in ("posix", "nt"):
            with self.subTest(platform=platform):
                process = SimpleNamespace(pid=424242, poll=Mock(return_value=0), kill=Mock(), wait=Mock())
                local_os = SimpleNamespace(name=platform, killpg=Mock())
                with patch.object(runner, "os", local_os), patch.object(runner, "signal", SimpleNamespace(SIGKILL=9)):
                    runner.stop_worker(process, self.cleanup_record())
                local_os.killpg.assert_not_called()
                process.kill.assert_not_called()
                process.wait.assert_not_called()


if __name__ == "__main__":
    unittest.main()
