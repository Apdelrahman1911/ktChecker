"""Authored workflow wiring and synthetic collector/license-gate tests, not hosted execution."""

import argparse
import io
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
import unittest
from unittest.mock import patch

try:
    from . import ci_common, collect_evidence, report_distribution
except ImportError:
    import ci_common
    import collect_evidence
    import report_distribution


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


class DistributionUploadWorkflowTests(unittest.TestCase):
    gate_name = "Require reviewed licenses before owner-approved preview distribution upload"
    upload_name = "Retain the actual tested unsigned preview distribution"
    inventory_name = "Inventory actual dependency bytes, declared licenses and retained notices"
    approval = "vars.KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED == 'true'"
    review_id = "distribution_upload_license_review"
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

    def assert_upload_guard(self, document):
        gate = self.step(document, self.gate_name)
        upload = self.step(document, self.upload_name)
        self.assertEqual([f"success() && {self.approval}"], self.conditions(gate))
        self.assertEqual([f"success() && {self.approval} && steps.{self.review_id}.outcome == 'success'"],
                         self.conditions(upload))
        self.assertEqual([self.review_id], re.findall(r"^        id: ([^\n]+)$", gate, re.MULTILINE))
        for step in (gate, upload):
            self.assertNotIn("continue-on-error:", step)
        self.assertEqual(self.report_command(self.review_outputs, strict=True),
                         authored_command(document, self.gate_name))

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
        self.assertEqual(["modules/cli/build/distributions/*.zip", "modules/cli/build/distributions/*.tar",
                          *self.inventory_outputs, *self.review_outputs],
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
            (f"if: success() && {self.approval} && steps.{self.review_id}.outcome == 'success'\n", "if: success()\n"),
            (f"steps.{self.review_id}.outcome", f"steps.{self.review_id}.conclusion"),
            (f"id: {self.review_id}\n", "id: another_review\n"),
            (f"id: {self.review_id}\n", f"id: {self.review_id}\n        continue-on-error: true\n"),
            ("          --require-license-review\n", ""),
            (f"--output {self.review_outputs[0]}", f"--output {self.inventory_outputs[0]}"),
            (self.approval, "vars.KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED != 'false'"),
        )
        for old, new in mutations:
            with self.subTest(mutation=old):
                candidate = document.replace(old, new)
                self.assertNotEqual(document, candidate)
                with self.assertRaises(AssertionError):
                    self.assert_upload_guard(candidate)

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
            | set(DistributionUploadWorkflowTests.review_outputs) | {
                "modules/*/build/test-results/**/*.xml", "tests/audit/build/test-results/**/*.xml",
                "modules/kernel/build/reports/stress/*.json",
                "modules/cli/build/distributions/*.zip", "modules/cli/build/distributions/*.tar",
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


if __name__ == "__main__":
    unittest.main()
