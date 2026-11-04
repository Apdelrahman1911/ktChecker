"""Independent probe-ownership regressions, NOT native containment qualification.

Every process, wait status and group signal below is a test double. No launcher,
JVM, shell or real child process is executed or signaled. These tests call the
actual Python preparation probe to verify ownership/deadline failure boundaries;
they do not exercise the separate Kotlin runner or its forthcoming C supervisor.

Run: python3 -m unittest discover -s tests/audit -p 'test_jdk_probe_audit.py' -v
"""

from __future__ import annotations

from contextlib import ExitStack
import errno
import importlib.util
import io
import os
from pathlib import Path
import signal
import sys
from types import SimpleNamespace
import unittest
from unittest import mock


_ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "ktchecker_jdk_probe_under_independent_audit", _ROOT / "scripts/prepare_jdk_image.py"
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("Cannot load the preparation probe under audit")
probe = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = probe
_SPEC.loader.exec_module(probe)

_WAIT_HOST = os.name == "posix" and all(
    hasattr(os, name) for name in ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT")
)


class _SyntheticChild:
    """Not a Popen object: attempting an unreviewed reaping path fails the test."""

    def __init__(self, case: unittest.TestCase, events: list, clock: list[float], *,
                 exit_code: int = 0, wait_advance: float = 0):
        self.case, self.events, self.clock = case, events, clock
        self.exit_code, self.wait_advance = exit_code, wait_advance
        self.pid = 567_890  # Synthetic only; killpg and Popen are always mocked together.
        self.stdout = io.BytesIO(b"")
        self.stderr = io.BytesIO(b'openjdk version "21.0.12"\n')
        self.returncode = None
        self.reaped = False

    def poll(self):
        self.case.fail("poll() can reap: ownership must be retained until the final signal")

    def wait(self, timeout):
        self.case.assertFalse(self.reaped, "The same child cannot be reaped twice")
        self.case.assertEqual(2, timeout)
        self.case.assertTrue(any(event[0] == "signal" for event in self.events),
                             "First reap occurred before the final group-signal attempt")
        self.events.append(("reap", self.pid))
        self.reaped = True
        self.clock[0] += self.wait_advance
        self.returncode = self.exit_code
        return self.returncode


@unittest.skipUnless(_WAIT_HOST, "Pure controls require POSIX waitid constants; no native child is run")
class JdkProbeOwnershipAudit(unittest.TestCase):
    def control(self, *, observation="exited", wait_error=None, signal_error=None,
                observe_advance=0.0, wait_advance=0.0, exit_code=0):
        events, clock = [], [0.0]
        child = _SyntheticChild(self, events, clock, exit_code=exit_code, wait_advance=wait_advance)

        def observe(id_type, child_id, flags):
            self.assertEqual(probe.os.P_PID, id_type)
            self.assertEqual(child.pid, child_id)
            self.assertEqual(probe.os.WEXITED | probe.os.WNOHANG | probe.os.WNOWAIT, flags)
            self.assertFalse(child.reaped)
            events.append(("observe_unreaped", child_id))
            clock[0] += observe_advance
            if wait_error is not None:
                raise wait_error
            return SimpleNamespace(si_pid=child_id) if observation == "exited" else None

        def signal_group(group_id, selected_signal):
            self.assertFalse(child.reaped, "Signaling after reap can hit a reused PGID")
            self.assertEqual(child.pid, group_id)
            self.assertEqual(signal.SIGKILL, selected_signal)
            events.append(("signal", group_id))
            if signal_error is not None:
                raise signal_error

        def launch(command, **options):
            self.assertEqual("/AUDIT-NONEXISTENT/bin/java", command[0])
            self.assertEqual("-version", command[-1])
            self.assertTrue(options["start_new_session"])
            events.append(("synthetic_launch", child.pid))
            return child

        # All process-related functions are substituted before invoking production
        # code. Synthetic PID values can never reach real signaling or wait calls.
        with ExitStack() as stack:
            stack.enter_context(mock.patch.object(probe.subprocess, "Popen", side_effect=launch))
            stack.enter_context(mock.patch.object(probe.os, "waitid", side_effect=observe))
            stack.enter_context(mock.patch.object(probe.os, "killpg", side_effect=signal_group))
            stack.enter_context(mock.patch.object(probe.signal, "getsignal", return_value=signal.SIG_DFL))
            stack.enter_context(mock.patch.object(probe.time, "monotonic", side_effect=lambda: clock[0]))
            report = probe.probe_runtime(Path("/AUDIT-NONEXISTENT/bin/java"), 1.0, 512, "21.0.12")
        self.assertFalse(report["qualification"])
        self.assertEqual(1, sum(event[0] == "synthetic_launch" for event in events))
        return report, events, child

    def test_even_already_exited_leader_is_signaled_before_first_reap(self):
        report, events, child = self.control()
        self.assertEqual("passed", report["status"], report)
        self.assertTrue(child.reaped)
        self.assertEqual(["synthetic_launch", "observe_unreaped", "signal", "reap"],
                         [event[0] for event in events])

    def test_lost_child_ownership_never_signals_or_reaps_the_old_numeric_id(self):
        for failure in (ChildProcessError("synthetic ownership loss"),
                        OSError(errno.ECHILD, "synthetic missing child")):
            with self.subTest(failure=type(failure).__name__):
                report, events, child = self.control(wait_error=failure)
                self.assertEqual("failed", report["status"])
                self.assertEqual("runtime_child_wait_ownership_lost", report["reason"])
                self.assertIsNone(report["exit_code"])
                self.assertFalse(child.reaped)
                self.assertEqual(["synthetic_launch", "observe_unreaped"], [event[0] for event in events])

    def test_timeout_uses_owned_signal_then_reap_without_turning_valid_text_green(self):
        report, events, child = self.control(observation="running", observe_advance=1.1,
                                             exit_code=-signal.SIGKILL)
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_timeout", report["reason"])
        self.assertEqual(-signal.SIGKILL, report["exit_code"])
        self.assertTrue(child.reaped)
        self.assertEqual(["synthetic_launch", "observe_unreaped", "signal", "reap"],
                         [event[0] for event in events])

    def test_cleanup_signal_failure_cannot_be_a_successful_version_probe(self):
        report, events, child = self.control(signal_error=PermissionError("synthetic denied signal"))
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_process_cleanup_failed", report["reason"])
        self.assertEqual(0, report["exit_code"])
        self.assertTrue(child.reaped)
        self.assertEqual("reap", events[-1][0], "No signal is allowed after the reap")

    def test_cleanup_overrun_rejects_an_exit_observed_before_the_deadline(self):
        report, _, child = self.control(observe_advance=0.2, wait_advance=0.9)
        self.assertTrue(child.reaped)
        self.assertEqual(0, report["exit_code"])
        self.assertEqual("21.0.12", report["java_version"])
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_timeout", report["reason"])
        self.assertEqual(1100, report["elapsed_millis"])

    def test_custom_or_ignored_sigchld_abstains_before_any_process_operation(self):
        for handler in (signal.SIG_IGN, lambda *_: None):
            with self.subTest(handler="ignored" if handler == signal.SIG_IGN else "custom"), ExitStack() as stack:
                launch = stack.enter_context(mock.patch.object(probe.subprocess, "Popen"))
                wait = stack.enter_context(mock.patch.object(probe.os, "waitid"))
                kill = stack.enter_context(mock.patch.object(probe.os, "killpg"))
                stack.enter_context(mock.patch.object(probe.signal, "getsignal", return_value=handler))
                report = probe.probe_runtime(Path("/AUDIT-NONEXISTENT/bin/java"), 1, 512, "21.0.12")
            launch.assert_not_called(); wait.assert_not_called(); kill.assert_not_called()
            self.assertEqual("unavailable", report["status"])
            self.assertEqual("exclusive_child_wait_ownership_required", report["reason"])
            self.assertFalse(report["executed"])
            self.assertFalse(report["qualification"])

    def test_non_posix_probe_abstains_without_guessing_a_group_launcher(self):
        java = Path("/AUDIT-NONEXISTENT/bin/java")
        with ExitStack() as stack:
            launch = stack.enter_context(mock.patch.object(probe.subprocess, "Popen"))
            wait = stack.enter_context(mock.patch.object(probe.os, "waitid"))
            kill = stack.enter_context(mock.patch.object(probe.os, "killpg"))
            stack.enter_context(mock.patch.object(probe.os, "name", "nt"))
            report = probe.probe_runtime(java, 1, 512, "21.0.12")
        launch.assert_not_called(); wait.assert_not_called(); kill.assert_not_called()
        self.assertEqual("unavailable", report["status"])
        self.assertEqual("unreaped_child_ownership_probe_host_required", report["reason"])
        self.assertFalse(report["executed"])
        self.assertFalse(report["qualification"])


if __name__ == "__main__":
    unittest.main()
