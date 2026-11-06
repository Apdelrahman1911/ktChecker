#!/usr/bin/env python3
"""Synthetic controls for the prospective Compose CI contract.

These tests are authored SOURCE, not retained executions. They neither obtain
real owner resolution nor run Gradle, Java, Detekt, a native helper or downloads.
All would-be external launch/acquisition seams fail unless explicitly mocked.
The fabricated unit-only metadata/bytes below are NOT a supplied runtime lock,
real SARIF, process evidence, a detector oracle, custody or qualification.
On hosts without the production POSIX I/O capabilities, metadata controls use an
explicit temporary-fixture-only descriptor model; separate controls require the
real production entry points to refuse. That model is not Windows secure I/O,
native execution, privacy enforcement or host qualification.
"""
from __future__ import annotations

from contextlib import ExitStack, redirect_stdout
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

if __package__:
    from . import run_compose_corpus as ci
else:
    import run_compose_corpus as ci


RULE = "ktc.compose.pack.RememberContentMissing"
PARTIAL_RULE = "ktc.compose.pack.RememberMissing"
IMMUTABLE_RULE = "ktc.compose.pack.UnstableCollections"
SOURCE_WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/compose-corpus-validation.yml"
SCOPE = {"module": ":compose-state-effects-corpus", "sourceSet": "main", "target": "jvm", "compilation": "main"}
FLAGS = ["--no-daemon", "--console=plain", "--max-workers=2", "--no-parallel", "--no-configuration-cache"]
DENIED = {
    "untrusted-library-labels": "detekt_compose_library_not_authorized",
    "copied-approved-profile": "detekt_compose_library_not_authorized",
    "missing-runtime": "detekt_compose_library_missing",
    "missing-annotations": "detekt_compose_library_missing",
    "unsupported-runtime": "detekt_compose_tuple_unsupported",
    "changed-owner-resolution": "detekt_compose_owner_resolution_changed",
    "provider-instance-rebind": "detekt_compose_binding_mismatch",
    "revoked-library-permit": "detekt_compose_library_not_authorized",
    "owner-disabled-selected-rule": "rule_disabled_by_owner_selected_configuration",
}
REJECTED = {
    "wrong-source-set": "detekt_compose_compilation_mismatch",
    "wrong-membership": "detekt_compose_compilation_members_mismatch",
    "reordered-dependencies": "detekt_compose_compilation_mismatch",
    "profile-present-at-prepare": "detekt_compose_preparation_profile_present",
}


def digest(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode("utf-8")


def safe_xml(*, child: str = "", suite: str = "") -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{ci.TEST_CLASS}" tests="1" skipped="0" failures="0" errors="0" '
        'timestamp="2026-11-06T01:02:03" hostname="unit-only-runner" time="0.001">'
        '<properties/>'
        f'<testcase name="{ci.TEST_NAME}" classname="{ci.TEST_CLASS}" time="0.001">{child}</testcase>'
        f'{suite}<system-out><![CDATA[]]></system-out><system-err><![CDATA[]]></system-err>'
        '</testsuite>\n'
    ).encode("ascii")


class FakeResponse(io.BytesIO):
    def __init__(self, raw: bytes, url: str, status: int = 200, headers: dict | None = None):
        super().__init__(raw)
        self.url, self.status = url, status
        self.headers = {} if headers is None else headers

    def geturl(self):
        return self.url


class FakeOpener:
    def __init__(self, response: FakeResponse):
        self.response = response
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request.full_url, timeout))
        return self.response


class _FixtureOnlyIO:
    """Portable inert fixture storage, NEVER a production/security fallback.

    Only private unit-created files below one temporary root may reach this seam.
    Production read bounds, hashes, stable-read checks and all metadata joins stay
    in use; anchored/no-follow/nonblocking OS behavior is tested separately.
    """
    def __init__(self, root: Path):
        self.root = root

    def path(self, path: Path, *, directory: bool | None = None) -> Path:
        if not path.is_relative_to(self.root):
            raise AssertionError("unit-only I/O outside the isolated fixture root")
        path = ci.exact_path(path, directory=directory)
        if not path.resolve().is_relative_to(self.root):
            raise AssertionError("unit-only I/O outside the isolated fixture root")
        return path

    def open_leaf(self, path: Path, flags: int, *, nonblocking: bool = False) -> int:
        path = self.path(path)
        if flags == os.O_RDONLY:
            if nonblocking is not True:
                raise AssertionError("unit reader omitted its required nonblocking request")
            self.path(path, directory=False)
        elif flags != (os.O_WRONLY | os.O_CREAT | os.O_EXCL) or nonblocking:
            raise AssertionError("unit-only I/O expected read or exclusive new write")
        # Binary transport on Windows, not fabricated security-flag support.
        if os.name == "nt":
            flags |= os.O_BINARY
        return os.open(path, flags, 0o600)

    def make_directory(self, path: Path) -> None:
        path = self.path(path)
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path(path, directory=True)


class _IsolatedControls(unittest.TestCase):
    allow_fixture_io = True

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        temporary = self.stack.enter_context(tempfile.TemporaryDirectory(prefix="ktc-compose-ci-unit-only-"))
        self.root = Path(temporary).resolve()
        self.stack.enter_context(mock.patch.object(ci, "REPO_ROOT", self.root))
        # These fixtures model a Linux owner workflow, not host wrapper discovery.
        # In particular they must not probe for or require installed Git Bash.
        self.stack.enter_context(mock.patch.object(ci, "wrapper_argv", side_effect=lambda root: [str(root / "gradlew")]))
        self.fixture_io = None
        if self.allow_fixture_io and not ci.nofollow_io_available():
            self.install_fixture_io()
        self.external = {}
        for name in ("source_identity", "run_logged", "gradle_result"):
            self.external[name] = self.stack.enter_context(
                mock.patch.object(ci, name, side_effect=AssertionError("unit-only: external execution forbidden"))
            )
        self.network = self.stack.enter_context(
            mock.patch.object(ci.urllib.request, "build_opener", side_effect=AssertionError("unit-only: network forbidden"))
        )
        self.env = {
            "KTC_COMPOSE_CI_CONSENT": ci.CONSENT,
            "KTC_COMPOSE_EXPECTED_COMMIT": "1" * 40,
            "GITHUB_SHA": "1" * 40,
            "GITHUB_WORKFLOW_SHA": "1" * 40,
            "GITHUB_ACTIONS": "true",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux",
            "RUNNER_ARCH": "X64",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_RUN_ID": "123456",
            "GITHUB_REPOSITORY": "unit-only/repository",
            "GITHUB_JOB": "compose-corpus",
            "GITHUB_WORKFLOW_REF": "unit-only/repository/.github/workflows/compose-corpus-validation.yml@refs/heads/reviewed",
            "KT_CI_JOB_STATUS": "success",
        }
        self.stack.enter_context(mock.patch.dict(os.environ, self.env, clear=True))
        previous_umask = os.umask(0o077)
        self.addCleanup(os.umask, previous_umask)
        self.source = {"commit": "1" * 40, "dirty": False, "unsupported_directories": 0,
                       "working_tree_digest": digest(b"synthetic source identity only"), "file_count": 3}
        self.context = ci.dispatch_context(self.source)
        self.lock_digest = digest(b"synthetic expected lock only")
        self.next_fixture = 0

    def install_fixture_io(self):
        self.fixture_io = _FixtureOnlyIO(self.root)
        for name in ("open_leaf", "make_directory"):
            self.stack.enter_context(mock.patch.object(ci, name, side_effect=getattr(self.fixture_io, name)))
        # Only synthetic orchestration in this test scope bypasses the real gate.
        self.stack.enter_context(mock.patch.object(ci, "require_nofollow_io", return_value=None))

    def put(self, relative: str | Path, raw: bytes, root: Path | None = None) -> Path:
        path = (self.root if root is None else root) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        return path

    def problem(self, expected: str, function, *args, **kwargs):
        with self.assertRaises(ci.Problem) as caught:
            function(*args, **kwargs)
        self.assertEqual(expected, caught.exception.code)

    def no_external_calls(self):
        for external in self.external.values():
            external.assert_not_called()
        self.network.assert_not_called()

    def row(self, coordinate: str, *, raw: bytes = b"unit-only inert bytes", pin: tuple | None = None) -> dict:
        group, module, version = coordinate.split(":")
        host = "https://dl.google.com/dl/android/maven2/" if group.startswith("androidx.") else "https://repo.maven.apache.org/maven2/"
        suffix = group.replace(".", "/") + f"/{module}/{version}/{module}-{version}.jar"
        return {"coordinate": coordinate, "url": host + suffix, "digest": pin[0] if pin else digest(raw),
                "size": pin[1] if pin else len(raw)}

    def lock(self) -> dict:
        # This intentionally synthetic schema fixture does NOT prove resolution.
        provenance = json_bytes({"unit_test_only": True, "not_a_resolution": True})
        self.put("unit-data/provenance.json", provenance)
        dependencies = [self.row(coordinate, pin=pin) for coordinate, pin in ci.API_PINS.items()]
        dependencies += [self.row("org.jetbrains.kotlin:kotlin-stdlib:0.0.0-ci-unit-only"),
                         self.row("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:0.0.0-ci-unit-only")]
        reviewed = {"compilation_id": ":compose-state-effects-corpus:jvm:main", "scope": dict(ci.FIXTURE_SCOPE),
                    "platform": "jvm", "source_set_ids": ["main"], "compiler_version": "2.4.21",
                    "compiler_arguments": [], "dependencies": dependencies}
        unsupported = copy.deepcopy(reviewed)
        unsupported["dependencies"][0] = self.row(
            "androidx.compose.runtime:runtime-desktop:0.0.0-ci-unit-only", raw=b"different synthetic unit-only bytes"
        )
        return {"schema": ci.LOCK_SCHEMA, "dependency_vector_kind": ci.VECTOR_KIND,
                "resolution_provenance": [{"path": "unit-data/provenance.json", "size": len(provenance), "digest": digest(provenance)}],
                "reviewed": reviewed, "unsupported_runtime": unsupported}

    def enrollment(self, root: Path | None = None, rule: str = RULE) -> dict:
        root = self.root if root is None else root
        layout = ci.paths(root, rule)
        descriptor = {"schema": ci.OWNER_SCHEMA, "dependency_vector_kind": ci.VECTOR_KIND,
                      "reviewed": {"compiler_arguments": [], "dependencies": [
                          {"coordinate": "unit.only:inert-library:0.0.0-ci-unit-only", "digest": digest(b"inert library"),
                           "path": str(layout["runtime"] / "inert-library.jar"), "size": len(b"inert library")}
                      ]}, "unsupported_runtime": {"unit_test_only": True}}
        engine = []
        for name in ("dev.detekt:detekt-cli:2.0.0-alpha.6:all", "io.nlopez.compose.rules:detekt:0.6.8",
                     "io.nlopez.compose.rules:common-detekt:0.6.8", "dev.detekt:detekt-rules-libraries:2.0.0-alpha.6",
                     "dev.detekt:detekt-rules-ruleauthors:2.0.0-alpha.6"):
            engine.append({"coordinate": name, "path": str(layout["runtime"] / "engine" / (name.split(":")[1] + ".jar")),
                           "digest": digest(name.encode("ascii")), "size": len(name)})
        return {"jdk_home": str(root / "unit-jdk"), "jdk_launcher_digest": digest(b"inert unit java"),
                "jdk_release_digest": digest(b"inert unit release"), "engine": engine,
                "engine_directory": str(layout["runtime"] / "engine"),
                "owner_file": str(layout["runtime"] / "owner-compilations.json"),
                "owner_descriptor_digest": digest(json_bytes(descriptor)), "descriptor": descriptor,
                "evidence_root": str(layout["evidence"]), "prepared_time_ns": time.time_ns() - 2_000_000_000,
                "xml_absent_before_run": True}

    def patch_enrollment(self, enrollment: dict):
        return self.stack.enter_context(mock.patch.object(ci, "enrollment_input", return_value=(self.source, self.context, enrollment)))

    def collect_fixture(self) -> tuple[dict, dict, dict, Path]:
        enrollment = self.enrollment()
        layout = ci.paths(self.root, RULE)
        self.patch_enrollment(enrollment)
        self.put("gradlew", b"#!/bin/false\n")
        raw_xml = safe_xml()
        self.put(layout["xml"], raw_xml)
        run = layout["evidence"] / "compose-state-effects-unit-only"
        run.mkdir(parents=True)
        arguments = ci.build_arguments(RULE, enrollment)
        attempt = {"source": self.source, "ci": self.context, "selected_rule_id": RULE, "owner_lock_digest": self.lock_digest,
                   "owner_descriptor_digest": enrollment["owner_descriptor_digest"], "arguments": arguments,
                   "started_time_ns": time.time_ns() - 1_000_000_000, "state": "launch_pending_not_execution_proof"}
        build = {"source": self.source, "ci": self.context, "selected_rule_id": RULE, "owner_lock_digest": self.lock_digest,
                 "owner_descriptor_digest": enrollment["owner_descriptor_digest"], "execution_attempt_digest": digest(json_bytes(attempt)),
                 "state": "passed", "return_code": 0, "tasks": {ci.TASK: "EXECUTED", ci.SUPERVISOR_TASK: "EXECUTED"},
                 "required_tasks": [ci.SUPERVISOR_TASK, ci.TASK], "expected_failure": False, "errors": [],
                 "raw_logs_uploaded": False, "command": [str(self.root / "gradlew")] + FLAGS + arguments}
        self.put(layout["private"] / "execute-attempt.json", json_bytes(attempt))
        self.put(layout["build"], json_bytes(build))
        observed = {"private_evidence_index_digest": digest(b"unit-only index"), "native_calls": 8, "whole_rule_qualified": False}
        self.stack.enter_context(mock.patch.object(ci, "native_evidence", return_value=observed))
        self.stack.enter_context(mock.patch.object(ci, "indexed_evidence", return_value=({}, observed["private_evidence_index_digest"])))
        return layout, build, attempt, run

    def guards(self, rule: str) -> dict[str, bytes]:
        denied = dict(DENIED)
        if rule == IMMUTABLE_RULE:
            denied["missing-immutable-family"] = "detekt_compose_api_surface_unknown"
        data = {}
        for key, reason in denied.items():
            prefix = "controls/" + key + "/"
            data[prefix + "observation.json"] = json_bytes({"kind": "actual_provider_guard_check_not_detector_execution",
                                                          "runner_callbacks": 0, "tested": False, "qualified": False})
            data[prefix + "result.json"] = json_bytes({
                "status": "partial", "providerId": "detekt", "findings": [], "rawDiagnostics": [], "reasons": [reason],
                "coverage": [{"ruleId": rule, "state": "unavailable", "reason": reason, "provenance": "not_executed",
                              "certificateId": None, "implemented": False, "tested": False, "qualified": False}],
            })
        for key, reason in REJECTED.items():
            data["controls/" + key + "/preparation-rejection.json"] = json_bytes({
                "kind": "structural_preparation_rejection_not_provider_coverage", "code": reason, "runner_callbacks": 0, "qualified": False,
            })
        return data

    def index(self, run: Path, data: dict[str, bytes]) -> None:
        entries = []
        for relative, raw in sorted(data.items()):
            self.put(relative, raw, run)
            entries.append({"path": relative, "size": len(raw), "sha256": digest(raw)})
        self.put("evidence-index.json", json_bytes({
            "kind": "hashes_of_completed_private_evidence_writes_not_custody_or_execution_authority", "entries": entries,
            "excludes_this_index_and_any_later_failure_marker": True, "qualifies_surrounding_build_or_release": False,
        }), run)

    def native_fixture(self, rule: str = RULE) -> tuple[Path, dict, Path, dict[str, bytes]]:
        self.next_fixture += 1
        root = self.root / ("native-unit-only-" + str(self.next_fixture))
        layout = ci.paths(root, rule)
        run = layout["evidence"] / "compose-state-effects-unit-only"
        run.mkdir(parents=True)
        enrollment = self.enrollment(root, rule)
        raw_source = b"/* synthetic unit-only SOURCE, never compiled */\n"
        raw_binary = b"synthetic inert unit-only supervisor bytes, never executable"
        self.put("modules/provider-runtime/native/owned_process_supervisor.c", raw_source, root)
        generated = root / ci.SUPERVISOR_PREFIX
        self.put("manifest", (ci.SUPERVISOR_PROTOCOL + "\n" + ci.SUPERVISOR_TARGET + "\n" + digest(raw_source) + "\n" + digest(raw_binary) + "\n").encode("ascii"), generated)
        self.put(ci.SUPERVISOR_TARGET + "/supervisor", raw_binary, generated)
        selected_supervisor = {"protocol_version": ci.SUPERVISOR_PROTOCOL, "target": ci.SUPERVISOR_TARGET,
                               "source_digest": digest(raw_source), "binary_digest": digest(raw_binary),
                               "origin": "actual_runner_outcome_not_an_inspector_or_synthetic_receipt", "native_qualification": False}
        slug, count, mode = ci.RULES[rule]
        cases, outcomes = [], []
        data = self.guards(rule)
        data["owner/independently-selected-compilations.json"] = json_bytes(enrollment["descriptor"])
        for number in range(count):
            case_id = "unit-only-" + str(number)
            sources = {"src/main/kotlin/Subject.kt": f"fun inertUnit{number}() = Unit\n"}
            findings = [{"unit_only_not_a_location": True}] if number == 0 else []
            case = {"id": case_id, "sources": sources, "compilation_files": ["src/main/kotlin/Subject.kt"],
                    "expected_raw_findings": findings, "expected_admitted_findings": findings,
                    "expected_withheld_raw_findings": [], "semantic_interpretation": "synthetic, not a detector oracle"}
            cases.append(case)
            prefix = "cases/" + case_id + "/"
            materialized = run / "scratch" / case_id / "sources"
            output = run / "outputs" / case_id / "provider-unit-only"
            output.mkdir(parents=True)
            snapshot_files = []
            for relative, source in sources.items():
                raw = source.encode("utf-8")
                self.put(relative, raw, materialized)
                data[prefix + "sources/" + relative] = raw
                snapshot_files.append({"path": relative, "digest": digest(raw), "size": len(raw)})
            report = json_bytes({"unit_only": True, "not_real_sarif": True, "case": case_id})
            config = b"# unit-only config; never passed to Detekt\n"
            binding_digest = digest(("unit-only-binding-" + case_id).encode("ascii"))
            arguments = ci.light_arguments(enrollment, case, binding_digest, config)
            execution = {"approved": True, "executable": enrollment["jdk_home"] + "/bin/java",
                         "executableDigest": enrollment["jdk_launcher_digest"], "timeoutMillis": 30_000,
                         "maxOutputBytes": 1_048_576, "arguments": arguments, "environment": {}}
            context = {"scope": SCOPE, "compilerVersion": "2.4.21", "compilerArguments": [],
                       "classpath": [{key: row[key] for key in ("coordinate", "digest", "path")}
                                     for row in enrollment["descriptor"]["reviewed"]["dependencies"]],
                       "compilationFiles": case["compilation_files"]}
            input_value = {"requiredRuleIds": [rule], "context": context, "execution": execution,
                           "snapshot": {"files": snapshot_files}, "materializedRoot": str(materialized), "outputRoot": str(output.parent)}
            process = {"command": [execution["executable"]] + [arg.replace("{snapshot}", str(materialized)).replace("{output}", str(output)) for arg in arguments],
                       "output_directory": str(output), "status": "executed", "exit_code": 0, "executed": True,
                       "execution_confirmed": True, "successful_execution": True, "cancelled": False, "timed_out": False,
                       "truncated": False, "reasons": [], "supervisor_identity": selected_supervisor}
            data[prefix + "source-oracle.json"] = json_bytes(case)
            data[prefix + "input.json"] = json_bytes(input_value)
            data[prefix + "input-before-profile.json"] = json_bytes(dict(input_value, execution=None))
            data[prefix + "process.json"] = json_bytes(process)
            data[prefix + "library-binding.json"] = json_bytes({
                "source": "live_fixture_owner_preparation", "owner_descriptor_digest": enrollment["owner_descriptor_digest"],
                "execution_granted_by_library_permit": False, "binding_digest": binding_digest,
            })
            data[prefix + "engine-artifacts.json"] = json_bytes({
                row["coordinate"]: {key: row[key] for key in ("path", "digest")} for row in enrollment["engine"]
            })
            data[prefix + "actual-report.sarif"] = report
            data[prefix + "actual-config.yml"] = config
            data[prefix + "planned-config.yml"] = config
            self.put("detekt.sarif", report, output)
            self.put("detekt.yml", config, output)
            state = "unavailable" if mode == "partial_reviewed_message_facets" else "findings" if findings else "unknown"
            data[prefix + "provider-result.json"] = json_bytes({
                "providerId": "detekt", "providerVersion": "2.0.0-alpha.6", "status": "partial", "findings": findings,
                "coverage": [{"ruleId": rule, "state": state, "context": SCOPE, "provenance": "executed", "certificateId": None,
                              "tested": False, "qualified": False, "implemented": mode != "partial_reviewed_message_facets",
                              "reason": "detekt_compose_partial_api_surface" if mode == "partial_reviewed_message_facets" else None}],
            })
            outcomes.append({"case_id": case_id, "observation_kind": "actual_detekt_light_sarif", "native_invocations": 1,
                             "report_digest": digest(report), "configuration_digest": digest(config), "binding_digest": binding_digest,
                             "expected_raw_locations": len(findings), "actual_raw_locations": len(findings), "advisory_locations": len(findings),
                             "withheld_family_locations": 0, "whole_rule_state": state, "tested": False, "qualified": False,
                             "semantic_interpretation": case["semantic_interpretation"]})
        self.put(ci.CORPUS + "/fixtures/" + slug + ".json", json_bytes({"rule_id": rule, "cases": cases}), root)
        data["started.json"] = json_bytes({"selected_rule_id": rule, "selected_mode": mode, "source_case_count": count,
                                          "expected_native_calls": count, "build_outcome": None, "qualified": False})
        data["assertions-completed.json"] = json_bytes({
            "selected_rule_id": rule, "selected_mode": mode, "native_calls": count,
            "state": "selected_assertions_completed_build_outcome_unknown", "cases": outcomes,
            "build_outcome": None, "whole_batch_observed": False, "all_eight_rules_delivered": False,
            "parser_compatibility_qualified": False, "release_qualified": False, "publication_committed": False,
            "authored_batch_rule_count": 8, "authored_batch_case_count": 61, "aggregate_elapsed_millis": 1,
            "source_oracles_not_run_for_other_rules": sorted(ci.ALL_RULES - {rule}),
        })
        self.index(run, data)
        return root, enrollment, run, data


class FilesystemCapabilityControls(_IsolatedControls):
    allow_fixture_io = False

    def capability_model(self):
        # Never mutate the process-wide os.name or invent usable host flags.
        # Synthetic flags reach only the predicate and mocked descriptor calls.
        model = SimpleNamespace(**vars(os))
        model.name = "posix"
        model.O_NOFOLLOW, model.O_DIRECTORY, model.O_NONBLOCK = 1, 2, 4
        model.open = mock.Mock(side_effect=AssertionError("unexpected descriptor open"))
        model.mkdir = mock.Mock(side_effect=AssertionError("unexpected directory creation"))
        model.umask = mock.Mock(side_effect=AssertionError("unexpected process mutation"))
        model.supports_dir_fd = {model.open, model.mkdir}
        return model

    def test_each_required_capability_is_checked_before_native_flags_or_io(self):
        target = self.put("capability-input.json", b"{}")
        supported = self.capability_model()
        with mock.patch.object(ci, "os", supported):
            self.assertTrue(ci.nofollow_io_available())
            ci.require_nofollow_io()
        supported.open.assert_not_called()
        supported.mkdir.assert_not_called()
        variants = ("windows", "O_NOFOLLOW", "O_DIRECTORY", "O_NONBLOCK",
                    "zero-nofollow", "zero-directory", "zero-nonblock", "boolean-flag",
                    "open-dirfd", "mkdir-dirfd", "missing-dirfd-set")
        for variant in variants:
            model = self.capability_model()
            if variant == "windows":
                model.name = "nt"
            elif variant.startswith("O_"):
                delattr(model, variant)
            elif variant.startswith("zero-"):
                setattr(model, {"zero-nofollow": "O_NOFOLLOW", "zero-directory": "O_DIRECTORY",
                                "zero-nonblock": "O_NONBLOCK"}[variant], 0)
            elif variant == "boolean-flag":
                model.O_NOFOLLOW = True
            elif variant == "missing-dirfd-set":
                del model.supports_dir_fd
            else:
                model.supports_dir_fd.remove(model.open if variant == "open-dirfd" else model.mkdir)
            with self.subTest(capability=variant), mock.patch.object(ci, "os", model):
                self.assertFalse(ci.nofollow_io_available())
                for function, arguments in ((ci.read_regular, (target, 2)), (ci.file_identity, (target,)),
                                            (ci.open_leaf, (target, os.O_RDONLY)),
                                            (ci.make_directory, (self.root / "not-created",)),
                                            (ci.write_new, (self.root / "not-written.json", b"{}"))):
                    self.problem("compose_ci_nofollow_io_unavailable", function, *arguments)
            model.open.assert_not_called()
            model.mkdir.assert_not_called()
            model.umask.assert_not_called()
        self.assertEqual(b"{}", target.read_bytes())
        self.assertFalse((self.root / "not-created").exists())
        self.assertFalse((self.root / "not-written.json").exists())
        self.no_external_calls()

    def test_capable_descriptor_model_keeps_all_required_native_flags(self):
        # Descriptor numbers are inert model values; no host descriptor is opened.
        target = self.put("descriptor-model-input.json", b"{}")
        model = self.capability_model()
        descriptors = list(range(1000, 1000 + len(target.parts)))
        model.open.side_effect = descriptors
        model.close = mock.Mock()
        directory_flags = os.O_RDONLY | model.O_DIRECTORY | model.O_NOFOLLOW
        expected = [mock.call("/", directory_flags)]
        current = descriptors[0]
        closed = []
        for index, component in enumerate(target.parts[1:-1], 1):
            expected.append(mock.call(component, directory_flags, dir_fd=current))
            closed.append(mock.call(current))
            current = descriptors[index]
        expected.append(mock.call(target.name, os.O_RDONLY | model.O_NOFOLLOW | model.O_NONBLOCK,
                                  0o600, dir_fd=current))
        closed.append(mock.call(current))
        with mock.patch.object(ci, "os", model):
            result = ci.open_leaf(target, os.O_RDONLY, nonblocking=True)
        self.assertEqual(descriptors[len(expected) - 1], result)
        self.assertEqual(expected, model.open.call_args_list)
        self.assertEqual(closed, model.close.call_args_list)
        model.mkdir.assert_not_called()
        model.umask.assert_not_called()
        self.no_external_calls()

    def test_unsupported_cli_phases_remain_unavailable_without_launch_or_evidence_fallback(self):
        for phase in ("gate", "wrapper", "provision", "execute", "collect"):
            model = self.capability_model()
            model.name = "nt"
            del model.O_NONBLOCK
            output = io.StringIO()
            functions = {name: mock.Mock(side_effect=AssertionError("phase must not be dispatched"))
                         for name in ("gate", "wrapper", "provision", "execute", "collect")}
            with self.subTest(phase=phase), mock.patch.object(ci, "os", model), \
                    mock.patch.multiple(ci, **functions), redirect_stdout(output):
                code = ci.main(["--phase", phase, "--rule", RULE, "--owner-lock-sha256", self.lock_digest])
            self.assertEqual(1, code)
            self.assertIn("unavailable (compose_ci_nofollow_io_unavailable)", output.getvalue())
            self.assertNotIn(str(self.root), output.getvalue())
            self.assertFalse((self.root / "build").exists())
            for function in functions.values():
                function.assert_not_called()
            model.open.assert_not_called()
            model.mkdir.assert_not_called()
            model.umask.assert_not_called()
        self.no_external_calls()

    def test_real_host_reader_or_explicit_unsupported_result_is_not_silently_skipped(self):
        target = self.put("real-host-input.json", b"{}")
        self.assertIsNone(self.fixture_io)
        if ci.nofollow_io_available():
            self.assertEqual(b"{}", ci.read_regular(target, 2))
            self.assertEqual({"size": 2, "digest": digest(b"{}")}, ci.file_identity(target))
            self.problem("compose_ci_file_bound", ci.read_regular, target, 1)
        else:
            for function, arguments in ((ci.read_regular, (target, 2)), (ci.file_identity, (target,)),
                                        (ci.write_new, (self.root / "unsupported-output.json", b"{}"))):
                self.problem("compose_ci_nofollow_io_unavailable", function, *arguments)
            self.assertFalse((self.root / "unsupported-output.json").exists())
        self.assertEqual(b"{}", target.read_bytes())
        self.no_external_calls()

    def test_portable_fixture_model_keeps_real_byte_bounds_hashes_and_exclusive_writes(self):
        # Exercise the model on supported hosts too, without claiming OS safety.
        self.install_fixture_io()
        target = self.root / "fixture-model/value.json"
        ci.write_new(target, b"{}")
        self.assertEqual(b"{}", ci.read_regular(target, 2))
        self.assertEqual({"size": 2, "digest": digest(b"{}")}, ci.file_identity(target))
        self.problem("compose_ci_file_bound", ci.read_regular, target, 1)
        with self.assertRaises(FileExistsError):
            ci.write_new(target, b"overwrite forbidden")
        with self.assertRaises(AssertionError):
            self.fixture_io.open_leaf(target, os.O_RDONLY)
        with self.assertRaises(AssertionError):
            self.fixture_io.make_directory(self.root.parent / "outside-unit-root")
        self.assertEqual(b"{}", target.read_bytes())
        self.no_external_calls()


class OwnerInputControls(_IsolatedControls):
    def test_missing_lock_is_nonzero_unavailable_before_any_external_call(self):
        output = io.StringIO()
        with redirect_stdout(output):
            code = ci.main(["--phase", "gate", "--rule", RULE, "--owner-lock-sha256", self.lock_digest])
        self.assertEqual(1, code)
        summary = json.loads(ci.paths(self.root, RULE)["public"].joinpath("gate.json").read_bytes())
        self.assertEqual(["compose_ci_owner_lock_unavailable"], summary["errors"])
        self.assertIs(False, summary["provider_controls_executed"])
        self.assertNotIn("passed", output.getvalue())
        self.no_external_calls()

    def test_duplicate_keys_nonfinite_and_nonobject_json_rejected(self):
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":-Infinity}',
                    b'{"x":1e309}', b'{"x":-1e309}', b"[]"):
            with self.subTest(raw=raw), self.assertRaises(ci.Problem):
                ci.strict_json(raw)
        self.assertEqual({"valid": [1, True, None]}, ci.strict_json(b'{"valid":[1,true,null]}'))
        self.assertEqual({"elapsed_seconds": 0.125, "finite_exponent": 1000.0},
                         ci.strict_json(b'{"elapsed_seconds":0.125,"finite_exponent":1e3}'))
        self.problem("compose_ci_json_bound", ci.strict_json, b"{}", 1)

    def test_strict_integer_rejects_booleans_and_bounds(self):
        for value in (False, True, 1.0, "1", None, -1, 9):
            self.assertFalse(ci.integer(value, 0, 8))
        self.assertTrue(ci.integer(0, 0, 8))
        self.assertTrue(ci.integer(8, 0, 8))

    def test_relative_paths_cannot_escape_or_alias(self):
        for value in ("", ".", "../x", "a/../x", "/x", "a//x", "a/./x", "a\\x", "a\x00x", "a\nx", "x:drive", True):
            with self.subTest(value=value), self.assertRaises(ci.Problem):
                ci.relative(value)
        self.assertEqual("unit-data/x.json", str(ci.relative("unit-data/x.json")))

    def test_relative_paths_require_nonempty_canonical_components(self):
        for value in (".", "./", "./.", "././", ".//.", "x/", "x/.", "x//y", "x/./y"):
            with self.subTest(value=value):
                self.problem("compose_ci_path_invalid", ci.relative, value)
        for value in ("x.json", ".owner-lock", "unit-data/x+1.json", "unit-data/version..json"):
            with self.subTest(value=value):
                path = ci.relative(value)
                self.assertTrue(path.parts)
                self.assertEqual(value, str(path))
        self.no_external_calls()

    def test_source_pins_and_clean_identity_are_required(self):
        raw = b"unit-only SOURCE\n"
        self.put("unit.py", raw)
        pin = {"unit.py": {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}}
        identity = self.external["source_identity"]
        identity.side_effect = None
        identity.return_value = self.source
        with mock.patch.object(ci, "SOURCE_PINS", pin):
            self.assertEqual(self.source, ci.check_source(self.root))
            for altered in (dict(self.source, dirty=True), dict(self.source, unsupported_directories=False)):
                identity.return_value = altered
                self.problem("compose_ci_reviewed_clean_source_required", ci.check_source, self.root)
            identity.return_value = self.source
            self.put("unit.py", b"changed SOURCE!\n")
            with self.assertRaises(ci.Problem):
                ci.check_source(self.root)

    def test_read_write_identity_rejects_symlinks_and_overwrite(self):
        target = self.put("private/real.json", b"{}")
        link = self.root / "private/link.json"
        link.symlink_to(target)
        self.problem("compose_ci_symlink_rejected", ci.read_regular, link, 2)
        alias = self.root / "alias"
        alias.symlink_to(target.parent, target_is_directory=True)
        self.problem("compose_ci_symlink_rejected", ci.read_regular, alias / "real.json", 2)
        with self.assertRaises(FileExistsError):
            ci.write_new(target, b"secret replacement")
        self.assertEqual(b"{}", target.read_bytes())
        self.assertEqual({"size": 2, "digest": digest(b"{}")}, ci.file_identity(target))
        self.problem("compose_ci_file_bound", ci.read_regular, target, 1)

    def test_supplied_synthetic_lock_shape_is_not_resolution(self):
        lock = self.lock()
        self.assertEqual(lock, ci.validate_lock(lock, self.root, IMMUTABLE_RULE))
        self.no_external_calls()
        # A structurally accepted fixture remains only input data; this helper
        # never claims to infer dependency closure or authorize a provider run.
        self.assertFalse(ci.public_base("gate", RULE)["complete_runtime_lock_delivered_by_this_proposal"])
        self.assertFalse(ci.public_base("gate", RULE)["tuple_qualified"])

    def test_owner_lock_requires_complete_vectors_and_retained_provenance(self):
        mutations = (
            lambda value: value.pop("unsupported_runtime"),
            lambda value: value.update(resolution_provenance=[]),
            lambda value: value["reviewed"].update(dependencies=value["reviewed"]["dependencies"][:3]),
            lambda value: value["reviewed"].update(compiler_version="2.4.10"),
            lambda value: value["reviewed"]["scope"].update(source_set="test"),
            lambda value: value["resolution_provenance"][0].update(size=True),
            lambda value: value["resolution_provenance"][0].update(digest=digest(b"wrong provenance")),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                lock = self.lock()
                mutate(lock)
                with self.assertRaises(ci.Problem):
                    ci.validate_lock(lock, self.root, IMMUTABLE_RULE)
        self.no_external_calls()

    def test_published_api_pins_cannot_be_relabelled_or_approximated(self):
        for index in (0, 1, 2):
            for field, value in (("digest", digest(b"not the published binary")), ("size", True), ("size", 1)):
                with self.subTest(index=index, field=field, value=value):
                    lock = self.lock()
                    lock["reviewed"]["dependencies"][index][field] = value
                    with self.assertRaises(ci.Problem):
                        ci.validate_lock(lock, self.root, IMMUTABLE_RULE)

    def test_other_runtime_requires_both_other_coordinate_and_other_bytes(self):
        for same_coordinate, same_bytes in ((True, False), (False, True), (True, True)):
            lock = self.lock()
            current = lock["reviewed"]["dependencies"][0]
            other = lock["unsupported_runtime"]["dependencies"][0]
            if same_coordinate:
                other = self.row(current["coordinate"], raw=b"independent inert other bytes")
                lock["unsupported_runtime"]["dependencies"][0] = other
            if same_bytes:
                other["digest"] = current["digest"]
                other["size"] = current["size"]
            with self.subTest(same_coordinate=same_coordinate, same_bytes=same_bytes), self.assertRaises(ci.Problem):
                ci.validate_lock(lock, self.root, RULE)

    def test_duplicate_ga_runtime_alias_and_cross_vector_conflict_rejected(self):
        for kind in ("duplicate-coordinate", "duplicate-ga", "runtime-alias", "cross-vector"):
            lock = self.lock()
            if kind == "duplicate-coordinate":
                lock["reviewed"]["dependencies"].append(copy.deepcopy(lock["reviewed"]["dependencies"][-1]))
            elif kind == "duplicate-ga":
                lock["reviewed"]["dependencies"].append(self.row("org.jetbrains.kotlin:kotlin-stdlib:other-unit-only"))
            elif kind == "runtime-alias":
                lock["reviewed"]["dependencies"].append(self.row("org.jetbrains.compose.runtime:runtime-desktop:unit-only"))
            else:
                lock["unsupported_runtime"]["dependencies"][-1]["digest"] = digest(b"conflicting unit pin")
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.validate_lock(lock, self.root, RULE)

    def test_exact_dispatch_source_workflow_and_first_attempt_are_required(self):
        self.assertEqual("1" * 40, ci.dispatch_context(self.source, self.env)["GITHUB_SHA"])
        changed = {"KTC_COMPOSE_CI_CONSENT": "not-authorized", "KTC_COMPOSE_EXPECTED_COMMIT": "HEAD",
                   "GITHUB_SHA": "2" * 40, "GITHUB_WORKFLOW_SHA": "2" * 40, "GITHUB_EVENT_NAME": "pull_request",
                   "RUNNER_ENVIRONMENT": "self-hosted", "RUNNER_OS": "macOS", "RUNNER_ARCH": "ARM64",
                   "GITHUB_RUN_ATTEMPT": "2", "GITHUB_RUN_ID": "0", "GITHUB_JOB": "other",
                   "GITHUB_WORKFLOW_REF": "unit-only/repository/.github/workflows/other.yml@refs/heads/reviewed"}
        for key, value in changed.items():
            with self.subTest(key=key), self.assertRaises(ci.Problem):
                ci.dispatch_context(self.source, dict(self.env, **{key: value}))

    def test_non_native_rules_are_not_covert_scope_exclusions_or_jobs(self):
        for rule in ci.ALL_RULES - set(ci.RULES):
            self.problem("compose_ci_rule_not_supported_for_native_matrix", ci.paths, self.root, rule)
        self.assertEqual(6, len(ci.RULES))
        self.assertEqual(46, sum(value[1] for value in ci.RULES.values()))
        self.assertTrue(ci.public_base("collect", RULE)["all43_compose_release_scope_retained"])
        self.assertIsNone(ci.public_base("collect", RULE)["provider_controls_executed"])


class AcquisitionControls(_IsolatedControls):
    def test_exact_coordinate_derived_official_urls_only(self):
        row = self.row("unit.only:artifact:1.0")
        self.assertEqual("artifact-1.0.jar", ci.artifact_url(row["coordinate"], row["url"]))
        variants = [row["url"].replace("https:", "http:"), row["url"].replace("repo.maven.apache.org", "evil.invalid"),
                    row["url"].replace("repo.maven.apache.org", "name:secret@repo.maven.apache.org"),
                    row["url"] + "?private=token", row["url"] + "#fragment", row["url"].replace("/unit/only/", "/unit/%2fonly/"),
                    row["url"].replace("-1.0.jar", "-1.0-sources.jar"), row["url"] + "/extra",
                    " " + row["url"], row["url"] + "\n", row["url"].replace("https://", "https://\t", 1)]
        for url in variants:
            with self.subTest(url=url), self.assertRaises(ci.Problem):
                ci.artifact_url(row["coordinate"], url)
        for coordinate in ("unit.only:artifact:1.0:sources", "unit..only:artifact:1.0", "unit.only:artifact-sources:1.0", ".:artifact:1.0"):
            with self.subTest(coordinate=coordinate), self.assertRaises(ci.Problem):
                ci.artifact_url(coordinate, row["url"])

    def test_redirect_handler_rejects_without_following(self):
        self.problem("compose_ci_artifact_redirect_rejected", ci.NoRedirect().redirect_request,
                     None, None, 302, "", {}, "https://evil.invalid")
        self.no_external_calls()

    def test_acquisition_consent_freshness_and_deadline_precede_network(self):
        row = self.row("unit.only:artifact:1.0")
        destination = self.root / "artifact.jar"
        self.problem("compose_ci_explicit_download_consent_required", ci.acquire, row, destination, time.monotonic() + 10, False)
        self.problem("compose_ci_provision_deadline", ci.acquire, row, destination, time.monotonic() - 1, True)
        destination.write_bytes(b"retain existing inert bytes")
        self.problem("compose_ci_fresh_artifact_destination_required", ci.acquire, row, destination, time.monotonic() + 10, True)
        self.assertEqual(b"retain existing inert bytes", destination.read_bytes())
        self.no_external_calls()

    def test_inert_download_is_bounded_hashed_and_read_back_with_no_retry(self):
        raw = b"inert unit-only data"
        row = self.row("unit.only:artifact:1.0", raw=raw)
        response = FakeResponse(raw, row["url"], headers={"Content-Length": str(len(raw))})
        opener = FakeOpener(response)
        destination = self.root / "private/artifact.jar"
        actual = ci.acquire(row, destination, time.monotonic() + 10, True, opener)
        self.assertEqual(dict(coordinate=row["coordinate"], path=str(destination), size=len(raw), digest=digest(raw)), actual)
        self.assertEqual(raw, destination.read_bytes())
        self.assertEqual(1, len(opener.calls))
        self.assertLessEqual(opener.calls[0][1], 10)
        self.no_external_calls()

    def test_wrong_pin_short_oversized_and_encoded_responses_never_pass(self):
        for kind in ("digest", "short", "large", "encoding", "redirect", "length", "status"):
            row = self.row("unit.only:artifact:1.0", raw=b"123")
            raw, url, status, headers = b"123", row["url"], 200, {}
            if kind == "digest":
                row["digest"] = digest(b"wrong")
            elif kind == "short":
                raw = b"12"
            elif kind == "large":
                raw = b"1234"
            elif kind == "encoding":
                headers = {"Content-Encoding": "gzip"}
            elif kind == "redirect":
                url = "https://evil.invalid"
            elif kind == "length":
                headers = {"Content-Length": "4"}
            else:
                status = 206
            destination = self.root / (kind + ".jar")
            opener = FakeOpener(FakeResponse(raw, url, status, headers))
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.acquire(row, destination, time.monotonic() + 10, True, opener)
            self.assertEqual(1, len(opener.calls))
            if kind in {"digest", "short", "large"}:
                self.assertTrue(destination.is_file(), "failed inert partial is retained, not repaired/deleted")
        self.no_external_calls()


class XmlAndExecutionControls(_IsolatedControls):
    def test_safe_original_xml_is_byte_identified_not_regenerated(self):
        raw = safe_xml()
        result = ci.original_xml(raw)
        self.assertEqual(digest(raw), result["original_xml_digest"])
        self.assertEqual(ci.TEST_CLASS, result["class"])
        self.assertEqual(ci.TEST_NAME, result["name"])
        self.assertEqual("passed", result["state"])

    def test_original_xml_rejects_comments_pi_dtd_and_non_ascii_encodings(self):
        raw = safe_xml()
        for altered in (raw.replace(b"<properties/>", b"<!-- private unit marker --><properties/>"),
                        raw + b"<?private unit-marker?>", b'<!DOCTYPE testsuite [<!ENTITY x "private">]>' + raw,
                        raw.decode("ascii").encode("utf-16"), raw.replace(b"<properties/>", b"<properties>\xc2\xa0</properties>")):
            with self.subTest(altered=altered[:40]), self.assertRaises((ci.Problem, ValueError)):
                ci.original_xml(altered)

    def test_original_xml_rejects_namespace_declarations_even_when_unused(self):
        # Unused xmlns is omitted from ElementTree's ordinary attrib mapping.
        # Original bytes must reject it on every element, not merely root tags.
        declarations = (
            b'xmlns:private="unit-only-private-metadata"',
            b'xmlns=""',
            b'xmlns:xml="http://www.w3.org/XML/1998/namespace"',
        )
        for element in (b"<testsuite ", b"<testcase "):
            for declaration in declarations:
                raw = safe_xml().replace(element, element + declaration + b" ", 1)
                with self.subTest(element=element, declaration=declaration):
                    self.problem("compose_ci_original_xml_unsafe", ci.original_xml, raw)
        self.no_external_calls()

    def test_original_xml_rejects_output_properties_failures_skips_and_unknown_elements(self):
        for child, suite in (("<failure>private unit diagnostic</failure>", ""), ("<error/>", ""), ("<skipped/>", ""),
                             ("", "<properties><property name=\"secret\" value=\"unit-marker\"/></properties>"),
                             ("", "<private>unit-marker</private>"), ("", "<system-out>unit-marker</system-out>")):
            with self.subTest(child=child, suite=suite), self.assertRaises(ci.Problem):
                ci.original_xml(safe_xml(child=child, suite=suite))

    def test_original_xml_requires_exact_one_class_method_and_counts(self):
        raw = safe_xml()
        variants = [raw.replace(b'tests="1"', b'tests="0"'), raw.replace(b'skipped="0"', b'skipped="1"'),
                    raw.replace(ci.TEST_CLASS.encode("ascii"), b"wrong.Class"), raw.replace(ci.TEST_NAME.encode("ascii"), b"wrong()"),
                    raw.replace(b'<properties/>', f'<testcase name="{ci.TEST_NAME}" classname="{ci.TEST_CLASS}"/>'.encode("ascii")),
                    raw.replace(b'hostname="unit-only-runner"', b'hostname="unit / private marker"')]
        for altered in variants:
            with self.subTest(altered=altered[:60]), self.assertRaises(ci.Problem):
                ci.original_xml(altered)

    def test_build_properties_select_only_exact_task_and_native_budgets(self):
        enrollment = self.enrollment()
        arguments = ci.build_arguments(RULE, enrollment)
        self.assertEqual(["--rerun-tasks", "--no-build-cache", ci.SUPERVISOR_TASK, ci.TASK], arguments[:4])
        properties = [value for value in arguments if value.startswith("-PdetektCompose")]
        self.assertEqual(10, len(properties))
        self.assertIn("-PdetektComposeMaxInvocations=8", properties)
        self.assertIn("-PdetektComposeAggregateMillis=300000", properties)
        self.assertIn("-PdetektComposeChildMillis=30000", properties)
        self.assertIn("-PdetektComposeConsent=compose-state-effects-v1-one-rule", properties)
        self.assertNotIn("run_gradle.py", repr(arguments))

    def test_unknown_outer_launch_is_not_retried_or_reported_as_no_execution(self):
        enrollment = self.enrollment()
        layout = ci.paths(self.root, RULE)
        layout["evidence"].mkdir(parents=True)
        self.patch_enrollment(enrollment)
        self.external["gradle_result"].side_effect = RuntimeError("private unit-only diagnostic must not print")
        output = io.StringIO()
        with redirect_stdout(output):
            code = ci.main(["--phase", "execute", "--rule", RULE, "--owner-lock-sha256", self.lock_digest])
        self.assertEqual(1, code)
        self.assertNotIn("private unit-only diagnostic", output.getvalue())
        summary = json.loads((layout["public"] / "execution.json").read_bytes())
        self.assertIsNone(summary["provider_controls_executed"])
        with self.assertRaises(FileExistsError):
            ci.execute(self.root, RULE, self.lock_digest)
        self.assertEqual(1, self.external["gradle_result"].call_count)
        self.assertTrue((layout["private"] / "execute-attempt.json").is_file())

    def test_execute_uses_existing_gradle_utility_and_never_echoes_raw_output(self):
        enrollment = self.enrollment()
        layout = ci.paths(self.root, RULE)
        layout["evidence"].mkdir(parents=True)
        self.patch_enrollment(enrollment)
        self.external["gradle_result"].side_effect = None
        self.external["gradle_result"].return_value = ({"state": "passed", "tasks": {ci.TASK: "EXECUTED", ci.SUPERVISOR_TASK: "EXECUTED"}}, "private raw unit-only log")
        output = io.StringIO()
        with redirect_stdout(output):
            result = ci.execute(self.root, RULE, self.lock_digest)
        self.assertEqual("passed", result["state"])
        self.assertEqual("", output.getvalue())
        call = self.external["gradle_result"].call_args
        self.assertEqual(1200, call.kwargs["timeout"])
        self.assertEqual((ci.SUPERVISOR_TASK, ci.TASK), call.kwargs["require_tasks"])
        self.assertEqual(layout["private"] / "gradle", call.kwargs["log_base"])
        self.assertEqual(1, self.external["gradle_result"].call_count)

    def test_collect_copies_only_identical_safe_original_xml_after_all_joins(self):
        layout, _, _, _ = self.collect_fixture()
        raw = layout["xml"].read_bytes()
        result = ci.collect(self.root, RULE, self.lock_digest)
        self.assertEqual("passed", result["state"])
        self.assertEqual(raw, (layout["public"] / "junit-original.xml").read_bytes())
        self.assertEqual(raw, (layout["private"] / "original-junit.xml").read_bytes())
        self.assertIs(False, result["release_qualified"])
        self.assertIs(False, result["whole_jdk_distribution_hash_locked"])
        self.assertIs(False, result["custody_or_cleanup_proven"])
        self.no_external_calls()

    def test_skipped_cached_failed_wrong_command_and_boolean_return_are_not_executed_tasks(self):
        # Each subcase gets an isolated fixture; no helper retry or overwrite.
        for field in ("SKIPPED", "FROM-CACHE", "UP-TO-DATE", "FAILED", "wrong-command", "boolean-return"):
            with self.subTest(field=field), tempfile.TemporaryDirectory(dir=self.root) as temporary:
                original_root = self.root
                self.root = Path(temporary)
                try:
                    layout, build, _, _ = self.collect_fixture()
                    if field == "wrong-command":
                        build["command"].append(":unselected:task")
                    elif field == "boolean-return":
                        build["return_code"] = False
                    else:
                        build["tasks"][ci.TASK] = field
                    self.put(layout["build"], json_bytes(build))
                    self.problem("compose_ci_actual_executed_task_evidence_required", ci.collect, self.root, RULE, self.lock_digest)
                    self.assertFalse((layout["public"] / "junit-original.xml").exists())
                    self.assertTrue((layout["private"] / "original-junit.xml").exists())
                finally:
                    self.root = original_root

    def test_stale_xml_and_duplicate_xml_file_are_not_success(self):
        for kind in ("stale", "duplicate"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.root) as temporary:
                original_root = self.root
                self.root = Path(temporary)
                try:
                    layout, _, attempt, _ = self.collect_fixture()
                    if kind == "stale":
                        earlier = attempt["started_time_ns"] - 1_000_000
                        os.utime(layout["xml"], ns=(earlier, earlier))
                        expected = "compose_ci_stale_xml"
                    else:
                        self.put(layout["xml"].parent / "TEST-Other.xml", safe_xml())
                        expected = "compose_ci_exact_junit_file_inventory_required"
                    self.problem(expected, ci.collect, self.root, RULE, self.lock_digest)
                    self.assertFalse((layout["public"] / "junit-original.xml").exists())
                finally:
                    self.root = original_root

    def test_surrounding_failure_and_extra_native_attempt_are_not_success(self):
        layout, _, _, run = self.collect_fixture()
        with mock.patch.dict(os.environ, {"KT_CI_JOB_STATUS": "failure"}):
            self.problem("compose_ci_surrounding_job_not_success", ci.collect, self.root, RULE, self.lock_digest)
        self.assertFalse((layout["public"] / "junit-original.xml").exists())
        # No automatic retry is performed by the contract. This second scenario
        # uses another isolated directory, not the first collector's evidence.
        with tempfile.TemporaryDirectory(dir=self.root) as temporary:
            self.root = Path(temporary)
            layout, _, _, run = self.collect_fixture()
            run.with_name("compose-state-effects-extra").mkdir()
            self.problem("compose_ci_exact_fresh_native_run_required", ci.collect, self.root, RULE, self.lock_digest)
            self.assertFalse((layout["public"] / "junit-original.xml").exists())

    def test_private_original_xml_stays_private_when_whitelist_rejects(self):
        layout, _, _, _ = self.collect_fixture()
        raw = safe_xml(child="<failure>unit-only private diagnostic</failure>")
        self.put(layout["xml"], raw)
        with self.assertRaises(ci.Problem):
            ci.collect(self.root, RULE, self.lock_digest)
        self.assertEqual(raw, (layout["private"] / "original-junit.xml").read_bytes())
        self.assertFalse((layout["public"] / "junit-original.xml").exists())


class NativeEvidenceJoinControls(_IsolatedControls):
    def test_synthetic_join_success_remains_unqualified_not_a_real_run(self):
        root, enrollment, run, _ = self.native_fixture()
        result = ci.native_evidence(root, RULE, enrollment, run)
        self.assertEqual(8, result["native_calls"])
        self.assertEqual(13, result["guard_assertions_retained"])
        self.assertEqual(0, result["guard_native_callbacks"])
        self.assertFalse(result["whole_rule_qualified"])
        self.assertFalse(result["private_native_reports_durable_off_host_retention_proven"])
        self.assertFalse(result["guard_observations_are_native_detector_negatives"])
        self.no_external_calls()

    def test_partial_facets_stay_unavailable_and_full_clean_cases_stay_unknown(self):
        for rule in (RULE, PARTIAL_RULE, IMMUTABLE_RULE):
            root, enrollment, run, _ = self.native_fixture(rule)
            result = ci.native_evidence(root, rule, enrollment, run)
            expected = "unavailable" if rule == PARTIAL_RULE else "unknown"
            self.assertTrue(all(case["whole_rule_state"] == expected for case in result["cases"][1:]))
            self.assertEqual(14 if rule == IMMUTABLE_RULE else 13, result["guard_assertions_retained"])
            self.assertFalse(result["whole_rule_qualified"])

    def test_index_rejects_tampering_duplicate_paths_booleans_and_failure_marker(self):
        for kind in ("tamper", "duplicate", "boolean-size", "traversal", "failed"):
            root, _, run, data = self.native_fixture()
            if kind == "tamper":
                self.put("started.json", b"changed unit-only bytes", run)
            elif kind == "failed":
                self.put("failed-or-incomplete.json", b"{}", run)
            else:
                index_path = run / "evidence-index.json"
                index = json.loads(index_path.read_bytes())
                if kind == "duplicate":
                    index["entries"].append(index["entries"][0])
                elif kind == "boolean-size":
                    index["entries"][0]["size"] = True
                else:
                    index["entries"][0]["path"] = "../escape.json"
                index_path.write_bytes(json_bytes(index))
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.indexed_evidence(run)

    def test_indexed_symlink_is_not_followed(self):
        _, _, run, data = self.native_fixture()
        target = self.put("unit-only-external.json", data["started.json"])
        (run / "started.json").unlink()
        (run / "started.json").symlink_to(target)
        self.problem("compose_ci_symlink_rejected", ci.indexed_evidence, run)

    def test_guard_inventory_callbacks_reason_and_scope_are_joined(self):
        for kind in ("missing", "native-callback", "boolean-callback", "qualified", "wrong-reason"):
            data = self.guards(RULE)
            if kind == "missing":
                data.pop("controls/missing-runtime/observation.json")
            elif kind in {"native-callback", "boolean-callback"}:
                key = "controls/missing-runtime/observation.json"
                value = json.loads(data[key])
                value["runner_callbacks"] = 1 if kind == "native-callback" else False
                data[key] = json_bytes(value)
            else:
                key = "controls/missing-runtime/result.json"
                value = json.loads(data[key])
                value["coverage"][0]["qualified" if kind == "qualified" else "reason"] = True if kind == "qualified" else "wrong"
                data[key] = json_bytes(value)
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.guard_evidence(RULE, data)

    def test_completed_scope_and_exact_case_inventory_cannot_be_promoted(self):
        mutations = (
            lambda value: value.update(whole_batch_observed=True),
            lambda value: value.update(native_calls=True),
            lambda value: value.update(selected_rule_id=PARTIAL_RULE),
            lambda value: value.update(authored_batch_case_count=True),
            lambda value: value.update(source_oracles_not_run_for_other_rules=[]),
            lambda value: value["cases"].pop(),
            lambda value: value["cases"][0].update(qualified=True),
            lambda value: value["cases"][0].update(actual_raw_locations=True),
        )
        for mutate in mutations:
            root, enrollment, run, data = self.native_fixture()
            completed = json.loads(data["assertions-completed.json"])
            mutate(completed)
            data["assertions-completed.json"] = json_bytes(completed)
            self.index(run, data)
            with self.subTest(mutate=mutate), self.assertRaises(ci.Problem):
                ci.native_evidence(root, RULE, enrollment, run)

    def test_actual_process_confirmation_status_and_supervisor_identity_required(self):
        changes = {"status": "not_executed", "executed": False, "execution_confirmed": False,
                   "successful_execution": False, "cancelled": True, "timed_out": True, "truncated": True,
                   "reasons": ["cleanup_unknown"], "exit_code": False}
        for field, value in changes.items():
            root, enrollment, run, data = self.native_fixture()
            key = "cases/unit-only-0/process.json"
            process = json.loads(data[key])
            process[field] = value
            data[key] = json_bytes(process)
            self.index(run, data)
            with self.subTest(field=field):
                self.problem("compose_ci_actual_process_incomplete", ci.native_evidence, root, RULE, enrollment, run)
        root, enrollment, run, data = self.native_fixture()
        key = "cases/unit-only-0/process.json"
        process = json.loads(data[key])
        process["supervisor_identity"]["binary_digest"] = digest(b"forged unrelated unit-only identity")
        data[key] = json_bytes(process)
        self.index(run, data)
        self.problem("compose_ci_actual_supervisor_identity_mismatch", ci.native_evidence, root, RULE, enrollment, run)

    def test_light_profile_rejects_extra_classpath_injection_timeout_and_environment(self):
        for kind in ("extra-argument", "timeout", "output-boolean", "environment"):
            root, enrollment, run, data = self.native_fixture()
            key = "cases/unit-only-0/input.json"
            value = json.loads(data[key])
            if kind == "extra-argument":
                value["execution"]["arguments"] += ["--classpath", "/unit-only/injected"]
            elif kind == "timeout":
                value["execution"]["timeoutMillis"] = 0
            elif kind == "output-boolean":
                value["execution"]["maxOutputBytes"] = True
            else:
                value["execution"]["environment"] = {"JAVA_TOOL_OPTIONS": "unit-only-injection"}
            data[key] = json_bytes(value)
            self.index(run, data)
            with self.subTest(kind=kind):
                self.problem("compose_ci_actual_profile_mismatch", ci.native_evidence, root, RULE, enrollment, run)

    def test_owner_descriptor_and_ordered_compilation_context_are_exact(self):
        for kind in ("owner", "classpath", "scope", "members", "compiler"):
            root, enrollment, run, data = self.native_fixture()
            if kind == "owner":
                data["owner/independently-selected-compilations.json"] = json_bytes({"unit_only_changed_owner": True})
            else:
                key = "cases/unit-only-0/input.json"
                value = json.loads(data[key])
                field, selected = {"classpath": ("classpath", []), "scope": ("scope", dict(SCOPE, sourceSet="test")),
                                   "members": ("compilationFiles", []), "compiler": ("compilerVersion", "2.4.10")}[kind]
                value["context"][field] = selected
                data[key] = json_bytes(value)
            self.index(run, data)
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.native_evidence(root, RULE, enrollment, run)

    def test_output_paths_cannot_escape_and_original_native_bytes_must_match(self):
        for kind in ("escape", "dot-alias", "slash-alias", "report", "source", "engine"):
            root, enrollment, run, data = self.native_fixture()
            if kind in {"escape", "dot-alias", "slash-alias"}:
                key = "cases/unit-only-0/process.json"
                value = json.loads(data[key])
                suffix = {"escape": "/../provider-unit-only", "dot-alias": "/./provider-unit-only",
                          "slash-alias": "//provider-unit-only"}[kind]
                value["output_directory"] = str(run / "outputs/unit-only-0") + suffix
                data[key] = json_bytes(value)
                self.index(run, data)
            elif kind == "report":
                self.put("outputs/unit-only-0/provider-unit-only/detekt.sarif", b"changed original unit-only output", run)
            elif kind == "source":
                self.put("scratch/unit-only-0/sources/src/main/kotlin/Subject.kt", b"changed source", run)
            else:
                key = "cases/unit-only-0/engine-artifacts.json"
                value = json.loads(data[key])
                next(iter(value.values()))["digest"] = digest(b"changed engine")
                data[key] = json_bytes(value)
                self.index(run, data)
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.native_evidence(root, RULE, enrollment, run)

    def test_provider_coverage_cannot_convert_unknown_into_pass_or_qualification(self):
        for field, selected in (("state", "passed"), ("qualified", True), ("certificateId", "invented-unit-certificate"),
                                ("implemented", False), ("provenance", "reused_with_proof")):
            root, enrollment, run, data = self.native_fixture()
            key = "cases/unit-only-1/provider-result.json"
            value = json.loads(data[key])
            value["coverage"][0][field] = selected
            data[key] = json_bytes(value)
            self.index(run, data)
            with self.subTest(field=field):
                self.problem("compose_ci_actual_provider_coverage_overclaim", ci.native_evidence, root, RULE, enrollment, run)

    def test_generated_supervisor_manifest_must_bind_current_source_and_bytes(self):
        for kind in ("source", "binary", "manifest"):
            root, _, _, _ = self.native_fixture()
            if kind == "source":
                self.put("modules/provider-runtime/native/owned_process_supervisor.c", b"changed unit source", root)
            elif kind == "binary":
                self.put(ci.SUPERVISOR_PREFIX + "/" + ci.SUPERVISOR_TARGET + "/supervisor", b"changed inert bytes", root)
            else:
                self.put(ci.SUPERVISOR_PREFIX + "/manifest", b"unavailable\n", root)
            with self.subTest(kind=kind), self.assertRaises((ci.Problem, ValueError)):
                ci.supervisor_input(root)


class ProvisionAndEnvironmentControls(_IsolatedControls):
    def environment_fixture(self):
        home = self.root / "unit-only-jdk"
        launcher = self.put(home / "bin/java", b"inert unit-only launcher text, never executed")
        release = self.put(home / "release", b'JAVA_VERSION="21-unit-only"\n')
        os.environ["JAVA_HOME"] = str(home)
        value = {"state": "passed", "ci_context_required": True, "source": self.source, "ci": self.context,
                 "host": {"os": "Linux", "arch": "x86_64"},
                 "runner": {"RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64"},
                 "java": {"major": 21, "path": str(launcher), "launcher_digest": digest(launcher.read_bytes()),
                          "release_file_digest": digest(release.read_bytes())},
                 "gradle": {"executed_wrapper_version": "9.6.1"}}
        path = ci.paths(self.root, RULE)["environment"]
        self.put(path, json_bytes(value))
        return value, home, path

    def test_environment_joins_actual_selected_paths_hashes_and_ci_context(self):
        value, home, _ = self.environment_fixture()
        self.assertEqual((value, home), ci.environment_input(self.root, RULE, self.source, self.context))
        self.no_external_calls()

    def test_environment_rejects_wrong_java_wrapper_runner_or_source(self):
        for kind in ("java", "wrapper", "runner", "source", "workflow"):
            value, _, path = self.environment_fixture()
            if kind == "java":
                value["java"]["major"] = True
            elif kind == "wrapper":
                value["gradle"]["executed_wrapper_version"] = "other"
            elif kind == "runner":
                value["runner"]["RUNNER_ENVIRONMENT"] = "self-hosted"
            elif kind == "source":
                value["source"] = dict(self.source, dirty=True)
            else:
                value["ci"] = dict(self.context, GITHUB_WORKFLOW_SHA="2" * 40)
            self.put(path, json_bytes(value))
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.environment_input(self.root, RULE, self.source, self.context)

    def test_environment_detects_launcher_or_release_drift(self):
        for relative in ("bin/java", "release"):
            _, home, _ = self.environment_fixture()
            self.put(home / relative, b"changed inert unit-only bytes")
            with self.subTest(relative=relative):
                self.problem("compose_ci_observed_jdk_changed", ci.environment_input, self.root, RULE, self.source, self.context)

    def provision_fixture(self):
        lock = self.lock()
        self.stack.enter_context(mock.patch.object(ci, "owner_inputs", return_value=(self.source, self.context, lock)))
        self.stack.enter_context(mock.patch.object(ci, "check_source", return_value=self.source))
        environment = {"java": {"launcher_digest": digest(b"unit-only java"), "release_file_digest": digest(b"unit-only release")}}
        self.stack.enter_context(mock.patch.object(ci, "environment_input", return_value=(environment, self.root / "unit-only-jdk")))
        pins, payloads = [], {}
        for index, row in enumerate(self.enrollment()["engine"]):
            raw = ("unit-only inert engine " + str(index)).encode("ascii")
            filename = "unit-engine-" + str(index) + ".jar"
            pins.append({"coordinate": row["coordinate"], "filename": filename, "size": len(raw),
                         "sha256": hashlib.sha256(raw).hexdigest()})
            payloads[filename] = raw
        self.put("modules/provider-detekt/src/main/resources/detekt-artifacts.json", json_bytes({"artifacts": pins}))
        def fake_resolve(arguments, *, cwd, log_base, timeout, max_output_bytes):
            self.assertEqual("--download", arguments[-3])
            self.assertEqual("--cache", arguments[-2])
            self.assertEqual(600, timeout)
            self.assertEqual(1_048_576, max_output_bytes)
            directory = Path(arguments[-1])
            identities = []
            for pin in pins:
                path = self.put(pin["filename"], payloads[pin["filename"]], directory)
                identities.append({"coordinate": pin["coordinate"], "digest": "sha256:" + pin["sha256"], "path": str(path)})
            return {"state": "completed", "return_code": 0}, json_bytes({"artifacts": identities}).decode("utf-8"), ""
        self.external["run_logged"].side_effect = fake_resolve
        def inert_acquisition(row, destination, deadline, download):
            self.assertTrue(download)
            self.assertGreater(deadline, time.monotonic())
            # Do not materialize counterfeit published artifacts. Only the
            # provisioning descriptor's complete-vector wiring is under test.
            return {"coordinate": row["coordinate"], "digest": row["digest"], "size": row["size"], "path": str(destination)}
        acquired = self.stack.enter_context(mock.patch.object(ci, "acquire", side_effect=inert_acquisition))
        return lock, acquired

    def test_provision_preserves_supplied_complete_ordered_vectors_not_api_only_subset(self):
        lock, acquired = self.provision_fixture()
        before = copy.deepcopy(lock)
        result = ci.provision(self.root, RULE, self.lock_digest, True)
        self.assertEqual("passed", result["state"])
        self.assertEqual(0, result["native_calls"])
        self.assertFalse(result["provider_controls_executed"])
        layout = ci.paths(self.root, RULE)
        descriptor_raw = (layout["runtime"] / "owner-compilations.json").read_bytes()
        descriptor = json.loads(descriptor_raw)
        for name in ("reviewed", "unsupported_runtime"):
            self.assertEqual([row["coordinate"] for row in lock[name]["dependencies"]],
                             [row["coordinate"] for row in descriptor[name]["dependencies"]])
            self.assertEqual(5, len(descriptor[name]["dependencies"]))
            self.assertTrue(all(set(row) == {"coordinate", "digest", "size", "path"} for row in descriptor[name]["dependencies"]))
        self.assertEqual(lock, before)
        self.assertEqual(6, acquired.call_count)
        self.assertEqual(digest(descriptor_raw), result["owner_descriptor_digest"])
        self.external["gradle_result"].assert_not_called()
        self.network.assert_not_called()

    def test_provision_missing_download_consent_does_not_resolve_or_launch(self):
        _, acquired = self.provision_fixture()
        self.problem("compose_ci_explicit_download_consent_required", ci.provision, self.root, RULE, self.lock_digest, False)
        self.external["run_logged"].assert_not_called()
        self.external["gradle_result"].assert_not_called()
        acquired.assert_not_called()

    def test_provision_refuses_stale_runtime_or_preexisting_xml_directory(self):
        for kind in ("runtime", "xml"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=self.root) as temporary:
                previous = self.root
                self.root = Path(temporary)
                try:
                    _, acquired = self.provision_fixture()
                    layout = ci.paths(self.root, RULE)
                    selected = layout["runtime"] if kind == "runtime" else layout["xml"].parent
                    selected.mkdir(parents=True)
                    self.problem("compose_ci_fresh_outputs_required", ci.provision, self.root, RULE, self.lock_digest, True)
                    acquired.assert_not_called()
                finally:
                    self.root = previous
        self.external["run_logged"].assert_not_called()

    def test_immutable_missing_or_conflicting_member_cannot_enable_unstable_rule(self):
        for kind in ("missing", "conflict", "alias"):
            lock = self.lock()
            if kind == "missing":
                lock["reviewed"]["dependencies"].pop(2)
            elif kind == "conflict":
                lock["reviewed"]["dependencies"].append(self.row("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:other-unit-only"))
            else:
                lock["reviewed"]["dependencies"].append(self.row("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8"))
            with self.subTest(kind=kind), self.assertRaises(ci.Problem):
                ci.validate_lock(lock, self.root, IMMUTABLE_RULE)


class WorkflowSourceControls(_IsolatedControls):
    def test_workflow_has_exact_manual_six_rule_matrix_and_no_raw_upload(self):
        # This is a source-shape check, not a YAML/Actions execution claim.
        source = SOURCE_WORKFLOW.read_text(encoding="utf-8")
        rules = re.findall(r"^          - rule_id: (\S+)$", source, re.MULTILINE)
        slugs = re.findall(r"^            slug: (\S+)$", source, re.MULTILINE)
        counts = [int(value) for value in re.findall(r"^            maximum_native_invocations: ([0-9]+)$", source, re.MULTILINE)]
        self.assertEqual(list(ci.RULES), rules)
        self.assertEqual([ci.RULES[rule][0] for rule in rules], slugs)
        self.assertEqual([ci.RULES[rule][1] for rule in rules], counts)
        self.assertEqual(46, sum(counts))
        self.assertIn("  workflow_dispatch:", source)
        self.assertNotRegex(source, r"(?m)^\s+(?:pull_request|push|schedule):")
        self.assertIn("      max-parallel: 1", source)
        self.assertIn("      fail-fast: false", source)
        self.assertNotIn("continue-on-error:", source)
        uploads = re.findall(r"^          path: (.+)$", source, re.MULTILINE)
        self.assertEqual(["build/ci-evidence/compose/${{ matrix.slug }}/summary.json",
                          "build/ci-evidence/compose/${{ matrix.slug }}/junit-original.xml"], uploads)
        self.assertNotRegex(source, r"(?m)^\s+(?:secrets|environment):")
        self.assertNotIn("cancel-in-progress: true", source)


if __name__ == "__main__":
    unittest.main()
