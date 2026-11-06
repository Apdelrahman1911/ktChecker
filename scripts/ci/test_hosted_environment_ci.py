"""Synthetic public-context controls; no hosted, tool or target execution evidence."""

import argparse
import copy
import json
import os
from pathlib import Path
import re
import shlex
import sys
import unittest
from unittest.mock import patch

try:
    from . import record_environment
except ImportError:
    import record_environment


ROOT = Path(__file__).resolve().parents[2]
IDENTITY = {"commit": "a" * 40, "dirty": False, "working_tree_digest": "sha256:" + "d" * 64}
HOST = {"os": "Linux", "arch": "x86_64", "release": "synthetic", "python": "3.12.0"}
RUNNER = {"RUNNER_OS": "Linux", "RUNNER_ARCH": "X64", "RUNNER_ENVIRONMENT": "github-hosted",
          "ImageOS": "synthetic-image", "ImageVersion": "synthetic.1"}
CI = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "fixture/repository", "GITHUB_RUN_ID": "123",
      "GITHUB_RUN_ATTEMPT": "2", "GITHUB_JOB": "checker-host", "GITHUB_EVENT_NAME": "pull_request",
      "GITHUB_SHA": "a" * 40, "GITHUB_WORKFLOW_SHA": "b" * 40,
      "GITHUB_WORKFLOW_REF": "fixture/repository/.github/workflows/implementation.yml@refs/pull/7/merge"}
OMITTED = object()


class HostedEnvironmentContextTests(unittest.TestCase):
    def errors(self, *, source=None, host=None, runner=None, ci=None):
        return record_environment.ci_context_errors(
            IDENTITY if source is None else source, HOST if host is None else host,
            RUNNER if runner is None else runner, CI if ci is None else ci)

    def test_complete_context_accepts_each_checker_host(self):
        for runner_os, host_os, arch in (("Linux", "Linux", "X64"), ("Windows", "Windows", "X64"),
                                        ("macOS", "Darwin", "ARM64")):
            with self.subTest(runner_os=runner_os):
                self.assertEqual([], self.errors(host={**HOST, "os": host_os},
                                                 runner={**RUNNER, "RUNNER_OS": runner_os, "RUNNER_ARCH": arch}))

    def test_every_required_public_field_must_be_present(self):
        for group, original in (("runner", RUNNER), ("ci", CI)):
            for key in original:
                with self.subTest(group=group, key=key):
                    values = dict(original)
                    del values[key]
                    self.assertEqual([f"Missing or invalid required CI context: {key}"],
                                     self.errors(**{group: values}))

    def test_empty_and_nontext_fields_fail_without_type_errors(self):
        for group, original in (("runner", RUNNER), ("ci", CI)):
            for key in original:
                for value in ("", None, False, 1, []):
                    with self.subTest(group=group, key=key, value=value):
                        self.assertIn(f"Missing or invalid required CI context: {key}",
                                      self.errors(**{group: {**original, key: value}}))

    def test_runner_origin_and_actions_markers_are_exact(self):
        for group, key, value in (("runner", "RUNNER_ENVIRONMENT", "self-hosted"),
                                  ("runner", "RUNNER_ENVIRONMENT", "GITHUB-HOSTED"),
                                  ("ci", "GITHUB_ACTIONS", "false"), ("ci", "GITHUB_ACTIONS", "TRUE")):
            with self.subTest(group=group, key=key, value=value):
                original = RUNNER if group == "runner" else CI
                self.assertTrue(self.errors(**{group: {**original, key: value}}))

    def test_run_and_attempt_ids_are_bounded_positive_decimal(self):
        for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"):
            for value in ("0", "0000", "-1", "+1", " 1", "1\n", "1.0", "1" * 21, "１"):
                with self.subTest(key=key, value=value):
                    self.assertEqual([f"Missing or invalid required CI context: {key}"],
                                     self.errors(ci={**CI, key: value}))

    def test_valid_metadata_boundaries_do_not_become_version_pins(self):
        runner = {**RUNNER, "ImageOS": "x" * 128, "ImageVersion": "z" * 128}
        ci = {**CI, "GITHUB_RUN_ID": "9" * 20, "GITHUB_RUN_ATTEMPT": "0001",
              "GITHUB_JOB": "_" + "j" * 127, "GITHUB_EVENT_NAME": "e" * 128}
        self.assertEqual([], self.errors(runner=runner, ci=ci))

    def test_invalid_image_and_identity_tokens_fail_without_value_echo(self):
        mutations = (("runner", "ImageOS", "private-token\n"), ("runner", "ImageVersion", "private token"),
                     ("runner", "ImageOS", "x" * 129), ("runner", "ImageVersion", "x" * 129),
                     ("runner", "RUNNER_OS", "Darwin"), ("runner", "RUNNER_ARCH", "x86_64"),
                     ("ci", "GITHUB_JOB", "private/job"), ("ci", "GITHUB_EVENT_NAME", "private event"),
                     ("ci", "GITHUB_JOB", "x" * 129), ("ci", "GITHUB_EVENT_NAME", "x" * 129),
                     ("ci", "GITHUB_REPOSITORY", "private-only"), ("ci", "GITHUB_REPOSITORY", "a/b/c"))
        for group, key, value in mutations:
            with self.subTest(group=group, key=key):
                original = RUNNER if group == "runner" else CI
                errors = self.errors(**{group: {**original, key: value}})
                self.assertEqual([f"Missing or invalid required CI context: {key}"], errors)
                self.assertNotIn(value, json.dumps(errors))

    def test_candidate_and_workflow_shas_require_full_lowercase_hex(self):
        for key in ("GITHUB_SHA", "GITHUB_WORKFLOW_SHA"):
            for value in ("a" * 39, "a" * 41, "A" * 40, "g" * 40, "a" * 40 + "\n"):
                with self.subTest(key=key, value=value):
                    self.assertEqual([f"Missing or invalid required CI context: {key}"],
                                     self.errors(ci={**CI, key: value}))

    def test_candidate_sha_must_match_observed_checkout_commit(self):
        for commit in ("c" * 40, None, "", "sha256:" + "d" * 64):
            with self.subTest(commit=commit):
                self.assertEqual(["Required CI GITHUB_SHA does not match observed checkout commit"],
                                 self.errors(source={**IDENTITY, "commit": commit}))

    def test_workflow_commit_may_legitimately_differ_from_candidate(self):
        self.assertNotEqual(CI["GITHUB_SHA"], CI["GITHUB_WORKFLOW_SHA"])
        self.assertEqual([], self.errors())
        self.assertEqual([], self.errors(ci={**CI, "GITHUB_WORKFLOW_SHA": CI["GITHUB_SHA"]}))

    def test_workflow_ref_accepts_independent_repository_and_unicode_ref(self):
        ci = {**CI, "GITHUB_WORKFLOW_REF": "fixture/other/.github/workflows/validation.yml@refs/heads/δοκιμή"}
        self.assertEqual([], self.errors(ci=ci))

    def test_workflow_ref_requires_bounded_printable_path_and_ref(self):
        for value in ("fixture/repository@refs/heads/main", "fixture/repository/.github/workflows/@main",
                      "fixture/repository/.github/workflows/validation.yml@",
                      CI["GITHUB_WORKFLOW_REF"] + "\n", CI["GITHUB_WORKFLOW_REF"] + "\x00", "x" * 1025):
            with self.subTest(value=value):
                self.assertEqual(["Missing or invalid required CI context: GITHUB_WORKFLOW_REF"],
                                 self.errors(ci={**CI, "GITHUB_WORKFLOW_REF": value}))

    def test_runner_os_must_agree_with_observed_host(self):
        for runner_os, host_os in (("Linux", "Windows"), ("Windows", "Linux"),
                                   ("macOS", "Linux"), ("Linux", "unknown")):
            with self.subTest(runner_os=runner_os, host_os=host_os):
                self.assertEqual(["Required CI RUNNER_OS does not match observed host OS"],
                                 self.errors(host={**HOST, "os": host_os},
                                             runner={**RUNNER, "RUNNER_OS": runner_os}))

    def test_context_validation_does_not_mutate_observations(self):
        arguments = [copy.deepcopy(value) for value in (IDENTITY, HOST, RUNNER, CI)]
        before = copy.deepcopy(arguments)
        self.assertEqual([], record_environment.ci_context_errors(*arguments))
        self.assertEqual(before, arguments)

    def _record(self, env, *, required=OMITTED, source=None, java_version="21.0.12"):
        args = argparse.Namespace(gradle_result=None, require_java_major=21, require_gradle=None,
                                  require_xcode=None, require_xcode_build=None)
        if required is not OMITTED:
            args.require_ci_context = required
        fake_home = Path(__file__).resolve().parent / ".synthetic-uninstalled-jdk-for-context-tests"
        self.assertFalse(fake_home.exists())
        fake_java = str(fake_home / "bin" / ("java.exe" if os.name == "nt" else "java"))
        with patch.dict(os.environ, env, clear=True), \
             patch.object(record_environment, "source_identity", return_value=IDENTITY if source is None else source) as identity, \
             patch.object(record_environment, "wrapper_profile", return_value={"state": "pinned"}), \
             patch.object(record_environment, "probe", return_value=(0, f'openjdk version "{java_version}"\n')) as probe, \
             patch.object(record_environment, "observe_xcode", return_value={"state": "unavailable"}), \
             patch.object(record_environment, "installed_packages", return_value=[]), \
             patch.object(record_environment, "digest_file") as digest, \
             patch.object(record_environment, "utc_now", return_value="2000-01-01T00:00:00+00:00"), \
             patch.object(record_environment.shutil, "which", side_effect=lambda name: fake_java if name == "java" else None), \
             patch.object(record_environment.platform, "system", return_value=HOST["os"]), \
             patch.object(record_environment.platform, "release", return_value=HOST["release"]), \
             patch.object(record_environment.platform, "machine", return_value=HOST["arch"]), \
             patch.object(record_environment.platform, "python_version", return_value=HOST["python"]):
            result = record_environment.record(args)
        identity.assert_called_once_with()
        probe.assert_called_once_with([fake_java, "-version"])
        digest.assert_not_called()
        return result

    def test_local_namespace_without_new_flag_still_passes(self):
        result = self._record({})
        self.assertEqual("passed", result["state"])
        self.assertIs(False, result["ci_context_required"])
        self.assertEqual({}, result["runner"])
        self.assertEqual({}, result["ci"])

    def test_explicit_local_opt_out_preserves_tool_only_behavior(self):
        result = self._record({"GITHUB_SHA": "not-a-commit", "ImageVersion": ""}, required=False)
        self.assertEqual("passed", result["state"])
        self.assertIs(False, result["ci_context_required"])
        self.assertEqual([], result["errors"])

    def test_hosted_record_fails_closed_when_context_is_missing(self):
        result = self._record({}, required=True)
        self.assertEqual("failed", result["state"])
        self.assertIs(True, result["ci_context_required"])
        self.assertEqual(len(RUNNER) + len(CI), len(result["errors"]))
        self.assertEqual(IDENTITY, result["source"])
        self.assertEqual(HOST, result["host"])

    def test_hosted_record_requires_image_and_exact_candidate_commit(self):
        for removed in ("ImageOS", "ImageVersion", "GITHUB_SHA"):
            with self.subTest(removed=removed):
                env = {**RUNNER, **CI}
                del env[removed]
                result = self._record(env, required=True)
                self.assertEqual("failed", result["state"])
                self.assertIn(f"Missing or invalid required CI context: {removed}", result["errors"])
        result = self._record({**RUNNER, **CI}, required=True, source={**IDENTITY, "commit": "c" * 40})
        self.assertEqual("failed", result["state"])
        self.assertEqual(["Required CI GITHUB_SHA does not match observed checkout commit"], result["errors"])

    def test_hosted_record_retains_only_explicit_public_context(self):
        java_home = str(Path(__file__).resolve().parent / ".synthetic-uninstalled-jdk-for-context-tests")
        result = self._record({**RUNNER, **CI, "JAVA_HOME": java_home,
                               "TOKEN_SECRET": "secret-sentinel", "GITHUB_TOKEN": "token-sentinel",
                               "GITHUB_WORKSPACE": "workspace-sentinel"}, required=True)
        self.assertEqual("passed", result["state"])
        self.assertIs(True, result["ci_context_required"])
        self.assertEqual(RUNNER, result["runner"])
        self.assertEqual(CI, result["ci"])
        self.assertEqual({"JAVA_HOME": java_home}, result["tool_paths"])
        for value in ("TOKEN_SECRET", "GITHUB_TOKEN", "secret-sentinel", "token-sentinel", "workspace-sentinel"):
            self.assertNotIn(value, json.dumps(result))
        self.assertEqual("not_performed", result["actual_codex_host_observation"]["state"])
        self.assertEqual("Tool availability only; no target execution or rule qualification implied", result["qualification"])

    def test_complete_context_cannot_override_a_failed_tool_requirement(self):
        result = self._record({**RUNNER, **CI}, required=True, java_version="25.0.1")
        self.assertEqual("failed", result["state"])
        self.assertEqual(["Observed Java major 25; required 21"], result["errors"])

    def test_cli_opt_in_and_existing_exit_status_are_preserved(self):
        for required, state, expected in ((True, "passed", 0), (True, "failed", 1), (False, "passed", 0)):
            with self.subTest(required=required, state=state):
                output = ROOT / "synthetic-environment.json"
                argv = ["record_environment.py", "--output", str(output)]
                if required:
                    argv.append("--require-ci-context")
                result = {"state": state, "errors": [] if state == "passed" else ["synthetic failure"]}
                with patch.object(sys, "argv", argv), patch.object(record_environment, "record", return_value=result) as record, \
                     patch.object(record_environment, "write_json") as write, patch("builtins.print"):
                    self.assertEqual(expected, record_environment.main())
                self.assertIs(required, record.call_args.args[0].require_ci_context)
                write.assert_called_once_with(output, result)


class HostedEnvironmentWorkflowTests(unittest.TestCase):
    def _assert_calls(self, text, filename):
        initial = "python scripts/ci/record_environment.py --output build/ci-evidence/environment-initial.json --require-java-major 21"
        bound = "python scripts/ci/record_environment.py --output build/ci-evidence/environment.json --require-java-major {} --require-gradle 9.6.1 --gradle-result build/ci-evidence/gradle-version.json"
        if filename == "implementation.yml":
            expected = [("Record initial host and pinned wrapper", initial, False),
                        ("Bind observed Gradle to the selected JVM", bound.format(21), True)]
        else:
            ios = ("python scripts/ci/record_environment.py --output build/ci-evidence/environment.json --require-ci-context "
                   "--require-java-major 25 --require-gradle 9.6.1 --gradle-result build/ci-evidence/gradle-version.json "
                   "--require-xcode 26.4.1 --require-xcode-build 17E202")
            expected = [("Record actual environment, even on preflight failure", bound.format(25), True),
                        ("Record actual environment, even on preflight failure", bound.format(25), True),
                        ("Verify selected Xcode version/build and record actual SDK/runtime inventory", ios, True)]
        selected = []
        for name, body in re.findall(r"^      - name: ([^\n]+)\n(.*?)(?=^      - name: |\Z)", text, re.M | re.S):
            if "scripts/ci/record_environment.py" not in body:
                continue
            runs = re.findall(r"^        run: ([^\n]+)(?:\n((?:          [^\n]*\n)*))?", body, re.M)
            self.assertEqual(1, len(runs))
            inline, folded = runs[0]
            command = folded if inline == ">-" else inline
            self.assertNotIn("continue-on-error:", body)
            self.assertEqual(1, command.count("--require-ci-context"))
            selected.append((name, shlex.split(command), re.findall(r"^        if: ([^\n]+)$", body, re.M)))
        self.assertEqual(len(expected), text.count("scripts/ci/record_environment.py"))
        self.assertEqual(len(expected), len(selected))
        for actual, (name, command, always) in zip(selected, expected):
            if "--require-ci-context" not in command:
                command += " --require-ci-context"
            self.assertEqual((name, shlex.split(command), ["always()"] if always else []), actual)

    def test_exact_five_existing_calls_require_context_without_tool_scope_changes(self):
        for filename in ("implementation.yml", "platform-validation.yml"):
            with self.subTest(filename=filename):
                self._assert_calls((ROOT / ".github/workflows" / filename).read_text(encoding="utf-8"), filename)

    def test_workflow_controls_reject_removed_duplicate_and_softened_gates(self):
        for filename in ("implementation.yml", "platform-validation.yml"):
            original = (ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
            recorder_line = next(line for line in original.splitlines() if "python scripts/ci/record_environment.py" in line)
            mutations = (original.replace(recorder_line, recorder_line.replace(" --require-ci-context", "", 1), 1),
                         original.replace(recorder_line, recorder_line.replace(" --require-ci-context", " --require-ci-context --require-ci-context", 1), 1),
                         original.replace("run: python scripts/ci/record_environment.py", "continue-on-error: true\n        run: python scripts/ci/record_environment.py", 1),
                         original.replace("scripts/ci/record_environment.py --output", "scripts/ci/record_environment.py --unknown-option --output", 1),
                         original.replace("        run: python scripts/ci/record_environment.py", "        if: false\n        run: python scripts/ci/record_environment.py", 1))
            for index, mutation in enumerate(mutations):
                with self.subTest(filename=filename, mutation=index):
                    self.assertNotEqual(original, mutation)
                    with self.assertRaises(AssertionError):
                        self._assert_calls(mutation, filename)


if __name__ == "__main__":
    unittest.main()
