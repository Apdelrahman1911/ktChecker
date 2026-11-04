"""Authored workflow wiring and synthetic collector tests, not hosted execution."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
import unittest
from unittest.mock import patch

try:
    from . import ci_common, collect_evidence
except ImportError:
    import ci_common
    import collect_evidence


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
MODULES = ("kernel", "project-model", "provider-architecture", "review-controller")
REPORTS = tuple(f"modules/{module}/build/test-results/test/*.xml" for module in MODULES) + (
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.KernelPlanIntegrationTest.xml",
    "modules/kernel/build/test-results/test/TEST-dev.ktchecker.kernel.KernelStressTest.xml",
)
STRESS = "modules/kernel/build/reports/stress/kernel-100-patches.json"


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
