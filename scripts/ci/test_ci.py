"""Adversarial infrastructure unit tests, not evidence that a provider/target ran."""

import argparse
import copy
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

try:
    from . import ci_common, collect_evidence, record_environment, run_platform_checks, run_provider_checks, smoke_distribution
except ImportError:
    import ci_common
    import collect_evidence
    import record_environment
    import run_platform_checks
    import run_provider_checks
    import smoke_distribution


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}


def doctor():
    target = smoke_distribution.supervisor_target(smoke_distribution.platform.system(), smoke_distribution.platform.machine())
    supported = target in smoke_distribution.SUPERVISOR_TARGETS
    containment = {"state": "available" if supported else "unavailable", "target": target,
                   "bundledBytesVerified": supported, "executionVerified": False, "releaseQualified": False,
                   "reasons": [] if supported else ["process_group_containment_unavailable"], "scope": smoke_distribution.CONTAINMENT_SCOPE}
    if supported:
        containment["identity"] = {"protocolVersion": smoke_distribution.SUPERVISOR_PROTOCOL, "target": target,
                                   "sourceDigest": "sha256:" + "3" * 64, "binaryDigest": "sha256:" + "4" * 64}
    return {"contractVersion": "ktchecker/preview-1", "command": "doctor", "status": "preview",
            "version": "0.1.0-dev.1", "releaseQualified": False,
            "runtime": {"javaVersion": "21.0.12", "os": "Linux", "arch": "amd64"},
            "installedProvidersInspected": False, "processContainment": containment,
            "capabilities": [], "gaps": ["Actual host observation has not run"]}


class SourceAndProcessTests(unittest.TestCase):
    def test_actual_uncommitted_bytes_change_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "tracked.kt").write_bytes(b"before")
            (root / "untracked.kt").write_bytes(b"new")

            def git(argv, **_):
                if argv[1] == "rev-parse":
                    output = ("1" * 40 + "\n").encode()
                elif argv[1] == "ls-files":
                    output = b"tracked.kt\0untracked.kt\0deleted.kt\0"
                else:
                    output = b" M tracked.kt\n?? untracked.kt\n"
                return subprocess.CompletedProcess(argv, 0, stdout=output, stderr=b"")

            with patch.object(ci_common.subprocess, "run", side_effect=git):
                before = ci_common.source_identity(root)
                (root / "untracked.kt").write_bytes(b"changed")
                after = ci_common.source_identity(root)
            self.assertEqual(before["commit"], after["commit"])
            self.assertNotEqual(before["working_tree_digest"], after["working_tree_digest"])
            self.assertTrue(before["dirty"])
            self.assertEqual(3, before["file_count"])
            self.assertNotIn("before", json.dumps(before))

    def test_task_success_is_not_no_source_or_cached_execution(self):
        tasks = ci_common.parse_gradle_tasks("""> Task :real
> Task :cached FROM-CACHE
> Task :up UP-TO-DATE
> Task :empty NO-SOURCE
> Task :apple SKIPPED
> Task :broken FAILED
BUILD SUCCESSFUL
""")
        self.assertEqual("EXECUTED", tasks[":real"])
        self.assertEqual("SKIPPED", tasks[":apple"])
        self.assertEqual("NO-SOURCE", tasks[":empty"])
        self.assertEqual({}, ci_common.parse_gradle_tasks("BUILD SUCCESSFUL"))

    def test_run_logged_invokes_typed_process_and_hashes_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, stdout, stderr = ci_common.run_logged(
                [sys.executable, "-c", "print('bounded output')"], cwd=root, log_base=root / "command", timeout=10
            )
            self.assertEqual("completed", result["state"])
            self.assertEqual(0, result["return_code"])
            self.assertIn("bounded output", stdout)
            self.assertEqual("", stderr)
            self.assertEqual(ci_common.digest_bytes(stdout.encode()), result["stdout_digest"])
            self.assertFalse(result["raw_logs_uploaded"])

    def test_timeout_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, _, _ = ci_common.run_logged(
                [sys.executable, "-c", "import time; time.sleep(30)"],
                cwd=root, log_base=root / "slow", timeout=1,
            )
            self.assertEqual("timeout", result["state"])
            self.assertNotEqual(0, result["return_code"])

    def test_side_report_output_is_bounded_and_monitored_not_silently_unlimited(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / "large.xml"
            result, _, _ = ci_common.run_logged(
                [sys.executable, "-c", "from pathlib import Path; import sys,time; Path(sys.argv[1]).write_bytes(b'x'*2048); time.sleep(30)", str(report)],
                cwd=root, log_base=root / "bounded-report", timeout=10, max_output_bytes=1024,
                additional_output_paths=(report,),
            )
            self.assertEqual("output_limit", result["state"])
            self.assertEqual(2048, result["additional_output_bytes"])
            self.assertTrue(result["additional_output_paths_monitored"])
            self.assertFalse(result["raw_logs_uploaded"])

    def test_even_fast_output_overrun_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, stdout, _ = ci_common.run_logged(
                [sys.executable, "-c", "print('x' * 1000)"],
                cwd=root, log_base=root / "loud", timeout=10, max_output_bytes=64,
            )
            self.assertEqual("output_limit", result["state"])
            self.assertLessEqual(len(stdout.encode()), 64)

    def test_oversized_log_preview_never_allocates_the_entire_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(Path, "read_bytes", side_effect=AssertionError("unbounded logfile read")):
                result, stdout, stderr = ci_common.run_logged(
                    [sys.executable, "-c", "import sys; sys.stdout.write('x' * 4096); sys.stderr.write('y' * 4096)"],
                    cwd=root, log_base=root / "large-preview", timeout=10,
                    max_output_bytes=32, separate_stderr=True,
                )
            self.assertEqual("output_limit", result["state"])
            self.assertEqual("x" * 32, stdout)
            self.assertEqual("y" * 32, stderr)
            self.assertEqual(ci_common.digest_bytes(b"x" * 4096), result["stdout_digest"])
            self.assertEqual(ci_common.digest_bytes(b"y" * 4096), result["stderr_digest"])

    def test_missing_executable_is_unavailable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, _, _ = ci_common.run_logged([str(root / "absent")], cwd=root, log_base=root / "absent")
            self.assertEqual("unavailable", result["state"])
            self.assertIsNone(result["return_code"])

    def test_completed_child_with_fast_output_overrun_is_never_group_signaled(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            process = Mock(pid=12345)
            process.poll.return_value = 0
            process.wait.return_value = 0

            def start(*_, **kwargs):
                kwargs["stdout"].write(b"x" * 256)
                kwargs["stdout"].flush()
                return process

            with patch.object(ci_common.subprocess, "Popen", side_effect=start), \
                 patch.object(ci_common, "terminate_tree") as terminate:
                result, _, _ = ci_common.run_logged(
                    ["synthetic-child"], cwd=root, log_base=root / "fast", max_output_bytes=32,
                )
            self.assertEqual("output_limit", result["state"])
            self.assertEqual(0, result["return_code"])
            terminate.assert_not_called()
            process.wait.assert_called_once_with(timeout=5)

    def test_final_direct_child_wait_is_bounded_without_post_wait_group_retry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            process = Mock(pid=12345, returncode=None)
            process.poll.return_value = None
            process.wait.side_effect = subprocess.TimeoutExpired("synthetic-child", 5)
            with patch.object(ci_common.subprocess, "Popen", return_value=process), \
                 patch.object(ci_common.time, "monotonic", side_effect=(0, 2, 3)), \
                 patch.object(ci_common, "terminate_tree") as terminate:
                result, _, _ = ci_common.run_logged(
                    ["synthetic-child"], cwd=root, log_base=root / "unverified", timeout=1,
                )
            self.assertEqual("cleanup_unverified", result["state"])
            self.assertIsNone(result["return_code"])
            process.wait.assert_called_once_with(timeout=5)
            terminate.assert_called_once_with(process)

    def test_ci_posix_group_signal_sequence_ends_at_successful_wait(self):
        # Synthetic call ordering only: not real signal delivery or native proof.
        kill_signal = getattr(signal, "SIGKILL", 9)
        for first_wait_times_out in (False, True):
            with self.subTest(first_wait_times_out=first_wait_times_out):
                events = []
                process = Mock(pid=12345)

                def wait(*, timeout):
                    events.append(("wait", timeout))
                    if first_wait_times_out and len(events) == 2:
                        raise subprocess.TimeoutExpired("synthetic-child", timeout)
                    return 0

                process.wait.side_effect = wait
                with patch.object(ci_common.os, "name", "posix"), \
                     patch.object(ci_common.os, "killpg", create=True,
                                  side_effect=lambda pid, sig: events.append((pid, sig))), \
                     patch.object(ci_common.signal, "SIGKILL", kill_signal, create=True):
                    ci_common.terminate_tree(process)
                expected = [(12345, signal.SIGTERM), ("wait", 5)]
                if first_wait_times_out:
                    expected.extend([(12345, kill_signal), ("wait", 5)])
                self.assertEqual(expected, events)

    def gradle_control(self, stdout, return_code=1, **kwargs):
        execution = {"state": "completed", "return_code": return_code}
        with patch.object(ci_common, "wrapper_argv", return_value=["/trusted/gradlew"]), \
             patch.object(ci_common, "run_logged", return_value=(execution, stdout, "")):
            result, _ = ci_common.gradle_result(["controlledTask"], log_base=Path("unused"), **kwargs)
        return result

    def test_expected_error_requires_task_failure_and_named_diagnostic(self):
        output = "> Task :compileKotlinIosArm64 FAILED\ne: file:///fixture/Envelope.kt:3:1 [NO_ACTUAL_FOR_EXPECT] platformFamily has no actual\n"
        result = self.gradle_control(output, expected_failure=True, failed_task=":compileKotlinIosArm64",
                                     diagnostic_pattern=run_platform_checks.NATIVE_DIAGNOSTICS["missing-actual"])
        self.assertEqual("passed", result["state"])
        for invalid in (output.replace("FAILED", "SKIPPED"), output.replace("[NO_ACTUAL_FOR_EXPECT] platformFamily has no actual", "Network unreachable")):
            result = self.gradle_control(invalid, expected_failure=True, failed_task=":compileKotlinIosArm64",
                                         diagnostic_pattern=run_platform_checks.NATIVE_DIAGNOSTICS["missing-actual"])
            self.assertEqual("failed", result["state"])

    def test_a_negative_control_that_succeeds_is_a_failure(self):
        result = self.gradle_control(
            "> Task :compileKotlinIosArm64\ne: [NO_ACTUAL_FOR_EXPECT] platformFamily has no actual",
            return_code=0, expected_failure=True, failed_task=":compileKotlinIosArm64", diagnostic_pattern="NO_ACTUAL_FOR_EXPECT",
        )
        self.assertEqual("failed", result["state"])

    def test_required_task_must_really_execute(self):
        for state in ("SKIPPED", "NO-SOURCE", "UP-TO-DATE", "FROM-CACHE"):
            result = self.gradle_control(f"> Task :iosSimulatorArm64Test {state}", return_code=0,
                                         require_tasks=(":iosSimulatorArm64Test",))
            self.assertEqual("failed", result["state"])


class ReportTests(unittest.TestCase):
    def report(self, xml):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "test.xml"
            path.write_text(xml, encoding="utf-8")
            return collect_evidence.junit_report(path, root)

    def test_nested_junit_does_not_double_count_aggregate(self):
        value = self.report('<testsuites><testsuite tests="2"><testsuite name="actual" tests="2"><testcase name="pass"/><testcase name="skip"><skipped/></testcase></testsuite></testsuite></testsuites>')
        self.assertEqual(2, value["counts"]["tests"])
        self.assertEqual(1, value["counts"]["passed"])
        self.assertEqual(1, value["counts"]["skipped"])
        self.assertEqual([], value["inconsistencies"])

    def test_test_output_and_failure_messages_are_not_uploaded(self):
        value = self.report('<testsuite name="control" tests="1" failures="1"><testcase name="fail"><failure>SECRET TRANSCRIPT</failure><system-out>PRIVATE SOURCE</system-out></testcase></testsuite>')
        self.assertEqual(1, value["counts"]["failure"])
        self.assertNotIn("SECRET", json.dumps(value))
        self.assertNotIn("PRIVATE", json.dumps(value))

    def test_namespaced_cases_are_counted(self):
        value = self.report('<testsuite xmlns="urn:example" tests="1"><testcase name="actual"/></testsuite>')
        self.assertEqual(1, value["counts"]["passed"])

    def test_declared_counts_cannot_forge_passed_cases(self):
        value = self.report('<testsuite name="empty" tests="100"/>')
        self.assertEqual(0, value["counts"]["tests"])
        self.assertTrue(value["inconsistencies"])

    def test_dtd_and_malformed_reports_fail(self):
        for xml in ('<!DOCTYPE testsuite [<!ENTITY x "hidden">]><testsuite/>', '<testsuite><testcase>'):
            with self.subTest(xml=xml), self.assertRaises((ValueError, collect_evidence.ET.ParseError)):
                self.report(xml)

    def test_artifact_digest_binds_actual_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "Sample.framework"
            directory.mkdir()
            binary = directory / "Sample"
            binary.write_bytes(b"actual binary")
            before = collect_evidence.artifact(directory, root)
            binary.write_bytes(b"changed binary")
            after = collect_evidence.artifact(directory, root)
            self.assertNotEqual(before["digest"], after["digest"])
            self.assertEqual(1, before["file_count"])

    def test_evidence_path_traversal_is_rejected(self):
        for pattern in ("../secret", "/etc/passwd", "build/../../secret", "C:/private", "C:private",
                        "\\\\server\\private", "build\\..\\private", "build/private:stream", "private\x00"):
            with self.subTest(pattern=pattern), self.assertRaises(ValueError):
                collect_evidence.expand(pattern)

    def test_missing_reports_or_artifacts_do_not_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = root / "environment.json"
            environment.write_text(json.dumps({"state": "passed", "source": IDENTITY}))
            args = argparse.Namespace(profile="test", environment=environment, reports=["reports/*.xml"],
                                      require_tests=True, require_report=["reports/*.xml"], artifact=[],
                                      require_artifact=["artifacts/*.zip"], command_result=[])
            with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
                 patch.dict(os.environ, {"KT_CI_JOB_STATUS": "success"}):
                value = collect_evidence.collect(args, root)
            self.assertEqual("incomplete", value["state"])
            self.assertEqual(3, len(value["missing_evidence"]))

    def test_passed_reports_bind_the_same_source_not_a_stale_label(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = root / "environment.json"
            environment.write_text(json.dumps({"state": "passed", "source": {**IDENTITY, "working_tree_digest": "wrong"}}))
            path = root / "test.xml"
            path.write_text('<testsuite tests="1"><testcase name="actual"/></testsuite>')
            args = argparse.Namespace(profile="test", environment=environment, reports=["test.xml"],
                                      require_tests=True, require_report=["test.xml"], artifact=[], require_artifact=[], command_result=[])
            with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
                 patch.dict(os.environ, {"KT_CI_JOB_STATUS": "success"}):
                value = collect_evidence.collect(args, root)
            self.assertEqual("incomplete", value["state"])
            self.assertTrue(any("same actual source" in error for error in value["errors"]))


class SmokeAndEnvironmentTests(unittest.TestCase):
    def test_doctor_must_explicitly_be_preview(self):
        self.assertEqual([], smoke_distribution.validate_doctor(doctor()))
        for key, invalid in (("releaseQualified", True), ("releaseQualified", 0), ("command", "checks"),
                             ("contractVersion", "ktchecker/1"), ("capabilities", {}), ("gaps", None)):
            value = doctor()
            value[key] = invalid
            self.assertTrue(smoke_distribution.validate_doctor(value), key)
        self.assertTrue(smoke_distribution.validate_doctor([]))
        self.assertTrue(smoke_distribution.validate_doctor({"arbitrary": "JSON"}))

    def test_doctor_containment_is_exact_byte_inspection_never_execution_or_release(self):
        value = doctor()
        value["processContainment"] = {"state": "available", "target": "linux-x86_64", "bundledBytesVerified": True,
                                       "executionVerified": False, "releaseQualified": False, "reasons": [],
                                       "scope": smoke_distribution.CONTAINMENT_SCOPE,
                                       "identity": {"protocolVersion": smoke_distribution.SUPERVISOR_PROTOCOL, "target": "linux-x86_64",
                                                    "sourceDigest": "sha256:" + "3" * 64, "binaryDigest": "sha256:" + "4" * 64}}
        self.assertEqual([], smoke_distribution.validate_doctor(value, require_containment=True, expected_target="linux-x86_64"))
        for key, replacement in (("executionVerified", True), ("releaseQualified", True), ("bundledBytesVerified", 1),
                                 ("target", "macos-aarch64"), ("reasons", ["PRIVATE path and message"]), ("identity", {})):
            invalid = copy.deepcopy(value)
            invalid["processContainment"][key] = replacement
            with self.subTest(key=key):
                self.assertTrue(smoke_distribution.validate_doctor(invalid, require_containment=True, expected_target="linux-x86_64"))
        value["installedProvidersInspected"] = True
        self.assertTrue(smoke_distribution.validate_doctor(value))
        del value["processContainment"]
        self.assertTrue(smoke_distribution.validate_doctor(value, require_containment=True))

    def test_doctor_windows_unavailability_is_portable_not_native_execution(self):
        value = {"state": "unavailable", "target": "windows-x86_64", "bundledBytesVerified": False,
                 "executionVerified": False, "releaseQualified": False, "reasons": ["process_group_containment_unavailable"],
                 "scope": smoke_distribution.CONTAINMENT_SCOPE}
        self.assertEqual([], smoke_distribution.validate_containment(value, "windows-x86_64"))
        self.assertTrue(smoke_distribution.validate_containment(value, "linux-x86_64"))
        for key, replacement in (("state", "available"), ("identity", {}), ("reasons", []), ("executionVerified", True)):
            with self.subTest(key=key):
                self.assertTrue(smoke_distribution.validate_containment(dict(value, **{key: replacement}), "windows-x86_64"))

    def test_fixed_windows_batch_quoting_supports_spaces_and_unicode(self):
        command = smoke_distribution.windows_batch_command(Path("C:/Temp/CI Δ fixture/bin/ktchecker.bat"), "C:/Windows/System32/cmd.exe")
        self.assertIn('/d /v:off /s /c ""', command)
        self.assertTrue(command.endswith('doctor --format json"'))
        for character in ('%', '!', '&', '|', '^', '"', '\n'):
            with self.assertRaises(ValueError):
                smoke_distribution.windows_batch_command(Path(f"C:/bad{character}path/ktchecker.bat"), "cmd.exe")

    def test_absent_installation_cannot_be_a_smoke_pass(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(smoke_distribution, "source_identity", return_value=IDENTITY):
            root = Path(temporary)
            value = smoke_distribution.smoke(root / "absent", root / "smoke.json")
            self.assertEqual("failed", value["state"])
            self.assertTrue(value["errors"])

    def test_only_whitelisted_environment_is_recorded(self):
        args = argparse.Namespace(gradle_result=None, require_java_major=21, require_gradle=None,
                                  require_xcode=None, require_xcode_build=None)
        with patch.object(record_environment, "source_identity", return_value=IDENTITY), \
             patch.object(record_environment, "wrapper_profile", return_value={"state": "pinned"}), \
             patch.object(record_environment, "probe", return_value=(0, 'openjdk version "21.0.12"\n')), \
             patch.object(record_environment, "observe_xcode", return_value={"state": "unavailable"}), \
             patch.dict(os.environ, {"TOKEN_SECRET": "must-not-be-recorded", "JAVA_HOME": "/public/jdk21"}, clear=True):
            result = record_environment.record(args)
        self.assertEqual("passed", result["state"])
        self.assertEqual("/public/jdk21", result["tool_paths"]["JAVA_HOME"])
        self.assertNotIn("must-not-be-recorded", json.dumps(result))
        self.assertNotIn("TOKEN_SECRET", json.dumps(result))
        self.assertEqual("not_performed", result["actual_codex_host_observation"]["state"])

    def test_xcode_pin_is_required_instead_of_runner_default(self):
        args = argparse.Namespace(gradle_result=None, require_java_major=21, require_gradle=None,
                                  require_xcode="26.4.1", require_xcode_build="17E202")
        with patch.object(record_environment, "source_identity", return_value=IDENTITY), \
             patch.object(record_environment, "wrapper_profile", return_value={"state": "pinned"}), \
             patch.object(record_environment, "probe", return_value=(0, 'openjdk version "21.0.12"\n')), \
             patch.object(record_environment, "observe_xcode", return_value={"state": "observed", "version": "26.6", "build": "wrong"}):
            result = record_environment.record(args)
        self.assertEqual("failed", result["state"])


class SimulatorTests(unittest.TestCase):
    def inventory(self, version="26.4", available=True):
        identifier = "com.apple.CoreSimulator.SimRuntime.iOS-" + version.replace(".", "-")
        runtime = {"identifier": identifier, "version": version, "isAvailable": available, "buildversion": "fixture-build"}
        device = {"name": "iPhone 17", "isAvailable": True, "udid": "12345678-1234-1234-1234-123456789abc", "state": "Shutdown"}
        return {"runtimes": [runtime]}, {"devices": {identifier: [device]}}

    def test_selects_uuid_from_exact_runtime_not_ambiguous_name(self):
        value = run_platform_checks.select_simulator(*self.inventory())
        self.assertEqual("26.4", value["runtime_version"])
        self.assertEqual("12345678-1234-1234-1234-123456789abc", value["udid"])

    def test_wrong_runtime_unavailable_device_and_untrusted_id_fail(self):
        for version, available in (("26.5", True), ("26.4", False)):
            with self.assertRaises(ValueError):
                run_platform_checks.select_simulator(*self.inventory(version, available))
        runtimes, devices = self.inventory()
        devices["devices"][runtimes["runtimes"][0]["identifier"]][0]["udid"] = "$(untrusted-command)"
        with self.assertRaises(ValueError):
            run_platform_checks.select_simulator(runtimes, devices)

    def test_linux_is_unavailable_not_a_native_test_pass(self):
        with patch.object(run_platform_checks.platform, "system", return_value="Linux"), \
             patch.object(run_platform_checks.platform, "machine", return_value="x86_64"), \
             patch.object(run_platform_checks, "probe", return_value=(0, 'openjdk version "25.0.1"\n')), \
             patch.object(run_platform_checks, "installed_packages", return_value=[{"installed": True}]), \
             patch.object(run_platform_checks, "observe_xcode", return_value={"state": "unavailable"}), \
             patch.dict(os.environ, {"ANDROID_HOME": "/public/sdk"}):
            result = run_platform_checks.preflight("ios")
        self.assertEqual("unavailable", result["state"])
        self.assertTrue(any("non-Darwin" in error for error in result["errors"]))
        self.assertNotIn("simulator", result)

    def test_project_jvm_25_is_separate_from_broker_21(self):
        for version, expected in (("25.0.1", "passed"), ("21.0.12", "unavailable")):
            with self.subTest(version=version), \
                 patch.object(run_platform_checks, "probe", return_value=(0, f'openjdk version "{version}"\n')) as probe, \
                 patch.object(run_platform_checks, "installed_packages", return_value=[{"installed": True}]), \
                 patch.dict(os.environ, {"ANDROID_HOME": "/public/sdk", "JAVA_HOME": "/public/project-jdk"}):
                result = run_platform_checks.preflight("desktop")
            self.assertEqual(expected, result["state"])
            self.assertEqual(25, result["required_project_java_major"])
            self.assertEqual(21, result["engine_broker_java_major"])
            self.assertEqual(str(Path("/public/project-jdk") / "bin" / ("java.exe" if os.name == "nt" else "java")), probe.call_args.args[0][0])


class ProviderEvidenceTests(unittest.TestCase):
    def driver(self, profile="android", case="positive", target="android"):
        provider_id = "kotlin-native" if profile == "ios" else "android-lint"
        upstream = next(iter(run_provider_checks.EXPECTED_NATIVE[case])) if profile == "ios" and case != "positive" else "NewApi"
        rule_id = run_provider_checks.CANONICAL_IDS[upstream]
        context = run_provider_checks.fixture_scope(profile, target)
        members = run_provider_checks.fixture_membership(profile, case, target)
        tasks = run_provider_checks.fixture_tasks(profile, target)
        snapshot = "sha256:" + "3" * 64
        path = "negative/new-api/androidMain/dev/ktchecker/fixture/Notification.kt" if profile == "android" else "src/commonMain/kotlin/dev/ktchecker/fixture/Envelope.kt"
        finding = {"ruleId": rule_id, "providerId": provider_id, "upstreamId": upstream, "severity": "error",
                   "context": context, "fingerprint": "sha256:" + "4" * 64,
                   "location": {"path": path, "fileDigest": ci_common.digest_file(run_provider_checks.FIXTURE_ROOT / path), "line": 3, "column": 0}}
        return {"schemaVersion": 1, "state": "passed", "case": case, "target": target, "snapshotId": snapshot,
                "modelBinding": {"scope": copy.deepcopy(context), "modelDigest": run_provider_checks.fixture_model_digest(profile, case, snapshot, context, members, tasks),
                                 "sourceSetByFile": members, "requiredTaskPaths": tasks, "provenance": run_provider_checks.MODEL_PROVENANCE},
                "artifactIdentities": [{"coordinate": "fixture-tool:1", "path": "/public/tool.jar", "digest": "sha256:" + "6" * 64}],
                "providerResult": {"providerId": provider_id, "providerVersion": "2.4.21" if profile == "ios" else "32.3.1", "status": "incomplete",
                                   "rawDiagnostics": [], "findings": [] if case == "positive" else [finding], "reasons": ["qualification_pending"],
                                   "coverage": [{"ruleId": rule_id, "context": context, "state": "incomplete", "qualified": False,
                                                 "provenance": "executed", "certificateId": "sha256:" + "7" * 64}]}}

    def test_small_executed_controls_accept_honest_incomplete_qualification(self):
        for profile, target, cases in (("android", "android", ("positive", "new-api")),
                                       ("ios", "iosArm64", ("positive", "missing-actual", "wrong-actual")),
                                       ("ios", "iosSimulatorArm64", ("positive", "missing-actual", "wrong-actual"))):
            for case in cases:
                with self.subTest(profile=profile, target=target, case=case):
                    self.assertEqual([], run_provider_checks.validate_driver(self.driver(profile, case, target), profile, case, target))

    def test_command_exit_zero_cannot_replace_driver_assertions(self):
        for state in ("unavailable", "failed", "running"):
            value = self.driver()
            value["state"] = state
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))

    def test_model_requires_root_scope_exact_members_and_bound_digest(self):
        original = self.driver(case="new-api")
        for mutate in (
            lambda row: row.pop("modelBinding"),
            lambda row: row["modelBinding"].update(scope={**row["modelBinding"]["scope"], "module": ":fixture"}),
            lambda row: row["modelBinding"].update(provenance="general_gradle_model_imported"),
            lambda row: row["modelBinding"].update(modelDigest="sha256:" + "9" * 64),
            lambda row: row["modelBinding"].update(sourceSetByFile={}),
            lambda row: row["modelBinding"]["sourceSetByFile"].update({"src/desktopMain/kotlin/dev/ktchecker/fixture/Platform.kt": "androidMain"}),
            lambda row: row["modelBinding"]["sourceSetByFile"].update({"negative/new-api/androidMain/dev/ktchecker/fixture/Notification.kt": "commonMain"}),
            lambda row: row["providerResult"]["coverage"][0]["context"].update(module=":other"),
            lambda row: row["providerResult"]["coverage"][0]["context"].update(compilation="main"),
        ):
            value = copy.deepcopy(original)
            mutate(value)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "new-api", "android"))

    def test_model_exact_task_paths_reject_basename_child_module_extra_and_duplicates(self):
        for paths in (("lintAnalyzeAndroidMain", ":lintReportAndroidMain"),
                      (":other:lintAnalyzeAndroidMain", ":lintReportAndroidMain"),
                      (":lintAnalyzeAndroidMain",),
                      (":lintAnalyzeAndroidMain", ":lintAnalyzeAndroidMain"),
                      (":lintAnalyzeAndroidMain", ":lintReportAndroidMain", ":other"),
                      (":lintAnalyzeAndroidMain", None)):
            value = self.driver()
            value["modelBinding"]["requiredTaskPaths"] = list(paths)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))
        native = self.driver("ios", "positive", "iosArm64")
        native["modelBinding"]["requiredTaskPaths"] = [":compileKotlinIosArm64"]
        self.assertTrue(run_provider_checks.validate_driver(native, "ios", "positive", "iosArm64"))

    def test_fixture_case_membership_excludes_other_targets_and_inactive_negative_roots(self):
        positive = run_provider_checks.fixture_membership("ios", "positive", "iosArm64")
        missing = run_provider_checks.fixture_membership("ios", "missing-actual", "iosArm64")
        wrong = run_provider_checks.fixture_membership("ios", "wrong-actual", "iosSimulatorArm64")
        actual = "src/iosMain/kotlin/dev/ktchecker/fixture/Platform.kt"
        replacement = "negative/wrong-actual/iosMain/dev/ktchecker/fixture/Platform.kt"
        self.assertIn(actual, positive)
        self.assertNotIn(actual, missing)
        self.assertNotIn(actual, wrong)
        self.assertIn(replacement, wrong)
        self.assertTrue(all("androidMain" not in path and "desktopMain" not in path for path in wrong))
        for path in ("/etc/passwd", "C:/private.kt", "C:private.kt", "//host/share/private.kt", "src\\private.kt",
                     "src/../private.kt", "src//private.kt", "src/./private.kt", "src/private.kt:secret", "src/private\x00.kt"):
            self.assertFalse(run_provider_checks.relative_source_path(path), path)

    def test_findings_bind_exact_file_source_set_and_bytes_not_merely_target(self):
        original = self.driver(case="new-api")
        for mutate in (
            lambda row: row["providerResult"]["findings"][0]["context"].update(sourceSet="commonMain"),
            lambda row: row["providerResult"]["findings"][0]["context"].update(module=":other"),
            lambda row: row["providerResult"]["findings"][0]["context"].update(compilation="main"),
            lambda row: row["providerResult"]["findings"][0]["location"].update(fileDigest="sha256:" + "9" * 64),
            lambda row: row["providerResult"]["findings"][0]["location"].update(path="src/desktopMain/kotlin/dev/ktchecker/fixture/Platform.kt"),
        ):
            value = copy.deepcopy(original)
            mutate(value)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "new-api", "android"))

    def test_native_source_origin_does_not_expand_selected_analysis_scope(self):
        value = self.driver("ios", "wrong-actual", "iosArm64")
        path = "negative/wrong-actual/iosMain/dev/ktchecker/fixture/Platform.kt"
        value["providerResult"]["findings"][0]["location"].update(
            path=path, fileDigest=ci_common.digest_file(run_provider_checks.FIXTURE_ROOT / path))
        self.assertEqual("iosMain", value["modelBinding"]["sourceSetByFile"][path])
        self.assertEqual("commonMain", value["providerResult"]["findings"][0]["context"]["sourceSet"])
        self.assertEqual([], run_provider_checks.validate_driver(value, "ios", "wrong-actual", "iosArm64"))
        value["providerResult"]["findings"][0]["context"]["sourceSet"] = "iosMain"
        self.assertTrue(run_provider_checks.validate_driver(value, "ios", "wrong-actual", "iosArm64"))

    def test_driver_json_rejects_duplicate_keys_invalid_utf8_and_nonfinite_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "driver.json"
            for raw in (b'{"state":"failed","state":"passed"}', b'{"secret":"\xff"}', b'{"metric":NaN}',
                        b'{"modelBinding":{"scope":{},"scope":null}}'):
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    run_provider_checks.read_driver(path)

    def test_exact_provider_version_target_and_snapshot_are_required(self):
        original = self.driver()
        for mutate in (
            lambda row: row.update(schemaVersion=True),
            lambda row: row.update(snapshotId="mutable-worktree"),
            lambda row: row.update(target="iosArm64"),
            lambda row: row["providerResult"].update(providerVersion="32.5.0"),
            lambda row: row["providerResult"]["coverage"][0]["context"].update(target="jvm"),
            lambda row: row["artifactIdentities"][0].update(digest=None),
        ):
            value = copy.deepcopy(original)
            mutate(value)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))

    def test_execution_certificate_never_grants_rule_qualification(self):
        for changes in ({"qualified": True}, {"qualified": 0}, {"provenance": "not_executed"},
                        {"provenance": "reused_with_proof"}, {"certificateId": None}, {"state": "passed"}):
            value = self.driver()
            value["providerResult"]["coverage"][0].update(changes)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))
        value = self.driver()
        value["providerResult"]["status"] = "complete"
        self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))

    def test_negative_requires_exact_canonical_id_and_captured_location(self):
        original = self.driver(case="new-api")
        for changes in ({"ruleId": "invented"}, {"upstreamId": "HardcodedText"}, {"severity": "warning"},
                        {"location": None}, {"location": {"path": "../../private.kt", "fileDigest": None}},
                        {"location": {"path": "src/Other.kt", "fileDigest": "sha256:" + "5" * 64, "line": 1}}):
            value = copy.deepcopy(original)
            value["providerResult"]["findings"][0].update(changes)
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "new-api", "android"))

    def test_malformed_evidence_is_an_error_not_a_sanitizer_crash(self):
        for value in ([], None, {"providerResult": []}, {"artifactIdentities": None, "providerResult": {"findings": None}}):
            self.assertTrue(run_provider_checks.validate_driver(value, "android", "positive", "android"))
            if isinstance(value, dict):
                run_provider_checks.sanitized_driver(value)
        value = self.driver(case="new-api")
        value["providerResult"]["findings"][0]["location"] = None
        self.assertEqual({}, run_provider_checks.sanitized_driver(value)["providerResult"]["findings"][0]["location"])

    def test_uploaded_driver_omits_raw_source_diagnoses_and_unknown_nested_fields(self):
        value = self.driver(case="new-api")
        value["providerResult"]["rawDiagnostics"] = [{"message": "PRIVATE raw diagnostic"}]
        finding = value["providerResult"]["findings"][0]
        finding.update(title="PRIVATE title", diagnosis="PRIVATE diagnosis", evidence=[{"summary": "PRIVATE source"}])
        finding["location"]["excerpt"] = "PRIVATE excerpt"
        finding["context"]["transcript"] = "PRIVATE context"
        binding = value["modelBinding"]
        binding["transcript"] = "PRIVATE model"
        binding["scope"]["transcript"] = "PRIVATE scope"
        binding["sourceSetByFile"]["PRIVATE unsubmitted source.kt"] = "PRIVATE set"
        value["providerResult"]["metrics"] = {"outputBytes": 12, "transcript": "PRIVATE output"}
        value["artifactIdentities"][0]["raw"] = "PRIVATE artifact"
        sanitized = run_provider_checks.sanitized_driver(value)
        self.assertNotIn("PRIVATE", json.dumps(sanitized))
        self.assertTrue(sanitized["source_excerpts_messages_and_raw_diagnostics_omitted"])
        self.assertEqual(run_provider_checks.MODEL_PROVENANCE, sanitized["modelBinding"]["provenance"])
        self.assertEqual(run_provider_checks.fixture_membership("android", "new-api", "android"), sanitized["modelBinding"]["sourceSetByFile"])

    def test_unavailable_prerequisites_never_invoke_gradle(self):
        with tempfile.TemporaryDirectory() as temporary, \
             patch.object(run_provider_checks, "preflight", return_value={"state": "unavailable", "errors": ["No arm64 Mac"]}), \
             patch.object(run_provider_checks, "source_identity", return_value=IDENTITY), \
             patch.object(run_provider_checks, "gradle_result") as gradle, \
             patch.dict(os.environ, {"JAVA_HOME": "/public/project-jdk"}):
            args = argparse.Namespace(profile="ios", output=Path(temporary) / "provider.json", timeout=1)
            value = run_provider_checks.execute(args)
            self.assertEqual("unavailable", value["state"])
            self.assertTrue(args.output.is_file())
            gradle.assert_not_called()

    def test_nested_fixture_receives_project_java_home_and_actual_artifact_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "tool.jar"
            artifact.write_bytes(b"actual tool artifact")

            def gradle(arguments, **_):
                self.assertIn(f"-PplatformJavaHome={Path('/public/project-jdk').resolve()}", arguments)
                self.assertIn("-Porg.gradle.java.installations.fromEnv=JAVA_HOME,JAVA_HOME_21_X64,JAVA_HOME_21_ARM64", arguments)
                self.assertIn("-PplatformLintAnalysisTask=:lintAnalyzeAndroidMain", arguments)
                self.assertIn("-PplatformLintReportTask=:lintReportAndroidMain", arguments)
                case = next(arg.split("=", 1)[1] for arg in arguments if arg.startswith("-PplatformCase="))
                path = Path(next(arg.split("=", 1)[1] for arg in arguments if arg.startswith("-PplatformResult=")))
                value = self.driver(case=case)
                value["artifactIdentities"][0].update(path=str(artifact), digest=ci_common.digest_bytes(b"wrong tool artifact"))
                ci_common.write_json(path, value)
                return {"state": "passed", "errors": [], "tasks": {":provider-lint:lintFixture": "EXECUTED"}}, ""

            with patch.object(run_provider_checks, "preflight", return_value={"state": "passed", "errors": []}), \
                 patch.object(run_provider_checks, "source_identity", return_value=IDENTITY), \
                 patch.object(run_provider_checks, "gradle_result", side_effect=gradle), \
                 patch.dict(os.environ, {"JAVA_HOME": "/public/project-jdk"}):
                result = run_provider_checks.execute(argparse.Namespace(profile="android", output=root / "provider.json", timeout=1))
            self.assertEqual("failed", result["state"])
            self.assertEqual(2, len(result["stages"]))
            self.assertTrue(all(any("artifact bytes differ" in error for error in stage["errors"]) for stage in result["stages"]))


if __name__ == "__main__":
    unittest.main()
