"""Synthetic private-file/fake-transport checks only; no JVM, provider or model execution.

The fake cleanup numbers are fixtures, not process-containment or performance evidence.
Actual group teardown is covered separately by the existing BoundedProcess tests.
"""
from __future__ import annotations

import copy
import os
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

import run_preview_example as example


DIGEST = "sha256:" + "a" * 64
SNAPSHOT = "sha256:" + "b" * 64


def provider_document():
    target = "linux-x86_64"
    return {"contractVersion": example.CONTRACT, "releaseQualified": False, "doesNotEstablishTargetExecution": True,
            "compiler": {"state": "toolchain_bound", "toolchainDigest": DIGEST,
                         "requiresExplicitExecutionAuthorization": True, "javaHomeSelection": "explicit_owner_selection",
                         "runtimeRequirement": "complete_link_free_jdk21_image"},
            "processContainment": {"state": "available", "target": target, "bundledBytesVerified": True,
                                   "executionVerified": False, "releaseQualified": False,
                                   "identity": {"protocolVersion": example.CONTAINMENT_PROTOCOL, "target": target,
                                                "sourceDigest": "sha256:" + "c" * 64,
                                                "binaryDigest": "sha256:" + "d" * 64}}}


class FakeTransport:
    def __init__(self):
        self.counts = {"stdinBytes": 0, "stdoutBytes": 0, "stderrBytes": 0}
        self.close_calls = 0
        self.fail_close = False
        self.limits = example.PipeLimits()

    def statistics(self):
        return dict(self.counts, includesNdjsonNewlines=True, stderrRetained=False)

    def close(self):
        self.close_calls += 1
        if self.fail_close:
            raise example.ExampleError("cleanup_failed")

    def cleanup_statistics(self):
        return {"observedMillis": 4000.0, "termToLastKillGraceSeconds": self.limits.cleanup_seconds,
                "forcedHelperLossGuaranteed": False, "escapedDescendantsSupported": False}


class FakeRpc:
    def __init__(self, transport):
        self.transport = transport
        self.calls = []
        self.transform = lambda response: response
        self.error = None

    def call(self, method, params, *, deadline):
        self.calls.append((method, copy.deepcopy(params), deadline))
        for field, increment in (("stdinBytes", 11), ("stdoutBytes", 23), ("stderrBytes", 2)):
            self.transport.counts[field] += increment
        if self.error is not None:
            raise example.ExampleError(self.error)
        if time.monotonic() >= deadline:
            raise example.ExampleError("transport_timeout")
        if method == "owner.overlay":
            response = {"manifest": {"snapshotId": SNAPSHOT}, "headRevision": len(self.calls)}
        else:
            document = params["document"]
            prepared = {"contractVersion": example.CONTRACT, "binding": {"clientId": document["clientId"]},
                        "patchId": document["patchId"], "idempotencyKey": document["idempotencyKey"],
                        "executionAuthorized": params["authorizeExecution"],
                        "input": {"snapshot": {"snapshotId": document["snapshotId"]},
                                  "context": {"toolchainDigest": DIGEST}}}
            response = {"prepared": prepared, "installedProviders": provider_document()}
        return self.transform(response)


@unittest.skipUnless(os.name == "posix" and hasattr(os, "O_NOFOLLOW"), "owner example requires anchored POSIX private files")
class OwnerCaptureSessionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="ktc-capture-fixture-")
        self.addCleanup(self.directory.cleanup)
        self.root = example.PrivateDirectory(Path(self.directory.name) / "private")
        self.addCleanup(self.root.close)
        self.inspection = example.provider_inspection(provider_document())
        self.transport = FakeTransport()
        self.rpc = FakeRpc(self.transport)
        process_patch = mock.patch.object(example, "BoundedProcess", return_value=self.transport)
        client_patch = mock.patch.object(example, "NdjsonClient", return_value=self.rpc)
        self.process, self.client = process_patch.start(), client_patch.start()
        self.addCleanup(process_patch.stop)
        self.addCleanup(client_patch.stop)
        self.command = ["selected-java", "-cp", "selected-distribution", "dev.ktchecker.cli.MainKt"]
        self.deadline = time.monotonic() + 120.0
        self.root.write_json("overlay.json", {"workspaceId": "workspace", "worktreeId": "worktree", "clientId": "client",
                                             "sessionId": "session", "patchId": "patch", "changes": []})
        self.root.write_json("compilation.json", {"clientId": "client", "snapshotId": SNAPSHOT,
                                                 "patchId": "patch", "idempotencyKey": "request",
                                                 "compilationFiles": ["Main.kt"], "requiredRuleIds": [example.RULE]})

    def session(self):
        return example.OwnerCaptureSession(self.command, self.root, self.inspection)

    def overlay(self, session, *, deadline=None):
        return session.call("overlay", "--request", str(self.root.file_path("overlay.json")),
                            deadline=self.deadline if deadline is None else deadline)

    def prepare(self, session, *, authorize=False, name="prepared.json"):
        args = ["prepare", "--request", str(self.root.file_path("compilation.json"))]
        if authorize:
            args.append("--authorize-execution")
        return session.call(*args, "--out", str(self.root.file_path(name)), deadline=self.deadline)

    def test_one_lazy_owner_across_two_capture_rounds_and_one_full_cleanup(self):
        session = self.session()
        self.process.assert_not_called()
        deltas = []
        for authorized, output in ((True, "first-prepared.json"), (False, "second-prepared.json")):
            deltas.append(self.overlay(session)[1])
            response, statistics = self.prepare(session, authorize=authorized, name=output)
            deltas.append(statistics)
            self.assertIs(response["executionAuthorized"], authorized)
            self.assertIs(self.root.read_json(output)["executionAuthorized"], authorized)
        self.process.assert_called_once_with(self.command + ["owner-capture-session", "--owner-action", "--workers", "0"])
        self.assertNotIn("--authorize-execution", self.process.call_args.args[0])
        self.assertEqual(["owner.overlay", "owner.prepare", "owner.overlay", "owner.prepare"], [call[0] for call in self.rpc.calls])
        self.assertEqual([True, False], [call[1]["authorizeExecution"] for call in self.rpc.calls if call[0] == "owner.prepare"])
        self.assertTrue(all(call[1]["expectedToolchainDigest"] == DIGEST for call in self.rpc.calls))
        self.assertTrue(all("authorizeExecution" not in call[1] for call in self.rpc.calls if call[0] == "owner.overlay"))
        for field, expected in (("stdinBytes", 11), ("stdoutBytes", 23), ("stderrBytes", 2)):
            self.assertEqual([expected] * 4, [value[field] for value in deltas])
            self.assertEqual(self.transport.counts[field], sum(value[field] for value in deltas))
        session.close()
        session.close()
        self.assertEqual(1, self.transport.close_calls)
        self.assertEqual(4.0, session.record["cleanup"]["termToLastKillGraceSeconds"])
        self.assertEqual(4, session.record["completedCaptureCommands"])
        self.assertTrue(session.record["transportTotalsOverlapOwnerCommandDeltas"])
        self.assertFalse(session.record["establishesCompilerExecutionOrWarmth"])

    def test_original_absolute_deadline_includes_spawn_and_first_reply(self):
        session = self.session()
        with mock.patch.object(example.time, "monotonic", return_value=100.0), \
                mock.patch.object(example.time, "monotonic_ns", side_effect=[100_000_000_000, 101_000_000_000, 104_000_000_000]):
            self.overlay(session, deadline=110.0)
        self.assertEqual(110.0, self.rpc.calls[0][2])
        self.assertEqual(100_000_000_000, session.startup["spawnStartedMonotonicNanos"])
        self.assertEqual(1000.0, session.startup["spawnObservedMillis"])
        self.assertEqual(4000.0, session.startup["firstSuccessfulCaptureReplyMillisFromSpawnStart"])
        self.assertTrue(session.startup["startupIncludedInFirstCaptureLatency"])
        self.assertFalse(session.startup["establishesCompilerExecutionOrWarmth"])

    def test_slow_spawn_cannot_reset_or_extend_deadline(self):
        session = self.session()
        now = [100.0]
        def spawn(_command):
            now[0] = 120.0
            return self.transport
        self.process.side_effect = spawn
        with mock.patch.object(example.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaisesRegex(example.ExampleError, "transport_timeout"):
                self.overlay(session, deadline=110.0)
        self.assertEqual(110.0, self.rpc.calls[0][2])
        self.assertTrue(session.poisoned)
        with self.assertRaisesRegex(example.ExampleError, "owner_capture_session_failed"):
            self.overlay(session)
        self.process.assert_called_once()

    def test_invalid_or_elapsed_deadline_cannot_spawn(self):
        for deadline in (True, float("nan"), float("inf"), -1.0, 0.0):
            with self.subTest(deadline=repr(deadline)):
                with self.assertRaisesRegex(example.ExampleError, "invalid_timeout"):
                    self.overlay(self.session(), deadline=deadline)
        self.process.assert_not_called()
        self.assertEqual([], self.rpc.calls)

    def test_closed_command_forms_never_spawn_or_dispatch_other_owner_rights(self):
        request = str(self.root.file_path("compilation.json"))
        out = str(self.root.file_path("prepared.json"))
        cases = [("rpc",), ("register",), ("owner-model-session",), ("review.submit",),
                 ("prepare", "--request", request, "--authorize-execution", "--authorize-execution", "--out", out),
                 ("prepare", "--request", request, "--out", out, "--workers", "1"),
                 ("overlay", "--request", request, "--authorize-execution")]
        for args in cases:
            with self.assertRaisesRegex(example.ExampleError, "owner_capture_command_invalid"):
                self.session().call(*args, deadline=self.deadline)
        self.process.assert_not_called()
        self.assertEqual([], self.rpc.calls)

    def test_existing_output_is_rejected_before_start_or_send_without_overwrite(self):
        self.root.write_json("prepared.json", {"existing": True})
        session = self.session()
        with self.assertRaisesRegex(example.ExampleError, "private_file_exists"):
            self.prepare(session, authorize=True)
        self.process.assert_not_called()
        self.assertEqual([], self.rpc.calls)
        self.assertEqual({"existing": True}, self.root.read_json("prepared.json"))
        self.assertTrue(session.poisoned)

    def test_outside_and_noncanonical_output_is_rejected_before_send(self):
        for selected in (str(Path(self.directory.name) / "elsewhere.json"), str(self.root.path / ".." / "elsewhere.json"),
                         str(self.root.path) + "/./prepared.json"):
            with self.assertRaisesRegex(example.ExampleError, "unsafe_output_path"):
                self.session().call("prepare", "--request", str(self.root.file_path("compilation.json")),
                                    "--out", selected, deadline=self.deadline)
        self.process.assert_not_called()
        self.assertEqual([], self.rpc.calls)

    def test_running_owner_local_preflight_failure_poison_does_not_reconnect(self):
        session = self.session()
        self.overlay(session)
        self.root.write_json("prepared.json", {"existing": True})
        with self.assertRaisesRegex(example.ExampleError, "private_file_exists"):
            self.prepare(session)
        with self.assertRaisesRegex(example.ExampleError, "owner_capture_session_failed"):
            self.prepare(session, name="different-prepared.json")
        self.process.assert_called_once()
        self.assertEqual(1, len(self.rpc.calls))
        session.close()
        self.assertEqual(1, self.transport.close_calls)

    def test_only_designated_private_request_is_read_before_start(self):
        selected = str(Path(self.directory.name) / "outside.json")
        with self.assertRaisesRegex(example.ExampleError, "unsafe_output_path"):
            self.session().call("overlay", "--request", selected, deadline=self.deadline)
        with self.assertRaisesRegex(example.ExampleError, "private_file_failed"):
            self.session().call("overlay", "--request", str(self.root.file_path("missing.json")), deadline=self.deadline)
        self.process.assert_not_called()
        self.assertEqual([], self.rpc.calls)

    def test_transport_or_protocol_error_poison_retains_uncertain_counts_without_replay(self):
        session = self.session()
        self.rpc.error = "finding_wire_profile_outcome_uncertain"
        with self.assertRaisesRegex(example.ExampleError, "finding_wire_profile_outcome_uncertain"):
            self.overlay(session)
        self.rpc.error = None
        with self.assertRaisesRegex(example.ExampleError, "owner_capture_session_failed"):
            self.overlay(session)
        self.process.assert_called_once()
        self.assertEqual(1, len(self.rpc.calls))
        session.close()
        self.assertEqual(0, session.record["completedCaptureCommands"])
        self.assertGreater(session.record["transport"]["stdinBytes"], 0)
        self.assertTrue(session.record["poisoned"])

    def test_failed_start_never_restarts_and_unavailable_cleanup_is_not_fabricated(self):
        session = self.session()
        self.process.side_effect = example.ExampleError("process_start_failed")
        with self.assertRaisesRegex(example.ExampleError, "process_start_failed"):
            self.overlay(session)
        with self.assertRaisesRegex(example.ExampleError, "owner_capture_session_failed"):
            self.overlay(session)
        self.process.assert_called_once()
        session.close()
        self.assertEqual("startup_attempted_no_retained_transport", session.record["state"])
        self.assertIsNone(session.record["transport"])
        self.assertIsNone(session.record["startup"]["firstSuccessfulCaptureReplyMillisFromSpawnStart"])
        self.assertEqual({"state": "unavailable"}, session.record["cleanup"])
        self.assertTrue(session.record["poisoned"])

    def test_response_consent_is_exact_boolean_before_output_publication(self):
        for consent in (True, 0, 1, "false", None):
            with self.subTest(consent=repr(consent)):
                self.rpc.transform = lambda response, value=consent: dict(response, prepared=dict(response["prepared"], executionAuthorized=value))
                session = self.session()
                with self.assertRaisesRegex(example.ExampleError, "owner_capture_response_invalid"):
                    self.prepare(session, authorize=False)
                self.assertFalse(self.root.file_path("prepared.json").exists())
                self.assertTrue(session.poisoned)
        self.assertEqual(5, len(self.rpc.calls))

    def test_wrong_snapshot_toolchain_client_or_provider_cannot_publish_prepared_output(self):
        def changed(field):
            def transform(response):
                if field == "snapshot":
                    response["prepared"]["input"]["snapshot"]["snapshotId"] = "sha256:" + "e" * 64
                elif field == "client":
                    response["prepared"]["binding"]["clientId"] = "another-client"
                elif field == "context":
                    response["prepared"]["input"]["context"]["toolchainDigest"] = "sha256:" + "f" * 64
                elif field == "provider":
                    response["installedProviders"]["compiler"]["toolchainDigest"] = "sha256:" + "f" * 64
                else:
                    response["unknown"] = True
                return response
            return transform
        for field in ("snapshot", "client", "context", "provider", "extra"):
            self.rpc.transform = changed(field)
            with self.assertRaises(example.ExampleError):
                self.prepare(self.session())
            self.assertFalse(self.root.file_path("prepared.json").exists())

    def test_root_identity_failure_after_reply_prevents_output_and_poisons(self):
        session = self.session()
        original = self.rpc.transform
        def transform(response):
            self.root.verify = mock.Mock(side_effect=example.ExampleError("private_root_changed"))
            return original(response)
        self.rpc.transform = transform
        with self.assertRaisesRegex(example.ExampleError, "private_root_changed"):
            self.prepare(session)
        self.assertFalse((self.root.path / "prepared.json").exists())
        self.assertTrue(session.poisoned)

    def test_failed_cleanup_is_reported_and_not_retried(self):
        session = self.session()
        self.overlay(session)
        self.transport.fail_close = True
        with self.assertRaisesRegex(example.ExampleError, "cleanup_failed"):
            session.close()
        session.close()
        self.assertEqual(1, self.transport.close_calls)
        self.assertEqual("failed", session.record["cleanup"]["state"])
        self.assertFalse(session.record["cleanup"]["forcedHelperLossGuaranteed"])
        self.assertTrue(session.record["poisoned"])

    def controller_shell(self, capture):
        controller = object.__new__(example.BenchmarkController)
        controller.root, controller.owner_capture = self.root, capture
        controller.command, controller.inspection = self.command, self.inspection
        controller.failure, controller.preparation_ms = None, 0.0
        controller.sessions, controller.owner_transport = [], []
        controller.scenario = object()
        controller.ledger = SimpleNamespace(plan=[], abort=mock.Mock(), report=mock.Mock(return_value={}))
        controller.setup = mock.Mock()
        controller.close_broker = mock.Mock()
        return controller

    def test_broker_cleanup_failure_still_closes_capture_and_fails_collection(self):
        capture = self.session()
        self.overlay(capture)
        controller = self.controller_shell(capture)
        controller.close_broker.side_effect = example.ExampleError("cleanup_failed")
        with mock.patch.object(example.benchmark, "batches", return_value=[]):
            report = controller.run()
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual("preview_example_cleanup_failed", report["collectorFailure"]["code"])
        self.assertEqual(1, self.transport.close_calls)
        self.assertEqual("owned_group_signaled_leader_reaped_pipes_closed", report["ownerCaptureSession"]["cleanup"]["state"])

    def test_capture_cleanup_failure_also_fails_collection(self):
        capture = self.session()
        self.overlay(capture)
        self.transport.fail_close = True
        controller = self.controller_shell(capture)
        with mock.patch.object(example.benchmark, "batches", return_value=[]):
            report = controller.run()
        controller.close_broker.assert_called_once()
        self.assertEqual("failed", report["collectionState"])
        self.assertEqual("failed", report["ownerCaptureSession"]["cleanup"]["state"])

    def test_mocked_owner_or_no_trials_reports_not_started_without_fabricated_metrics(self):
        controller = self.controller_shell(None)
        with mock.patch.object(example.benchmark, "batches", return_value=[]):
            report = controller.run()
        self.assertEqual("collected", report["collectionState"])
        self.assertEqual({"state": "not_started", "establishesCompilerExecutionOrWarmth": False}, report["ownerCaptureSession"])
        self.assertEqual({"stdinBytes": 0, "stdoutBytes": 0, "stderrBytes": 0}, report["ownerCommandTransportTotals"])
        self.process.assert_not_called()

    def test_benchmark_calls_retain_owner_but_setup_commands_remain_one_shot(self):
        controller = self.controller_shell(None)
        controller.deadline = mock.Mock(return_value=self.deadline)
        controller.deadline_ns = mock.Mock(return_value=int(self.deadline * 1e9))
        statistics = {"stdinBytes": 0, "stdoutBytes": 7, "stderrBytes": 0}
        with mock.patch.object(example, "run_json_command", return_value=(provider_document(), statistics)) as one_shot:
            controller.call("providers")
            controller.call("overlay", "--request", str(self.root.file_path("overlay.json")))
            retained = controller.owner_capture
            controller.call("overlay", "--request", str(self.root.file_path("overlay.json")))
        self.assertIs(retained, controller.owner_capture)
        one_shot.assert_called_once_with(self.command + ["--workers", "0", "providers"], deadline=self.deadline)
        self.process.assert_called_once()
        self.assertEqual([self.deadline, self.deadline], [call[2] for call in self.rpc.calls])
        self.assertEqual(3, len(controller.owner_transport))

    def test_nonbenchmark_controller_remains_one_shot(self):
        # Constructor checks only this owner-selected path; the fake launcher is never executable or run.
        jdk = Path(self.directory.name) / "synthetic-jdk"
        (jdk / "bin").mkdir(parents=True, mode=0o700)
        launcher = jdk / "bin" / "java"
        launcher.write_bytes(b"synthetic-path-preflight-only\n")
        launcher.chmod(0o600)
        controller = example.Controller(Path("selected-home"), self.root, jdk)
        statistics = {"stdinBytes": 0, "stdoutBytes": 7, "stderrBytes": 0}
        with mock.patch.object(example, "run_json_command", return_value=({}, statistics)) as one_shot, \
                mock.patch.object(example, "OwnerCaptureSession") as capture:
            controller.call("overlay", "--request", "selected.json")
            controller.call("prepare", "--request", "selected.json", "--out", "prepared.json")
        self.assertEqual(2, one_shot.call_count)
        capture.assert_not_called()


if __name__ == "__main__":
    unittest.main()
