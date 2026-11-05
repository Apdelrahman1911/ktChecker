"""Authored workflow wiring and synthetic collector/license-gate tests, not hosted execution."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import stat
import tempfile
import unittest
from unittest.mock import patch

try:
    from . import ci_common, collect_evidence, report_distribution
    from . import test_distribution_archives_ci as archive_fixtures
except ImportError:
    import ci_common
    import collect_evidence
    import report_distribution
    import test_distribution_archives_ci as archive_fixtures


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
MODULES = ("kernel", "project-model", "provider-architecture", "review-controller")
REPORTS = tuple(f"modules/{module}/build/test-results/test/*.xml" for module in MODULES) + (
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.KernelPlanIntegrationTest.xml",
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.KernelStressTest.xml",
)
STRESS = "modules/kernel/build/reports/stress/kernel-100-patches.json"
PYTHON_REPORTS = ("build/ci-evidence/python/python-checks.json",
                  "build/ci-evidence/python/python-checks.xml")
LIVE_REPORTS = ("build/ci-evidence/live-supervisor/live-supervisor-checks.json",
                "build/ci-evidence/live-supervisor/live-supervisor-checks.xml")
LIVE_PROBE_RECEIPT = "build/ci-evidence/live-probe/receipt.json"
# These additive model profiles are pure controls, not live compiler/Native gates.
MODEL_TASKS = (":worker-protocol:modelWireTest", ":provider-compiler:modelWorkerTest")
MODEL_REPORTS = (
    "modules/worker-protocol/build/test-results/modelWireTest/TEST-dev.ktchecker.workerprotocol.CompilerModelWireTest.xml",
    "modules/provider-compiler/build/test-results/modelWorkerTest/TEST-dev.ktchecker.compilerworker.live.CompilerModelApplicationFilesystemTest.xml",
    "modules/provider-compiler/build/test-results/modelWorkerTest/TEST-dev.ktchecker.compilerworker.live.CompilerModelApplicationLifecycleTest.xml",
)
# B1 reports describe bounded JVM controls, not wrapper-entrypoint or Native qualification.
BUILD_CONTRACTS_TASKS = (":provider-build-contracts:test",)
BUILD_CONTRACTS_REPORTS = (
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.GradleWrapperChecksumProviderTest.xml",
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.InstalledWrapperContextTest.xml",
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperActivationPolicyBoundsTest.xml",
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperPropertiesTest.xml",
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperChecksumConsumerIntegrationTest.xml",
    "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperRulePolicyIntegrationTest.xml",
)
# Joint barrier reports are local JVM controls, not real owner/host or release-scale qualification.
COMPLETION_BARRIER_TASKS = (":kernel:test", ":review-controller:test")
COMPLETION_BARRIER_REPORTS = (
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.IntegratedCompletionBarrierTest.xml",
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.IntegratedCompletionAuditCodecTest.xml",
    "modules/review-controller/build/test-results/test/TEST-dev.ktchecker.reviewcontroller.OwnerIntegratedCompletionRegistryTest.xml",
)
APPLICATION_TASKS = (":worker-protocol:test", ":provider-compiler:test",
                     ":provider-compiler:workerTest", ":provider-runtime:test") + MODEL_TASKS + BUILD_CONTRACTS_TASKS + COMPLETION_BARRIER_TASKS
APPLICATION_REPORTS = (
    "modules/worker-protocol/build/test-results/test/TEST-dev.ktchecker.workerprotocol.ApplicationWireTest.xml",
    "modules/worker-protocol/build/test-results/test/TEST-dev.ktchecker.workerprotocol.CompilerJobWireTest.xml",
    "modules/provider-compiler/build/test-results/test/TEST-dev.ktchecker.compiler.live.CompilerApplicationLifecycleTest.xml",
    "modules/provider-compiler/build/test-results/test/TEST-dev.ktchecker.compiler.live.CompilerLiveHostAuthorityTest.xml",
    "modules/provider-compiler/build/test-results/test/TEST-dev.ktchecker.compiler.live.CompilerLiveRenderingTest.xml",
    "modules/provider-compiler/build/test-results/workerTest/TEST-dev.ktchecker.compilerworker.live.CompilerApplicationLifecycleTest.xml",
    "modules/provider-compiler/build/test-results/workerTest/TEST-dev.ktchecker.compilerworker.live.CompilerApplicationFilesystemTest.xml",
    "modules/provider-runtime/build/test-results/test/TEST-dev.ktchecker.providers.runtime.OwnedApplicationSessionsTest.xml",
    "modules/provider-runtime/build/test-results/test/TEST-dev.ktchecker.providers.runtime.OwnedApplicationTypesTest.xml",
) + MODEL_REPORTS + BUILD_CONTRACTS_REPORTS + COMPLETION_BARRIER_REPORTS


def authored_command(document: str, name: str) -> list[str]:
    """Read only a fixed authored run stanza; actionlint validates actual YAML.

    This is not a general YAML parser and never expands/executes expressions.
    """
    step = re.search(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - name: |\Z)",
                     document, re.MULTILINE)
    if not step:
        raise AssertionError(f"Required authored step absent: {name}")
    run = re.search(r"^        run: >-\n((?:          [^\n]*\n)+)", step[1], re.MULTILINE)
    if not run:
        raise AssertionError(f"Required folded command absent: {name}")
    return shlex.split(" ".join(line.strip() for line in run[1].splitlines()))


def values(command: list[str], option: str) -> list[str]:
    return [command[index + 1] for index, value in enumerate(command) if value == option]


class ImplementationWorkflowTests(unittest.TestCase):
    def document(self):
        return (ci_common.REPO_ROOT / ".github/workflows/implementation.yml").read_text(encoding="utf-8")

    def test_new_module_and_kernel_schema2_tasks_must_execute_without_test_filters(self):
        command = authored_command(self.document(), "Build and test actual modules; assemble preview distributions")
        required = values(command, "--require-task")
        for module in MODULES:
            self.assertIn(f":{module}:test", required)
        self.assertIn("test", command[command.index("--") + 1:])
        self.assertIn("--rerun-tasks", command)
        self.assertIn("--no-build-cache", command)
        self.assertNotIn("--tests", command)
        # These remain separate opt-in JavaExecs, not implied owner/Native runs.
        self.assertNotIn(":project-model:ownerModelFixture", command)
        self.assertNotIn(":provider-native:nativeThrowsFixture", command)

    def test_required_module_contexts_and_stress_digest_are_retained(self):
        document = self.document()
        command = authored_command(document, "Collect actual suites and artifact identities, including failures")
        for pattern in REPORTS:
            self.assertIn(pattern, values(command, "--require-report"))
        self.assertIn(STRESS, values(command, "--require-artifact"))
        self.assertIn("--require-tests", command)
        self.assertIn("modules/kernel/build/reports/stress/*.json", document)


class ImplementationApplicationWorkflowTests(unittest.TestCase):
    """Real authored stanzas and synthetic task statuses, not suite/hosted passes."""

    build_name = "Build and test actual modules; assemble preview distributions"
    collector_name = "Collect actual suites and artifact identities, including failures"
    failure_steps = (
        "Retain direct original owned live supervisor controls on every host",
        collector_name,
        "Retain sanitized observed evidence (not terminal transcripts)",
        "Retain original controlled-fixture JUnit and kernel stress reports",
    )

    def document(self):
        return (ci_common.REPO_ROOT / ".github/workflows/implementation.yml").read_text(encoding="utf-8")

    def step(self, document, name):
        rows = re.findall(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - name: |\Z)",
                          document, re.MULTILINE)
        self.assertEqual(1, len(rows), name)
        return rows[0]

    def assert_task_gate(self, document):
        step = self.step(document, self.build_name)
        self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
        command = authored_command(document, self.build_name)
        self.assertEqual(["python", "scripts/ci/run_gradle.py"], command[:2])
        self.assertEqual(1, command.count("--"))
        boundary = command.index("--")
        wrapper, arguments = command[:boundary], command[boundary + 1:]
        self.assertEqual(["build/ci-evidence/host-build.json"], values(wrapper, "--output"))
        self.assertEqual(["1200"], values(wrapper, "--timeout"))
        required = values(wrapper, "--require-task")
        for task in APPLICATION_TASKS:
            self.assertEqual(1, required.count(task), task)
        for task in MODEL_TASKS + BUILD_CONTRACTS_TASKS:
            self.assertEqual(1, arguments.count(task), task)
        for value in ("--rerun-tasks", "--no-build-cache", "test"):
            self.assertEqual(1, arguments.count(value), value)
        self.assertFalse(any(value in ("--tests", "--exclude-task", "-x") or
                             value.startswith(("--tests=", "--exclude-task=", "-x"))
                             for value in arguments))
        return tuple(task for task in required if task in APPLICATION_TASKS), arguments

    def assert_report_gate(self, document):
        step = self.step(document, self.collector_name)
        self.assertEqual(["always()"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertNotIn("continue-on-error:", step)
        command = authored_command(document, self.collector_name)
        self.assertEqual(["python", "scripts/ci/collect_evidence.py"], command[:2])
        self.assertNotIn("--", command)
        self.assertEqual(["build/ci-evidence/evidence.json"], values(command, "--output"))
        self.assertEqual(["build/ci-evidence/environment.json"], values(command, "--environment"))
        self.assertEqual(["modules/*/build/test-results/**/*.xml", "tests/audit/build/test-results/**/*.xml"],
                         values(command, "--reports"))
        required = values(command, "--require-report")
        self.assertEqual(21, len(APPLICATION_REPORTS))
        for report in APPLICATION_REPORTS:
            self.assertEqual(1, required.count(report), report)
        self.assertEqual(1, required.count("modules/provider-runtime/build/test-results/test/*.xml"))
        self.assertEqual(1, command.count("--require-tests"))
        self.assertEqual(1, values(command, "--command-result").count("build/ci-evidence/host-build.json"))

    def assert_step_mutation_rejected(self, document, name, old, new, assertion):
        step = self.step(document, name)
        self.assertEqual(1, step.count(old), old)
        changed = step.replace(old, new, 1)
        self.assertNotEqual(step, changed)
        with self.assertRaises(AssertionError):
            assertion(document.replace(step, changed, 1))

    def test_exact_wire_host_worker_and_runtime_tasks_are_required_before_gradle_arguments(self):
        self.assert_task_gate(self.document())

    def test_twenty_one_exact_class_reports_are_required_by_the_real_collector_stanza(self):
        self.assert_report_gate(self.document())

    def test_each_required_task_removal_or_gradle_only_forwarding_is_rejected(self):
        document = self.document()
        self.assert_task_gate(document)
        for task in APPLICATION_TASKS:
            old = "--require-task " + task
            with self.subTest(task=task, mutation="removal"):
                self.assert_step_mutation_rejected(document, self.build_name, old, "", self.assert_task_gate)
            with self.subTest(task=task, mutation="after-delimiter"):
                step = self.step(document, self.build_name)
                changed = step.replace(old, "", 1).replace("-- --rerun-tasks", "-- " + old + " --rerun-tasks", 1)
                self.assertNotEqual(step, changed)
                with self.assertRaises(AssertionError):
                    self.assert_task_gate(document.replace(step, changed, 1))

    def test_each_additive_gradle_argument_omission_duplicate_or_wrong_task_is_rejected(self):
        document = self.document()
        self.assert_task_gate(document)
        step = self.step(document, self.build_name)
        self.assertEqual(1, step.count("-- --rerun-tasks"))
        wrapper, delimiter, arguments = step.partition("-- --rerun-tasks")
        for task in MODEL_TASKS + BUILD_CONTRACTS_TASKS:
            old = " " + task
            self.assertEqual(1, arguments.count(old), task)
            wrong_task = task + "NotRequired"
            self.assertNotEqual(task, wrong_task)
            for replacement in ("", old + old, " " + wrong_task):
                with self.subTest(task=task, replacement=replacement):
                    changed = wrapper + delimiter + arguments.replace(old, replacement, 1)
                    self.assertNotEqual(step, changed)
                    with self.assertRaises(AssertionError):
                        self.assert_task_gate(document.replace(step, changed, 1))

    def test_each_required_report_removal_or_scan_only_demotion_is_rejected(self):
        document = self.document()
        self.assert_report_gate(document)
        for report in APPLICATION_REPORTS:
            old = '--require-report "' + report + '"'
            for new in ("", '--reports "' + report + '"'):
                with self.subTest(report=report, replacement=new):
                    self.assert_step_mutation_rejected(document, self.collector_name, old, new,
                                                       self.assert_report_gate)

    def test_new_checksum_policy_and_audit_report_requirements_cannot_be_duplicated_or_widened(self):
        document = self.document()
        self.assert_report_gate(document)
        new_reports = (
            "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperChecksumConsumerIntegrationTest.xml",
            "modules/provider-build-contracts/build/test-results/test/TEST-dev.ktchecker.providers.buildcontracts.WrapperRulePolicyIntegrationTest.xml",
            "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.IntegratedCompletionAuditCodecTest.xml",
        )
        for report in new_reports:
            self.assertIn(report, APPLICATION_REPORTS)
            old = '--require-report "' + report + '"'
            parent = report.rsplit("/", 1)[0]
            replacements = (
                ("duplicate", old + " " + old),
                ("directory-wildcard", '--require-report "' + parent + '/*.xml"'),
                ("neighbor-class", '--require-report "' + parent + '/TEST-synthetic.neighbor.xml"'),
                ("other-task-directory", '--require-report "' + report.replace(
                    "/test-results/test/", "/test-results/otherTest/") + '"'),
            )
            for mutation, replacement in replacements:
                with self.subTest(report=report, mutation=mutation):
                    self.assert_step_mutation_rejected(document, self.collector_name, old, replacement,
                                                       self.assert_report_gate)

    def test_runner_flags_and_receipt_outputs_cannot_be_removed_or_redirected(self):
        document = self.document()
        self.assert_task_gate(document)
        self.assert_report_gate(document)
        controls = (
            (self.build_name, self.assert_task_gate, (
                ("python scripts/ci/run_gradle.py", "python scripts/ci/run_python_checks.py"),
                ("--output build/ci-evidence/host-build.json", "--output build/ci-evidence/other-build.json"),
                ("--timeout 1200", "--timeout 2400"),
                ("--rerun-tasks ", ""), ("--no-build-cache test", "test"),
            )),
            (self.collector_name, self.assert_report_gate, (
                ("python scripts/ci/collect_evidence.py", "python scripts/ci/run_python_checks.py"),
                ("--output build/ci-evidence/evidence.json", "--output build/ci-evidence/other-evidence.json"),
                ("--environment build/ci-evidence/environment.json", "--environment build/ci-evidence/other-environment.json"),
                ("--require-tests", ""),
                ("--command-result build/ci-evidence/host-build.json", ""),
            )),
        )
        for name, assertion, mutations in controls:
            for old, new in mutations:
                with self.subTest(step=name, mutation=old):
                    self.assert_step_mutation_rejected(document, name, old, new, assertion)

    def synthetic_task_receipt(self, required, arguments, changed_task=None, outcome="EXECUTED"):
        # Minimal task-status contract only: no JUnit suites, provider/live runs,
        # subprocess, actual Gradle command, files or hosted receipt are created.
        output = "\n".join(
            "> Task " + task + (" " + outcome if task == changed_task and outcome != "EXECUTED" else "")
            for task in required if not (task == changed_task and outcome == "absent")
        ) + "\n"
        with patch.object(ci_common, "wrapper_argv", return_value=["synthetic-wrapper-not-executed"]), \
             patch.object(ci_common, "run_logged", return_value=({"state": "completed", "return_code": 0}, output, "")):
            result, _ = ci_common.gradle_result(arguments, log_base=Path("unused-synthetic-task-receipt"),
                                                require_tasks=required)
        return result

    def test_only_synthetic_bare_task_markers_qualify_as_executed_receipts(self):
        required, arguments = self.assert_task_gate(self.document())
        result = self.synthetic_task_receipt(required, arguments)
        self.assertEqual("passed", result["state"])
        self.assertEqual(list(required), result["required_tasks"])
        self.assertEqual({task: "EXECUTED" for task in required}, result["tasks"])

    def test_each_synthetic_absent_cached_skipped_nosource_or_failed_task_is_rejected(self):
        required, arguments = self.assert_task_gate(self.document())
        for task in required:
            for outcome in ("absent", "UP-TO-DATE", "FROM-CACHE", "NO-SOURCE", "SKIPPED", "FAILED"):
                with self.subTest(task=task, outcome=outcome):
                    result = self.synthetic_task_receipt(required, arguments, task, outcome)
                    self.assertEqual("failed", result["state"])
                    self.assertIn(f"Required task did not execute: {task} ({outcome})", result["errors"])

    def assert_run_isolation(self, document):
        blocks = re.findall(r"^concurrency:\n((?:  [^\n]+\n)+)", document, re.MULTILINE)
        self.assertEqual(1, len(blocks))
        self.assertEqual(["group: implementation-${{ github.workflow }}-${{ github.run_id }}",
                          "cancel-in-progress: false"], [line.strip() for line in blocks[0].splitlines()])
        self.assertEqual(["false"], re.findall(r"^      fail-fast: ([^\n]+)$", document, re.MULTILINE))
        self.assertEqual(["3"], re.findall(r"^      max-parallel: ([^\n]+)$", document, re.MULTILINE))
        self.assertEqual(["30"], re.findall(r"^    timeout-minutes: ([^\n]+)$", document, re.MULTILINE))
        self.assertEqual(["ubuntu-24.04", "windows-2025", "macos-26"],
                         re.findall(r"^          - os: ([^\n]+)$", document, re.MULTILINE))
        for name in self.failure_steps:
            step = self.step(document, name)
            self.assertEqual(["always()"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE), name)
            self.assertNotIn("continue-on-error:", step)
        collector = authored_command(document, self.collector_name)
        self.assertEqual(1, values(collector, "--command-result").count("build/ci-evidence/host-build.json"))
        evidence = self.step(document, self.failure_steps[2])
        self.assertRegex(evidence, r"(?m)^            build/ci-evidence/\*\.json$")
        original = self.step(document, self.failure_steps[3])
        self.assertRegex(original, r"(?m)^            modules/\*/build/test-results/\*\*/\*\.xml$")

    def test_unique_run_group_disables_auto_cancel_and_keeps_bounded_failure_diagnostics(self):
        # Fixed source expressions only, not dispatch, scheduler evaluation or
        # every-patch review coverage; distinct run IDs isolate unrelated runs.
        self.assert_run_isolation(self.document())

    def test_run_isolation_rejects_shared_ref_groups_auto_cancel_or_removed_bounds(self):
        document = self.document()
        self.assert_run_isolation(document)
        for old, new in (
            ("group: implementation-${{ github.workflow }}-${{ github.run_id }}",
             "group: implementation-${{ github.workflow }}-${{ github.ref }}"),
            ("group: implementation-${{ github.workflow }}-${{ github.run_id }}",
             "group: implementation-${{ github.workflow }}"),
            ("cancel-in-progress: false", "cancel-in-progress: true"),
            ("max-parallel: 3", "max-parallel: 4"),
            ("timeout-minutes: 30", "timeout-minutes: 90"),
        ):
            with self.subTest(mutation=old, replacement=new):
                self.assertEqual(1, document.count(old), old)
                changed = document.replace(old, new, 1)
                with self.assertRaises(AssertionError):
                    self.assert_run_isolation(changed)

    def test_failure_receipt_collection_and_retention_cannot_be_success_only(self):
        document = self.document()
        self.assert_run_isolation(document)
        for name in self.failure_steps:
            with self.subTest(step=name):
                self.assert_step_mutation_rejected(document, name, "if: always()", "if: success()",
                                                   self.assert_run_isolation)


class DistributionUploadWorkflowTests(unittest.TestCase):
    gate_name = "Require reviewed licenses before owner-approved preview distribution upload"
    upload_name = "Retain the actual tested unsigned preview distribution"
    inventory_name = "Inventory actual dependency bytes, declared licenses and retained notices"
    approval = "vars.KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED == 'true'"
    review_id = "distribution_upload_license_review"
    archive_name = "Compare actual ZIP and TAR manifests with staged installation"
    unavailable_name = "Declare Windows archive verification unavailable and upload blocked"
    archive_id = "distribution_archive_integrity"
    archive_prefix = "ktchecker-0.1.0-dev.1"
    archive_paths = ("modules/cli/build/distributions/ktchecker-0.1.0-dev.1.zip",
                     "modules/cli/build/distributions/ktchecker-0.1.0-dev.1.tar")
    archive_output = "build/ci-evidence/distribution-archives.json"
    archive_condition = "success() && (runner.os == 'Linux' || runner.os == 'macOS')"
    inventory_outputs = ("build/ci-evidence/package-report.json", "build/ci-evidence/distribution.cdx.json",
                         "build/ci-evidence/NOTICE.dependency-report.txt")
    review_outputs = ("build/ci-evidence/distribution-upload-review.json",
                      "build/ci-evidence/distribution-upload-review.cdx.json",
                      "build/ci-evidence/NOTICE.distribution-upload-review.txt")

    def document(self):
        return (ci_common.REPO_ROOT / ".github/workflows/implementation.yml").read_text(encoding="utf-8")

    def step(self, document, name):
        matches = re.findall(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - name: |\Z)",
                             document, re.MULTILINE)
        self.assertEqual(1, len(matches), f"Required authored step must occur exactly once: {name}")
        return matches[0]

    def conditions(self, step):
        return re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE)

    def artifact_paths(self, step):
        paths = re.search(r"^          path: \|\n((?:            [^\n]*\n)+)", step, re.MULTILINE)
        self.assertIsNotNone(paths)
        return [line.strip() for line in paths[1].splitlines()]

    def report_command(self, outputs, strict=False):
        command = ["python", "scripts/ci/report_distribution.py", "--dependencies",
                   "build/ci-evidence/raw/distribution-dependencies.json", "--output", outputs[0],
                   "--sbom", outputs[1], "--notice", outputs[2]]
        return command + (["--require-license-review"] if strict else [])

    def archive_command(self):
        return ["python", "scripts/ci/verify_distribution_archives.py",
                "--installation", "modules/cli/build/install/ktchecker",
                "--expected-prefix", self.archive_prefix,
                "--archive", self.archive_paths[0], "--archive", self.archive_paths[1],
                "--output", self.archive_output]

    def upload_condition(self):
        return (f"success() && {self.approval} && steps.{self.review_id}.outcome == 'success'"
                f" && steps.{self.archive_id}.outcome == 'success'")

    def assert_archive_guard(self, document):
        step = self.step(document, self.archive_name)
        self.assertEqual([self.archive_condition], self.conditions(step))
        self.assertEqual([self.archive_id], re.findall(r"^        id: ([^\n]+)$", step, re.MULTILINE))
        self.assertEqual(["3"], re.findall(r"^        timeout-minutes: ([^\n]+)$", step, re.MULTILINE))
        self.assertNotIn("continue-on-error:", step)
        self.assertEqual(self.archive_command(), authored_command(document, self.archive_name))
        self.assertNotIn("KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED", step)
        unavailable = self.step(document, self.unavailable_name)
        self.assertEqual(["always() && runner.os == 'Windows'"], self.conditions(unavailable))
        self.assertNotIn("continue-on-error:", unavailable)
        self.assertNotRegex(unavailable, r"(?m)^        id:")
        self.assertEqual(["echo", "Archive verification unavailable on Windows: POSIX no-follow required; "
                                 "preview archive upload remains blocked."],
                         authored_command(document, self.unavailable_name))
        self.assertLess(document.index("- name: " + self.inventory_name), document.index("- name: " + self.archive_name))
        self.assertLess(document.index("- name: " + self.archive_name), document.index("- name: " + self.gate_name))
        collector = authored_command(document, "Collect actual suites and artifact identities, including failures")
        self.assertEqual(1, values(collector, "--artifact").count(self.archive_output))
        for option in ("--command-result", "--require-artifact", "--reports", "--require-report"):
            self.assertNotIn(self.archive_output, values(collector, option))

    def assert_upload_guard(self, document):
        self.assert_archive_guard(document)
        gate = self.step(document, self.gate_name)
        upload = self.step(document, self.upload_name)
        self.assertEqual([f"success() && {self.approval}"], self.conditions(gate))
        self.assertEqual([self.upload_condition()], self.conditions(upload))
        self.assertEqual([self.review_id], re.findall(r"^        id: ([^\n]+)$", gate, re.MULTILINE))
        for step in (gate, upload):
            self.assertNotIn("continue-on-error:", step)
        self.assertEqual(self.report_command(self.review_outputs, strict=True),
                         authored_command(document, self.gate_name))
        self.assertEqual([*self.archive_paths, self.archive_output, *self.inventory_outputs, *self.review_outputs],
                         self.artifact_paths(upload))

    def test_preview_archive_upload_is_default_denied_and_requires_strict_gate_success(self):
        # Fixed authored GitHub expressions only, not a hosted evaluation or upload.
        document = self.document()
        self.assert_upload_guard(document)
        self.assertEqual(2, document.count(self.approval))
        self.assertLess(document.index("- name: " + self.inventory_name), document.index("- name: " + self.gate_name))
        self.assertLess(document.index("- name: " + self.gate_name), document.index("- name: " + self.upload_name))

    def test_strict_review_preserves_original_inventory_and_uses_distinct_retained_outputs(self):
        document = self.document()
        inventory = authored_command(document, self.inventory_name)
        self.assertEqual(self.report_command(self.inventory_outputs), inventory)
        self.assertEqual(6, len(set(self.inventory_outputs + self.review_outputs)))
        self.assertEqual([*self.archive_paths, self.archive_output, *self.inventory_outputs, *self.review_outputs],
                         self.artifact_paths(self.step(document, self.upload_name)))
        collector = authored_command(document, "Collect actual suites and artifact identities, including failures")
        self.assertIn(self.inventory_outputs[0], values(collector, "--command-result"))
        # A default-denied/skipped strict gate must not become missing required evidence.
        self.assertNotIn(self.review_outputs[0], values(collector, "--command-result"))

    def test_owner_opt_in_never_gates_existing_required_validations_or_failure_diagnostics(self):
        document = self.document()
        unconditional = (
            "Verify the evidence harness failure paths", "Record initial host and pinned wrapper",
            "Execute the checksum-pinned Gradle wrapper",
            "Provision pinned optional provider libraries or record Windows unavailability",
            "Build and test actual modules; assemble preview distributions",
            "Resolve actual installed distribution coordinates and Maven POMs",
            "Execute installed native launcher from a spaces and Unicode path",
        )
        always = (
            "Bind observed Gradle to the selected JVM",
            "Observe exact helper build inputs and compiler or explicit unsupported host",
            "Execute exact tiny native controls or record Windows native unavailability",
            "Retain direct original owned live supervisor controls on every host",
            "Verify exact genuine library controls or visible Windows opt-out skips",
            "Verify actual host-specific helper receipt and installed doctor bytes", self.inventory_name,
            "Collect actual suites and artifact identities, including failures",
            "Retain sanitized observed evidence (not terminal transcripts)",
            "Retain original controlled-fixture JUnit and kernel stress reports",
        )
        for name, expected in [(name, []) for name in unconditional] + [(name, ["always()"]) for name in always]:
            with self.subTest(step=name):
                step = self.step(document, name)
                self.assertEqual(expected, self.conditions(step))
                self.assertNotIn("continue-on-error:", step)
                self.assertNotIn("KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED", step)
        build = authored_command(document, "Build and test actual modules; assemble preview distributions")
        for task in (":contracts:test", ":audit:test", ":kernel:test", ":project-model:test",
                     ":provider-architecture:test", ":review-controller:test",
                     ":provider-runtime:buildOwnedProcessSupervisor", ":provider-runtime:test",
                     ":provider-runtime:buildOwnedLiveProcessSupervisor",
                     ":provider-detekt:test", ":provider-ktlint:test", ":cli:installDist", ":cli:distZip", ":cli:distTar"):
            self.assertIn(task, values(build, "--require-task"))
        for task in ("test", ":cli:installDist", ":cli:distZip", ":cli:distTar"):
            self.assertIn(task, build[build.index("--") + 1:])
        for forbidden in ("--tests", "--exclude-task", "-x"):
            self.assertNotIn(forbidden, build)

    def test_failure_diagnostics_remain_unconditional_and_actions_remain_pinned(self):
        document = self.document()
        evidence = self.step(document, "Retain sanitized observed evidence (not terminal transcripts)")
        original = self.step(document, "Retain original controlled-fixture JUnit and kernel stress reports")
        self.assertEqual(["build/ci-evidence/*.json", *PYTHON_REPORTS, *LIVE_REPORTS, LIVE_PROBE_RECEIPT,
                          self.inventory_outputs[2], self.review_outputs[2]],
                         self.artifact_paths(evidence))
        self.assertEqual(["modules/*/build/test-results/**/*.xml", "tests/audit/build/test-results/**/*.xml",
                          "modules/kernel/build/reports/stress/*.json"], self.artifact_paths(original))
        for step, missing in ((evidence, "error"), (original, "warn")):
            self.assertEqual(["always()"], self.conditions(step))
            self.assertEqual([missing], re.findall(r"^          if-no-files-found: ([^\n]+)$", step, re.MULTILINE))
            self.assertEqual(["14"], re.findall(r"^          retention-days: ([^\n]+)$", step, re.MULTILINE))
        upload_action = "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"
        self.assertEqual(["actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09",
                          "actions/setup-python@ece7cb06caefa5fff74198d8649806c4678c61a1",
                          "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
                          upload_action, upload_action, upload_action],
                         re.findall(r"^        uses: ([^\s]+)", document, re.MULTILINE))
        self.assertLess(document.index("- name: " + self.gate_name),
                        document.index("- name: Collect actual suites and artifact identities, including failures"))
        for name in ("Retain sanitized observed evidence (not terminal transcripts)",
                     "Retain original controlled-fixture JUnit and kernel stress reports"):
            self.assertLess(document.index("- name: " + name), document.index("- name: " + self.upload_name))

    def test_exact_upload_guard_rejects_synthetic_bypasses(self):
        document = self.document()
        mutations = (
            (f"if: success() && {self.approval}\n", "if: success()\n"),
            (f"if: {self.upload_condition()}\n", "if: success()\n"),
            (f"steps.{self.review_id}.outcome", f"steps.{self.review_id}.conclusion"),
            (f" && steps.{self.archive_id}.outcome == 'success'", ""),
            (f"steps.{self.archive_id}.outcome", f"steps.{self.archive_id}.conclusion"),
            (f"steps.{self.archive_id}.outcome == 'success'", f"steps.{self.archive_id}.outcome != 'failure'"),
            (f"id: {self.review_id}\n", "id: another_review\n"),
            (f"id: {self.review_id}\n", f"id: {self.review_id}\n        continue-on-error: true\n"),
            ("          --require-license-review\n", ""),
            (f"--output {self.review_outputs[0]}", f"--output {self.inventory_outputs[0]}"),
            (self.approval, "vars.KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED != 'false'"),
            ("            " + self.archive_paths[0] + "\n", "            modules/cli/build/distributions/*.zip\n"),
            ("            " + self.archive_paths[1] + "\n", "            modules/cli/build/distributions/*.tar\n"),
            ("            " + self.archive_output + "\n", ""),
        )
        for old, new in mutations:
            with self.subTest(mutation=old):
                candidate = document.replace(old, new)
                self.assertNotEqual(document, candidate)
                with self.assertRaises(AssertionError):
                    self.assert_upload_guard(candidate)

    def assert_archive_source_coordinates(self, document, build_source, cli_source):
        # Fixed authored Gradle assignments only, never Gradle evaluation.
        base = re.findall(r'^\s*distributionBaseName\.set\("([^"\n]+)"\)\s*$', cli_source, re.MULTILINE)
        version = re.findall(r'^\s*version = "([^"\n]+)"\s*$', build_source, re.MULTILINE)
        launcher = re.findall(r'^\s*applicationName = "([^"\n]+)"\s*$', cli_source, re.MULTILINE)
        self.assertEqual(1, len(base))
        self.assertEqual(1, len(version))
        self.assertEqual(base, launcher)
        command = authored_command(document, self.archive_name)
        prefix = base[0] + "-" + version[0]
        paths = [f"modules/cli/build/distributions/{prefix}.{suffix}" for suffix in ("zip", "tar")]
        self.assertEqual([f"modules/cli/build/install/{base[0]}"], values(command, "--installation"))
        self.assertEqual([prefix], values(command, "--expected-prefix"))
        self.assertEqual(paths, values(command, "--archive"))
        uploaded = self.artifact_paths(self.step(document, self.upload_name))
        self.assertEqual(paths, [path for path in uploaded if path.startswith("modules/cli/build/distributions/")])

    def test_exact_archive_names_and_prefix_bind_current_gradle_source_coordinates(self):
        self.assert_archive_source_coordinates(
            self.document(), (ci_common.REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8"),
            (ci_common.REPO_ROOT / "modules/cli/build.gradle.kts").read_text(encoding="utf-8"))

    def test_version_basename_or_launcher_source_drift_requires_reviewed_workflow_update(self):
        document = self.document()
        build = (ci_common.REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        cli = (ci_common.REPO_ROOT / "modules/cli/build.gradle.kts").read_text(encoding="utf-8")
        self.assert_archive_source_coordinates(document, build, cli)
        mutations = (
            (build.replace('version = "0.1.0-dev.1"', 'version = "0.1.0-dev.2"'), cli),
            (build, cli.replace('distributionBaseName.set("ktchecker")', 'distributionBaseName.set("other")')),
            (build, cli.replace('applicationName = "ktchecker"', 'applicationName = "other"')),
        )
        for changed_build, changed_cli in mutations:
            with self.subTest(build_changed=changed_build != build, cli_changed=changed_cli != cli):
                self.assertNotEqual((build, cli), (changed_build, changed_cli))
                with self.assertRaises(AssertionError):
                    self.assert_archive_source_coordinates(document, changed_build, changed_cli)

    def test_posix_archive_gate_is_required_without_owner_opt_in_and_windows_is_unavailable(self):
        self.assert_archive_guard(self.document())
        self.assert_upload_guard(self.document())

    def test_archive_source_mutations_cannot_skip_mask_or_redirect_the_comparison(self):
        document = self.document()
        step = self.step(document, self.archive_name)
        self.assert_archive_guard(document)
        mutations = (
            (self.archive_condition, "success()"),
            (self.archive_condition, f"success() && {self.approval}"),
            (self.archive_condition, "success() && runner.os == 'Linux'"),
            (f"id: {self.archive_id}\n", "id: different_archive_gate\n"),
            (f"id: {self.archive_id}\n", f"id: {self.archive_id}\n        continue-on-error: true\n"),
            ("timeout-minutes: 3", "timeout-minutes: 30"),
            ("python scripts/ci/verify_distribution_archives.py", "python scripts/ci/report_distribution.py"),
            ("--installation modules/cli/build/install/ktchecker", "--installation modules/cli/build/install/other"),
            ("--expected-prefix " + self.archive_prefix, ""),
            ("--expected-prefix " + self.archive_prefix, "--expected-prefix other"),
            ("--archive " + self.archive_paths[0], ""),
            ("--archive " + self.archive_paths[1], ""),
            ("--archive " + self.archive_paths[0], "--archive modules/cli/build/distributions/*.zip"),
            ("--archive " + self.archive_paths[1], "--archive " + self.archive_paths[0]),
            ("--output " + self.archive_output, "--output build/ci-evidence/raw/archive.json"),
            ("--output " + self.archive_output, "--output " + self.archive_output + " || true"),
        )
        for old, new in mutations:
            with self.subTest(mutation=old, replacement=new):
                self.assertEqual(1, step.count(old))
                changed = document.replace(step, step.replace(old, new, 1), 1)
                with self.assertRaises(AssertionError):
                    self.assert_archive_guard(changed)

    def test_archive_receipt_cannot_become_required_windows_evidence_or_generic_pass(self):
        document = self.document()
        original = "--artifact " + self.archive_output
        self.assertEqual(1, document.count(original))
        for replacement in ("", "--require-artifact " + self.archive_output,
                            "--command-result " + self.archive_output, "--reports " + self.archive_output):
            with self.subTest(replacement=replacement), self.assertRaises(AssertionError):
                self.assert_archive_guard(document.replace(original, replacement, 1))
        unavailable = self.step(document, self.unavailable_name)
        for original, replacement in (("always() && runner.os == 'Windows'", "success()"),
                                      ("echo ", "python "), ("unavailable on Windows", "verified on Windows")):
            with self.subTest(replacement=replacement), self.assertRaises(AssertionError):
                self.assert_archive_guard(document.replace(unavailable, unavailable.replace(original, replacement), 1))

    def synthetic_archive_inputs(self, root, *, changed_suffix=None, mutation=None):
        """Tiny new fixtures only; no extraction, installation, JVM or shell run."""
        command = authored_command(self.document(), self.archive_name)
        self.assertEqual(self.archive_command(), command)
        argv = command[2:]
        for index, argument in enumerate(argv[:-1]):
            if argument in ("--installation", "--archive", "--output"):
                argv[index + 1] = str(root / argv[index + 1])
        installation = Path(values(argv, "--installation")[0])
        content = {"THIRD_PARTY_NOTICES.md": b"synthetic notice, not legal clearance\n",
                   "bin/ktchecker": b"synthetic POSIX launcher, never run\n",
                   "bin/ktchecker.bat": b"synthetic Windows launcher, never run\r\n",
                   "lib/synthetic.jar": b"synthetic placeholder, not JVM bytecode\n"}

        def new_file(path, data, executable=False):
            path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o700 if executable else 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)

        paths = []
        for name, data in content.items():
            path = installation / name
            new_file(path, data, executable=name == "bin/ktchecker")
            paths.append(path)
        for value in values(argv, "--archive"):
            path = Path(value)
            rows = [{"name": self.archive_prefix + "/" + name, "data": data, "method": 8,
                     "mode": stat.S_IFREG | (0o755 if name == "bin/ktchecker" else 0o644)}
                    for name, data in sorted(content.items())]
            if path.suffix == changed_suffix:
                if mutation == "bytes":
                    rows[0]["data"] = b"X" + rows[0]["data"][1:]
                elif mutation == "name":
                    rows[0]["name"] += ".other"
                elif mutation == "executable":
                    rows[0]["mode"] = stat.S_IFREG | 0o755
                elif mutation == "prefix":
                    for row in rows:
                        row["name"] = row["name"].replace(self.archive_prefix, self.archive_prefix + "-other", 1)
                elif mutation == "missing_member":
                    rows.pop(0)
                elif mutation == "extra_member":
                    rows.append({"name": self.archive_prefix + "/extra.txt", "data": b"unexpected"})
                elif mutation == "missing_archive":
                    continue
                elif mutation != "unsupported_tar":
                    self.fail("Unknown synthetic archive mutation")
            raw = archive_fixtures.make_zip(rows) if path.suffix == ".zip" else archive_fixtures.make_tar(rows)
            if path.suffix == changed_suffix and mutation == "unsupported_tar":
                self.assertEqual(".tar", path.suffix)
                raw = b"\x1f\x8bsynthetic unsupported compressed TAR"
            new_file(path, raw)
            paths.append(path)
        output = Path(values(argv, "--output")[0])
        output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        before = {path: (hashlib.sha256(path.read_bytes()).hexdigest(), bool(path.stat().st_mode & 0o111))
                  for path in paths}
        return argv, before, content

    def run_synthetic_archive_command(self, argv):
        with patch("sys.stdout", new_callable=io.StringIO) as stdout:
            status = archive_fixtures.verifier.main(argv)
        return status, json.loads(stdout.getvalue())

    @unittest.skipUnless(archive_fixtures.verifier.platform_available(), "POSIX archive I/O unavailable; no emulated pass")
    def test_authored_archive_command_checks_real_synthetic_pair_and_retains_only_metadata(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-workflow-archives-") as temporary:
            # Canonicalize only this fresh trusted root, including macOS temp aliases.
            root = Path(temporary).resolve(strict=True)
            argv, before, content = self.synthetic_archive_inputs(root)
            status, result = self.run_synthetic_archive_command(argv)
            self.assertEqual(0, status)
            self.assertEqual("verified", result["state"])
            self.assertIs(True, result["output_written"])
            self.assertEqual([Path(path).name for path in self.archive_paths], [row["name"] for row in result["archives"]])
            self.assertEqual([self.archive_prefix] * 2, [row["prefix"] for row in result["archives"]])
            self.assertEqual({"zip32", "ustar"}, {row["format"] for row in result["archives"]})
            self.assertEqual("unchanged", result["input_recheck"])
            retained = json.loads((root / self.archive_output).read_text(encoding="utf-8"))
            self.assertEqual({key: value for key, value in result.items() if key != "output_written"}, retained)
            for key in ("raw_contents_emitted", "compiled_code_or_runtime_proof", "historical_build_source_binding",
                        "release_qualified", "atomic_snapshot_or_hostile_writer_containment"):
                self.assertIs(False, retained[key])
            self.assertEqual("not_assessed", retained["license_review"])
            rendered = json.dumps(retained)
            self.assertNotIn(str(root), rendered)
            for raw in content.values():
                self.assertNotIn(raw.decode().strip(), rendered)
            for path, identity in before.items():
                self.assertEqual(identity, (hashlib.sha256(path.read_bytes()).hexdigest(), bool(path.stat().st_mode & 0o111)))

    @unittest.skipUnless(archive_fixtures.verifier.platform_available(), "POSIX archive I/O unavailable; no emulated pass")
    def test_each_archive_byte_name_mode_prefix_omission_and_extra_mutation_fails_actual_cli(self):
        errors = {"bytes": "archive_file_sha256_mismatch", "name": "archive_extra_regular_file",
                  "executable": "archive_executable_mismatch", "prefix": "archive_prefix_mismatch",
                  "missing_member": "archive_missing_regular_file", "extra_member": "archive_extra_regular_file"}
        for suffix in (".zip", ".tar"):
            for mutation, code in errors.items():
                with self.subTest(suffix=suffix, mutation=mutation), \
                        tempfile.TemporaryDirectory(prefix="synthetic-workflow-archives-") as temporary:
                    root = Path(temporary).resolve(strict=True)
                    argv, _, _ = self.synthetic_archive_inputs(root, changed_suffix=suffix, mutation=mutation)
                    status, result = self.run_synthetic_archive_command(argv)
                    self.assertEqual(1, status)
                    self.assertEqual("failed", result["state"])
                    self.assertIs(True, result["output_written"])
                    self.assertEqual(code, result["errors"][0]["code"])
                    self.assertEqual(1 if suffix == ".zip" else 2, result["errors"][0]["archive_index"])
                    self.assertTrue(all(row["state"] != "verified" for row in result["archives"]))
                    self.assertEqual("failed", json.loads((root / self.archive_output).read_text())["state"])

    @unittest.skipUnless(archive_fixtures.verifier.platform_available(), "POSIX archive I/O unavailable; no emulated pass")
    def test_missing_archive_and_unsupported_tar_remain_visible_non_success(self):
        for suffix, mutation, state, code in (
            (".zip", "missing_archive", "unavailable", "verification_io_unavailable"),
            (".tar", "missing_archive", "unavailable", "verification_io_unavailable"),
            (".tar", "unsupported_tar", "unsupported", "unsupported_tar_compression"),
        ):
            with self.subTest(suffix=suffix, mutation=mutation), \
                    tempfile.TemporaryDirectory(prefix="synthetic-workflow-archives-") as temporary:
                argv, _, _ = self.synthetic_archive_inputs(Path(temporary).resolve(strict=True),
                                                          changed_suffix=suffix, mutation=mutation)
                status, result = self.run_synthetic_archive_command(argv)
                self.assertEqual(1, status)
                self.assertEqual(state, result["state"])
                self.assertEqual(code, result["errors"][0]["code"])
                self.assertIs(True, result["output_written"])

    def test_authored_archive_command_cannot_turn_unavailable_host_into_success(self):
        verifier = archive_fixtures.verifier
        argv = authored_command(self.document(), self.archive_name)[2:]
        with patch.object(verifier, "platform_available", return_value=False), patch.object(verifier.os, "open") as opened:
            status, result = self.run_synthetic_archive_command(argv)
        opened.assert_not_called()
        self.assertEqual(1, status)
        self.assertEqual("unavailable", result["state"])
        self.assertIs(False, result["output_written"])
        self.assertEqual(["platform_no_follow_unavailable", "report_no_follow_unavailable"],
                         [row["code"] for row in result["errors"]])

    @unittest.skipUnless(archive_fixtures.verifier.platform_available(), "POSIX archive I/O unavailable; no emulated pass")
    def test_unchecked_neighbor_archives_are_neither_compared_nor_selected_for_upload(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-workflow-archives-") as temporary:
            root = Path(temporary).resolve(strict=True)
            argv, _, _ = self.synthetic_archive_inputs(root)
            directory = (root / self.archive_paths[0]).parent
            for suffix in (".zip", ".tar"):
                path = directory / ("unchecked-neighbor" + suffix)
                with path.open("xb") as handle:
                    handle.write(b"not an archive and never eligible for upload")
            status, result = self.run_synthetic_archive_command(argv)
            self.assertEqual(0, status)
            selected = self.artifact_paths(self.step(self.document(), self.upload_name))
            self.assertEqual(list(self.archive_paths), [path for path in selected if path.startswith("modules/")])
            self.assertFalse(any("*" in path or "unchecked-neighbor" in path for path in selected))
            self.assertEqual([Path(path).name for path in self.archive_paths], [row["name"] for row in result["archives"]])

    def run_synthetic_report(self, command, root, report):
        argv = command[1:]
        for option in ("--dependencies", "--output", "--sbom", "--notice"):
            index = argv.index(option) + 1
            argv[index] = str(root / argv[index])
        # Stub inventory data only; no distribution inspection, owner license grant,
        # Gradle/JVM/provider/native invocation, or archive publication occurs.
        with patch.object(report_distribution, "generate", return_value=report), \
             patch("sys.argv", argv), patch("sys.stdout", new_callable=io.StringIO):
            return report_distribution.main()

    def test_blocked_strict_gate_fails_and_keeps_original_inventory_bytes(self):
        document = self.document()

        def blocked_report():
            return {"schema_version": 1, "evidence_kind": "synthetic_workflow_license_gate_control",
                    "state": "passed", "license_review_status": "blocked", "errors": [], "components": [],
                    "license_review_blockers": ["owner_license_unresolved: synthetic owner decision remains unresolved"],
                    "dependency_manifest_digest": "sha256:" + "3" * 64, "source": IDENTITY,
                    "recorded_at": "2000-01-01T00:00:00Z", "release_qualified": False}

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(0, self.run_synthetic_report(authored_command(document, self.inventory_name), root, blocked_report()))
            original = {path: (root / path).read_bytes() for path in self.inventory_outputs}
            self.assertEqual(1, self.run_synthetic_report(authored_command(document, self.gate_name), root, blocked_report()))
            for path, before in original.items():
                self.assertEqual(before, (root / path).read_bytes())
            reviewed = json.loads((root / self.review_outputs[0]).read_text(encoding="utf-8"))
            self.assertEqual("passed", reviewed["state"])
            self.assertEqual("blocked", reviewed["license_review_status"])
            self.assertTrue(reviewed["license_review_blockers"])
            self.assertFalse(reviewed["release_qualified"])
            self.assertEqual({Path(path).name for path in self.review_outputs[1:]},
                             {row["name"] for row in reviewed["generated_reports"]})
            self.assertIn("owner_license_unresolved", (root / self.review_outputs[2]).read_text(encoding="utf-8"))


class PythonEvidenceWorkflowTests(unittest.TestCase):
    """Authored routing/gates only; hosted execution still needs actual receipts."""

    profiles = (
        ("implementation.yml", "Verify the evidence harness failure paths", "scripts/ci",
         "Retain sanitized observed evidence (not terminal transcripts)"),
        ("plan-validation.yml", "Test validation failure paths", "scripts",
         "Retain sanitized Python diagnostics even when validation fails"),
    )

    def document(self, filename):
        return (ci_common.REPO_ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")

    def step(self, document, name):
        rows = re.findall(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - name: |\Z)",
                          document, re.MULTILINE)
        self.assertEqual(1, len(rows), name)
        return rows[0]

    def assert_execution(self, document, name, start):
        self.assertEqual([
            "python", "scripts/ci/run_python_checks.py", "--start-directory", start,
            "--output", PYTHON_REPORTS[0], "--junit", PYTHON_REPORTS[1],
            "--host", "${{ matrix.os }}", "--require-ci-context", "--timeout", "480",
        ], authored_command(document, name))
        step = self.step(document, name)
        self.assertNotRegex(step, r"(?m)^        if:")
        self.assertNotIn("continue-on-error:", step)
        self.assertNotIn("GITHUB_SHA:", step)
        self.assertNotIn("GITHUB_RUN_ID:", step)

    def assert_retention(self, document, name):
        step = self.step(document, name)
        self.assertEqual(["always()"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertNotIn("continue-on-error:", step)
        self.assertEqual(["actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"],
                         re.findall(r"^        uses: ([^\s]+)", step, re.MULTILINE))
        paths = re.search(r"^          path: \|\n((?:            [^\n]*\n)+)", step, re.MULTILINE)
        self.assertIsNotNone(paths)
        selected = [line.strip() for line in paths[1].splitlines()]
        for path in PYTHON_REPORTS:
            self.assertEqual(1, selected.count(path))
        self.assertEqual(list(PYTHON_REPORTS), [path for path in selected if "/python/" in path])
        self.assertFalse(any("raw" in path or "**" in path or "log" in path or "checkpoint" in path
                             for path in selected))
        self.assertEqual(["error"], re.findall(r"^          if-no-files-found: ([^\n]+)$", step, re.MULTILINE))
        self.assertEqual(["14"], re.findall(r"^          retention-days: ([^\n]+)$", step, re.MULTILINE))

    def test_discovery_scope_stays_complete_and_nonzero_exits_gate_each_workflow(self):
        for filename, name, start, _ in self.profiles:
            with self.subTest(workflow=filename):
                self.assert_execution(self.document(filename), name, start)

    def test_only_literal_sanitized_pair_is_retained_even_on_failure(self):
        for filename, _, _, upload in self.profiles:
            with self.subTest(workflow=filename):
                self.assert_retention(self.document(filename), upload)

    def test_python_projection_never_enters_the_ordinary_junit_denominator(self):
        command = authored_command(self.document("implementation.yml"),
                                   "Collect actual suites and artifact identities, including failures")
        for option in ("--reports", "--require-report"):
            for pattern in values(command, option):
                self.assertTrue(pattern.startswith(("modules/", "tests/audit/")))
        # Its JSON has unittest-specific discovery/callback semantics, not the
        # generic command-result contract. The actual step exit gates the job.
        self.assertNotIn(PYTHON_REPORTS[0], values(command, "--command-result"))
        self.assertNotIn(PYTHON_REPORTS[1], command)

    def test_linux_lint_normal_and_optimized_privacy_controls_are_preserved(self):
        step = self.step(self.document("implementation.yml"),
                         "Verify synthetic Lint receipt privacy without relying on Python assertions")
        self.assertEqual(["runner.os == 'Linux'"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertIn("          python providers/platform-fixtures/test_retain_lint_evidence.py -q\n", step)
        self.assertIn("          python -O providers/platform-fixtures/test_retain_lint_evidence.py -q\n", step)
        self.assertNotIn("continue-on-error:", step)

    def test_execution_gate_rejects_scope_reduction_or_masked_failure(self):
        for filename, name, start, _ in self.profiles:
            document = self.document(filename)
            mutations = (
                ("--start-directory " + start + "\n", "--start-directory scripts/empty\n"),
                ("--require-ci-context ", ""),
                ("--timeout 480", "--timeout 2400"),
                ("      - name: " + name + "\n", "      - name: " + name + "\n        continue-on-error: true\n"),
            )
            for old, new in mutations:
                with self.subTest(workflow=filename, mutation=old):
                    changed = document.replace(old, new)
                    self.assertNotEqual(document, changed)
                    with self.assertRaises(AssertionError):
                        self.assert_execution(changed, name, start)

    def test_retention_gate_rejects_raw_globs_missing_pair_and_success_only_upload(self):
        for filename, _, _, upload in self.profiles:
            document = self.document(filename)
            for path in (PYTHON_REPORTS[0], PYTHON_REPORTS[1], "build/ci-evidence/python/**"):
                old = PYTHON_REPORTS[1] if path.endswith("/**") else path
                new = path if path.endswith("/**") else "build/ci-evidence/other.json"
                with self.subTest(workflow=filename, replacement=new, original=old):
                    changed = document.replace("            " + old + "\n", "            " + new + "\n")
                    self.assertNotEqual(document, changed)
                    with self.assertRaises(AssertionError):
                        self.assert_retention(changed, upload)
            step = self.step(document, upload)
            changed = document.replace(step, step.replace("if: always()", "if: success()"))
            with self.assertRaises(AssertionError):
                self.assert_retention(changed, upload)


class LiveSupervisorWorkflowTests(unittest.TestCase):
    """Authored routing/mutation controls only; no native run or qualification."""

    runner_name = "Retain direct original owned live supervisor controls on every host"
    probe_name = "Build a fresh owned live probe on Linux without executing it"
    build_name = "Build and test actual modules; assemble preview distributions"
    upload_name = "Retain sanitized observed evidence (not terminal transcripts)"
    collector_name = "Collect actual suites and artifact identities, including failures"
    opt_in = "${{ runner.os == 'Linux' && '1' || '' }}"
    probe_output = "${{ github.workspace }}/build/ci-evidence/live-probe"
    probe_path = "${{ runner.os == 'Linux' && format('{0}/build/ci-evidence/live-probe/probe', github.workspace) || '' }}"

    def document(self):
        return (ci_common.REPO_ROOT / ".github/workflows/implementation.yml").read_text(encoding="utf-8")

    def step(self, document, name):
        rows = re.findall(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - name: |\Z)",
                          document, re.MULTILINE)
        self.assertEqual(1, len(rows), name)
        return rows[0]

    def assert_direct_runner(self, document):
        step = self.step(document, self.runner_name)
        self.assertEqual(["always()"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertEqual([
            "python", "-B", "scripts/ci/live_supervisor_test_runner.py",
            "--output", LIVE_REPORTS[0], "--junit", LIVE_REPORTS[1],
            "--host", "${{ matrix.os }}", "--require-ci-context",
            "${{", "matrix.supervisor_cc_argument", "}}",
        ], authored_command(document, self.runner_name))
        # Fixed direct argv also rejects shell timeout/kill/generic-runner wrappers
        # and masked exits. Do not invent a second deadline or override CI identity.
        self.assertNotRegex(step, r"(?m)^        (?:continue-on-error|timeout-minutes|env|shell):")

    def assert_probe_build(self, document):
        step = self.step(document, self.probe_name)
        self.assertEqual(["runner.os == 'Linux'"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertEqual(["python3", "-B", "scripts/ci/build_live_probe.py", "--cc", "/usr/bin/cc",
                          "--output", self.probe_output], authored_command(document, self.probe_name))
        self.assertNotRegex(step, r"(?m)^        (?:continue-on-error|timeout-minutes|env|shell):")
        self.assertLess(document.index("- name: " + self.probe_name),
                        document.index("- name: " + self.build_name))

    def assert_host_build(self, document):
        step = self.step(document, self.build_name)
        env = re.search(r"^        env:\n((?:          [^\n]+\n)+)", step, re.MULTILINE)
        self.assertIsNotNone(env)
        self.assertEqual(["KTC_RUN_OWNED_LIVE_INTEGRATION: " + self.opt_in,
                          "KTC_OWNED_LIVE_PROBE: " + self.probe_path],
                         [line.strip() for line in env[1].splitlines()])
        for key in ("KTC_RUN_OWNED_LIVE_INTEGRATION:", "KTC_OWNED_LIVE_PROBE:"):
            self.assertEqual(1, document.count(key), "No workflow/job-global or second-step live opt-in")
        required = values(authored_command(document, self.build_name), "--require-task")
        for task in (":provider-runtime:buildOwnedProcessSupervisor", ":provider-runtime:test",
                     ":provider-runtime:buildOwnedLiveProcessSupervisor"):
            self.assertEqual(1, required.count(task))
        self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")

    def assert_retention(self, document):
        step = self.step(document, self.upload_name)
        self.assertEqual(["always()"], re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE))
        self.assertNotIn("continue-on-error:", step)
        pattern = r"^          path: \|\n((?:            [^\n]*\n)+)"
        paths = re.search(pattern, step, re.MULTILINE)
        self.assertIsNotNone(paths)
        expected = ["build/ci-evidence/*.json", *PYTHON_REPORTS, *LIVE_REPORTS, LIVE_PROBE_RECEIPT,
                    DistributionUploadWorkflowTests.inventory_outputs[2],
                    DistributionUploadWorkflowTests.review_outputs[2]]
        self.assertEqual(expected, [line.strip() for line in paths[1].splitlines()])
        # Inspect all authored upload lists, so another artifact cannot smuggle
        # the probe, its directory/glob, local phase/failure files or raw traces.
        allowed = set(expected) | set(DistributionUploadWorkflowTests.inventory_outputs) \
            | set(DistributionUploadWorkflowTests.review_outputs) \
            | set(DistributionUploadWorkflowTests.archive_paths) | {
                "modules/*/build/test-results/**/*.xml", "tests/audit/build/test-results/**/*.xml",
                "modules/kernel/build/reports/stress/*.json",
                DistributionUploadWorkflowTests.archive_output,
            }
        blocks = re.findall(pattern, document, re.MULTILINE)
        self.assertEqual(3, len(blocks))
        published = [line.strip() for block in blocks for line in block.splitlines()]
        for path in published:
            self.assertIn(path, allowed)
        self.assertEqual([*LIVE_REPORTS, LIVE_PROBE_RECEIPT],
                         [path for path in published if path in (*LIVE_REPORTS, LIVE_PROBE_RECEIPT)])

    def assert_separate_denominators(self, document):
        command = authored_command(document, self.collector_name)
        for option in ("--reports", "--require-report"):
            for path in values(command, option):
                self.assertTrue(path.startswith(("modules/", "tests/audit/")))
        self.assertEqual([f"build/ci-evidence/{name}.json" for name in (
            "gradle-version", "supervisor-prerequisites", "supervisor-controls", "library-prerequisites",
            "host-build", "library-controls", "dependency-manifest-command", "distribution-smoke",
            "supervisor-installed-bytes", "package-report",
        )], values(command, "--command-result"))
        for path in (*LIVE_REPORTS, LIVE_PROBE_RECEIPT):
            self.assertNotIn(path, command)

    def assert_mutations_fail(self, document, name, assertion, mutations):
        step = self.step(document, name)
        for old, new in mutations:
            with self.subTest(step=name, mutation=old, replacement=new):
                changed = step.replace(old, new, 1)
                self.assertNotEqual(step, changed)
                with self.assertRaises(AssertionError):
                    assertion(document.replace(step, changed, 1))

    def test_direct_original_runner_is_always_enabled_on_all_actual_matrix_hosts(self):
        document = self.document()
        self.assert_direct_runner(document)
        self.assertEqual(["ubuntu-24.04", "windows-2025", "macos-26"],
                         re.findall(r"^          - os: ([^\n]+)$", document, re.MULTILINE))

    def test_fresh_probe_build_and_explicit_live_opt_in_are_linux_only(self):
        document = self.document()
        self.assert_probe_build(document)
        self.assert_host_build(document)

    def test_only_literal_sanitized_live_pair_and_probe_receipt_are_retained(self):
        self.assert_retention(self.document())

    def test_live_callbacks_and_probe_receipt_never_enter_generic_denominators(self):
        self.assert_separate_denominators(self.document())

    def test_runner_controls_reject_outer_deadlines_scope_spoofing_and_masked_exits(self):
        self.assert_mutations_fail(self.document(), self.runner_name, self.assert_direct_runner, (
            ("if: always()", "if: runner.os == 'Linux'"),
            ("if: always()", "if: success()"),
            ("--host \"${{ matrix.os }}\"", "--host ubuntu-24.04"),
            ("--require-ci-context", "--timeout 480"),
            ("python -B scripts/ci/live_supervisor_test_runner.py", "timeout 480 python -B scripts/ci/live_supervisor_test_runner.py"),
            ("python -B scripts/ci/live_supervisor_test_runner.py", "python scripts/ci/run_python_checks.py --start-directory modules/provider-runtime/native"),
            ("python -B", "python -O -B"),
            ("if: always()", "if: always()\n        timeout-minutes: 8"),
            ("if: always()", "if: always()\n        continue-on-error: true"),
            ("${{ matrix.supervisor_cc_argument }}\n", "${{ matrix.supervisor_cc_argument }} || true\n"),
        ))

    def test_probe_and_host_build_controls_reject_reuse_cross_host_opt_in_and_missing_task(self):
        document = self.document()
        self.assert_mutations_fail(document, self.probe_name, self.assert_probe_build, (
            ("if: runner.os == 'Linux'", "if: always()"),
            ("--cc /usr/bin/cc", "--cc cc"),
            (self.probe_output, "build/ci-evidence/live-probe"),
            ("python3 -B", "rm -rf build/ci-evidence/live-probe && python3 -B"),
            ("if: runner.os == 'Linux'", "if: runner.os == 'Linux'\n        continue-on-error: true"),
        ))
        self.assert_mutations_fail(document, self.build_name, self.assert_host_build, (
            (self.opt_in, "'1'"),
            (self.probe_path, "build/ci-evidence/live-probe/probe"),
            ("          --require-task :provider-runtime:buildOwnedLiveProcessSupervisor\n", ""),
        ))
        global_opt_in = document.replace("\njobs:\n", "\nenv:\n  KTC_RUN_OWNED_LIVE_INTEGRATION: '1'\njobs:\n", 1)
        self.assertNotEqual(document, global_opt_in)
        with self.assertRaises(AssertionError):
            self.assert_host_build(global_opt_in)

    def test_retention_controls_reject_raw_binary_directory_globs_and_local_state_files(self):
        mutations = [(LIVE_REPORTS[0], "build/ci-evidence/live-supervisor/**"),
                     (LIVE_REPORTS[1], "build/ci-evidence/raw/live-supervisor/**"),
                     ("if: always()", "if: success()")]
        mutations.extend((LIVE_PROBE_RECEIPT, "build/ci-evidence/live-probe/" + suffix)
                         for suffix in ("probe", "", "*", "**", "phase", "before.json", "failure.json"))
        self.assert_mutations_fail(self.document(), self.upload_name, self.assert_retention, mutations)

    def test_generic_denominator_controls_reject_live_json_xml_and_broad_globs(self):
        self.assert_mutations_fail(self.document(), self.collector_name, self.assert_separate_denominators, (
            ('--reports "modules/*/build/test-results/**/*.xml"', '--reports "build/ci-evidence/**/*.xml"'),
            ('--reports "modules/*/build/test-results/**/*.xml"', '--reports "' + LIVE_REPORTS[1] + '"'),
            ("--command-result build/ci-evidence/package-report.json", "--command-result " + LIVE_REPORTS[0]),
            ("--command-result build/ci-evidence/package-report.json", "--command-result " + LIVE_PROBE_RECEIPT),
        ))


class WindowsCheckoutWorkflowTests(unittest.TestCase):
    def test_explicit_windows_long_paths_step_precedes_checkout(self):
        # Authored wiring only: this does not demonstrate hosted Windows checkout
        # or support by any downstream tool for paths beyond legacy MAX_PATH.
        for filename in ("implementation.yml", "bridges-validation.yml", "plan-validation.yml"):
            with self.subTest(workflow=filename):
                document = (ci_common.REPO_ROOT / ".github/workflows" / filename).read_text(encoding="utf-8")
                step = re.search(
                    r"^      - name: Enable Windows long paths before checkout\n"
                    r"([\s\S]*?)(?=^      - name: |\Z)", document, re.MULTILINE)
                self.assertIsNotNone(step)
                self.assertRegex(step[1], r"(?m)^        if: runner\.os == 'Windows'$")
                self.assertRegex(step[1], r"(?m)^        shell: pwsh$")
                self.assertRegex(step[1], r"(?m)^        run: git config --global core\.longpaths true$")
                self.assertNotIn("continue-on-error:", step[1])
                checkout = re.search(r"^        uses: actions/checkout@", document, re.MULTILINE)
                self.assertIsNotNone(checkout)
                self.assertLess(step.end(), checkout.start())
                self.assertEqual(1, document.count("git config --global core.longpaths true"))


class RequiredContextCollectorTests(unittest.TestCase):
    def fixture(self, root: Path):
        environment = root / "environment.json"
        environment.write_text(json.dumps({"state": "passed", "source": IDENTITY}), encoding="utf-8")
        paths = {}
        for pattern in REPORTS:
            path = root / pattern.replace("*.xml", "TEST-synthetic.xml")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('<testsuite tests="1"><testcase name="synthetic"/></testsuite>', encoding="utf-8")
            paths[pattern] = path
        stress = root / STRESS
        stress.parent.mkdir(parents=True, exist_ok=True)
        stress.write_bytes(b'{"verified":true,"PRIVATE":"untrusted embedded claim"}\n')
        args = argparse.Namespace(profile="synthetic-wiring", environment=environment,
                                  reports=["modules/*/build/test-results/**/*.xml"], require_report=list(REPORTS),
                                  require_tests=True, artifact=[], require_artifact=[STRESS], command_result=[])
        return args, paths, stress

    def collect(self, args, root):
        with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
             patch.dict(os.environ, {"KT_CI_JOB_STATUS": "success"}):
            return collect_evidence.collect(args, root)

    def test_other_suites_cannot_mask_missing_or_skipped_required_new_context(self):
        for pattern in REPORTS:
            for state in ("missing", "skipped"):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(pattern=pattern, state=state):
                    root = Path(temporary)
                    args, paths, _ = self.fixture(root)
                    path = paths[pattern]
                    if "*" in pattern:
                        selected = list(path.parent.glob("*.xml"))
                    else:
                        selected = [path]
                    for report in selected:
                        if state == "missing":
                            report.unlink()
                        else:
                            report.write_text('<testsuite tests="1" skipped="1"><testcase name="synthetic"><skipped/></testcase></testsuite>', encoding="utf-8")
                    result = self.collect(args, root)
                    self.assertGreater(result["counts"]["passed"], 0)
                    self.assertEqual("incomplete", result["state"])
                    self.assertTrue(any(pattern in item for item in result["missing_evidence"]))

    def test_stress_artifact_is_required_but_only_its_actual_bytes_are_projected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, _, stress = self.fixture(root)
            result = self.collect(args, root)
            self.assertEqual("passed", result["state"])
            self.assertEqual([{"path": STRESS, "kind": "file", "size": stress.stat().st_size,
                               "digest": ci_common.digest_file(stress)}], result["artifacts"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertNotIn("verified", result["artifacts"][0])
            stress.unlink()
            missing = self.collect(args, root)
            self.assertEqual("incomplete", missing["state"])
            self.assertIn(f"Required artifact absent: {STRESS}", missing["missing_evidence"])


class ModelContextCollectorTests(unittest.TestCase):
    """Owned synthetic path/status fixtures only; not retained model-control results."""

    synthetic_pass = ('<testsuite name="synthetic.model-context" tests="1">'
                      '<testcase classname="synthetic.model-context" name="authored-only"/></testsuite>')

    def fixture(self, root: Path):
        environment = root / "environment.json"
        environment.write_text(json.dumps({"state": "passed", "source": IDENTITY}), encoding="utf-8")
        paths = {}
        for report in MODEL_REPORTS:
            path = root / report
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(self.synthetic_pass, encoding="utf-8")
            paths[report] = path
        unrelated = root / "modules/worker-protocol/build/test-results/test/TEST-unrelated.xml"
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_text(self.synthetic_pass, encoding="utf-8")
        args = argparse.Namespace(profile="synthetic-model-context-wiring", environment=environment,
                                  reports=["modules/*/build/test-results/**/*.xml"],
                                  require_report=list(MODEL_REPORTS), require_tests=True,
                                  artifact=[], require_artifact=[], command_result=[])
        return args, paths

    def collect(self, args, root, job_status="success"):
        with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
             patch.dict(os.environ, {"KT_CI_JOB_STATUS": job_status}):
            return collect_evidence.collect(args, root)

    def test_only_exact_synthetic_model_report_paths_satisfy_the_authored_contexts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, _ = self.fixture(root)
            result = self.collect(args, root)
            self.assertEqual("passed", result["state"])
            self.assertEqual(4, result["counts"]["passed"])
            self.assertEqual([], result["missing_evidence"])
            self.assertEqual([], result["errors"])

    def test_other_passed_suites_cannot_mask_missing_empty_skipped_failed_or_errored_model_reports(self):
        alternatives = {
            "empty": '<testsuite name="synthetic.model-context" tests="0"/>',
            "skipped": '<testsuite tests="1" skipped="1"><testcase name="authored-only"><skipped/></testcase></testsuite>',
            "failure": '<testsuite tests="1" failures="1"><testcase name="authored-only"><failure/></testcase></testsuite>',
            "error": '<testsuite tests="1" errors="1"><testcase name="authored-only"><error/></testcase></testsuite>',
        }
        for report in MODEL_REPORTS:
            for state in ("missing", *alternatives):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report, state=state):
                    root = Path(temporary)
                    args, paths = self.fixture(root)
                    if state == "missing":
                        paths[report].unlink()
                    else:
                        paths[report].write_text(alternatives[state], encoding="utf-8")
                    result = self.collect(args, root)
                    self.assertGreater(result["counts"]["passed"], 0)
                    self.assertEqual("incomplete", result["state"])
                    self.assertIn("No passed test cases in required report context: " + report,
                                  result["missing_evidence"])
                    if state in ("failure", "error"):
                        self.assertIn("JUnit reports contain failures/errors", result["errors"])

    def test_an_ordinary_test_directory_cannot_substitute_for_an_exact_model_task_context(self):
        for report in MODEL_REPORTS:
            with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report):
                root = Path(temporary)
                args, paths = self.fixture(root)
                original = paths[report]
                replacement = original.parent.parent / "test" / original.name
                replacement.parent.mkdir(parents=True, exist_ok=True)
                original.rename(replacement)
                result = self.collect(args, root)
                self.assertEqual(4, result["counts"]["passed"])
                self.assertEqual("incomplete", result["state"])
                self.assertIn("No passed test cases in required report context: " + report,
                              result["missing_evidence"])

    def test_passed_synthetic_model_reports_cannot_promote_a_failed_command_or_job(self):
        for failure in ("command", "job"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(failure=failure):
                root = Path(temporary)
                args, _ = self.fixture(root)
                if failure == "command":
                    command = root / "synthetic-host-build.json"
                    command.write_text(json.dumps({"state": "failed", "return_code": 1, "source": IDENTITY}),
                                       encoding="utf-8")
                    args.command_result = [command]
                result = self.collect(args, root, "failure" if failure == "job" else "success")
                self.assertEqual(4, result["counts"]["passed"])
                self.assertEqual("incomplete", result["state"])
                if failure == "command":
                    self.assertIn("Command/smoke evidence did not pass: synthetic-host-build.json", result["errors"])
                else:
                    self.assertEqual("failure", result["job_status"])


class BuildContractsContextCollectorTests(unittest.TestCase):
    """Owned synthetic path/status fixtures only; not the retained original B1 results."""

    synthetic_pass = ('<testsuite name="synthetic.build-contract-context" tests="1">'
                      '<testcase classname="synthetic.build-contract-context" name="authored-only"/></testsuite>')

    def fixture(self, root: Path):
        environment = root / "environment.json"
        environment.write_text(json.dumps({"state": "passed", "source": IDENTITY}), encoding="utf-8")
        paths = {}
        for report in BUILD_CONTRACTS_REPORTS:
            path = root / report
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(self.synthetic_pass, encoding="utf-8")
            paths[report] = path
        unrelated = root / "modules/provider-build-contracts/build/test-results/test/TEST-unrelated.xml"
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_text(self.synthetic_pass, encoding="utf-8")
        args = argparse.Namespace(profile="synthetic-build-contract-context-wiring", environment=environment,
                                  reports=["modules/*/build/test-results/**/*.xml"],
                                  require_report=list(BUILD_CONTRACTS_REPORTS), require_tests=True,
                                  artifact=[], require_artifact=[], command_result=[])
        return args, paths

    def collect(self, args, root, job_status="success"):
        with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
             patch.dict(os.environ, {"KT_CI_JOB_STATUS": job_status}):
            return collect_evidence.collect(args, root)

    def test_only_six_exact_synthetic_build_contract_report_paths_satisfy_the_authored_contexts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, _ = self.fixture(root)
            result = self.collect(args, root)
            self.assertEqual("passed", result["state"])
            self.assertEqual(7, result["counts"]["passed"])
            self.assertEqual([], result["missing_evidence"])
            self.assertEqual([], result["errors"])

    def test_other_passed_suites_cannot_mask_missing_empty_skipped_failed_or_errored_build_contract_reports(self):
        alternatives = {
            "empty": '<testsuite name="synthetic.build-contract-context" tests="0"/>',
            "skipped": '<testsuite tests="1" skipped="1"><testcase name="authored-only"><skipped/></testcase></testsuite>',
            "failure": '<testsuite tests="1" failures="1"><testcase name="authored-only"><failure/></testcase></testsuite>',
            "error": '<testsuite tests="1" errors="1"><testcase name="authored-only"><error/></testcase></testsuite>',
        }
        for report in BUILD_CONTRACTS_REPORTS:
            for state in ("missing", *alternatives):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report, state=state):
                    root = Path(temporary)
                    args, paths = self.fixture(root)
                    if state == "missing":
                        paths[report].unlink()
                    else:
                        paths[report].write_text(alternatives[state], encoding="utf-8")
                    result = self.collect(args, root)
                    self.assertGreater(result["counts"]["passed"], 0)
                    self.assertEqual("incomplete", result["state"])
                    self.assertIn("No passed test cases in required report context: " + report,
                                  result["missing_evidence"])
                    if state in ("failure", "error"):
                        self.assertIn("JUnit reports contain failures/errors", result["errors"])

    def test_a_neighbor_class_or_other_task_directory_cannot_substitute_for_an_exact_build_contract_context(self):
        for report in BUILD_CONTRACTS_REPORTS:
            for mutation in ("neighbor-class", "other-task-directory"):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report, mutation=mutation):
                    root = Path(temporary)
                    args, paths = self.fixture(root)
                    original = paths[report]
                    replacement = (original.with_name("TEST-synthetic.neighbor.xml") if mutation == "neighbor-class"
                                   else original.parent.parent / "otherTest" / original.name)
                    replacement.parent.mkdir(parents=True, exist_ok=True)
                    original.rename(replacement)
                    result = self.collect(args, root)
                    self.assertEqual(7, result["counts"]["passed"])
                    self.assertEqual("incomplete", result["state"])
                    self.assertIn("No passed test cases in required report context: " + report,
                                  result["missing_evidence"])

    def test_passed_synthetic_build_contract_reports_cannot_promote_a_failed_command_or_job(self):
        for failure in ("command", "job"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(failure=failure):
                root = Path(temporary)
                args, _ = self.fixture(root)
                if failure == "command":
                    command = root / "synthetic-host-build.json"
                    command.write_text(json.dumps({"state": "failed", "return_code": 1, "source": IDENTITY}),
                                       encoding="utf-8")
                    args.command_result = [command]
                result = self.collect(args, root, "failure" if failure == "job" else "success")
                self.assertEqual(7, result["counts"]["passed"])
                self.assertEqual("incomplete", result["state"])
                if failure == "command":
                    self.assertIn("Command/smoke evidence did not pass: synthetic-host-build.json", result["errors"])
                else:
                    self.assertEqual("failure", result["job_status"])


class CompletionBarrierContextCollectorTests(unittest.TestCase):
    """Owned synthetic path/status fixtures only; not the retained original 26 barrier cases."""

    synthetic_pass = ('<testsuite name="synthetic.completion-barrier-context" tests="1">'
                      '<testcase classname="synthetic.completion-barrier-context" name="authored-only"/></testsuite>')

    def fixture(self, root: Path):
        environment = root / "environment.json"
        environment.write_text(json.dumps({"state": "passed", "source": IDENTITY}), encoding="utf-8")
        paths = {}
        for report in COMPLETION_BARRIER_REPORTS:
            path = root / report
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(self.synthetic_pass, encoding="utf-8")
            paths[report] = path
        unrelated = root / "modules/kernel/build/test-results/test/TEST-unrelated.xml"
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_text(self.synthetic_pass, encoding="utf-8")
        args = argparse.Namespace(profile="synthetic-completion-barrier-context-wiring", environment=environment,
                                  reports=["modules/*/build/test-results/**/*.xml"],
                                  require_report=list(COMPLETION_BARRIER_REPORTS), require_tests=True,
                                  artifact=[], require_artifact=[], command_result=[])
        return args, paths

    def collect(self, args, root, job_status="success"):
        with patch.object(collect_evidence, "source_identity", return_value=IDENTITY), \
             patch.dict(os.environ, {"KT_CI_JOB_STATUS": job_status}):
            return collect_evidence.collect(args, root)

    def test_only_three_exact_synthetic_completion_barrier_report_paths_satisfy_the_authored_contexts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args, _ = self.fixture(root)
            result = self.collect(args, root)
            self.assertEqual("passed", result["state"])
            self.assertEqual(4, result["counts"]["passed"])
            self.assertEqual([], result["missing_evidence"])
            self.assertEqual([], result["errors"])

    def test_other_passed_suites_cannot_mask_missing_empty_skipped_failed_or_errored_completion_barrier_reports(self):
        alternatives = {
            "empty": '<testsuite name="synthetic.completion-barrier-context" tests="0"/>',
            "skipped": '<testsuite tests="1" skipped="1"><testcase name="authored-only"><skipped/></testcase></testsuite>',
            "failure": '<testsuite tests="1" failures="1"><testcase name="authored-only"><failure/></testcase></testsuite>',
            "error": '<testsuite tests="1" errors="1"><testcase name="authored-only"><error/></testcase></testsuite>',
        }
        for report in COMPLETION_BARRIER_REPORTS:
            for state in ("missing", *alternatives):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report, state=state):
                    root = Path(temporary)
                    args, paths = self.fixture(root)
                    if state == "missing":
                        paths[report].unlink()
                    else:
                        paths[report].write_text(alternatives[state], encoding="utf-8")
                    result = self.collect(args, root)
                    self.assertGreater(result["counts"]["passed"], 0)
                    self.assertEqual("incomplete", result["state"])
                    self.assertIn("No passed test cases in required report context: " + report,
                                  result["missing_evidence"])
                    if state in ("failure", "error"):
                        self.assertIn("JUnit reports contain failures/errors", result["errors"])

    def test_a_neighbor_class_or_other_task_directory_cannot_substitute_for_an_exact_completion_barrier_context(self):
        for report in COMPLETION_BARRIER_REPORTS:
            for mutation in ("neighbor-class", "other-task-directory"):
                with tempfile.TemporaryDirectory() as temporary, self.subTest(report=report, mutation=mutation):
                    root = Path(temporary)
                    args, paths = self.fixture(root)
                    original = paths[report]
                    replacement = (original.with_name("TEST-synthetic.neighbor.xml") if mutation == "neighbor-class"
                                   else original.parent.parent / "otherTest" / original.name)
                    replacement.parent.mkdir(parents=True, exist_ok=True)
                    original.rename(replacement)
                    result = self.collect(args, root)
                    self.assertEqual(4, result["counts"]["passed"])
                    self.assertEqual("incomplete", result["state"])
                    self.assertIn("No passed test cases in required report context: " + report,
                                  result["missing_evidence"])

    def test_passed_synthetic_completion_barrier_reports_cannot_promote_a_failed_command_or_job(self):
        for failure in ("command", "job"):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(failure=failure):
                root = Path(temporary)
                args, _ = self.fixture(root)
                if failure == "command":
                    command = root / "synthetic-host-build.json"
                    command.write_text(json.dumps({"state": "failed", "return_code": 1, "source": IDENTITY}),
                                       encoding="utf-8")
                    args.command_result = [command]
                result = self.collect(args, root, "failure" if failure == "job" else "success")
                self.assertEqual(4, result["counts"]["passed"])
                self.assertEqual("incomplete", result["state"])
                if failure == "command":
                    self.assertIn("Command/smoke evidence did not pass: synthetic-host-build.json", result["errors"])
                else:
                    self.assertEqual("failure", result["job_status"])


if __name__ == "__main__":
    unittest.main()
