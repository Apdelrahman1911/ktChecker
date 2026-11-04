#!/usr/bin/env python3
"""Separately gated, tiny Linux synthetic controls for owned live preview-2.

Explicit build/run gate (no downloads, provider, JVM or shell):
  python3 native/test_live_supervisor.py --cc /usr/bin/cc -v

Linux syscall controls require an existing /usr/bin/strace. Source authoring or
parser-only tests are not native execution evidence. No test teardown kills a
helper or reconstructs/signals a PGID; waiting probes self-expire. Trace bytes
and temporary binaries stay private. Unresolved attempts retain their custody
evidence rather than deleting it or attempting helper-kill recovery.
"""
from __future__ import annotations

import argparse
from collections import deque
from contextlib import ExitStack, contextmanager
import ctypes
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import build_live_supervisor as builder


CC = Path("/usr/bin/cc")
BOUND, STDOUT, STDERR, ACK, FINAL = 1, 2, 3, 4, 5
ARM, DATA, DISARM, CLOSE_STDIN, CANCEL = 17, 18, 19, 20, 127
F_EXIT, F_REAPED, F_QUIESCENT, F_GROUP, F_EXEC = 1, 2, 4, 8, 16
F_CANCEL, F_TIMEOUT, F_OUTPUT, F_PARENT, F_IO = 32, 64, 128, 256, 512
F_EXEC_FAILED, F_PROTOCOL, F_OWNERSHIP, F_SHUTDOWN = 1024, 2048, 4096, 8192
F_INPUT, F_CONTROL, F_INPUT_INCOMPLETE = 16384, 32768, 65536
F_STDIN_CLOSED, F_OUTPUT_EOF, F_NO_CHILD = 131072, 262144, 524288
COMPLETED_MASK = 0x6001F
MAX_TEST_WIRE = 2 * 1024 * 1024
MAX_TEST_TRACE = 1024 * 1024


class NativeAttempt:
    """Private test custody, not production cleanup or a PID recovery service."""
    def __init__(self, root: Path):
        self.root = root
        self.pending = []
        self.retained = False

    def launch(self, *args, **kwargs):
        previous = self.retained
        # Pre-arm before BOTH construction and registration: a constructor
        # exception or failed append cannot prove that no child was launched.
        self.retained = True
        process = subprocess.Popen(*args, **kwargs)
        self.pending.append(process)  # custody precedes Channel construction
        self.retained = previous
        return process

    def observe(self, process, timeout: float = 5.0) -> bool:
        previous = self.retained
        self.retained = True  # interruption/error cannot permit later deletion
        failed = False
        interruption = None
        # Only close our control stream and wait for the obtained direct child.
        # No communicate(), kill(), numeric-PID lookup or reconstructed group.
        operations = []
        if process.stdin is not None:
            operations.append(process.stdin.close)

        def observe_exit():
            if process.wait(timeout=timeout) is None:
                raise AssertionError("obtained process exit was not observed")

        operations.append(observe_exit)
        for name in ("stdout", "stderr"):
            stream = getattr(process, name)
            if stream is not None:
                operations.append(stream.close)
        for operation in operations:
            try:
                operation()
            except BaseException as error:
                failed = True
                if not isinstance(error, Exception) and interruption is None:
                    interruption = error
        if not failed:
            self.pending = [owned for owned in self.pending if owned is not process]
            self.retained = previous
        if interruption is not None:
            raise interruption
        return not failed

    def cleanup(self) -> bool:
        if self.retained or self.pending:
            # Private local diagnostic only: no raw trace, command or content.
            print(f"native-live unresolved attempt retained: {self.root}", file=sys.stderr)
            return False
        shutil.rmtree(self.root)
        return True


def bounded_trace_text(path: Path) -> str:
    with path.open("rb") as source:
        data = source.read(MAX_TEST_TRACE + 1)
    if len(data) > MAX_TEST_TRACE:
        raise AssertionError("native syscall trace exceeded private read bound")
    return data.decode("utf-8", errors="strict")


def frame(tag: int, payload: bytes = b"") -> bytes:
    return struct.pack(">BI", tag, len(payload)) + payload


def validate_final(payload: bytes, *, input_limit: int, output_limit: int,
                   received: int, ack_count: int, ack_input: int, ack_epoch: int) -> dict:
    if len(payload) != 80:
        raise AssertionError("wrong FINAL size")
    fields = struct.unpack(">IIiIQQQQQQQQ", payload)
    cause, flags, child_exit, reserved, accepted, written, acked, observed, captured, requests, acks, epoch = fields
    if not 0 <= cause <= 12 or flags & ~0xFFFFF or reserved or not -1 <= child_exit <= 255:
        raise AssertionError("invalid FINAL scalar")
    if not 0 <= acked <= written <= accepted <= input_limit:
        raise AssertionError("invalid FINAL input ordering")
    if not 0 <= captured <= observed <= output_limit + 8192 or captured > output_limit or captured != received:
        raise AssertionError("invalid FINAL output ordering")
    if observed != captured and not flags & (F_OUTPUT | F_IO):
        raise AssertionError("unmarked output loss")
    if flags & F_OUTPUT and (observed <= captured or flags & F_OUTPUT_EOF):
        raise AssertionError("invalid output overflow proof")
    if not 0 <= acks <= requests <= 8192 or requests - acks > 1 or acks != ack_count or acked != ack_input:
        raise AssertionError("invalid FINAL credit accounting")
    if not 0 <= epoch <= min(4096, requests) or not ack_epoch <= epoch <= ack_epoch + 1:
        raise AssertionError("invalid FINAL epoch accounting")
    if bool(flags & F_INPUT_INCOMPLETE) != (accepted > written):
        raise AssertionError("invalid incomplete input flag")
    if bool(flags & F_EXIT) != (child_exit >= 0) or (flags & F_REAPED and not flags & F_EXIT):
        raise AssertionError("invalid exit/reap flags")
    if flags & F_EXEC and ((flags & (F_GROUP | F_EXIT)) != (F_GROUP | F_EXIT) or
                           flags & (F_EXEC_FAILED | F_NO_CHILD) or child_exit == 126):
        raise AssertionError("invalid exec evidence")
    if flags & F_EXEC_FAILED and (not flags & F_GROUP or flags & F_EXEC):
        raise AssertionError("invalid exec-failure evidence")
    if flags & F_OWNERSHIP and (not flags & F_SHUTDOWN or flags & F_QUIESCENT):
        raise AssertionError("invalid ownership evidence")
    if flags & F_QUIESCENT and (flags & F_SHUTDOWN or not flags & F_STDIN_CLOSED):
        raise AssertionError("invalid quiescence flags")
    if flags & F_NO_CHILD:
        if (child_exit != -1 or flags & (F_EXIT | F_REAPED | F_GROUP | F_EXEC | F_EXEC_FAILED) or
                any((accepted, written, acked, observed, captured, requests, acks, epoch)) or cause == 0 or
                (flags & (F_QUIESCENT | F_STDIN_CLOSED | F_OUTPUT_EOF)) !=
                (F_QUIESCENT | F_STDIN_CLOSED | F_OUTPUT_EOF)):
            raise AssertionError("invalid no-child evidence")
    elif flags & F_QUIESCENT and (flags & (F_EXIT | F_REAPED)) != (F_EXIT | F_REAPED):
        raise AssertionError("quiescence without actual reaping")
    required = {1: F_CANCEL, 2: F_TIMEOUT, 3: F_OUTPUT, 4: F_PARENT, 5: F_IO, 6: F_EXEC_FAILED,
                8: F_SHUTDOWN, 9: F_PROTOCOL, 10: F_OWNERSHIP | F_SHUTDOWN, 11: F_INPUT, 12: F_CONTROL}
    if cause in required and flags & required[cause] != required[cause]:
        raise AssertionError("missing cause flag")
    if cause == 7 and flags & F_EXEC:
        raise AssertionError("setup failure with exec confirmation")
    if cause == 0 and (flags != COMPLETED_MASK or child_exit < 0 or child_exit == 126 or
                      observed != captured or accepted != written or accepted != acked or requests != acks):
        raise AssertionError("unqualified completed FINAL")
    return dict(cause=cause, flags=flags, exit=child_exit, accepted=accepted, written=written,
                acked=acked, observed=observed, captured=captured, requests=requests, acks=acks, epoch=epoch)


class Channel:
    """Bounded synthetic owner. It never communicate()s/implicitly closes CTRL.

    This is a test harness, not the production worker/session API. It retains
    bounded public test bytes to assert exact transcript structure locally.
    """
    def __init__(self, process: subprocess.Popen, nonce: bytes, generation: int, input_limit: int, output_limit: int):
        self.process = process
        self.nonce = nonce
        self.generation, self.input_limit, self.output_limit = generation, input_limit, output_limit
        self.selector = selectors.DefaultSelector()
        try:
            for name in ("stdout", "stderr"):
                stream = getattr(process, name)
                os.set_blocking(stream.fileno(), False)
                self.selector.register(stream, selectors.EVENT_READ, name)
            os.set_blocking(process.stdin.fileno(), False)
            self.buffer = bytearray()
            self.output = {STDOUT: bytearray(), STDERR: bytearray()}
            self.magic = False
            self.bound = False
            self.final = None
            self.wire_eof = self.diagnostic_eof = False
            self.owner_output_closed = False
            self.wire_bytes = self.received = self.ack_count = self.ack_input = self.ack_epoch = 0
            self.next_sequence = 1
            self.acks = deque()
            self.stdout_offset = 0
        except BaseException:
            # The caller already holds the returned process in NativeAttempt.
            # It owns EOF/wait/stream closure even if this selector failed.
            self.selector.close()
            raise

    def send_bytes(self, data: bytes, timeout: float = 2.0):
        # Do not drain stdout here: the blocked-owner-output controls depend on
        # showing that a small CANCEL can enter CTRL without consuming output.
        deadline = time.monotonic() + timeout
        offset = 0
        writer = selectors.DefaultSelector()
        writer.register(self.process.stdin, selectors.EVENT_WRITE)
        try:
            while offset < len(data):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise AssertionError("bounded owner control write timed out")
                if not writer.select(min(remaining, 0.05)):
                    continue
                try:
                    count = os.write(self.process.stdin.fileno(), data[offset:offset + 8192])
                except BlockingIOError:
                    continue
                if count <= 0:
                    raise AssertionError("owner control write made no progress")
                offset += count
        finally:
            writer.close()

    def _parse(self):
        if not self.magic:
            if len(self.buffer) < 8:
                return
            if self.buffer[:8] != b"KTCLIV2\n":
                raise AssertionError("wrong native magic")
            del self.buffer[:8]
            self.magic = True
        while self.buffer:
            if self.final is not None:
                raise AssertionError("trailing native bytes")
            if len(self.buffer) < 5:
                return
            tag, size = struct.unpack_from(">BI", self.buffer)
            if not ((tag == BOUND and size == 40) or (tag in (STDOUT, STDERR) and 1 <= size <= 8192) or
                    (tag == ACK and size == 32) or (tag == FINAL and size == 80)):
                raise AssertionError("invalid native header before body allocation")
            if len(self.buffer) < size + 5:
                return
            payload = bytes(self.buffer[5:size + 5])
            del self.buffer[:size + 5]
            if not self.bound:
                if tag != BOUND or payload != self.nonce + struct.pack(">QQQ", self.generation, self.input_limit, self.output_limit):
                    raise AssertionError("missing/mismatched BOUND")
                self.bound = True
                continue
            if tag == BOUND:
                raise AssertionError("duplicate BOUND")
            if tag in (STDOUT, STDERR):
                self.received += size
                if self.received > self.output_limit:
                    raise AssertionError("native output cap violated")
                self.output[tag].extend(payload)
            elif tag == ACK:
                kind, reserved, sequence, value_a, value_b = struct.unpack(">IIQQQ", payload)
                if kind not in (ARM, DATA, DISARM, CLOSE_STDIN) or reserved or sequence != self.ack_count + 1:
                    raise AssertionError("invalid native ACK order")
                if kind == ARM:
                    if value_a != self.ack_epoch + 1 or value_a > 4096 or value_b:
                        raise AssertionError("invalid ARM ACK")
                    self.ack_epoch = value_a
                elif kind == DISARM:
                    if value_a != self.ack_epoch or value_a == 0 or value_b:
                        raise AssertionError("invalid DISARM ACK")
                elif kind == DATA:
                    if not 1 <= value_a <= 8192 or value_b != self.ack_input + value_a or value_b > self.input_limit:
                        raise AssertionError("invalid DATA ACK")
                    self.ack_input = value_b
                elif value_a or value_b:
                    raise AssertionError("invalid CLOSE ACK")
                self.ack_count += 1
                if self.ack_count > 8192 or len(self.acks) >= 8192:
                    raise AssertionError("native ACK quota violated")
                self.acks.append((kind, sequence, value_a, value_b))
            elif tag == FINAL:
                self.final = validate_final(payload, input_limit=self.input_limit, output_limit=self.output_limit,
                                            received=self.received, ack_count=self.ack_count,
                                            ack_input=self.ack_input, ack_epoch=self.ack_epoch)

    def pump(self, timeout: float = 0.05):
        for key, _ in self.selector.select(timeout):
            try:
                data = os.read(key.fd, 8192)
            except BlockingIOError:
                continue
            if not data:
                self.selector.unregister(key.fd)
                if key.data == "stdout":
                    self.wire_eof = True
                else:
                    self.diagnostic_eof = True
            elif key.data == "stderr":
                raise AssertionError("unexpected native diagnostic bytes")
            else:
                self.wire_bytes += len(data)
                if self.wire_bytes > MAX_TEST_WIRE:
                    raise AssertionError("synthetic wire retention cap exceeded")
                self.buffer.extend(data)
                self._parse()

    def wait_until(self, predicate, timeout: float = 2.0):
        deadline = time.monotonic() + timeout
        while not predicate():
            remaining = deadline - time.monotonic()
            if remaining <= 0 or (self.wire_eof and self.diagnostic_eof):
                raise AssertionError("synthetic native observation timed out")
            self.pump(min(remaining, 0.05))

    def wait_bound(self):
        self.wait_until(lambda: self.bound)

    def request(self, tag: int, body: bytes = b"", *, wait: bool = True, timeout: float = 2.0):
        sequence = self.next_sequence
        self.next_sequence += 1
        self.send_bytes(frame(tag, struct.pack(">Q", sequence) + body))
        return self.wait_ack(sequence, timeout=timeout) if wait else sequence

    def wait_ack(self, sequence: int, *, timeout: float = 2.0, required: bool = True):
        deadline = time.monotonic() + timeout
        while not self.acks:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or self.final is not None:
                if required:
                    raise AssertionError("expected ordinary ACK missing")
                return None
            self.pump(min(remaining, 0.01))
        result = self.acks.popleft()
        if result[1] != sequence:
            raise AssertionError("unsolicited or mismatched ACK")
        return result

    def arm(self, epoch: int = 1, budget: int = 1500):
        return self.request(ARM, struct.pack(">QQ", epoch, budget))

    def data(self, data: bytes, *, wait: bool = True):
        return self.request(DATA, data, wait=wait)

    def disarm(self, epoch: int = 1):
        return self.request(DISARM, struct.pack(">Q", epoch))

    def close_child(self):
        return self.request(CLOSE_STDIN)

    def cancel(self):
        self.send_bytes(frame(CANCEL))

    def close_control(self):
        if not self.process.stdin.closed:
            self.process.stdin.close()

    def close_owner_output(self):
        self.selector.unregister(self.process.stdout)
        self.process.stdout.close()
        self.owner_output_closed = True

    def stdout_bytes(self, size: int, timeout: float = 2.0) -> bytes:
        end = self.stdout_offset + size
        self.wait_until(lambda: len(self.output[STDOUT]) >= end, timeout)
        result = bytes(self.output[STDOUT][self.stdout_offset:end])
        self.stdout_offset = end
        return result

    def stdout_line(self, timeout: float = 2.0) -> bytes:
        self.wait_until(lambda: b"\n" in self.output[STDOUT][self.stdout_offset:], timeout)
        end = self.output[STDOUT].index(b"\n", self.stdout_offset) + 1
        return self.stdout_bytes(end - self.stdout_offset)

    def finish(self, *, timeout: float = 5.0, valid: bool = True) -> int:
        deadline = time.monotonic() + timeout
        while not ((self.wire_eof or self.owner_output_closed) and self.diagnostic_eof):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise AssertionError("native streams did not close within observation budget")
            self.pump(min(remaining, 0.05))
        code = self.process.wait(timeout=max(0.01, deadline - time.monotonic()))
        if valid and (code != 0 or not self.bound or self.final is None or self.buffer or not self.wire_eof):
            raise AssertionError("native complete FINAL + EOF + exit0 missing")
        return code

    def dispose(self):
        self.selector.close()
        for name in ("stdin", "stdout", "stderr"):
            stream = getattr(self.process, name)
            if stream is not None and not stream.closed:
                stream.close()


class WireValidationTest(unittest.TestCase):
    """Pure harness controls: never substituted for actual native validation."""
    def terminal(self, **changes):
        values = dict(cause=0, flags=COMPLETED_MASK, exit=23, reserved=0, accepted=4,
                      written=4, acked=4, observed=4, captured=4, requests=2, acks=2, epoch=1)
        values.update(changes)
        return struct.pack(">IIiIQQQQQQQQ", *values.values())

    def decode(self, data):
        return validate_final(data, input_limit=8192, output_limit=8192,
                              received=4, ack_count=2, ack_input=4, ack_epoch=1)

    def test_final_roundtrip_and_separate_nonzero_child_exit(self):
        self.assertEqual(23, self.decode(self.terminal())["exit"])

    def test_impossible_final_fields_are_rejected(self):
        for change in ({"flags": COMPLETED_MASK | (1 << 20)}, {"reserved": 1}, {"exit": -1},
                       {"exit": 126}, {"accepted": 3}, {"written": 3}, {"acked": 3},
                       {"captured": 3}, {"observed": 3}, {"requests": 4}, {"acks": 1},
                       {"epoch": 4097}, {"flags": COMPLETED_MASK | F_NO_CHILD},
                       {"flags": COMPLETED_MASK | F_INPUT_INCOMPLETE},
                       {"cause": 1}, {"cause": 7}, {"flags": COMPLETED_MASK | F_SHUTDOWN}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                self.decode(self.terminal(**change))


class BuildReceiptTest(unittest.TestCase):
    def test_separate_v2_linux_only_protocol_prefix_and_schema(self):
        self.assertEqual("ktchecker-owned-child-supervisor/preview-2", builder.PROTOCOL)
        self.assertEqual("ktchecker-owned-child-supervisor-build/2", builder.SCHEMA)
        self.assertEqual("dev/ktchecker/providers/runtime/native-live", builder.PREFIX.as_posix())
        self.assertEqual({"linux-x86_64", "linux-aarch64"}, builder.SUPPORTED)

    def test_unsupported_hosts_emit_unavailable_without_compiler_or_v1_edits(self):
        for system, machine in (("Darwin", "arm64"), ("Darwin", "amd64"), ("Windows", "AMD64"),
                                ("Linux", "riscv64"), ("FreeBSD", "x86_64")):
            with self.subTest(system=system, machine=machine), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                v1 = root / "dev/ktchecker/providers/runtime/native/manifest"
                v1.parent.mkdir(parents=True); v1.write_bytes(b"v1-unchanged\n")
                stale = root / builder.PREFIX / "linux-x86_64/supervisor"
                stale.parent.mkdir(parents=True); stale.write_bytes(b"stale-v2")
                host = builder.host_identity(system, machine)
                with patch.object(builder, "bounded_command", side_effect=AssertionError("compiler called")):
                    receipt = builder.build(root, Path("/not-a-compiler"), host=host)
                self.assertEqual("unavailable", receipt["status"])
                self.assertIsNone(receipt["compiler"])
                self.assertIsNone(receipt["invocation"])
                self.assertIsNone(receipt["output"])
                self.assertEqual(b"v1-unchanged\n", v1.read_bytes())
                self.assertFalse(stale.exists())
                lines = (root / builder.PREFIX / "manifest").read_text().splitlines()
                self.assertEqual([builder.PROTOCOL, host["target"]], lines[:2])
                self.assertEqual("unavailable", lines[3])
                self.assertEqual(4, len(lines))

    def test_failed_build_clears_only_v2_generated_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            v1 = root / "dev/ktchecker/providers/runtime/native/manifest"
            v1.parent.mkdir(parents=True); v1.write_bytes(b"v1-unchanged\n")
            v2 = root / builder.PREFIX / "manifest"
            v2.parent.mkdir(parents=True); v2.write_bytes(b"stale-v2")
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_unavailable"):
                builder.build(root, Path("cc"), host=builder.host_identity("Linux", "x86_64"))
            self.assertFalse(v2.exists())
            self.assertEqual(b"v1-unchanged\n", v1.read_bytes())

    def test_private_compiler_paths_and_all_banners_are_redacted(self):
        self.assertEqual("<private-compiler>", builder.public_compiler_path(Path("/home/private/tools/cc")))
        for banner in (b"gcc private-token", b"clang version private-token", b"unrecognized private-token"):
            self.assertEqual(("<redacted>", True), builder.public_version_line(banner))


class BuildDriverFailureTest(unittest.TestCase):
    """Authored exception/deadline controls; Popen and every OS read are fake."""
    def setUp(self):
        self.now = 0.0
        self.order = []
        self.stream = Mock()
        self.process = Mock(stdout=self.stream)
        self.process.poll.return_value = 0
        self.process.wait.side_effect = lambda **_kwargs: self.order.append("wait") or 0
        self.process.kill.side_effect = lambda: self.order.append("kill")
        self.stream.close.side_effect = lambda: self.order.append("stdout-close")
        self.selector = Mock()
        self.selector.get_map.return_value = {}
        self.selector.close.side_effect = lambda: self.order.append("selector-close")
        self.launch = Mock(return_value=self.process)
        self.selector_factory = Mock(return_value=self.selector)
        self.clock = Mock(side_effect=lambda: self.now)
        self.read = Mock(return_value=b"")

    def command(self, timeout=1.0):
        with patch.object(builder.subprocess, "Popen", self.launch), \
                patch.object(builder.selectors, "DefaultSelector", self.selector_factory), \
                patch.object(builder.time, "monotonic", self.clock), \
                patch.object(builder.os, "read", self.read):
            return builder.bounded_command(["/owner-selected/cc", "--version"], timeout)

    def live_driver(self):
        self.process.poll.side_effect = lambda: self.order.append("poll") or None

    def check_retirement(self, *, selector=True):
        self.process.kill.assert_called_once_with()
        self.process.wait.assert_called_once_with(timeout=2)
        self.stream.close.assert_called_once_with()
        if selector:
            self.selector.close.assert_called_once_with()
        else:
            self.selector.close.assert_not_called()

    def test_invalid_timeout_never_launches_a_driver(self):
        for value in (0, -1, float("inf"), float("-inf"), float("nan")):
            with self.subTest(timeout=value), self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout"):
                self.command(value)
        self.launch.assert_not_called()

    def test_constructor_exception_is_launch_unknown_not_no_child_or_retry(self):
        for error in (OSError("synthetic-launch"), KeyboardInterrupt()):
            with self.subTest(kind=type(error).__name__):
                self.launch.side_effect = error
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_launch_unknown"):
                    self.command()
        self.assertEqual(2, self.launch.call_count)  # one launch per explicit attempt, no retry
        self.process.kill.assert_not_called()
        self.process.wait.assert_not_called()
        self.selector_factory.assert_not_called()

    def test_post_launch_clock_or_selector_setup_failures_retire_obtained_driver(self):
        for stage in ("clock", "selector", "register"):
            with self.subTest(stage=stage):
                self.setUp()
                self.live_driver()
                if stage == "clock":
                    self.clock.side_effect = [0.0, 0.0, OSError("synthetic-clock")]
                elif stage == "selector":
                    self.selector_factory.side_effect = OSError("synthetic-selector")
                else:
                    self.selector.register.side_effect = OSError("synthetic-register")
                with self.assertRaises(OSError):
                    self.command()
                self.check_retirement(selector=stage == "register")
                self.assertEqual(["poll", "kill", "wait"] +
                                 (["selector-close"] if stage == "register" else []) + ["stdout-close"], self.order)

    def test_missing_pipe_fails_explicitly_and_still_retires_obtained_driver(self):
        self.live_driver()
        self.process.stdout = None
        with self.assertRaisesRegex(builder.BuildProblem, "compiler_failed"):
            self.command()
        self.process.kill.assert_called_once_with()
        self.process.wait.assert_called_once_with(timeout=2)
        self.stream.close.assert_not_called()

    def test_selector_and_pipe_close_failures_cannot_skip_retirement_or_each_other(self):
        for bad_selector, bad_stream in ((True, False), (False, True), (True, True)):
            with self.subTest(selector=bad_selector, stdout=bad_stream):
                self.setUp()
                self.live_driver()
                self.selector.register.side_effect = OSError("synthetic-register")

                def close_selector():
                    self.order.append("selector-close")
                    if bad_selector:
                        raise OSError("synthetic-selector-close")

                def close_stream():
                    self.order.append("stdout-close")
                    if bad_stream:
                        raise OSError("synthetic-stdout-close")

                self.selector.close.side_effect = close_selector
                self.stream.close.side_effect = close_stream
                with self.assertRaises(OSError):
                    self.command()
                self.check_retirement()
                self.assertEqual(["poll", "kill", "wait", "selector-close", "stdout-close"], self.order)

    def test_poll_or_kill_error_still_attempts_wait_and_both_closes(self):
        for stage in ("poll", "kill"):
            with self.subTest(stage=stage):
                self.setUp()
                self.live_driver()
                self.selector.register.side_effect = OSError("synthetic-register")
                getattr(self.process, stage).side_effect = OSError("synthetic-retirement")
                with self.assertRaises(OSError):
                    self.command()
                self.process.wait.assert_called_once_with(timeout=2)
                self.selector.close.assert_called_once_with()
                self.stream.close.assert_called_once_with()
                if stage == "poll":
                    self.process.kill.assert_not_called()  # never guess authority after failed observation

    def test_unobserved_reap_is_cleanup_unknown_even_with_simultaneous_close_failure(self):
        for error in (subprocess.TimeoutExpired(["<compiler>"], 2), OSError("synthetic-wait"),
                      KeyboardInterrupt(), None):
            with self.subTest(kind=type(error).__name__):
                self.setUp()
                self.live_driver()
                self.selector.register.side_effect = OSError("synthetic-register")
                self.process.wait.side_effect = None
                if error is None:
                    self.process.wait.return_value = None
                else:
                    self.process.wait.side_effect = error
                self.selector.close.side_effect = OSError("synthetic-selector-close")
                self.stream.close.side_effect = OSError("synthetic-stdout-close")
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_cleanup_unknown"):
                    self.command()
                self.check_retirement()

    def test_late_constructor_is_retired_without_selector_setup(self):
        self.live_driver()

        def launch(*_args, **_kwargs):
            self.now = 1.0
            return self.process

        self.launch.side_effect = launch
        with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout"):
            self.command()
        self.check_retirement(selector=False)
        self.selector_factory.assert_not_called()

    def test_late_exit_or_late_descriptor_close_cannot_return_success(self):
        for stage in ("wait", "close"):
            with self.subTest(stage=stage):
                self.setUp()

                def wait(**_kwargs):
                    self.now = 1.0
                    return 0

                def close():
                    self.now = 1.0

                if stage == "wait":
                    self.process.wait.side_effect = wait
                else:
                    self.stream.close.side_effect = close
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout"):
                    self.command()
                self.process.kill.assert_not_called()
                self.selector.close.assert_called_once_with()
                self.stream.close.assert_called_once_with()

    def test_successful_already_reaped_driver_is_not_signaled(self):
        self.assertEqual(b"", self.command())
        self.process.kill.assert_not_called()
        self.assertEqual(2, self.process.wait.call_count)
        self.selector.close.assert_called_once_with()
        self.stream.close.assert_called_once_with()

    def test_diagnostic_limit_is_checked_before_retention(self):
        self.live_driver()
        self.selector.get_map.return_value = {99: object()}
        self.selector.select.return_value = [(Mock(fd=99), selectors.EVENT_READ)]
        self.read.return_value = b"abc"
        with patch.object(builder, "MAX_COMPILER_OUTPUT", 2), \
                self.assertRaisesRegex(builder.BuildProblem, "compiler_output_limit"):
            self.command()
        self.check_retirement()


class SyntheticInterruption(BaseException):
    """Exercise ownership even when ordinary Exception handling is bypassed."""


class BuildCustodyHarness:
    """Real private directory entries, but wholly synthetic compiler behavior."""

    SOURCE_SHA = "sha256:" + "a" * 64
    BUILDER_SHA = "sha256:" + "b" * 64
    COMPILER_SHA = "sha256:" + "c" * 64
    VERSION = b"synthetic compiler PRIVATE-BANNER-TOKEN\n"

    @staticmethod
    def forbidden(*_args, **_kwargs):
        raise AssertionError("real process or unowned signal/observation attempted")

    def __init__(self, directory):
        self.output = directory / "private-output"
        # A space makes this a private path even under an unusual public TMPDIR.
        self.cc = directory / "private synthetic compiler.txt"
        self.cc.write_bytes(b"synthetic compiler identity only; never executed\n")
        self.host = {"os": "linux", "architecture": "x86_64", "target": "linux-x86_64"}
        self.payload = b"synthetic supervisor payload only; never executed\n"
        self.events = []
        self.phases = []
        self.calls = []
        self.publication_writes = []
        self.housekeeping = []
        self.digest_counts = {}
        self.on_phase = None
        self.on_command = None
        self.on_write = None
        self.on_digest = None
        self.forbid_housekeeping = False
        self.housekeeping_faults = {}
        self.raw_phase = builder._write_attempt_phase
        self.raw_write = Path.write_bytes
        self.raw_unlink = Path.unlink
        self.raw_rmdir = Path.rmdir
        self.raw_rmtree = builder.shutil.rmtree

    @property
    def root(self):
        return self.output.absolute() / builder.PREFIX

    @property
    def attempt(self):
        return self.root.with_name(f".{self.root.name}.compiler-attempt")

    @property
    def candidate(self):
        return self.attempt / "supervisor"

    @staticmethod
    def phase_bytes(phase):
        return f"ktchecker-native-compiler-attempt/1\n{phase}\n".encode("ascii")

    def record(self, event):
        if len(self.events) >= 1024:
            raise AssertionError("synthetic custody operation bound exceeded")
        self.events.append(event)

    def require_guard(self):
        if not self.attempt.is_dir():
            raise AssertionError("synthetic operation occurred without custody guard")

    def write_phase(self, attempt, phase):
        self.record("phase:" + phase)
        self.require_guard()
        if attempt != self.attempt:
            raise AssertionError("unexpected synthetic custody path")
        if self.on_phase is not None:
            self.on_phase(attempt, phase)
        self.raw_phase(attempt, phase)
        self.phases.append(phase)

    def write_bytes(self, path, data):
        self.record("write:" + path.name)
        if not isinstance(data, bytes) or len(data) > 65_536:
            raise AssertionError("synthetic custody file bound exceeded")
        if path in (self.root / "build-provenance.json", self.root / "manifest"):
            self.require_guard()
            self.publication_writes.append((path.name, (self.attempt / "phase").read_bytes()))
        if self.on_write is not None:
            result = self.on_write(path, data)
            if result is not None:
                return result
        return self.raw_write(path, data)

    def command(self, argv, timeout):
        self.require_guard()
        if argv == [str(self.cc), "--version"]:
            kind, pending, expected_timeout = "version", "version_probe_pending", 10
        else:
            kind, pending, expected_timeout = "compile", "compile_pending", 60
            if (len(argv) != len(builder.FLAGS) + 4 or argv[0] != str(self.cc) or
                    argv[1:-3] != list(builder.FLAGS) or Path(argv[-3]).suffix != ".c" or
                    argv[-2:] != ["-o", str(self.candidate)]):
                raise AssertionError("unexpected synthetic compile arguments")
        if timeout != expected_timeout or (self.attempt / "phase").read_bytes() != self.phase_bytes(pending):
            raise AssertionError("compiler call preceded its pending custody record")
        self.record("command:" + kind)
        self.calls.append(kind)
        if kind == "compile":
            self.candidate.write_bytes(self.payload)
        if self.on_command is not None:
            self.on_command(kind)
        return self.VERSION if kind == "version" else b""

    def file_digest(self, path, maximum):
        self.require_guard()
        self.record("digest:" + path.name)
        self.digest_counts[path] = self.digest_counts.get(path, 0) + 1
        if self.on_digest is not None:
            result = self.on_digest(path, maximum)
            if result is not None:
                return result
        if path == self.candidate:
            return builder.digest(self.payload)
        if path == self.cc.resolve():
            return self.COMPILER_SHA
        if path == Path(builder.__file__).resolve():
            return self.BUILDER_SHA
        if path.parent == Path(builder.__file__).resolve().parent and path.suffix == ".c":
            return self.SOURCE_SHA
        raise AssertionError("unexpected synthetic digest input")

    def access(self, path, mode):
        if path != self.cc or mode != builder.os.X_OK:
            raise AssertionError("unexpected synthetic access check")
        return True

    def housekeeping_step(self, operation, path):
        self.record(operation)
        self.housekeeping.append((operation, path))
        if self.forbid_housekeeping:
            raise AssertionError("exception-path housekeeping attempted")
        fault = self.housekeeping_faults.get(operation)
        if fault is not None:
            raise fault

    def unlink(self, path, *args, **kwargs):
        self.housekeeping_step("unlink", path)
        return self.raw_unlink(path, *args, **kwargs)

    def rmdir(self, path, *args, **kwargs):
        self.housekeeping_step("rmdir", path)
        return self.raw_rmdir(path, *args, **kwargs)

    def rmtree(self, path, *args, **kwargs):
        self.housekeeping_step("rmtree", path)
        return self.raw_rmtree(path, *args, **kwargs)

    def seed_stale_output(self):
        self.root.mkdir(parents=True)
        sentinel = self.root / "previous-output.txt"
        sentinel.write_bytes(b"previous synthetic output\n")
        return sentinel

    def build(self, *, host=None):
        return builder.build(self.output, self.cc, host=self.host if host is None else host)


@contextmanager
def synthetic_build():
    # Constructor/compiler and low-level process operations are forbidden. This
    # fresh fixture has no real process custody, so its own cleanup is safe after
    # all subject patches are removed, even for simulated retained attempts.
    with tempfile.TemporaryDirectory(prefix="ktc-builder-custody-synthetic-") as temporary:
        harness = BuildCustodyHarness(Path(temporary))

        def write_bytes(path, data):
            return harness.write_bytes(path, data)

        def unlink(path, *args, **kwargs):
            return harness.unlink(path, *args, **kwargs)

        def rmdir(path, *args, **kwargs):
            return harness.rmdir(path, *args, **kwargs)

        with ExitStack() as patches:
            patches.enter_context(patch.object(builder, "bounded_command", side_effect=harness.command))
            patches.enter_context(patch.object(builder, "file_digest", side_effect=harness.file_digest))
            patches.enter_context(patch.object(builder, "_write_attempt_phase", side_effect=harness.write_phase))
            patches.enter_context(patch.object(builder.os, "access", side_effect=harness.access))
            patches.enter_context(patch.object(builder.subprocess, "Popen", side_effect=BuildCustodyHarness.forbidden))
            patches.enter_context(patch.object(builder.os, "kill", side_effect=BuildCustodyHarness.forbidden))
            patches.enter_context(patch.object(builder.os, "killpg", side_effect=BuildCustodyHarness.forbidden, create=True))
            patches.enter_context(patch.object(builder.os, "waitpid", side_effect=BuildCustodyHarness.forbidden, create=True))
            patches.enter_context(patch.object(Path, "write_bytes", new=write_bytes))
            patches.enter_context(patch.object(Path, "unlink", new=unlink))
            patches.enter_context(patch.object(Path, "rmdir", new=rmdir))
            patches.enter_context(patch.object(builder.shutil, "rmtree", side_effect=harness.rmtree))
            yield harness


class CompilerBuildCustodyTest(unittest.TestCase):
    PHASES = ["preflight", "version_probe_pending", "version_probe_direct_return_observed",
              "compile_pending", "compile_direct_return_observed"]

    def assert_phase(self, harness, phase):
        self.assertTrue(harness.attempt.is_dir())
        recorded = (harness.attempt / "phase").read_bytes()
        self.assertEqual(harness.phase_bytes(phase), recorded)
        self.assertLessEqual(len(recorded), 128)

    def assert_no_receipt(self, harness):
        self.assertFalse((harness.root / "build-provenance.json").exists())
        self.assertFalse((harness.root / "manifest").exists())

    def assert_reuse_refused(self, harness, *, host=None):
        history = list(harness.events)
        with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_retained$"):
            harness.build(host=host)
        # No validation, state write, compiler call, clear, or release follows
        # refusal. Only nondestructive parent setup and exclusive mkdir precede it.
        self.assertEqual(history, harness.events)

    def test_success_holds_guard_through_publication_then_allows_rebuild(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            receipt = harness.build()
            self.assertFalse(stale.exists())
            self.assertEqual(self.PHASES, harness.phases)
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertEqual([
                ("build-provenance.json", harness.phase_bytes("compile_direct_return_observed")),
                ("manifest", harness.phase_bytes("compile_direct_return_observed")),
            ], harness.publication_writes)
            self.assertEqual([
                ("rmtree", harness.root), ("unlink", harness.attempt / "phase"), ("rmdir", harness.attempt),
            ], harness.housekeeping)
            self.assertFalse(harness.attempt.exists())
            installed = harness.root / harness.host["target"] / "supervisor"
            self.assertEqual(harness.payload, installed.read_bytes())
            self.assertEqual("built", receipt["status"])
            self.assertIsNone(receipt["reason"])
            self.assertEqual(harness.SOURCE_SHA, receipt["inputs"]["source"]["sha256"])
            self.assertEqual(harness.BUILDER_SHA, receipt["inputs"]["builder"]["sha256"])
            self.assertEqual(harness.COMPILER_SHA, receipt["compiler"]["driverSha256"])
            self.assertEqual("<private-compiler>", receipt["compiler"]["selectedPath"])
            self.assertEqual("<private-compiler>", receipt["compiler"]["resolvedPath"])
            self.assertEqual("<redacted>", receipt["compiler"]["versionFirstLine"])
            self.assertEqual(builder.digest(harness.VERSION), receipt["compiler"]["versionOutputSha256"])
            self.assertEqual(["<compiler>", *builder.FLAGS, "<source>", "-o", "<output>"],
                             receipt["invocation"]["argv"])
            self.assertFalse(receipt["invocation"]["privateArgvRetained"])
            self.assertEqual({"privatePathsRetained": False, "rawCompilerTranscriptRetained": False,
                              "versionFirstLineRedacted": True}, receipt["privacy"])
            encoded = (harness.root / "build-provenance.json").read_bytes()
            self.assertEqual(receipt, builder.json.loads(encoded))
            self.assertLessEqual(len(encoded), builder.MAX_RECEIPT)
            for private_value in (str(harness.output), str(harness.cc), str(harness.attempt), "PRIVATE-BANNER-TOKEN"):
                self.assertNotIn(private_value, encoded.decode("ascii"))
            expected_manifest = (f"{builder.PROTOCOL}\n{harness.host['target']}\n{harness.SOURCE_SHA}\n"
                                 f"{builder.digest(harness.payload)}\n").encode("ascii")
            self.assertEqual(expected_manifest, (harness.root / "manifest").read_bytes())
            self.assertEqual(receipt, harness.build())
            self.assertEqual(self.PHASES * 2, harness.phases)
            self.assertEqual(["version", "compile"] * 2, harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_unsupported_host_also_guards_clear_and_releases_only_after_writes(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            unsupported = {"os": "windows", "architecture": "x86_64", "target": "windows-x86_64"}
            receipt = harness.build(host=unsupported)
            self.assertFalse(stale.exists())
            self.assertEqual([], harness.calls)
            self.assertEqual(["preflight"], harness.phases)
            self.assertEqual([
                ("build-provenance.json", harness.phase_bytes("preflight")),
                ("manifest", harness.phase_bytes("preflight")),
            ], harness.publication_writes)
            self.assertEqual("unavailable", receipt["status"])
            self.assertEqual("unsupported_host", receipt["reason"])
            self.assertIsNone(receipt["compiler"])
            self.assertIsNone(receipt["invocation"])
            self.assertIsNone(receipt["output"])
            self.assertFalse(harness.attempt.exists())

    def test_version_unknown_retains_pending_guard_without_cleanup_or_compile(self):
        for reason in ("launch_unknown", "cleanup_unknown"):
            with self.subTest(reason=reason), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                fault = builder.BuildProblem("native_supervisor_compiler_" + reason)

                def fail(kind):
                    self.assertEqual("version", kind)
                    harness.forbid_housekeeping = True
                    raise fault

                harness.on_command = fail
                with self.assertRaises(builder.BuildProblem) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertFalse(stale.exists())
                self.assertEqual(["version"], harness.calls)
                self.assertEqual(self.PHASES[:2], harness.phases)
                self.assert_phase(harness, "version_probe_pending")
                self.assertFalse(harness.candidate.exists())
                self.assert_no_receipt(harness)
                self.assertEqual([("rmtree", harness.root)], harness.housekeeping)
                self.assert_reuse_refused(harness)

    def test_compile_unknown_preserves_candidate_and_original_reason(self):
        for reason in ("launch_unknown", "cleanup_unknown"):
            with self.subTest(reason=reason), synthetic_build() as harness:
                harness.payload = b"partial synthetic compiler output\n"
                fault = builder.BuildProblem("native_supervisor_compiler_" + reason)

                def fail(kind):
                    if kind == "compile":
                        harness.forbid_housekeeping = True
                        raise fault

                harness.on_command = fail
                with self.assertRaises(builder.BuildProblem) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertEqual(["version", "compile"], harness.calls)
                self.assertEqual(self.PHASES[:4], harness.phases)
                self.assert_phase(harness, "compile_pending")
                self.assertEqual(harness.payload, harness.candidate.read_bytes())
                self.assertFalse((harness.root / harness.host["target"]).exists())
                self.assert_no_receipt(harness)
                self.assertEqual([], harness.housekeeping)
                self.assert_reuse_refused(harness)
                self.assertEqual(harness.payload, harness.candidate.read_bytes())

    def test_preflight_validation_failures_conservatively_retain_without_launch(self):
        for kind in (OSError, MemoryError, SyntheticInterruption):
            with self.subTest(kind=kind.__name__), synthetic_build() as harness:
                fault = kind("synthetic input failure")

                def fail(_path, _maximum):
                    harness.forbid_housekeeping = True
                    raise fault

                harness.on_digest = fail
                with self.assertRaises(kind) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, "preflight")
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_post_version_ordinary_and_base_exceptions_do_not_release_guard(self):
        for kind in (OSError, MemoryError, SyntheticInterruption):
            with self.subTest(kind=kind.__name__), synthetic_build() as harness:
                fault = kind("synthetic post-version failure")

                def fail(_output):
                    harness.forbid_housekeeping = True
                    raise fault

                with patch.object(builder, "public_version_line", side_effect=fail):
                    with self.assertRaises(kind) as raised:
                        harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, "version_probe_direct_return_observed")
                self.assertEqual(["version"], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_existing_directory_malformed_state_or_file_refuses_before_clear(self):
        for state in ("missing", "malformed", "file"):
            with self.subTest(state=state), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                if state == "file":
                    harness.attempt.write_bytes(b"synthetic retained entry\n")
                else:
                    harness.attempt.mkdir()
                    if state == "malformed":
                        (harness.attempt / "phase").write_bytes(b"unrecognized synthetic state\n")
                harness.forbid_housekeeping = True
                # Existing state is never opened or parsed, including malformed
                # content. Ownership refusal depends only on exclusive mkdir.
                with patch.object(Path, "open", side_effect=BuildCustodyHarness.forbidden):
                    self.assert_reuse_refused(harness)
                self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.phases)
                self.assertEqual([], harness.housekeeping)
                if state == "file":
                    self.assertEqual(b"synthetic retained entry\n", harness.attempt.read_bytes())
                elif state == "malformed":
                    self.assertEqual(b"unrecognized synthetic state\n", (harness.attempt / "phase").read_bytes())
                else:
                    self.assertFalse((harness.attempt / "phase").exists())

    def test_guard_acquisition_errors_never_clear_or_launch(self):
        for acquired in (False, True):
            for kind in (OSError, PermissionError):
                with self.subTest(acquired=acquired, kind=kind.__name__), synthetic_build() as harness:
                    stale = harness.seed_stale_output()
                    raw_mkdir = Path.mkdir

                    def mkdir(path, *args, **kwargs):
                        if path == harness.attempt:
                            if acquired:
                                raw_mkdir(path, *args, **kwargs)
                            raise kind("synthetic acquisition failure")
                        return raw_mkdir(path, *args, **kwargs)

                    harness.forbid_housekeeping = True
                    with patch.object(Path, "mkdir", new=mkdir):
                        with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_unavailable$"):
                            harness.build()
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assertEqual(acquired, harness.attempt.is_dir())
                    self.assertEqual([], harness.calls)
                    self.assertEqual([], harness.phases)
                    self.assertEqual([], harness.housekeeping)
                    if acquired:
                        self.assert_reuse_refused(harness)

    def test_parent_creation_failure_is_unavailable_without_touching_output(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            raw_mkdir = Path.mkdir

            def mkdir(path, *args, **kwargs):
                if path == harness.attempt.parent:
                    raise PermissionError("synthetic parent setup failure")
                return raw_mkdir(path, *args, **kwargs)

            harness.forbid_housekeeping = True
            with patch.object(Path, "mkdir", new=mkdir):
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_unavailable$"):
                    harness.build()
            self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
            self.assertFalse(harness.attempt.exists())
            self.assertEqual([], harness.calls)
            self.assertEqual([], harness.phases)
            self.assertEqual([], harness.housekeeping)

    def test_preflight_phase_failure_or_short_write_guards_unchanged_output(self):
        for mode in ("error", "short"):
            with self.subTest(mode=mode), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                fault = OSError("synthetic phase write failure")

                def write(path, data):
                    if path == harness.attempt / "phase":
                        self.assertEqual(harness.phase_bytes("preflight"), data)
                        if mode == "error":
                            raise fault
                        harness.raw_write(path, b"p")
                        return 1
                    return None

                harness.on_write = write
                harness.forbid_housekeeping = True
                if mode == "error":
                    with self.assertRaises(OSError) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assertFalse((harness.attempt / "phase").exists())
                else:
                    with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_state_failed$"):
                        harness.build()
                    self.assertEqual(b"p", (harness.attempt / "phase").read_bytes())
                self.assertTrue(harness.attempt.is_dir())
                self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_reuse_refused(harness)

    def test_invalid_phase_is_rejected_without_persisting_caller_text(self):
        with synthetic_build() as harness:
            harness.attempt.mkdir(parents=True)
            with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_state_invalid$"):
                builder._write_attempt_phase(harness.attempt, "private-path-or-error-text")
            self.assertFalse((harness.attempt / "phase").exists())
            self.assertEqual([], harness.calls)
            self.assert_reuse_refused(harness)

    def test_direct_return_phase_write_failure_prevents_next_stage_and_retains(self):
        for phase, pending, calls in (
                ("version_probe_direct_return_observed", "version_probe_pending", ["version"]),
                ("compile_direct_return_observed", "compile_pending", ["version", "compile"])):
            with self.subTest(phase=phase), synthetic_build() as harness:
                fault = SyntheticInterruption("synthetic post-return state interruption")

                def write(path, data):
                    if path == harness.attempt / "phase" and data == harness.phase_bytes(phase):
                        harness.forbid_housekeeping = True
                        raise fault
                    return None

                harness.on_write = write
                with self.assertRaises(SyntheticInterruption) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, pending)
                self.assertEqual(calls, harness.calls)
                self.assertEqual("compile" in calls, harness.candidate.exists())
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_second_caller_at_acquisition_refuses_before_first_destructive_clear(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            interleavings = []

            def phase_hook(attempt, phase):
                if phase == "preflight":
                    self.assertTrue(attempt.is_dir())
                    self.assertFalse((attempt / "phase").exists())
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assert_reuse_refused(harness)
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assertEqual([], harness.housekeeping)
                    self.assertEqual([], harness.calls)
                    interleavings.append("refused-before-clear")

            harness.on_phase = phase_hook
            receipt = harness.build()
            self.assertEqual(["refused-before-clear"], interleavings)
            self.assertEqual("built", receipt["status"])
            self.assertFalse(stale.exists())
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_second_unsupported_caller_during_compile_preserves_first_candidate(self):
        with synthetic_build() as harness:
            interleavings = []
            unsupported = {"os": "unsupported", "architecture": "unsupported", "target": "unsupported"}

            def command_hook(kind):
                if kind == "compile":
                    self.assert_phase(harness, "compile_pending")
                    self.assertEqual(harness.payload, harness.candidate.read_bytes())
                    self.assert_reuse_refused(harness, host=unsupported)
                    self.assert_phase(harness, "compile_pending")
                    self.assertEqual(harness.payload, harness.candidate.read_bytes())
                    interleavings.append("refused-during-compile")

            harness.on_command = command_hook
            receipt = harness.build()
            self.assertEqual(["refused-during-compile"], interleavings)
            self.assertEqual("built", receipt["status"])
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_native_and_native_live_sibling_custody_are_isolated_both_directions(self):
        common = Path("dev/ktchecker/providers/runtime")
        for retained_name, other_name in (("native", "native-live"), ("native-live", "native")):
            with self.subTest(retained=retained_name), synthetic_build() as harness:
                # Parameterize the namespace under the same subject. Each real
                # builder's own declared PREFIX still needs source review.
                with patch.object(builder, "PREFIX", common / retained_name):
                    fault = builder.BuildProblem("native_supervisor_compiler_cleanup_unknown")

                    def fail(kind):
                        if kind == "compile":
                            raise fault

                    harness.on_command = fail
                    with self.assertRaises(builder.BuildProblem) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assert_phase(harness, "compile_pending")
                    retained_attempt, retained_candidate = harness.attempt, harness.candidate
                    retained_sentinel = harness.root / "unresolved-owner-sentinel.txt"
                    retained_sentinel.write_bytes(b"synthetic unresolved owner\n")
                harness.on_command = None
                with patch.object(builder, "PREFIX", common / other_name):
                    self.assertNotEqual(retained_attempt, harness.attempt)
                    receipt = harness.build()
                    self.assertEqual("built", receipt["status"])
                    self.assertFalse(harness.attempt.exists())
                    self.assertTrue(retained_attempt.is_dir())
                    self.assertEqual(harness.phase_bytes("compile_pending"), (retained_attempt / "phase").read_bytes())
                    self.assertEqual(harness.payload, retained_candidate.read_bytes())
                    self.assertEqual(b"synthetic unresolved owner\n", retained_sentinel.read_bytes())
                with patch.object(builder, "PREFIX", common / retained_name):
                    self.assert_reuse_refused(harness)
                self.assertEqual(["version", "compile"] * 2, harness.calls)

    def test_binary_rejection_and_input_drift_retain_direct_return_phase(self):
        for site in ("binary", "source", "builder", "compiler"):
            with self.subTest(site=site), synthetic_build() as harness:
                def digest_hook(path, _maximum):
                    if site == "binary" and path == harness.candidate:
                        harness.forbid_housekeeping = True
                        raise builder.BuildProblem("native_supervisor_build_input_invalid")
                    selected = ((site == "source" and path.suffix == ".c") or
                                (site == "builder" and path == Path(builder.__file__).resolve()) or
                                (site == "compiler" and path == harness.cc.resolve()))
                    if selected and harness.digest_counts[path] == 2:
                        harness.forbid_housekeeping = True
                        return "sha256:" + "d" * 64
                    return None

                harness.on_digest = digest_hook
                reason = "input_invalid" if site == "binary" else "input_changed"
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_" + reason + "$"):
                    harness.build()
                self.assert_phase(harness, "compile_direct_return_observed")
                self.assertEqual(harness.payload, harness.candidate.read_bytes())
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_receipt_limit_failure_retains_guard_after_output_installation(self):
        with synthetic_build() as harness:
            with patch.object(builder, "MAX_RECEIPT", 1):
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_receipt_limit$"):
                    harness.build()
            self.assert_phase(harness, "compile_direct_return_observed")
            self.assertFalse(harness.candidate.exists())
            self.assertTrue((harness.root / harness.host["target"] / "supervisor").is_file())
            self.assert_no_receipt(harness)
            self.assertEqual([], harness.housekeeping)
            self.assert_reuse_refused(harness)

    def test_publication_write_exceptions_preserve_guard_and_original_exception(self):
        for filename in ("build-provenance.json", "manifest"):
            for kind in (OSError, SyntheticInterruption):
                with self.subTest(filename=filename, kind=kind.__name__), synthetic_build() as harness:
                    fault = kind("synthetic publication write failure")

                    def write(path, _data):
                        if path == harness.root / filename:
                            harness.forbid_housekeeping = True
                            raise fault
                        return None

                    harness.on_write = write
                    with self.assertRaises(kind) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assert_phase(harness, "compile_direct_return_observed")
                    self.assertEqual([], harness.housekeeping)
                    # Publication is not atomic: an installed output or earlier
                    # receipt may exist, but the build failed and remains guarded.
                    self.assertTrue((harness.root / harness.host["target"] / "supervisor").is_file())
                    self.assertEqual(filename == "manifest", (harness.root / "build-provenance.json").exists())
                    self.assertFalse((harness.root / "manifest").exists())
                    self.assert_reuse_refused(harness)

    def test_short_publication_write_never_releases_guard(self):
        for filename in ("build-provenance.json", "manifest"):
            with self.subTest(filename=filename), synthetic_build() as harness:
                def write(path, data):
                    if path == harness.root / filename:
                        harness.raw_write(path, data[:1])
                        harness.forbid_housekeeping = True
                        return 1
                    return None

                harness.on_write = write
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_publication_failed$"):
                    harness.build()
                self.assert_phase(harness, "compile_direct_return_observed")
                self.assertEqual(1, (harness.root / filename).stat().st_size)
                self.assertEqual([], harness.housekeeping)
                if filename == "build-provenance.json":
                    self.assertFalse((harness.root / "manifest").exists())
                self.assert_reuse_refused(harness)

    def test_pre_removal_release_failures_preserve_existing_guard(self):
        for operation in ("unlink", "rmdir"):
            with self.subTest(operation=operation), synthetic_build() as harness:
                fault = PermissionError("synthetic release failure")
                # Inject before the primitive changes the directory. This does
                # not promise that a real ambiguous rmdir exception retains it.
                harness.housekeeping_faults[operation] = fault
                with self.assertRaises(PermissionError) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertTrue(harness.attempt.is_dir())
                self.assertEqual(operation == "unlink", (harness.attempt / "phase").exists())
                expected = [("unlink", harness.attempt / "phase")]
                if operation == "rmdir":
                    expected.append(("rmdir", harness.attempt))
                self.assertEqual(expected, harness.housekeeping)
                self.assertEqual(2, len(harness.publication_writes))
                self.assert_reuse_refused(harness)

    def test_unexpected_residue_prevents_release_without_recursive_attempt_cleanup(self):
        with synthetic_build() as harness:
            residue = harness.attempt / "unexpected-synthetic-residue.txt"

            def command_hook(kind):
                if kind == "compile":
                    residue.write_bytes(b"unexpected synthetic residue\n")

            harness.on_command = command_hook
            with self.assertRaises(OSError):
                harness.build()
            self.assertTrue(harness.attempt.is_dir())
            self.assertFalse((harness.attempt / "phase").exists())
            self.assertEqual(b"unexpected synthetic residue\n", residue.read_bytes())
            self.assertEqual([("unlink", harness.attempt / "phase"), ("rmdir", harness.attempt)], harness.housekeeping)
            self.assert_reuse_refused(harness)
            self.assertEqual(b"unexpected synthetic residue\n", residue.read_bytes())

    def test_resource_clear_failure_preserves_guard_and_original_exception(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            fault = OSError("synthetic resource clear failure")
            harness.housekeeping_faults["rmtree"] = fault
            with self.assertRaises(OSError) as raised:
                harness.build()
            self.assertIs(fault, raised.exception)
            self.assert_phase(harness, "preflight")
            self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
            self.assertEqual([], harness.calls)
            self.assertEqual([("rmtree", harness.root)], harness.housekeeping)
            self.assert_reuse_refused(harness)


class NativeHarnessCustodyTest(unittest.TestCase):
    """No real child is launched: prove the harness retains unknown custody."""
    def fake_process(self):
        process = Mock(stdin=Mock(), stdout=Mock(), stderr=Mock())
        process.wait.return_value = 0
        return process

    def test_failed_obtained_process_registration_retains_attempt_without_pending_entry(self):
        for failure in (MemoryError(), KeyboardInterrupt()):
            with self.subTest(kind=type(failure).__name__), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "attempt"
                root.mkdir()
                trace = root / "trace"
                trace.write_bytes(b"public-synthetic-trace")
                attempt = NativeAttempt(root)
                process = self.fake_process()

                class FailedAppend(list):
                    def append(self, _process):
                        raise failure

                attempt.pending = FailedAppend()
                with patch.object(subprocess, "Popen", return_value=process), self.assertRaises(type(failure)):
                    attempt.launch(["/fake-helper"])
                self.assertTrue(attempt.retained)
                self.assertFalse(attempt.pending)
                with patch.object(sys, "stderr", Mock()):
                    self.assertFalse(attempt.cleanup())
                self.assertEqual(b"public-synthetic-trace", trace.read_bytes())
                process.kill.assert_not_called()
                process.wait.assert_not_called()

    def test_unknown_process_or_close_retains_exact_attempt_and_trace(self):
        for stage in ("wait", "stdin", "stdout", "stderr"):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "attempt"
                root.mkdir()
                trace = root / "trace"
                trace.write_bytes(b"public-synthetic-trace")
                attempt = NativeAttempt(root)
                process = self.fake_process()
                with patch.object(subprocess, "Popen", return_value=process):
                    self.assertIs(process, attempt.launch(["/fake-helper"]))
                if stage == "wait":
                    process.wait.side_effect = subprocess.TimeoutExpired(["<helper>"], 5)
                else:
                    getattr(process, stage).close.side_effect = OSError("synthetic-close")
                self.assertFalse(attempt.observe(process))
                with patch.object(sys, "stderr", Mock()):
                    self.assertFalse(attempt.cleanup())
                self.assertEqual(b"public-synthetic-trace", trace.read_bytes())
                self.assertIn(process, attempt.pending)
                process.wait.assert_called_once_with(timeout=5.0)
                process.stdin.close.assert_called_once_with()
                process.stdout.close.assert_called_once_with()
                process.stderr.close.assert_called_once_with()
                process.kill.assert_not_called()

    def test_known_exit_and_all_closed_streams_allow_only_exact_attempt_removal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "attempt"
            root.mkdir()
            sibling = Path(temporary) / "sibling"
            sibling.write_bytes(b"keep")
            attempt = NativeAttempt(root)
            process = self.fake_process()
            with patch.object(subprocess, "Popen", return_value=process):
                attempt.launch(["/fake-helper"])
            self.assertTrue(attempt.observe(process))
            self.assertTrue(attempt.cleanup())
            self.assertFalse(root.exists())
            self.assertEqual(b"keep", sibling.read_bytes())
            process.kill.assert_not_called()

    def test_failed_channel_construction_keeps_obtained_process_guarded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "attempt"
            root.mkdir()
            owner = NativeLiveSupervisorTest("test_immediate_arm_after_bound_never_races_before_fork")
            owner.attempt = NativeAttempt(root)
            owner.processes = []
            owner.channels = []
            process = self.fake_process()
            with patch.object(subprocess, "Popen", return_value=process):
                self.assertIs(process, owner.spawn_owned(["/fake-helper"]))
            with patch.object(sys.modules[__name__], "Channel", side_effect=OSError("synthetic-channel")), \
                    self.assertRaises(OSError):
                owner.attach_channel(process, b"x" * 16, 1000, 8192, 8192)
            self.assertTrue(owner.attempt.retained)
            self.assertEqual([process], owner.processes)
            self.assertFalse(owner.channels)
            process.stdin.close.assert_called_once_with()
            process.wait.assert_called_once_with(timeout=5.0)
            process.stdout.close.assert_called_once_with()
            process.stderr.close.assert_called_once_with()
            process.kill.assert_not_called()
            with patch.object(sys, "stderr", Mock()):
                self.assertFalse(owner.attempt.cleanup())
            self.assertTrue(root.exists())

    def test_channel_registration_failure_closes_its_partial_selector(self):
        process = self.fake_process()
        selector = Mock()
        selector.register.side_effect = OSError("synthetic-register")
        with patch.object(selectors, "DefaultSelector", return_value=selector), \
                patch.object(os, "set_blocking"), self.assertRaises(OSError):
            Channel(process, b"x" * 16, 1000, 8192, 8192)
        selector.close.assert_called_once_with()
        process.kill.assert_not_called()

    def test_trace_read_has_an_explicit_allocation_bound(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / "trace"
            trace.write_bytes(b"12345")
            with patch.object(sys.modules[__name__], "MAX_TEST_TRACE", 4), self.assertRaises(AssertionError):
                bounded_trace_text(trace)


STATE_CONTROL_SOURCE = r"""
/* Test-only inclusion: deterministic syscall/clock faults, not real child
 * ownership evidence. The production entry point is never invoked here. */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
static int test_clock(clockid_t id, struct timespec *value);
static int test_poll(struct pollfd *fds, nfds_t count, int timeout);
static ssize_t test_read(int fd, void *bytes, size_t size);
static ssize_t test_write(int fd, const void *bytes, size_t size);
static int test_close(int fd);
static int test_kill(pid_t pid, int number);
static int test_waitid(idtype_t kind, id_t id, siginfo_t *info, int options);
static pid_t test_waitpid(pid_t pid, int *status, int options);
#define clock_gettime test_clock
#define poll test_poll
#define read test_read
#define write test_write
#define close test_close
#define kill test_kill
#define waitid test_waitid
#define waitpid test_waitpid
#define main uncalled_supervisor_main
#include "owned_live_process_supervisor.c"
#undef main
#undef clock_gettime
#undef poll
#undef read
#undef write
#undef close
#undef kill
#undef waitid
#undef waitpid

enum { MODE_POLL, MODE_CLEANUP, MODE_PREAMBLE, MODE_FLUSH };
static int mode, wire_writes, input_writes, closes, kills, waits, order_error;
static uint64_t fake_now, after_event;
static bool bad_clock, fail_after_event, interrupt_after_event;
static unsigned char preamble[24];
static size_t preamble_offset;
static const pid_t held_child = 41; /* fake identity: never passed to a real signal */

static int test_clock(clockid_t id, struct timespec *value) {
    (void)id;
    if (bad_clock) { errno = EIO; return -1; }
    value->tv_sec = (time_t)(fake_now / 1000u);
    value->tv_nsec = (long)(fake_now % 1000u) * 1000000L;
    return 0;
}

static int test_poll(struct pollfd *fds, nfds_t count, int timeout) {
    (void)fds; (void)count; (void)timeout;
    if (mode == MODE_POLL) { errno = EIO; return -1; }
    return 0;
}

static ssize_t test_read(int fd, void *bytes, size_t size) {
    if (mode == MODE_PREAMBLE && fd == STDIN_FILENO && preamble_offset < sizeof(preamble)) {
        size_t count = sizeof(preamble) - preamble_offset;
        if (count > size) count = size;
        memcpy(bytes, preamble + preamble_offset, count);
        preamble_offset += count;
        if (preamble_offset == sizeof(preamble)) {
            fake_now = after_event;
            bad_clock = fail_after_event;
            if (interrupt_after_event) interrupted = 1;
        }
        return (ssize_t)count;
    }
    errno = EAGAIN; return -1;
}

static ssize_t test_write(int fd, const void *bytes, size_t size) {
    (void)bytes;
    if (fd == STDOUT_FILENO) {
        ++wire_writes;
        if (mode == MODE_FLUSH) { fake_now = after_event; bad_clock = fail_after_event; }
    } else ++input_writes;
    return (ssize_t)size;
}

static int test_close(int fd) { (void)fd; ++closes; return 0; }

static int test_kill(pid_t pid, int number) {
    if (waits || pid != -held_child || number != SIGKILL) order_error = 1;
    ++kills;
    return 0;
}

static int test_waitid(idtype_t kind, id_t id, siginfo_t *info, int options) {
    if (kind != P_PID || id != (id_t)held_child || !(options & WNOWAIT)) order_error = 1;
    memset(info, 0, sizeof(*info));
    info->si_pid = held_child; info->si_code = CLD_EXITED; info->si_status = 0;
    return 0;
}

static pid_t test_waitpid(pid_t pid, int *status, int options) {
    if (!kills || pid != -1 || options != WNOHANG) order_error = 1;
    ++waits;
    if (waits == 1) { *status = 0; return held_child; }
    fake_now = after_event; bad_clock = fail_after_event;
    errno = ECHILD; return -1;
}

static void reset_state(struct supervisor *s, int selected) {
    memset(s, 0, sizeof(*s));
    s->child_exit = -1;
    s->input = s->out = s->err = s->ready = s->gate = -1;
    s->generation_ms = 10000; s->generation_deadline = 10000;
    s->input_limit = s->output_limit = 65536;
    mode = selected;
    wire_writes = input_writes = closes = kills = waits = order_error = 0;
    fake_now = after_event = 100;
    bad_clock = fail_after_event = interrupt_after_event = false;
    preamble_offset = 0; interrupted = 0;
}

static int ordinary_after_failure(void) {
    const unsigned char tags[] = { ARM, DATA, DISARM, CLOSE_STDIN };
    for (size_t index = 0; index < sizeof(tags); ++index) {
        for (int cancelled = 0; cancelled < 2; ++cancelled) {
            struct supervisor s; reset_state(&s, MODE_POLL);
            s.forked = s.bound_flushed = true; /* synthetic dispatch preconditions */
            s.input = 17; s.gate = 18;
            s.control_tag = tags[index]; put64(s.control_payload, 1);
            if (tags[index] == ARM) {
                s.payload_needed = 24; put64(s.control_payload + 8, 1); put64(s.control_payload + 16, 500);
            } else if (tags[index] == DATA || tags[index] == DISARM) {
                s.armed = true; s.last_epoch = 1; s.epoch_deadline = 1000;
                if (tags[index] == DATA) { s.payload_needed = 9; s.control_payload[8] = 'x'; }
                else { s.payload_needed = 16; put64(s.control_payload + 8, 1); }
            } else s.payload_needed = 8;
            poll_once(&s, fake_now);
            if (s.cause != IO_FAILURE || !(s.flags & F_IO)) return 10;
            accept_control(&s); write_input(&s);
            if (!s.stopping || s.cause != IO_FAILURE || s.requests_accepted || s.ordinary_pending ||
                s.control_size || s.input_accepted || s.input_written || s.input_ack_flushed ||
                s.input >= 0 || s.gate >= 0 || closes != 2 || input_writes || wire_writes || kills || waits) return 11;
            if (cancelled) { s.control_tag = CANCEL; s.payload_needed = 0; accept_control(&s); }
            control_eof(&s);
            if (s.cause != IO_FAILURE || !!(s.flags & F_PARENT) != !cancelled ||
                !!(s.flags & F_CANCELLED) != !!cancelled || s.requests_accepted || s.control_size) return 12;
        }
    }
    return 0;
}

static void cleanup_state(struct supervisor *s) {
    reset_state(s, MODE_CLEANUP);
    s->child = held_child; s->forked = s->owned = s->group = true;
    s->stopping = true; s->kill_at = 250; s->cleanup_deadline = 2000;
    s->flags = F_GROUP | F_STDIN_CLOSED | F_OUTPUT_EOF;
}

static int cleanup_deadlines(void) {
    const uint64_t observations[] = {1999, 2000, 2001};
    for (size_t index = 0; index < sizeof(observations) / sizeof(observations[0]); ++index) {
        struct supervisor s; cleanup_state(&s);
        uint64_t now = 1990; fake_now = now; after_event = observations[index];
        if (!cleanup_turn(&s, &now) || s.clock_failed || kills != 1 || waits != 2 || order_error ||
            (s.flags & (F_EXIT | F_REAPED)) != (F_EXIT | F_REAPED)) return 20;
        if (index == 0) {
            if (s.cause != COMPLETED || !(s.flags & F_QUIESCENT) || (s.flags & F_SHUTDOWN)) return 21;
        } else {
            if (s.cause != SHUTDOWN_UNVERIFIED || !(s.flags & F_SHUTDOWN) || (s.flags & F_QUIESCENT)) return 22;
            reap_after_last_signal(&s);
            if (s.flags & F_QUIESCENT) return 23; /* no late rehabilitation */
        }
        signal_owned(&s, SIGTERM);
        if (kills != 1 || order_error) return 24; /* guarded after any reap */
    }
    /* The fresh sample can cross cleanup AND kill time before any signal.
     * The expiry branch must still issue final signals before its reap pass. */
    struct supervisor s; cleanup_state(&s);
    uint64_t now = 100; fake_now = after_event = 2000;
    if (!cleanup_turn(&s, &now) || kills != 1 || waits != 2 || order_error ||
        s.cause != SHUTDOWN_UNVERIFIED || !(s.flags & F_REAPED) || (s.flags & F_QUIESCENT)) return 25;
    cleanup_state(&s); now = fake_now = 1990; after_event = 1999; fail_after_event = true;
    if (cleanup_turn(&s, &now) || !s.clock_failed || clock_abort(&s) != 74 || kills != 1 || order_error) return 26;
    return 0;
}

static int preamble_deadlines(void) {
    for (int index = 0; index < 5; ++index) {
        struct supervisor s; reset_state(&s, MODE_PREAMBLE);
        s.generation_deadline = 1000; fake_now = 999;
        after_event = index == 1 ? 1000 : index == 2 ? 1001 : 999;
        fail_after_event = index == 3; interrupt_after_event = index == 4;
        memset(s.nonce, 'n', sizeof(s.nonce));
        memcpy(preamble, "KTCCTL2\n", 8); memcpy(preamble + 8, s.nonce, sizeof(s.nonce));
        if (read_preamble(&s) != (index == 0) || preamble_offset != sizeof(preamble) ||
            wire_writes || kills || waits) return 30;
    }
    return 0;
}

static int flush_deadlines(void) {
    for (int index = 0; index < 4; ++index) {
        struct supervisor s; reset_state(&s, MODE_FLUSH);
        s.stopping = s.control_closed = true; s.cause = CANCELLED; s.flags = F_CANCELLED;
        fake_now = 1000; after_event = index == 0 ? 1499 : index == 1 ? 1500 : 1501;
        fail_after_event = index == 3;
        int result = emit_terminal(&s);
        if (result != (index == 0 ? 0 : 74) || wire_writes != 1 || input_writes || kills || waits) return 40;
    }
    return 0;
}

int main(int argc, char **argv) {
    struct sigaction action; memset(&action, 0, sizeof(action)); action.sa_handler = SIG_DFL;
    sigset_t unblocked;
    if (sigemptyset(&action.sa_mask) != 0 || sigemptyset(&unblocked) != 0 ||
        sigaddset(&unblocked, SIGALRM) != 0 || sigaction(SIGALRM, &action, NULL) != 0 ||
        sigprocmask(SIG_UNBLOCK, &unblocked, NULL) != 0) return 70;
    (void)alarm(3); /* independent self-expiry of this no-child state control */
    if (argc != 2) return 64;
    if (strcmp(argv[1], "poll") == 0) return ordinary_after_failure();
    if (strcmp(argv[1], "cleanup") == 0) return cleanup_deadlines();
    if (strcmp(argv[1], "preamble") == 0) return preamble_deadlines();
    if (strcmp(argv[1], "flush") == 0) return flush_deadlines();
    return 64;
}
"""


PROBE_EXPIRY_CONTROL_SOURCE = r"""
/* Exercise the real probe entry point with inherited ignored/blocked ALRM,
 * but use its finite argv mode: a broken expiry cannot strand a waiting child. */
#define main finite_probe_entry
#include "live_supervisor_probe.c"
#undef main
int main(void) {
    struct sigaction ignored; memset(&ignored, 0, sizeof(ignored)); ignored.sa_handler = SIG_IGN;
    sigset_t mask;
    if (sigemptyset(&ignored.sa_mask) != 0 || sigemptyset(&mask) != 0 ||
        sigaddset(&mask, SIGALRM) != 0 || sigaction(SIGALRM, &ignored, NULL) != 0 ||
        sigprocmask(SIG_BLOCK, &mask, NULL) != 0) return 70;
    char name[] = "probe", mode[] = "argv";
    char *arguments[] = {name, mode, NULL};
    if (finite_probe_entry(2, arguments) != 23) return 10;
    struct sigaction current;
    if (sigaction(SIGALRM, NULL, &current) != 0 || sigprocmask(SIG_SETMASK, NULL, &mask) != 0) return 71;
    unsigned int remaining = alarm(0);
    if (current.sa_handler != SIG_DFL || sigismember(&mask, SIGALRM) != 0 || !remaining || remaining > 12) return 11;
    return 0;
}
"""


@unittest.skipUnless(builder.host_identity()["target"] in builder.SUPPORTED, "live preview-2 native execution is Linux-only")
class NativeLiveSupervisorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # No TemporaryDirectory finalizer: unresolved custody must survive the
        # class/interpreter ending. Only explicit verified observation permits
        # cleanup of the exact generated attempt tree.
        cls.root = Path(tempfile.mkdtemp(prefix="ktchecker-native-live-test-"))
        cls.attempt = NativeAttempt(cls.root)
        cls.receipt = builder.build(cls.root / "resources", CC)
        cls.supervisor = cls.root / "resources" / cls.receipt["output"]["path"]
        cls.probe = cls.root / "probe"
        builder.bounded_command([str(CC), *builder.FLAGS, str(Path(__file__).with_name("live_supervisor_probe.c")),
                                 "-o", str(cls.probe)], 30)
        native_directory = Path(__file__).resolve().parent
        for name, source in (("state-control", STATE_CONTROL_SOURCE), ("probe-expiry-control", PROBE_EXPIRY_CONTROL_SOURCE)):
            source_path = cls.root / (name + ".c")
            binary_path = cls.root / name
            source_path.write_text(source, encoding="utf-8")
            builder.bounded_command([str(CC), *builder.FLAGS, "-I", str(native_directory), str(source_path),
                                     "-o", str(binary_path)], 30)
        cls.state_control = cls.root / "state-control"
        cls.probe_expiry_control = cls.root / "probe-expiry-control"

    @classmethod
    def tearDownClass(cls):
        cls.attempt.cleanup()

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(prefix="case-", dir=self.root))
        self.channels = []
        self.processes = []

    def tearDown(self):
        failures = []
        for channel in self.channels:
            try:
                channel.selector.close()
            except Exception:
                self.attempt.retained = True
                failures.append("channel selector closure failed")
        for process in self.processes:
            # EOF requests native owner-loss cleanup; an unresolved helper or
            # close preserves the attempt/traces. No forced helper teardown.
            if not self.attempt.observe(process):
                failures.append("obtained process/streams unresolved; private attempt retained")
        self.assertFalse(failures, "; ".join(failures))

    def spawn_owned(self, *args, **kwargs):
        process = self.attempt.launch(*args, **kwargs)
        self.processes.append(process)
        return process

    def attach_channel(self, process, nonce, generation, input_limit, output_limit):
        try:
            channel = Channel(process, nonce, generation, input_limit, output_limit)
        except BaseException:
            self.attempt.retained = True
            self.attempt.observe(process)
            raise
        self.channels.append(channel)
        return channel

    def start(self, args, *, generation=3000, input_limit=65_536, output_limit=65_536,
              preamble=True, wait_bound=True, initial=b"", prefix=(), nonce=None, **kwargs):
        nonce = os.urandom(16) if nonce is None else nonce
        command = [*map(str, prefix), str(self.supervisor), "--ktc-owned-live-v2", str(generation),
                   str(input_limit), str(output_limit), nonce.hex(), "--", *map(str, args)]
        process = self.spawn_owned(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, bufsize=0, **kwargs)
        channel = self.attach_channel(process, nonce, generation, input_limit, output_limit)
        if preamble:
            channel.send_bytes(b"KTCCTL2\n" + nonce + initial)
        if wait_bound:
            channel.wait_bound()
        return channel

    def finish(self, channel, *, quiescent=True):
        self.assertEqual(0, channel.finish())
        result = channel.final
        if quiescent:
            self.assertTrue(result["flags"] & F_QUIESCENT, result)
            self.assertFalse(result["flags"] & (F_OWNERSHIP | F_SHUTDOWN), result)
        return result

    def wait_file(self, path):
        deadline = time.monotonic() + 1.5
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertTrue(path.exists(), "public probe readiness marker absent")

    def resistant(self, mode="resist", **kwargs):
        marker, ready = self.case / "late", self.case / "ready"
        channel = self.start([self.probe, mode, marker, ready], **kwargs)
        self.wait_file(ready)
        return channel, marker

    def fill_to_partial_write(self, channel, *, observe_missing_ack=True):
        line = channel.stdout_line()
        self.assertRegex(line, rb"^capacity=[0-9]+\n$")
        capacity = int(line[9:])
        self.assertTrue(4096 <= capacity <= 1048576)
        # Leave exactly 4096 bytes free. An 8192-byte nonblocking write can
        # partially forward but cannot ACK until the nonreader consumes more.
        remaining = capacity - 4096
        while remaining:
            size = min(8192, remaining)
            channel.data(b"x" * size)
            remaining -= size
        sequence = channel.data(b"y" * 8192, wait=False)
        if observe_missing_ack:
            self.assertIsNone(channel.wait_ack(sequence, timeout=0.06, required=False))
        return capacity

    def run_state_control(self, *arguments):
        # This direct, self-expiring executable contains no invoked fork path.
        # Its stubbed syscall evidence never substitutes for real native tests.
        process = self.spawn_owned([str(self.state_control), *arguments], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual(0, process.wait(timeout=5))

    def test_faulted_poll_retires_before_any_ordinary_acceptance_but_preserves_cancel_eof(self):
        self.run_state_control("poll")

    def test_cleanup_proof_requires_fresh_time_before_deadline_and_never_rehabilitates_late_reap(self):
        self.run_state_control("cleanup")

    def test_complete_preamble_still_requires_fresh_nonexpired_uninterrupted_time(self):
        self.run_state_control("preamble")

    def test_full_final_write_at_or_after_deadline_or_clock_failure_cannot_return_zero(self):
        self.run_state_control("flush")

    def test_probe_entry_resets_and_unblocks_inherited_sigalrm_before_arming_expiry(self):
        process = self.spawn_owned([str(self.probe_expiry_control)], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual(0, process.wait(timeout=5))

    def test_built_receipt_binds_new_source_builder_driver_and_binary(self):
        receipt = self.receipt
        self.assertEqual("built", receipt["status"])
        self.assertEqual(builder.SCHEMA, receipt["schemaVersion"])
        self.assertEqual(builder.file_digest(self.supervisor, builder.MAX_BINARY), receipt["output"]["sha256"])
        self.assertEqual(builder.file_digest(Path(__file__).with_name("owned_live_process_supervisor.c"), builder.MAX_BINARY),
                         receipt["inputs"]["source"]["sha256"])
        self.assertEqual(builder.file_digest(Path(builder.__file__), builder.MAX_BINARY), receipt["inputs"]["builder"]["sha256"])
        self.assertEqual(builder.file_digest(CC.resolve(), 256 * 1024 * 1024), receipt["compiler"]["driverSha256"])
        self.assertTrue(receipt["compilerDriverIdentityOnly"])
        self.assertFalse(receipt["transitiveCompilerToolchainVerified"])
        self.assertFalse(receipt["sourceToBinaryReproducibilityEstablished"])
        root = self.root / "resources" / builder.PREFIX
        retained = (root / "build-provenance.json").read_bytes()
        self.assertLessEqual(len(retained), builder.MAX_RECEIPT)
        self.assertNotIn(str(self.root).encode(), retained)
        self.assertEqual([builder.PROTOCOL, receipt["host"]["target"], receipt["inputs"]["source"]["sha256"],
                          receipt["output"]["sha256"]], (root / "manifest").read_text().splitlines())

    def test_real_duplex_reply_then_later_dynamic_request_on_same_live_child(self):
        channel = self.start([self.probe, "duplex"])
        self.assertIsNone(channel.final)  # BOUND has no execution/ready flags
        self.assertEqual((ARM, 1, 1, 0), channel.arm())
        first = b"first-opaque-request-" + os.urandom(12)
        first_wire = struct.pack(">H", len(first)) + first
        self.assertEqual((DATA, 2, len(first_wire), len(first_wire)), channel.data(first_wire))
        reply_size = struct.unpack(">H", channel.stdout_bytes(2))[0]
        reply = channel.stdout_bytes(reply_size)
        self.assertEqual(first[::-1], reply)
        self.assertIsNone(channel.process.poll())
        self.assertIsNone(channel.final)
        channel.disarm(1)
        time.sleep(0.03)  # same generation remains live while disarmed/idle
        channel.arm(2)
        second = b"selected-after-response:" + reply
        second_wire = struct.pack(">H", len(second)) + second
        channel.data(second_wire)
        reply_size = struct.unpack(">H", channel.stdout_bytes(2))[0]
        self.assertEqual(second[::-1], channel.stdout_bytes(reply_size))
        self.assertIsNone(channel.process.poll())
        channel.close_child()
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK, 23, 6, 2),
                         (result["cause"], result["flags"], result["exit"], result["requests"], result["epoch"]))
        self.assertEqual(len(first_wire) + len(second_wire), result["acked"])

    def test_immediate_arm_after_bound_never_races_before_fork(self):
        for _ in range(5):
            channel = self.start([self.probe, "echo"])
            channel.arm()  # no readiness event, sleep, or other child observation
            channel.close_child()
            result = self.finish(channel)
            self.assertEqual((0, COMPLETED_MASK), (result["cause"], result["flags"]))

    def test_complete_cancel_with_preamble_prevents_fork_and_proves_no_child(self):
        marker = self.case / "started"
        channel = self.start([self.probe, "started", marker], initial=frame(CANCEL))
        result = self.finish(channel)
        self.assertEqual(1, result["cause"])
        self.assertTrue(result["flags"] & F_NO_CHILD)
        self.assertFalse(marker.exists())
        self.assertEqual((0, 0, 0), (result["requests"], result["accepted"], result["epoch"]))

    def test_ordinary_before_bound_is_not_buffered_or_accepted(self):
        marker = self.case / "started"
        channel = self.start([self.probe, "started", marker], initial=frame(ARM, struct.pack(">QQQ", 1, 1, 1000)))
        result = self.finish(channel)
        self.assertEqual(9, result["cause"])
        self.assertTrue(result["flags"] & F_NO_CHILD)
        self.assertEqual(0, result["requests"])
        self.assertFalse(marker.exists())

    def test_fragmented_preamble_header_and_data_body(self):
        channel = self.start([self.probe, "echo"], preamble=False, wait_bound=False)
        for byte in b"KTCCTL2\n" + channel.nonce:
            channel.send_bytes(bytes([byte]))
            time.sleep(0.001)
        channel.wait_bound()
        for byte in frame(ARM, struct.pack(">QQQ", 1, 1, 1500)):
            channel.send_bytes(bytes([byte]))
            time.sleep(0.001)
        channel.next_sequence = 2
        self.assertEqual((ARM, 1, 1, 0), channel.wait_ack(1))
        data = b"fragmented-input"
        for byte in frame(DATA, struct.pack(">Q", 2) + data):
            channel.send_bytes(bytes([byte]))
            time.sleep(0.001)
        channel.next_sequence = 3
        self.assertEqual((DATA, 2, len(data), len(data)), channel.wait_ack(2))
        self.assertEqual(data, channel.stdout_bytes(len(data)))
        channel.close_child()
        self.assertEqual(0, self.finish(channel)["cause"])

    def test_partial_data_is_never_speculatively_forwarded(self):
        channel = self.start([self.probe, "echo"])
        channel.arm()
        record = frame(DATA, struct.pack(">Q", 2) + b"AB")
        channel.send_bytes(record[:-1])
        channel.pump(0.06)
        self.assertEqual(b"", bytes(channel.output[STDOUT]))
        self.assertEqual(1, channel.ack_count)
        channel.send_bytes(record[-1:]); channel.next_sequence = 3
        self.assertEqual((DATA, 2, 2, 2), channel.wait_ack(2))
        self.assertEqual(b"AB", channel.stdout_bytes(2))
        channel.close_child()
        self.assertEqual(2, self.finish(channel)["accepted"])

    def test_partial_data_then_owner_eof_is_not_counted_or_forwarded(self):
        channel = self.start([self.probe, "echo"])
        channel.arm()
        channel.send_bytes(frame(DATA, struct.pack(">Q", 2) + b"AB")[:-1])
        channel.close_control()
        result = self.finish(channel)
        self.assertEqual(4, result["cause"])
        self.assertEqual(F_PARENT | F_PROTOCOL, result["flags"] & (F_PARENT | F_PROTOCOL))
        self.assertEqual((0, 0, 1), (result["accepted"], result["captured"], result["requests"]))

    def test_bad_wrong_nonce_and_truncated_preambles_never_fork(self):
        for index, kind in enumerate(("magic", "nonce", "truncated")):
            marker = self.case / ("started-" + str(index))
            channel = self.start([self.probe, "started", marker], preamble=False, wait_bound=False)
            preamble = b"KTCCTL2\n" + channel.nonce
            if kind == "magic":
                preamble = b"KTCOWN1\n" + channel.nonce
            elif kind == "nonce":
                preamble = preamble[:-1] + bytes([preamble[-1] ^ 1])
            else:
                preamble = preamble[:-1]
            channel.send_bytes(preamble); channel.close_control()
            self.assertNotEqual(0, channel.finish(valid=False))
            self.assertFalse(channel.bound)
            self.assertFalse(marker.exists())

    def test_incomplete_preamble_obeys_immutable_lifetime(self):
        marker = self.case / "started"
        channel = self.start([self.probe, "started", marker], generation=100, preamble=False, wait_bound=False)
        channel.send_bytes(b"KTCCTL2\n")
        self.assertNotEqual(0, channel.finish(valid=False))
        self.assertFalse(marker.exists())
        self.assertFalse(channel.bound)

    def test_exact_literal_argv_nonzero_exit_and_separate_output(self):
        arguments = ["", "space value", "single'quote", 'double"quote', "*?[glob]", "$(not-expanded)", "line\nbreak", "ユニコード"]
        channel = self.start([self.probe, "argv", *arguments])
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK, 23), (result["cause"], result["flags"], result["exit"]))
        expected = b"".join(struct.pack(">I", len(arg.encode())) + arg.encode() for arg in arguments)
        self.assertEqual(expected, bytes(channel.output[STDOUT]))
        self.assertEqual(b"probe-stderr\n", bytes(channel.output[STDERR]))

    def test_child_stdin_close_is_distinct_from_owner_control_eof(self):
        channel = self.start([self.probe, "echo"])
        self.assertEqual((CLOSE_STDIN, 1, 0, 0), channel.close_child())
        self.assertFalse(channel.process.stdin.closed)
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK), (result["cause"], result["flags"]))

    def test_closed_stdin_allows_watchdog_bookkeeping_but_not_more_data_or_close(self):
        channel = self.start([self.probe, "hold"])
        channel.close_child(); channel.arm(); channel.disarm(); channel.cancel()
        result = self.finish(channel)
        self.assertEqual((1, 3, 3), (result["cause"], result["requests"], result["acks"]))
        for tag, body in ((CLOSE_STDIN, b""), (DATA, b"not-reopened")):
            channel = self.start([self.probe, "hold"])
            channel.close_child(); channel.arm()
            channel.request(tag, body, wait=False)
            result = self.finish(channel)
            self.assertEqual((9, 2, 2), (result["cause"], result["requests"], result["acks"]))

    def test_cancel_then_eof_is_terminal_request_not_owner_loss(self):
        channel, marker = self.resistant()
        channel.cancel(); channel.close_control()
        result = self.finish(channel)
        self.assertEqual(1, result["cause"])
        self.assertTrue(result["flags"] & F_CANCEL)
        self.assertFalse(result["flags"] & (F_PARENT | F_EXEC))
        self.assertEqual(137, result["exit"])
        self.assertFalse(marker.exists())

    def test_unsolicited_owner_eof_retires_and_cleans(self):
        channel, marker = self.resistant()
        channel.close_control()
        result = self.finish(channel)
        self.assertEqual(4, result["cause"])
        self.assertTrue(result["flags"] & F_PARENT)
        self.assertFalse(marker.exists())

    def test_cancel_prefix_eof_is_owner_loss_not_cancellation(self):
        channel, marker = self.resistant()
        channel.send_bytes(frame(CANCEL)[:-1]); channel.close_control()
        result = self.finish(channel)
        self.assertEqual(4, result["cause"])
        self.assertTrue(result["flags"] & F_PROTOCOL)
        self.assertFalse(result["flags"] & F_CANCEL)
        self.assertFalse(marker.exists())

    def test_control_headers_sequences_and_transitions_fail_closed(self):
        cases = (frame(0), struct.pack(">BI", DATA, 8201), frame(DATA, struct.pack(">Q", 1)),
                 frame(CANCEL, b"x"), frame(ARM, struct.pack(">QQQ", 0, 1, 1000)),
                 frame(ARM, struct.pack(">QQQ", 1, 2, 1000)), frame(ARM, struct.pack(">QQQ", 1, 1, 0)),
                 frame(DATA, struct.pack(">Q", 1) + b"unarmed"), frame(DISARM, struct.pack(">QQ", 1, 1)))
        for malformed in cases:
            with self.subTest(header=malformed[:5]):
                channel = self.start([self.probe, "hold"])
                channel.send_bytes(malformed)
                result = self.finish(channel)
                self.assertEqual(9, result["cause"])
                self.assertTrue(result["flags"] & F_PROTOCOL)
                self.assertEqual(0, result["requests"])

    def test_global_credit_rejects_pipelined_ordinary_control(self):
        channel = self.start([self.probe, "hold"])
        channel.send_bytes(frame(ARM, struct.pack(">QQQ", 1, 1, 1000)) + frame(DISARM, struct.pack(">QQ", 2, 1)))
        result = self.finish(channel)
        self.assertEqual(9, result["cause"])
        self.assertEqual((1, 1, 1), (result["requests"], result["acks"], result["epoch"]))

    def test_arm_cannot_replace_or_extend_active_epoch(self):
        channel = self.start([self.probe, "hold"])
        channel.arm(1, 1000)
        channel.request(ARM, struct.pack(">QQ", 2, 3000), wait=False)
        result = self.finish(channel)
        self.assertEqual(9, result["cause"])
        self.assertEqual((1, 1), (result["requests"], result["epoch"]))

    def test_expired_disarm_cannot_erase_timeout(self):
        channel, marker = self.resistant()
        channel.arm(1, 100)
        time.sleep(0.14)
        channel.request(DISARM, struct.pack(">Q", 1), wait=False)
        result = self.finish(channel)
        self.assertEqual(2, result["cause"])
        self.assertTrue(result["flags"] & F_TIMEOUT)
        self.assertEqual((1, 1, 1), (result["requests"], result["acks"], result["epoch"]))
        self.assertFalse(marker.exists())

    def test_generation_lifetime_continues_while_disarmed_and_idle(self):
        channel, marker = self.resistant(generation=180)
        channel.arm(1, 100)
        channel.disarm(1)
        result = self.finish(channel)
        self.assertEqual(2, result["cause"])
        self.assertTrue(result["flags"] & F_TIMEOUT)
        self.assertEqual((2, 2, 1), (result["requests"], result["acks"], result["epoch"]))
        self.assertFalse(marker.exists())

    def test_long_arm_is_subordinate_to_short_generation(self):
        channel, marker = self.resistant(generation=150)
        channel.arm(1, 3600000)
        result = self.finish(channel)
        self.assertEqual(2, result["cause"])
        self.assertFalse(marker.exists())

    def test_input_limit_rejects_without_counting_or_acknowledging_excess(self):
        channel = self.start([self.probe, "echo"], input_limit=3)
        channel.arm(); channel.data(b"abc"); channel.data(b"d", wait=False)
        result = self.finish(channel)
        self.assertEqual(11, result["cause"])
        self.assertEqual((3, 3, 3, 2, 2),
                         (result["accepted"], result["written"], result["acked"], result["requests"], result["acks"]))

    def test_output_limit_counts_only_one_bounded_overshoot(self):
        channel = self.start([self.probe, "echo"], output_limit=3)
        channel.arm(); channel.data(b"abcd", wait=False)
        result = self.finish(channel)
        self.assertEqual(3, result["cause"])
        self.assertEqual(3, result["captured"])
        self.assertTrue(3 < result["observed"] <= 3 + 8192)
        self.assertFalse(result["flags"] & F_OUTPUT_EOF)

    def test_pending_partial_input_does_not_block_cancel(self):
        channel, marker = self.resistant("backpressure", input_limit=2 * 1024 * 1024)
        channel.arm(1, 2000)
        capacity = self.fill_to_partial_write(channel)
        started = time.monotonic(); channel.cancel()
        result = self.finish(channel)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(1, result["cause"])
        self.assertEqual(capacity + 4096, result["accepted"])
        self.assertEqual(capacity, result["written"])
        self.assertEqual(capacity - 4096, result["acked"])
        self.assertEqual(result["requests"] - 1, result["acks"])
        self.assertTrue(result["flags"] & F_INPUT_INCOMPLETE)
        self.assertFalse(marker.exists())

    def test_pending_partial_input_does_not_block_epoch_deadline(self):
        channel, marker = self.resistant("backpressure", input_limit=2 * 1024 * 1024)
        channel.arm(1, 200)
        self.fill_to_partial_write(channel)
        result = self.finish(channel)
        self.assertEqual(2, result["cause"])
        self.assertTrue(result["flags"] & F_INPUT_INCOMPLETE)
        self.assertFalse(marker.exists())

    def test_close_cannot_bypass_outstanding_partial_data(self):
        channel, marker = self.resistant("backpressure", input_limit=2 * 1024 * 1024)
        channel.arm(1, 2000)
        self.fill_to_partial_write(channel)
        channel.request(CLOSE_STDIN, wait=False)
        result = self.finish(channel)
        self.assertEqual(9, result["cause"])
        self.assertTrue(result["flags"] & F_INPUT_INCOMPLETE)
        self.assertFalse(marker.exists())

    def test_normal_leader_exit_with_unwritten_accepted_data_is_io_failure(self):
        channel = self.start([self.probe, "early-exit"], input_limit=2 * 1024 * 1024)
        channel.arm(1, 2000)
        self.fill_to_partial_write(channel)
        result = self.finish(channel)
        self.assertEqual((5, 7), (result["cause"], result["exit"]))
        self.assertEqual(F_INPUT_INCOMPLETE | F_EXEC, result["flags"] & (F_INPUT_INCOMPLETE | F_EXEC))
        self.assertGreater(result["accepted"], result["written"])

    def test_all_control_requests_share_finite_quota_without_ack_queue_growth(self):
        channel = self.start([self.probe, "hold"], generation=10000)
        for epoch in range(1, 4097):
            channel.arm(epoch, 5000)
            channel.disarm(epoch)
        self.assertEqual(8192, channel.ack_count)
        channel.request(CLOSE_STDIN, wait=False)
        result = self.finish(channel)
        self.assertEqual(12, result["cause"])
        self.assertEqual((8192, 8192, 4096), (result["requests"], result["acks"], result["epoch"]))

    def test_duplicate_cancel_flood_does_not_extend_cleanup(self):
        channel, marker = self.resistant()
        started = time.monotonic()
        channel.send_bytes(frame(CANCEL) * 4096)
        result = self.finish(channel)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual(1, result["cause"])
        self.assertEqual(0, result["requests"])
        self.assertFalse(marker.exists())

    def test_blocked_helper_stdout_still_accepts_cancel_and_cleans_before_unknown_exit(self):
        channel, marker = self.resistant("flood", generation=5000, output_limit=16 * 1024 * 1024)
        time.sleep(0.04)  # no output pump: data slot and owner pipe backpressure
        started = time.monotonic(); channel.cancel()
        self.assertEqual(74, channel.process.wait(timeout=3.5))
        self.assertLess(time.monotonic() - started, 3.5)
        self.assertEqual(74, channel.finish(valid=False))
        self.assertIsNone(channel.final)  # blocked delivery is NOT clean closure
        self.assertFalse(marker.exists())

    def test_blocked_helper_stdout_cannot_disable_epoch_deadline(self):
        channel, marker = self.resistant("flood", generation=5000, output_limit=16 * 1024 * 1024)
        channel.arm(1, 100)
        self.assertEqual(74, channel.process.wait(timeout=3.5))
        self.assertEqual(74, channel.finish(valid=False))
        self.assertIsNone(channel.final)
        self.assertFalse(marker.exists())

    def test_cancel_is_still_read_with_both_child_stdin_and_helper_stdout_blocked(self):
        channel, marker = self.resistant("both-pressure", generation=5000,
                                          input_limit=2 * 1024 * 1024, output_limit=16 * 1024 * 1024)
        channel.arm(1, 3000)
        channel.data(b"F")  # probe consumes only this one flood trigger
        self.fill_to_partial_write(channel, observe_missing_ack=False)
        time.sleep(0.04)  # stop all output consumption with DATA still pending
        started = time.monotonic(); channel.cancel()
        self.assertEqual(74, channel.process.wait(timeout=3.5))
        self.assertLess(time.monotonic() - started, 3.5)
        self.assertEqual(74, channel.finish(valid=False))
        self.assertIsNone(channel.final)
        self.assertFalse(marker.exists())

    def test_broken_owner_stdout_still_runs_owned_cleanup(self):
        channel, marker = self.resistant()
        channel.close_owner_output(); channel.cancel()
        self.assertEqual(74, channel.finish(valid=False))
        self.assertFalse(marker.exists())

    def test_fast_leader_exit_kills_term_resistant_descendant_before_late_write(self):
        channel, marker = self.resistant("orphan")
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK, 0), (result["cause"], result["flags"], result["exit"]))
        time.sleep(1)
        self.assertFalse(marker.exists())

    def test_unrelated_retained_group_is_not_signaled(self):
        marker = self.case / "unrelated"
        process = self.spawn_owned([str(self.probe), "unrelated", str(marker)], start_new_session=True,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(b"ready\n", process.stdout.readline())
        channel, late = self.resistant("orphan")
        self.assertEqual(0, self.finish(channel)["cause"])
        self.assertEqual(0, process.wait(timeout=2))
        self.assertEqual("unrelated-survived", marker.read_text())
        self.assertFalse(late.exists())

    def test_helper_term_int_hup_are_cleanup_requests_not_immediate_helper_death(self):
        for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            case = self.case / str(number); case.mkdir()
            marker, ready = case / "late", case / "ready"
            channel = self.start([self.probe, "resist", marker, ready])
            self.wait_file(ready)
            channel.process.send_signal(number)  # intentional signal control, direct unreaped helper
            result = self.finish(channel)
            self.assertEqual(1, result["cause"])
            self.assertFalse(marker.exists())

    def test_exec_failure_records_actual_exit_without_confirmation(self):
        channel = self.start([self.case / "absent-executable"])
        result = self.finish(channel)
        self.assertEqual((6, 126), (result["cause"], result["exit"]))
        self.assertTrue(result["flags"] & F_EXEC_FAILED)
        self.assertFalse(result["flags"] & F_EXEC)

    def test_signaled_and_reserved_126_exits_never_confirm_exec(self):
        for arguments, code in ((["signal"], 137), (["exit", "126"], 126)):
            channel = self.start([self.probe, *arguments])
            result = self.finish(channel)
            self.assertEqual((7, code), (result["cause"], result["exit"]))
            self.assertFalse(result["flags"] & F_EXEC)
            self.assertTrue(channel.output[STDOUT])

    def test_inherited_ignored_sigchld_is_reset_before_owned_fork(self):
        def ignore_sigchld():
            signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        channel = self.start([self.probe, "exit", "0"], preexec_fn=ignore_sigchld)
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK), (result["cause"], result["flags"]))

    def test_invalid_launch_fields_and_v1_discriminator_never_fork(self):
        nonce = bytes.fromhex("00112233445566778899aabbccddeeff")
        base = [str(self.supervisor), "--ktc-owned-live-v2", "1000", "65536", "65536", nonce.hex(), "--", str(self.probe), "hold"]
        for index, value in ((1, "--ktc-owned-child-v1"), (2, "0"), (2, "01000"), (2, "3600001"),
                             (2, "+1"), (3, "0"), (4, "16777217"), (5, "0" * 32),
                             (5, nonce.hex().upper()), (6, "---"), (7, "relative-probe")):
            with self.subTest(index=index, value=value):
                command = list(base); command[index] = value
                process = self.spawn_owned(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, bufsize=0)
                channel = self.attach_channel(process, nonce, 1000, 65536, 65536)
                self.assertEqual(64, channel.finish(valid=False))
                self.assertFalse(channel.magic)

    def test_actual_owner_death_closes_control_and_native_still_closes_owned_generation(self):
        libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        self.assertEqual(0, libc.prctl(37, ctypes.byref(previous), 0, 0, 0))
        self.assertEqual(0, libc.prctl(36, 1, 0, 0, 0))
        adoption = object()
        self.attempt.pending.append(adoption)  # retained until the actual adopted child is reaped
        marker, ready, pidfile = self.case / "late", self.case / "ready", self.case / "helper-pid"
        nonce = os.urandom(16)
        owner_code = """
import os, pathlib, subprocess, sys, time
helper, probe, marker, ready, pidfile, nonce = sys.argv[1:]
process = subprocess.Popen([helper, '--ktc-owned-live-v2', '3000', '65536', '65536', nonce,
    '--', probe, 'resist', marker, ready], stdin=subprocess.PIPE,
    stdout=sys.stdout.buffer, stderr=sys.stderr.buffer, close_fds=True, bufsize=0)
pathlib.Path(pidfile).write_text(str(process.pid))
process.stdin.write(b'KTCCTL2\\n' + bytes.fromhex(nonce))
deadline = time.monotonic() + 1.5
while not pathlib.Path(ready).exists() and time.monotonic() < deadline:
    time.sleep(0.005)
os._exit(17)
"""
        try:
            owner = self.spawn_owned([sys.executable, "-c", owner_code, str(self.supervisor), str(self.probe),
                                     str(marker), str(ready), str(pidfile), nonce.hex()], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
            channel = self.attach_channel(owner, nonce, 3000, 65536, 65536)
            self.assertEqual(17, channel.finish(valid=False))
            self.assertIsNotNone(channel.final)
            self.assertTrue(channel.wire_eof)
            self.assertEqual(4, channel.final["cause"])
            self.assertTrue(channel.final["flags"] & F_QUIESCENT)
            self.assertFalse(marker.exists())
            # Kernel subreaper adoption, not a signaling authority/PID census.
            pid = int(pidfile.read_text())
            deadline = time.monotonic() + 2
            waited = (0, 0)
            while waited[0] == 0 and time.monotonic() < deadline:
                waited = os.waitpid(pid, os.WNOHANG)
                if waited[0] == 0:
                    time.sleep(0.005)
            self.assertEqual(pid, waited[0])
            self.assertTrue(os.WIFEXITED(waited[1]))
            self.assertEqual(0, os.WEXITSTATUS(waited[1]))
            self.attempt.pending.remove(adoption)
        finally:
            self.assertEqual(0, libc.prctl(36, previous.value, 0, 0, 0))

    def test_missing_quiescence_is_reported_without_numeric_pid_or_helper_kill_recovery(self):
        libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        self.assertEqual(0, libc.prctl(37, ctypes.byref(previous), 0, 0, 0))
        self.assertEqual(0, libc.prctl(36, 1, 0, 0, 0))
        adoption = object()
        self.attempt.pending.append(adoption)
        ready = self.case / "escaped-ready"
        try:
            channel = self.start([self.probe, "escape", ready])
            result = self.finish(channel, quiescent=False)
            self.assertEqual(8, result["cause"])
            self.assertTrue(result["flags"] & F_SHUTDOWN)
            self.assertFalse(result["flags"] & F_QUIESCENT)
            # The escaped probe has its own fixed expiry. We only reap the
            # actual adopted child after it exits; never signal its number.
            pid = int(ready.read_text())
            deadline = time.monotonic() + 3
            waited = (0, 0)
            while waited[0] == 0 and time.monotonic() < deadline:
                waited = os.waitpid(pid, os.WNOHANG)
                if waited[0] == 0:
                    time.sleep(0.005)
            self.assertEqual(pid, waited[0])
            self.assertTrue(os.WIFEXITED(waited[1]))
            self.attempt.pending.remove(adoption)
        finally:
            self.assertEqual(0, libc.prctl(36, previous.value, 0, 0, 0))

    def test_syscalls_final_real_signal_precedes_any_helper_reap(self):
        trace_tool = Path("/usr/bin/strace")
        self.assertTrue(trace_tool.is_file(), "explicit existing strace required for native Linux ordering control")
        trace = self.case / "syscalls"
        marker, ready = self.case / "late", self.case / "ready"
        channel = self.start([self.probe, "orphan", marker, ready],
                             prefix=[trace_tool, "-f", "-qq", "-o", trace,
                                     "-e", "trace=clone,fork,vfork,kill,wait4,waitid"])
        result = self.finish(channel)
        self.assertEqual((0, COMPLETED_MASK), (result["cause"], result["flags"]))
        try:
            lines = bounded_trace_text(trace).splitlines()
        except BaseException:
            self.attempt.retained = True
            raise
        signals = []
        for index, line in enumerate(lines):
            match = re.search(r"^(\d+)\s+kill\(-(\d+), (SIGTERM|SIGKILL)\)", line)
            if match:
                signals.append((index, int(match[1]), int(match[2]), match[3]))
        self.assertEqual(["SIGTERM", "SIGKILL"], [entry[3] for entry in signals])
        helper, leader = signals[0][1:3]
        self.assertTrue(all(entry[1:3] == (helper, leader) for entry in signals))
        helper_lines = [(index, line) for index, line in enumerate(lines) if re.match(r"^" + str(helper) + r"\s", line)]
        reaps = [index for index, line in helper_lines if "wait4(" in line]
        self.assertTrue(reaps, "no actual helper reap syscall observed")
        self.assertLess(max(entry[0] for entry in signals), min(reaps))
        self.assertFalse(any(index > min(reaps) and "kill(" in line for index, line in helper_lines))
        self.assertTrue(any("waitid(P_PID, " + str(leader) in line and "WNOWAIT" in line for _, line in helper_lines))
        self.assertTrue(any("wait4(" in line and "ECHILD" in line for _, line in helper_lines))
        self.assertTrue(any(re.search(r"wait4\(.*\)\s*=\s*" + str(leader) + r"(?:\s|$)", line)
                            for _, line in helper_lines))
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--cc", type=Path, required=True)
    args, unittest_args = parser.parse_known_args()
    CC = args.cc
    unittest.main(argv=[sys.argv[0], *unittest_args])
