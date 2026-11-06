"""Pure accounting for run_preview_example's opt-in, unqualified smoke scenarios.

No process, host, provider, clock, filesystem or token-counter implementation lives
here. All times are observations from the ONE controller monotonic clock. A JSON
receipt cannot grant model visibility, policy authority or release qualification.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import re


SCHEMA = "ktchecker/preview-benchmark-scenarios-1"
REPORT_SCHEMA = "ktchecker/preview-benchmark-observations-1"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
TERMINAL = {"complete", "incomplete", "cancelled"}
SUCCESS = {"passed", "findings", "not_applicable"}
COVERAGE_STATES = SUCCESS | {"unknown", "failed", "unsupported", "unavailable", "unqualified", "pending", "cancelled"}
MAX_TRACE_ENTRIES = 4096


class BenchmarkError(ValueError):
    """Only static collector-owned codes, never input values, cross this boundary."""

    def __init__(self, code: str):
        self.code = "preview_benchmark_" + code
        super().__init__(self.code)


def check(condition: bool, code: str) -> None:
    if not condition:
        raise BenchmarkError(code)


def is_digest(value: object) -> bool:
    return isinstance(value, str) and DIGEST.fullmatch(value) is not None


def digest(value: object) -> str:
    # This is a collector join, NOT a claim to reproduce Kotlin's analysisKey.
    data = json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False,
                      separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(data).hexdigest()


@dataclass(frozen=True)
class Scenario:
    id: str
    warmthRequest: str
    schedule: str
    baselineRounds: int
    measuredRounds: int
    workerCount: int
    filesPerPatch: int
    runTimeoutSeconds: int
    trialTimeoutSeconds: int


def select_scenario(document: dict, selected: str) -> Scenario:
    check(isinstance(document, dict) and set(document) == {"schema", "scenarios"} and
          document["schema"] == SCHEMA and isinstance(document["scenarios"], list) and
          1 <= len(document["scenarios"]) <= 8, "invalid_scenarios")
    scenarios = []
    fields = set(Scenario.__dataclass_fields__)
    for row in document["scenarios"]:
        check(isinstance(row, dict) and set(row) == fields, "invalid_scenario")
        check(isinstance(row["id"], str) and re.fullmatch(r"[a-z][a-z0-9-]{0,63}", row["id"]) is not None and
              isinstance(row["warmthRequest"], str) and
              row["warmthRequest"] in {"fresh_broker_resident_dependencies", "persistent_broker_history"} and
              isinstance(row["schedule"], str) and row["schedule"] in {"sequential", "concurrent"}, "invalid_scenario")
        check(all(type(row[key]) is int for key in fields - {"id", "warmthRequest", "schedule"}) and
              row["filesPerPatch"] == 1 and 1 <= row["workerCount"] <= 2 and
              1 <= row["measuredRounds"] <= 8 and
              1 <= row["trialTimeoutSeconds"] <= row["runTimeoutSeconds"] <= 900, "invalid_scenario")
        check((row["warmthRequest"] == "fresh_broker_resident_dependencies" and row["baselineRounds"] == 0 and
               row["measuredRounds"] == 1) or
              (row["warmthRequest"] == "persistent_broker_history" and row["baselineRounds"] == 1), "invalid_scenario")
        scenarios.append(Scenario(**row))
    check(len({item.id for item in scenarios}) == len(scenarios), "duplicate_scenario")
    matches = [item for item in scenarios if item.id == selected]
    check(len(matches) == 1, "scenario_not_found")
    return matches[0]


@dataclass(frozen=True)
class Patch:
    trial_id: str
    phase: str
    round: int
    client_id: str
    patch_id: str
    idempotency_key: str
    source: str

    @property
    def expected(self) -> str:
        return "finding" if self.client_id == "agent-a" else "clean"

    @property
    def path(self) -> str:
        return self.client_id + "/Main.kt"

    @property
    def source_digest(self) -> str:
        return "sha256:" + hashlib.sha256(self.source.encode("utf-8")).hexdigest()


def patches(scenario: Scenario) -> list[Patch]:
    result = []
    for ordinal in range(scenario.baselineRounds + scenario.measuredRounds):
        phase = "baseline" if ordinal < scenario.baselineRounds else "measured"
        number = ordinal + 1
        for name in ("agent-a", "agent-b"):
            trial = phase + "-r" + str(number) + "-" + name
            # Each defect is newly named in the changed file, not an unrelated
            # warning carried unchanged from the warm-up/previous patch.
            source = (f"interface Contract{number} {{ fun answer(): Int }}\nclass Broken{number} : Contract{number}\n"
                      if name == "agent-a" else f"package independent\nfun unrelated{number}(): Int = {42 + number}\n")
            result.append(Patch(trial, phase, number, name, "benchmark-" + trial, "request-" + trial, source))
    return result


def batches(scenario: Scenario, plan: list[Patch]) -> list[list[Patch]]:
    width = 2 if scenario.schedule == "concurrent" else 1
    return [plan[index:index + width] for index in range(0, len(plan), width)]


def input_join(client: dict, patch: Patch, rule: str, scope: dict) -> dict:
    prepared = client["prepared"]
    binding, value = prepared["binding"], prepared["input"]
    context, snapshot = value["context"], value["snapshot"]
    check(value["requiredRuleIds"] == [rule] and context["scope"] == scope and
          context["compilationFiles"] == [patch.path] and value.get("changedPaths") == [patch.path], "input_plan_mismatch")
    check(prepared["patchId"] == patch.patch_id and prepared["idempotencyKey"] == patch.idempotency_key and
          binding["clientId"] == patch.client_id and is_digest(snapshot["snapshotId"]), "input_plan_mismatch")
    check(type(context.get("fullProject")) is bool and isinstance(snapshot.get("files"), list) and
          (value.get("baselineSnapshotId") is None or is_digest(value["baselineSnapshotId"])), "input_identity_invalid")
    own = [row for row in snapshot["files"] if isinstance(row, dict) and row.get("path") == patch.path]
    check(len(own) == 1 and own[0].get("digest") == patch.source_digest and type(own[0].get("size")) is int and
          own[0]["size"] == len(patch.source.encode("utf-8")), "source_join_mismatch")
    configuration = prepared.get("configuration")
    check(isinstance(configuration, dict) and type(configuration.get("schemaVersion")) is int and configuration["schemaVersion"] == 1 and
          is_digest(configuration.get("registryDigest")), "configuration_join_missing")
    revision = configuration.get("revisionToken")
    expected_binding = {key: binding[key] for key in ("ownerId", "workspaceId", "worktreeId", "lineageId")}
    check(isinstance(revision, dict) and configuration.get("binding") == revision.get("binding") == expected_binding and
          is_digest(revision.get("policyDigest")) and revision["policyDigest"] == context.get("configurationDigest"),
          "configuration_join_mismatch")
    layers = {}
    for layer in ("shared", "local"):
        row = revision.get(layer)
        check(isinstance(row, dict) and type(row.get("revision")) is int and 0 <= row["revision"] < 2**63 and
              is_digest(row.get("documentDigest")) and
              (row.get("generationId") is None or isinstance(row["generationId"], str)) and
              (row["revision"] == 0) == (row.get("generationId") is None), "configuration_revision_invalid")
        # Keep the exact revision join without publishing owner-controlled generation text.
        layers[layer] = {"revision": row["revision"], "documentDigest": row["documentDigest"],
                         "generationIdDigest": None if row.get("generationId") is None else digest(row["generationId"])}
    identities = {}
    for key in ("modelDigest", "dependencyDigest", "toolchainDigest", "configurationDigest"):
        item = context.get(key)
        check(item is None or is_digest(item), "input_identity_invalid")
        identities[key] = item
    return {"snapshotId": snapshot["snapshotId"], "baselineSnapshotId": value.get("baselineSnapshotId"),
            "changedFileDigest": patch.source_digest, "compilationScope": scope, "requiredRuleIds": [rule],
            "requiredDepth": "build", "fullProject": context["fullProject"], "identities": identities,
            "configuration": dict(layers, policyDigest=revision["policyDigest"], registryDigest=configuration["registryDigest"],
                                  revisionTokenDigest=digest(revision)),
            "bindingDigest": digest(binding), "preparedInputDigest": digest(value),
            "preparedProfilesDigest": None if prepared.get("profiles") is None else digest(prepared["profiles"]),
            "profilesIdentityState": "unavailable" if prepared.get("profiles") is None else "joined",
            "collectorDigestEncoding": "sorted-key compact UTF-8 JSON; not a kernel analysisKey or authority"}


def result_facts(status: dict, trial: "Trial") -> dict:
    value = status.get("result")
    empty = {"findingCount": None, "freshActionableFindingCount": 0, "requiredComplete": False,
             "coverageStates": [], "providerReportedMetrics": None, "reviewReasonCount": len(status["reasons"]),
             "analysisReasonCount": None}
    if value is None:
        return empty
    check(isinstance(value, dict) and isinstance(value.get("findings"), list) and isinstance(value.get("coverage"), list) and
          isinstance(value.get("reasons"), list) and all(isinstance(row, str) for row in value["reasons"]) and
          isinstance(value.get("rawDiagnostics"), list), "invalid_result")
    metrics = value.get("metrics")
    check(isinstance(metrics, dict), "invalid_metrics")
    safe_metrics = {}
    for field in ("elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"):
        number = metrics.get(field)
        check(field in metrics and (number is None and field in {"cpuMillis", "peakRssBytes"} or
              type(number) is int and 0 <= number < 2**63), "invalid_metrics")
        safe_metrics[field] = number
    fresh = 0
    expected, patch = trial.scope, trial.patch
    lines = patch.source.splitlines()
    for finding in value["findings"]:
        check(isinstance(finding, dict), "invalid_finding")
        if finding.get("ruleId") != trial.rule:
            continue
        location = finding.get("location")
        check(finding.get("providerId") == trial.provider and finding.get("upstreamId") == trial.upstream and
              finding.get("context") == expected and isinstance(location, dict) and
              location.get("path") == patch.path and location.get("fileDigest") == patch.source_digest,
              "unbound_finding")
        coordinates = [location.get(key) for key in ("line", "column", "endLine", "endColumn")]
        check(all(type(number) is int and 0 <= number <= 2_147_483_647 for number in coordinates) and
              1 <= coordinates[0] <= coordinates[2] <= len(lines) and
              (coordinates[0], coordinates[1]) <= (coordinates[2], coordinates[3]), "invalid_finding_range")
        check(coordinates[1] <= len(lines[coordinates[0] - 1].encode("utf-16-le")) // 2 and
              coordinates[3] <= len(lines[coordinates[2] - 1].encode("utf-16-le")) // 2, "invalid_finding_range")
        actionable = (finding.get("disposition") == "open" and finding.get("severity") in {"error", "warning"} and
                      is_digest(finding.get("fingerprint")) and isinstance(finding.get("diagnosis"), str) and
                      bool(finding["diagnosis"].strip()))
        # The closed corpus has its known diagnostic on the new class at line 2.
        if patch.expected == "finding" and coordinates[0] == coordinates[2] == 2 and actionable:
            fresh += 1
    coverage = value["coverage"]
    check(all(isinstance(row, dict) for row in coverage), "invalid_coverage")
    exact = (len(coverage) == 1 and coverage[0].get("ruleId") == trial.rule and
             coverage[0].get("context") == expected and coverage[0].get("required") is True and
             coverage[0].get("depth") == "build")
    passing = exact and all(row.get("state") in SUCCESS and row.get("provenance") in {"executed", "reused_with_proof"} and
                            is_digest(row.get("certificateId")) and row.get("implemented") is True and
                            row.get("tested") is True and row.get("qualified") is True for row in coverage)
    # Only explicit informational diagnostics are nonblocking. Missing/default
    # or unrecognized severity cannot establish required completion.
    raw_clear = all(isinstance(row, dict) and row.get("severity") == "info" for row in value["rawDiagnostics"])
    required = (status["state"] == "complete" and status["cancelled"] is False and value.get("status") == "complete" and
                not status["reasons"] and not value["reasons"] and passing and raw_clear and
                all(is_digest(item) for item in trial.join["identities"].values()) and
                all(row.get("ruleId") == trial.rule and row.get("context") == expected for row in value["findings"]))
    return {"findingCount": len(value["findings"]), "freshActionableFindingCount": fresh, "requiredComplete": bool(required),
            "coverageStates": [row.get("state") if isinstance(row.get("state"), str) and row["state"] in COVERAGE_STATES
                               else "unrecognized" for row in coverage], "providerReportedMetrics": safe_metrics,
            "reviewReasonCount": len(status["reasons"]), "analysisReasonCount": len(value["reasons"])}


class Trial:
    def __init__(self, ledger: "Ledger", patch: Patch, rule: str, provider: str, upstream: str, scope: dict):
        self.ledger, self.patch, self.rule, self.provider, self.upstream, self.scope = ledger, patch, rule, provider, upstream, scope
        self.times: dict[str, int] = {}
        self.client = self.join = self.review_id = self.review_sequence = None
        self.last_state = None
        self.last_facts = None
        self.confirmation_pending = False
        self.last_scalar_observed_ns = self.last_terminal_event_ns = None
        self.failure = None
        self.poll_observations = self.unchanged_observations = 0
        self.last_status_key = None
        self.trace = []
        self.duplicate_round_trip_ms = None
        self.broker_session = None
        self.prior_accepted = self.prior_unsettled = None

    @property
    def terminal(self) -> bool:
        # A fetched terminal event may postdate the last scalar observation.
        # Its payload is not current authority: require a later valid scalar
        # response even when the last scalar state was already terminal.
        return not self.confirmation_pending and self.last_state in TERMINAL

    @property
    def required_complete(self) -> bool:
        return not self.confirmation_pending and self.last_facts is not None and self.last_facts["requiredComplete"]

    def stamp(self, name: str, now: int) -> None:
        check(type(now) is int and now >= self.ledger.origin_ns and
              (not self.times or now >= max(self.times.values())) and name not in self.times, "clock_or_stage_invalid")
        self.times[name] = now

    def bind(self, client: dict, now: int) -> None:
        check("patchComplete" in self.times and self.client is None, "binding_stage_invalid")
        joined = input_join(client, self.patch, self.rule, self.scope)
        self.stamp("prepared", now)
        self.client, self.join = client, joined

    def validate_header(self, status: dict, *, accepted_event: bool = False) -> None:
        check(self.client is not None and isinstance(status, dict) and status.get("contractVersion") == "ktchecker/preview-1" and
              status.get("clientId") == self.patch.client_id and status.get("patchId") == self.patch.patch_id and
              status.get("sourceSnapshotId") == self.join["snapshotId"] and
              isinstance(status.get("reviewId"), str) and UUID.fullmatch(status["reviewId"]) is not None and
              type(status.get("sequence")) is int and status["sequence"] >= 1 and
              isinstance(status.get("state"), str) and
              status["state"] in TERMINAL | {"queued", "running"} | ({"accepted"} if accepted_event else set()) and
              type(status.get("cancelled")) is bool and isinstance(status.get("reasons"), list) and
              all(isinstance(row, str) for row in status["reasons"]), "status_join_mismatch")
        if self.review_id is not None:
            check((status["reviewId"], status["sequence"]) == (self.review_id, self.review_sequence), "status_join_mismatch")
        check(status["state"] != "accepted" or status.get("result") is None, "accepted_result_invalid")

    def inspect(self, status: dict, *, accepted_event: bool = False) -> dict:
        self.validate_header(status, accepted_event=accepted_event)
        return result_facts(status, self)

    def observe(self, status: dict, now: int, via: str, *, event: dict | None = None) -> None:
        check(via in {"review.submit", "duplicate review.submit", "review.status", "review.events"} and
              "submitStarted" in self.times and type(now) is int and now >= max(self.times.values()), "observation_stage_invalid")
        self.validate_header(status, accepted_event=via == "review.events")
        if self.review_id is None:
            check(via == "review.submit", "admission_not_observed")
            prior = [trial for trial in self.ledger.trials.values() if trial.review_id is not None]
            check(all(trial.review_id != status["reviewId"] for trial in prior) and
                  all(trial.review_sequence < status["sequence"] for trial in prior if trial.patch.client_id == self.patch.client_id),
                  "admission_identity_reused")
            self.review_id, self.review_sequence = status["reviewId"], status["sequence"]
            self.times["ack"] = now  # Kernel.submit returns AFTER its FULL-sync ledger transaction commits.
        # A corrupt result must not erase a separately valid observed admission.
        facts = result_facts(status, self)
        encoded = digest(status)
        unchanged = event is None and (encoded, via) == self.last_status_key
        check(unchanged or self.ledger.trace_count < MAX_TRACE_ENTRIES, "trace_limit")
        event_row = None if event is None else {key: event[key] for key in ("eventId", "sequence", "payloadDigest", "kind")}
        if event is not None and status["state"] == "accepted" and "acceptedEvent" not in self.times:
            self.times["acceptedEvent"] = now
        if status["state"] == "running" and "running" not in self.times:
            self.times["running"] = now  # Observation bound, NOT provider start or exact queue time.
        if facts["freshActionableFindingCount"] and "finding" not in self.times:
            self.times["finding"] = now
        current_complete = facts["requiredComplete"] and via != "review.events"
        if current_complete and "required" not in self.times:
            self.times["required"] = now
        if self.patch.expected == "clean" and facts["findingCount"] == 0 and current_complete and "clean" not in self.times:
            self.times["clean"] = now
        if status["state"] in TERMINAL and "terminal" not in self.times:
            self.times["terminal"] = now
        if status["state"] in TERMINAL and not facts["requiredComplete"] and "unsuccessfulTerminal" not in self.times:
            self.times["unsuccessfulTerminal"] = now
        # Keep the last scalar sample and first-success timings as history, but
        # never present them as current settlement after a terminal event. A
        # failed/malformed confirming response cannot reach this clearing path.
        if via != "review.events":
            self.last_state, self.last_facts = status["state"], facts
            self.last_scalar_observed_ns = now
            self.confirmation_pending = False
        elif status["state"] in TERMINAL:
            self.last_terminal_event_ns = now
            self.confirmation_pending = True
        self.times["lastObserved"] = now
        if via == "review.status":
            self.poll_observations += 1
        if unchanged:
            self.unchanged_observations += 1
        else:
            self.ledger.trace_count += 1
            row = {"observedMonotonicNanos": now, "via": via, "state": status["state"], "statusDigest": encoded,
                   "freshActionableFindingCount": facts["freshActionableFindingCount"], "requiredComplete": facts["requiredComplete"],
                   "currentCompletionMetricEligible": via != "review.events", "providerReportedMetrics": facts["providerReportedMetrics"],
                   "currentStatusConfirmationPending": self.confirmation_pending}
            if event_row is not None:
                row["event"] = event_row
            self.trace.append(row)
        self.last_status_key = (encoded, via)
        self.ledger.observe_contention(now)

    def metric(self, key: str, origin: str = "patchComplete") -> dict:
        if key in self.times and origin in self.times:
            return {"state": "observed", "millis": (self.times[key] - self.times[origin]) / 1e6}
        state = "not_started" if "patchComplete" not in self.times else "not_observed"
        if key == "ack" and "submitStarted" in self.times:
            state = "outcome_unknown"
        elif self.failure is not None:
            state = "censored_timeout" if self.failure["kind"] == "timeout" else "interrupted_observation"
        elif key == "required" and self.terminal:
            state = "not_completed"
        return {"state": state, "millis": None}

    def public(self) -> dict:
        useful = "finding" if self.patch.expected == "finding" else "clean"
        if self.review_id is None:
            admission = "outcome_unknown" if "submitStarted" in self.times else "not_submitted"
        else:
            admission = "durable_submit_return_observed"
        outcome = self.last_state if self.terminal else "not_started" if not self.times else "pending"
        if self.failure is not None and not self.terminal:
            outcome = self.failure["kind"]
        usefulness = "observed" if useful in self.times else "not_started" if not self.times else "not_observed"
        if self.terminal and useful not in self.times:
            usefulness = ("miss" if self.patch.expected == "finding" else
                          "unexpected_findings" if self.last_facts and self.last_facts["findingCount"] else "clean_assessment_incomplete")
        facts = self.last_facts
        return {"trialId": self.patch.trial_id, "phase": self.patch.phase, "round": self.patch.round,
                "clientId": self.patch.client_id, "patchId": self.patch.patch_id, "expected": self.patch.expected,
                "changedFileCount": 1, "brokerSession": self.broker_session, "inputJoin": self.join,
                "reviewId": self.review_id, "reviewSequence": self.review_sequence, "admission": admission,
                "outcome": outcome, "usefulnessOutcome": usefulness,
                "usefulKind": "fresh_actionable_finding" if useful == "finding" else "successful_required_clean_assessment",
                "requiredAnalysisComplete": self.required_complete,
                "lastScalarState": self.last_state,
                "currentStatusConfirmation": {"state": "pending_after_terminal_event" if self.confirmation_pending else
                                               "scalar_observed" if self.last_state is not None else "unobserved",
                                               "lastScalarObservedMonotonicNanos": self.last_scalar_observed_ns,
                                               "lastTerminalEventObservedMonotonicNanos": self.last_terminal_event_ns},
                "priorAcceptedReviewCountAtSubmit": self.prior_accepted,
                "priorUnsettledRequiredObligationCountAtSubmit": self.prior_unsettled,
                "observationTimestampsMonotonicNanos": dict(self.times),
                "timings": {"T_ack": self.metric("ack"), "submitAckRoundTrip": self.metric("ack", "submitStarted"),
                            "acceptedEventObserved": self.metric("acceptedEvent"), "T_useful": self.metric(useful),
                            "firstUsefulFinding": self.metric("finding"), "successfulCleanAssessment": self.metric("clean"),
                            "T_required": self.metric("required"), "terminalOutcome": self.metric("terminal"),
                            "failedIncompleteTerminalOutcome": self.metric("unsuccessfulTerminal"),
                            "firstRunningStatusObserved": self.metric("running"),
                            "duplicateSubmitRoundTripMillis": self.duplicate_round_trip_ms},
                "lastResultFacts": facts, "lastResultFactsScope": "last scalar sample; current confirmation governs requiredAnalysisComplete",
                "failure": self.failure,
                "statusPollObservationCount": self.poll_observations, "unchangedObservationCount": self.unchanged_observations,
                "trace": list(self.trace)}


class Ledger:
    def __init__(self, scenario: Scenario, origin_ns: int, clock_id: str, rule: str, provider: str, upstream: str, scope_for):
        check(type(origin_ns) is int and origin_ns >= 0 and isinstance(clock_id, str) and UUID.fullmatch(clock_id) is not None, "clock_identity_invalid")
        self.scenario, self.origin_ns, self.clock_id = scenario, origin_ns, clock_id
        self.plan = patches(scenario)
        self.trials = {patch.trial_id: Trial(self, patch, rule, provider, upstream, scope_for(patch.client_id)) for patch in self.plan}
        self.trace_count = 0
        self.max_outstanding_observed = self.max_unsettled = 0
        self.oldest_unsettled_age_ms = 0.0

    def observe_contention(self, now: int) -> None:
        accepted = [item for item in self.trials.values() if item.review_id is not None]
        unsettled = [item for item in accepted if not item.required_complete]
        self.max_outstanding_observed = max(self.max_outstanding_observed, sum(not item.terminal for item in accepted))
        self.max_unsettled = max(self.max_unsettled, len(unsettled))
        if unsettled:
            self.oldest_unsettled_age_ms = max(self.oldest_unsettled_age_ms, max((now - item.times["ack"]) / 1e6 for item in unsettled))

    def abort(self, kind: str, code: str, now: int) -> None:
        check(kind in {"timeout", "aborted"} and re.fullmatch(r"preview_(?:example|benchmark)_[a-z_]+", code) is not None,
              "invalid_failure_code")
        for trial in self.trials.values():
            if trial.times and not trial.terminal:
                trial.failure = {"kind": kind, "code": code, "observedMonotonicNanos": now,
                                 "scope": "controller observation ended; not an assertion that provider work failed or stopped"}
        self.observe_contention(now)

    def report(self, now: int) -> dict:
        self.observe_contention(now)
        rows = [trial.public() for trial in self.trials.values()]
        counts = {}
        for phase in ("baseline", "measured"):
            members = [row for row in rows if row["phase"] == phase]
            counts[phase] = {"planned": len(members),
                             "completedPatchHandshakes": sum("patchComplete" in row["observationTimestampsMonotonicNanos"] for row in members),
                             "submitAttempts": sum("submitStarted" in row["observationTimestampsMonotonicNanos"] for row in members),
                             "durableAcceptanceObserved": sum(row["reviewId"] is not None for row in members),
                             "unknownAdmissionOutcomes": sum(row["admission"] == "outcome_unknown" for row in members),
                             "useful": sum(row["usefulnessOutcome"] == "observed" for row in members),
                             "requiredComplete": sum(row["requiredAnalysisComplete"] for row in members),
                             "outcomes": {state: sum(row["outcome"] == state for row in members)
                                          for state in ("not_started", "pending", "complete", "incomplete", "cancelled", "timeout", "aborted")},
                             "usefulnessOutcomes": {state: sum(row["usefulnessOutcome"] == state for row in members)
                                                    for state in ("not_started", "not_observed", "observed", "miss", "clean_assessment_incomplete", "unexpected_findings")}}
        baseline = counts["baseline"]
        return {"schema": REPORT_SCHEMA, "contractVersion": "ktchecker/preview-1", "releaseQualified": False,
                "fullProjectCoverageEstablished": False, "targetQualificationEstablished": False,
                "scenario": asdict(self.scenario), "scenarioDigest": digest(asdict(self.scenario)),
                "clock": {"domain": "one_controller_process_monotonic", "id": self.clock_id,
                          "originMonotonicNanos": self.origin_ns, "lastObservedMonotonicNanos": now,
                          "latencyOrigin": "controller_completed_synthetic_patch_before_overlay_capture",
                          "isHostCompletedPatchHandshake": False, "crossRuntimeClockSubtraction": False},
                "acceptanceScope": "validated scalar review.submit return after kernel durable immutable-input/obligation commit; not model delivery or a crash-recovery test",
                "coverageScope": "one explicit build-depth rule per declared partial compilation, not the locked full-project policy",
                "successfulCompletionObserver": "validated current submit/status response after any fetched terminal event; first-success timings remain historical, not current settlement",
                "counts": counts, "trials": rows,
                "warmth": {"requested": self.scenario.warmthRequest, "compilerWarmth": "unknown",
                           "warmProjectPrerequisite": "not_established",
                           "baselineRequiredComplete": baseline["planned"] > 0 and baseline["planned"] == baseline["requiredComplete"],
                           "baselineIsFullProject": False, "resultCacheHitMiss": "not_instrumented",
                           "dependencyAcquisition": "not_measured_resident_owner_selected_inputs",
                           "osPageCacheControl": "not_controlled"},
                "contention": {"requestedSchedule": self.scenario.schedule,
                               "maxAcceptedWithoutTerminalObservation": self.max_outstanding_observed,
                               "terminalObservationBasis": "current scalar confirmation after the latest fetched terminal event, not first historical terminal timing",
                               "maxUnsettledRequiredObligations": self.max_unsettled,
                               "oldestUnsettledAcceptedObligationObservedAgeMillis": self.oldest_unsettled_age_ms,
                               "scope": "controller-observed admission/current-terminal confirmation; event reconfirmation and polling lag can overstate concurrent execution",
                               "simultaneousProviderExecution": "unavailable", "queueDelay": "unavailable",
                               "sustainedFairness": "not_measured"},
                "contextDelivery": {"state": "not_attempted", "intendedModelRequestInclusion": "unavailable",
                                    "T_contextMillis": None, "insertionMillis": None, "observationMillis": None,
                                    "reason": "application poller is not a model host; no host timing or visibility evidence imported"},
                "tokens": {"state": "unavailable", "inputTokens": None, "outputTokens": None,
                           "summaryTokens": None, "detailTokens": None, "transportBytesAreTokens": False},
                "wholeBrokerProcessTreeResources": {"state": "not_measured", "cpuMillis": None, "peakRssBytes": None},
                "providerMetricsScope": "per-result reported counters only; null means unavailable, not zero; not summed as whole-process-tree usage",
                "percentiles": {"state": "not_qualified", "reason": "bounded smoke scenarios; every planned/attempted/accepted outcome retained, no percentile gate"}}
