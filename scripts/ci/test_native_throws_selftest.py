"""Source contracts and inert, mocked repository-recipe controls ONLY.

No test launches Python children, Java, Gradle, curl, Native, a compiler, a
supervisor, or an Apple provider. Synthetic files/ZIPs/receipts are not execution
or qualification evidence. The existing collector test's explicitly synthetic
POSIX fixture-IO facade keeps these controls portable without changing host OS
APIs or permitting any real process. Hosted workflow behavior is not tested.
"""

import argparse
import ast
from contextlib import ExitStack, contextmanager, redirect_stderr, redirect_stdout
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shlex
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile

try:
    from . import run_native_throws_selftest as producer
    from . import run_native_throws_checks as collector
    from . import test_native_throws_ci as support
    from . import ci_common as common
except ImportError:
    import run_native_throws_selftest as producer
    import run_native_throws_checks as collector
    import test_native_throws_ci as support
    import ci_common as common


COMMIT = "1" * 40
ENROLLMENT_DIGEST = "sha256:" + "3" * 64
RAW_DIGEST = "sha256:" + "4" * 64
IDENTITY = {"commit": COMMIT, "dirty": False, "file_count": 10,
    "working_tree_digest": "sha256:" + "2" * 64, "digest_scope": collector.SOURCE_SCOPE,
    "unsupported_directories": 0}
PRIVATE = "SYNTHETIC_PRIVATE_CANARY /private/owner/path token=not-a-real-secret"
CONTEXT = {"run_id": "1234", "run_attempt": "1", "job": producer.JOB}
CI_ENV = {
    "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": producer.REPOSITORY,
    "GITHUB_SERVER_URL": "https://github.com", "GITHUB_RUN_ID": CONTEXT["run_id"],
    "GITHUB_RUN_ATTEMPT": "1", "GITHUB_JOB": producer.JOB,
    "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_SHA": COMMIT,
    "GITHUB_WORKFLOW_SHA": COMMIT, "GITHUB_WORKFLOW": producer.WORKFLOW_NAME,
    "GITHUB_REF": "refs/heads/main", "GITHUB_ACTOR": "synthetic-owner",
    "GITHUB_TRIGGERING_ACTOR": "synthetic-owner", "RUNNER_ENVIRONMENT": "github-hosted",
    "RUNNER_OS": "macOS", "RUNNER_ARCH": "ARM64",
    "GITHUB_WORKFLOW_REF": producer.REPOSITORY + "/" + producer.WORKFLOW + "@refs/heads/main",
}


def event():
    return {"inputs": {"confirm_policy": "true", "expected_commit": COMMIT},
        "repository": {"full_name": producer.REPOSITORY}, "sender": {"login": "synthetic-owner"}}


def command_result(stdout=b"", stderr=b"", **changes):
    value = {"state": "completed", "execution_started": True, "return_code": 0,
        "cleanup_protocol_completed": True, "group_quiescence_observed": True,
        "escaped_descendants_proven_absent": False, "elapsed_seconds": 0.001,
        "stdout_digest": collector.digest_bytes(stdout), "stderr_digest": collector.digest_bytes(stderr),
        "code": None}
    value.update(changes)
    return value


def collector_result(passed=True):
    args = argparse.Namespace(cohort="first-mac", target=[producer.TARGET], authority_digest=ENROLLMENT_DIGEST,
        global_timeout=1200, case_timeout=180, max_output_bytes=33554432, max_cohort_bytes=268435456)
    value = collector.base_result(args)
    value["source"] = copy.deepcopy(IDENTITY)
    if passed:
        value.update(state="passed_controls_only", required_status="passing_controls_only",
            provider_controls_attempted=True, source_and_launch_tuple_verified_final=True)
        value["counts"] = {"selected_invocations": 8, "attempted": 8, "passed": 8,
            "observed_incomplete": 0, "pending_final_verification": 0, "failed": 0, "not_attempted": 0}
        for row in value["cases"]:
            row.update(state="passed", passing_control=True, source_and_launch_tuple_verified_before=True,
                source_and_launch_tuple_verified_after=True, command=command_result())
    else:
        value.update(state="failed", required_status="failed", errors=["native_throws_case_evidence_unavailable"])
    return value


class AdmissionTests(unittest.TestCase):
    def denied(self, *, env=None, payload=None, policy=producer.POLICY_ID, commit=COMMIT):
        with self.assertRaises(producer.SelftestError):
            producer.admit(policy, commit, copy.deepcopy(CI_ENV if env is None else env),
                copy.deepcopy(event() if payload is None else payload))

    def test_only_exact_explicit_dispatch_has_a_prior_repository_testing_scope(self):
        for confirmation in (True, "true"):
            payload = event()
            payload["inputs"]["confirm_policy"] = confirmation
            value = producer.admit(producer.POLICY_ID, COMMIT, CI_ENV, payload)
            self.assertEqual(CONTEXT, value["context"])
            self.assertEqual(COMMIT, value["workflow_sha"])
            self.assertIs(True, value["confirmation"])
        for confirmation in (False, "false", "True", "1", "", 1, 1.0, None, []):
            with self.subTest(confirmation=confirmation):
                payload = event()
                payload["inputs"]["confirm_policy"] = confirmation
                self.denied(payload=payload)
        for name in ("push", "pull_request", "pull_request_target", "schedule", "workflow_run", ""):
            with self.subTest(event=name):
                self.denied(env={**CI_ENV, "GITHUB_EVENT_NAME": name})

    def test_policy_commit_and_dispatch_inputs_are_closed(self):
        for commit in ("a" * 39, "a" * 41, "A" * 40, "a" * 64, "main", "", None, 1):
            with self.subTest(commit=commit):
                self.denied(commit=commit)
        self.denied(policy="native-throws-all20")
        for key in ("GITHUB_SHA", "GITHUB_WORKFLOW_SHA"):
            self.denied(env={**CI_ENV, key: "a" * 40})
        for inputs in ({}, {"expected_commit": COMMIT}, {"confirm_policy": "true"},
                       {**event()["inputs"], "authority": PRIVATE}, {**event()["inputs"], "expected_commit": "a" * 40}):
            with self.subTest(inputs=inputs):
                self.denied(payload={**event(), "inputs": inputs})

    def test_reruns_require_a_new_explicit_dispatch_not_replayed_labels(self):
        for attempt in ("2", "01", "0", "", None, 1):
            with self.subTest(attempt=attempt):
                self.denied(env={**CI_ENV, "GITHUB_RUN_ATTEMPT": attempt})

    def test_repository_workflow_job_ref_actor_and_host_labels_are_exact(self):
        mutations = {"GITHUB_ACTIONS": "false", "GITHUB_REPOSITORY": "other/repo",
            "GITHUB_SERVER_URL": "https://example.invalid", "GITHUB_WORKFLOW_REF": "other/workflow",
            "GITHUB_WORKFLOW": "other workflow", "GITHUB_JOB": "other-job", "GITHUB_REF": "refs/tags/v1",
            "GITHUB_ACTOR": "another-owner", "GITHUB_TRIGGERING_ACTOR": "another-owner",
            "GITHUB_RUN_ID": "0", "RUNNER_ENVIRONMENT": "self-hosted", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"}
        for key, value in mutations.items():
            with self.subTest(key=key):
                self.denied(env={**CI_ENV, key: value})
        for ref in ("refs/heads/a/../b", "refs/heads//a", "refs/heads/.", "refs/heads/a\n"):
            with self.subTest(ref=ref):
                self.denied(env={**CI_ENV, "GITHUB_REF": ref,
                    "GITHUB_WORKFLOW_REF": producer.REPOSITORY + "/" + producer.WORKFLOW + "@" + ref})
        valid_ref = "refs/heads/reviewed/topic-1"
        valid_env = {**CI_ENV, "GITHUB_REF": valid_ref,
            "GITHUB_WORKFLOW_REF": producer.REPOSITORY + "/" + producer.WORKFLOW + "@" + valid_ref}
        self.assertEqual(valid_env["GITHUB_WORKFLOW_REF"],
            producer.admit(producer.POLICY_ID, COMMIT, valid_env, event())["workflow_ref"])
        for key in ("repository", "sender"):
            self.denied(payload={**event(), key: {"full_name": "other/repo", "login": "another-owner"}})

    def test_malformed_event_and_context_shapes_are_sanitized_denials(self):
        for payload in ([], "bad", 1):
            with self.subTest(payload=payload):
                self.denied(payload=payload)
        for key in ("inputs", "repository", "sender"):
            for value in (None, [], "bad", 1):
                with self.subTest(key=key, value=value):
                    self.denied(payload={**event(), key: value})
        for key in ("GITHUB_REF", "GITHUB_ACTOR", "GITHUB_RUN_ID"):
            self.denied(env={**CI_ENV, key: None})
        with self.assertRaises(producer.SelftestError):
            producer.admit(producer.POLICY_ID, COMMIT, None, event())
        with self.assertRaises(producer.SelftestError):
            producer.admit(producer.POLICY_ID, COMMIT, CI_ENV, None)

    def test_cli_exposes_only_policy_and_expected_commit_without_argument_transcripts(self):
        with patch.object(producer, "run", return_value=0) as run:
            self.assertEqual(0, producer.main(["--policy", producer.POLICY_ID, "--expected-commit", COMMIT]))
            run.assert_called_once_with(producer.POLICY_ID, COMMIT)
        for option in ("--fixture", "--task", "--url", "--authority", "--authority-digest", "--native-home",
                       "--output", "--enroll-existing", "--target", "--cohort", "--global-timeout", "--pol", "--expected"):
            with self.subTest(option=option), patch.object(producer, "run") as run:
                stdout, stderr = io.StringIO(), io.StringIO()
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    code = producer.main(["--policy", producer.POLICY_ID, "--expected-commit", COMMIT, option, PRIVATE])
                self.assertEqual(1, code)
                run.assert_not_called()
                self.assertNotIn(PRIVATE, stdout.getvalue() + stderr.getvalue())


class SourceAndWorkflowContracts(unittest.TestCase):
    def workflow_permission_maps(self, document):
        """Fail-closed reader for this workflow's literal permission maps ONLY.

        This is a source contract, not a YAML parser or hosted-runner evidence.
        Global and every job override must use the same narrow contents scope;
        scalars, anchors, quoted keys, inline nonempty maps and nesting fail.
        """
        lines = document.splitlines()
        maps, jobs = {}, set()
        in_jobs, job = False, None
        for index, original in enumerate(lines):
            line = original.split("#", 1)[0].rstrip()
            if not line.strip():
                continue
            self.assertNotRegex(line, r"^[ ]*\t", "Literal space indentation required")
            indent = len(line) - len(line.lstrip(" "))
            if indent == 0:
                in_jobs, job = line == "jobs:", None
            elif in_jobs and indent == 2:
                owner = re.fullmatch(r"  ([A-Za-z0-9_-]+):", line)
                self.assertIsNotNone(owner, "Literal job mapping required")
                job = owner.group(1)
                self.assertNotIn(job, jobs, "Duplicate job mapping")
                jobs.add(job)
            header = re.fullmatch(r" *(?:permissions|\"permissions\"|'permissions') *: *(.*)", line)
            if header is None:
                continue
            self.assertTrue(line[indent:].startswith("permissions:"), "Literal permissions key required")
            if indent == 0:
                scope = "$global"
            else:
                self.assertEqual(4, indent, "Permissions may only be global or job-local")
                self.assertTrue(in_jobs and job is not None, "Job-local permissions need a job owner")
                scope = job
            self.assertNotIn(scope, maps, "Duplicate permission map")
            scalar = header.group(1)
            permissions = {}
            if scalar != "{}":
                self.assertEqual("", scalar, "Permission scalars/aliases/inline maps are not authorized")
                for following in lines[index + 1:]:
                    child = following.split("#", 1)[0].rstrip()
                    if not child.strip():
                        continue
                    self.assertNotRegex(child, r"^[ ]*\t", "Literal space indentation required")
                    child_indent = len(child) - len(child.lstrip(" "))
                    if child_indent <= indent:
                        break
                    self.assertEqual(indent + 2, child_indent, "Literal flat permission mapping required")
                    pair = re.fullmatch(r" *([a-z][a-z-]*): *(read|none)", child)
                    self.assertIsNotNone(pair, "Only literal read/none permission values are authorized")
                    self.assertNotIn(pair.group(1), permissions, "Duplicate permission entry")
                    permissions[pair.group(1)] = pair.group(2)
                self.assertTrue(permissions, "Null permission maps are not authorized")
            self.assertLessEqual(set(permissions), {"contents"}, "Only the fixed contents permission scope is authorized")
            maps[scope] = permissions
        self.assertEqual({"contents": "read"}, maps.get("$global"), "Exact global read-only permissions required")
        return maps

    def test_policy_is_fixed_narrow_and_does_not_treat_hashes_as_consent(self):
        policy = producer.POLICY
        self.assertEqual("fixed_repository_selftest_only", policy["scope"])
        self.assertEqual("new_explicit_dispatch_required", policy["reruns"])
        self.assertEqual(list(collector.FIRST_MAC), policy["controls"])
        self.assertEqual(("iosArm64", "first-mac", 1200, 180),
            (policy["target"], policy["cohort"], policy["budgets"]["collector_seconds"], policy["budgets"]["case_seconds"]))
        self.assertEqual([":compileCommonMainKotlinMetadata", ":compileKotlinIosArm64"], policy["dependency_recipe"]["tasks"])
        self.assertEqual("empty-list-nonempty", policy["dependency_recipe"]["case"])
        self.assertFalse(policy["dependency_recipe"]["generated_target_binary_link_or_execution"])
        self.assertFalse(policy["dependency_recipe"]["transitive_network_closure_enforced_or_qualified"])
        self.assertEqual("author_enrolled_not_upstream_sha256_or_signature", policy["native_archive"]["sha256_origin"])
        self.assertTrue(collector.valid_digest(producer.recipe_digest()))
        changed = copy.deepcopy(policy)
        changed["budgets"]["case_seconds"] = 179
        before = producer.recipe_digest()
        with patch.object(producer, "POLICY", changed):
            self.assertNotEqual(before, producer.recipe_digest())

    def test_existing_collector_and_provisioner_source_are_unchanged_reviewed_inputs(self):
        for module, expected in ((collector, "c2a2aacb33592383036adf57f97a47716ca8bf98f66ec71c37b906aa63f68a1b"),
                                (producer.native, "5a819d25884ded97302c3e83f0d8721eac2fbb92d7b2df15cb688c23f005a68e")):
            with self.subTest(module=module.__name__):
                self.assertEqual(expected, hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest())

    def test_embedded_jar_recipe_is_fresh_narrow_and_java_wire_module_is_optional(self):
        self.assertEqual((":provider-native", ":provider-runtime", ":snapshot", ":contracts"), producer.PROJECTS)
        self.assertEqual(producer.PROJECTS + (":worker-protocol",), producer.ALLOWED_PROJECTS)
        self.assertEqual(set(collector.COMPONENT_ENTRIES), set(producer.COMPONENT_PROJECTS))
        self.assertEqual(set(producer.PROJECTS), set(producer.COMPONENT_PROJECTS.values()))
        self.assertNotIn(":worker-protocol:compileKotlin", producer.REQUIRED_BUILD_TASKS)
        self.assertIn("dependsOn(p.tasks.named('jar'))", producer.JAR_INIT)
        self.assertIn("dependsOn(configuration)", producer.JAR_INIT)
        self.assertIn("getByName('runtimeClasspath')", producer.JAR_INIT)
        self.assertIn("StandardOpenOption.CREATE_NEW", producer.JAR_INIT)
        self.assertIn("owners.containsAll(required)", producer.JAR_INIT)
        self.assertIn("owners.unique(false).size() != owners.size()", producer.JAR_INIT)
        self.assertIn("[A-Za-z0-9_.+-]+\\.jar", producer.JAR_INIT)
        for prohibited in ("JavaExec", "nativeThrowsFixture", "installDist", ":cli:", "new URL", "exec {"):
            self.assertNotIn(prohibited, producer.JAR_INIT)

    def test_producer_has_no_new_process_implementation_and_one_same_process_collector_call(self):
        source = Path(producer.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        imports = [alias.name for node in ast.walk(tree) if isinstance(node, ast.Import) for alias in node.names]
        imports += [(node.module + "." if node.module else "") + alias.name
            for node in ast.walk(tree) if isinstance(node, ast.ImportFrom) for alias in node.names]
        for prohibited in ("subprocess", "urllib.request"):
            self.assertFalse(any(name == prohibited or name.startswith(prohibited + ".") for name in imports))
        process_calls = {"system", "popen", "fork", "forkpty", "posix_spawn", "posix_spawnp"}
        self.assertFalse(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name) and node.func.value.id == "os"
            and (node.func.attr in process_calls or node.func.attr.startswith(("exec", "spawn"))) for node in ast.walk(tree)))
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
            and isinstance(node.func.value, ast.Name) and node.func.value.id == "collector"]
        self.assertEqual(1, sum(node.func.attr == "main" for node in calls))
        self.assertEqual(1, sum(node.func.attr == "run_bounded" for node in calls))
        for node in ast.walk(tree):
            if isinstance(node, ast.Constant) and isinstance(node.value, str) and node.value.startswith("native_selftest_"):
                self.assertIn(node.value, producer.ERRORS)

    def test_workflow_is_manual_exact_commit_hosted_and_bounded_with_no_cache_or_write_permission(self):
        document = (producer.REPO_ROOT / producer.WORKFLOW).read_text(encoding="utf-8")
        self.assertIn("on:\n  workflow_dispatch:\n", document)
        for event_name in ("push:", "pull_request:", "pull_request_target:", "schedule:", "workflow_run:"):
            self.assertNotIn(event_name, document)
        for literal in ("default: false", "type: boolean", "contents: read", "cancel-in-progress: false",
                        "runs-on: macos-26", "timeout-minutes: 90", "timeout-minutes: 75",
                        "ref: ${{ github.workflow_sha }}", "persist-credentials: false", "fetch-depth: 1",
                        '[[ "$EXPECTED_COMMIT" =~ ^[0-9a-f]{40}$ ]]', '[[ "$EXPECTED_COMMIT" == "$EVENT_COMMIT" ]]',
                        '[[ "$EXPECTED_COMMIT" == "$WORKFLOW_COMMIT" ]]', '[[ "$GITHUB_RUN_ATTEMPT" == \'1\' ]]',
                        "github.event_name == 'workflow_dispatch' && inputs.confirm_policy == true"):
            self.assertIn(literal, document)
        # The workflow targets macOS even when this source check runs on Windows.
        self.assertIn(producer.DEVELOPER_DIR.as_posix(), document)
        self.workflow_permission_maps(document)
        self.assertNotRegex(document, r"\b(?:cache|secrets|self-hosted)\s*:")
        action_rows = re.findall(r"^\s+uses: ([^\s#]+)", document, re.M)
        expected = ["actions/checkout@" + producer.POLICY["setup_actions"]["checkout"],
            "actions/setup-python@" + producer.POLICY["setup_actions"]["python"],
            "actions/setup-java@" + producer.POLICY["setup_actions"]["java"],
            "actions/setup-java@" + producer.POLICY["setup_actions"]["java"],
            "actions/upload-artifact@" + producer.POLICY["setup_actions"]["upload"]]
        self.assertEqual(expected, action_rows)
        self.assertEqual(["21", "25"], re.findall(r"java-version: '([0-9]+)'", document))
        command = re.search(r"run: >-\n((?:[ ]{10}.+\n)+)", document).group(1)
        self.assertEqual(["python", "scripts/ci/run_native_throws_selftest.py", "--policy", producer.POLICY_ID,
            "--expected-commit", "$NATIVE_THROWS_EXPECTED_COMMIT"], shlex.split(command))
        self.assertNotIn("${{", command)

    def test_workflow_mac_developer_directory_is_independent_of_checker_host_path_flavour(self):
        literal='/Applications/Xcode_26.4.1.app/Contents/Developer'
        for flavour in (PurePosixPath,PureWindowsPath):
            with self.subTest(flavour=flavour.__name__), patch.object(producer,'DEVELOPER_DIR',flavour(literal)):
                self.test_workflow_is_manual_exact_commit_hosted_and_bounded_with_no_cache_or_write_permission()

    def test_workflow_different_developer_directory_is_not_accepted_after_separator_conversion(self):
        for path in (PurePosixPath('/Applications/Other.app/Contents/Developer'),
                     PureWindowsPath('C:/Applications/Xcode_26.4.1.app/Contents/Developer')):
            with self.subTest(path_flavour=type(path).__name__), patch.object(producer,'DEVELOPER_DIR',path):
                with self.assertRaises(AssertionError):
                    self.test_workflow_is_manual_exact_commit_hosted_and_bounded_with_no_cache_or_write_permission()

    def test_permission_mutations_reject_global_and_every_job_write_override(self):
        document = (producer.REPO_ROOT / producer.WORKFLOW).read_text(encoding="utf-8")
        global_map = "permissions:\n  contents: read"
        job_header = "  " + producer.JOB + ":\n"
        self.assertEqual(1, document.count(global_map))
        self.assertEqual(1, document.count(job_header))
        read_jobs = document.replace(job_header, job_header + "    permissions:\n      contents: read\n", 1)
        read_jobs += "\n  synthetic-other-job:\n    permissions: {}\n    runs-on: macos-26\n    steps:\n      - run: ':'\n"
        self.assertEqual({"$global": {"contents": "read"}, producer.JOB: {"contents": "read"},
            "synthetic-other-job": {}}, self.workflow_permission_maps(read_jobs))
        for replacement in ("permissions:\n  contents: write", "permissions: write-all",
                            "permissions:\n  contents: read\n  id-token: write",
                            "permissions: {contents: write}", "permissions: *unreviewed",
                            "permissions:\n  contents: read\n  contents: read"):
            with self.subTest(scope="global", replacement=replacement), self.assertRaises(AssertionError):
                self.workflow_permission_maps(read_jobs.replace(global_map, replacement, 1))
        for owner, old_map in ((producer.JOB, "    permissions:\n      contents: read\n"),
                              ("synthetic-other-job", "    permissions: {}\n")):
            for replacement in ("    permissions:\n      contents: write\n", "    permissions: write-all\n",
                                "    permissions:\n      contents: read\n      id-token: write\n",
                                "    permissions: {contents: write}\n", "    permissions: *unreviewed\n",
                                "    permissions:\n      contents: read\n      contents: read\n"):
                owner_header = "  " + owner + ":\n"
                mutation = read_jobs.replace(owner_header + old_map, owner_header + replacement, 1)
                self.assertNotEqual(read_jobs, mutation)
                with self.subTest(scope=owner, replacement=replacement), self.assertRaises(AssertionError):
                    self.workflow_permission_maps(mutation)

    def test_upload_has_only_two_literal_sanitized_json_paths_and_short_retention(self):
        document = (producer.REPO_ROOT / producer.WORKFLOW).read_text(encoding="utf-8")
        upload = document.split("Retain only the two sanitized self-test summaries", 1)[1]
        paths = re.search(r"path: \|\n((?:[ ]{12}.+\n)+)", upload).group(1)
        self.assertEqual(["build/ci-native-throws-selftest/policy-build.json",
            "build/ci-native-throws-selftest/native-throws-controls.json"], [line.strip() for line in paths.splitlines()])
        self.assertIn("if: always()", upload)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("retention-days: 7", upload)
        self.assertNotRegex(paths, r"[*!?]|private|\.jar|\.zip|\.log|\.tar|\.klib")


class OwnedFixtureTests(unittest.TestCase):
    """Every process/tool/helper execution boundary is forbidden by default."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="synthetic-native-selftest-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve(strict=True)
        self.stage = self.root / "build/ci-native-throws-selftest"
        self.paths = producer.Paths(self.root, self.stage, self.stage / "private",
            self.root / "jdk21", self.root / "jdk25", self.root / "python")
        for directory in (self.paths.private / "commands", self.paths.gradle_home, self.paths.home):
            directory.mkdir(parents=True)
        for major, home in ((21, self.paths.java21), (25, self.paths.java25)):
            for name in collector.JDK_FILES:
                support.write(home / name, f'JAVA_VERSION="{major}.0.5"\n'.encode("ascii") if name == "release"
                    else b"inert selected JVM file; never execute", name == "bin/java")
        self.git = self.root / "git"
        self.developer = self.root / "SyntheticXcode.app/Contents/Developer"
        self.cc = self.developer / "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"
        self.xcodebuild, self.xcrun = self.root / "xcodebuild", self.root / "xcrun"
        for path in (self.paths.python, self.git, self.cc, self.xcodebuild, self.xcrun,
                     self.developer.parent / "Info.plist"):
            support.write(path, b"inert selected tool; never execute", True)
        self.event_path = self.root / "event.json"
        support.write(self.event_path, producer.encoded(event()))
        self.env = {**CI_ENV, "GITHUB_WORKSPACE": str(self.root), "GITHUB_EVENT_PATH": str(self.event_path),
            "JAVA_HOME_21_ARM64": str(self.paths.java21), "JAVA_HOME_25_ARM64": str(self.paths.java25),
            "JAVA_HOME": str(self.paths.java25)}
        self.dispatch = producer.admit(producer.POLICY_ID, COMMIT, self.env, event())
        self.budget = collector.Budget.start(producer.RECIPE_SECONDS)
        self.summary = {"steps": []}
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        facade = support.synthetic_posix_os(self.root)
        facade.chmod = lambda value, mode: os.chmod(facade.fixture_io.owned_path(value), mode)
        # Path.open uses a different owned-fixture IO route than os.open. Its
        # fsync is explicitly simulated here, never evidence of durability or
        # a reason to delegate an unknown descriptor to a host OS API.
        facade.fsync = Mock(return_value=None)
        self.stack.callback(facade.fixture_io.assert_closed)
        self.stack.enter_context(patch.object(producer, "os", facade))
        self.stack.enter_context(patch.object(collector, "os", facade))
        self.stack.enter_context(patch.dict(os.environ, self.env, clear=True))
        fake_platform = SimpleNamespace(system=lambda: "Darwin", machine=lambda: "arm64")
        self.stack.enter_context(patch.object(producer, "platform", fake_platform))
        self.stack.enter_context(patch.object(collector, "platform", fake_platform))
        self.stack.enter_context(patch.object(producer, "sys", SimpleNamespace(executable=str(self.paths.python), version_info=(3, 12, 0))))
        for module, name, value in ((producer, "GIT", self.git), (producer, "CC", self.cc),
                                   (producer, "DEVELOPER_DIR", self.developer), (collector, "XCODEBUILD", self.xcodebuild),
                                   (collector, "XCRUN", self.xcrun)):
            self.stack.enter_context(patch.object(module, name, value))
        for module, name in ((collector, "run_bounded"), (collector, "source_identity"), (collector, "main"),
                             (collector, "process_gate"), (producer.native, "run_logged"),
                             (producer.native, "source_identity"), (producer.native, "provision")):
            blocked = self.stack.enter_context(patch.object(module, name, side_effect=AssertionError("No actual execution authorized")))
            # A swallowed tripwire exception must not make a denial test pass.
            self.addCleanup(blocked.assert_not_called)
        process_tripwire = Mock(side_effect=AssertionError("No actual process authorized"))
        no_process = SimpleNamespace(Popen=process_tripwire, run=process_tripwire, call=process_tripwire,
            check_call=process_tripwire, check_output=process_tripwire, DEVNULL=collector.subprocess.DEVNULL,
            PIPE=collector.subprocess.PIPE, STDOUT=collector.subprocess.STDOUT)
        for module in (collector, common):
            # Replace only each helper's module reference, never host OS APIs
            # or the globally shared subprocess module itself.
            self.stack.enter_context(patch.object(module, "subprocess", no_process))
        self.addCleanup(process_tripwire.assert_not_called)
        self.recipe = producer.Recipe(self.paths, self.env, self.dispatch, support.pin(self.event_path),
            self.event_path, self.budget, self.summary)

    def arm(self):
        self.recipe.source = copy.deepcopy(IDENTITY)
        self.recipe.permit_pin = producer.private_json(self.recipe.permit,
            {"synthetic_prior_permit": True, "source": IDENTITY}, self.budget)
        return self.stack.enter_context(patch.object(self.recipe, "measure_source", return_value=copy.deepcopy(IDENTITY)))

    def fake_command(self, stdout=b"synthetic output", stderr=b"", **changes):
        def execute(argv, *, directory, **kwargs):
            self.assertTrue(Path(directory).is_relative_to(self.paths.private))
            Path(directory).mkdir(mode=0o700)
            support.write(Path(directory) / "stdout.log", stdout)
            support.write(Path(directory) / "stderr.log", stderr)
            return command_result(stdout, stderr, **changes)
        return execute

    def config(self, home=None, **changes):
        values = {"airplaneMode": "false", "dependenciesUrl": producer.DEPENDENCIES_URL,
            "llvmHome.macos_arm64": "$llvm.selected", "llvm.selected": "synthetic-llvm",
            "libffiDir.macos_arm64": "synthetic-libffi", "targetSysRoot.ios_arm64": "synthetic-ios"}
        values.update(changes)
        raw = ("# inert configuration fixture\n" + "\n".join(key + "=" + value for key, value in values.items()) + "\n").encode("utf-8")
        support.write((home or self.paths.native_home) / "konan/konan.properties", raw)
        return raw


class FixedPathsAndEnvironmentTests(OwnedFixtureTests):
    def test_only_action_slots_and_a_new_ignored_output_root_are_selected(self):
        root = self.root / "fresh-repository"
        root.mkdir()
        env = {**self.env, "GITHUB_WORKSPACE": str(root)}
        value = producer.select_paths(root, env)
        self.assertEqual(root / "build/ci-native-throws-selftest/private", value.private)
        self.assertEqual(self.paths.java21, value.java21)
        self.assertEqual(self.paths.java25, value.java25)
        value.stage.mkdir(parents=True)
        with self.assertRaises(producer.SelftestError):
            producer.select_paths(root, env)

    def test_missing_swapped_ambient_or_same_jdk_slots_are_denied(self):
        root = self.root / "fresh-repository"
        root.mkdir()
        env = {**self.env, "GITHUB_WORKSPACE": str(root)}
        for changed in ({**env, "JAVA_HOME_21_ARM64": "relative"}, {**env, "JAVA_HOME_25_ARM64": None},
                        {**env, "JAVA_HOME": str(self.paths.java21)}, {**env, "JAVA_HOME_21_ARM64": str(self.paths.java25)},
                        {**env, "GITHUB_WORKSPACE": str(self.root)}):
            with self.subTest(env=changed), self.assertRaises(producer.SelftestError):
                producer.select_paths(root, changed)

    def test_environment_is_a_fixed_allowlist_without_tokens_proxies_jvm_or_agent_injections(self):
        dangerous = {key: PRIVATE for key in ("GITHUB_TOKEN", "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS", "CLASSPATH", "LD_PRELOAD",
            "DYLD_INSERT_LIBRARIES", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "SSH_AUTH_SOCK", "PYTHONPATH")}
        value = producer.safe_env(self.paths, {**self.env, **dangerous}, self.paths.java21)
        self.assertEqual(set(producer.CI_NAMES) | {"PATH", "JAVA_HOME", "GRADLE_USER_HOME", "HOME", "KONAN_DATA_DIR",
            "KONAN_USE_INTERNAL_SERVER", "DEVELOPER_DIR", "LANG", "LC_ALL", "GIT_CONFIG_NOSYSTEM", "GIT_CONFIG_GLOBAL"}, set(value))
        self.assertEqual(str(self.paths.java21), value["JAVA_HOME"])
        self.assertEqual(str(self.paths.data), value["KONAN_DATA_DIR"])
        self.assertEqual("0", value["KONAN_USE_INTERNAL_SERVER"])
        self.assertNotIn(PRIVATE, json.dumps(value))
        before = dict(os.environ)
        with self.assertRaises(RuntimeError):
            with producer.environment(value):
                self.assertEqual(value, dict(os.environ))
                raise RuntimeError("synthetic interruption")
        self.assertEqual(before, dict(os.environ))

    def test_gradle_recipe_disables_reuse_discovery_and_persistent_state_sharing(self):
        argv = producer.base_gradle(self.paths, self.paths.java21, self.paths.private / "module-project-cache")
        for flag in ("--no-daemon", "--no-configuration-cache", "--no-build-cache", "--no-parallel", "--rerun-tasks", "--max-workers=1",
                     "-Porg.gradle.java.installations.auto-download=false", "-Porg.gradle.java.installations.auto-detect=false",
                     "-Pkotlin.compiler.execution.strategy=in-process"):
            self.assertIn(flag, argv)
        self.assertEqual(str(self.root / "gradlew"), argv[0])
        self.assertIn("-Porg.gradle.java.installations.paths=" + str(self.paths.java21), argv)
        self.assertIn("-Pkotlin.project.persistent.dir=" + str(self.paths.private / "module-project-cache-kotlin-state"), argv)
        self.assertNotIn("--offline", argv)

    def test_mocked_observations_are_separate_from_authored_tool_versions(self):
        def command(label, argv, **kwargs):
            text = {"jdk21-version": b'openjdk version "21.0.5"\n', "jdk25-version": b'openjdk version "25.0.5"\n',
                "wrapper-version": b"Gradle 9.6.1\n", "xcode-version": b"Xcode 26.4.1\nBuild version 17E202\n",
                "iphoneos-version": b"26.4\n", "iphonesimulator-version": b"26.4\n"}[label]
            return command_result(text), text, b""
        with patch.object(self.recipe, "command", side_effect=command) as execute:
            java21, java25, xcode = producer.observe_tools(self.recipe)
        self.assertEqual(6, execute.call_count)
        self.assertEqual("21.0.5", java21["version"])
        self.assertEqual("25.0.5", java25["version"])
        self.assertEqual("26.4.1", xcode["version"])
        self.assertIsNone(self.summary["observed_tools"]["native"])
        self.assertEqual(21, producer.POLICY["declared_tools"]["broker_java_major"])


class PermitAndCommandTests(OwnedFixtureTests):
    def test_prior_permit_is_frozen_before_any_component_or_bootstrap_launch(self):
        with patch.object(collector, "process_gate", return_value=None), \
                patch.object(self.recipe, "measure_source", return_value=copy.deepcopy(IDENTITY)), \
                patch.object(collector, "fixture_input", return_value=({}, {})):
            self.recipe.freeze_permit()
        value = collector.strict_json(self.recipe.permit.read_bytes())
        self.assertEqual("prior_repository_native_throws_selftest_permit", value["evidence_kind"])
        self.assertEqual("explicit_reviewed_workflow_dispatch_not_hash_as_consent", value["authorization_basis"])
        self.assertEqual(self.dispatch, value["dispatch"])
        self.assertEqual(producer.POLICY, value["policy"])
        self.assertEqual(producer.recipe_digest(), value["recipe_digest"])
        self.assertEqual(str(self.paths.java21), value["selected_java21"])
        self.assertTrue(self.summary["prior_policy_confirmed"])
        self.assertEqual(support.pin(self.recipe.permit), self.recipe.permit_pin)

    def denied_source_freeze(self, identity):
        with patch.object(collector, "process_gate", return_value=None), \
                patch.object(self.recipe, "measure_source", return_value=identity), \
                self.assertRaises(producer.SelftestError):
            self.recipe.freeze_permit()
        self.assertFalse(self.recipe.permit.exists())
        self.assertTrue(self.recipe.stopped)

    def test_dirty_source_cannot_freeze_a_permit(self):
        self.denied_source_freeze({**IDENTITY, "dirty": True})

    def test_wrong_source_commit_cannot_freeze_a_permit(self):
        self.denied_source_freeze({**IDENTITY, "commit": "a" * 40})

    def test_event_drift_between_admission_and_permit_is_denied_before_source_probes(self):
        self.event_path.write_bytes(producer.encoded({**event(), "inputs": {"confirm_policy": "false", "expected_commit": COMMIT}}))
        with patch.object(collector, "process_gate") as gate, patch.object(self.recipe, "measure_source") as source, \
                self.assertRaises(producer.SelftestError):
            self.recipe.freeze_permit()
        gate.assert_not_called()
        source.assert_not_called()
        self.assertTrue(self.recipe.stopped)

    def stopped_before_launch(self, mutation):
        source = self.arm()
        mutation(source)
        with patch.object(collector, "run_bounded") as execute:
            for _ in range(2):
                with self.assertRaises((producer.SelftestError, collector.Problem, OSError)):
                    self.recipe.command("synthetic-never-launch", [str(self.paths.java21 / "bin/java"), "-version"],
                        cwd=self.root, env=self.env)
            execute.assert_not_called()
        self.assertTrue(self.recipe.stopped)

    def test_changed_permit_stops_every_later_launch(self):
        self.stopped_before_launch(lambda source: self.recipe.permit.write_bytes(b"changed synthetic permit"))

    def test_changed_event_stops_every_later_launch(self):
        self.stopped_before_launch(lambda source: self.event_path.write_bytes(b"changed synthetic event"))

    def test_changed_source_stops_every_later_launch(self):
        self.stopped_before_launch(lambda source: setattr(source, "return_value", {**IDENTITY, "working_tree_digest": RAW_DIGEST}))

    def test_changed_full_workflow_context_stops_every_later_launch(self):
        self.stopped_before_launch(lambda source: os.environ.update(GITHUB_WORKFLOW_SHA="a" * 40))

    def test_changed_run_context_stops_every_later_launch(self):
        self.stopped_before_launch(lambda source: os.environ.update(GITHUB_RUN_ATTEMPT="2"))

    def test_no_launch_without_a_prior_permit(self):
        with patch.object(collector, "run_bounded") as execute, self.assertRaises(producer.SelftestError):
            self.recipe.command("synthetic-never-launch", ["not-an-executable"], cwd=self.root, env=self.env)
        execute.assert_not_called()
        self.assertTrue(self.recipe.stopped)

    def test_bounded_command_rechecks_source_and_projects_no_argv_or_transcript(self):
        source = self.arm()
        with patch.object(collector, "run_bounded", side_effect=self.fake_command(PRIVATE.encode("utf-8"))) as execute:
            value, out, err = self.recipe.command("synthetic-success", ["inert-selected-command", PRIVATE],
                cwd=self.root, env=self.env)
        self.assertEqual(PRIVATE.encode("utf-8"), out)
        self.assertEqual(b"", err)
        self.assertTrue(collector.command_ok(value))
        self.assertEqual(2, source.call_count)
        self.assertEqual(1, execute.call_count)
        self.assertLessEqual(execute.call_args.kwargs["timeout"], 900)
        self.assertEqual(producer.MAX_LOG, execute.call_args.kwargs["max_bytes"])
        self.assertNotIn(PRIVATE, json.dumps(self.summary))
        self.assertFalse(self.summary["steps"][0]["escaped_descendants_proven_absent"])

    def failed_command_stops(self, **changes):
        self.arm()
        with patch.object(collector, "run_bounded", side_effect=self.fake_command(**changes)) as execute:
            for label in ("synthetic-failed-command", "synthetic-later-command"):
                with self.assertRaises(producer.SelftestError):
                    self.recipe.command(label, ["inert-selected-command"], cwd=self.root, env=self.env)
            self.assertEqual(1, execute.call_count)
        self.assertTrue(self.recipe.stopped)

    def test_nonzero_exit_stops_later_launches(self):
        self.failed_command_stops(return_code=1)

    def test_unconfirmed_cleanup_stops_later_launches_even_after_exit_zero(self):
        self.failed_command_stops(cleanup_protocol_completed=False)

    def test_unconfirmed_group_quiescence_stops_later_launches(self):
        self.failed_command_stops(group_quiescence_observed=False)

    def test_capture_io_uncertainty_stops_later_launches(self):
        self.arm()
        with patch.object(collector, "run_bounded", return_value=command_result()) as execute:
            with self.assertRaises(OSError):
                self.recipe.command("synthetic-missing-captures", ["inert-selected-command"], cwd=self.root, env=self.env)
            with self.assertRaises(producer.SelftestError):
                self.recipe.command("synthetic-later-command", ["inert-selected-command"], cwd=self.root, env=self.env)
            self.assertEqual(1, execute.call_count)
        self.assertTrue(self.recipe.stopped)

    def test_launcher_exception_stops_later_launches_without_private_exception_text(self):
        self.arm()
        with patch.object(collector, "run_bounded", side_effect=OSError(PRIVATE)) as execute:
            with self.assertRaises(OSError):
                self.recipe.command("synthetic-launch-error", ["inert-selected-command"], cwd=self.root, env=self.env)
            with self.assertRaises(producer.SelftestError):
                self.recipe.command("synthetic-later-command", ["inert-selected-command"], cwd=self.root, env=self.env)
            self.assertEqual(1, execute.call_count)
        self.assertNotIn(PRIVATE, json.dumps(self.summary))


class JarFixtureMixin:
    def jar_fixture(self, wire=False, extras=None):
        stage = self.paths.private / "jars"
        stage.mkdir()
        source_c = self.root / "modules/provider-runtime/native/owned_process_supervisor.c"
        builder = source_c.with_name("build_supervisor.py")
        support.write(source_c, b"inert source text; never compile")
        support.write(builder, b"inert builder text; never execute")
        groups = [(":provider-native", ("driver", "fixture_model", "provider")),
            (":provider-runtime", ("runner", "supervisor_loader")), (":snapshot", ("snapshot",)), (":contracts", ("contracts",))]
        if wire:
            groups.append((":worker-protocol", ()))
        rows = []
        for index, (project, roles) in enumerate(groups):
            name = project[1:] + "-0.1.0-dev.1.jar"
            source = self.paths.private / "module-build" / project[1:] / "libs" / name
            source.parent.mkdir(parents=True)
            with zipfile.ZipFile(source, "w") as jar:
                for role in roles:
                    jar.writestr(collector.COMPONENT_ENTRIES[role], support.inert_class(role))
                if project == ":worker-protocol":
                    jar.writestr("dev/ktchecker/worker/protocol/InertWire.class", support.inert_class("wire"))
                for entry, raw in (extras or {}).get(project, {}).items():
                    jar.writestr(entry, raw)
                if project == ":provider-runtime":
                    binary = support.inert_macho()
                    identity = {"protocolVersion": collector.PROTOCOL, "target": "macos-aarch64",
                        "sourceDigest": support.pin(source_c)["digest"], "binaryDigest": collector.digest_bytes(binary)}
                    jar.writestr(collector.NATIVE_RESOURCE + "manifest", "\n".join(identity.values()) + "\n")
                    jar.writestr(collector.NATIVE_RESOURCE + "macos-aarch64/supervisor", binary)
                    receipt = {"status": "built", "host": {"target": "macos-aarch64"},
                        "inputs": {"source": {"sha256": identity["sourceDigest"]}, "builder": {"sha256": support.pin(builder)["digest"]}},
                        "compiler": {"driverSha256": support.pin(self.cc)["digest"]}, "output": {"sha256": identity["binaryDigest"]}}
                    jar.writestr(collector.NATIVE_RESOURCE + "build-provenance.json", producer.encoded(receipt))
            output = stage / name
            shutil.copyfile(source, output)
            rows.append({"index": index, "origin": "owner_project", "project": project,
                "coordinate": "dev.ktchecker:" + project[1:] + ":0.1.0-dev.1", "source": str(source),
                "path": str(output), **support.pin(output)})
        value = {"schema_version": 1, "producer": "fixed_native_throws_jar_recipe/1", "source_root": str(self.root),
            "gradle_version": "9.6.1", "java_version": "21.0.5", "jars": rows}
        support.write(stage / "manifest.json", producer.encoded(value))
        return value

    def classpath_projection(self):
        actual = collector.classpath_input
        verify = collector.verify_absolute_pin

        def classpath(value, budget):
            def view(row, budget, maximum=collector.MAX_FILE):
                return support.SyntheticDarwinClasspathPath(self.root, verify(row, budget, maximum))
            with patch.object(collector, "verify_absolute_pin", side_effect=view):
                return actual(value, budget)
        return patch.object(collector, "classpath_input", side_effect=classpath)


class JarRecipeTests(JarFixtureMixin, OwnedFixtureTests):
    def test_required_four_component_origins_and_optional_java_only_wire_jar_validate_inert_bytes(self):
        self.jar_fixture(wire=True)
        with self.classpath_projection():
            value, manifest_pin = producer.validate_jars(self.paths, self.budget)
        self.assertEqual(5, len(value["classpath"]))
        self.assertEqual(set(collector.COMPONENT_ENTRIES), set(value["components"]))
        self.assertEqual(support.pin(self.paths.private / "jars/manifest.json"), manifest_pin)

    def test_unknown_project_duplicate_owner_missing_required_owner_and_changed_jars_are_denied(self):
        original = self.jar_fixture()
        for mutate in (lambda value: value["jars"][0].update(project=":unknown"),
                       lambda value: value["jars"][3].update(project=":snapshot"),
                       lambda value: value["jars"].pop(),
                       lambda value: value["jars"][0].update(digest=RAW_DIGEST)):
            value = copy.deepcopy(original)
            mutate(value)
            support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
            with self.subTest(mutation=mutate), self.classpath_projection(), \
                    self.assertRaises((producer.SelftestError, collector.Problem)):
                producer.validate_jars(self.paths, self.budget)

    def test_component_shadowing_by_an_optional_wire_jar_is_not_accepted(self):
        self.jar_fixture(wire=True, extras={":worker-protocol": {
            collector.COMPONENT_ENTRIES["contracts"]: support.inert_class("contracts")}})
        with self.classpath_projection(), self.assertRaises(producer.SelftestError):
            producer.validate_jars(self.paths, self.budget)

    def test_presence_of_four_owner_jars_does_not_authorize_a_component_moved_to_the_wire_jar(self):
        value = self.jar_fixture(wire=True)
        entry = collector.COMPONENT_ENTRIES["contracts"]
        for row in value["jars"]:
            if row["project"] not in {":contracts", ":worker-protocol"}:
                continue
            source = Path(row["source"])
            with zipfile.ZipFile(source) as jar:
                entries = {name: jar.read(name) for name in jar.namelist() if name != entry}
            if row["project"] == ":worker-protocol":
                entries[entry] = support.inert_class("contracts")
            with zipfile.ZipFile(source, "w") as jar:
                for name, raw in entries.items():
                    jar.writestr(name, raw)
            shutil.copyfile(source, Path(row["path"]))
            row.update(support.pin(source))
        support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
        with self.classpath_projection(), self.assertRaises(producer.SelftestError):
            producer.validate_jars(self.paths, self.budget)

    def test_unknown_extra_staged_file_and_floating_manifest_version_are_denied(self):
        value = self.jar_fixture()
        value["schema_version"] = 1.0
        support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
        with self.assertRaises(producer.SelftestError):
            producer.validate_jars(self.paths, self.budget)
        value["schema_version"] = 1
        support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
        support.write(self.paths.private / "jars/not-an-enrolled-jar.txt", b"inert extra")
        with self.classpath_projection(), self.assertRaises(producer.SelftestError):
            producer.validate_jars(self.paths, self.budget)

    def test_maven_jar_origin_is_fixed_to_the_private_cache_and_duplicates_are_denied(self):
        value = self.jar_fixture()
        source = self.paths.gradle_home / "caches/modules-2/files-2.1/synthetic/vendor/1.0/digest/vendor-1.0.jar"
        source.parent.mkdir(parents=True)
        with zipfile.ZipFile(source, "w") as jar:
            jar.writestr("synthetic/vendor/Inert.class", b"inert vendor bytes")
        output = self.paths.private / "jars/vendor-1.0.jar"
        shutil.copyfile(source, output)
        row = {"index": 4, "origin": "maven", "project": None, "coordinate": "synthetic:vendor:1.0",
            "source": str(source), "path": str(output), **support.pin(output)}
        value["jars"].append(row)
        support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
        with self.classpath_projection():
            actual, _ = producer.validate_jars(self.paths, self.budget)
        self.assertEqual(5, len(actual["classpath"]))
        for changed in ({**row, "source": str(self.paths.java21 / "bin/java")},
                        {**row, "coordinate": "not-a-fixed-coordinate"},
                        {**row, "path": value["jars"][0]["path"]}):
            value["jars"][-1] = changed
            support.write(self.paths.private / "jars/manifest.json", producer.encoded(value))
            with self.subTest(row=changed), self.classpath_projection(), self.assertRaises(producer.SelftestError):
                producer.validate_jars(self.paths, self.budget)

    def test_narrow_build_observes_wire_compile_java_not_compile_kotlin(self):
        (self.paths.private / "module-build/worker-protocol/libs").mkdir(parents=True)
        tasks = producer.REQUIRED_BUILD_TASKS + (":worker-protocol:compileJava", ":worker-protocol:jar")
        stdout = "".join("> Task " + task + "\n" for task in tasks).encode("ascii")
        with patch.object(self.recipe, "command", return_value=(command_result(stdout), stdout, b"")) as execute, \
                patch.object(producer, "validate_jars", return_value=({}, {})):
            producer.build_jars(self.recipe)
        argv = execute.call_args.args[1]
        self.assertEqual(producer.STAGE_TASK, argv[-1])
        self.assertNotIn(":worker-protocol:compileKotlin", argv)
        self.assertIn("-PnativeSupervisorCc=" + str(self.cc), argv)
        self.assertNotIn("nativeThrowsFixture", " ".join(argv))

    def test_task_receipts_refuse_cache_no_source_duplicates_and_missing_compiles(self):
        required = producer.PREREQUISITE_TASKS
        valid = "".join("> Task " + task + "\n" for task in required)
        producer.require_tasks(valid.encode("utf-8"), required)
        for bad in (valid.replace(required[0] + "\n", required[0] + " UP-TO-DATE\n"),
                    valid.replace(required[1] + "\n", required[1] + " FROM-CACHE\n"),
                    valid.replace(required[1] + "\n", required[1] + " NO-SOURCE\n"),
                    valid + "> Task " + required[0] + "\n", "> Task " + required[0] + "\n"):
            with self.subTest(stdout=bad), self.assertRaises(producer.SelftestError):
                producer.require_tasks(bad.encode("utf-8"), required)


class ProvisionAdapterTests(OwnedFixtureTests):
    def acquisition(self):
        p, native = self.paths, producer.native
        argv = ["/usr/bin/curl", "--disable", "--fail", "--silent", "--show-error", "--proto", "=https",
            "--proxy", "", "--max-time", "590", "--connect-timeout", "20", "--max-filesize", str(native.MAX_ARCHIVE_BYTES),
            "--max-redirs", "0", "--retry", "0", "--output", str(p.provision / "archive.tar.gz"),
            "--write-out", "%{http_code}", native.ARCHIVE_URL]
        kwargs = {"cwd": p.provision, "log_base": p.provision / "transport", "timeout": 600,
            "max_output_bytes": 65536, "env": {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}}
        return argv, kwargs

    def version(self):
        p = self.paths
        argv = [str(p.data / producer.native.HOME_NAME / "bin/konanc"), "-version", "-Xoverride-konan-properties=airplaneMode=true"]
        kwargs = {"cwd": p.provision, "log_base": p.provision / "compiler-version", "timeout": 60,
            "max_output_bytes": 65536, "separate_stderr": True,
            "env": {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C", "JAVA_HOME": str(p.java25),
                "JAVA_OPTS": "-Xmx512m", "KONAN_DATA_DIR": str(p.data), "KONAN_USE_INTERNAL_SERVER": "0",
                "DEVELOPER_DIR": str(self.developer), "HOME": str(p.provision / "home")}}
        return argv, kwargs

    def test_only_existing_fixed_acquisition_and_version_calls_use_the_held_helper(self):
        previous = (producer.native.run_logged, producer.native.source_identity)
        with patch.object(self.recipe, "command", return_value=(command_result(), b"200", b"")) as execute, \
                patch.object(self.recipe, "guard", return_value=IDENTITY), producer.provision_adapter(self.recipe):
            for argv, kwargs in (self.acquisition(), self.version()):
                value, out, err = producer.native.run_logged(argv, **kwargs)
                self.assertTrue(collector.command_ok(value))
                self.assertEqual(("200", ""), (out, err))
            self.assertEqual(IDENTITY, producer.native.source_identity(self.root))
            self.assertEqual(2, execute.call_count)
            self.assertEqual(["native-archive-acquisition", "native-provisioned-version"],
                [call.args[0] for call in execute.call_args_list])
        self.assertEqual(previous, (producer.native.run_logged, producer.native.source_identity))

    def test_url_flags_paths_budget_environment_and_extra_outputs_are_not_adapter_choices(self):
        original_argv, original_kwargs = self.acquisition()
        changed_argv = original_argv.copy()
        changed_argv[-1] = "https://example.invalid/native.tar.gz"
        flag_argv = original_argv.copy()
        flag_argv[1] = "--location"
        mutations = [(changed_argv, original_kwargs), (flag_argv, original_kwargs),
            (original_argv, {**original_kwargs, "cwd": self.root}),
            (original_argv, {**original_kwargs, "log_base": self.root / "transport"}),
            (original_argv, {**original_kwargs, "timeout": 1200}),
            (original_argv, {**original_kwargs, "max_output_bytes": 100000}),
            (original_argv, {**original_kwargs, "env": {**original_kwargs["env"], "HTTPS_PROXY": PRIVATE}}),
            (original_argv, {**original_kwargs, "additional_output_paths": (self.root / "unexpected",)})]
        with patch.object(self.recipe, "command") as execute, producer.provision_adapter(self.recipe):
            for argv, kwargs in mutations:
                with self.subTest(argv=argv, kwargs=kwargs), self.assertRaises(producer.SelftestError):
                    producer.native.run_logged(argv, **kwargs)
            execute.assert_not_called()

    def test_scoped_adapters_restore_even_when_a_fixed_command_raises(self):
        previous = (producer.native.run_logged, producer.native.source_identity)
        with self.assertRaises(OSError), patch.object(self.recipe, "command", side_effect=OSError(PRIVATE)):
            with producer.provision_adapter(self.recipe):
                argv, kwargs = self.version()
                producer.native.run_logged(argv, **kwargs)
        self.assertEqual(previous, (producer.native.run_logged, producer.native.source_identity))


class EnrollmentTests(JarFixtureMixin, OwnedFixtureTests):
    def enrollment_fixture(self):
        """Complete inert byte authority, not provisioning/build evidence.

        Only the original-provisioning observation/selection boundary is
        mocked. JAR origins, JDKs, wrapper/cache, selected Native inventory,
        dependency directories, Xcode pins and authored fixture files are
        genuinely present owned bytes for the unchanged collector validators.
        """
        self.arm()
        receipt = self.paths.private / "native-original-receipt.json"
        selection = self.paths.provision / "selection.json"
        support.write(receipt, producer.encoded({"synthetic": "not real provisioning"}))
        support.write(selection, producer.encoded({"synthetic": "not real selection"}))
        self.jar_fixture()
        with self.classpath_projection():
            jars, jar_pin = producer.validate_jars(self.paths, self.budget)
        java21 = producer.java_enrollment(self.paths.java21, 21, self.budget)
        java25 = producer.java_enrollment(self.paths.java25, 25, self.budget)
        wrapper_text = "\n".join(key + "=" + value for key, value in {
            "distributionUrl": collector.GRADLE_URL.replace(":", "\\:"),
            "distributionSha256Sum": collector.GRADLE_SHA256, "distributionBase": "GRADLE_USER_HOME",
            "distributionPath": "wrapper/dists", "zipStoreBase": "GRADLE_USER_HOME", "zipStorePath": "wrapper/dists"}.items()) + "\n"
        for name in collector.WRAPPER_FILES:
            support.write(self.root / name, wrapper_text.encode("ascii") if name.endswith(".properties")
                else b"inert selected wrapper; never execute", name == "gradlew")
        cache = collector.wrapper_cache(self.paths.gradle_home)
        support.write(cache / "gradle-9.6.1/bin/gradle", b"inert selected Gradle; never execute", True)
        support.write(cache / "gradle-9.6.1/lib/gradle-launcher-9.6.1.jar", b"inert distribution bytes")
        support.write(cache / "gradle-9.6.1-bin.zip.ok", b"")
        self.config(airplaneMode="true")
        for name in ("bin/konanc", "tools/env_blacklist", "konan/lib/compiler.jar", "klib/common/stdlib/manifest",
                     "klib/platform/ios_arm64/platform/manifest"):
            support.write(self.paths.native_home / name, b"inert selected Native input; never execute", name == "bin/konanc")
        _, _, dependencies = producer.native_config(self.paths.native_home, self.budget)
        for package in dependencies.values():
            support.write(self.paths.data / "dependencies" / package / "inert", b"inert package bytes")
        xcode = {"developer_dir": str(self.developer), "version": "26.4.1", "build": "17E202",
            "iphoneos_sdk": "26.4", "iphonesimulator_sdk": "26.4", "files": {
                "xcodebuild": support.pin(self.xcodebuild, True), "xcrun": support.pin(self.xcrun, True),
                "info_plist": support.pin(self.developer.parent / "Info.plist", True)}}
        shutil.copytree(producer.REPO_ROOT / collector.FIXTURE, self.root / collector.FIXTURE)
        reference = {"synthetic": "not real provenance"}
        provisioned = {"reference": reference, "receipt_pin": support.pin(receipt), "selection_pin": support.pin(selection)}
        prepared = {"dependencies": dependencies}
        return jars, jar_pin, java21, java25, xcode, provisioned, prepared

    @contextmanager
    def inert_selection_boundary(self, provisioned, xcode):
        with self.classpath_projection(), \
                patch.object(producer.native, "observed_prerequisites", return_value=({}, self.paths.java25, xcode)) as observed, \
                patch.object(producer.native, "consume_selection", return_value=(self.paths.data / producer.native.HOME_NAME,
                    self.paths.data, provisioned["reference"])) as consume:
            yield observed, consume

    def test_measured_handoff_uses_exact_existing_collector_schema_not_a_new_acceptance_route(self):
        jars, jar_pin, java21, java25, xcode, provisioned, prepared = self.enrollment_fixture()
        # These spies call the REAL, unchanged validators; no acceptance
        # helper, inventory, JAR projection or Gradle enrollment is bypassed.
        with self.inert_selection_boundary(provisioned, xcode) as (observed, consume), \
                patch.object(collector, "enrollment_input", wraps=collector.enrollment_input) as input_check, \
                patch.object(collector, "verify_enrollment", wraps=collector.verify_enrollment) as tuple_check, \
                patch.object(self.recipe, "command", side_effect=AssertionError("No launch from enrollment")) as command:
            authority, pin, argv = producer.enroll(self.recipe, jars, jar_pin, java21, java25, xcode, provisioned, prepared)
        self.assertEqual({"schema_version", "evidence_kind", "context", "source", "fixture_manifest_digest", "git", "classpath", "components",
            "supervisor_identity", "java21", "java25", "gradle", "native", "xcode"}, set(authority))
        self.assertEqual("owner_enrolled_native_throws_build", authority["evidence_kind"])
        self.assertEqual([producer.TARGET], authority["native"]["targets"])
        self.assertEqual(4, len(authority["classpath"]))
        self.assertEqual(jars["components"], authority["components"])
        self.assertEqual((java21, java25, xcode), (authority["java21"], authority["java25"], authority["xcode"]))
        args = collector.parser().parse_args(argv)
        input_check.assert_called_once_with(args, self.root, self.budget)
        tuple_check.assert_called_once_with(authority, args, self.root, self.budget)
        observed.assert_called_once()
        consume.assert_called_once_with(self.paths.provision / "selection.json", self.paths.private / "native-original-receipt.json",
            producer.TARGET, self.root, self.recipe.source, self.paths.java25, xcode)
        command.assert_not_called()
        self.assertEqual(pin["digest"], args.authority_digest)
        receipt = collector.strict_json((self.paths.private / "recipe-handoff.json").read_bytes())
        self.assertEqual(self.recipe.permit_pin, receipt["prior_permit_pin"])
        self.assertEqual(pin, receipt["observed_enrollment_pin"])
        self.assertFalse(receipt["consumer_project_authority"])
        self.assertFalse(receipt["binary_publication_authorized"])
        self.assertFalse(receipt["independent_source_to_binary_or_complete_external_closure_verified"])

    def assert_real_validator_rejection(self, inputs, stage, code):
        errors = []
        def spy(actual):
            def check(*args, **kwargs):
                try:
                    return actual(*args, **kwargs)
                except collector.Problem as error:
                    errors.append(error)
                    raise
            return check
        with self.inert_selection_boundary(inputs[5], inputs[4]), \
                patch.object(collector, "enrollment_input", side_effect=spy(collector.enrollment_input)) as input_check, \
                patch.object(collector, "verify_enrollment", side_effect=spy(collector.verify_enrollment)) as tuple_check, \
                patch.object(self.recipe, "command", side_effect=AssertionError("No launch after validator rejection")) as command:
            with self.assertRaises(collector.Problem) as caught:
                producer.enroll(self.recipe, *inputs)
        self.assertEqual(1, len(errors))
        self.assertIs(errors[0], caught.exception, "Propagate the exact real-validator exception")
        self.assertEqual(code, str(caught.exception))
        input_check.assert_called_once()
        if stage == "enrollment_input":
            tuple_check.assert_not_called()
        else:
            self.assertEqual("verify_enrollment", stage)
            tuple_check.assert_called_once()
        command.assert_not_called()
        self.assertFalse((self.paths.private / "recipe-handoff.json").exists())
        for key in ("observed_enrollment_digest", "recipe_handoff_digest", "fresh_required_jvm_tasks_observed",
                    "fresh_offline_fixed_klib_tasks_observed"):
            self.assertNotIn(key, self.summary)

    def test_real_enrollment_input_rejection_propagates_before_tuple_or_handoff(self):
        inputs = self.enrollment_fixture()
        # Inject malformed synthetic measured metadata only. This is not a
        # claim that malformed source could freeze a real prior permit.
        self.recipe.source = {**self.recipe.source, "file_count": True}
        self.recipe.measure_source.return_value = copy.deepcopy(self.recipe.source)
        self.assert_real_validator_rejection(inputs, "enrollment_input", "native_throws_source_authority_invalid")

    def test_real_launch_tuple_rejection_propagates_without_handoff_or_launch(self):
        inputs = list(self.enrollment_fixture())
        inputs[4] = {**inputs[4], "version": "26.4.0"}
        self.assert_real_validator_rejection(inputs, "verify_enrollment", "native_throws_xcode_pin_mismatch")

    def test_changed_original_receipt_stops_measurement_without_consuming_or_collecting(self):
        inputs = self.enrollment_fixture()
        (self.paths.private / "native-original-receipt.json").write_bytes(b"changed inert receipt")
        with self.classpath_projection(), \
                patch.object(producer.native, "consume_selection") as consume, \
                patch.object(collector, "enrollment_input") as input_check, \
                self.assertRaises(producer.SelftestError):
            producer.enroll(self.recipe, *inputs)
        consume.assert_not_called()
        input_check.assert_not_called()


class NativePreparationTests(OwnedFixtureTests):
    def test_config_resolves_only_existing_named_package_properties(self):
        self.config()
        _, values, dependencies = producer.native_config(self.paths.native_home, self.budget)
        self.assertEqual("false", values["airplaneMode"])
        self.assertEqual({"llvm": "synthetic-llvm", "libffi": "synthetic-libffi", "sysroot-iosArm64": "synthetic-ios"}, dependencies)
        self.config(**{"llvmHome.macos_arm64": "$not.present"})
        with self.assertRaises(producer.SelftestError):
            producer.native_config(self.paths.native_home, self.budget)

    def test_duplicate_cycle_path_and_alternate_dependency_url_are_incompatible(self):
        for changes in ({"llvm.selected": "$llvmHome.macos_arm64"}, {"llvm.selected": "../../outside"},
                        {"libffiDir.macos_arm64": ""}, {"targetSysRoot.ios_arm64": "/Applications/SDK"},
                        {"dependenciesUrl": "https://example.invalid"}, {"airplaneMode": "yes"}):
            with self.subTest(changes=changes):
                self.config(**changes)
                with self.assertRaises(producer.SelftestError):
                    producer.native_config(self.paths.native_home, self.budget)
        raw = self.config()
        support.write(self.paths.native_home / "konan/konan.properties", raw + b"airplaneMode=false\n")
        with self.assertRaises(producer.SelftestError):
            producer.native_config(self.paths.native_home, self.budget)

    def test_airplane_freeze_changes_only_one_existing_value_in_the_execution_copy(self):
        original_home = self.paths.data / producer.native.HOME_NAME
        raw = self.config(original_home).replace(b"airplaneMode=false\n", b"\n  airplaneMode \t= false \t\r\n\n")
        support.write(original_home / "konan/konan.properties", raw)
        support.write(self.paths.native_home / "konan/konan.properties", raw)
        value = producer.freeze_airplane(self.paths.native_home, self.budget)
        self.assertEqual(raw.replace(b"= false", b"= true"), (self.paths.native_home / "konan/konan.properties").read_bytes())
        self.assertEqual(raw, (original_home / "konan/konan.properties").read_bytes())
        self.assertEqual(collector.digest_bytes(raw), value["before_digest"])
        self.assertFalse(value["original_selection_mutated"])

    def test_missing_or_comment_only_airplane_assignment_is_not_invented(self):
        raw = self.config().replace(b"airplaneMode=false\n", b"# airplaneMode=false\n")
        support.write(self.paths.native_home / "konan/konan.properties", raw)
        with self.assertRaises(producer.SelftestError):
            producer.freeze_airplane(self.paths.native_home, self.budget)
        self.assertEqual(raw, (self.paths.native_home / "konan/konan.properties").read_bytes())

    def test_dependency_readiness_requires_present_nonempty_regular_package_files(self):
        self.config()
        _, _, dependencies = producer.native_config(self.paths.native_home, self.budget)
        with self.assertRaises(producer.SelftestError):
            producer.dependency_readiness(self.paths, dependencies, self.budget)
        for package in dependencies.values():
            directory = self.paths.data / "dependencies" / package
            directory.mkdir(parents=True)
            support.write(directory / "placeholder", b"")
        with self.assertRaises(producer.SelftestError):
            producer.dependency_readiness(self.paths, dependencies, self.budget)
        for package in dependencies.values():
            support.write(self.paths.data / "dependencies" / package / "inert-package-file", b"not a real toolchain")
        producer.dependency_readiness(self.paths, dependencies, self.budget)

    def test_normalized_clone_matches_inert_original_bytes_without_mutating_original_selection(self):
        home = self.paths.data / producer.native.HOME_NAME
        raw = self.config(home)
        support.write(home / "bin/konanc", b"inert compiler launcher", True)
        original = support.tree_pins(home)
        value = producer.clone_native(self.recipe)
        self.assertEqual(original, support.tree_pins(self.paths.native_home))
        self.assertEqual(original, support.tree_pins(home))
        self.assertEqual(raw, (home / "konan/konan.properties").read_bytes())
        self.assertFalse(value["original_selection_mutated"])
        self.assertTrue(collector.valid_digest(value["source_and_copy_byte_manifest_digest"]))

    def test_clone_materializes_only_internal_regular_file_links_in_the_owned_fixture(self):
        home = self.paths.data / producer.native.HOME_NAME
        self.config(home)
        support.write(home / "bin/konanc", b"inert launcher bytes", True)
        try:
            (home / "bin/internal-link").symlink_to("konanc")
        except OSError:
            self.skipTest("Host does not allow creation of the inert fixture symlink")
        value = producer.clone_native(self.recipe)
        copied = self.paths.native_home / "bin/internal-link"
        self.assertFalse(copied.is_symlink())
        self.assertEqual(b"inert launcher bytes", copied.read_bytes())
        self.assertEqual(1, value["archive_enrolled_regular_file_links_materialized"])

    def test_clone_refuses_directory_symlinks_rather_than_inventing_a_native_layout(self):
        home = self.paths.data / producer.native.HOME_NAME
        self.config(home)
        (home / "bin").mkdir()
        try:
            (home / "linked-directory").symlink_to("bin", target_is_directory=True)
        except OSError:
            self.skipTest("Host does not allow creation of the inert fixture symlink")
        with self.assertRaises(producer.SelftestError):
            producer.clone_native(self.recipe)

    def test_klib_readiness_requires_actual_manifest_bytes_for_the_one_selected_native_target(self):
        build = self.paths.private / "synthetic-klib-build"
        build.mkdir()
        with self.assertRaises(producer.SelftestError):
            producer.klib_outputs(build, self.budget)
        path = build / "fixture.klib"
        for platform, target in (("JVM", "ios_arm64"), ("NATIVE", "ios_simulator_arm64")):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("default/manifest", "builtins_platform=" + platform + "\nnative_targets=" + target + "\n")
            with self.assertRaises(producer.SelftestError):
                producer.klib_outputs(build, self.budget)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("default/manifest", "builtins_platform=NATIVE\nnative_targets=ios_arm64\n")
        self.assertTrue(collector.valid_digest(producer.klib_outputs(build, self.budget)))

    def warmup_fixture(self):
        fixture = self.root / collector.FIXTURE
        support.write(fixture / "source-manifest.json", b"synthetic authored manifest; not a Native source claim")
        original_home = self.paths.data / producer.native.HOME_NAME
        self.config(original_home)
        for name in ("bin/konanc", "tools/env_blacklist", "konan/lib/compiler.jar", "klib/common/stdlib/manifest",
                     "klib/platform/ios_arm64/platform/manifest"):
            support.write(original_home / name, b"inert selected Native input; never execute", name == "bin/konanc")
        _, _, dependencies = producer.native_config(original_home, self.budget)
        for package in dependencies.values():
            support.write(self.paths.data / "dependencies" / package / "inert", b"inert installed package")
        return "".join("> Task " + task + "\n" for task in producer.PREREQUISITE_TASKS).encode("ascii")

    def inert_warmup_command(self, stdout):
        def command(label, argv, **kwargs):
            self.assertIn(label, {"online-fixed-klib-prerequisite", "offline-fixed-klib-prerequisite"})
            mode = label.split("-fixed-klib-prerequisite", 1)[0]
            # Synthetic output bytes go to independently fixed expected paths,
            # not a path selected by whatever argv the candidate supplies.
            # This is NEVER actual Native/compiler or dependency evidence.
            support.write(self.paths.private / (mode + "-klib-build") / "default/manifest",
                b"builtins_platform=NATIVE\nnative_targets=ios_arm64\n")
            return command_result(stdout), stdout, b""
        return command

    def test_warmup_is_online_then_fresh_offline_with_the_exact_compatible_compile_tasks(self):
        stdout = self.warmup_fixture()
        original_home = self.paths.data / producer.native.HOME_NAME
        original = support.tree_pins(original_home)
        # Only execution and the synthetic prerequisite source authority are
        # mocked. Clone/config freeze/selected inventories/output reads and
        # readiness use the real byte validators on owned inert fixtures.
        with patch.object(collector, "fixture_input", return_value=({}, {})), \
                patch.object(producer, "klib_outputs", wraps=producer.klib_outputs) as outputs, \
                patch.object(self.recipe, "command", side_effect=self.inert_warmup_command(stdout)) as execute:
            prepared = producer.prepare_native(self.recipe)
        calls = execute.call_args_list
        self.assertEqual(["online-fixed-klib-prerequisite", "offline-fixed-klib-prerequisite"], [call.args[0] for call in calls])
        self.assertNotIn("--offline", calls[0].args[1])
        self.assertIn("--offline", calls[1].args[1])
        self.assertEqual(list(producer.PREREQUISITE_TASKS), calls[0].args[1][-2:])
        self.assertEqual(list(producer.PREREQUISITE_TASKS), calls[1].args[1][-2:])
        properties = {"-PnativeThrowsCiBuild=": [], "-PfixtureBuildDir=": []}
        expected_builds = [self.paths.private / (mode + "-klib-build") for mode in ("online", "offline")]
        for call, mode, build in zip(calls, ("online", "offline"), expected_builds):
            self.assertIn("-PfixtureNegativeCase=empty-list-nonempty", call.args[1])
            self.assertIn("-Pkotlin.native.home=" + str(self.paths.native_home), call.args[1])
            self.assertNotRegex(" ".join(call.args[1]), r"link|framework|nativeThrowsFixture|installDist")
            self.assertEqual(str(self.paths.private / (mode + "-project-cache")),
                call.args[1][call.args[1].index("--project-cache-dir") + 1])
            for prefix, selected in properties.items():
                rows = [arg for arg in call.args[1] if arg.startswith(prefix)]
                self.assertEqual([prefix + str(build)], rows)
                selected.append(rows[0][len(prefix):])
        for selected in properties.values():
            self.assertNotEqual(selected[0], selected[1], "Offline output paths must be fresh for BOTH properties")
        self.assertEqual(expected_builds, [call.args[0] for call in outputs.call_args_list])
        self.assertEqual(original, support.tree_pins(original_home))
        self.assertFalse(prepared["execution_copy"]["original_selection_mutated"])

    def test_nonconfiguration_inventory_drift_after_online_denies_any_offline_launch(self):
        stdout = self.warmup_fixture()
        inert_command = self.inert_warmup_command(stdout)
        def drift(label, argv, **kwargs):
            result = inert_command(label, argv, **kwargs)
            support.write(self.paths.native_home / "bin/konanc", b"changed inert selected launcher", True)
            return result
        with patch.object(collector, "fixture_input", return_value=({}, {})), \
                patch.object(producer, "freeze_airplane", wraps=producer.freeze_airplane) as freeze, \
                patch.object(producer, "klib_outputs", wraps=producer.klib_outputs) as outputs, \
                patch.object(self.recipe, "command", side_effect=drift) as execute:
            with self.assertRaises(producer.SelftestError) as caught:
                producer.prepare_native(self.recipe)
        self.assertEqual("native_selftest_copy_invalid", str(caught.exception))
        execute.assert_called_once()
        self.assertEqual("online-fixed-klib-prerequisite", execute.call_args.args[0])
        freeze.assert_not_called()
        outputs.assert_not_called()
        self.assertFalse((self.paths.private / "offline-klib-build").exists())
        self.assertFalse((self.paths.private / "offline-project-cache").exists())
        self.assertEqual("false", producer.native_config(self.paths.native_home, self.budget)[1]["airplaneMode"])

    def test_nonconfiguration_inventory_drift_before_offline_freeze_denies_relaunch(self):
        stdout = self.warmup_fixture()
        actual_outputs = producer.klib_outputs
        def drift_after_output(path, budget):
            digest = actual_outputs(path, budget)
            support.write(self.paths.native_home / "konan/lib/compiler.jar", b"changed inert selected compiler bytes")
            return digest
        with patch.object(collector, "fixture_input", return_value=({}, {})), \
                patch.object(producer, "freeze_airplane", wraps=producer.freeze_airplane) as freeze, \
                patch.object(producer, "klib_outputs", side_effect=drift_after_output) as outputs, \
                patch.object(self.recipe, "command", side_effect=self.inert_warmup_command(stdout)) as execute:
            with self.assertRaises(producer.SelftestError) as caught:
                producer.prepare_native(self.recipe)
        self.assertEqual("native_selftest_copy_invalid", str(caught.exception))
        execute.assert_called_once()
        self.assertEqual("online-fixed-klib-prerequisite", execute.call_args.args[0])
        outputs.assert_called_once_with(self.paths.private / "online-klib-build", self.budget)
        freeze.assert_not_called()
        self.assertFalse((self.paths.private / "offline-klib-build").exists())
        self.assertFalse((self.paths.private / "offline-project-cache").exists())
        self.assertEqual("false", producer.native_config(self.paths.native_home, self.budget)[1]["airplaneMode"])

    def test_private_cache_bytes_and_entry_bounds_fail_finitely(self):
        support.write(self.paths.private / "synthetic-cache-file", b"1234567890")
        with patch.object(producer, "MAX_PRIVATE_BYTES", 9), self.assertRaises(producer.SelftestError):
            producer.cache_bound(self.paths, self.budget)
        with patch.object(producer, "MAX_PRIVATE_ENTRIES", 1), self.assertRaises(producer.SelftestError):
            producer.cache_bound(self.paths, self.budget)


class CollectorProjectionTests(unittest.TestCase):
    def confirm(self, value=None, code=0):
        return producer.confirm_controls(collector_result() if value is None else value, code, IDENTITY, ENROLLMENT_DIGEST, RAW_DIGEST)

    def test_only_zero_exit_eight_passes_and_final_source_tuple_confirm_controls_not_qualification(self):
        value = self.confirm()
        self.assertEqual("passed_controls_only", value["state"])
        self.assertEqual((8, 8), (value["selected_control_count"], value["passing_control_count"]))
        self.assertTrue(value["collector_invoked"])
        self.assertTrue(value["collector_evidence_confirmed"])
        for key in ("comprehensive_cohort_completed", "target_qualified", "release_qualified", "source_to_binary_provenance_verified",
                    "fresh_source_compilation_verified", "external_native_dependency_closure_qualified", "raw_logs_uploaded"):
            self.assertIs(False, value[key])

    def test_exit_state_source_count_selection_and_final_tuple_mismatches_never_pass(self):
        mutations = {"state": "incomplete_controls_with_observations", "source": {**IDENTITY, "commit": "a" * 40},
            "source_and_launch_tuple_verified_final": False, "required_status": "incomplete", "provider_controls_attempted": False,
            "selected_authored_control_count": 8.0, "targets": ["iosSimulatorArm64"], "selection": "full",
            "owner_enrollment_digest": RAW_DIGEST, "cohort_id": "other", "schema_version": True}
        for key, change in mutations.items():
            value = collector_result()
            value[key] = change
            with self.subTest(key=key), self.assertRaises(producer.SelftestError):
                self.confirm(value)
        for code in (1, True, None, 2):
            with self.subTest(exit_code=code), self.assertRaises(producer.SelftestError):
                self.confirm(code=code)
        for change in (8.0, True, 7):
            value = collector_result()
            value["counts"]["passed"] = change
            with self.assertRaises(producer.SelftestError):
                self.confirm(value)

    def test_observation_cases_qualification_flags_and_unconfirmed_case_cleanup_are_denied(self):
        for key in ("comprehensive_cohort_completed", "full_selected_target_cohort_completed", "target_qualified", "release_qualified",
                    "source_to_binary_provenance_verified", "fresh_source_compilation_verified", "external_native_dependency_closure_qualified",
                    "raw_logs_uploaded"):
            value = collector_result()
            value[key] = True
            with self.subTest(flag=key), self.assertRaises(producer.SelftestError):
                self.confirm(value)
        for mutation in (lambda value: value["cases"][0].update(name="override-filter-suppression"),
                         lambda value: value["cases"][0].update(state="observed_incomplete"),
                         lambda value: value["cases"][0].update(passing_control=1),
                         lambda value: value["cases"][0].update(source_and_launch_tuple_verified_after=False),
                         lambda value: value["cases"][0]["command"].update(cleanup_protocol_completed=False),
                         lambda value: value["cases"][0].update(command=None), lambda value: value["cases"].pop()):
            value = collector_result()
            mutation(value)
            with self.subTest(mutation=mutation), self.assertRaises(producer.SelftestError):
                self.confirm(value)

    def test_failure_is_nonpassing_and_a_mismatched_successful_disk_summary_is_unconfirmed(self):
        value = self.confirm(collector_result(False), code=1)
        self.assertEqual("failed", value["state"])
        self.assertEqual(0, value["passing_control_count"])
        self.assertFalse(any(row["passing_control"] for row in value["cases"]))
        for result, code in ((collector_result(False), 0), (collector_result(), 1)):
            with self.assertRaises(producer.SelftestError):
                self.confirm(result, code=code)
        for errors in (PRIVATE, [1], None):
            result = collector_result(False)
            result["errors"] = errors
            with self.assertRaises(producer.SelftestError):
                self.confirm(result, code=1)

    def test_metadata_projection_drops_hostile_arbitrary_paths_argv_transcripts_and_driver_fields(self):
        original = collector_result()
        original.update(arbitrary=PRIVATE, private_path=PRIVATE, private_originals_digest=RAW_DIGEST)
        for row in original["cases"]:
            row.update(driver={"source": PRIVATE, "argv": [PRIVATE]}, private_driver_digest=PRIVATE, arbitrary=PRIVATE)
            row["command"].update(argv=[PRIVATE], cwd=PRIVATE, stdout=PRIVATE, stderr=PRIVATE)
        value = self.confirm(original)
        self.assertNotIn(PRIVATE, json.dumps(value))
        self.assertEqual({"name", "state", "passing_control"}, set(value["cases"][0]))
        self.assertEqual(RAW_DIGEST, value["collector_summary_digest"])
        failed = collector_result(False)
        failed["errors"] = [PRIVATE]
        self.assertNotIn(PRIVATE, json.dumps(self.confirm(failed, code=1)))

    def test_invalid_handoff_digest_cannot_be_projected_as_private_text(self):
        with self.assertRaises(producer.SelftestError):
            producer.confirm_controls(collector_result(), 0, IDENTITY, PRIVATE, RAW_DIGEST)
        with self.assertRaises(producer.SelftestError):
            producer.confirm_controls(collector_result(), 0, IDENTITY, ENROLLMENT_DIGEST, PRIVATE)


class MetadataTests(OwnedFixtureTests):
    def test_exclusive_held_output_is_bounded_and_rewrites_only_its_owned_descriptor(self):
        path = self.paths.stage / "synthetic-summary.json"
        output = producer.MetadataFile(path)
        try:
            output.write({"state": "unavailable", "synthetic": True})
            output.write({"state": "failed"})
            self.assertEqual({"state": "failed"}, json.loads(path.read_bytes()))
            with self.assertRaises(OSError):
                producer.MetadataFile(path)
            with patch.object(producer, "MAX_SUMMARY", 1), self.assertRaises(producer.SelftestError):
                output.write({"state": "failed"})
            with patch.object(producer.os, "write", return_value=0), self.assertRaises(producer.SelftestError):
                output.write({"state": "failed"})
        finally:
            output.close()

    def test_symlink_output_is_not_followed_or_clobbered(self):
        original = self.paths.stage / "original.json"
        link = self.paths.stage / "symlink-summary.json"
        support.write(original, b"inert existing bytes")
        try:
            link.symlink_to(original)
        except OSError:
            self.skipTest("Host does not allow creation of the inert fixture symlink")
        with self.assertRaises((OSError, AssertionError)):
            producer.MetadataFile(link)
        self.assertEqual(b"inert existing bytes", original.read_bytes())


class MockOrchestrationTests(OwnedFixtureTests):
    def orchestration(self, *, phase_error=None, exit_code=0, disk_result=None, missing_summary=False,
                      final_guard_error=False, publication_error=False):
        order = []
        authority = {"synthetic": "not real build authority"}
        pin = {"size": 1, "digest": ENROLLMENT_DIGEST}
        argv = producer.collector_argv(self.paths, ENROLLMENT_DIGEST)

        def phase(name, result=None):
            def perform(*args, **kwargs):
                order.append(name)
                if phase_error == name:
                    raise producer.SelftestError("native_selftest_command_failed")
                if name == "permit":
                    recipe = args[0]
                    recipe.source = copy.deepcopy(IDENTITY)
                    recipe.permit_pin = {"size": 1, "digest": RAW_DIGEST}
                    recipe.summary.update(source=recipe.source, prior_policy_confirmed=True, prior_permit_digest=RAW_DIGEST)
                return result
            return perform

        def guard(recipe, **kwargs):
            order.append("guard-final" if kwargs.get("reserve") is False else "guard")
            if kwargs.get("reserve") is False and final_guard_error:
                raise producer.SelftestError("native_selftest_source_changed")
            return IDENTITY

        def collect(received):
            order.append("collector")
            self.assertEqual(argv, received)
            self.assertEqual(str(self.paths.java21), os.environ["JAVA_HOME"])
            if not missing_summary:
                support.write(self.paths.private / "collector-result.json",
                    producer.encoded(collector_result(exit_code == 0) if disk_result is None else disk_result))
            return exit_code

        class Output:
            def __init__(self, path):
                self.path = path
                self.writes = 0
            def write(inner, value):
                inner.writes += 1
                if publication_error and inner.writes > 1 and inner.path.name == "native-throws-controls.json":
                    raise OSError(PRIVATE)
                support.write(inner.path, producer.encoded(copy.deepcopy(value)))
            def close(inner):
                pass

        # Production requires a fresh stage. Remove only this inert fixture's
        # previously created empty private directories, never repository files.
        shutil.rmtree(self.paths.stage)
        stdout = io.StringIO()
        with ExitStack() as stack:
            for module, name, replacement in (
                (producer, "select_paths", Mock(return_value=self.paths)),
                (producer.Recipe, "freeze_permit", phase("permit")),
                (producer, "observe_tools", phase("observe", ({}, {}, {}))),
                (producer, "build_jars", phase("build", ({}, {}))),
                (producer, "cache_bound", phase("cache")),
                (producer, "provision_distribution", phase("provision", {})),
                (producer, "prepare_native", phase("prepare", {})),
                (producer, "enroll", phase("enroll", (authority, pin, argv))),
                (producer.Recipe, "guard", guard), (collector, "main", collect),
                (collector, "verify_enrollment", phase("verify")), (producer, "MetadataFile", Output)):
                stack.enter_context(patch.object(module, name, replacement))
            with redirect_stdout(stdout):
                code = producer.run(producer.POLICY_ID, COMMIT, self.root)
        self.assertNotIn(PRIVATE, stdout.getvalue())
        policy = json.loads((self.paths.stage / "policy-build.json").read_bytes())
        controls = json.loads((self.paths.stage / "native-throws-controls.json").read_bytes())
        self.assertNotIn(PRIVATE, json.dumps(policy) + json.dumps(controls))
        return code, order, policy, controls

    def test_all_execution_mocked_order_starts_with_permit_and_success_requires_collector_confirmation(self):
        code, order, policy, controls = self.orchestration()
        self.assertEqual(0, code)
        self.assertEqual(["permit", "observe", "build", "cache", "provision", "cache", "prepare", "enroll", "guard", "collector",
            "guard-final", "verify", "cache"], order)
        self.assertEqual("passed_controls_only", policy["state"])
        self.assertTrue(policy["collector_evidence_confirmed"])
        self.assertEqual(8, controls["passing_control_count"])
        self.assertFalse(policy["consumer_project_authority"])
        self.assertFalse(policy["binary_publication_authorized"])
        self.assertFalse(policy["target_qualified"])

    def test_preparation_failure_never_invokes_the_collector(self):
        code, order, policy, controls = self.orchestration(phase_error="prepare")
        self.assertEqual(1, code)
        self.assertNotIn("collector", order)
        self.assertFalse(policy["collector_invoked"])
        self.assertEqual(0, controls["passing_control_count"])

    def test_permit_failure_never_probes_builds_provisions_or_collects(self):
        code, order, policy, controls = self.orchestration(phase_error="permit")
        self.assertEqual(1, code)
        self.assertEqual(["permit"], order)
        self.assertFalse(policy["prior_policy_confirmed"])
        self.assertEqual("unavailable", controls["state"])

    def test_collector_failure_remains_nonzero_with_confirmed_nonpassing_metadata(self):
        code, order, policy, controls = self.orchestration(exit_code=1)
        self.assertEqual(1, code)
        self.assertEqual(1, order.count("collector"))
        self.assertTrue(policy["collector_evidence_confirmed"])
        self.assertEqual(0, controls["passing_control_count"])
        self.assertEqual("failed", controls["state"])

    def test_mismatched_or_missing_disk_summary_is_unconfirmed_and_never_success(self):
        code, order, policy, controls = self.orchestration(exit_code=1, disk_result=collector_result())
        self.assertEqual(1, code)
        self.assertFalse(policy["collector_evidence_confirmed"])
        self.assertEqual(0, controls["passing_control_count"])
        self.assertEqual(1, order.count("collector"))

    def test_missing_disk_summary_is_unconfirmed_and_never_success(self):
        code, order, policy, controls = self.orchestration(missing_summary=True)
        self.assertEqual(1, code)
        self.assertFalse(policy["collector_evidence_confirmed"])
        self.assertEqual(0, controls["passing_control_count"])
        self.assertEqual(1, order.count("collector"))

    def test_final_source_drift_never_publishes_confirmed_passing_controls(self):
        code, order, policy, controls = self.orchestration(final_guard_error=True)
        self.assertEqual(1, code)
        self.assertFalse(policy["collector_evidence_confirmed"])
        self.assertFalse(controls["collector_evidence_confirmed"])
        self.assertEqual(0, controls["passing_control_count"])
        self.assertNotIn("verify", order)

    def test_publication_failure_is_nonzero_even_after_mocked_collector_success(self):
        code, order, policy, controls = self.orchestration(publication_error=True)
        self.assertEqual(1, code)
        self.assertEqual(1, order.count("collector"))
        self.assertEqual("failed", policy["state"])
        self.assertFalse(policy["collector_evidence_confirmed"])
        self.assertIn("native_selftest_publication_failed", policy["errors"])

    def test_collector_handoff_argv_has_exact_scope_bounds_and_only_owned_fixed_paths(self):
        argv = producer.collector_argv(self.paths, ENROLLMENT_DIGEST)
        args = collector.parser().parse_args(argv)
        self.assertEqual([producer.TARGET], args.target)
        self.assertEqual(("first-mac", 1200, 180, 33554432, 268435456),
            (args.cohort, args.global_timeout, args.case_timeout, args.max_output_bytes, args.max_cohort_bytes))
        self.assertEqual(self.paths.native_home, args.native_home)
        self.assertEqual(self.paths.java21, args.broker_java_home)
        self.assertEqual(self.paths.java25, args.project_java_home)
        self.assertEqual(self.paths.private / "observed-enrollment.json", args.authority)
        self.assertEqual(self.paths.private / "collector-result.json", args.output)
        self.assertEqual(1, argv.count("--target"))
        self.assertNotIn("--enroll-existing", argv)


if __name__ == "__main__":
    unittest.main()
