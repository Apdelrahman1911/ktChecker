#!/usr/bin/env python3
"""Dependency-free, inert synthetic controls for the one-cohort projection.

No provider, JVM, native helper, project/model code, network or subprocess is
used. Artifacts called .class/.sarif/process.json are inert synthetic bytes.
Source-only reviewers can read this fixture builder without importing it.
Use a separately authorized, bounded unittest lane; these are not real-cohort
receipts and confer no rule/global tested credit or activation/qualification.

The 44 prior cases remain explicit: 33 semantic/source controls keep their real
reporter I/O on POSIX and use owned-fixture reader/publication doubles only on
unsupported Windows; 11 native I/O controls remain real POSIX-only exercises.
The Windows semantic adapter calls the real validator but is NOT native I/O
evidence. Separate all-host controls exercise both its success and rejection.
The native class is explicitly skipped where required POSIX APIs are absent;
skips never count as passes or Windows support. Additional applicability controls
exercise unsupported gates without any real open. Production stays POSIX-only.
"""

import ast
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import stat
import tempfile
import unittest
from unittest import mock

import report_detekt_cohort_execution as report


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prefixed(data):
    return "sha256:" + digest(data)


class SyntheticRun:
    """An explicitly named, tiny original-relative mirror. No current originals."""

    def __init__(self, directory):
        # Canonicalize only this newly owned fixture root. Production input and
        # output spellings still reach the unchanged no-follow reporter checks.
        self.base = Path(directory).resolve(strict=True)
        self.root = self.base / "inputs"
        self.root.mkdir()
        self.output = self.base / "report.json"
        self.docs, self.originals = {}, {}
        self.parent = "build/ci-evidence/inert-cohort"
        self.run = self.parent + "/evidence/detekt/runs/explicit-inert-run"
        self.selection_path = "explicit-selection.json"
        self.version = "2.0.0-alpha.6"
        self.source_digest = prefixed(b"whole-command source receipt claim, not the START subset")
        self.current_digest = prefixed(b"deliberately different current source")
        self.start = datetime(2026, 11, 5, tzinfo=timezone.utc)
        self.ids = ["ktc.synthetic.rule-" + str(index) for index in range(16)]
        self.selected = self.ids[:3]
        self.not_run = self.ids[3:]
        self.artifacts = [{"coordinate": "dev.detekt:detekt-cli:" + self.version + ":all", "digest": prefixed(b"inert pinned tool claim")}]
        self.plans = []
        for index, rule_id in enumerate(self.selected):
            cases = [{"case_id": name, "kind": name, "assessment": "independent_synthetic_case",
                      "descriptor_enabled": name != "configuration"} for name in report.CASE_KINDS]
            cases += [{"case_id": name, "kind": "regression", "assessment": assessment, "descriptor_enabled": True}
                      for name, assessment in (("known-fp-control", "anticipated_upstream_false_positive"),
                                               ("known-fn-control", "anticipated_upstream_false_negative"),
                                               ("policy-control", "opt_in_policy_limit"))]
            self.plans.append({"rule_id": rule_id, "slug": "rule-" + str(index), "upstream_id": "Upstream" + str(index),
                               "sarif_rule_id": "detekt.Rule" + str(index), "cases": cases})
        bindings, witness_refs = [], []
        sources = {report.RUNTIME_SOURCE: b"inert historical runtime source",
                   report.NATIVE_SOURCE: b"inert historical native source, never compiled",
                   "providers/detekt-fixtures/corpus/syntax-expansion-v1/cohorts/inert.json": b'{"inert":true}',
                   "build.gradle.kts": b"inert historical build source, never evaluated"}
        for origin, data in sources.items():
            self.originals[origin] = data
            original = self.reference(origin)
            retained = self.add(self.run + "/at-start/" + origin, data)
            bindings.append({"role": "source", "original": original, "retained": retained})
            witness_refs.extend((original, retained))
        runtime_image = self.add(self.run + "/code-images/runtime.class", b"inert runtime image")
        corpus_image = self.add(self.run + "/code-images/corpus.class", b"inert corpus image")
        native_image = self.add(self.run + "/native-image/linux-x86_64/supervisor", b"inert non-executable native image")
        source_tuple = prefixed("\n".join(name + ":" + prefixed(data) for name, data in sorted(sources.items())).encode())
        fixture_tuple = prefixed("\n".join(name + ":" + prefixed(data) for name, data in sorted(sources.items()) if name.endswith(".json")).encode())
        self.context = {"id": "explicit-inert-native-preview1", "target": "jvm", "source_set": "synthetic",
                        "corpus_cohort": "inert-v1", "cohort_plan_digest": prefixed(b"explicit inert authoring plan"),
                        "corpus_source_tuple_digest": source_tuple, "corpus_code_image_digest": "sha256:" + corpus_image["sha256"],
                        "corpus_fixture_set_digest": fixture_tuple, "invocation_ceiling": 37, "aggregate_limit_millis": 1_200_000,
                        "child_limit_millis": 60_000, "execution_backend": "owned_child_native_supervisor_preview1",
                        "supervisor_protocol_version": "ktchecker-owned-child-supervisor/preview-1", "supervisor_target": "linux-x86_64",
                        "supervisor_source_digest": prefixed(sources[report.NATIVE_SOURCE]), "supervisor_binary_digest": "sha256:" + native_image["sha256"],
                        "runtime_execution_source_digest": prefixed(sources[report.RUNTIME_SOURCE]), "runtime_code_image_digest": "sha256:" + runtime_image["sha256"],
                        "native_qualification": False, "module": ":inert-v1", "compilation": "main", "analysis_mode": "light",
                        "kotlin_version": "2.4.21", "compiler_version": "2.4.21", "embedded_kotlin_version": "2.4.10",
                        "jdk_version": "21.0.12", "library_versions": {}, "scope_note": "Only explicit synthetic fixture receipts; full sixteen/145 not_run."}
        self.context_digest = prefixed(encoded(self.context))
        self.native_identity = {"protocol_version": self.context["supervisor_protocol_version"], "target": "linux-x86_64",
                                "source_digest": self.context["supervisor_source_digest"], "binary_digest": self.context["supervisor_binary_digest"],
                                "identity_origin": "actual_launched_helper_result_not_inspector", "native_qualification": False}
        native_manifest = self.add(self.run + "/native-image/manifest", (self.context["supervisor_protocol_version"] + "\nlinux-x86_64\n" +
                                                                      self.context["supervisor_source_digest"] + "\n" + self.context["supervisor_binary_digest"] + "\n").encode())
        witness_refs.extend((runtime_image, corpus_image, native_image, native_manifest))
        for suffix, retained in (("manifest", native_manifest), ("linux-x86_64/supervisor", native_image)):
            origin = "modules/provider-runtime/build/resources/main/dev/ktchecker/providers/runtime/native/" + suffix
            self.originals[origin] = self.docs[retained["path"]]
            original = self.reference(origin)
            bindings.append({"role": "installed_resource", "original": original, "retained": retained})
            witness_refs.append(original)
        witness_refs.append(self.add(self.run + "/tool-start-witness.json", {"schema_version": 1, "qualified": False,
                                   "captured_at": self.at(0.1015), "artifacts": deepcopy(self.artifacts),
                                   "java_executable_digest": prefixed(b"inert Java file claim"), "java_runtime_version": "21.0.12+synthetic"}))
        self.witness_path = self.run + "/start-witness.json"
        self.witness = {"schema_version": 1, "captured_at": self.at(0.1015), "qualified": False, "references": witness_refs,
                        "source_attribution": "Explicit retained originals, not later source.", "bytecode_attribution": "Inert byte fixtures; no build proof.",
                        "installed_supervisor_identity": {**self.native_identity, "identity_origin": "exact_installed_code_source_manifest_and_binary_at_START_not_inspector"}}
        witness_ref = self.add(self.witness_path, self.witness)
        self.counts = dict(zip(report.KINDS, (27, 1, 6, 3)))
        self.budget = {"cohort": "inert-v1", "reserved_invocations": 37, "checked_invocations": 37, "invocation_ceiling": 37,
                       "aggregate_limit_millis": 1_200_000, "child_limit_millis": 60_000, "elapsed_monotonic_millis": 114,
                       "remaining_millis": 1_199_885, "stopped": False, "all_invocations_checked": True, "run_complete": False,
                       "publication_committed": False, "actual_execution_confirmations": 28, "actual_runtime_callbacks": 31,
                       "reserved_by_kind": deepcopy(self.counts), "checked_by_kind": deepcopy(self.counts), "native_launch_ceiling": 28, "qualified": False}
        self.controls = {"cohort": "inert-v1", "cohort_plan_digest": self.context["cohort_plan_digest"], "selected_rule_ids": self.selected,
                         "not_run_rule_ids": self.not_run, "full_corpus_state": "not_run", "scope_exclusions": [],
                         "approved_corpus_rule_count": 16, "approved_corpus_analysis_count": 145, "planned_invocations": 37,
                         "budget": {**deepcopy(self.budget), "reserved_invocations": 0, "checked_invocations": 0, "all_invocations_checked": False}}
        controls_ref = self.add(self.run + "/selected-cohort-controls.json", self.controls)
        budget_ref = self.add(self.run + "/checked-cohort-budget.json", self.budget)
        self.events, self.event_refs, self.checked_refs = [], [], {}
        self.event("run_started", 0)
        rows, ordinal, shared_probe = [], 0, None
        for rule_index, plan in enumerate(self.plans):
            cases = []
            for case_plan in plan["cases"]:
                name = case_plan["case_id"]
                prefix = self.run + "/cases/" + plan["slug"] + "/" + name
                # Body text is deliberately opaque. The reporter must not emit it.
                refs = [self.add(prefix + "/" + filename, b"OPAQUE_INERT_BODY_DO_NOT_PROJECT")
                        for filename in ("input.json", "provider-result.json", "generated-config.yml", "process.json", "report.sarif", "sources/Case.kt")]
                analysis = {"case_id": name, "rule_id": plan["rule_id"], "passed": True, "qualified": False, "execution_confirmed": True,
                            "selected_sarif_rule_id": plan["sarif_rule_id"], "selected_sarif_descriptor_enabled": case_plan["descriptor_enabled"],
                            "supervisor_identity": deepcopy(self.native_identity), "cleanup_signal": report.SIGNALS[report.KINDS[0]], "evidence": refs.copy()}
                analysis_ref = self.add(prefix + "/analysis-checked.json", analysis)
                refs.append(analysis_ref)
                ordinal += 1
                self.ticket(ordinal, report.KINDS[0], plan["rule_id"], name, refs.copy())
                if name == "positive":
                    denial_prefix = prefix + "/configuration-permit-replay"
                    result_ref = self.add(denial_prefix + ".json", b"OPAQUE_INERT_DENIAL_PROVIDER_RESULT")
                    process_ref = self.add(denial_prefix + "-denial-process.json", b"OPAQUE_INERT_DENIAL_PROCESS")
                    denial_ref = self.add(denial_prefix + "-denial-receipt.json", {"case_kind": "changed_configuration_permit_replay", "passed": True,
                                          "qualified": False, "execution_confirmed": False, "supervisor_identity": None,
                                          "process": process_ref, "provider_result": result_ref, "actual_denial": {"stdout": ""}})
                    extra = [result_ref, process_ref, denial_ref]
                    ordinal += 1
                    self.ticket(ordinal, report.KINDS[3], plan["rule_id"], "configuration-permit-replay", extra)
                    refs.extend(extra)
                if name == "version":
                    if shared_probe is None:
                        process_ref = self.add(self.run + "/version/process.json", b"OPAQUE_INERT_VERSION_PROCESS")
                        probe_ref = self.add(self.run + "/version/receipt.json", {"actual_exit_code": 0, "stdout": self.version + "\n", "stderr": "",
                                            "execution_confirmed": True, "supervisor_identity": deepcopy(self.native_identity),
                                            "declared_project_compiler": "2.4.21", "embedded_compiler_pin": "2.4.10", "compatibility_qualified": False,
                                            "process": process_ref})
                        shared_probe = [process_ref, probe_ref]
                        ordinal += 1
                        self.ticket(ordinal, report.KINDS[1], plan["rule_id"], "cli-version-probe", shared_probe)
                    refs.extend(shared_probe)
                    for target in ("common", "native"):
                        result_ref = self.add(prefix + "/" + target + "-provider-result.json", b"OPAQUE_INERT_ABSTENTION_PROVIDER_RESULT")
                        abstain_ref = self.add(prefix + "/" + target + "-abstention-receipt.json", {"case_kind": "unsupported_target_abstention", "target": target,
                                              "rule_id": plan["rule_id"], "passed": True, "qualified": False, "runtime_callback_observed": False,
                                              "provider_result": result_ref, "cleanup_signal": report.SIGNALS[report.KINDS[2]]})
                        extra = [result_ref, abstain_ref]
                        ordinal += 1
                        self.ticket(ordinal, report.KINDS[2], plan["rule_id"], "unsupported-" + target, extra)
                        refs.extend(extra)
                found = [] if name in ("negative", "configuration", "known-fn-control") else [{"line": 2, "excerpt": "OPAQUE_INERT_FINDING_EXCERPT"}]
                receipt = {"schema_version": 1, "case_id": name, "case_kind": case_plan["kind"], "passed": True, "provider_id": "detekt",
                           "provider_version": self.version, "rule_id": plan["rule_id"], "upstream_id": plan["upstream_id"], "actual_exit_code": 0,
                           "actual_provider_status": "partial", "execution_confirmed": True, "supervisor_identity": deepcopy(self.native_identity),
                           "snapshot_id": prefixed((prefix + " snapshot").encode()), "parent_snapshot_id": None,
                           "analysis_key": prefixed((prefix + " analysis").encode()), "configuration_sha256": "sha256:" + refs[2]["sha256"],
                           "analysis_mode": "light", "registered_materialization_verified": True, "source_rewrites": False, "qualified": False,
                           "authored_intent": "Inert authored legal alternative", "authored_assessment": case_plan["assessment"],
                           "assessment_scope": "Authored synthetic intent, not natural-project precision", "findings": found,
                           "expected_findings": deepcopy(found), "artifacts": refs.copy(), "start_witness": witness_ref,
                           "artifact_pins": deepcopy(self.artifacts), "command_template": ["OPAQUE_INERT_COMMAND_DO_NOT_PROJECT"]}
                receipt_ref = self.add(prefix + "/receipt.json", receipt)
                cases.append({"kind": case_plan["kind"], "case_id": name, "passed": True, "assessment": case_plan["assessment"], "evidence": refs + [receipt_ref]})
            rows.append({"rule_id": plan["rule_id"], "implemented": True, "tested": True, "qualified": False, "provider_status": "executed_unqualified",
                         "implementation_evidence": witness_refs + [witness_ref, controls_ref], "cases": cases, "qualification_evidence": [],
                         "gaps": ["Known synthetic FP/FN and policy limitations remain.", "No compatibility, natural-project precision or activation claim."]})
        for rule_id in self.not_run:
            rows.append({"rule_id": rule_id, "implemented": False, "tested": False, "qualified": False, "attempted": False,
                         "provider_status": "not_run", "implementation_evidence": [], "cases": [], "qualification_evidence": [],
                         "gaps": ["Not run: never borrowed from another cohort."]})
        self.event("all_cohort_invocations_checked", 37)
        self.sidecar_path = self.parent + "/evidence/detekt/evidence/verified-inert-native-preview1.json"
        self.sidecar = {"schema_version": 1, "provider_id": "detekt", "provider_version": self.version, "candidate_only": False,
                        "run_complete": True, "publication_committed": True, "context": self.context, "start_witness": witness_ref,
                        "rules": rows, "approved_corpus_rule_count": 16, "approved_corpus_analysis_count": 145, "full_corpus_state": "not_run",
                        "selected_cohort_rule_count": 3, "not_run_rule_ids": self.not_run, "scope_exclusions": [],
                        "cohort_controls": controls_ref, "checked_budget": budget_ref, "progress_evidence": self.event_refs}
        sidecar_ref = self.add(self.sidecar_path, self.sidecar)
        source = {"commit": "a" * 40, "dirty": True, "file_count": 4330, "unsupported_directories": 0, "working_tree_digest": self.source_digest,
                  "digest_scope": "tracked + untracked nonignored actual bytes, missing files, symlinks and executable bits"}
        self.command = {"schema_version": 1, "evidence_kind": "executed_gradle_command", "state": "passed", "return_code": 0,
                        "source": deepcopy(source), "started_at": self.at(0), "elapsed_seconds": 2, "expected_failure": False,
                        "expected_failed_tasks": [], "required_tasks": [report.TASK], "errors": [], "tasks": {report.TASK: "EXECUTED"},
                        "command": ["OPAQUE_INERT_GRADLE_COMMAND_DO_NOT_PROJECT"]}
        runtime = {"task": report.TASK, "workers": 1, "forks": 1, "fork_every": 1, "selected": [report.TEST_CLASS], "cohort": "inert-v1",
                   "max_invocations": "37", "aggregate_millis": "1200000", "child_millis": "60000", "evidence_root": "/original/repository/" + self.parent + "/evidence"}
        self.junit = ('<?xml version="1.0"?><testsuite name="' + report.TEST_CLASS + '" tests="1" failures="0" errors="0" skipped="0" timestamp="' + self.at(0.1) +
                      '" time="1.5"><testcase name="' + report.TEST_NAME + '" classname="' + report.TEST_CLASS + '" time="1.4"/></testsuite>').encode()
        self.inputs = {"source_before": self.add(self.parent + "/source.before.json", deepcopy(source)),
                       "source_after": self.add(self.parent + "/source.after.json", deepcopy(source)),
                       "command": self.add(self.parent + "/command.json", self.command),
                       "outer_exit": self.add(self.parent + "/outer-exit.json", {"return_code": 0}),
                       "runtime_configuration": self.add(self.parent + "/runtime-configuration.json", runtime),
                       "junit": self.add(self.parent + "/junit/TEST-" + report.TEST_CLASS + ".xml", self.junit), "sidecar": sidecar_ref}
        self.selection = {"schema_version": 1, "kind": "detekt_cohort_fixture_execution_selection", "source_selection": "historical",
                          "source_digest": self.source_digest, "context_digest": self.context_digest, "original_repository_root": "/original/repository",
                          "run_parent": self.parent, "run_directory": self.run, "provider": {"id": "detekt", "version": self.version, "artifacts": deepcopy(self.artifacts)},
                          "inputs": self.inputs, "original_inputs": bindings, "images": {"runtime_code": runtime_image, "corpus_code": corpus_image, "native_binary": native_image},
                          "approved_rule_ids": self.ids, "selected_rules": self.plans}
        self.add(self.selection_path, self.selection)

    def at(self, seconds):
        return (self.start + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")

    def reference(self, path):
        value = self.originals[path] if path in self.originals else self.docs[path]
        return {"path": path, "sha256": digest(value if type(value) is bytes else encoded(value))}

    def add(self, path, value):
        self.docs[path] = value
        return self.reference(path)

    def event(self, phase, ordinal, kind=None, rule_id=None, case_id=None, extra=None):
        sequence = len(self.events) + 1
        event = {"schema_version": 1, "sequence": sequence, "phase": phase, "cohort": "inert-v1", "captured_at": self.at(0.1 + sequence / 1000),
                 "elapsed_monotonic_millis": sequence, "remaining_millis": 1_200_000 - sequence - 1,
                 "reserved_invocations": ordinal, "checked_invocations": ordinal if kind is None else ordinal - 1, "qualified": False}
        if kind is not None:
            event.update({"invocation": ordinal, "kind": kind, "rule_id": rule_id, "case_id": case_id})
        event.update(extra or {})
        self.events.append(event)
        self.event_refs.append(self.add(self.run + "/progress/" + f"{sequence:04d}.json", event))

    def ticket(self, ordinal, kind, rule_id, case_id, refs):
        self.event("reserved_before_call", ordinal, kind, rule_id, case_id)
        observed = {"cleanup_signal": report.SIGNALS[kind]}
        phase = "actual_runtime_callback_retained"
        if kind in report.KINDS[:2]:
            observed.update({"actual_status": "executed", "actual_exit_code": 0, "execution_confirmed": True, "actual_reasons": [], "actual_supervisor_target": "linux-x86_64"})
        elif kind == report.KINDS[3]:
            observed.update({"actual_status": "unavailable", "actual_exit_code": None, "execution_confirmed": False,
                             "actual_reasons": ["execution_not_authorized"], "actual_supervisor_target": None})
        else:
            phase = "actual_provider_abstention_retained"
            observed.update({"actual_status": "partial", "runtime_callback_observed": False, "unexpected_execution_confirmed": None,
                             "actual_reasons": ["detekt_jvm_provider_does_not_qualify_common_or_native_targets"]})
        self.event(phase, ordinal, kind, rule_id, case_id, observed)
        self.event("assertions_checked", ordinal, kind, rule_id, case_id, {"passed": True, "cleanup_signal": report.SIGNALS[kind], "evidence": refs})
        self.checked_refs[(kind, rule_id, case_id)] = refs

    def materialize(self):
        """Repin bottom-up after a semantic mutation; never execute a fixture."""
        memo, pending = {}, set()
        def body(path):
            if path in memo:
                return memo[path]
            if path in self.originals:
                return self.originals[path]
            if path not in self.docs:
                return None
            if path in pending:
                raise AssertionError("Synthetic reference cycle")
            pending.add(path)
            value = self.docs[path]
            def refresh(item):
                if type(item) is dict:
                    if set(item) == {"path", "sha256"}:
                        target = body(item["path"])
                        return {"path": item["path"], "sha256": digest(target)} if target is not None else item.copy()
                    return {k: refresh(v) for k, v in item.items()}
                if type(item) is list:
                    return [refresh(v) for v in item]
                return item
            result = value if type(value) is bytes else encoded(refresh(value))
            pending.remove(path)
            memo[path] = result
            return result
        for name in self.docs:
            body(name)
        for name, data in memo.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.selection_sha = digest(memo[self.selection_path])

    def argv(self, **changes):
        values = {"input-root": str(self.root), "run-evidence": self.selection_path, "expect-run-evidence-sha256": self.selection_sha,
                  "expect-source-digest": self.source_digest, "expect-context-digest": self.context_digest,
                  "expect-provider-version": self.version, "current-source-digest": self.current_digest, "output": str(self.output)}
        values.update(changes)
        return [part for key, value in values.items() for part in ("--" + key, value)]


# This observes API availability, not a successful native filesystem operation.
# Unsupported Windows still executes semantic/applicability controls.
NATIVE_REPORT_IO = all(hasattr(os, name) for name in ("O_NOFOLLOW", "O_DIRECTORY")) and \
    os.open in os.supports_dir_fd
WINDOWS_PORTABLE_SEMANTICS = os.name == "nt" and not NATIVE_REPORT_IO
NATIVE_IO_SKIP = ("Actual POSIX no-follow/dir-fd I/O unavailable; portable semantic and "
                  "fail-closed controls still run; no emulated native pass or Windows qualification")


class PortableFixtureReadSet:
    """Ordinary reads of owned inert fixtures, NOT the production I/O contract.

    This double supplies bytes and reference pins to the real semantic validator.
    It does not emulate inode custody, no-follow behavior, ACLs, atomicity or
    publication. Those properties belong only to the separate native I/O cases.
    """

    def __init__(self, root):
        self.root = root
        self.files = {}
        self.total = 0
        self.closed = False
        self.rechecks = 0

    def owned_path(self, name):
        # Independent fixture-boundary check: do not rely on candidate validation
        # to stop a malformed reference from reading outside the owned fixture.
        if type(name) is not str or not name or "\\" in name or ":" in name or \
                any(part in ("", ".", "..") for part in name.split("/")):
            raise AssertionError("unsafe portable fixture reference")
        return self.root.joinpath(*name.split("/"))

    def read(self, reference, repeat=False):
        reference = report.ref(reference)
        name, expected = reference["path"], reference["sha256"]
        path = self.owned_path(name)
        if name in self.files:
            report.same(expected, self.files[name][0], "reference digest conflict")
            if not repeat:
                return self.files[name][2]
        with path.open("rb") as stream:
            raw = stream.read(report.MAX_FILE + 1)
        report.need(len(raw) <= report.MAX_FILE, "portable fixture byte bound")
        report.same(digest(raw), expected, "evidence digest mismatch")
        if name not in self.files:
            self.total += len(raw)
            # None is deliberate: this double supplies no physical identity.
            self.files[name] = (expected, None, raw)
        return raw

    def document(self, reference):
        return report.parse_json(self.read(reference))

    def recheck(self):
        self.rechecks += 1
        for name, (expected, _, _) in list(self.files.items()):
            self.read({"path": name, "sha256": expected}, repeat=True)

    def close(self):
        if self.closed:
            raise AssertionError("portable fixture reader closed twice")
        self.closed = True


class FixtureExecutionReportSupport:
    """Shared assertions; no test methods and no implicit test discovery."""

    def make_run(self):
        temporary = tempfile.TemporaryDirectory(prefix="inert-detekt-projection-")
        self.addCleanup(temporary.cleanup)
        return SyntheticRun(temporary.name)

    def call(self, fixture, **arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = report.main(fixture.argv(**arguments))
        self.assertNotIn("OPAQUE_INERT", stdout.getvalue() + stderr.getvalue())
        return result, stdout.getvalue(), stderr.getvalue()

    def accepted(self, fixture, **arguments):
        fixture.materialize()
        result, _, error = self.call(fixture, **arguments)
        self.assertEqual(0, result, error)
        return json.loads(fixture.output.read_bytes())

    def rejected(self, fixture, **arguments):
        fixture.materialize()
        result, _, error = self.call(fixture, **arguments)
        self.assertEqual(2, result, error)
        self.assertFalse(fixture.output.exists())
        return error


class FixtureExecutionReportTests(FixtureExecutionReportSupport, unittest.TestCase):
    """Keep integrated POSIX cases; use explicit fixture I/O on Windows only."""

    def call(self, fixture, **arguments):
        if WINDOWS_PORTABLE_SEMANTICS:
            return self.portable_call(fixture, **arguments)
        return super().call(fixture, **arguments)

    def portable_call(self, fixture, **arguments):
        """Exercise the real validator, not native reads or report publication."""
        reader = PortableFixtureReadSet(fixture.root)
        opened = []

        def fixture_reader(root):
            self.assertEqual(str(fixture.root), root)
            opened.append(root)
            return reader

        def fixture_publication(actual_reader, output, value):
            self.assertIs(reader, actual_reader)
            self.assertEqual(str(fixture.output), output)
            # Serialize the validator's actual return; never substitute a pass
            # object. This fresh ordinary write is NOT production publication.
            with fixture.output.open("xb") as stream:
                stream.write(encoded(value))

        with mock.patch.object(report, "ReadSet", side_effect=fixture_reader), \
                mock.patch.object(report, "write_report", side_effect=fixture_publication) as publish, \
                mock.patch.object(report, "create_report", wraps=report.create_report) as validate, \
                mock.patch.object(report, "open_directory", side_effect=AssertionError(
                    "production no-follow I/O forbidden in portable semantic controls")) as native_open:
            result = super().call(fixture, **arguments)
        self.assertEqual([str(fixture.root)], opened)
        validate.assert_called_once()
        self.assertIs(reader, validate.call_args.args[0])
        self.assertTrue(reader.closed)
        native_open.assert_not_called()
        if result[0] == 0:
            publish.assert_called_once()
            self.assertEqual(1, reader.rechecks)
        else:
            publish.assert_not_called()
            self.assertFalse(fixture.output.exists())
        return result

    def test_historical_receipts_do_not_retarget_to_changed_or_absent_current_source(self):
        fixture = self.make_run()
        changed = fixture.root / report.RUNTIME_SOURCE
        changed.parent.mkdir(parents=True)
        changed.write_text("DIFFERENT CURRENT SOURCE MUST NEVER BE USED")
        value = self.accepted(fixture)
        self.assertEqual("historical", value["source"]["selection"])
        self.assertFalse(value["source"]["matches_caller_current_source"])
        self.assertFalse(value["source"]["current_source_fallback"])
        self.assertEqual("DIFFERENT CURRENT SOURCE MUST NEVER BE USED", changed.read_text())
        self.assertEqual(fixture.source_digest, value["source"]["execution_receipt"]["working_tree_digest"])

    def test_explicit_current_selection_requires_matching_comparison(self):
        for matches in (False, True):
            with self.subTest(matches=matches):
                fixture = self.make_run()
                fixture.selection["source_selection"] = "current"
                if matches:
                    value = self.accepted(fixture, **{"current-source-digest": fixture.source_digest})
                    self.assertTrue(value["source"]["matches_caller_current_source"])
                else:
                    self.rejected(fixture)

    def test_exact_three_rule_counts_typed_controls_and_not_run_scope(self):
        value = self.accepted(self.make_run())
        summary = value["summary"]
        self.assertEqual((3, 13, 37, 28, 31, 113), tuple(summary[k] for k in ("fixture_execution_checked_rules", "not_run_rules", "checked_invocations",
                                                                         "actual_native_confirmations", "actual_runtime_callbacks", "progress_events")))
        self.assertEqual(dict(zip(report.KINDS, (27, 1, 6, 3))), summary["checked_by_kind"])
        self.assertEqual(27, sum(row["detector_analyses"] for row in value["rules"]))
        self.assertEqual(1, summary["shared_cli_probe_count"])
        self.assertTrue(summary["shared_cli_probe_is_not_a_per_rule_analysis"])
        self.assertEqual(13, len(value["not_run_rule_ids"]))
        self.assertEqual("not_run", summary["full_corpus_state"])
        self.assertEqual(145, summary["approved_corpus_analysis_count"])

    def test_sidecar_local_tested_is_not_global_tested_or_qualification(self):
        value = self.accepted(self.make_run())
        for field in ("qualified", "release_qualified", "native_qualification", "coverage_ledger_updated", "activation_changed"):
            self.assertIs(False, value[field])
        for row in value["rules"]:
            self.assertIs(False, row["qualified"])
            self.assertIs(False, row["activation_changed"])
            self.assertNotIn("tested", row)
            self.assertEqual(row["fixture_execution_checked"], row["sidecar_local_claims_not_global_ledger_credit"]["tested"])

    def test_authored_false_positive_negative_policy_and_limitations_are_preserved_separately(self):
        fixture = self.make_run()
        value = self.accepted(fixture)
        for expected, row in zip(fixture.sidecar["rules"], value["rules"]):
            self.assertEqual(expected["gaps"], row["limitations"])
        controls = value["rules"][0]["authored_control_assessments_not_real_world_error_rates"]
        self.assertEqual(["known-fp-control"], controls["anticipated_upstream_false_positive"])
        self.assertEqual(["known-fn-control"], controls["anticipated_upstream_false_negative"])
        self.assertEqual(["policy-control"], controls["opt_in_policy_limit"])

    def test_disabled_configuration_is_not_an_enabled_descriptor(self):
        value = self.accepted(self.make_run())
        for row in value["rules"][:3]:
            case = next(case for case in row["cases"] if case["case_id"] == "configuration")
            self.assertTrue(case["execution_confirmed"])
            self.assertFalse(case["descriptor_enabled"])
            self.assertEqual(1, row["configuration_authorization_denials"])
            self.assertEqual(2, row["unsupported_target_abstentions"])

    def test_report_binds_full_context_and_selected_evidence_without_raw_body_text(self):
        fixture = self.make_run()
        value = self.accepted(fixture)
        self.assertEqual(fixture.context, value["context"])
        self.assertEqual(fixture.context_digest, value["context_digest"])
        self.assertEqual(fixture.selection_sha, value["run_evidence"]["sha256"])
        self.assertEqual(fixture.inputs.keys(), value["settled_inputs"].keys())
        self.assertGreater(value["evidence_read_set"]["files"], 113)
        body = fixture.output.read_text()
        for forbidden in ("OPAQUE_INERT", "command_template", "/original/repository", "DIFFERENT CURRENT SOURCE"):
            self.assertNotIn(forbidden, body)
        self.assertNotEqual(value["source"]["execution_receipt"]["working_tree_digest"], value["context"]["corpus_source_tuple_digest"])

    def test_candidate_failed_or_uncommitted_sidecar_is_rejected(self):
        for field, value in (("candidate_only", True), ("run_complete", False), ("publication_committed", False), ("candidate_only", 0)):
            with self.subTest(field=field, value=value):
                fixture = self.make_run()
                fixture.sidecar[field] = value
                self.rejected(fixture)

    def test_command_or_outer_failure_cached_task_and_missing_selector_are_rejected(self):
        mutations = (lambda f: f.command.update(state="failed"), lambda f: f.command.update(return_code=1),
                     lambda f: f.command["tasks"].update({report.TASK: "UP-TO-DATE"}), lambda f: f.command.update(errors=["inert error"]),
                     lambda f: f.docs[f.inputs["outer_exit"]["path"]].update(return_code=1),
                     lambda f: f.docs[f.inputs["runtime_configuration"]["path"]].update(cohort="different-cohort"),
                     lambda f: f.docs[f.inputs["runtime_configuration"]["path"]].update(max_invocations="40"))
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                fixture = self.make_run()
                mutate(fixture)
                self.rejected(fixture)

    def test_junit_fail_skip_missing_duplicate_wrong_method_and_xml_entities_rejected(self):
        for old, new in ((b'failures="0"', b'failures="1"'), (b'skipped="0"', b'skipped="1"'),
                         (report.TEST_NAME.encode(), b"different method()"), (b"<testcase ", b"<wrongcase "),
                         (b"</testsuite>", b'<testcase name="extra"/></testsuite>'),
                         (b'<?xml version="1.0"?>', b'<?xml version="1.0"?><!DOCTYPE testsuite [<!ENTITY x "unsafe">]>')):
            with self.subTest(old=old):
                fixture = self.make_run()
                fixture.docs[fixture.inputs["junit"]["path"]] = fixture.junit.replace(old, new)
                self.rejected(fixture)

    def test_utf16_utf32_dtd_and_non_utf8_xml_envelopes_rejected(self):
        for encoding in ("utf-16", "utf-16-le", "utf-16-be", "utf-32", "utf-32-le", "utf-32-be"):
            with self.subTest(encoding=encoding):
                fixture = self.make_run()
                xml = fixture.junit.decode().replace('<?xml version="1.0"?>',
                      '<?xml version="1.0" encoding="' + encoding + '"?><!DOCTYPE testsuite [<!ENTITY inert "inert">]>')
                fixture.docs[fixture.inputs["junit"]["path"]] = xml.encode(encoding)
                self.rejected(fixture)
        for prefix in (b'<?xml version="1.0" encoding="ISO-8859-1"?>', b'<?xml version="1.0"?>\x00'):
            with self.subTest(prefix=prefix):
                fixture = self.make_run()
                fixture.docs[fixture.inputs["junit"]["path"]] = fixture.junit.replace(b'<?xml version="1.0"?>', prefix)
                self.rejected(fixture)

    def test_ordinary_utf8_junit_with_utf8_bom_is_accepted(self):
        fixture = self.make_run()
        fixture.docs[fixture.inputs["junit"]["path"]] = b'\xef\xbb\xbf' + fixture.junit.replace(
            b'<?xml version="1.0"?>', b'<?xml version="1.0" encoding="UTF-8"?>')
        self.accepted(fixture)

    def test_expected_selector_source_context_provider_and_current_digests_fail_closed(self):
        for key, value in (("expect-run-evidence-sha256", "0" * 64), ("expect-source-digest", prefixed(b"other source")),
                           ("expect-context-digest", prefixed(b"other context")), ("expect-provider-version", "other-version"),
                           ("current-source-digest", "not-a-digest")):
            with self.subTest(key=key):
                self.rejected(self.make_run(), **{key: value})

    def test_source_before_after_and_command_identity_swaps_rejected(self):
        for where in ("source_before", "source_after", "command"):
            with self.subTest(where=where):
                fixture = self.make_run()
                data = fixture.docs[fixture.inputs[where]["path"]]
                if where == "command":
                    data = data["source"]
                data["working_tree_digest"] = prefixed(b"swapped source")
                self.rejected(fixture)

    def test_all_context_fields_are_hash_bound_not_just_id(self):
        for field, value in (("jdk_version", "21.0.13"), ("library_versions", {"new-lib": "1"}), ("scope_note", "different scope"),
                             ("corpus_code_image_digest", prefixed(b"foreign image")), ("new_unrecognized_context_field", "still hash-bound")):
            with self.subTest(field=field):
                fixture = self.make_run()
                fixture.context[field] = value
                self.rejected(fixture)

    def test_repinning_wrong_source_tuple_image_or_runtime_does_not_bypass_join(self):
        for field in ("corpus_source_tuple_digest", "corpus_fixture_set_digest", "runtime_code_image_digest", "corpus_code_image_digest",
                      "runtime_execution_source_digest", "supervisor_source_digest", "supervisor_binary_digest"):
            with self.subTest(field=field):
                fixture = self.make_run()
                fixture.context[field] = prefixed(b"wrong independently selected context identity")
                fixture.context_digest = prefixed(encoded(fixture.context))
                fixture.selection["context_digest"] = fixture.context_digest
                self.rejected(fixture)

    def test_missing_unbound_extra_or_mislocated_original_inputs_rejected(self):
        for mutation in ("missing", "extra", "bad-role", "mislocated", "duplicate"):
            with self.subTest(mutation=mutation):
                fixture = self.make_run()
                bindings = fixture.selection["original_inputs"]
                if mutation == "missing":
                    bindings.pop(0)
                elif mutation == "extra":
                    binding = deepcopy(bindings[0])
                    binding["original"]["path"] += ".unselected"
                    binding["retained"]["path"] += ".unselected"
                    bindings.append(binding)
                elif mutation == "bad-role":
                    bindings[0]["role"] = "current-source-fallback"
                elif mutation == "mislocated":
                    bindings[0]["retained"] = deepcopy(bindings[1]["retained"])
                else:
                    bindings.append(deepcopy(bindings[0]))
                self.rejected(fixture)

    def test_dropped_not_run_rows_ids_scope_exclusions_and_borrowed_credit_rejected(self):
        mutations = (lambda f: f.sidecar["rules"].pop(), lambda f: f.sidecar.update(not_run_rule_ids=[]),
                     lambda f: f.sidecar.update(scope_exclusions=[f.not_run[0]]), lambda f: f.sidecar.update(full_corpus_state="complete"),
                     lambda f: f.sidecar["rules"][-1].update(tested=True), lambda f: f.sidecar["rules"][-1].update(attempted=True),
                     lambda f: f.selection["approved_rule_ids"].pop())
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                fixture = self.make_run()
                mutate(fixture)
                self.rejected(fixture)

    def test_later_nineteen_rule_172_analysis_scope_is_not_retargeted(self):
        fixture = self.make_run()
        fixture.selection["approved_rule_ids"].extend("ktc.synthetic.later-" + str(i) for i in range(3))
        for item in (fixture.sidecar, fixture.controls):
            item["approved_corpus_rule_count"] = 19
            item["approved_corpus_analysis_count"] = 172
        self.rejected(fixture)

    def test_forged_qualification_or_missing_limitations_rejected(self):
        for where in ("row", "context", "receipt", "budget", "evidence", "gaps"):
            with self.subTest(where=where):
                fixture = self.make_run()
                if where == "row":
                    fixture.sidecar["rules"][0]["qualified"] = True
                elif where == "context":
                    fixture.context["native_qualification"] = True
                elif where == "receipt":
                    fixture.docs[fixture.run + "/cases/rule-0/positive/receipt.json"]["qualified"] = True
                elif where == "budget":
                    fixture.budget["qualified"] = True
                elif where == "evidence":
                    fixture.sidecar["rules"][0]["qualification_evidence"] = [fixture.inputs["source_before"]]
                else:
                    fixture.sidecar["rules"][0]["gaps"] = []
                self.rejected(fixture)

    def test_missing_duplicate_reordered_cases_or_assessment_swap_rejected(self):
        for mutation in ("missing", "duplicate", "reordered", "assessment", "receipt-rule", "case-kind"):
            with self.subTest(mutation=mutation):
                fixture = self.make_run()
                cases = fixture.sidecar["rules"][0]["cases"]
                if mutation == "missing":
                    cases.pop()
                elif mutation == "duplicate":
                    cases[-1] = deepcopy(cases[0])
                elif mutation == "reordered":
                    cases[0], cases[1] = cases[1], cases[0]
                elif mutation == "assessment":
                    cases[-1]["assessment"] = "universal_validated_defect"
                else:
                    receipt = fixture.docs[fixture.run + "/cases/rule-0/positive/receipt.json"]
                    receipt["rule_id" if mutation == "receipt-rule" else "case_kind"] = "different"
                self.rejected(fixture)

    def test_selected_case_plan_duplicate_and_disabled_descriptor_substitution_rejected(self):
        for field, value in (("case_id", "positive"), ("kind", "negative"), ("descriptor_enabled", False)):
            with self.subTest(field=field):
                fixture = self.make_run()
                fixture.plans[0]["cases"][-1][field] = value
                self.rejected(fixture)

    def test_missing_duplicate_reordered_stale_or_failed_progress_rejected(self):
        for mutation in ("missing", "duplicate", "reordered", "sequence", "old-time", "failed", "unpassed", "wrong-ticket", "bool-counter"):
            with self.subTest(mutation=mutation):
                fixture = self.make_run()
                if mutation == "missing":
                    fixture.event_refs.pop(4)
                elif mutation == "duplicate":
                    fixture.event_refs[4] = deepcopy(fixture.event_refs[3])
                elif mutation == "reordered":
                    fixture.event_refs[1], fixture.event_refs[2] = fixture.event_refs[2], fixture.event_refs[1]
                elif mutation == "sequence":
                    fixture.events[3]["sequence"] = 9
                elif mutation == "old-time":
                    fixture.events[3]["captured_at"] = "2026-11-04T00:00:00Z"
                elif mutation == "failed":
                    fixture.events[-1]["phase"] = "failed_or_incomplete"
                elif mutation == "unpassed":
                    fixture.events[3]["passed"] = False
                elif mutation == "wrong-ticket":
                    fixture.events[3]["rule_id"] = fixture.selected[1]
                else:
                    fixture.events[1]["reserved_invocations"] = True
                self.rejected(fixture)

    def test_progress_denial_abstention_probe_never_gain_analysis_credit(self):
        for kind, field, value in ((report.KINDS[3], "execution_confirmed", True), (report.KINDS[2], "runtime_callback_observed", True),
                                   (report.KINDS[2], "unexpected_execution_confirmed", True), (report.KINDS[1], "execution_confirmed", False),
                                   (report.KINDS[0], "actual_reasons", ["cleanup missing"]), (report.KINDS[3], "actual_exit_code", 0)):
            with self.subTest(kind=kind, field=field):
                fixture = self.make_run()
                event = next(e for e in fixture.events if e.get("kind") == kind and e["phase"].startswith("actual_"))
                event[field] = value
                self.rejected(fixture)

    def test_candidate_budget_flags_stay_false_but_final_sidecar_must_commit(self):
        for field, value in (("run_complete", True), ("publication_committed", True), ("all_invocations_checked", False),
                             ("stopped", True), ("actual_execution_confirmations", 37), ("actual_runtime_callbacks", 37),
                             ("reserved_by_kind", dict(zip(report.KINDS, (28, 0, 6, 3)))), ("remaining_millis", 0)):
            with self.subTest(field=field):
                fixture = self.make_run()
                fixture.budget[field] = value
                self.rejected(fixture)

    def test_special_control_receipt_swaps_or_forged_execution_rejected(self):
        for suffix, field, value in (("version/receipt.json", "stdout", "different-version"),
                                     ("cases/rule-0/positive/configuration-permit-replay-denial-receipt.json", "execution_confirmed", True),
                                     ("cases/rule-0/version/native-abstention-receipt.json", "target", "common"),
                                     ("cases/rule-0/version/common-abstention-receipt.json", "runtime_callback_observed", True)):
            with self.subTest(suffix=suffix):
                fixture = self.make_run()
                fixture.docs[fixture.run + "/" + suffix][field] = value
                self.rejected(fixture)

    def test_case_receipt_descriptor_provider_artifact_or_materialization_swap_rejected(self):
        for file, field, value in (("receipt.json", "provider_version", "old-version"), ("receipt.json", "source_rewrites", True),
                                  ("receipt.json", "registered_materialization_verified", False), ("receipt.json", "actual_provider_status", "complete"),
                                  ("receipt.json", "artifact_pins", [{"coordinate": "wrong", "digest": prefixed(b"wrong")}]),
                                  ("analysis-checked.json", "selected_sarif_descriptor_enabled", False),
                                  ("analysis-checked.json", "selected_sarif_rule_id", "wrong-rule")):
            with self.subTest(file=file, field=field):
                fixture = self.make_run()
                fixture.docs[fixture.run + "/cases/rule-0/positive/" + file][field] = value
                self.rejected(fixture)

    def test_case_evidence_missing_or_other_run_reference_rejected(self):
        for mutation in ("missing-receipt", "missing-progress-link", "other-run", "case-control"):
            with self.subTest(mutation=mutation):
                fixture = self.make_run()
                case = fixture.sidecar["rules"][0]["cases"][0]
                if mutation == "missing-receipt":
                    case["evidence"].pop()
                elif mutation == "missing-progress-link":
                    fixture.events[3]["evidence"] = fixture.events[3]["evidence"][:-1]
                elif mutation == "other-run":
                    case["evidence"].append({"path": "build/ci-evidence/another-run/receipt.json", "sha256": "a" * 64})
                else:
                    case["evidence"] = [r for r in case["evidence"] if not r["path"].endswith("-denial-receipt.json")]
                self.rejected(fixture)

    def test_fully_repinned_missing_analysis_core_or_all_sources_rejected(self):
        for filename in ("input.json", "provider-result.json", "generated-config.yml", "process.json", "report.sarif", "sources/Case.kt"):
            with self.subTest(filename=filename):
                fixture = self.make_run()
                prefix = fixture.run + "/cases/rule-0/positive"
                missing = prefix + "/" + filename
                # Remove everywhere in the selected case graph, then repin all
                # outer references. Rejection must not rely on a stale digest.
                case = fixture.sidecar["rules"][0]["cases"][0]
                receipt = fixture.docs[prefix + "/receipt.json"]
                analysis = fixture.docs[prefix + "/analysis-checked.json"]
                for item, key in ((case, "evidence"), (receipt, "artifacts"), (analysis, "evidence"), (fixture.events[3], "evidence")):
                    item[key] = [r for r in item[key] if r["path"] != missing]
                self.rejected(fixture)

    def test_fully_repinned_sibling_sources_or_unexpected_analysis_branch_rejected(self):
        for replacement in ("../negative/sources/Case.kt", "unrelated.json"):
            with self.subTest(replacement=replacement):
                fixture = self.make_run()
                prefix = fixture.run + "/cases/rule-0/positive"
                original = prefix + "/sources/Case.kt"
                sibling = fixture.run + "/cases/rule-0/negative/sources/Case.kt"
                foreign = fixture.reference(sibling) if replacement.startswith("..") else fixture.add(prefix + "/unrelated.json", b"inert unrelated body")
                case = fixture.sidecar["rules"][0]["cases"][0]
                receipt = fixture.docs[prefix + "/receipt.json"]
                analysis = fixture.docs[prefix + "/analysis-checked.json"]
                for item, key in ((case, "evidence"), (receipt, "artifacts"), (analysis, "evidence"), (fixture.events[3], "evidence")):
                    item[key] = [deepcopy(foreign) if r["path"] == original else r for r in item[key]]
                self.rejected(fixture)

    def test_duplicate_json_nonfinite_deep_and_malformed_inputs_fail_closed(self):
        for body in (b'{"schema_version":1,"schema_version":1}', b'{"value":NaN}', b'{"value":Infinity}',
                     b'{"value":' + b'[' * 34 + b'0' + b']' * 34 + b'}', b'\xff', b'{'):
            with self.subTest(body=body[:32]):
                fixture = self.make_run()
                fixture.materialize()
                (fixture.root / fixture.selection_path).write_bytes(body)
                fixture.selection_sha = digest(body)
                self.assertEqual(2, self.call(fixture)[0])
                self.assertFalse(fixture.output.exists())

    def test_noncanonical_traversal_and_unselected_source_boundaries_rejected(self):
        for path in ("../outside.json", "/outside.json", "build//outside.json", "build/./outside.json", "C:\\outside.json", "build/../outside.json"):
            with self.subTest(path=path):
                self.rejected(self.make_run(), **{"run-evidence": path})

    def test_stdlib_only_report_has_no_launcher_network_or_dynamic_import_calls(self):
        tree = ast.parse(Path(report.__file__).read_text())
        imports = []
        calls = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imports.extend(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imports.append(node.module)
            elif isinstance(node, ast.Call):
                if isinstance(node.func, ast.Name):
                    calls.append(node.func.id)
                elif isinstance(node.func, ast.Attribute):
                    calls.append(node.func.attr)
        self.assertEqual({"argparse", "datetime", "hashlib", "json", "math", "os", "re", "stat", "sys", "xml.etree.ElementTree"}, set(imports))
        forbidden = {"exec", "eval", "__import__", "system", "popen", "fork", "forkpty", "posix_spawn", "posix_spawnp",
                     "spawnl", "spawnle", "spawnlp", "spawnlpe", "spawnv", "spawnve", "spawnvp", "spawnvpe",
                     "execv", "execve", "execl", "execvp", "execvpe", "connect", "urlopen", "getaddrinfo"}
        self.assertFalse(forbidden.intersection(calls))

    def test_portable_adapter_does_not_replace_validator_rejection_with_success(self):
        fixture = self.make_run()
        fixture.materialize()
        rejection = "synthetic semantic validator rejection"
        with mock.patch.object(report, "create_report", side_effect=report.ReportError(rejection)) as validate:
            result, output, error = self.portable_call(fixture)
        validate.assert_called_once()
        self.assertEqual(2, result)
        self.assertEqual("", output)
        self.assertEqual("fixture-execution report rejected: " + rejection + "\n", error)
        self.assertFalse(fixture.output.exists())

    def test_portable_adapter_positive_path_executes_real_semantic_validation_on_every_host(self):
        fixture = self.make_run()
        fixture.materialize()
        result, output, error = self.portable_call(fixture)
        self.assertEqual(0, result, error)
        self.assertIn("unqualified", output)
        value = json.loads(fixture.output.read_bytes())
        self.assertEqual("fixture_execution_checked", value["state"])
        self.assertEqual(3, value["summary"]["fixture_execution_checked_rules"])
        self.assertEqual(37, value["summary"]["checked_invocations"])
        self.assertGreater(value["evidence_read_set"]["files"], 113)
        for field in ("qualified", "release_qualified", "native_qualification", "coverage_ledger_updated", "activation_changed"):
            self.assertIs(False, value[field])


@unittest.skipUnless(NATIVE_REPORT_IO, NATIVE_IO_SKIP)
class FixtureNativeIoReportTests(FixtureExecutionReportSupport, unittest.TestCase):
    """Actual POSIX no-follow/dir-fd fixtures; no reader/publisher substitution."""

    def test_retained_source_missing_even_when_current_original_matches_is_rejected(self):
        fixture = self.make_run()
        fixture.materialize()
        binding = fixture.selection["original_inputs"][0]
        original = fixture.root / binding["original"]["path"]
        original.parent.mkdir(parents=True)
        original.write_bytes(fixture.originals[binding["original"]["path"]])
        (fixture.root / binding["retained"]["path"]).unlink()
        self.assertEqual(2, self.call(fixture)[0])
        self.assertFalse(fixture.output.exists())

    def test_opaque_evidence_tampering_is_detected_without_body_projection(self):
        fixture = self.make_run()
        fixture.materialize()
        (fixture.root / (fixture.run + "/cases/rule-0/positive/process.json")).write_bytes(b"OPAQUE_INERT_TAMPER")
        self.assertEqual(2, self.call(fixture)[0])
        self.assertFalse(fixture.output.exists())

    def test_missing_explicit_selection_never_searches_for_alternative_receipts(self):
        fixture = self.make_run()
        fixture.materialize()
        (fixture.root / fixture.selection_path).unlink()
        self.assertEqual(2, self.call(fixture)[0])
        self.assertFalse(fixture.output.exists())

    def test_symlink_leaf_ancestor_and_input_root_rejected(self):
        for variant in ("leaf", "ancestor", "root"):
            with self.subTest(variant=variant):
                fixture = self.make_run()
                fixture.materialize()
                if variant == "leaf":
                    path = fixture.root / fixture.selection_path
                    target = fixture.base / "selection-copy.json"
                    path.rename(target)
                    path.symlink_to(target)
                    arguments = {}
                elif variant == "ancestor":
                    path = fixture.root / "build"
                    target = fixture.base / "moved-build"
                    path.rename(target)
                    path.symlink_to(target, target_is_directory=True)
                    arguments = {}
                else:
                    path = fixture.base / "linked-root"
                    path.symlink_to(fixture.root, target_is_directory=True)
                    arguments = {"input-root": str(path)}
                self.assertEqual(2, self.call(fixture, **arguments)[0])
                self.assertFalse(fixture.output.exists())

    @unittest.skipUnless(hasattr(os, "mkfifo"), "Actual FIFO fixture API unavailable; not an emulated native pass")
    def test_hardlinked_leaf_and_nonregular_fifo_fail_without_blocking(self):
        for variant in ("hardlink", "fifo"):
            with self.subTest(variant=variant):
                fixture = self.make_run()
                fixture.materialize()
                path = fixture.root / fixture.selection_path
                if variant == "hardlink":
                    os.link(path, fixture.base / "hardlink.json")
                else:
                    path.unlink()
                    os.mkfifo(path)
                self.assertEqual(2, self.call(fixture)[0])
                self.assertFalse(fixture.output.exists())

    def test_file_total_count_json_and_output_budgets_are_bounded(self):
        for limit, value in (("MAX_FILE", 8), ("MAX_TOTAL", 8), ("MAX_FILES", 1), ("MAX_JSON", 8), ("MAX_OUTPUT", 8)):
            with self.subTest(limit=limit):
                fixture = self.make_run()
                fixture.materialize()
                with mock.patch.object(report, limit, value):
                    self.assertEqual(2, self.call(fixture)[0])
                self.assertFalse(fixture.output.exists())

    def test_exclusive_output_refuses_existing_leaf_or_input_tree(self):
        for variant in ("existing", "symlink", "inside-input"):
            with self.subTest(variant=variant):
                fixture = self.make_run()
                fixture.materialize()
                arguments = {}
                sentinel = fixture.base / "sentinel"
                sentinel.write_bytes(b"preserve sentinel")
                if variant == "existing":
                    fixture.output.write_bytes(b"preserve existing output")
                elif variant == "symlink":
                    fixture.output.symlink_to(sentinel)
                else:
                    arguments = {"output": str(fixture.root / "must-not-create.json")}
                self.assertEqual(2, self.call(fixture, **arguments)[0])
                self.assertEqual(b"preserve sentinel", sentinel.read_bytes())
                if variant == "existing":
                    self.assertEqual(b"preserve existing output", fixture.output.read_bytes())
                self.assertFalse((fixture.root / "must-not-create.json").exists())

    def test_exclusive_output_readback_and_only_owned_output_cleanup(self):
        fixture = self.make_run()
        fixture.materialize()
        real_write = report.os.write
        def corrupt(fd, data):
            return real_write(fd, b"x" * len(data))
        with mock.patch.object(report.os, "write", side_effect=corrupt):
            self.assertEqual(2, self.call(fixture)[0])
        self.assertFalse(fixture.output.exists())
        self.assertTrue((fixture.root / fixture.selection_path).exists())

    def test_late_input_drift_rejects_and_withdraws_only_new_output(self):
        fixture = self.make_run()
        fixture.materialize()
        original = report.ReadSet.recheck
        calls = []
        def change_on_output(reader):
            calls.append(None)
            if len(calls) == 2:
                (fixture.root / fixture.selection_path).write_bytes(b"changed after report calculation")
            return original(reader)
        with mock.patch.object(report.ReadSet, "recheck", change_on_output):
            self.assertEqual(2, self.call(fixture)[0])
        self.assertEqual(2, len(calls))
        self.assertFalse(fixture.output.exists())

    def test_physical_output_parent_alias_rejected_without_creating_file(self):
        fixture = self.make_run()
        fixture.materialize()
        reader = report.ReadSet(str(fixture.root))
        self.addCleanup(reader.close)
        with mock.patch.object(report, "open_directory", return_value=(os.dup(reader.fd), [reader.directories[""]])):
            with self.assertRaises(report.ReportError):
                report.write_report(reader, str(fixture.output), {"qualified": False})
        self.assertFalse(fixture.output.exists())
        self.assertFalse((fixture.root / fixture.output.name).exists())

    def test_readset_rejects_unavailable_and_alias_file_identities(self):
        fixture = self.make_run()
        fixture.materialize()
        reader = report.ReadSet(str(fixture.root))
        self.addCleanup(reader.close)
        first = {"path": fixture.selection_path, "sha256": fixture.selection_sha}
        reader.read(first)
        first_id = reader.files[fixture.selection_path][1][0]
        original_identity = report.identity
        with mock.patch.object(report, "identity", side_effect=lambda info: first_id if stat.S_ISREG(info.st_mode) else original_identity(info)):
            with self.assertRaises(report.ReportError):
                reader.read(json.loads((fixture.root / fixture.selection_path).read_bytes())["inputs"]["source_before"])
        with self.assertRaises(report.ReportError):
            report.identity(type("Unavailable", (), {"st_ino": 0, "st_dev": 0})())

    def test_actual_posix_positive_report_keeps_real_input_and_publication_paths(self):
        fixture = self.make_run()
        value = self.accepted(fixture)
        self.assertEqual(fixture.base, fixture.base.resolve(strict=True))
        self.assertEqual("fixture_execution_checked", value["state"])
        self.assertEqual(3, value["summary"]["fixture_execution_checked_rules"])
        self.assertEqual(37, value["summary"]["checked_invocations"])
        self.assertGreater(value["evidence_read_set"]["files"], 113)
        self.assertEqual(0o600, stat.S_IMODE(fixture.output.stat().st_mode))
        for field in ("qualified", "release_qualified", "native_qualification", "coverage_ledger_updated", "activation_changed"):
            self.assertIs(False, value[field])

    def test_outer_input_and_output_aliases_are_not_silently_canonicalized_by_reporter(self):
        for target in ("input-root", "output"):
            with self.subTest(target=target):
                fixture = self.make_run()
                fixture.materialize()
                alias = fixture.base / "outer-alias"
                alias.symlink_to(fixture.base, target_is_directory=True)
                spelling = alias / ("inputs" if target == "input-root" else fixture.output.name)
                self.assertEqual(2, self.call(fixture, **{target: str(spelling)})[0])
                self.assertTrue(alias.is_symlink())
                self.assertTrue((fixture.root / fixture.selection_path).is_file())
                self.assertFalse(fixture.output.exists())


class SyntheticRootOpenReached(Exception):
    """An intercepted first open, never a returned or fabricated descriptor."""


class FixtureApplicabilityTests(unittest.TestCase):
    """Portable gates, including observed Windows absence; no native I/O claim."""

    @staticmethod
    def facade():
        opened = mock.Mock(side_effect=SyntheticRootOpenReached)
        return SimpleNamespace(O_RDONLY=0, O_DIRECTORY=1, O_NOFOLLOW=2,
                               open=opened, supports_dir_fd={opened})

    def test_new_owned_fixture_root_is_canonicalized_before_children_are_created(self):
        with tempfile.TemporaryDirectory(prefix="inert-cohort-root-spelling-") as temporary:
            actual = Path(temporary).resolve(strict=True)
            alias = actual.parent / (actual.name + "-synthetic-outer-alias")
            self.assertFalse(alias.exists())
            # Model a trusted /var -> /private/var outer spelling without
            # requiring host symlink privileges or changing production paths.
            with mock.patch.object(Path, "resolve", autospec=True, return_value=actual) as resolve:
                fixture = SyntheticRun(alias)
            resolve.assert_called_once_with(alias, strict=True)
            self.assertEqual(actual, fixture.base)
            self.assertEqual(actual / "inputs", fixture.root)
            self.assertEqual(actual / "report.json", fixture.output)
            self.assertTrue(fixture.root.is_dir())
            self.assertFalse(alias.exists())

    def test_each_missing_posix_capability_rejects_before_any_open(self):
        original_os, host_name = report.os, os.name
        for missing in ("O_NOFOLLOW", "O_DIRECTORY", "open_dir_fd"):
            with self.subTest(missing=missing):
                facade = self.facade()
                if missing == "open_dir_fd":
                    facade.supports_dir_fd.clear()
                else:
                    delattr(facade, missing)
                with mock.patch.object(report, "os", facade):
                    with self.assertRaisesRegex(report.ReportError, "^POSIX no-follow handles required$"):
                        report.open_directory("/synthetic/unopened")
                facade.open.assert_not_called()
        self.assertIs(original_os, report.os)
        self.assertEqual(host_name, os.name)

    def test_supported_capability_gate_reaches_exact_no_follow_root_open(self):
        facade = self.facade()
        with mock.patch.object(report, "os", facade):
            with self.assertRaises(SyntheticRootOpenReached):
                report.open_directory("/synthetic/unopened")
        facade.open.assert_called_once_with("/", facade.O_RDONLY | facade.O_DIRECTORY | facade.O_NOFOLLOW)

    def test_observed_host_gate_is_not_overridden_to_manufacture_windows_support(self):
        observed = report.os
        opened = mock.Mock(side_effect=SyntheticRootOpenReached)
        facade = SimpleNamespace(open=opened, supports_dir_fd={opened} if observed.open in observed.supports_dir_fd else set())
        for name in ("O_RDONLY", "O_DIRECTORY", "O_NOFOLLOW"):
            if hasattr(observed, name):
                setattr(facade, name, getattr(observed, name))
        supported = all(hasattr(observed, name) for name in ("O_NOFOLLOW", "O_DIRECTORY")) and \
            observed.open in observed.supports_dir_fd
        self.assertEqual(supported, NATIVE_REPORT_IO)
        self.assertEqual(observed.name == "nt" and not supported, WINDOWS_PORTABLE_SEMANTICS)
        # Copy only the actual capability observations and intercept first open.
        # Windows lacks the required capabilities: it must fail before opening.
        with mock.patch.object(report, "os", facade):
            if supported:
                with self.assertRaises(SyntheticRootOpenReached):
                    report.open_directory("/synthetic/unopened")
            else:
                with self.assertRaisesRegex(report.ReportError, "^POSIX no-follow handles required$"):
                    report.open_directory("/synthetic/unopened")
        if supported:
            opened.assert_called_once_with("/", facade.O_RDONLY | facade.O_DIRECTORY | facade.O_NOFOLLOW)
        else:
            opened.assert_not_called()
        self.assertIs(observed, report.os)

    def test_drive_and_unc_roots_fail_closed_in_real_cli_before_directory_io(self):
        roots = ("C:\\synthetic\\input", "C:/synthetic/input", "\\\\server\\share\\synthetic")
        for root in roots:
            with self.subTest(root=root):
                args = {"input-root": root, "run-evidence": "selection.json", "expect-run-evidence-sha256": "1" * 64,
                        "expect-source-digest": "sha256:" + "2" * 64, "expect-context-digest": "sha256:" + "3" * 64,
                        "expect-provider-version": "synthetic", "current-source-digest": "sha256:" + "4" * 64,
                        "output": "/synthetic/unopened-report.json"}
                argv = [part for key, value in args.items() for part in ("--" + key, value)]
                output, error = io.StringIO(), io.StringIO()
                with redirect_stdout(output), redirect_stderr(error), \
                        mock.patch.object(report, "open_directory", side_effect=AssertionError(
                            "native directory I/O reached for an unsupported root")) as opened:
                    result = report.main(argv)
                self.assertEqual(2, result)
                self.assertEqual("", output.getvalue())
                self.assertEqual("fixture-execution report rejected: absolute path required\n", error.getvalue())
                opened.assert_not_called()


if __name__ == "__main__":
    unittest.main()
