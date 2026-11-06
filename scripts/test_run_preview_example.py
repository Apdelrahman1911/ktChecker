"""Bounded fake Python subprocesses and synthetic private files only; no Java/JVM.

These check example mechanics, not actual compiler execution, model delivery,
provider qualification or distribution compatibility. All patches are scoped.
"""
from __future__ import annotations

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import stat
import sys
import tempfile
import time
import unittest
from unittest import mock

import run_preview_example as example


HAS_GUARD = os.name == "posix" and all(hasattr(os, name) for name in ("waitid", "WNOWAIT", "O_NOFOLLOW"))
TOOLCHAIN = "sha256:" + "1" * 64
CERTIFICATE = "sha256:" + "2" * 64
CONTAINMENT_SOURCE = "sha256:" + "3" * 64
CONTAINMENT_BINARY = "sha256:" + "4" * 64
SECRET = "Z" * 43
PRIVATE_TEXT = "private-owner-reason-and-source-marker"
FINDING_WIRE_PROFILE = "ktchecker/finding-retrieval-attachments-1"
REVIEW_IDS = {"agent-a": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "agent-b": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}


def command(source: str) -> list[str]:
    return [sys.executable, "-u", "-c", source]


def limits(**kwargs):
    return example.PipeLimits(cleanup_seconds=0.25, **kwargs)


@contextlib.contextmanager
def tiny_cleanup_processes():
    original = example.BoundedProcess.__init__

    def tiny_init(transport, selected_command, limits=None):
        original(transport, selected_command, limits if limits is not None else example.PipeLimits(cleanup_seconds=0.25))

    with mock.patch.object(example.BoundedProcess, "__init__", new=tiny_init):
        yield


def provider_document():
    return {"contractVersion": example.CONTRACT, "releaseQualified": False, "doesNotEstablishTargetExecution": True,
            "processContainment": {"state": "available", "target": "linux-x86_64", "bundledBytesVerified": True,
                                   "executionVerified": False, "releaseQualified": False,
                                   "reasons": [PRIVATE_TEXT], "token": SECRET,
                                   "identity": {"protocolVersion": example.CONTAINMENT_PROTOCOL, "target": "linux-x86_64",
                                                "sourceDigest": CONTAINMENT_SOURCE, "binaryDigest": CONTAINMENT_BINARY,
                                                "private": PRIVATE_TEXT, "token": SECRET}},
            "compiler": {"state": "toolchain_bound", "toolchainDigest": TOOLCHAIN,
                         "requiresExplicitExecutionAuthorization": True, "javaHomeSelection": "explicit_owner_selection",
                         "runtimeRequirement": "complete_link_free_jdk21_image", "reason": PRIVATE_TEXT}}


def client_document(name="agent-a", *, execution_authorized=True):
    binding = {"ownerId": "owner-cccccccc-cccc-cccc-cccc-cccccccccccc", "workspaceId": "example-project",
               "worktreeId": "shared-overlay", "clientId": name, "sessionId": "session-" + name,
               "lineageId": "task-" + name, "generation": 1}
    data = example.SOURCES[name].encode("utf-8")
    snapshot = {"snapshotId": "sha256:" + ("a" if name == "agent-a" else "b") * 64,
                "ownerId": binding["ownerId"], "workspaceId": binding["workspaceId"], "worktreeId": binding["worktreeId"],
                "captureKind": "immutable_overlay", "unknownInputs": [],
                "files": [{"path": name + "/Main.kt", "digest": "sha256:" + hashlib.sha256(data).hexdigest(), "size": len(data)}]}
    prepared = {"contractVersion": example.CONTRACT, "binding": binding, "patchId": "patch-1", "idempotencyKey": "request-1",
                "coverageScope": "explicit-rule-subset-not-full-release", "executionAuthorized": execution_authorized,
                "input": {"snapshot": snapshot, "requiredRuleIds": [example.RULE],
                          "context": {"scope": example.scope_for(name), "fullProject": False, "compilerVersion": "2.4.21",
                                      "compilationFiles": [name + "/Main.kt"], "toolchainDigest": TOOLCHAIN}}}
    registration = {"contractVersion": example.CONTRACT, "binding": binding,
                    "client": {"clientId": name, "token": SECRET}, "adapter": {"clientId": name, "token": SECRET}}
    return {"prepared": prepared, "registration": registration}


def result_document(name="agent-a"):
    data = example.SOURCES[name].encode("utf-8")
    finding = {"ruleId": example.RULE, "providerId": example.COMPILER, "upstreamId": example.UPSTREAM,
               "context": example.scope_for(name), "diagnosis": PRIVATE_TEXT,
               "location": {"path": name + "/Main.kt", "fileDigest": "sha256:" + hashlib.sha256(data).hexdigest(),
                            "line": 2, "column": 0, "endLine": 2, "endColumn": 0}}
    return {"providerId": "coordinator", "status": "incomplete", "findings": [finding] if name == "agent-a" else [],
            "coverage": [{"ruleId": example.RULE, "context": example.scope_for(name), "required": True,
                          "state": "failed" if name == "agent-a" else "unknown", "provenance": "executed",
                          "certificateId": None, "qualified": False}], "reasons": [PRIVATE_TEXT],
            "rawDiagnostics": [{"message": PRIVATE_TEXT, "token": SECRET}],
            "metrics": {"elapsedMillis": 9, "cpuMillis": 3, "peakRssBytes": None, "outputBytes": 96, "secret": SECRET}}


def status_document(name="agent-a", state="incomplete", result=True):
    client = client_document(name)
    return {"contractVersion": example.CONTRACT, "reviewId": REVIEW_IDS[name], "clientId": name, "patchId": "patch-1",
            "sourceSnapshotId": client["prepared"]["input"]["snapshot"]["snapshotId"], "sequence": 1,
            "state": state, "cancelled": state == "cancelled", "reasons": [PRIVATE_TEXT],
            "result": result_document(name) if result else None}


def item_document(name="agent-a"):
    return {"client": client_document(name), "status": status_document(name), "submitted_ns": time.monotonic_ns(),
            "ack_ms": 1.0, "duplicate_ack_ms": 2.0, "first_useful_ms": None, "first_useful_via": None,
            "terminal_ms": None, "terminal_via": None, "cursor": None, "event_sequence": 0}


def event_page(item, *, sequence=1, cursor="opaque-cursor-one", state="running"):
    status = copy.deepcopy(item["status"])
    status["state"] = state
    payload = json.dumps(status, separators=(",", ":"), sort_keys=True)
    event = {"contractVersion": example.CONTRACT, "eventId": "dddddddd-dddd-dddd-dddd-dddddddddddd",
             "clientId": status["clientId"], "reviewId": status["reviewId"], "patchId": status["patchId"],
             "sourceSnapshotId": status["sourceSnapshotId"], "generation": 1, "sequence": sequence,
             "kind": "progress", "deliveryRequired": True, "payload": payload,
             "payloadDigest": "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()}
    return {"contractVersion": example.CONTRACT, "events": [event], "cursor": cursor, "hasMore": False}


class SafeFailureMixin:
    def fails(self, code, function, *args, **kwargs):
        with self.assertRaises(example.ExampleError) as caught:
            function(*args, **kwargs)
        self.assertEqual("preview_example_" + code, caught.exception.code)
        self.assertNotIn(PRIVATE_TEXT, str(caught.exception))
        self.assertNotIn(SECRET, str(caught.exception))


class JsonTests(SafeFailureMixin, unittest.TestCase):
    def test_absolute_owner_deadline_is_checked_before_any_process_start(self):
        with mock.patch.object(example.time, "monotonic", return_value=100.0), mock.patch.object(example, "BoundedProcess") as process:
            for deadline in (True, 100.0, 99.0, float("nan"), float("inf")):
                self.fails("invalid_timeout", example.run_json_command, [SECRET], deadline=deadline)
            process.assert_not_called()

    def test_utf8_limits_are_bytes_not_characters(self):
        data = example.encode_json({"value": "é" * 50})
        self.assertGreater(len(data), len(data.decode("utf-8")))
        self.assertEqual({"value": "é" * 50}, example.parse_json(data, len(data)))
        self.fails("frame_limit", example.parse_json, data, len(data) - 1)
        self.fails("frame_limit", example.encode_json, {"value": "é" * 50}, len(data) - 1)

    def test_ambiguous_invalid_and_nonfinite_json_rejected_without_private_text(self):
        invalid = [b"", b"[]", b'{"x":', b'{"x":1} {"x":2}', b'{"x":1,"x":2}',
                   b'{"x":{"token":1,"\\u0074oken":2}}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e999}',
                   b'{"x":"\\ud800"}', b'{"x":"\\udfff"}', b'{"x":"\xff"}', b'{"x":"a\x00b"}',
                   b'{"x":' + b"[" * 66 + b"0" + b"]" * 66 + b"}"]
        for data in invalid:
            with self.subTest(data_kind=invalid.index(data)):
                self.fails("invalid_json", example.parse_json, data)
        for value in ({"x": float("nan")}, {"x": float("inf")}, {"x": "\ud800"}, {1: "value"}, {"x": object()}):
            self.fails("invalid_json", example.encode_json, value)

    def test_valid_unicode_surrogate_pair_is_not_rejected(self):
        self.assertEqual({"x": "💡"}, example.parse_json(b'{"x":"\\ud83d\\udca1"}'))

    def test_numeric_and_transport_limit_validation_does_not_use_assert(self):
        self.assertEqual(4.0, example.PipeLimits().cleanup_seconds)
        for kwargs in ({"frame_bytes": 0}, {"frame_bytes": True}, {"stdout_bytes": 0}, {"cleanup_seconds": float("nan")},
                       {"cleanup_seconds": True}, {"frame_bytes": example.MAX_FRAME_BYTES + 1}):
            self.fails("invalid_limits", example.PipeLimits, **kwargs)
        for value in (0, -1, float("inf"), True):
            self.fails("invalid_timeout", example.BoundedProcess.deadline, value)


class NdjsonWireProfileTests(SafeFailureMixin, unittest.TestCase):
    """In-memory exchanges only; replacement clients model reconnect, not processes."""

    @staticmethod
    def success(identifier="1", result=None):
        return {"contractVersion": example.CONTRACT, "id": identifier,
                "findingWireProfile": FINDING_WIRE_PROFILE, "result": {} if result is None else result}

    @staticmethod
    def transport(responses):
        transport = mock.Mock()
        transport.limits = example.PipeLimits()
        transport.deadline.side_effect = lambda seconds: time.monotonic() + seconds
        transport.read_frame.side_effect = [json.dumps(response).encode("utf-8") for response in responses]
        return transport

    @staticmethod
    def requests(transport):
        return [json.loads(call.args[0]) for call in transport.write_frame.call_args_list]

    def test_literal_profile_is_sent_on_every_call_without_changing_params_or_ids(self):
        self.assertEqual(FINDING_WIRE_PROFILE, example.FINDING_WIRE_PROFILE)
        cases = [("doctor", {}, None, "1"),
                 ("review.submit", {"idempotencyKey": "one-request", "findingWireProfile": "nested-not-envelope"},
                  "owner-id", "owner-id"),
                 ("review.events", {"cursor": "old-cursor", "limit": 50}, None, "3")]
        responses = [self.success(identifier, {"value": index}) for index, (_, _, _, identifier) in enumerate(cases)]
        transport = self.transport(responses)
        client = example.NdjsonClient(transport)
        for index, (method, params, request_id, _) in enumerate(cases):
            self.assertEqual({"value": index}, client.call(method, params, request_id=request_id))
        self.assertEqual([{"id": identifier, "method": method, "params": params,
                           "findingWireProfile": FINDING_WIRE_PROFILE}
                          for method, params, _, identifier in cases], self.requests(transport))
        self.assertEqual(len(cases), transport.read_frame.call_count)

    def test_missing_malformed_and_mismatched_success_markers_never_expose_result(self):
        cases = [("missing", None), ("null", None), ("boolean", True), ("number", 1),
                 ("array", [FINDING_WIRE_PROFILE]), ("object", {"profile": FINDING_WIRE_PROFILE}),
                 ("empty", ""), ("old", "ktchecker/finding-retrieval-attachments-0"),
                 ("case", FINDING_WIRE_PROFILE.upper()), ("space", FINDING_WIRE_PROFILE + " "),
                 ("private", PRIVATE_TEXT + SECRET)]
        for label, profile in cases:
            with self.subTest(profile_kind=label):
                response = self.success(result={"private": PRIVATE_TEXT, "token": SECRET})
                if label == "missing":
                    del response["findingWireProfile"]
                else:
                    response["findingWireProfile"] = profile
                transport = self.transport([response])
                client = example.NdjsonClient(transport)
                expose = mock.Mock()
                self.fails("finding_wire_profile_outcome_uncertain",
                           lambda: expose(client.call("review.submit", {"idempotencyKey": "no-replay"})))
                expose.assert_not_called()
                transport.write_frame.assert_called_once()
                transport.read_frame.assert_called_once()
                self.assertEqual(FINDING_WIRE_PROFILE, self.requests(transport)[0]["findingWireProfile"])

    def test_matching_profile_does_not_bypass_success_envelope_validation(self):
        for response in (dict(self.success(), extra=SECRET), self.success(result=[])):
            transport = self.transport([response])
            self.fails("invalid_response", example.NdjsonClient(transport).call, "doctor", {})
            transport.write_frame.assert_called_once()
            transport.read_frame.assert_called_once()

    def test_prior_success_is_not_a_handshake_for_later_successes(self):
        unmarked = self.success("2", {"events": [], "cursor": "untrusted-cursor"})
        del unmarked["findingWireProfile"]
        transport = self.transport([self.success(result={"ready": True}), unmarked])
        client = example.NdjsonClient(transport)
        self.assertEqual({"ready": True}, client.call("doctor", {}))
        expose = mock.Mock()
        self.fails("finding_wire_profile_outcome_uncertain",
                   lambda: expose(client.call("review.events", {"cursor": "old-cursor"})))
        expose.assert_not_called()
        self.assertEqual(["1", "2"], [request["id"] for request in self.requests(transport)])
        self.assertTrue(all(request["findingWireProfile"] == FINDING_WIRE_PROFILE
                            for request in self.requests(transport)))
        self.assertEqual(2, transport.read_frame.call_count)

    def test_replacement_client_rechecks_old_peer_even_for_default_empty_findings(self):
        prior = self.transport([self.success(result={"reviewId": "already-accepted"})])
        self.assertEqual({"reviewId": "already-accepted"},
                         example.NdjsonClient(prior).call("review.submit", {"idempotencyKey": "already-sent"}))
        for result in ({}, {"findings": []}, {"findings": [], "findingWireProfile": FINDING_WIRE_PROFILE}):
            # An old peer can ignore the new request field and return a default or
            # empty result. Neither prior success nor a nested marker is an echo.
            legacy = {"contractVersion": example.CONTRACT, "id": "1", "result": result}
            replacement = self.transport([legacy])
            self.fails("finding_wire_profile_outcome_uncertain",
                       example.NdjsonClient(replacement).call, "doctor", {})
            self.assertEqual([{"id": "1", "method": "doctor", "params": {},
                               "findingWireProfile": FINDING_WIRE_PROFILE}], self.requests(replacement))
            replacement.read_frame.assert_called_once()
        self.assertEqual(1, prior.write_frame.call_count)  # No old mutation was replayed.

    def test_bad_success_after_synthetic_commit_is_uncertain_not_rollback_or_replay(self):
        response = dict(self.success(result={"reviewId": "committed-before-response"}),
                        findingWireProfile="foreign-profile")
        transport = self.transport([response])
        committed = []
        transport.write_frame.side_effect = lambda data, _deadline: committed.append(json.loads(data))
        self.fails("finding_wire_profile_outcome_uncertain", example.NdjsonClient(transport).call,
                   "review.submit", {"idempotencyKey": "one-attempt"})
        self.assertEqual([{"id": "1", "method": "review.submit", "params": {"idempotencyKey": "one-attempt"},
                           "findingWireProfile": FINDING_WIRE_PROFILE}], committed)
        transport.write_frame.assert_called_once()
        transport.read_frame.assert_called_once()

    def test_error_envelopes_keep_sanitized_rejection_and_existing_identity_precedence(self):
        error = {"contractVersion": example.CONTRACT, "id": "1",
                 "error": {"code": SECRET, "message": PRIVATE_TEXT}}
        cases = [(error, "rpc_rejected"),
                 (dict(error, findingWireProfile=None), "rpc_rejected"),
                 (dict(error, findingWireProfile="foreign-profile", result={}), "rpc_rejected"),
                 (dict(error, findingWireProfile=FINDING_WIRE_PROFILE), "rpc_rejected"),
                 (dict(error, id="wrong-id"), "wrong_response_id"),
                 (dict(error, contractVersion="foreign"), "wrong_contract")]
        for response, code in cases:
            with self.subTest(code=code):
                transport = self.transport([response])
                self.fails(code, example.NdjsonClient(transport).call, "doctor", {})
                transport.write_frame.assert_called_once()
                transport.read_frame.assert_called_once()


@unittest.skipUnless(HAS_GUARD, "POSIX unreaped leader/pipe support required")
class TransportTests(SafeFailureMixin, unittest.TestCase):
    def process(self, source, **kwargs):
        return example.BoundedProcess(command(source), limits(**kwargs))

    def reply(self, data, *, tail="time.sleep(30)", **kwargs):
        return self.process("import os,sys,time\nsys.stdin.buffer.readline()\nos.write(1," + repr(data) + ")\n" + tail, **kwargs)

    def assert_closed(self, transport):
        self.assertTrue(transport.closed)
        self.assertIsNotNone(transport.process.returncode)
        self.assertTrue(all(getattr(transport.process, name).closed for name in ("stdin", "stdout", "stderr")))
        self.assertIsNone(transport.selector.get_map())
        self.assertGreaterEqual(transport.cleanup_statistics()["observedMillis"], 0)
        self.assertFalse(transport.cleanup_statistics()["forcedHelperLossGuaranteed"])

    def test_persistent_binary_ndjson_round_trips(self):
        source = """
import json,sys
for line in sys.stdin.buffer:
    request=json.loads(line)
    assert request.get('findingWireProfile')=='ktchecker/finding-retrieval-attachments-1'
    print(json.dumps({'contractVersion':'ktchecker/preview-1','id':request['id'],
                      'findingWireProfile':'ktchecker/finding-retrieval-attachments-1','result':{'value':'é💡'}}),flush=True)
"""
        with self.process(source) as transport:
            client = example.NdjsonClient(transport)
            self.assertEqual({"value": "é💡"}, client.call("doctor", {}))
            self.assertEqual({"value": "é💡"}, client.call("doctor", {}, request_id="unicode-💡"))
            self.assertEqual({"value": "é💡"}, client.call("doctor", {}, request_id="💡" * 128))
            self.assertGreater(transport.statistics()["stdoutBytes"], 0)
            self.assertFalse(transport.statistics()["stderrRetained"])
        self.assert_closed(transport)

    def test_partial_line_timeout_and_no_output_timeout_are_bounded_and_cleaned(self):
        for data in (b"", b'{"private":"' + PRIVATE_TEXT.encode()):
            started = time.monotonic()
            with self.reply(data) as transport:
                self.fails("transport_timeout", example.NdjsonClient(transport).call, "doctor", {},
                           deadline=transport.deadline(0.3))
            self.assertLess(time.monotonic() - started, 4)
            self.assert_closed(transport)

    def test_empty_eof_and_valid_json_without_newline_fail_closed(self):
        for data, code in ((b"", "transport_eof"), (b'{"x":1}', "partial_eof")):
            with self.reply(data, tail="pass") as transport:
                self.fails(code, example.NdjsonClient(transport).call, "doctor", {})
            self.assert_closed(transport)

    def test_utf8_response_byte_oversize_and_oversize_before_newline(self):
        for data in (b'{"value":"' + ("é" * 100).encode("utf-8") + b'"}\n', b"x" * 129):
            with self.reply(data, frame_bytes=128) as transport:
                self.fails("frame_limit", example.NdjsonClient(transport).call, "doctor", {})
                self.assertLessEqual(len(transport.buffer), 129)
            self.assert_closed(transport)

    def test_response_at_exact_byte_limit_with_lf_passes(self):
        data = example.encode_json({"contractVersion": example.CONTRACT, "id": "1",
                                    "findingWireProfile": FINDING_WIRE_PROFILE, "result": {"value": "é" * 25}})
        with self.reply(data + b"\n", frame_bytes=len(data)) as transport:
            self.assertEqual({"value": "é" * 25}, example.NdjsonClient(transport).call("doctor", {}))
            self.assertEqual(len(data) + 1, transport.stdout_bytes)

    def test_malformed_response_contract_id_envelope_and_private_error(self):
        base = {"contractVersion": example.CONTRACT, "id": "1",
                "findingWireProfile": FINDING_WIRE_PROFILE, "result": {}}
        cases = [(b'{"id":', "invalid_json"),
                 (b'{"contractVersion":"ktchecker/preview-1","id":"1","id":"1","result":{}}', "invalid_json")]
        for field, value, code in (("id", "2", "wrong_response_id"), ("id", 1, "wrong_response_id"),
                                   ("contractVersion", "foreign", "wrong_contract"), ("result", [], "invalid_response")):
            body = dict(base, **{field: value})
            cases.append((json.dumps(body).encode(), code))
        cases += [(json.dumps(dict(base, extra=SECRET)).encode(), "invalid_response"),
                  (json.dumps(dict(base, error={"message": PRIVATE_TEXT, "code": SECRET})).encode(), "rpc_rejected")]
        for data, code in cases:
            with self.subTest(code=code):
                with self.reply(data + b"\n") as transport:
                    self.fails(code, example.NdjsonClient(transport).call, "doctor", {})
                self.assert_closed(transport)

    def test_multiple_response_frames_are_not_accepted(self):
        data = example.encode_json({"contractVersion": example.CONTRACT, "id": "1",
                                    "findingWireProfile": FINDING_WIRE_PROFILE, "result": {}})
        with self.reply(data + b"\n" + data + b"\n") as transport:
            self.fails("unexpected_output", example.NdjsonClient(transport).call, "doctor", {})

    def test_response_parsing_does_not_restart_or_escape_request_deadline(self):
        data = example.encode_json({"contractVersion": example.CONTRACT, "id": "1",
                                    "findingWireProfile": FINDING_WIRE_PROFILE, "result": {}})
        original = example.parse_json

        def delayed(*args, **kwargs):
            value = original(*args, **kwargs)
            time.sleep(0.35)
            return value

        with self.reply(data + b"\n") as transport:
            with mock.patch.object(example, "parse_json", side_effect=delayed):
                self.fails("transport_timeout", example.NdjsonClient(transport).call, "doctor", {},
                           deadline=transport.deadline(0.3))
        self.assert_closed(transport)

    def test_invalid_ids_and_request_utf8_byte_limit_reject_before_write(self):
        with self.process("import time;time.sleep(30)", frame_bytes=128) as transport:
            client = example.NdjsonClient(transport)
            for identifier in ("", "x" * 257, 1, True, "\ud800", "💡" * 129):
                self.fails("invalid_request_id", client.call, "doctor", {}, request_id=identifier)
            self.fails("frame_limit", client.call, "doctor", {"value": "é" * 100})
            self.fails("invalid_request", client.call, "doctor\nmalicious", {})
            self.assertEqual(0, transport.stdin_bytes)

    def test_request_blocked_write_uses_deadline_and_cleanup(self):
        started = time.monotonic()
        with self.process("import time;time.sleep(30)", frame_bytes=524_288) as transport:
            self.fails("transport_timeout", example.NdjsonClient(transport).call, "doctor", {"value": "x" * 262_144},
                       deadline=transport.deadline(0.3))
            self.assertGreater(transport.stdin_bytes, 0)
            self.assertLess(transport.stdin_bytes, 262_144)
        self.assertLess(time.monotonic() - started, 4)
        self.assert_closed(transport)

    def test_session_stderr_stdout_and_stdin_budgets(self):
        with self.process("import os,time;os.write(2,b'x'*1000);time.sleep(30)", stderr_bytes=128) as transport:
            self.fails("stderr_limit", example.NdjsonClient(transport).call, "doctor", {})
            self.assertEqual(129, transport.stderr_bytes)
        source = """
import json,sys
for line in sys.stdin.buffer:
    r=json.loads(line)
    assert r.get('findingWireProfile')=='ktchecker/finding-retrieval-attachments-1'
    print(json.dumps({'contractVersion':'ktchecker/preview-1','id':r['id'],
                      'findingWireProfile':'ktchecker/finding-retrieval-attachments-1','result':{}}),flush=True)
"""
        reply = {"contractVersion": example.CONTRACT, "id": "1",
                 "findingWireProfile": FINDING_WIRE_PROFILE, "result": {}}
        stdout_budget = len((json.dumps(reply) + "\n").encode("utf-8"))
        with self.process(source, stdout_bytes=stdout_budget) as transport:
            client = example.NdjsonClient(transport)
            client.call("doctor", {})
            self.fails("stdout_limit", client.call, "doctor", {})
            self.assertEqual(stdout_budget + 1, transport.stdout_bytes)
        request = {"id": "1", "method": "doctor", "params": {}, "findingWireProfile": FINDING_WIRE_PROFILE}
        stdin_budget = len(example.encode_json(request)) + 1
        with self.process(source, stdin_bytes=stdin_budget) as transport:
            client = example.NdjsonClient(transport)
            client.call("doctor", {})
            self.fails("stdin_limit", client.call, "doctor", {})

    def test_one_shot_command_success_exit_error_trailing_output_and_timeout(self):
        value = {"contractVersion": example.CONTRACT, "value": 1}
        source = "import os;os.write(2,b'" + PRIVATE_TEXT + "');print(" + repr(json.dumps(value)) + ",flush=True)"
        result, statistics = example.run_json_command(command(source), timeout=3, limits=limits())
        self.assertEqual(value, result)
        self.assertEqual(len(PRIVATE_TEXT), statistics["stderrBytes"])
        self.assertNotIn(PRIVATE_TEXT, json.dumps(statistics))
        self.fails("owner_command_failed", example.run_json_command,
                   command(source + ";raise SystemExit(3)"), timeout=3, limits=limits())
        self.fails("unexpected_output", example.run_json_command,
                   command(source + ";print('" + PRIVATE_TEXT + "',flush=True)"), timeout=3, limits=limits())
        self.fails("transport_timeout", example.run_json_command,
                   command(source + ";import time;time.sleep(30)"), timeout=0.3, limits=limits())

    def test_cleanup_kills_term_ignoring_leader_and_closes_every_pipe(self):
        with self.process("import signal,time,os;signal.signal(signal.SIGTERM,signal.SIG_IGN);os.write(1,b'{}\\n');time.sleep(30)") as transport:
            self.assertEqual({}, example.parse_json(transport.read_frame(transport.deadline(3))))
        self.assertEqual(-signal.SIGKILL, transport.process.returncode)
        self.assert_closed(transport)
        transport.close()  # Idempotent; never signals a reaped/reused PID.

    def test_cleanup_signals_descendant_and_parent_reaps_it(self):
        source = """
import json,signal,subprocess,sys,time
child=subprocess.Popen([sys.executable,'-u','-c','import time;time.sleep(30)'])
def stop(_signal,_frame):
    child.wait(timeout=2)
    raise SystemExit(0)
signal.signal(signal.SIGTERM,stop)
print(json.dumps({'pid':child.pid}),flush=True)
time.sleep(30)
"""
        with self.process(source) as transport:
            child_pid = example.parse_json(transport.read_frame(transport.deadline(3)))["pid"]
        self.assert_closed(transport)
        with self.assertRaises(ProcessLookupError):
            os.kill(child_pid, 0)

    def test_success_path_retains_unreaped_leader_until_last_group_signal(self):
        real_killpg = os.killpg
        observed = []

        def guarded(pid, number):
            state = os.waitid(os.P_PID, pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            self.assertIsNotNone(state, "One-shot command should have exited but must not have been reaped")
            self.assertEqual(pid, state.si_pid)
            observed.append((number, time.monotonic()))
            return real_killpg(pid, number)

        source = "print('" + json.dumps({"contractVersion": example.CONTRACT}) + "',flush=True)"
        with mock.patch.object(example.os, "killpg", side_effect=guarded):
            example.run_json_command(command(source), timeout=3, limits=limits())
        self.assertEqual([signal.SIGTERM, signal.SIGKILL], [item[0] for item in observed])
        self.assertGreaterEqual(observed[1][1] - observed[0][1], limits().cleanup_seconds)

    def test_exited_leader_allows_cooperative_helper_to_finish_before_last_signal(self):
        with tempfile.TemporaryDirectory(prefix="ktchecker-exited-leader-grace-") as temporary:
            base = Path(temporary)
            ready, done = base / "ready", base / "done"
            helper = """
import pathlib,signal,sys,time
def terminate(_signal,_frame):
    time.sleep(0.1)
    pathlib.Path(sys.argv[2]).write_text('synthetic-ack')
    raise SystemExit(0)
signal.signal(signal.SIGTERM,terminate)
pathlib.Path(sys.argv[1]).write_text('synthetic-ready')
time.sleep(30)
"""
            source = """
import json,pathlib,subprocess,sys,time
subprocess.Popen([sys.executable,'-u','-c',HELPER,READY,DONE],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=True)
deadline=time.monotonic()+2
while not pathlib.Path(READY).is_file():
    if time.monotonic()>=deadline: raise SystemExit(5)
    time.sleep(0.005)
print(json.dumps({'contractVersion':'ktchecker/preview-1'}),flush=True)
"""
            source = "HELPER=" + repr(helper) + "\nREADY=" + repr(str(ready)) + "\nDONE=" + repr(str(done)) + "\n" + source
            _, statistics = example.run_json_command(command(source), timeout=3, limits=limits())
            self.assertEqual("synthetic-ack", done.read_text())
            self.assertGreaterEqual(statistics["cleanup"]["observedMillis"], limits().cleanup_seconds * 1000)

    def test_lost_unreaped_guard_fails_without_group_signal(self):
        transport = self.process("import time;time.sleep(30)")
        transport.process.kill()
        transport.process.wait(timeout=3)  # Simulate an external reaper, not normal product use.
        with mock.patch.object(example.os, "killpg") as killpg:
            self.fails("process_lifetime_guard_lost", transport.close)
        killpg.assert_not_called()
        self.assert_closed(transport)

    def test_process_start_error_is_static_not_command_or_path(self):
        self.fails("process_start_failed", example.BoundedProcess, ["/missing/" + SECRET], limits())


@unittest.skipUnless(HAS_GUARD, "POSIX private directory support required")
class PrivateDirectoryTests(SafeFailureMixin, unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ktchecker-example-test-")
        self.base = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def test_new_root_and_files_are_private_and_never_overwritten(self):
        with example.PrivateDirectory(self.base / "new") as root:
            self.assertEqual(0o700, stat.S_IMODE(root.path.stat().st_mode))
            root.write_json("credential.json", {"token": SECRET})
            path = root.path / "credential.json"
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual({"token": SECRET}, root.read_json("credential.json"))
            original = path.read_bytes()
            self.fails("private_file_exists", root.write_json, "credential.json", {"replacement": 1})
            self.assertEqual(original, path.read_bytes())
            self.fails("unsafe_private_filename", root.write_json, "../outside.json", {})
        self.assertIsNone(root.fd)

    def test_existing_output_and_symbolic_parent_are_not_chmodded_or_traversed(self):
        existing = self.base / "existing"
        existing.mkdir(mode=0o755)
        existing.chmod(0o755)  # Do not assume the test runner's umask.
        marker = existing / "marker"
        marker.write_text(PRIVATE_TEXT)
        self.fails("new_private_output_required", example.PrivateDirectory, existing)
        self.assertEqual(0o755, stat.S_IMODE(existing.stat().st_mode))
        self.assertEqual(PRIVATE_TEXT, marker.read_text())
        link = self.base / "alias"
        link.symlink_to(existing, target_is_directory=True)
        self.fails("new_private_output_required", example.PrivateDirectory, link / "new")
        self.assertFalse((existing / "new").exists())
        self.fails("unsafe_output_path", example.PrivateDirectory, self.base / "existing" / ".." / "new")

    def test_nonsticky_shared_parent_rejected_before_creation(self):
        parent = self.base / "shared"
        parent.mkdir(mode=0o777)
        parent.chmod(0o777)
        self.fails("unsafe_output_parent", example.PrivateDirectory, parent / "new")
        self.assertFalse((parent / "new").exists())

    def test_private_file_links_fifo_shared_permissions_and_size_rejected(self):
        with example.PrivateDirectory(self.base / "new") as root:
            root.write_json("source.json", {"token": SECRET})
            (root.path / "link.json").symlink_to("source.json")
            self.fails("private_file_failed", root.read_json, "link.json")
            os.link(root.path / "source.json", root.path / "hard.json")
            self.fails("unsafe_private_file", root.read_json, "hard.json")
            os.mkfifo(root.path / "fifo.json", 0o600)
            self.fails("unsafe_private_file", root.read_json, "fifo.json")
            root.write_json("shared.json", {})
            (root.path / "shared.json").chmod(0o644)
            self.fails("unsafe_private_file", root.read_json, "shared.json")
            with (root.path / "large.json").open("xb") as stream:
                stream.truncate(example.MAX_PRIVATE_BYTES + 1)
            (root.path / "large.json").chmod(0o600)
            self.fails("private_file_limit", root.read_json, "large.json")

    def test_renamed_or_permission_changed_root_fails_closed(self):
        with example.PrivateDirectory(self.base / "new") as root:
            root.path.rename(self.base / "moved")
            root.path.mkdir(mode=0o700)
            self.fails("private_root_changed", root.write_json, "credential.json", {"token": SECRET})
            self.assertFalse((root.path / "credential.json").exists())
        with example.PrivateDirectory(self.base / "other") as root:
            root.path.chmod(0o755)
            self.fails("private_root_changed", root.verify)

    def test_explicit_compiler_home_independent_of_broker_and_no_python_jdk_policy(self):
        compiler = self.base / "compiler"
        (compiler / "bin").mkdir(parents=True)
        target = self.base / "tiny-not-a-jdk"
        target.write_text("synthetic, never executed")
        (compiler / "bin" / "java").symlink_to(target)
        # The lightweight Python preflight accepts this path. The CLI is the
        # authority and will reject a non-JDK or linked image during providers.
        with example.PrivateDirectory(self.base / "new") as root:
            with mock.patch.dict(os.environ, {"JAVA_HOME": str(self.base / "broker")}), mock.patch.object(example.subprocess, "Popen") as popen:
                controller = example.Controller(self.base / "distribution", root, compiler)
            popen.assert_not_called()
            self.assertEqual(str(self.base / "broker" / "bin" / "java"), controller.command[0])
            selected = controller.command.index("--compiler-java-home") + 1
            self.assertEqual(str(compiler), controller.command[selected])
            self.fails("compiler_java_home_missing", example.Controller, self.base, root, self.base / "missing")

    def test_main_requires_compiler_home_and_never_falls_back_or_echoes_local_errors(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
            example.main(["--output", str(self.base / "new")])
        self.assertEqual(2, caught.exception.code)
        unknown_errors = io.StringIO()
        with contextlib.redirect_stderr(unknown_errors), self.assertRaises(SystemExit) as caught:
            example.main(["--output", str(self.base / "new"), "--compiler-java-home", str(self.base), SECRET])
        self.assertEqual(2, caught.exception.code)
        self.assertNotIn(SECRET, unknown_errors.getvalue())
        home = self.base / "distribution"
        (home / "lib").mkdir(parents=True)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), mock.patch.object(example.subprocess, "Popen") as popen:
            self.assertEqual(1, example.main(["--distribution", str(home), "--output", str(self.base / "new"),
                                              "--compiler-java-home", str(self.base / SECRET)]))
        popen.assert_not_called()
        self.assertFalse((self.base / "new").exists())
        self.assertNotIn(SECRET, stderr.getvalue())
        self.assertNotIn(str(self.base), stderr.getvalue())
        self.assertEqual("preview_example_compiler_java_home_missing", json.loads(stderr.getvalue())["code"])

    def test_benchmark_selection_is_explicit_paired_and_incompatible_with_prepare_only(self):
        for extra in (["--benchmark-scenario", "fresh-broker-concurrent"],
                      ["--benchmark-scenarios", str(self.base / SECRET)],
                      ["--benchmark-scenarios", str(self.base / SECRET), "--benchmark-scenario", "fresh-broker-concurrent", "--prepare-only"]):
            errors = io.StringIO()
            with contextlib.redirect_stderr(errors), mock.patch.object(example.subprocess, "Popen") as popen:
                self.assertEqual(1, example.main(["--output", str(self.base / "new"), "--compiler-java-home", str(self.base), *extra]))
            self.assertEqual("preview_example_benchmark_arguments_invalid", json.loads(errors.getvalue())["code"])
            self.assertNotIn(SECRET, errors.getvalue())
            self.assertFalse((self.base / "new").exists())
            popen.assert_not_called()

    def test_benchmark_data_read_is_bounded_link_free_and_does_not_execute_scenario_code(self):
        selected = example.benchmark.Scenario("probe", "fresh_broker_resident_dependencies", "concurrent", 0, 1, 2, 1, 600, 240)
        path = self.base / "scenario.json"
        path.write_text(json.dumps({"schema": example.benchmark.SCHEMA, "scenarios": [example.benchmark.asdict(selected)]}))
        with mock.patch.object(example.subprocess, "Popen") as popen:
            self.assertEqual(selected, example.read_benchmark_scenario(path, "probe"))
            link = self.base / "link.json"
            link.symlink_to(path)
            self.fails("benchmark_scenarios_unavailable", example.read_benchmark_scenario, link, "probe")
            path.write_bytes(b" " * 65_537)
            self.fails("benchmark_scenarios_invalid", example.read_benchmark_scenario, path, "probe")
            popen.assert_not_called()


class ObservationTests(SafeFailureMixin, unittest.TestCase):
    def test_provider_inspection_requires_explicit_selection_and_does_not_claim_execution(self):
        result = example.provider_inspection(provider_document())
        self.assertFalse(result["inspectionEstablishesExecution"])
        self.assertNotIn(PRIVATE_TEXT, json.dumps(result))
        self.assertNotIn(SECRET, json.dumps(result))
        for key, value in (("state", "unavailable"), ("javaHomeSelection", "broker_default_no_fallback"),
                           ("toolchainDigest", "wrong"), ("runtimeRequirement", "java21")):
            changed = provider_document()
            changed["compiler"][key] = value
            self.fails("compiler_unavailable" if key == "state" else "invalid_provider_inspection",
                       example.provider_inspection, changed)

    def test_provider_inspection_denies_missing_or_unavailable_bundled_helper(self):
        for containment in (None, {}, "available", {"state": "unavailable", "bundledBytesVerified": False},
                            {"state": "available", "bundledBytesVerified": False},
                            {"state": "available", "bundledBytesVerified": 1}):
            changed = provider_document()
            changed["processContainment"] = containment
            self.fails("process_containment_unavailable", example.provider_inspection, changed)
        changed = provider_document()
        del changed["processContainment"]
        self.fails("process_containment_unavailable", example.provider_inspection, changed)

    @unittest.skipUnless(HAS_GUARD, "POSIX private directory support required")
    def test_unavailable_containment_denies_before_client_registration_or_private_requests(self):
        with tempfile.TemporaryDirectory(prefix="ktchecker-containment-gate-") as temporary:
            base = Path(temporary)
            (base / "compiler" / "bin").mkdir(parents=True)
            (base / "compiler" / "bin" / "java").write_text("synthetic, never executed")
            for name, containment in (("missing", None), ("unavailable", {"state": "unavailable", "bundledBytesVerified": False})):
                with example.PrivateDirectory(base / name) as root:
                    controller = example.Controller(base / "distribution", root, base / "compiler")
                    document = provider_document()
                    if containment is None:
                        del document["processContainment"]
                    else:
                        document["processContainment"] = containment
                    with mock.patch.object(controller, "call", return_value=document) as call:
                        self.fails("process_containment_unavailable", controller.prepare, authorize_execution=False)
                    call.assert_called_once_with("providers")
                    self.assertFalse(list(root.path.iterdir()))

    def test_containment_projection_preserves_byte_identity_without_execution_or_qualification(self):
        document = provider_document()
        projection = example.provider_inspection(document)["processContainment"]
        self.assertEqual({"state": "available", "target": "linux-x86_64", "bundledBytesVerified": True,
                          "executionVerified": False, "releaseQualified": False,
                          "identity": {"protocolVersion": example.CONTAINMENT_PROTOCOL, "target": "linux-x86_64",
                                       "sourceDigest": CONTAINMENT_SOURCE, "binaryDigest": CONTAINMENT_BINARY}}, projection)
        for field, value in (("target", "windows-x86_64"), ("target", {}), ("executionVerified", True),
                             ("executionVerified", 0), ("releaseQualified", True), ("identity", None)):
            changed = provider_document()
            changed["processContainment"][field] = value
            self.fails("invalid_provider_inspection", example.provider_inspection, changed)
        for field, value in (("protocolVersion", "unknown"), ("target", "macos-aarch64"),
                             ("sourceDigest", "wrong"), ("binaryDigest", None)):
            changed = provider_document()
            changed["processContainment"]["identity"][field] = value
            self.fails("invalid_provider_inspection", example.provider_inspection, changed)
        changed = provider_document()
        changed["processContainment"]["identity"]["binaryDigest"] = TOOLCHAIN
        self.assertNotEqual(example.provider_inspection(document), example.provider_inspection(changed))

    def test_prepared_input_scope_exact_file_and_toolchain_binding(self):
        for authorized in (False, True):
            options = {"execution_authorized": authorized}
            example.validate_prepared(client_document(execution_authorized=authorized), "agent-a", TOOLCHAIN, **options)
            for field, value in (("fullProject", True), ("compilationFiles", ["agent-b/Main.kt"]),
                                 ("compilerVersion", "2.4.20"), ("toolchainDigest", CERTIFICATE),
                                 ("scope", example.scope_for("agent-b"))):
                client = client_document(execution_authorized=authorized)
                client["prepared"]["input"]["context"][field] = value
                self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)
            client = client_document(execution_authorized=authorized)
            client["prepared"]["input"]["snapshot"]["files"][0]["digest"] = CERTIFICATE
            self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)
            client = client_document(execution_authorized=authorized)
            client["prepared"]["input"]["requiredRuleIds"] = []
            self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)

    def test_prepared_authorization_requires_the_exact_selected_boolean(self):
        for authorized in (False, True):
            options = {"execution_authorized": authorized}
            for name in example.SOURCES:
                example.validate_prepared(client_document(name, execution_authorized=authorized), name, TOOLCHAIN, **options)
            for value in (not authorized, None, 0, 1, "false", "true", [], {}):
                with self.subTest(expected=authorized, retained=value):
                    client = client_document(execution_authorized=value)
                    self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)
            client = client_document(execution_authorized=authorized)
            del client["prepared"]["executionAuthorized"]
            self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)
        for value in (None, 0, 1, "false", "true", [], {}):
            self.fails("invalid_prepared_binding", example.validate_prepared, client_document(), "agent-a", TOOLCHAIN,
                       execution_authorized=value)

    def test_preparation_rejects_nonboolean_owner_choice_before_any_owner_command(self):
        controller = object.__new__(example.Controller)
        with mock.patch.object(controller, "call") as call:
            for value in (None, 0, 1, "false", "true", [], {}):
                self.fails("invalid_execution_authorization", controller.prepare, authorize_execution=value)
            call.assert_not_called()

    def test_review_rejects_non_authorized_preparation_before_broker_start(self):
        controller = object.__new__(example.Controller)
        controller.inspection = example.provider_inspection(provider_document())
        controller.root = mock.Mock(spec=example.PrivateDirectory)
        for name in example.SOURCES:
            clients = [client_document(other, execution_authorized=other != name) for other in example.SOURCES]
            unchanged = copy.deepcopy(clients)
            with mock.patch.object(example, "BoundedProcess") as process:
                self.fails("invalid_prepared_binding", controller.review, clients)
                process.assert_not_called()
            controller.root.verify.assert_not_called()
            self.assertEqual(unchanged, clients)  # No implicit grant repair or request mutation.

    def test_benchmark_patch_variant_uses_exact_explicit_source_and_ids_not_legacy_defaults(self):
        client = client_document()
        source = "interface Contract2 { fun answer(): Int }\nclass Broken2 : Contract2\n"
        client["prepared"].update(patchId="benchmark-measured-r2-agent-a", idempotencyKey="request-measured-r2-agent-a")
        file = client["prepared"]["input"]["snapshot"]["files"][0]
        file.update(digest="sha256:" + hashlib.sha256(source.encode()).hexdigest(), size=len(source.encode()))
        self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN)
        options = {"source": source, "patch_id": client["prepared"]["patchId"], "idempotency_key": client["prepared"]["idempotencyKey"]}
        example.validate_prepared(client, "agent-a", TOOLCHAIN, **options)
        options["source"] = example.SOURCES["agent-a"]
        self.fails("invalid_prepared_binding", example.validate_prepared, client, "agent-a", TOOLCHAIN, **options)

    def test_status_binding_checks_client_review_patch_snapshot_and_sequence(self):
        item = item_document()
        example.validate_status(item["status"], item["client"])
        for field, value in (("clientId", "agent-b"), ("reviewId", REVIEW_IDS["agent-b"]), ("patchId", "other"),
                             ("sourceSnapshotId", CERTIFICATE), ("sequence", True), ("sequence", 2), ("state", "unknown")):
            status = dict(item["status"], **{field: value})
            self.fails("invalid_status_binding", example.validate_status, status, item["client"], item["status"])

    def test_useful_finding_requires_actual_upstream_provider_exact_scope_file_digest_and_range(self):
        item = item_document()
        self.assertEqual(1, example.matching_findings(item["status"]["result"], item["client"]))
        for field, value in (("providerId", "unavailable"), ("upstreamId", "OTHER"), ("context", example.scope_for("agent-b"))):
            result = result_document()
            result["findings"][0][field] = value
            self.fails("invalid_finding_binding", example.matching_findings, result, item["client"])
        for field, value in (("fileDigest", CERTIFICATE), ("path", "agent-b/Main.kt"), ("line", True), ("endLine", 1),
                             ("endLine", 3), ("endColumn", 100)):
            result = result_document()
            result["findings"][0]["location"][field] = value
            self.fails("invalid_finding_binding", example.matching_findings, result, item["client"])

    def test_events_validate_binding_digest_and_payload_before_advancing_cursor(self):
        for field, value, code in (("clientId", "agent-b", "invalid_event_binding"), ("reviewId", REVIEW_IDS["agent-b"], "invalid_event_binding"),
                                   ("patchId", "other", "invalid_event_binding"), ("sourceSnapshotId", CERTIFICATE, "invalid_event_binding"),
                                   ("generation", True, "invalid_event_binding"), ("payloadDigest", CERTIFICATE, "invalid_event_digest")):
            item = item_document()
            page = event_page(item)
            page["events"][0][field] = value
            self.fails(code, example.validate_event_page, page, item)
            self.assertIsNone(item["cursor"])
            self.assertEqual(0, item["event_sequence"])
        item = item_document()
        page = event_page(item)
        page["events"][0]["payload"] = json.dumps(status_document("agent-b"))
        page["events"][0]["payloadDigest"] = "sha256:" + hashlib.sha256(page["events"][0]["payload"].encode()).hexdigest()
        self.fails("invalid_status_binding", example.validate_event_page, page, item)
        payloads = example.validate_event_page(event_page(item), item)
        self.assertEqual("opaque-cursor-one", item["cursor"])
        self.assertEqual(1, item["event_sequence"])
        self.assertEqual(1, len(payloads))
        self.fails("invalid_event_cursor", example.validate_event_page, event_page(item), item)
        self.fails("invalid_event_binding", example.validate_event_page, event_page(item, cursor="new-cursor"), item)

    def test_application_useful_terminal_and_acknowledgment_are_distinct_metrics(self):
        item = item_document()
        example.note_observation(item, status_document(state="running"), item["submitted_ns"] + 5_000_000, "review.events")
        self.assertEqual(5, item["first_useful_ms"])
        self.assertIsNone(item["terminal_ms"])
        example.note_observation(item, item["status"], item["submitted_ns"] + 7_000_000, "review.status")
        report = example.public_observation(item)
        self.assertEqual(1, report["requestAcknowledgmentObservedMillis"])
        self.assertEqual(5, report["firstUsefulFindingObservedMillis"])
        self.assertEqual(7, report["terminalResultObservedMillis"])
        self.assertFalse(report["requiredAnalysisComplete"])
        self.assertEqual({"elapsedMillis": 9, "cpuMillis": 3, "peakRssBytes": None, "outputBytes": 96}, report["analysisReportedMetrics"])
        self.assertNotIn(PRIVATE_TEXT, json.dumps(report))
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertNotIn("cpuAndRss", report)
        negative = item_document("agent-b")
        example.note_observation(negative, negative["status"], negative["submitted_ns"] + 8_000_000, "review.status")
        self.assertIsNone(example.public_observation(negative)["firstUsefulFindingObservedMillis"])

    def test_metrics_reject_boolean_negative_nonfinite_and_unmeasured_missing_values(self):
        for field, value in (("elapsedMillis", True), ("cpuMillis", -1), ("outputBytes", float("inf")),
                             ("peakRssBytes", "secret"), ("outputBytes", None), ("elapsedMillis", 2**63)):
            result = result_document()
            result["metrics"][field] = value
            self.fails("invalid_analysis_metrics", example.analysis_metrics, result)

    def test_expected_negative_result_and_executed_coverage_are_required_not_unavailability(self):
        for name in ("agent-a", "agent-b"):
            item = item_document(name)
            example.verify_selected_execution(item)
            for field, value in (("provenance", "not_executed"), ("certificateId", CERTIFICATE), ("state", "unavailable"),
                                 ("qualified", True)):
                changed = copy.deepcopy(item)
                changed["status"]["result"]["coverage"][0][field] = value
                self.fails("selected_execution_missing", example.verify_selected_execution, changed)
        item = item_document()
        item["status"]["result"]["findings"] = []
        self.fails("expected_compiler_result_missing", example.verify_selected_execution, item)


@unittest.skipUnless(HAS_GUARD, "POSIX fake broker support required")
class FakeBrokerReportTests(unittest.TestCase):
    def test_prepare_only_with_fake_owner_initializes_empty_root_before_private_requests(self):
        self.exercise_owner_preparation(authorize_execution=False)

    def test_actual_run_preparation_keeps_explicit_authorization_and_enters_review(self):
        self.exercise_owner_preparation(authorize_execution=True)

    def exercise_owner_preparation(self, *, authorize_execution):
        fixture = {"providers": provider_document(),
                   "clients": {name: client_document(name, execution_authorized=authorize_execution) for name in example.SOURCES},
                   "sources": example.SOURCES, "authorizeExecution": authorize_execution}
        source = "fixture=" + repr(fixture) + "\n" + r'''
import json,os,pathlib,sys
args=sys.argv[1:]
options={}
words=[]
i=0
while i<len(args):
    word=args[i]
    i+=1
    if word=='--authorize-execution':
        options[word]=True
    elif word.startswith('--'):
        options[word]=args[i]
        i+=1
    else:
        words.append(word)
root=pathlib.Path(options['--state'])
assert options['--compiler-java-home']
assert options['--workers']=='0'
def private_write(path,value):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'w',encoding='utf-8') as stream:
        json.dump(value,stream)
        stream.write('\n')
method=words[0]
if method=='providers':
    assert not list(root.iterdir()), 'Foreign files must not precede CLI state initialization'
    private_write(root/'owner.json',{'synthetic':True})
    result=fixture['providers']
elif method=='register':
    assert (root/'owner.json').is_file()
    name=options['--client']
    private_write(options['--out'],fixture['clients'][name]['registration'])
    result={'contractVersion':'ktchecker/preview-1','clientId':name,'state':'registered'}
elif method=='overlay':
    request=json.loads(pathlib.Path(options['--request']).read_text())
    name=request['clientId']
    assert request['changes'][0]['content']==fixture['sources'][name]
    assert request['expectedHeadRevision']==(0 if name=='agent-a' else 1)
    snapshot=fixture['clients'][name]['prepared']['input']['snapshot']['snapshotId']
    # Production SnapshotCommit is an owner DTO without contractVersion.
    result={'manifest':{'snapshotId':snapshot},'headRevision':request['expectedHeadRevision']+1}
elif method=='prepare':
    request=json.loads(pathlib.Path(options['--request']).read_text())
    name=request['clientId']
    assert ('--authorize-execution' in options) is fixture['authorizeExecution']
    assert request['compilationFiles']==[name+'/Main.kt']
    assert request['compilerVersion']=='2.4.21'
    assert request['requiredRuleIds']==fixture['clients'][name]['prepared']['input']['requiredRuleIds']
    private_write(options['--out'],fixture['clients'][name]['prepared'])
    result={'contractVersion':'ktchecker/preview-1','state':'prepared','installedProviders':fixture['providers']}
else:
    raise SystemExit(5)
print(json.dumps(result),flush=True)
'''
        original_init = example.Controller.__init__

        def fake_init(controller, home, root, compiler_java_home):
            original_init(controller, home, root, compiler_java_home)
            controller.command = command(source) + ["--state", str(root.path), "--compiler-java-home", str(compiler_java_home)]

        with tempfile.TemporaryDirectory(prefix="ktchecker-fake-owner-") as temporary:
            base = Path(temporary)
            (base / "distribution" / "lib").mkdir(parents=True)
            (base / "compiler" / "bin").mkdir(parents=True)
            (base / "compiler" / "bin" / "java").write_text("synthetic, never executed")
            output, errors = io.StringIO(), io.StringIO()
            # Only tiny Python owner fakes execute. The ordinary mode's review handoff
            # is observed here, not substituted for real compiler/provider evidence.
            with (tiny_cleanup_processes(), mock.patch.object(example.Controller, "__init__", new=fake_init),
                  mock.patch.object(example.Controller, "review", autospec=True,
                                    return_value={"state": "review-entered", "releaseQualified": False}) as review,
                  contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors)):
                status = example.main(["--distribution", str(base / "distribution"), "--output", str(base / "new"),
                                       "--compiler-java-home", str(base / "compiler"),
                                       *([] if authorize_execution else ["--prepare-only"])])
            self.assertEqual(0, status, errors.getvalue())
            report = json.loads(output.getvalue())
            self.assertFalse(report["releaseQualified"])
            if authorize_execution:
                self.assertEqual("review-entered", report["state"])
                review.assert_called_once()
                self.assertEqual([fixture["clients"][name] for name in example.SOURCES], review.call_args.args[1])
            else:
                review.assert_not_called()
                self.assertEqual("prepared", report["state"])
                self.assertEqual(2, report["clientCount"])
                self.assertIs(report["executionAuthorized"], False)
                self.assertFalse(report["compilerExecutionObserved"])
                self.assertEqual(example.provider_inspection(provider_document()), report["compilerInspection"])
                self.assertGreater(report["cleanup"]["ownerCommandObservedMillisTotal"], 0)
                self.assertFalse(report["cleanup"]["forcedHelperLossGuaranteed"])
                self.assertEqual("not_attempted", report["contextDelivery"]["state"])
            self.assertFalse((base / "new" / "observations.json").exists())
            for name in example.SOURCES:
                credential = base / "new" / (name + "-credentials.json")
                self.assertEqual(0o600, stat.S_IMODE(credential.stat().st_mode))
                prepared_file = base / "new" / (name + "-prepared.json")
                self.assertEqual(0o600, stat.S_IMODE(prepared_file.stat().st_mode))
                retained = json.loads(prepared_file.read_text(encoding="utf-8"))
                self.assertIs(retained["executionAuthorized"], authorize_execution)
                self.assertEqual(fixture["clients"][name]["prepared"], retained)
            self.assertNotIn(PRIVATE_TEXT, output.getvalue() + errors.getvalue())
            self.assertNotIn(SECRET, output.getvalue() + errors.getvalue())

    def test_fake_controller_report_advances_cursors_and_never_claims_model_or_project_coverage(self):
        clients = [client_document(name) for name in example.SOURCES]
        fixture = {"providers": provider_document(), "clients": clients,
                   "statuses": {name: status_document(name) for name in example.SOURCES}}
        source = "fixture=" + repr(fixture) + "\n" + r'''
import copy,hashlib,json,sys
polls={name:0 for name in fixture['statuses']}
events={name:0 for name in fixture['statuses']}
for line in sys.stdin.buffer:
    request=json.loads(line)
    assert request.get('findingWireProfile')=='ktchecker/finding-retrieval-attachments-1'
    method,params=request['method'],request['params']
    error=None
    if method=='doctor':
        result={'contractVersion':'ktchecker/preview-1','command':'doctor','installedProvidersInspected':True,
                'installedProviders':fixture['providers']}
    else:
        name=params['credential']['clientId']
        status=copy.deepcopy(fixture['statuses'][name])
        if method=='review.submit':
            status.update(state='queued',result=None)
            result=status
        elif method=='review.status':
            polls[name]+=1
            if polls[name]==1:
                status.update(state='running',result=None)
            result=status
        elif method=='review.events':
            expected=None if events[name]==0 else 'cursor-'+str(events[name])
            if params.get('cursor')!=expected:
                error={'code':'cursor-error','message':'private-owner-reason-and-source-marker'}
            events[name]+=1
            if polls[name]==1:
                status['state']='running'
            payload=json.dumps(status,separators=(',',':'),sort_keys=True)
            event={'contractVersion':'ktchecker/preview-1','eventId':'dddddddd-dddd-dddd-dddd-dddddddddddd',
                   'clientId':name,'reviewId':status['reviewId'],'patchId':status['patchId'],
                   'sourceSnapshotId':status['sourceSnapshotId'],'generation':1,'sequence':events[name],
                   'kind':'progress' if polls[name]==1 else 'result','deliveryRequired':True,'payload':payload,
                   'payloadDigest':'sha256:'+hashlib.sha256(payload.encode()).hexdigest()}
            result={'contractVersion':'ktchecker/preview-1','events':[event],'cursor':'cursor-'+str(events[name]),'hasMore':False}
        else:
            raise SystemExit(5)
    response={'contractVersion':'ktchecker/preview-1','id':request['id']}
    response['error' if error else 'result']=error if error else result
    if not error:
        response['findingWireProfile']='ktchecker/finding-retrieval-attachments-1'
    print(json.dumps(response,separators=(',',':')),flush=True)
'''
        with tempfile.TemporaryDirectory(prefix="ktchecker-fake-broker-") as temporary:
            base = Path(temporary)
            (base / "compiler" / "bin").mkdir(parents=True)
            (base / "compiler" / "bin" / "java").write_text("synthetic, never executed")
            with example.PrivateDirectory(base / "new") as root:
                controller = example.Controller(base / "distribution", root, base / "compiler")
                controller.command = command(source)  # Scoped instance injection; only a tiny Python fake is run.
                controller.inspection = example.provider_inspection(provider_document())
                controller.preparation_ms = 1.0
                with tiny_cleanup_processes():
                    report = controller.review(clients)
                self.assertEqual(report, root.read_json("observations.json"))
        self.assertFalse(report["releaseQualified"])
        self.assertFalse(report["fullProjectCoverageEstablished"])
        self.assertFalse(report["targetQualificationEstablished"])
        self.assertEqual(example.provider_inspection(provider_document()), report["compilerInspection"])
        self.assertGreaterEqual(report["cleanup"]["observedMillis"], limits().cleanup_seconds * 1000)
        self.assertFalse(report["cleanup"]["forcedHelperLossGuaranteed"])
        self.assertEqual("not_attempted", report["contextDelivery"]["state"])
        self.assertEqual("not_measured", report["wholeBrokerProcessTreeResources"]["state"])
        self.assertFalse(report["brokerStartup"]["establishesCompilerExecution"])
        self.assertGreaterEqual(report["brokerStartup"]["readyObservedMillisFromSpawnStart"], report["brokerStartup"]["spawnObservedMillis"])
        self.assertGreater(report["brokerTransport"]["stdoutBytes"], 0)
        first, negative = report["observations"]
        self.assertEqual("review.events", first["firstUsefulFindingObservedVia"])
        self.assertLessEqual(first["firstUsefulFindingObservedMillis"], first["terminalResultObservedMillis"])
        self.assertIsNone(negative["firstUsefulFindingObservedMillis"])
        self.assertEqual("review.status", negative["terminalResultObservedVia"])
        for observation in report["observations"]:
            self.assertFalse(observation["requiredAnalysisComplete"])
            self.assertTrue(observation["duplicateReusedReviewId"])
        self.assertNotIn(PRIVATE_TEXT, json.dumps(report))
        self.assertNotIn(SECRET, json.dumps(report))


if __name__ == "__main__":
    unittest.main()
