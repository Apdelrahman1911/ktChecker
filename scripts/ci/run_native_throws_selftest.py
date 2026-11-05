#!/usr/bin/env python3
"""Explicit, fixed repository self-test policy producer, NOT provider consent.

The reviewed workflow dispatch authorizes this recipe at its exact source SHA
BEFORE builds, tool probes, Native dependency preparation, or measurement. A
permit digest records that prior permission; artifact hashes are only measured
handoff references. Neither is a signature or authentication outside the trusted,
fresh GitHub-hosted workflow. Reruns are refused: dispatch again explicitly.

The existing installed-byte collector is unchanged and remains the only control
acceptance route. It receives the measured current-run envelope from this
separate recipe producer, never discovers or self-approves arbitrary artifacts.
There are no caller-selected paths, tasks, URLs, classpaths or enroll-existing
modes. Only iosArm64/first-mac8 is authorized, not all20/344, target/release
qualification, consumer-project review, or any binary publication.

All originals, JARs, Native copies, caches, commands and transcripts remain in a
new ignored build directory. Public JSON is an explicit metadata projection.
Capture/cleanup reuse the collector's cooperative held-leader CI helper. This
is not a network/OS sandbox, loaded-memory attestation, hostile same-UID defense,
complete transitive dependency/toolchain qualification, or reproducible-build
proof. Tests use inert bytes and mocked commands only, never Native execution.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import platform
import re
import stat
import sys
import time
import zipfile

if __package__:
    from . import provision_native as native
    from . import run_native_throws_checks as collector
else:
    import provision_native as native
    import run_native_throws_checks as collector


REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY_ID = "native-throws-first-mac-v1"
REPOSITORY = "Apdelrahman1911/ktChecker"
WORKFLOW = ".github/workflows/native-throws-selftest.yml"
WORKFLOW_NAME = "Repository Native throws self-test (first Mac eight)"
JOB = "native-throws-first-mac"
TARGET = "iosArm64"
DEVELOPER_DIR = Path("/Applications/Xcode_26.4.1.app/Contents/Developer")
CC = DEVELOPER_DIR / "Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"
GIT = Path("/usr/bin/git")
STAGE_TASK = ":provider-native:stageNativeThrowsCiJars"
PREREQUISITE_TASKS = (":compileCommonMainKotlinMetadata", ":compileKotlinIosArm64")
PROJECTS = (":provider-native", ":provider-runtime", ":snapshot", ":contracts")
ALLOWED_PROJECTS = PROJECTS + (":worker-protocol",)
COMPONENT_PROJECTS = {"driver": ":provider-native", "fixture_model": ":provider-native", "provider": ":provider-native",
                      "runner": ":provider-runtime", "supervisor_loader": ":provider-runtime",
                      "snapshot": ":snapshot", "contracts": ":contracts"}
REQUIRED_BUILD_TASKS = tuple(
    task for project in PROJECTS for task in (project + ":compileKotlin", project + ":jar")
) + (":provider-runtime:processResources", ":provider-runtime:buildOwnedProcessSupervisor",
     ":provider-runtime:buildOwnedLiveProcessSupervisor", STAGE_TASK)
COLLECTOR_SECONDS = 1200
CASE_SECONDS = 180
RECIPE_SECONDS = 4200
PREP_RESERVE = COLLECTOR_SECONDS + 60
MAX_LOG = 16 * 1024 * 1024
MAX_PRIVATE_BYTES = 12 * 1024 * 1024 * 1024
MAX_PRIVATE_ENTRIES = 500_000
MAX_SUMMARY = 512 * 1024
DEPENDENCIES_URL = "https://download.jetbrains.com/kotlin/native"
COMMIT = re.compile(r"[0-9a-f]{40}")
CI_NAMES = (
    "GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_SERVER_URL", "GITHUB_RUN_ID",
    "GITHUB_RUN_ATTEMPT", "GITHUB_JOB", "GITHUB_EVENT_NAME", "GITHUB_SHA",
    "GITHUB_WORKFLOW_SHA", "GITHUB_WORKFLOW_REF", "GITHUB_WORKFLOW", "GITHUB_REF",
    "GITHUB_ACTOR", "GITHUB_TRIGGERING_ACTOR", "GITHUB_WORKSPACE", "GITHUB_EVENT_PATH",
    "RUNNER_ENVIRONMENT", "RUNNER_OS", "RUNNER_ARCH",
)
ERRORS = frozenset("""
    native_selftest_arguments_invalid native_selftest_dispatch_required
    native_selftest_commit_mismatch native_selftest_context_invalid
    native_selftest_rerun_requires_new_dispatch native_selftest_host_invalid
    native_selftest_paths_invalid native_selftest_output_not_fresh
    native_selftest_source_not_clean native_selftest_permit_changed
    native_selftest_source_changed native_selftest_tool_version_mismatch
    native_selftest_command_failed native_selftest_tasks_not_executed
    native_selftest_provisioning_failed native_selftest_provisioning_adapter_invalid
    native_selftest_copy_invalid native_selftest_native_config_incompatible
    native_selftest_native_dependency_layout_incompatible
    native_selftest_native_dependency_missing native_selftest_klib_unconfirmed
    native_selftest_jar_manifest_invalid native_selftest_jar_origin_invalid
    native_selftest_component_provenance_invalid native_selftest_cache_limit
    native_selftest_collector_unconfirmed native_selftest_collector_did_not_pass
    native_selftest_publication_failed native_selftest_interrupted
    native_selftest_evidence_unavailable
""".split())

# Authored selections are not observed versions or upstream attestations.
POLICY = {
    "id": POLICY_ID, "scope": "fixed_repository_selftest_only", "repository": REPOSITORY,
    "workflow": WORKFLOW, "job": JOB, "reruns": "new_explicit_dispatch_required",
    "fixture": collector.FIXTURE, "fixture_manifest_digest": collector.MANIFEST_DIGEST,
    "cohort": "first-mac", "target": TARGET, "controls": list(collector.FIRST_MAC),
    "setup_actions": {
        "checkout": "fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09",
        "python": "ece7cb06caefa5fff74198d8649806c4678c61a1",
        "java": "b6effb05e454b25005698d916606bdc6ffcbf961",
        "upload": "ea165f8d65b6e75b540449e92b4886f43607fa02",
    },
    "declared_tools": {"runner": "macos-26/ARM64", "python": "3.12",
        "broker_java_major": 21, "project_java_major": 25, "java_distribution": "temurin",
        "gradle": "9.6.1", "native": "2.4.21", "xcode": "26.4.1", "xcode_build": "17E202",
        "iphoneos_sdk": "26.4", "iphonesimulator_sdk": "26.4"},
    "native_archive": {"url": native.ARCHIVE_URL, "size": native.ARCHIVE_SIZE,
        "digest": "sha256:" + native.ARCHIVE_SHA256,
        "sha256_origin": "author_enrolled_not_upstream_sha256_or_signature"},
    "gradle_archive": {"url": collector.GRADLE_URL, "digest": "sha256:" + collector.GRADLE_SHA256},
    "dependency_recipe": {
        "jvm": "exact_source_declared_provider_native_runtimeClasspath_including_transitives",
        "gradle_repositories": ["https://plugins.gradle.org", "https://repo.maven.apache.org"],
        "native_config_dependencies_url": DEPENDENCIES_URL,
        "native": "fixed_compatible_KLIB_tasks_online_then_fresh_offline_outputs",
        "case": "empty-list-nonempty", "tasks": list(PREREQUISITE_TASKS),
        "generated_target_binary_link_or_execution": False,
        "transitive_network_closure_enforced_or_qualified": False,
    },
    "build": {"task": STAGE_TASK, "required_tasks": list(REQUIRED_BUILD_TASKS),
        "required_component_projects": list(PROJECTS), "allowed_owned_projects": list(ALLOWED_PROJECTS),
        "component_projects": COMPONENT_PROJECTS,
        "optional_java_only_wire_tasks": [":worker-protocol:compileJava", ":worker-protocol:jar"],
        "workers": 1, "build_cache": False,
        "configuration_cache": False, "toolchain_download_or_discovery": False,
        "preview1_host_helper_build": True, "preview2_mac_unavailable_manifest_build": True},
    "budgets": {"recipe_seconds": RECIPE_SECONDS, "collector_seconds": COLLECTOR_SECONDS,
        "case_seconds": CASE_SECONDS, "command_log_bytes": MAX_LOG,
        "private_cache_bytes": MAX_PRIVATE_BYTES, "private_cache_entries": MAX_PRIVATE_ENTRIES},
    "publication": "only_two_sanitized_metadata_JSONs_no_binaries_or_originals",
}

# No JavaExec. Resolve only this exact checked-in project's runtime configuration.
# Private path properties are generated by this utility, never dispatch inputs.
JAR_INIT = r'''import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

gradle.beforeProject { p ->
    def base = new File(gradle.startParameter.projectProperties.get('nativeThrowsCiBuild'))
    p.layout.buildDirectory.set(new File(base, p.path == ':' ? '_root' : p.path.substring(1).replace(':', '/')))
}
gradle.projectsEvaluated {
    def p = gradle.rootProject.findProject(':provider-native')
    if (p == null) throw new GradleException('Fixed Native provider project required')
    def configuration = p.configurations.getByName('runtimeClasspath')
    p.tasks.register('stageNativeThrowsCiJars') {
        dependsOn(p.tasks.named('jar'))
        dependsOn(configuration)
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf('Fresh self-test artifact staging only') { true }
        doLast {
            def destination = new File(gradle.startParameter.projectProperties.get('nativeThrowsCiStage'))
            if (destination.exists() || !destination.mkdir()) throw new GradleException('Fresh staging required')
            def rows = [[origin: 'owner_project', project: ':provider-native',
                coordinate: "${p.group}:${p.name}:${p.version}", source: p.tasks.named('jar').get().archiveFile.get().asFile]]
            configuration.incoming.artifacts.artifacts.each { artifact ->
                def id = artifact.id.componentIdentifier
                if (id instanceof ProjectComponentIdentifier) {
                    def owner = gradle.rootProject.findProject(id.projectPath)
                    if (owner == null) throw new GradleException('Included build prohibited')
                    rows << [origin: 'owner_project', project: id.projectPath,
                        coordinate: "${owner.group}:${owner.name}:${owner.version}", source: artifact.file]
                } else if (id instanceof ModuleComponentIdentifier) {
                    rows << [origin: 'maven', project: null,
                        coordinate: "${id.group}:${id.module}:${id.version}", source: artifact.file]
                } else throw new GradleException('Unknown artifact origin')
            }
            def required = [':provider-native', ':provider-runtime', ':snapshot', ':contracts']
            def order = required + [':worker-protocol']
            def owners = rows.findAll { it.origin == 'owner_project' }.collect { it.project }
            if (!owners.containsAll(required) || owners.any { !order.contains(it) } || owners.unique(false).size() != owners.size())
                throw new GradleException('Required component origins and allowlisted closure only')
            rows.sort { a, b ->
                def ka = a.origin == 'owner_project' ? '0' + order.indexOf(a.project) : '1' + a.coordinate + ':' + a.source.name
                def kb = b.origin == 'owner_project' ? '0' + order.indexOf(b.project) : '1' + b.coordinate + ':' + b.source.name
                ka <=> kb
            }
            def names = [] as Set
            def jars = rows.withIndex().collect { row, index ->
                def source = row.source.canonicalFile
                if (!source.isFile() || !(source.name ==~ /[A-Za-z0-9_.+-]+\.jar/) || !names.add(source.name.toLowerCase(Locale.ROOT)))
                    throw new GradleException('Regular distinct JAR names required')
                def output = new File(destination, source.name)
                Files.copy(source.toPath(), output.toPath())
                def hasher = MessageDigest.getInstance('SHA-256')
                output.withInputStream { stream ->
                    byte[] buffer = new byte[1048576]
                    int count
                    while ((count = stream.read(buffer)) != -1) hasher.update(buffer, 0, count)
                }
                [index: index, origin: row.origin, project: row.project, coordinate: row.coordinate,
                 source: source.absolutePath, path: output.canonicalPath, size: output.length(),
                 digest: 'sha256:' + HexFormat.of().formatHex(hasher.digest())]
            }
            def value = [schema_version: 1, producer: 'fixed_native_throws_jar_recipe/1',
                source_root: gradle.rootProject.projectDir.canonicalPath, gradle_version: gradle.gradleVersion,
                java_version: System.getProperty('java.version'), jars: jars]
            Files.write(new File(destination, 'manifest.json').toPath(),
                (JsonOutput.prettyPrint(JsonOutput.toJson(value)) + '\n').getBytes('UTF-8'),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
    }
}
'''

FIXTURE_INIT = r'''gradle.beforeProject { p ->
    def base = new File(gradle.startParameter.projectProperties.get('nativeThrowsCiBuild'))
    p.layout.buildDirectory.set(new File(base, p.path == ':' ? '' : p.path.substring(1).replace(':', '/')))
}
'''


class SelftestError(Exception):
    def __init__(self, code: str):
        super().__init__(code if code in ERRORS else "native_selftest_evidence_unavailable")


def need(condition: bool, code: str) -> None:
    if not condition:
        raise SelftestError(code)


def encoded(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True, allow_nan=False) + "\n").encode("utf-8")


def recipe_digest() -> str:
    return collector.canonical_digest({"policy": POLICY, "jar_init": JAR_INIT, "fixture_init": FIXTURE_INIT})


def admit(policy: str, commit: str, env: dict, event: dict) -> dict:
    """Pure trusted-workflow admission; CI labels alone cannot authenticate users."""
    need(policy == POLICY_ID and isinstance(commit, str) and COMMIT.fullmatch(commit) is not None,
         "native_selftest_arguments_invalid")
    need(isinstance(env, dict) and isinstance(event, dict), "native_selftest_context_invalid")
    inputs = event.get("inputs")
    need(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and isinstance(inputs, dict)
         and set(inputs) == {"confirm_policy", "expected_commit"}
         and (inputs["confirm_policy"] is True or inputs["confirm_policy"] == "true"),
         "native_selftest_dispatch_required")
    need(inputs["expected_commit"] == commit == env.get("GITHUB_SHA") == env.get("GITHUB_WORKFLOW_SHA"),
         "native_selftest_commit_mismatch")
    need(env.get("GITHUB_RUN_ATTEMPT") == "1", "native_selftest_rerun_requires_new_dispatch")
    ref, actor, run_id = env.get("GITHUB_REF", ""), env.get("GITHUB_ACTOR", ""), env.get("GITHUB_RUN_ID", "")
    repository, sender = event.get("repository"), event.get("sender")
    need(env.get("GITHUB_ACTIONS") == "true" and env.get("GITHUB_SERVER_URL") == "https://github.com"
         and env.get("GITHUB_REPOSITORY") == REPOSITORY
         and isinstance(repository, dict) and repository.get("full_name") == REPOSITORY
         and isinstance(ref, str) and re.fullmatch(r"refs/heads/[A-Za-z0-9_./-]{1,200}", ref) is not None
         and all(part not in {"", ".", ".."} for part in ref.split("/"))
         and env.get("GITHUB_WORKFLOW_REF") == REPOSITORY + "/" + WORKFLOW + "@" + ref
         and env.get("GITHUB_WORKFLOW") == WORKFLOW_NAME and env.get("GITHUB_JOB") == JOB
         and isinstance(run_id, str) and re.fullmatch(r"[1-9][0-9]{0,19}", run_id) is not None
         and isinstance(actor, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,39}(?:\[bot\])?", actor) is not None
         and isinstance(sender, dict) and sender.get("login") == actor and env.get("GITHUB_TRIGGERING_ACTOR") == actor,
         "native_selftest_context_invalid")
    need(env.get("RUNNER_ENVIRONMENT") == "github-hosted" and env.get("RUNNER_OS") == "macOS"
         and env.get("RUNNER_ARCH") == "ARM64", "native_selftest_host_invalid")
    return {"repository": REPOSITORY, "workflow_ref": env["GITHUB_WORKFLOW_REF"], "workflow_sha": commit,
            "dispatch_actor": actor, "confirmation": True,
            "context": {"run_id": env["GITHUB_RUN_ID"], "run_attempt": "1", "job": JOB}}


@dataclass(frozen=True)
class Paths:
    root: Path
    stage: Path
    private: Path
    java21: Path
    java25: Path
    python: Path

    @property
    def gradle_home(self) -> Path:
        return self.private / "gradle-home"

    @property
    def home(self) -> Path:
        return self.private / "home"

    @property
    def provision(self) -> Path:
        return self.private / "native-provisioning"

    @property
    def data(self) -> Path:
        return self.provision / "data"

    @property
    def native_home(self) -> Path:
        return self.private / "native-execution" / native.HOME_NAME


def select_paths(root: Path, env: dict) -> Paths:
    root = collector.absolute(root, directory=True)
    need(env.get("GITHUB_WORKSPACE") == str(root), "native_selftest_paths_invalid")
    # These slots are written by the two pinned setup-java actions, not user inputs.
    homes = []
    for key in ("JAVA_HOME_21_ARM64", "JAVA_HOME_25_ARM64"):
        value = env.get(key)
        need(isinstance(value, str) and Path(value).is_absolute(), "native_selftest_paths_invalid")
        homes.append(collector.absolute(Path(value).resolve(strict=True), directory=True))
    need(homes[0] != homes[1] and env.get("JAVA_HOME") is not None
         and Path(env["JAVA_HOME"]).resolve(strict=True) == homes[1], "native_selftest_paths_invalid")
    python = collector.absolute(Path(sys.executable).resolve(strict=True))
    stage = collector.output_path(root / "build/ci-native-throws-selftest", root)
    need(not stage.exists(), "native_selftest_output_not_fresh")
    return Paths(root, stage, stage / "private", homes[0], homes[1], python)


@contextmanager
def environment(value: dict):
    previous = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(value)
        yield
    finally:
        os.environ.clear()
        os.environ.update(previous)


def safe_env(paths: Paths, original: dict, java: Path) -> dict:
    value = {key: original[key] for key in CI_NAMES if key in original}
    value.update(PATH=str(java / "bin") + ":" + str(paths.python.parent) + ":/usr/bin:/bin",
                 JAVA_HOME=str(java), GRADLE_USER_HOME=str(paths.gradle_home), HOME=str(paths.home),
                 KONAN_DATA_DIR=str(paths.data), KONAN_USE_INTERNAL_SERVER="0", DEVELOPER_DIR=str(DEVELOPER_DIR),
                 LANG="C", LC_ALL="C", GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null")
    return value


class MetadataFile:
    """Exclusive output, held descriptor; never replace a preexisting artifact."""

    def __init__(self, path: Path):
        self.fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)

    def write(self, value: dict) -> None:
        raw = encoded(value)
        need(len(raw) <= MAX_SUMMARY, "native_selftest_publication_failed")
        os.lseek(self.fd, 0, os.SEEK_SET)
        offset = 0
        while offset < len(raw):
            count = os.write(self.fd, raw[offset:])
            need(count > 0, "native_selftest_publication_failed")
            offset += count
        os.ftruncate(self.fd, len(raw))
        os.fsync(self.fd)

    def close(self) -> None:
        os.close(self.fd)


def private_json(path: Path, value: dict, budget: collector.Budget) -> dict:
    raw = encoded(value)
    need(len(raw) <= collector.MAX_ENROLLMENT, "native_selftest_evidence_unavailable")
    with path.open("xb") as output:
        os.chmod(path, 0o600)
        need(output.write(raw) == len(raw), "native_selftest_evidence_unavailable")
        output.flush()
        os.fsync(output.fileno())
    return collector.file_pin(path, budget, collector.MAX_ENROLLMENT)


def command_projection(label: str, value: dict) -> dict:
    number = value.get("return_code")
    elapsed = value.get("elapsed_seconds")
    return {"step": label, "passed": collector.command_ok(value),
            "execution_started": value.get("execution_started") is True,
            "return_code": number if type(number) is int and -255 <= number <= 255 else None,
            "cleanup_protocol_completed": value.get("cleanup_protocol_completed") is True,
            "group_quiescence_observed": value.get("group_quiescence_observed") is True,
            "escaped_descendants_proven_absent": False,
            "elapsed_seconds": elapsed if type(elapsed) in {int, float} and math.isfinite(elapsed) and 0 <= elapsed <= RECIPE_SECONDS else None,
            "stdout_digest": value.get("stdout_digest") if collector.valid_digest(value.get("stdout_digest")) else None,
            "stderr_digest": value.get("stderr_digest") if collector.valid_digest(value.get("stderr_digest")) else None,
            "error": None if collector.command_ok(value) else collector.public_code(value.get("code"))}


class Recipe:
    def __init__(self, paths: Paths, original: dict, dispatch: dict, event_pin: dict,
                 event_path: Path, budget: collector.Budget, summary: dict):
        self.paths, self.original, self.dispatch = paths, original, dispatch
        self.event_pin, self.event_path, self.budget, self.summary = event_pin, event_path, budget, summary
        self.source = None
        self.permit_pin = None
        self.permit = paths.private / "prior-permit.json"
        self.sequence = 0
        self.stopped = False

    def measure_source(self) -> dict:
        self.sequence += 1
        directory = self.paths.private / "commands" / f"source-{self.sequence:03d}"
        directory.mkdir(mode=0o700)
        return collector.source_identity(self.paths.root, GIT, safe_env(self.paths, self.original, self.paths.java21),
                                         directory, self.budget)

    def freeze_permit(self) -> None:
        try:
            need(not self.stopped and self.source is None and self.permit_pin is None,
                 "native_selftest_permit_changed")
            self.budget.remaining(PREP_RESERVE)
            need(collector.ci_context() == self.dispatch["context"]
                 and all(os.environ.get(key) == self.original.get(key) for key in CI_NAMES),
                 "native_selftest_context_invalid")
            need(collector.file_pin(self.event_path, self.budget, 1024 * 1024) == self.event_pin,
                 "native_selftest_permit_changed")
            collector.process_gate()
            collector.absolute(GIT)
            source = self.measure_source()
            collector.validate_source(source)
            need(source["commit"] == self.dispatch["workflow_sha"] and source["dirty"] is False,
                 "native_selftest_source_not_clean")
            collector.fixture_input(self.paths.root, self.paths.root / collector.FIXTURE, self.budget)
            self.source = source
            value = {"schema_version": 1, "evidence_kind": "prior_repository_native_throws_selftest_permit",
                     "authorization_basis": "explicit_reviewed_workflow_dispatch_not_hash_as_consent",
                     "dispatch": self.dispatch, "source": source, "policy": POLICY,
                     "recipe_digest": recipe_digest(), "event_file_pin": self.event_pin,
                     "selected_java21": str(self.paths.java21), "selected_java25": str(self.paths.java25),
                     "selected_python": str(self.paths.python), "selected_developer_dir": str(DEVELOPER_DIR),
                     "selected_cc": str(CC), "recorded_at": collector.utc_now()}
            self.permit_pin = private_json(self.permit, value, self.budget)
            self.summary.update(prior_permit_digest=self.permit_pin["digest"], source=source,
                                prior_policy_confirmed=True)
        except BaseException:
            self.stopped = True
            raise

    def guard(self, *, reserve: bool = True) -> dict:
        try:
            need(not self.stopped and self.permit_pin is not None, "native_selftest_permit_changed")
            self.budget.remaining(PREP_RESERVE if reserve else 0)
            need(collector.ci_context() == self.dispatch["context"]
                 and all(os.environ.get(key) == self.original.get(key) for key in CI_NAMES),
                 "native_selftest_context_invalid")
            need(collector.file_pin(self.permit, self.budget, collector.MAX_ENROLLMENT) == self.permit_pin
                 and collector.file_pin(self.event_path, self.budget, 1024 * 1024) == self.event_pin,
                 "native_selftest_permit_changed")
            source = self.measure_source()
            need(source == self.source, "native_selftest_source_changed")
            return source
        except BaseException:
            self.stopped = True
            raise

    def command(self, label: str, argv: list[str], *, cwd: Path, env: dict, timeout: int = 900,
                max_bytes: int = MAX_LOG) -> tuple[dict, bytes, bytes]:
        self.guard()
        try:
            need(isinstance(argv, list) and all(isinstance(arg, str) for arg in argv), "native_selftest_arguments_invalid")
            directory = self.paths.private / "commands" / label
            value = collector.run_bounded(argv, cwd=cwd, directory=directory, env=env, budget=self.budget,
                                          timeout=min(timeout, self.budget.remaining(PREP_RESERVE)), max_bytes=max_bytes)
            self.summary["steps"].append(command_projection(label, value))
            need(collector.command_ok(value), "native_selftest_command_failed")
            out = collector.bounded_read(directory / "stdout.log", self.budget, max_bytes)
            err = collector.bounded_read(directory / "stderr.log", self.budget, max_bytes)
            self.guard()
            return value, out, err
        except BaseException:
            self.stopped = True
            raise


def require_tasks(stdout: bytes, required: tuple[str, ...]) -> None:
    tasks = {}
    for line in stdout.decode("utf-8", errors="strict").splitlines():
        match = collector.TASK_LINE.fullmatch(line)
        if match:
            need(match[1] not in tasks, "native_selftest_tasks_not_executed")
            tasks[match[1]] = match[2] or "EXECUTED"
    need(all(tasks.get(task) == "EXECUTED" for task in required), "native_selftest_tasks_not_executed")


def java_enrollment(home: Path, major: int, budget: collector.Budget) -> dict:
    release = collector.bounded_read(collector.selected(home, "release"), budget, 65536).decode("utf-8")
    versions = re.findall(r'^JAVA_VERSION="([^"\r\n]+)"$', release, re.M)
    need(len(versions) == 1, "native_selftest_tool_version_mismatch")
    value = {"home": str(home), "version": versions[0],
             "files": {name: collector.file_pin(collector.selected(home, name), budget) for name in sorted(collector.JDK_FILES)}}
    collector.java_input(value, home, major, budget)
    return value


def gradle_enrollment(paths: Paths, budget: collector.Budget) -> dict:
    cache = collector.wrapper_cache(paths.gradle_home)
    home = collector.absolute(cache / "gradle-9.6.1", directory=True)
    marker = collector.selected(cache, "gradle-9.6.1-bin.zip.ok")
    return {"wrapper": str(paths.root / "gradlew"), "user_home": str(paths.gradle_home),
            "distribution_home": str(home),
            "wrapper_files": {name: collector.file_pin(collector.selected(paths.root, name), budget) for name in sorted(collector.WRAPPER_FILES)},
            "distribution_files": collector.inventory(home, ["bin", "lib"], budget),
            "cache_marker": {"path": str(marker), **collector.file_pin(marker, budget, 512)}}


def observe_tools(recipe: Recipe) -> tuple[dict, dict, dict]:
    p, budget = recipe.paths, recipe.budget
    env21, env25 = safe_env(p, recipe.original, p.java21), safe_env(p, recipe.original, p.java25)
    java21, java25 = java_enrollment(p.java21, 21, budget), java_enrollment(p.java25, 25, budget)
    observed = {}
    for label, home, value, env in (("jdk21-version", p.java21, java21, env21), ("jdk25-version", p.java25, java25, env25)):
        _, out, err = recipe.command(label, [str(home / "bin/java"), "-version"], cwd=p.root, env=env, timeout=60, max_bytes=65536)
        version = collector.java_version((out + b"\n" + err).decode("utf-8"))
        need(version == value["version"], "native_selftest_tool_version_mismatch")
        observed[label] = version
    _, out, _ = recipe.command("wrapper-version", [str(p.root / "gradlew"), "--no-daemon", "--console=plain", "--version"],
                               cwd=p.root, env=env25, timeout=180)
    versions = re.findall(r"^Gradle ([0-9.]+)$", out.decode("utf-8"), re.M)
    need(versions == ["9.6.1"], "native_selftest_tool_version_mismatch")
    _, out, _ = recipe.command("xcode-version", [str(collector.XCODEBUILD), "-version"], cwd=p.root, env=env25, timeout=60, max_bytes=65536)
    text = out.decode("utf-8")
    versions = re.findall(r"^Xcode ([0-9.]+)$", text, re.M)
    builds = re.findall(r"^Build version ([A-Za-z0-9.]+)$", text, re.M)
    need(versions == ["26.4.1"] and builds == ["17E202"], "native_selftest_tool_version_mismatch")
    xcode = {"developer_dir": str(DEVELOPER_DIR), "version": versions[0], "build": builds[0], "files": {}}
    for sdk in ("iphoneos", "iphonesimulator"):
        _, out, _ = recipe.command(sdk + "-version", [str(collector.XCRUN), "--sdk", sdk, "--show-sdk-version"],
                                   cwd=p.root, env=env25, timeout=60, max_bytes=65536)
        version = out.decode("utf-8").strip()
        need(version == "26.4", "native_selftest_tool_version_mismatch")
        xcode[sdk + "_sdk"] = version
    for key, path in (("xcodebuild", collector.XCODEBUILD), ("xcrun", collector.XCRUN), ("info_plist", DEVELOPER_DIR.parent / "Info.plist")):
        collector.absolute(path)
        xcode["files"][key] = {"path": str(path), **collector.file_pin(path, budget)}
    collector.absolute(DEVELOPER_DIR, directory=True)
    collector.absolute(CC.resolve(strict=True))
    recipe.summary["observed_tools"] = {"java21": observed["jdk21-version"], "java25": observed["jdk25-version"],
        "gradle": "9.6.1", "xcode": xcode["version"], "xcode_build": xcode["build"],
        "iphoneos_sdk": xcode["iphoneos_sdk"], "iphonesimulator_sdk": xcode["iphonesimulator_sdk"],
        "native": None}
    return java21, java25, xcode


def base_gradle(paths: Paths, java: Path, project_cache: Path) -> list[str]:
    kotlin_state = project_cache.with_name(project_cache.name + "-kotlin-state")
    return [str(paths.root / "gradlew"), "--no-daemon", "--no-configuration-cache", "--no-build-cache",
            "--no-parallel", "--rerun-tasks", "--console=plain", "--max-workers=1",
            "--project-cache-dir", str(project_cache), "-Dorg.gradle.java.home=" + str(java),
            "-Porg.gradle.java.installations.auto-download=false", "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.paths=" + str(java), "-Pkotlin.compiler.execution.strategy=in-process",
            "-Pkotlin.project.persistent.dir=" + str(kotlin_state), "-Dkotlin.project.persistent.dir=" + str(kotlin_state)]


def validate_jars(paths: Paths, budget: collector.Budget) -> tuple[dict, dict]:
    stage = paths.private / "jars"
    manifest = collector.strict_json(collector.bounded_read(stage / "manifest.json", budget, collector.MAX_ENROLLMENT))
    need(set(manifest) == {"schema_version", "producer", "source_root", "gradle_version", "java_version", "jars"}
         and type(manifest["schema_version"]) is int and manifest["schema_version"] == 1
         and manifest["producer"] == "fixed_native_throws_jar_recipe/1" and manifest["source_root"] == str(paths.root)
         and manifest["gradle_version"] == "9.6.1" and re.fullmatch(r"21(?:\.[0-9]+)*(?:[-+][A-Za-z0-9._+-]+)?", manifest["java_version"]) is not None,
         "native_selftest_jar_manifest_invalid")
    rows = manifest["jars"]
    need(isinstance(rows, list) and 4 <= len(rows) <= 64, "native_selftest_jar_manifest_invalid")
    classpath, components, identity, owners, names, folded_names = [], {}, None, [], set(), set()
    for index, row in enumerate(rows):
        need(isinstance(row, dict) and set(row) == {"index", "origin", "project", "coordinate", "source", "path", "size", "digest"}
             and type(row["index"]) is int and row["index"] == index, "native_selftest_jar_manifest_invalid")
        path = collector.absolute(row["path"])
        need(path.parent == stage and re.fullmatch(r"[A-Za-z0-9_.+-]+\.jar", path.name) is not None
             and path.name.casefold() not in folded_names, "native_selftest_jar_origin_invalid")
        names.add(path.name)
        folded_names.add(path.name.casefold())
        source = collector.absolute(row["source"])
        if row["origin"] == "owner_project":
            project = row["project"]
            need(project in ALLOWED_PROJECTS and source == paths.private / "module-build" / project[1:] / "libs" / path.name
                 and row["coordinate"] == "dev.ktchecker:" + project[1:] + ":0.1.0-dev.1", "native_selftest_jar_origin_invalid")
            owners.append(project)
        else:
            need(row["origin"] == "maven" and row["project"] is None
                 and source.is_relative_to(paths.gradle_home / "caches/modules-2/files-2.1")
                 and isinstance(row["coordinate"], str)
                 and re.fullmatch(r"[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+", row["coordinate"]) is not None,
                 "native_selftest_jar_origin_invalid")
        pin = {key: row[key] for key in ("size", "digest")}
        collector.verify_pin(source, pin, budget, collector.MAX_JAR)
        collector.verify_pin(path, pin, budget, collector.MAX_JAR)
        classpath.append({"path": str(path), **pin})
        with zipfile.ZipFile(path) as archive:
            for role, entry in collector.COMPONENT_ENTRIES.items():
                if entry in archive.namelist():
                    need(role not in components and row["origin"] == "owner_project"
                         and row["project"] == COMPONENT_PROJECTS[role], "native_selftest_component_provenance_invalid")
                    components[role] = {"classpath_index": index, "digest": collector.digest_bytes(collector.jar_bytes(archive, entry, 4 * 1024 * 1024))}
            if collector.NATIVE_RESOURCE + "manifest" in archive.namelist():
                need(identity is None, "native_selftest_component_provenance_invalid")
                lines = collector.jar_bytes(archive, collector.NATIVE_RESOURCE + "manifest", 512).decode("utf-8").splitlines()
                need(len(lines) == 4, "native_selftest_component_provenance_invalid")
                identity = dict(zip(("protocolVersion", "target", "sourceDigest", "binaryDigest"), lines))
                receipt = collector.strict_json(collector.jar_bytes(archive, collector.NATIVE_RESOURCE + "build-provenance.json", 16384))
                source_c = paths.root / "modules/provider-runtime/native/owned_process_supervisor.c"
                builder = source_c.with_name("build_supervisor.py")
                need(receipt.get("status") == "built" and receipt.get("host", {}).get("target") == "macos-aarch64"
                     and receipt.get("inputs", {}).get("source", {}).get("sha256") == collector.file_pin(source_c, budget)["digest"] == identity["sourceDigest"]
                     and receipt.get("inputs", {}).get("builder", {}).get("sha256") == collector.file_pin(builder, budget)["digest"]
                     and receipt.get("compiler", {}).get("driverSha256") == collector.file_pin(CC.resolve(strict=True), budget)["digest"]
                     and receipt.get("output", {}).get("sha256") == identity["binaryDigest"], "native_selftest_component_provenance_invalid")
    need(set(PROJECTS) <= set(owners) <= set(ALLOWED_PROJECTS) and len(owners) == len(set(owners))
         and owners == [project for project in ALLOWED_PROJECTS if project in owners]
         and set(components) == set(collector.COMPONENT_ENTRIES), "native_selftest_jar_origin_invalid")
    need(set(item.name for item in stage.iterdir()) == names | {"manifest.json"}, "native_selftest_jar_manifest_invalid")
    value = {"classpath": classpath, "components": components, "supervisor_identity": identity}
    collector.classpath_input(value, budget)
    return value, collector.file_pin(stage / "manifest.json", budget, collector.MAX_ENROLLMENT)


def build_jars(recipe: Recipe) -> tuple[dict, dict]:
    p = recipe.paths
    init = p.private / "jar-recipe.gradle"
    with init.open("x", encoding="utf-8") as output:
        output.write(JAR_INIT)
    argv = base_gradle(p, p.java21, p.private / "module-project-cache") + [
        "--init-script", str(init), "-PnativeThrowsCiBuild=" + str(p.private / "module-build"),
        "-PnativeThrowsCiStage=" + str(p.private / "jars"), "-PnativeSupervisorPython=" + str(p.python),
        "-PnativeSupervisorCc=" + str(CC), STAGE_TASK]
    _, stdout, _ = recipe.command("stage-component-jars", argv, cwd=p.root, env=safe_env(p, recipe.original, p.java21))
    require_tasks(stdout, REQUIRED_BUILD_TASKS)
    wire_jar = p.private / "module-build/worker-protocol/libs"
    if wire_jar.exists():
        require_tasks(stdout, (":worker-protocol:compileJava", ":worker-protocol:jar"))
    return validate_jars(p, recipe.budget)


@contextmanager
def provision_adapter(recipe: Recipe):
    """Scoped reuse of the existing provisioner; no nested competing launcher.

    Its exact fixed acquisition/version commands run through the reviewed held
    leader helper. No downloader or provisioning validation is replaced.
    """
    previous_logged, previous_source = native.run_logged, native.source_identity

    def logged(argv, *, cwd, log_base, timeout=1200, max_output_bytes=MAX_LOG,
               env=None, separate_stderr=False, additional_output_paths=()):
        p = recipe.paths
        need(not additional_output_paths and Path(cwd) == p.provision and max_output_bytes == 65536,
             "native_selftest_provisioning_adapter_invalid")
        acquisition = ["/usr/bin/curl", "--disable", "--fail", "--silent", "--show-error", "--proto", "=https",
            "--proxy", "", "--max-time", "590", "--connect-timeout", "20", "--max-filesize", str(native.MAX_ARCHIVE_BYTES),
            "--max-redirs", "0", "--retry", "0", "--output", str(p.provision / "archive.tar.gz"),
            "--write-out", "%{http_code}", native.ARCHIVE_URL]
        version = [str(p.data / native.HOME_NAME / "bin/konanc"), "-version", "-Xoverride-konan-properties=airplaneMode=true"]
        acquisition_env = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}
        version_env = {**acquisition_env, "JAVA_HOME": str(p.java25), "JAVA_OPTS": "-Xmx512m",
            "KONAN_DATA_DIR": str(p.data), "KONAN_USE_INTERNAL_SERVER": "0", "DEVELOPER_DIR": str(DEVELOPER_DIR),
            "HOME": str(p.provision / "home")}
        is_acquisition = (argv == acquisition and Path(log_base) == p.provision / "transport"
                          and timeout == 600 and separate_stderr is False and env == acquisition_env)
        is_version = (argv == version and Path(log_base) == p.provision / "compiler-version"
                      and timeout == 60 and separate_stderr is True and env == version_env)
        need(is_acquisition or is_version, "native_selftest_provisioning_adapter_invalid")
        label = "native-archive-acquisition" if is_acquisition else "native-provisioned-version"
        value, out, err = recipe.command(label, argv, cwd=Path(cwd), env=env,
                                          timeout=timeout, max_bytes=max_output_bytes)
        return {**value, "state": "completed"}, out.decode("utf-8"), err.decode("utf-8")

    def source(root=REPO_ROOT):
        need(Path(root) == recipe.paths.root, "native_selftest_provisioning_adapter_invalid")
        return recipe.guard()

    native.run_logged, native.source_identity = logged, source
    try:
        yield
    finally:
        native.run_logged, native.source_identity = previous_logged, previous_source


def provision_distribution(recipe: Recipe, java25: dict, xcode: dict) -> dict:
    p = recipe.paths
    env = safe_env(p, recipe.original, p.java25)
    release = collector.bounded_read(p.java25 / "release", recipe.budget, 65536).decode("utf-8")
    release_properties = {key: value.strip().strip('"') for line in release.splitlines()
                          for key, separator, value in [line.partition("=")]
                          if separator and key in {"JAVA_VERSION", "JAVA_RUNTIME_VERSION", "IMPLEMENTOR"}}
    observed = {"schema_version": 1, "evidence_kind": "observed_environment", "state": "passed",
        "source": recipe.source, "host": {"os": platform.system(), "arch": platform.machine()},
        "ci": {key: env[key] for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB")},
        "tool_paths": {"DEVELOPER_DIR": str(DEVELOPER_DIR)},
        "java": {"major": 25, "path": str(p.java25 / "bin/java"), "version": java25["version"],
            "launcher_digest": java25["files"]["bin/java"]["digest"], "release_properties": release_properties,
            "release_file_digest": java25["files"]["release"]["digest"]},
        "gradle": {"state": "pinned", "executed_wrapper_version": "9.6.1"},
        "xcode": {"state": "observed", **{key: xcode[key] for key in ("version", "build", "iphoneos_sdk", "iphonesimulator_sdk")}}}
    environment_path = p.private / "provisioning-environment.json"
    private_json(environment_path, observed, recipe.budget)
    args = argparse.Namespace(output=p.private / "native-original-receipt.json", selection=p.provision / "selection.json",
                              environment=environment_path, targets=[TARGET])
    recipe.guard()
    with environment(env), provision_adapter(recipe):
        value = native.provision(args, p.root)
        native.publish_json_no_clobber(args.output, value)
        need(value.get("state") == "passed", "native_selftest_provisioning_failed")
        _, _, selected_xcode = native.observed_prerequisites(environment_path, p.root, recipe.source)
        original_home, data, reference = native.consume_selection(args.selection, args.output, TARGET,
                                                                  p.root, recipe.source, p.java25, selected_xcode)
    need(original_home == p.data / native.HOME_NAME and data == p.data, "native_selftest_provisioning_failed")
    recipe.guard()
    recipe.summary["observed_tools"]["native"] = value["compiler_version_observed"]
    return {"reference": reference, "receipt_pin": collector.file_pin(args.output, recipe.budget, collector.MAX_ENROLLMENT),
            "selection_pin": collector.file_pin(args.selection, recipe.budget, 8 * 1024 * 1024)}


def copy_file(source: Path, destination: Path, budget: collector.Budget, maximum: int) -> dict:
    before = collector.file_pin(source, budget, maximum)
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(destination, flags, 0o700 if source.stat().st_mode & 0o111 else 0o600)
    try:
        with source.open("rb") as stream:
            total = 0
            while block := stream.read(1024 * 1024):
                budget.remaining(PREP_RESERVE)
                total += len(block)
                need(total <= maximum, "native_selftest_copy_invalid")
                offset = 0
                while offset < len(block):
                    count = os.write(fd, block[offset:])
                    need(count > 0, "native_selftest_copy_invalid")
                    offset += count
        os.fsync(fd)
    finally:
        os.close(fd)
    need(collector.file_pin(source, budget, maximum) == before == collector.file_pin(destination, budget, maximum),
         "native_selftest_copy_invalid")
    return before


def clone_native(recipe: Recipe) -> dict:
    p, budget = recipe.paths, recipe.budget
    source, destination = p.data / native.HOME_NAME, p.native_home
    destination.parent.mkdir(mode=0o700)
    destination.mkdir(mode=0o700)
    rows, links, total = [], 0, 0
    for current, directories, names in os.walk(source, followlinks=False, onerror=collector.walk_error):
        budget.remaining(PREP_RESERVE)
        need(all(not (Path(current) / name).is_symlink() for name in directories), "native_selftest_copy_invalid")
        for name in names:
            path = Path(current) / name
            relative = collector.relative(path.relative_to(source).as_posix())
            selected = path.resolve(strict=True) if path.is_symlink() else path
            need(selected.is_relative_to(source) and selected.is_file(), "native_selftest_copy_invalid")
            links += int(path.is_symlink())
            pin = copy_file(selected, destination / relative, budget, native.MAX_FILE_BYTES)
            total += pin["size"]
            need(total <= native.MAX_EXTRACTED_BYTES and len(rows) < native.MAX_MEMBERS, "native_selftest_copy_invalid")
            rows.append({"path": relative, **pin, "executable": bool(selected.stat().st_mode & 0o111)})
    need(rows, "native_selftest_copy_invalid")
    return {"source_and_copy_byte_manifest_digest": collector.canonical_digest(sorted(rows, key=lambda row: row["path"])),
            "archive_enrolled_regular_file_links_materialized": links, "copied_bytes": total,
            "original_selection_mutated": False}


def native_config(home: Path, budget: collector.Budget) -> tuple[bytes, dict, dict]:
    raw = collector.bounded_read(collector.selected(home, "konan/konan.properties"), budget, 1024 * 1024)
    config = {}
    for line in raw.decode("utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith(("#", "!")):
            continue
        key, separator, value = text.partition("=")
        if separator:
            key = key.strip()
            need(key not in config, "native_selftest_native_config_incompatible")
            config[key] = value.strip().replace("\\:", ":")
    need(config.get("dependenciesUrl", "").rstrip("/") == DEPENDENCIES_URL
         and config.get("airplaneMode") in {"false", "true"}, "native_selftest_native_config_incompatible")
    dependencies = {}
    for kind, key in (("llvm", "llvmHome.macos_arm64"), ("libffi", "libffiDir.macos_arm64"),
                      ("sysroot-iosArm64", "targetSysRoot.ios_arm64")):
        package = config.get(key, "")
        for _ in range(16):
            if re.fullmatch(r"\$[A-Za-z0-9_.-]+", package):
                package = config.get(package[1:], "")
            else:
                break
        need(re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", package) is not None and package not in {".", ".."},
             "native_selftest_native_dependency_layout_incompatible")
        dependencies[kind] = package
    return raw, config, dependencies


def freeze_airplane(home: Path, budget: collector.Budget) -> dict:
    raw, _, _ = native_config(home, budget)
    text = raw.decode("utf-8")
    changed, count = re.subn(r"(?m)^([ \t]*airplaneMode[ \t]*=[ \t]*)(?:true|false)([ \t]*\r?)$",
                             lambda match: match[1] + "true" + match[2], text)
    need(count == 1, "native_selftest_native_config_incompatible")
    path = collector.selected(home, "konan/konan.properties")
    with path.open("wb") as output:
        data = changed.encode("utf-8")
        need(output.write(data) == len(data), "native_selftest_copy_invalid")
        output.flush()
        os.fsync(output.fileno())
    _, config, _ = native_config(home, budget)
    need(config["airplaneMode"] == "true", "native_selftest_native_config_incompatible")
    return {"controlled_transformation": "replace_one_existing_airplaneMode_assignment_with_true",
            "before_digest": collector.digest_bytes(raw), "after_digest": collector.file_pin(path, budget)["digest"],
            "original_selection_mutated": False}


def cache_bound(paths: Paths, budget: collector.Budget, *, reserve: bool = True) -> None:
    total, entries = 0, 0
    for current, directories, names in os.walk(paths.private, followlinks=False, onerror=collector.walk_error):
        budget.remaining(PREP_RESERVE if reserve else 0)
        entries += 1 + len(names)
        need(entries <= MAX_PRIVATE_ENTRIES, "native_selftest_cache_limit")
        for name in [*directories, *names]:
            path = Path(current) / name
            info = path.lstat()
            need(stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode) or stat.S_ISLNK(info.st_mode), "native_selftest_cache_limit")
            if stat.S_ISLNK(info.st_mode):
                need(path.resolve(strict=True).is_relative_to(paths.private), "native_selftest_cache_limit")
            if not stat.S_ISDIR(info.st_mode):
                total += info.st_size
        need(total <= MAX_PRIVATE_BYTES, "native_selftest_cache_limit")


def dependency_readiness(paths: Paths, dependencies: dict, budget: collector.Budget) -> None:
    for package in dependencies.values():
        try:
            root = collector.selected(paths.data, "dependencies/" + package, directory=True)
        except (collector.Problem, OSError):
            raise SelftestError("native_selftest_native_dependency_missing") from None
        found = False
        for current, _, names in os.walk(root, followlinks=False, onerror=collector.walk_error):
            budget.remaining(PREP_RESERVE)
            if any(stat.S_ISREG((Path(current) / name).lstat().st_mode) and (Path(current) / name).stat().st_size > 0 for name in names):
                found = True
                break
        need(found, "native_selftest_native_dependency_missing")


def klib_outputs(directory: Path, budget: collector.Budget) -> str:
    pins, native_seen, total = [], False, 0
    for current, _, names in os.walk(directory, followlinks=False, onerror=collector.walk_error):
        budget.remaining(PREP_RESERVE)
        for name in names:
            path = Path(current) / name
            pin = collector.file_pin(path, budget)
            total += pin["size"]
            need(len(pins) < collector.MAX_FILES and total <= MAX_PRIVATE_BYTES, "native_selftest_cache_limit")
            pins.append({"path": path.relative_to(directory).as_posix(), **pin})
            raw = None
            if path.suffix == ".klib" and zipfile.is_zipfile(path):
                with zipfile.ZipFile(path) as archive:
                    if "default/manifest" in archive.namelist():
                        raw = collector.jar_bytes(archive, "default/manifest", 65536)
            elif name == "manifest" and path.parent.name == "default":
                raw = collector.bounded_read(path, budget, 65536)
            if raw is not None:
                values = collector.properties(raw.decode("utf-8"))
                native_seen |= values.get("builtins_platform") == "NATIVE" and "ios_arm64" in values.get("native_targets", "").split()
    need(native_seen, "native_selftest_klib_unconfirmed")
    return collector.canonical_digest(sorted(pins, key=lambda pin: pin["path"]))


def prepare_native(recipe: Recipe) -> dict:
    p, budget = recipe.paths, recipe.budget
    copy = clone_native(recipe)
    raw, config, dependencies = native_config(p.native_home, budget)
    need(config["airplaneMode"] == "false", "native_selftest_native_config_incompatible")
    selected_inputs = collector.inventory(p.native_home, collector.native_prefixes([TARGET]), budget)
    fixture = p.private / "prerequisite-source"
    fixture.mkdir(mode=0o700)
    _, fixture_pins = collector.fixture_input(p.root, p.root / collector.FIXTURE, budget)
    for relative, expected in {**fixture_pins, "source-manifest.json": collector.file_pin(p.root / collector.FIXTURE / "source-manifest.json", budget)}.items():
        need(copy_file(p.root / collector.FIXTURE / relative, fixture / relative, budget, collector.MAX_ENROLLMENT) == expected,
             "native_selftest_copy_invalid")
    init = p.private / "fixture-recipe.gradle"
    init.write_text(FIXTURE_INIT, encoding="utf-8")
    results = {}
    for mode in ("online", "offline"):
        if mode == "offline":
            need(collector.inventory(p.native_home, collector.native_prefixes([TARGET]), budget) == selected_inputs,
                 "native_selftest_copy_invalid")
            results["configuration_freeze"] = freeze_airplane(p.native_home, budget)
            selected_inputs["konan/konan.properties"] = collector.file_pin(p.native_home / "konan/konan.properties", budget)
            dependency_readiness(p, dependencies, budget)
        build = p.private / (mode + "-klib-build")
        argv = base_gradle(p, p.java25, p.private / (mode + "-project-cache")) + [
            "-p", str(fixture), "--init-script", str(init), "-PnativeThrowsCiBuild=" + str(build),
            "-PfixtureBuildDir=" + str(build),
            "-Pkotlin.native.home=" + str(p.native_home), "-PfixtureNegativeCase=empty-list-nonempty"]
        if mode == "offline":
            argv.append("--offline")
        _, stdout, _ = recipe.command(mode + "-fixed-klib-prerequisite", argv + list(PREREQUISITE_TASKS),
                                      cwd=fixture, env=safe_env(p, recipe.original, p.java25))
        require_tasks(stdout, PREREQUISITE_TASKS)
        need(collector.inventory(p.native_home, collector.native_prefixes([TARGET]), budget) == selected_inputs,
             "native_selftest_copy_invalid")
        results[mode + "_outputs_digest"] = klib_outputs(build, budget)
        cache_bound(p, budget)
    # The exact copy is frozen BEFORE measuring its collector enrollment.
    original = collector.bounded_read(p.data / native.HOME_NAME / "konan/konan.properties", budget, 1024 * 1024)
    need(original == raw, "native_selftest_copy_invalid")
    _, finalized, finalized_dependencies = native_config(p.native_home, budget)
    need(finalized["airplaneMode"] == "true" and finalized_dependencies == dependencies,
         "native_selftest_native_config_incompatible")
    dependency_readiness(p, dependencies, budget)
    return {"execution_copy": copy, "dependencies": dependencies, **results}


def collector_argv(paths: Paths, authority_digest: str) -> list[str]:
    return ["--authority", str(paths.private / "observed-enrollment.json"), "--authority-digest", authority_digest,
            "--fixture", str(paths.root / collector.FIXTURE), "--broker-java-home", str(paths.java21),
            "--project-java-home", str(paths.java25), "--native-home", str(paths.native_home),
            "--gradle", str(paths.root / "gradlew"), "--gradle-user-home", str(paths.gradle_home),
            "--konan-data-dir", str(paths.data), "--developer-dir", str(DEVELOPER_DIR), "--home", str(paths.home),
            "--private-dir", str(paths.private / "collector-originals"), "--output", str(paths.private / "collector-result.json"),
            "--target", TARGET, "--cohort", "first-mac", "--global-timeout", str(COLLECTOR_SECONDS),
            "--case-timeout", str(CASE_SECONDS), "--max-output-bytes", "33554432", "--max-cohort-bytes", "268435456"]


def enroll(recipe: Recipe, jars: dict, jar_pin: dict, java21: dict, java25: dict, xcode: dict,
           provisioned: dict, prepared: dict) -> tuple[dict, dict, list[str]]:
    recipe.guard()
    p, budget = recipe.paths, recipe.budget
    refreshed, refreshed_pin = validate_jars(p, budget)
    need(refreshed == jars and refreshed_pin == jar_pin, "native_selftest_component_provenance_invalid")
    need(collector.file_pin(p.private / "native-original-receipt.json", budget, collector.MAX_ENROLLMENT) == provisioned["receipt_pin"]
         and collector.file_pin(p.provision / "selection.json", budget, 8 * 1024 * 1024) == provisioned["selection_pin"],
         "native_selftest_provisioning_failed")
    with environment(safe_env(p, recipe.original, p.java25)):
        _, _, selected_xcode = native.observed_prerequisites(p.private / "provisioning-environment.json", p.root, recipe.source)
        _, _, original_reference = native.consume_selection(p.provision / "selection.json", p.private / "native-original-receipt.json",
            TARGET, p.root, recipe.source, p.java25, selected_xcode)
    need(original_reference == provisioned["reference"], "native_selftest_provisioning_failed")
    native_value = {"home": str(p.native_home), "data_dir": str(p.data), "targets": [TARGET],
                    "files": collector.inventory(p.native_home, collector.native_prefixes([TARGET]), budget),
                    "dependencies": prepared["dependencies"]}
    authority = {"schema_version": 1, "evidence_kind": "owner_enrolled_native_throws_build",
        "context": recipe.dispatch["context"], "source": recipe.source,
        "fixture_manifest_digest": collector.MANIFEST_DIGEST,
        "git": {"path": str(GIT), **collector.file_pin(GIT, budget)}, **jars,
        "java21": java21, "java25": java25, "gradle": gradle_enrollment(p, budget),
        "native": native_value, "xcode": xcode}
    pin = private_json(p.private / "observed-enrollment.json", authority, budget)
    argv = collector_argv(p, pin["digest"])
    args = collector.parser().parse_args(argv)
    collector.enrollment_input(args, p.root, budget)
    collector.verify_enrollment(authority, args, p.root, budget)
    provenance = {"schema_version": 1, "evidence_kind": "measured_repository_selftest_recipe_handoff",
        "authorization_basis": "prior_explicit_repository_workflow_policy_not_artifact_hash_as_consent",
        "prior_permit_pin": recipe.permit_pin, "recipe_digest": recipe_digest(), "source": recipe.source,
        "context": recipe.dispatch["context"], "jar_manifest_pin": jar_pin, "steps": recipe.summary["steps"],
        "original_provisioning": provisioned, "native_preparation": prepared, "observed_enrollment_pin": pin,
        "independent_source_to_binary_or_complete_external_closure_verified": False,
        "consumer_project_authority": False, "binary_publication_authorized": False}
    provenance_pin = private_json(p.private / "recipe-handoff.json", provenance, budget)
    recipe.summary.update(observed_enrollment_digest=pin["digest"], recipe_handoff_digest=provenance_pin["digest"],
                          fresh_required_jvm_tasks_observed=True, fresh_offline_fixed_klib_tasks_observed=True)
    recipe.guard()
    return authority, pin, argv


def empty_controls() -> dict:
    return {"schema_version": 1, "evidence_kind": "repository_native_throws_selftest_controls",
            "policy": POLICY_ID, "selection": "first-mac", "target": TARGET, "state": "unavailable",
            "collector_invoked": False, "collector_exit_code": None, "collector_evidence_confirmed": False,
            "collector_summary_digest": None, "source": None, "observed_enrollment_digest": None,
            "selected_control_count": 8, "passing_control_count": 0,
            "cases": [{"name": name, "state": "not_attempted", "passing_control": False} for name in collector.FIRST_MAC],
            "comprehensive_cohort_completed": False, "target_qualified": False, "release_qualified": False,
            "source_to_binary_provenance_verified": False, "fresh_source_compilation_verified": False,
            "external_native_dependency_closure_qualified": False, "raw_logs_uploaded": False,
            "errors": [], "limitations": collector.LIMITATIONS}


def confirm_controls(value: dict, exit_code: int, source: dict, digest: str, raw_digest: str) -> dict:
    need(isinstance(value, dict) and type(exit_code) is int and exit_code in {0, 1}
         and collector.valid_digest(digest) and collector.valid_digest(raw_digest)
         and value.get("evidence_kind") == "native_throws_installed_driver_controls"
         and value.get("schema_version") == 1 and type(value.get("schema_version")) is int
         and value.get("selection") == "first-mac" and value.get("targets") == [TARGET]
         and value.get("cohort_id") == collector.COHORT and value.get("source_manifest_digest") == collector.MANIFEST_DIGEST
         and value.get("owner_enrollment_digest") == digest and type(value.get("selected_authored_control_count")) is int
         and value.get("selected_authored_control_count") == 8
         and all(value.get(key) is False for key in ("comprehensive_cohort_completed", "full_selected_target_cohort_completed",
             "target_qualified", "release_qualified", "source_to_binary_provenance_verified", "fresh_source_compilation_verified",
             "external_native_dependency_closure_qualified", "raw_logs_uploaded")), "native_selftest_collector_unconfirmed")
    state = value.get("state")
    need(exit_code == 0 and state == "passed_controls_only" or exit_code == 1 and state in {"failed", "unavailable"},
         "native_selftest_collector_unconfirmed")
    cases = value.get("cases")
    need(isinstance(cases, list) and len(cases) == 8 and all(isinstance(row, dict) for row in cases)
         and [row.get("name") for row in cases] == list(collector.FIRST_MAC)
         and all(row.get("target") == TARGET and row.get("state") in {"passed", "failed", "not_attempted"}
                 and type(row.get("passing_control")) is bool for row in cases), "native_selftest_collector_unconfirmed")
    if exit_code == 0:
        counts = value.get("counts")
        need(value.get("source") == source and value.get("source_and_launch_tuple_verified_final") is True
             and value.get("required_status") == "passing_controls_only" and value.get("provider_controls_attempted") is True
             and value.get("errors") == [] and isinstance(counts, dict) and all(type(number) is int for number in counts.values())
             and counts == {"selected_invocations": 8, "attempted": 8, "passed": 8,
                 "observed_incomplete": 0, "pending_final_verification": 0, "failed": 0, "not_attempted": 0}
             and all(row["state"] == "passed" and row["passing_control"] is True and row.get("errors") == []
                 and row.get("source_and_launch_tuple_verified_before") is True and row.get("source_and_launch_tuple_verified_after") is True
                 and isinstance(row.get("command"), dict) and collector.command_ok(row["command"]) for row in cases),
             "native_selftest_collector_unconfirmed")
    else:
        need(value.get("source") in (None, source) and isinstance(value.get("errors"), list)
             and all(isinstance(code, str) for code in value["errors"])
             and not any(row["passing_control"] for row in cases),
             "native_selftest_collector_unconfirmed")
    result = empty_controls()
    result.update(state=state, collector_invoked=True, collector_exit_code=exit_code, collector_evidence_confirmed=True,
                  collector_summary_digest=raw_digest, source=value.get("source"), observed_enrollment_digest=digest,
                  passing_control_count=8 if exit_code == 0 else 0,
                  cases=[{"name": row["name"], "state": row["state"], "passing_control": row["passing_control"]} for row in cases],
                  errors=sorted({collector.public_code(code) for code in value.get("errors", [])}))
    return result


def run(policy: str, expected_commit: str, root: Path = REPO_ROOT) -> int:
    original = dict(os.environ)
    event_path = collector.absolute(Path(original.get("GITHUB_EVENT_PATH", "")))
    budget = collector.Budget.start(RECIPE_SECONDS)
    event_raw = collector.bounded_read(event_path, budget, 1024 * 1024)
    event_pin = {"size": len(event_raw), "digest": collector.digest_bytes(event_raw)}
    event = collector.strict_json(event_raw, 1024 * 1024)
    dispatch = admit(policy, expected_commit, original, event)
    need(platform.system() == "Darwin" and platform.machine().lower() in {"arm64", "aarch64"}
         and sys.version_info[:2] == (3, 12), "native_selftest_host_invalid")
    paths = select_paths(root, original)
    paths.stage.parent.mkdir(mode=0o700, exist_ok=True)
    paths.stage.mkdir(mode=0o700)
    paths.private.mkdir(mode=0o700)
    for directory in (paths.private / "commands", paths.gradle_home, paths.home):
        directory.mkdir(mode=0o700)
    summary = {"schema_version": 1, "evidence_kind": "repository_native_throws_selftest_policy_build",
        "policy": POLICY_ID, "recipe_digest": recipe_digest(), "state": "unavailable", "source": None,
        "prior_policy_confirmed": False, "prior_permit_digest": None, "observed_enrollment_digest": None,
        "recipe_handoff_digest": None, "declared_tools": POLICY["declared_tools"], "observed_tools": None,
        "fresh_required_jvm_tasks_observed": False, "fresh_offline_fixed_klib_tasks_observed": False,
        "collector_invoked": False, "collector_exit_code": None, "collector_evidence_confirmed": False,
        "target": TARGET, "selection": "first-mac", "selected_control_count": 8,
        "consumer_project_authority": False, "binary_publication_authorized": False, "target_qualified": False,
        "release_qualified": False, "complete_external_closure_qualified": False, "raw_logs_uploaded": False,
        "steps": [], "errors": [], "recorded_at": collector.utc_now()}
    controls = empty_controls()
    outputs = []
    recipe = Recipe(paths, original, dispatch, event_pin, event_path, budget, summary)
    result = 1
    try:
        for path in (paths.stage / "policy-build.json", paths.stage / "native-throws-controls.json"):
            outputs.append(MetadataFile(path))
        outputs[0].write(summary)
        outputs[1].write(controls)
        recipe.freeze_permit()
        java21, java25, xcode = observe_tools(recipe)
        jars, jar_pin = build_jars(recipe)
        cache_bound(paths, budget)
        provisioned = provision_distribution(recipe, java25, xcode)
        cache_bound(paths, budget)
        prepared = prepare_native(recipe)
        authority, pin, argv = enroll(recipe, jars, jar_pin, java21, java25, xcode, provisioned, prepared)
        recipe.guard()
        summary["collector_invoked"] = controls["collector_invoked"] = True
        for row in controls["cases"]:
            row["state"] = "unconfirmed"
        # Same process: do not timeout/kill a Python leader over nested sessions.
        with environment(safe_env(paths, original, paths.java21)):
            exit_code = collector.main(argv)
        summary["collector_exit_code"] = exit_code if type(exit_code) is int and exit_code in {0, 1} else None
        controls["collector_exit_code"] = summary["collector_exit_code"]
        recipe.guard(reserve=False)
        args = collector.parser().parse_args(argv)
        collector.verify_enrollment(authority, args, paths.root, budget)
        cache_bound(paths, budget, reserve=False)
        raw = collector.bounded_read(paths.private / "collector-result.json", budget, MAX_SUMMARY)
        controls = confirm_controls(collector.strict_json(raw, MAX_SUMMARY), exit_code, recipe.source, pin["digest"], collector.digest_bytes(raw))
        summary["collector_evidence_confirmed"] = True
        need(exit_code == 0, "native_selftest_collector_did_not_pass")
        summary["state"] = "passed_controls_only"
        result = 0
    except SelftestError as error:
        summary["errors"].append(str(error))
    except (KeyboardInterrupt, SystemExit):
        summary["errors"].append("native_selftest_interrupted")
    except Exception:
        summary["errors"].append("native_selftest_evidence_unavailable")
    finally:
        if result != 0:
            summary["state"] = "failed" if summary["prior_policy_confirmed"] else "unavailable"
        if not summary["collector_evidence_confirmed"]:
            controls.update(state="unavailable", collector_evidence_confirmed=False, passing_control_count=0)
            for row in controls["cases"]:
                row["passing_control"] = False
        summary["errors"] = sorted(set(summary["errors"]))
        summary["finished_at"] = collector.utc_now()
        try:
            need(len(outputs) == 2, "native_selftest_publication_failed")
            outputs[1].write(controls)
            outputs[0].write(summary)
        except (SelftestError, OSError, ValueError, TypeError):
            result = 1
            summary.update(state="failed", collector_evidence_confirmed=False)
            summary["errors"] = sorted(set([*summary["errors"], "native_selftest_publication_failed"]))
            # One best-effort fail-closed metadata rewrite; never another launch.
            if outputs:
                try:
                    outputs[0].write(summary)
                except (SelftestError, OSError, ValueError, TypeError):
                    pass
        finally:
            for output in outputs:
                try:
                    output.close()
                except OSError:
                    result = 1
        print("Native repository self-test: " + ("passed_controls_only" if result == 0 else "failed_or_unavailable"))
        for code in summary["errors"]:
            print(code)
    return result


class SafeParser(argparse.ArgumentParser):
    def error(self, _: str) -> None:
        raise SelftestError("native_selftest_arguments_invalid")


def main(argv: list[str] | None = None) -> int:
    parser = SafeParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--policy", required=True, choices=[POLICY_ID])
    parser.add_argument("--expected-commit", required=True)
    try:
        args = parser.parse_args(argv)
        return run(args.policy, args.expected_commit)
    except (SelftestError, collector.Problem, OSError, ValueError, TypeError, KeyError):
        print("Native repository self-test: unavailable (fixed policy admission failed)")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
