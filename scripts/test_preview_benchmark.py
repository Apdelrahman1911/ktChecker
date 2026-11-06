"""Synthetic accounting and fully in-process transport doubles; never start a JVM.

These controls are not benchmark observations or host/coverage qualification.
All synthetic certificates are test data, not production admission authority.
"""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import uuid

import preview_benchmark as accounting
import run_preview_example as driver
from test_run_preview_example import HAS_GUARD, provider_document


SECRET = "Z" * 43
PRIVATE = "private-owner-reason-and-source-marker"
POLICY = "sha256:" + "5" * 64
TOOLCHAIN = "sha256:" + "1" * 64
CLOCK = "cccccccc-cccc-cccc-cccc-cccccccccccc"


def scenario(*, warm=False, schedule="concurrent", measured=1):
    row = {"id": "synthetic-inert", "warmthRequest": "persistent_broker_history" if warm else "fresh_broker_resident_dependencies",
           "schedule": schedule, "baselineRounds": 1 if warm else 0, "measuredRounds": measured,
           "workerCount": 2, "filesPerPatch": 1, "runTimeoutSeconds": 600, "trialTimeoutSeconds": 240}
    return accounting.select_scenario({"schema": accounting.SCHEMA, "scenarios": [row]}, row["id"])


def client_document(patch):
    binding = {"ownerId": "owner-" + CLOCK, "workspaceId": "example-project", "worktreeId": "shared-overlay",
               "clientId": patch.client_id, "sessionId": "session-" + patch.client_id,
               "lineageId": "task-" + patch.client_id, "generation": 1}
    config_binding = {key: binding[key] for key in ("ownerId", "workspaceId", "worktreeId", "lineageId")}
    revision = {"revision": 0, "generationId": None, "documentDigest": accounting.digest("absent")}
    snapshot = {"snapshotId": accounting.digest([patch.path, patch.source]), "ownerId": binding["ownerId"],
                "workspaceId": binding["workspaceId"], "worktreeId": binding["worktreeId"], "captureKind": "immutable_overlay",
                "parentSnapshotId": None, "unknownInputs": [],
                "files": [{"path": patch.path, "digest": patch.source_digest, "size": len(patch.source.encode())}]}
    prepared = {"contractVersion": driver.CONTRACT, "binding": binding, "patchId": patch.patch_id,
                "idempotencyKey": patch.idempotency_key, "coverageScope": "explicit-rule-subset-not-full-release",
                "executionAuthorized": True, "profiles": {},
                "input": {"snapshot": snapshot, "materializedRoot": PRIVATE, "requiredRuleIds": [driver.RULE],
                          "changedPaths": [patch.path], "baselineSnapshotId": None,
                          "context": {"scope": driver.scope_for(patch.client_id), "compilationFiles": [patch.path],
                                      "compilerVersion": "2.4.21", "fullProject": False,
                                      "configurationDigest": POLICY, "modelDigest": accounting.digest("model"),
                                      "dependencyDigest": accounting.digest("dependencies"), "toolchainDigest": TOOLCHAIN}},
                "configuration": {"schemaVersion": 1, "binding": config_binding, "registryDigest": accounting.digest("registry"),
                                  "revisionToken": {"binding": config_binding, "policyDigest": POLICY,
                                                    "shared": dict(revision), "local": dict(revision)},
                                  "sharedDocument": PRIVATE, "localDocument": PRIVATE, "capturedAtEpochMillis": 123}}
    registration = {"contractVersion": driver.CONTRACT, "binding": binding,
                    "client": {"clientId": patch.client_id, "token": SECRET}, "adapter": {"clientId": patch.client_id, "token": SECRET}}
    return {"prepared": prepared, "registration": registration}


def result_document(trial, *, complete=False, finding=None):
    patch = trial.patch
    if finding is None:
        finding = patch.expected == "finding"
    rows = [{"ruleId": driver.RULE, "providerId": driver.COMPILER, "upstreamId": driver.UPSTREAM,
             "context": driver.scope_for(patch.client_id), "fingerprint": accounting.digest([patch.source, "finding"]),
             "diagnosis": PRIVATE, "title": PRIVATE, "whyItMatters": PRIVATE, "disposition": "open", "severity": "error",
             "location": {"path": patch.path, "fileDigest": patch.source_digest, "line": 2, "column": 0, "endLine": 2, "endColumn": 0}}] if finding else []
    return {"providerId": "coordinator", "status": "complete" if complete else "incomplete", "findings": rows,
            "coverage": [{"ruleId": driver.RULE, "context": driver.scope_for(patch.client_id), "required": True, "depth": "build",
                          "state": "findings" if complete and finding else "passed" if complete else "failed" if finding else "unknown",
                          "provenance": "executed", "certificateId": accounting.digest("synthetic-certificate") if complete else None,
                          "implemented": complete, "tested": complete, "qualified": complete}],
            "reasons": [] if complete else [PRIVATE], "rawDiagnostics": [],
            "metrics": {"elapsedMillis": 9, "cpuMillis": 3, "peakRssBytes": None, "outputBytes": 96, "private": SECRET}}


def status_document(trial, *, state="incomplete", complete=False, finding=None, result=True):
    return {"contractVersion": driver.CONTRACT, "reviewId": str(uuid.UUID(int=trial.patch.round * 10 + (1 if trial.patch.client_id == "agent-a" else 2))),
            "clientId": trial.patch.client_id, "patchId": trial.patch.patch_id, "sourceSnapshotId": trial.join["snapshotId"],
            "sequence": trial.patch.round, "state": state, "cancelled": state == "cancelled",
            "reasons": [] if complete else [PRIVATE],
            "result": result_document(trial, complete=complete, finding=finding) if result else None}


def ledger_document(selected=None):
    return accounting.Ledger(selected or scenario(), 0, CLOCK, driver.RULE, driver.COMPILER, driver.UPSTREAM, driver.scope_for)


def begin_trial(trial, start=1_000_000):
    trial.stamp("patchComplete", start)
    trial.stamp("captureStarted", start + 1_000_000)
    trial.stamp("captureCommitted", start + 2_000_000)
    trial.bind(client_document(trial.patch), start + 3_000_000)
    trial.stamp("submitStarted", start + 10_000_000)


def accept_trial(trial, start=1_000_000):
    begin_trial(trial, start)
    trial.observe(status_document(trial, state="queued", result=False), start + 11_000_000, "review.submit")


class ScenarioTests(unittest.TestCase):
    def test_closed_bounded_explicit_scenario_selection(self):
        selected = scenario(warm=True, measured=2)
        self.assertEqual(6, len(accounting.patches(selected)))
        self.assertEqual([2, 2, 2], [len(batch) for batch in accounting.batches(selected, accounting.patches(selected))])
        self.assertEqual([1, 1], [len(batch) for batch in accounting.batches(scenario(schedule="sequential"), accounting.patches(scenario()))])
        document = {"schema": accounting.SCHEMA, "scenarios": [accounting.asdict(scenario())]}
        for key, value in (("workerCount", 3), ("filesPerPatch", 2), ("measuredRounds", 0), ("measuredRounds", 9),
                           ("baselineRounds", 1), ("trialTimeoutSeconds", True), ("runTimeoutSeconds", 901),
                           ("warmthRequest", []), ("schedule", {}), ("extra", PRIVATE)):
            altered = copy.deepcopy(document)
            altered["scenarios"][0][key] = value
            with self.subTest(key=key, value_type=type(value).__name__), self.assertRaises(accounting.BenchmarkError):
                accounting.select_scenario(altered, "synthetic-inert")
        with self.assertRaises(accounting.BenchmarkError):
            accounting.select_scenario(document, "missing")
        with self.assertRaises(accounting.BenchmarkError):
            accounting.select_scenario(dict(document, scenarios=document["scenarios"] * 2), "synthetic-inert")

    def test_warm_history_changes_source_and_keeps_both_expected_classes(self):
        plan = accounting.patches(scenario(warm=True, measured=2))
        self.assertEqual(6, len({patch.source_digest for patch in plan}))
        self.assertEqual(6, len({patch.patch_id for patch in plan}))
        self.assertEqual(2, sum(patch.phase == "baseline" for patch in plan))
        self.assertEqual(3, sum(patch.expected == "clean" for patch in plan))
        self.assertIn("class Broken2 : Contract2", plan[2].source)
        self.assertNotIn("Broken1", plan[2].source)


class AccountingTests(unittest.TestCase):
    def test_ack_includes_capture_but_submit_round_trip_is_separate_and_never_useful(self):
        ledger = ledger_document()
        trial = next(iter(ledger.trials.values()))
        accept_trial(trial)
        row = trial.public()
        self.assertEqual(11, row["timings"]["T_ack"]["millis"])
        self.assertEqual(1, row["timings"]["submitAckRoundTrip"]["millis"])
        self.assertEqual("durable_submit_return_observed", row["admission"])
        self.assertIsNone(row["timings"]["T_useful"]["millis"])
        self.assertFalse(row["requiredAnalysisComplete"])
        self.assertIsNone(row["timings"]["acceptedEventObserved"]["millis"])

    def test_partial_fresh_finding_is_useful_without_required_completion(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial, state="running"), 20_000_000, "review.status")
        trial.observe(status_document(trial), 30_000_000, "review.status")
        row = trial.public()
        self.assertEqual(19, row["timings"]["T_useful"]["millis"])
        self.assertEqual(29, row["timings"]["failedIncompleteTerminalOutcome"]["millis"])
        self.assertIsNone(row["timings"]["T_required"]["millis"])
        self.assertEqual("not_completed", row["timings"]["T_required"]["state"])
        self.assertFalse(row["requiredAnalysisComplete"])

    def test_verified_clean_required_assessment_counts_as_useful_not_as_a_finding(self):
        ledger = ledger_document()
        trial = list(ledger.trials.values())[1]
        accept_trial(trial)
        trial.observe(status_document(trial, state="complete", complete=True), 20_000_000, "review.status")
        row = trial.public()
        self.assertTrue(row["requiredAnalysisComplete"])
        self.assertEqual("successful_required_clean_assessment", row["usefulKind"])
        self.assertEqual(19, row["timings"]["T_useful"]["millis"])
        self.assertEqual(row["timings"]["T_required"], row["timings"]["T_useful"])
        self.assertIsNone(row["timings"]["firstUsefulFinding"]["millis"])
        self.assertIsNone(row["timings"]["failedIncompleteTerminalOutcome"]["millis"])
        self.assertEqual(1, ledger.report(30_000_000)["counts"]["measured"]["useful"])

    def test_clean_pending_or_incomplete_never_wins_with_empty_findings(self):
        trial = list(ledger_document().trials.values())[1]
        accept_trial(trial)
        trial.observe(status_document(trial, state="running", finding=False), 20_000_000, "review.status")
        self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])
        trial.observe(status_document(trial, finding=False), 30_000_000, "review.status")
        self.assertEqual("clean_assessment_incomplete", trial.public()["usefulnessOutcome"])
        self.assertFalse(trial.public()["requiredAnalysisComplete"])

    def test_required_success_without_expected_diagnosis_is_still_a_usefulness_miss(self):
        ledger = ledger_document()
        trial = next(iter(ledger.trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial, state="complete", complete=True, finding=False), 20_000_000, "review.status")
        self.assertTrue(trial.public()["requiredAnalysisComplete"])
        self.assertEqual("miss", trial.public()["usefulnessOutcome"])
        self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])
        counts = ledger.report(30_000_000)["counts"]["measured"]
        self.assertEqual(2, counts["planned"])
        self.assertEqual(1, counts["requiredComplete"])
        self.assertEqual(0, counts["useful"])
        self.assertEqual(1, counts["usefulnessOutcomes"]["miss"])

    def test_job_or_review_id_only_and_accepted_event_are_not_findings(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        accepted = status_document(trial, state="accepted", result=False)
        event = {"eventId": CLOCK, "sequence": 1, "payloadDigest": accounting.digest(accepted), "kind": "accepted"}
        trial.observe(accepted, 20_000_000, "review.events", event=event)
        row = trial.public()
        self.assertIsNone(row["timings"]["T_useful"]["millis"])
        self.assertEqual(19, row["timings"]["acceptedEventObserved"]["millis"])
        self.assertEqual("queued", trial.last_state)

    def test_exact_scope_depth_certificate_and_nonempty_required_plan_are_all_required(self):
        for mutation in ("coverage_empty", "coverage_duplicate", "scope", "rule", "depth", "required", "certificateId",
                         "provenance", "implemented", "tested", "qualified", "state", "kernel_incomplete", "provider_incomplete",
                         "review_reason", "provider_reason", "raw_warning"):
            with self.subTest(mutation=mutation):
                trial = list(ledger_document().trials.values())[1]
                accept_trial(trial)
                value = status_document(trial, state="complete", complete=True)
                coverage = value["result"]["coverage"][0]
                if mutation == "coverage_empty":
                    value["result"]["coverage"] = []
                elif mutation == "coverage_duplicate":
                    value["result"]["coverage"].append(dict(coverage))
                elif mutation == "scope":
                    coverage["context"] = driver.scope_for("agent-a")
                elif mutation == "rule":
                    coverage["ruleId"] = "other"
                elif mutation == "kernel_incomplete":
                    value["state"] = "incomplete"
                elif mutation == "provider_incomplete":
                    value["result"]["status"] = "incomplete"
                elif mutation == "review_reason":
                    value["reasons"] = [PRIVATE]
                elif mutation == "provider_reason":
                    value["result"]["reasons"] = [PRIVATE]
                elif mutation == "raw_warning":
                    value["result"]["rawDiagnostics"] = [{"severity": "warning", "message": PRIVATE}]
                else:
                    coverage[mutation] = {"depth": "syntax", "required": False, "certificateId": None,
                                          "provenance": "not_executed", "state": "unknown"}.get(mutation, False)
                trial.observe(value, 20_000_000, "review.status")
                self.assertFalse(trial.public()["requiredAnalysisComplete"])
                self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])

    def test_unknown_or_blocking_raw_severity_cannot_certify_clean_or_erase_admission(self):
        diagnostics = [{"message": PRIVATE}] + [
            {"message": PRIVATE, "severity": severity}
            for severity in ("error", "warning", "fatal", "INFO", "", None, False, True, 0, 1.0, [], {}, ["info"], {"level": "info"})
        ] + [None, PRIVATE]
        for via in ("review.submit", "review.status"):
            for diagnostic in diagnostics:
                with self.subTest(via=via, diagnostic=diagnostic):
                    ledger = ledger_document()
                    trial = list(ledger.trials.values())[1]
                    if via == "review.submit":
                        begin_trial(trial)
                    else:
                        accept_trial(trial)
                    value = status_document(trial, state="complete", complete=True)
                    value["result"]["rawDiagnostics"] = [{"message": PRIVATE, "severity": "info"}, diagnostic]
                    trial.observe(value, 20_000_000, via)
                    row = trial.public()
                    self.assertEqual("durable_submit_return_observed", row["admission"])
                    self.assertIsNotNone(row["timings"]["T_ack"]["millis"])
                    self.assertFalse(row["lastResultFacts"]["requiredComplete"])
                    self.assertFalse(row["requiredAnalysisComplete"])
                    self.assertEqual("clean_assessment_incomplete", row["usefulnessOutcome"])
                    self.assertIsNone(row["timings"]["T_required"]["millis"])
                    self.assertIsNone(row["timings"]["T_useful"]["millis"])
                    report = ledger.report(30_000_000)
                    counts = report["counts"]["measured"]
                    self.assertEqual((2, 1, 1, 0, 0), tuple(counts[key] for key in
                                     ("planned", "submitAttempts", "durableAcceptanceObserved", "requiredComplete", "useful")))
                    self.assertEqual(1, report["contention"]["maxUnsettledRequiredObligations"])
                    self.assertNotIn(PRIVATE, json.dumps(report))

    def test_empty_or_explicit_info_raw_diagnostics_preserve_valid_clean_completion(self):
        for diagnostics in ([], [{"message": PRIVATE, "severity": "info"}]):
            with self.subTest(diagnostics=diagnostics):
                ledger = ledger_document()
                trial = list(ledger.trials.values())[1]
                accept_trial(trial)
                value = status_document(trial, state="complete", complete=True)
                value["result"]["rawDiagnostics"] = diagnostics
                trial.observe(value, 20_000_000, "review.status")
                row = trial.public()
                self.assertTrue(row["requiredAnalysisComplete"])
                self.assertEqual("observed", row["usefulnessOutcome"])
                self.assertEqual((19, 19), (row["timings"]["T_required"]["millis"], row["timings"]["T_useful"]["millis"]))
                self.assertIsNone(row["timings"]["firstUsefulFinding"]["millis"])
                counts = ledger.report(30_000_000)["counts"]["measured"]
                self.assertEqual((1, 1), (counts["requiredComplete"], counts["useful"]))

    def test_unresolved_raw_severity_keeps_fresh_finding_useful_without_required_success(self):
        for diagnostic in ({"message": PRIVATE}, {"message": PRIVATE, "severity": "warning"},
                           {"message": PRIVATE, "severity": {"level": "info"}}):
            with self.subTest(diagnostic=diagnostic):
                ledger = ledger_document()
                trial = next(iter(ledger.trials.values()))
                accept_trial(trial)
                value = status_document(trial, state="complete", complete=True)
                value["result"]["rawDiagnostics"] = [diagnostic]
                trial.observe(value, 20_000_000, "review.status")
                row = trial.public()
                self.assertEqual("durable_submit_return_observed", row["admission"])
                self.assertEqual("fresh_actionable_finding", row["usefulKind"])
                self.assertEqual(19, row["timings"]["T_useful"]["millis"])
                self.assertEqual(1, row["lastResultFacts"]["freshActionableFindingCount"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertIsNone(row["timings"]["T_required"]["millis"])
                counts = ledger.report(30_000_000)["counts"]["measured"]
                self.assertEqual((0, 1), (counts["requiredComplete"], counts["useful"]))

    def test_snapshot_or_config_or_revision_changes_cannot_be_silently_rejoined(self):
        for mutation in ("policy", "binding", "document", "revision", "source", "baseline"):
            trial = next(iter(ledger_document().trials.values()))
            trial.stamp("patchComplete", 1)
            client = client_document(trial.patch)
            config = client["prepared"]["configuration"]
            if mutation == "policy":
                config["revisionToken"]["policyDigest"] = accounting.digest("different")
            elif mutation == "binding":
                config["binding"] = dict(config["binding"], lineageId="different")
            elif mutation == "document":
                config["revisionToken"]["local"]["documentDigest"] = PRIVATE
            elif mutation == "revision":
                config["revisionToken"]["local"]["revision"] = True
            elif mutation == "source":
                client["prepared"]["input"]["snapshot"]["files"][0]["digest"] = accounting.digest("old")
            else:
                client["prepared"]["input"]["baselineSnapshotId"] = PRIVATE
            with self.subTest(mutation=mutation), self.assertRaises(accounting.BenchmarkError):
                trial.bind(client, 2)
            self.assertIsNone(trial.join)

    def test_exact_config_revision_joins_are_retained_and_unknown_model_is_not_zero(self):
        trial = list(ledger_document().trials.values())[1]
        trial.stamp("patchComplete", 1)
        client = client_document(trial.patch)
        row = client["prepared"]["configuration"]["revisionToken"]["local"]
        row.update(revision=9, generationId=PRIVATE)
        client["prepared"]["input"]["context"]["modelDigest"] = None
        trial.bind(client, 2)
        trial.stamp("submitStarted", 3)
        trial.observe(status_document(trial, state="complete", complete=True), 4, "review.submit")
        self.assertEqual(9, trial.join["configuration"]["local"]["revision"])
        self.assertEqual(accounting.digest(PRIVATE), trial.join["configuration"]["local"]["generationIdDigest"])
        self.assertIsNone(trial.join["identities"]["modelDigest"])
        self.assertFalse(trial.public()["requiredAnalysisComplete"])
        self.assertNotIn(PRIVATE, json.dumps(trial.public()))

    def test_old_wrong_scope_wrong_provider_or_out_of_range_findings_do_not_become_useful(self):
        for field, value in (("fileDigest", accounting.digest("old")), ("path", "agent-b/Main.kt"), ("line", 0), ("endLine", 99),
                             ("providerId", "other"), ("upstreamId", "OTHER"), ("context", driver.scope_for("agent-b"))):
            trial = next(iter(ledger_document().trials.values()))
            accept_trial(trial)
            status = status_document(trial)
            finding = status["result"]["findings"][0]
            target = finding if field in {"providerId", "upstreamId", "context"} else finding["location"]
            target[field] = value
            with self.subTest(field=field), self.assertRaises(accounting.BenchmarkError):
                trial.observe(status, 20_000_000, "review.status")
            self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])
            self.assertEqual("durable_submit_return_observed", trial.public()["admission"])

    def test_nonactionable_or_unrelated_warning_is_not_a_usefulness_win(self):
        for field, value in (("disposition", "suppressed"), ("diagnosis", ""), ("fingerprint", None), ("ruleId", "unrelated"),
                             ("severity", "info")):
            trial = next(iter(ledger_document().trials.values()))
            accept_trial(trial)
            status = status_document(trial)
            status["result"]["findings"][0][field] = value
            trial.observe(status, 20_000_000, "review.status")
            self.assertEqual("miss", trial.public()["usefulnessOutcome"])

    def test_cancelled_without_result_is_terminal_but_not_success(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial, state="cancelled", result=False), 20_000_000, "review.status")
        self.assertTrue(trial.terminal)
        self.assertEqual("cancelled", trial.public()["outcome"])
        self.assertEqual(19, trial.public()["timings"]["terminalOutcome"]["millis"])
        self.assertIsNone(trial.public()["timings"]["T_required"]["millis"])

    def test_lost_submit_return_keeps_uncertain_admission_and_the_entire_denominator(self):
        ledger = ledger_document(scenario(warm=True, measured=2))
        first = next(iter(ledger.trials.values()))
        begin_trial(first)
        ledger.abort("timeout", "preview_example_transport_timeout", 20_000_000)
        report = ledger.report(21_000_000)
        self.assertEqual("outcome_unknown", report["trials"][0]["admission"])
        self.assertEqual("outcome_unknown", report["trials"][0]["timings"]["T_ack"]["state"])
        self.assertEqual(6, len(report["trials"]))
        self.assertEqual(1, report["counts"]["baseline"]["unknownAdmissionOutcomes"])
        self.assertEqual(4, report["counts"]["measured"]["outcomes"]["not_started"])
        self.assertEqual(1, report["counts"]["baseline"]["outcomes"]["timeout"])

    def test_valid_ack_is_not_erased_by_invalid_result_metrics(self):
        trial = next(iter(ledger_document().trials.values()))
        begin_trial(trial)
        status = status_document(trial)
        status["result"]["metrics"]["cpuMillis"] = -1
        with self.assertRaises(accounting.BenchmarkError):
            trial.observe(status, 20_000_000, "review.submit")
        self.assertEqual("durable_submit_return_observed", trial.public()["admission"])
        self.assertIsNotNone(trial.public()["timings"]["T_ack"]["millis"])
        self.assertFalse(trial.terminal)

    def test_resource_null_and_actual_zero_are_distinct_and_bad_counters_fail_closed(self):
        for cpu, rss in ((None, None), (0, 0), (3, None)):
            trial = next(iter(ledger_document().trials.values()))
            accept_trial(trial)
            status = status_document(trial)
            status["result"]["metrics"].update(cpuMillis=cpu, peakRssBytes=rss)
            trial.observe(status, 20_000_000, "review.status")
            measured = trial.public()["lastResultFacts"]["providerReportedMetrics"]
            self.assertEqual(cpu, measured["cpuMillis"])
            self.assertEqual(rss, measured["peakRssBytes"])
        for key, value in (("cpuMillis", True), ("peakRssBytes", -1), ("elapsedMillis", None), ("outputBytes", 2**63)):
            trial = next(iter(ledger_document().trials.values()))
            accept_trial(trial)
            status = status_document(trial)
            status["result"]["metrics"][key] = value
            with self.subTest(key=key), self.assertRaises(accounting.BenchmarkError):
                trial.observe(status, 20_000_000, "review.status")

    def test_duplicate_and_historical_progress_do_not_regress_terminal_or_double_count_cpu(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial), 20_000_000, "review.status")
        trial.observe(status_document(trial, state="running"), 30_000_000, "review.events")
        trial.observe(status_document(trial), 40_000_000, "duplicate review.submit")
        row = trial.public()
        self.assertEqual("incomplete", row["outcome"])
        self.assertEqual(19, row["timings"]["terminalOutcome"]["millis"])
        self.assertEqual(3, row["lastResultFacts"]["providerReportedMetrics"]["cpuMillis"])
        self.assertIsNone(row["lastResultFacts"]["providerReportedMetrics"]["peakRssBytes"])

    def test_historical_complete_event_cannot_erase_current_cancelled_obligation(self):
        trial = list(ledger_document().trials.values())[1]
        accept_trial(trial)
        trial.observe(status_document(trial, state="cancelled", result=False), 20_000_000, "review.status")
        trial.observe(status_document(trial, state="complete", complete=True), 30_000_000, "review.events")
        row = trial.public()
        self.assertEqual("pending", row["outcome"])
        self.assertEqual("cancelled", row["lastScalarState"])
        self.assertEqual("pending_after_terminal_event", row["currentStatusConfirmation"]["state"])
        self.assertFalse(row["requiredAnalysisComplete"])
        self.assertIsNone(row["timings"]["T_required"]["millis"])
        self.assertIsNone(row["timings"]["T_useful"]["millis"])
        trial.observe(status_document(trial, state="cancelled", result=False), 40_000_000, "review.status")
        self.assertEqual("cancelled", trial.public()["outcome"])
        self.assertFalse(trial.required_complete)
        self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])

    def test_late_terminal_events_invalidate_current_completion_until_confirmation(self):
        for state in ("cancelled", "incomplete"):
            with self.subTest(state=state):
                ledger = ledger_document()
                trial = list(ledger.trials.values())[1]
                accept_trial(trial)
                trial.observe(status_document(trial, state="complete", complete=True), 20_000_000, "review.status")
                later = status_document(trial, state=state, result=state != "cancelled")
                trial.observe(later, 30_000_000, "review.events")
                row = trial.public()
                self.assertFalse(trial.terminal)
                self.assertTrue(trial.confirmation_pending)
                self.assertEqual("pending", row["outcome"])
                self.assertEqual("complete", row["lastScalarState"])
                self.assertTrue(row["lastResultFacts"]["requiredComplete"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertEqual(0, ledger.report(31_000_000)["counts"]["measured"]["requiredComplete"])
                self.assertEqual(1, ledger.report(31_000_000)["contention"]["maxUnsettledRequiredObligations"])
                self.assertEqual((19, 19), (row["timings"]["T_required"]["millis"], row["timings"]["T_useful"]["millis"]))
                trial.observe(later, 40_000_000, "review.status")
                row = trial.public()
                self.assertTrue(trial.terminal)
                self.assertFalse(trial.confirmation_pending)
                self.assertEqual(state, row["outcome"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertEqual((19, 19), (row["timings"]["T_required"]["millis"], row["timings"]["T_useful"]["millis"]))
                self.assertEqual(29, row["timings"]["failedIncompleteTerminalOutcome"]["millis"])

    def test_failed_current_confirmation_keeps_terminal_event_pending_and_admission(self):
        for failure in ("timeout", "invalid_metrics", "trace_limit"):
            with self.subTest(failure=failure):
                kind = "timeout" if failure == "timeout" else "aborted"
                ledger = ledger_document()
                trial = list(ledger.trials.values())[1]
                accept_trial(trial)
                complete = status_document(trial, state="complete", complete=True)
                trial.observe(complete, 20_000_000, "review.status")
                trial.observe(status_document(trial), 30_000_000, "review.events")
                if failure != "timeout":
                    malformed = copy.deepcopy(complete)
                    if failure == "invalid_metrics":
                        malformed["result"]["metrics"]["cpuMillis"] = -1
                    else:
                        ledger.trace_count = accounting.MAX_TRACE_ENTRIES
                    with self.assertRaises(accounting.BenchmarkError) as error:
                        trial.observe(malformed, 40_000_000, "review.status")
                    self.assertEqual("preview_benchmark_" + failure, error.exception.code)
                code = "preview_example_transport_timeout" if kind == "timeout" else "preview_benchmark_" + failure
                ledger.abort(kind, code, 50_000_000)
                report = ledger.report(51_000_000)
                row = report["trials"][1]
                self.assertTrue(trial.confirmation_pending)
                self.assertEqual("durable_submit_return_observed", row["admission"])
                self.assertEqual(kind, row["outcome"])
                self.assertEqual("complete", row["lastScalarState"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertEqual(19, row["timings"]["T_required"]["millis"])
                self.assertEqual(19, row["timings"]["T_useful"]["millis"])
                self.assertEqual(1, report["counts"]["measured"]["durableAcceptanceObserved"])
                self.assertEqual(0, report["counts"]["measured"]["requiredComplete"])
                self.assertEqual(1, report["counts"]["measured"]["outcomes"][kind])
                self.assertEqual(2, report["counts"]["measured"]["planned"])

    def test_repeated_terminal_events_need_new_confirmation_without_moving_first_timings(self):
        trial = list(ledger_document().trials.values())[1]
        accept_trial(trial)
        complete = status_document(trial, state="complete", complete=True)
        trial.observe(complete, 20_000_000, "review.status")
        trial.observe(complete, 30_000_000, "review.events")
        trial.observe(complete, 40_000_000, "review.status")
        self.assertTrue(trial.required_complete)
        trial.observe(status_document(trial), 50_000_000, "review.events")
        trial.observe(status_document(trial, state="running", result=False), 55_000_000, "review.events")
        self.assertTrue(trial.confirmation_pending)
        self.assertFalse(trial.required_complete)
        self.assertEqual("complete", trial.last_state)
        trial.observe(complete, 60_000_000, "review.status")
        self.assertTrue(trial.required_complete)
        row = trial.public()
        self.assertEqual((19, 19), (row["timings"]["T_required"]["millis"], row["timings"]["T_useful"]["millis"]))
        self.assertEqual({"state": "scalar_observed", "lastScalarObservedMonotonicNanos": 60_000_000,
                          "lastTerminalEventObservedMonotonicNanos": 50_000_000}, row["currentStatusConfirmation"])

    def test_complete_event_is_retained_then_current_status_confirms_clean_usefulness(self):
        trial = list(ledger_document().trials.values())[1]
        accept_trial(trial)
        complete = status_document(trial, state="complete", complete=True)
        trial.observe(complete, 20_000_000, "review.events")
        self.assertFalse(trial.terminal)
        self.assertIsNone(trial.public()["timings"]["T_useful"]["millis"])
        self.assertEqual(19, trial.public()["timings"]["terminalOutcome"]["millis"])
        trial.observe(complete, 30_000_000, "review.status")
        self.assertTrue(trial.terminal)
        self.assertEqual(29, trial.public()["timings"]["T_required"]["millis"])
        self.assertEqual(29, trial.public()["timings"]["T_useful"]["millis"])

    def test_incomplete_history_remains_an_unsettled_obligation_not_a_completed_job(self):
        ledger = ledger_document(scenario(warm=True))
        first, second = list(ledger.trials.values())[:2]
        accept_trial(first)
        accept_trial(second, 13_000_000)
        first.observe(status_document(first), 30_000_000, "review.status")
        second.observe(status_document(second), 31_000_000, "review.status")
        report = ledger.report(40_000_000)
        self.assertEqual(2, report["contention"]["maxAcceptedWithoutTerminalObservation"])
        self.assertEqual(2, report["contention"]["maxUnsettledRequiredObligations"])
        self.assertEqual(28, report["contention"]["oldestUnsettledAcceptedObligationObservedAgeMillis"])
        self.assertFalse(report["warmth"]["baselineRequiredComplete"])
        self.assertEqual("not_established", report["warmth"]["warmProjectPrerequisite"])
        self.assertEqual("unavailable", report["contention"]["simultaneousProviderExecution"])

    def test_tokens_context_and_whole_tree_resources_are_not_fabricated_or_leaked(self):
        ledger = ledger_document()
        trial = next(iter(ledger.trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial), 20_000_000, "review.status")
        report = ledger.report(30_000_000)
        self.assertEqual("not_attempted", report["contextDelivery"]["state"])
        self.assertIsNone(report["contextDelivery"]["T_contextMillis"])
        self.assertEqual("unavailable", report["tokens"]["state"])
        self.assertIsNone(report["tokens"]["inputTokens"])
        self.assertEqual("not_measured", report["wholeBrokerProcessTreeResources"]["state"])
        self.assertIsNone(report["wholeBrokerProcessTreeResources"]["cpuMillis"])
        self.assertFalse(report["releaseQualified"])
        self.assertNotIn(PRIVATE, json.dumps(report))
        self.assertNotIn(SECRET, json.dumps(report))

    def test_repeated_unchanged_status_polls_are_counted_without_unbounded_trace_growth(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        status = status_document(trial, state="queued", result=False)
        for timestamp in range(20_000_000, 20_000_100):
            trial.observe(status, timestamp, "review.status")
        self.assertEqual(2, len(trial.trace))
        self.assertEqual(100, trial.poll_observations)
        self.assertEqual(99, trial.unchanged_observations)
        self.assertEqual(20_000_099, trial.times["lastObserved"])

    def test_wrong_review_and_backward_or_boolean_clocks_are_rejected(self):
        trial = next(iter(ledger_document().trials.values()))
        accept_trial(trial)
        for timestamp in (1, True):
            with self.assertRaises(accounting.BenchmarkError):
                trial.observe(status_document(trial), timestamp, "review.status")
        status = status_document(trial)
        status["reviewId"] = CLOCK
        with self.assertRaises(accounting.BenchmarkError):
            trial.observe(status, 20_000_000, "review.status")


class DeadlineAndAdmissionTests(unittest.TestCase):
    def controller(self, ledger):
        # Data-only controller shell: no private directory, subprocess or JVM.
        controller = driver.BenchmarkController.__new__(driver.BenchmarkController)
        controller.scenario = ledger.scenario
        controller.ledger = ledger
        controller.active = []
        controller.run_deadline_ns = ledger.scenario.runTimeoutSeconds * 1_000_000_000
        controller.sessions = []
        controller.rpc = mock.Mock()
        controller.root = mock.Mock()
        return controller

    def test_terminal_active_trial_keeps_original_deadline_through_event_drain(self):
        ledger = ledger_document()
        trial = next(iter(ledger.trials.values()))
        accept_trial(trial)
        trial.observe(status_document(trial, state="complete", complete=True), 20_000_000, "review.status")
        controller = self.controller(ledger)
        controller.active = [trial]
        original = trial.times["patchComplete"] + ledger.scenario.trialTimeoutSeconds * 1_000_000_000
        self.assertTrue(trial.terminal)
        self.assertEqual(original, controller.deadline_ns())
        controller.active = []
        self.assertEqual(controller.run_deadline_ns, controller.deadline_ns())

    def test_expired_historical_confirmation_does_not_get_a_new_deadline_or_rpc_attempt(self):
        ledger = ledger_document(scenario(warm=True))
        trial = list(ledger.trials.values())[1]
        accept_trial(trial)
        trial.observe(status_document(trial, state="complete", complete=True), 20_000_000, "review.status")
        controller = self.controller(ledger)
        original = trial.times["patchComplete"] + ledger.scenario.trialTimeoutSeconds * 1_000_000_000
        trial.observe(status_document(trial, state="cancelled", result=False), original + 1, "review.events")
        self.assertNotIn(trial, controller.active)
        self.assertEqual(original, controller.deadline_ns())
        with mock.patch.object(driver.time, "monotonic_ns", return_value=original + 2), self.assertRaises(driver.ExampleError) as error:
            controller.poll_current(trial)
        self.assertEqual("preview_example_benchmark_deadline", error.exception.code)
        controller.rpc.call.assert_not_called()
        self.assertTrue(trial.confirmation_pending)
        self.assertEqual(1_000_000, trial.times["patchComplete"])
        self.assertEqual(19, trial.public()["timings"]["T_required"]["millis"])
        self.assertFalse(trial.required_complete)

    def test_prior_unsettled_count_at_submit_includes_historical_pending_confirmation(self):
        ledger = ledger_document(scenario(warm=True))
        prior, later = list(ledger.trials.values())[1:3]
        accept_trial(prior)
        prior.observe(status_document(prior, state="complete", complete=True), 20_000_000, "review.status")
        prior.observe(status_document(prior, state="cancelled", result=False), 30_000_000, "review.events")
        later.stamp("patchComplete", 40_000_000)
        later.bind(client_document(later.patch), 45_000_000)
        controller = self.controller(ledger)
        controller.active = [later]
        controller.rpc.call.return_value = status_document(later, state="queued", result=False)
        with mock.patch.object(driver.time, "monotonic_ns", side_effect=[50_000_000, 51_000_000, 52_000_000]):
            controller.submit(later)
        self.assertTrue(prior.last_facts["requiredComplete"])
        self.assertFalse(prior.required_complete)
        self.assertEqual(1, later.public()["priorAcceptedReviewCountAtSubmit"])
        self.assertEqual(1, later.public()["priorUnsettledRequiredObligationCountAtSubmit"])
        self.assertEqual("durable_submit_return_observed", later.public()["admission"])
        controller.rpc.call.assert_called_once()


class InProcessRuntime:
    """Implements just the real owner's/RPC's shapes. No executable is invoked."""

    def __init__(self, controller, *, fail=None):
        self.controller = controller
        self.fail = fail
        self.prepared = {}
        self.snapshots = {}
        self.files = {}
        self.statuses = {}
        self.polls = {}
        self.event_rows = {name: [] for name in driver.SOURCES}
        self.sequence = {name: 0 for name in driver.SOURCES}
        self.original_submits = self.duplicate_submits = self.closes = self.starts = 0

    def owner(self, *args):
        root = self.controller.root
        method = args[0]
        options = {args[index]: args[index + 1] for index in range(1, len(args) - 1) if args[index].startswith("--") and args[index] != "--authorize-execution"}
        if method == "providers":
            return provider_document()
        if method == "register":
            patch = next(patch for patch in self.controller.ledger.plan if patch.client_id == options["--client"])
            root.write_json(Path(options["--out"]).name, client_document(patch)["registration"])
            return {"contractVersion": driver.CONTRACT}
        if method == "overlay":
            value = root.read_json(Path(options["--request"]).name)
            patch = next(patch for patch in self.controller.ledger.plan if patch.patch_id == value["patchId"])
            assert value["changes"] == [{"operation": "put", "path": patch.path, "content": patch.source}]
            assert value["expectedHeadRevision"] == self.controller.base_revision
            self.files[patch.path] = {"path": patch.path, "digest": patch.source_digest, "size": len(patch.source.encode())}
            client = client_document(patch)
            snapshot = client["prepared"]["input"]["snapshot"]
            snapshot.update(parentSnapshotId=value["baseSnapshotId"], files=list(self.files.values()))
            snapshot["snapshotId"] = accounting.digest([snapshot["files"], value["baseSnapshotId"]])
            client["prepared"]["input"]["baselineSnapshotId"] = value["baseSnapshotId"]
            self.snapshots[snapshot["snapshotId"]] = client
            return {"manifest": snapshot, "headRevision": value["expectedHeadRevision"] + 1}
        if method == "prepare":
            value = root.read_json(Path(options["--request"]).name)
            client = self.snapshots[value["snapshotId"]]
            assert "--authorize-execution" in args
            assert client["prepared"]["patchId"] == value["patchId"]
            root.write_json(Path(options["--out"]).name, client["prepared"])
            self.prepared[value["patchId"]] = client
            return {"contractVersion": driver.CONTRACT, "installedProviders": provider_document()}
        raise AssertionError("unexpected owner command")

    def process(self, _command):
        self.starts += 1
        runtime = self

        class Transport:
            def statistics(self):
                return {"stdinBytes": 10, "stdoutBytes": 20, "stderrBytes": 0}

            def close(self):
                runtime.closes += 1
                if runtime.fail == "cleanup":
                    raise driver.ExampleError("cleanup_failed")

            def cleanup_statistics(self):
                return {"observedMillis": 0, "forcedHelperLossGuaranteed": False}

        return Transport()

    def append_event(self, trial, status, kind):
        name = trial.patch.client_id
        self.sequence[name] += 1
        payload = json.dumps(status, separators=(",", ":"))
        self.event_rows[name].append({"contractVersion": driver.CONTRACT, "eventId": str(uuid.uuid4()), "reviewId": status["reviewId"],
                                     "clientId": name, "patchId": trial.patch.patch_id, "sourceSnapshotId": trial.join["snapshotId"],
                                     "generation": 1, "sequence": self.sequence[name], "kind": kind, "deliveryRequired": True,
                                     "payload": payload, "payloadDigest": "sha256:" + hashlib.sha256(payload.encode()).hexdigest()})

    def ndjson(self, _transport):
        runtime = self

        class Rpc:
            def call(self, method, params, *, deadline):
                assert deadline > 0
                if method == "doctor":
                    return {"contractVersion": driver.CONTRACT, "command": "doctor", "installedProvidersInspected": True,
                            "installedProviders": provider_document()}
                name = params["credential"]["clientId"]
                if method == "review.submit":
                    patch_id = params["patchId"]
                    trial = next(item for item in runtime.controller.ledger.trials.values() if item.patch.patch_id == patch_id)
                    if patch_id not in runtime.statuses:
                        runtime.original_submits += 1
                        runtime.statuses[patch_id] = status_document(trial, state="queued", result=False)
                        runtime.polls[patch_id] = 0
                        runtime.append_event(trial, status_document(trial, state="accepted", result=False), "accepted")
                        if runtime.fail == "lost_submit":
                            raise driver.ExampleError("transport_timeout")
                    else:
                        runtime.duplicate_submits += 1
                    return copy.deepcopy(runtime.statuses[patch_id])
                if method == "review.status":
                    if runtime.fail == "timeout":
                        raise driver.ExampleError("transport_timeout")
                    trial = next(item for item in runtime.controller.ledger.trials.values() if item.review_id == params["reviewId"])
                    patch_id = trial.patch.patch_id
                    runtime.polls[patch_id] += 1
                    state = "running" if runtime.polls[patch_id] == 1 else "incomplete"
                    value = status_document(trial, state=state)
                    # Current status reads do not create another terminal event
                    # for an unchanged result in the real kernel.
                    if value != runtime.statuses[patch_id]:
                        runtime.append_event(trial, value, "progress" if state == "running" else "result")
                    runtime.statuses[patch_id] = value
                    return copy.deepcopy(value)
                if method == "review.events":
                    offset = 0 if params.get("cursor") is None else int(params["cursor"].split("-")[1])
                    rows = runtime.event_rows[name][offset:offset + 2]
                    end = offset + len(rows)
                    if runtime.fail == "bad_event" and len(rows) > 1:
                        rows = copy.deepcopy(rows)
                        rows[-1]["payloadDigest"] = accounting.digest("wrong")
                    return {"contractVersion": driver.CONTRACT, "events": rows, "cursor": "cursor-" + str(end),
                            "hasMore": end < len(runtime.event_rows[name])}
                # ACK/observe/gate/cancel are deliberately unsupported, not faked.
                raise AssertionError("unexpected RPC method")

        return Rpc()


class LateTerminalRuntime(InProcessRuntime):
    """Emit a late terminal event after a valid scalar success, without a process."""

    def __init__(self, controller, *, late_state, historical=False, confirmation_failure=None):
        super().__init__(controller)
        self.late_state = late_state
        self.historical = historical
        self.confirmation_failure = confirmation_failure
        self.target = list(controller.ledger.trials.values())[1]  # First clean review.
        self.emitted = False
        self.first_success_ns = None
        self.confirmations = []

    def ndjson(self, transport):
        delegate = super().ndjson(transport)
        runtime = self

        class Rpc:
            def call(self, method, params, *, deadline):
                if method == "review.status":
                    trial = next(item for item in runtime.controller.ledger.trials.values() if item.review_id == params["reviewId"])
                    patch_id = trial.patch.patch_id
                    runtime.polls[patch_id] += 1
                    if runtime.emitted and trial is runtime.target:
                        runtime.confirmations.append({"active": trial in runtime.controller.active,
                                                      "pending": trial.confirmation_pending,
                                                      "lastScalarState": trial.last_state, "deadline": deadline})
                        if runtime.confirmation_failure == "timeout":
                            raise driver.ExampleError("transport_timeout")
                        value = status_document(trial, state=runtime.late_state, result=runtime.late_state != "cancelled")
                        if runtime.confirmation_failure == "invalid":
                            value["sourceSnapshotId"] = accounting.digest("wrong-confirmation-snapshot")
                        return copy.deepcopy(value)
                    value = status_document(trial, state="complete", complete=True)
                    if value != runtime.statuses[patch_id]:
                        runtime.append_event(trial, value, "result")
                    runtime.statuses[patch_id] = value
                    return copy.deepcopy(value)
                if method == "review.events" and not runtime.emitted and params["credential"]["clientId"] == runtime.target.patch.client_id:
                    target = runtime.target
                    later_batch = runtime.controller.active[0].patch.round > target.patch.round
                    if target.last_state == "complete" and (not runtime.historical or later_batch):
                        runtime.first_success_ns = target.times["required"]
                        value = status_document(target, state=runtime.late_state, result=runtime.late_state != "cancelled")
                        runtime.statuses[target.patch.patch_id] = value
                        runtime.append_event(target, value, "cancelled" if runtime.late_state == "cancelled" else "result")
                        runtime.emitted = True
                return delegate.call(method, params, deadline=deadline)

        return Rpc()


@unittest.skipUnless(HAS_GUARD, "POSIX private controller support required")
class ControllerTests(unittest.TestCase):
    def collect(self, selected, *, fail=None, runtime_factory=None):
        with tempfile.TemporaryDirectory(prefix="ktchecker-inert-benchmark-") as temporary:
            # Resolve only this test-generated directory (/var may alias
            # /private/var on macOS). Production owner paths stay no-follow.
            base = Path(temporary).resolve()
            (base / "compiler" / "bin").mkdir(parents=True)
            (base / "compiler" / "bin" / "java").write_text("synthetic; never executed")
            with driver.PrivateDirectory(base / "new") as root:
                controller = driver.BenchmarkController(base / "distribution", root, base / "compiler", selected)
                runtime = InProcessRuntime(controller, fail=fail) if runtime_factory is None else runtime_factory(controller)
                with mock.patch.object(controller, "call", side_effect=runtime.owner), \
                        mock.patch.object(driver, "BoundedProcess", side_effect=runtime.process), \
                        mock.patch.object(driver, "NdjsonClient", side_effect=runtime.ndjson), \
                        mock.patch.object(driver.subprocess, "Popen", side_effect=AssertionError("process forbidden")), \
                        mock.patch.object(driver.time, "sleep", return_value=None):
                    report = controller.run()
                self.assertEqual(report, root.read_json("benchmark-observations.json"))
                self.assertTrue((root.path / "benchmark-plan.json").is_file())
                self.assertNotIn(SECRET, json.dumps(report))
                self.assertNotIn(PRIVATE, json.dumps(report))
                return report, runtime, controller

    def test_fresh_sequential_starts_a_broker_per_patch_and_keeps_old_reviews(self):
        report, runtime, _ = self.collect(scenario(schedule="sequential"))
        self.assertEqual("collected", report["collectionState"])
        self.assertEqual((2, 2, 2, 2), (runtime.starts, runtime.closes, runtime.original_submits, runtime.duplicate_submits))
        self.assertEqual([1, 2], [row["brokerSession"] for row in report["trials"]])
        self.assertEqual(1, report["trials"][1]["priorAcceptedReviewCountAtSubmit"])
        self.assertEqual(1, report["trials"][1]["priorUnsettledRequiredObligationCountAtSubmit"])

    def test_requested_warm_concurrent_history_uses_actual_new_captures_in_one_broker(self):
        report, runtime, controller = self.collect(scenario(warm=True, measured=2))
        self.assertEqual("collected", report["collectionState"])
        self.assertEqual((1, 1, 6, 6), (runtime.starts, runtime.closes, runtime.original_submits, runtime.duplicate_submits))
        self.assertEqual(6, len({row["inputJoin"]["snapshotId"] for row in report["trials"]}))
        self.assertEqual(2, report["counts"]["baseline"]["outcomes"]["incomplete"])
        self.assertEqual(4, report["counts"]["measured"]["outcomes"]["incomplete"])
        self.assertEqual(2, report["counts"]["measured"]["useful"])
        self.assertEqual(0, report["counts"]["measured"]["requiredComplete"])
        self.assertEqual(6, report["contention"]["maxUnsettledRequiredObligations"])
        self.assertEqual(2, report["contention"]["maxAcceptedWithoutTerminalObservation"])
        self.assertEqual({"agent-a": 9, "agent-b": 9}, {name: value["sequence"] for name, value in controller.cursors.items()})
        for row in report["trials"]:
            self.assertEqual(3, len([item for item in row["trace"] if "event" in item]))
            self.assertEqual("observed", row["timings"]["acceptedEventObserved"]["state"])
            self.assertLessEqual(row["observationTimestampsMonotonicNanos"]["patchComplete"], row["observationTimestampsMonotonicNanos"]["captureStarted"])
            self.assertLess(row["timings"]["submitAckRoundTrip"]["millis"], row["timings"]["T_ack"]["millis"])
        self.assertEqual("unknown", report["warmth"]["compilerWarmth"])
        self.assertFalse(report["warmth"]["baselineRequiredComplete"])

    def test_timeout_preserves_both_accepted_trials_and_all_unstarted_measured_trials(self):
        report, runtime, _ = self.collect(scenario(warm=True, measured=2), fail="timeout")
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual(2, report["counts"]["baseline"]["durableAcceptanceObserved"])
        self.assertEqual(2, report["counts"]["baseline"]["outcomes"]["timeout"])
        self.assertEqual(4, report["counts"]["measured"]["outcomes"]["not_started"])
        self.assertEqual(6, len(report["trials"]))
        self.assertEqual(1, runtime.closes)

    def test_lost_submit_response_is_unknown_and_never_automatically_retried(self):
        report, runtime, _ = self.collect(scenario(warm=True), fail="lost_submit")
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual(1, runtime.original_submits)
        self.assertEqual(0, runtime.duplicate_submits)
        self.assertEqual("outcome_unknown", report["trials"][0]["admission"])
        self.assertEqual(1, report["counts"]["baseline"]["unknownAdmissionOutcomes"])
        self.assertEqual(0, report["counts"]["baseline"]["durableAcceptanceObserved"])

    def test_corrupt_later_event_does_not_partially_advance_page_cursor(self):
        report, _, controller = self.collect(scenario(), fail="bad_event")
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual("preview_example_invalid_event_digest", report["collectorFailure"]["code"])
        self.assertEqual(0, controller.cursors["agent-a"]["sequence"])
        self.assertIsNone(controller.cursors["agent-a"]["cursor"])
        self.assertFalse(any("event" in entry for row in report["trials"] for entry in row["trace"]))

    def test_cleanup_failure_retains_observations_without_reporting_collection_success(self):
        report, runtime, _ = self.collect(scenario(), fail="cleanup")
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual("failed", report["brokerSessions"][0]["cleanup"]["state"])
        self.assertEqual(2, report["counts"]["measured"]["outcomes"]["incomplete"])
        self.assertEqual(1, runtime.closes)

    def test_late_active_cancel_or_incomplete_event_is_confirmed_before_batch_return(self):
        for state in ("cancelled", "incomplete"):
            with self.subTest(state=state):
                report, runtime, _ = self.collect(scenario(), runtime_factory=lambda controller:
                                                  LateTerminalRuntime(controller, late_state=state))
                row = report["trials"][1]
                self.assertEqual("collected", report["collectionState"])
                self.assertEqual(state, row["outcome"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertEqual(1, report["counts"]["measured"]["requiredComplete"])
                self.assertEqual(1, len(runtime.confirmations))
                self.assertEqual((True, True, "complete"), tuple(runtime.confirmations[0][key] for key in ("active", "pending", "lastScalarState")))
                self.assertEqual(runtime.first_success_ns, row["observationTimestampsMonotonicNanos"]["required"])
                self.assertEqual(row["timings"]["T_required"], row["timings"]["T_useful"])
                self.assertEqual("scalar_observed", row["currentStatusConfirmation"]["state"])
                self.assertGreaterEqual(row["currentStatusConfirmation"]["lastScalarObservedMonotonicNanos"],
                                        row["currentStatusConfirmation"]["lastTerminalEventObservedMonotonicNanos"])

    def test_historical_terminal_event_is_reconfirmed_and_later_admission_keeps_it_unsettled(self):
        report, runtime, _ = self.collect(scenario(warm=True, measured=2), runtime_factory=lambda controller:
                                          LateTerminalRuntime(controller, late_state="cancelled", historical=True))
        row = report["trials"][1]
        self.assertEqual("collected", report["collectionState"])
        self.assertEqual("cancelled", row["outcome"])
        self.assertFalse(row["requiredAnalysisComplete"])
        self.assertEqual(1, report["counts"]["baseline"]["requiredComplete"])
        self.assertEqual(4, report["counts"]["measured"]["requiredComplete"])
        self.assertEqual(1, len(runtime.confirmations))
        self.assertEqual((False, True, "complete"), tuple(runtime.confirmations[0][key] for key in ("active", "pending", "lastScalarState")))
        self.assertEqual(runtime.first_success_ns, row["observationTimestampsMonotonicNanos"]["required"])
        self.assertEqual(1, report["trials"][4]["priorUnsettledRequiredObligationCountAtSubmit"])
        self.assertEqual(1, runtime.starts)
        self.assertEqual(1, runtime.closes)

    def test_failed_historical_confirmation_retains_all_admissions_and_first_success_history(self):
        for failure in ("timeout", "invalid"):
            with self.subTest(failure=failure):
                report, runtime, _ = self.collect(scenario(warm=True, measured=2), runtime_factory=lambda controller:
                                                  LateTerminalRuntime(controller, late_state="incomplete", historical=True,
                                                                      confirmation_failure=failure))
                row = report["trials"][1]
                self.assertEqual("failed", report["collectionState"])
                self.assertEqual("preview_example_transport_timeout" if failure == "timeout" else "preview_benchmark_status_join_mismatch",
                                 report["collectorFailure"]["code"])
                self.assertEqual("timeout" if failure == "timeout" else "aborted", row["outcome"])
                self.assertEqual("pending_after_terminal_event", row["currentStatusConfirmation"]["state"])
                self.assertFalse(row["requiredAnalysisComplete"])
                self.assertEqual("durable_submit_return_observed", row["admission"])
                self.assertEqual(runtime.first_success_ns, row["observationTimestampsMonotonicNanos"]["required"])
                self.assertEqual(6, len(report["trials"]))
                self.assertEqual(2, report["counts"]["baseline"]["durableAcceptanceObserved"])
                self.assertEqual(2, report["counts"]["measured"]["durableAcceptanceObserved"])
                self.assertEqual(2, report["counts"]["measured"]["outcomes"]["not_started"])
                self.assertEqual(0, report["counts"]["measured"]["unknownAdmissionOutcomes"])
                self.assertEqual(1, len(runtime.confirmations))
                self.assertEqual(1, runtime.closes)


if __name__ == "__main__":
    unittest.main()
