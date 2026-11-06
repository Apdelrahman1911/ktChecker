#!/usr/bin/env python3
"""Explicit owner/controller example, not a project importer or a model host.

Creates two clients and synthetic immutable overlays in a NEW private directory.
--prepare-only retains non-authorized records and never submits a review.
Ordinary runs and explicit benchmark runs authorize their prepared profiles.
Requires an owner-selected compiler JDK; does not download, prepare or copy one.
The CLI, not this script's lightweight path preflight, verifies the complete
link-free JDK 21 image. JAVA_HOME selects the independent broker JVM.
Requires matching host-specific bundled containment bytes; their inspection
is not execution, cleanup, sandboxing or release-qualification proof.

This POSIX example bounds private subprocess I/O and deadlines, and cleans up
its cooperative process groups. It is not a sandbox for hostile owner code.
Polling is application observation, never model delivery or full-project proof.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import uuid

import preview_benchmark as benchmark


RULE = "ktc.compiler.common.abstract_member_not_implemented"
CONTRACT = "ktchecker/preview-1"
FINDING_WIRE_PROFILE = "ktchecker/finding-retrieval-attachments-1"
COMPILER = "kotlin-compiler-jvm"
UPSTREAM = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"
CONTAINMENT_PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
CONTAINMENT_TARGETS = {"linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"}
MAX_FRAME_BYTES = 16_777_216  # Protocol limit excludes the terminating LF.
MAX_PRIVATE_BYTES = 8_388_608
OWNER_TIMEOUT_SECONDS = 90.0
RPC_TIMEOUT_SECONDS = 30.0
REVIEW_TIMEOUT_SECONDS = 240.0
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
SOURCES = {
    "agent-a": "interface Contract { fun answer(): Int }\nclass Broken : Contract\n",
    "agent-b": "package independent\nfun unrelated(): Int = 42\n",
}
TERMINAL_STATES = {"complete", "incomplete", "cancelled"}


class ExampleError(RuntimeError):
    """Only script-owned codes cross the public boundary; no private exceptions."""

    def __init__(self, code: str):
        self.code = "preview_example_" + code
        super().__init__(self.code)


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, _message: str) -> None:
        # argparse's default error echoes unknown arguments. They might contain
        # an accidentally pasted credential; only static diagnostics are public.
        self.exit(2, json.dumps({"state": "failed", "code": "preview_example_invalid_arguments",
                                 "releaseQualified": False}) + "\n")


def require(condition: bool, code: str) -> None:
    if not condition:
        raise ExampleError(code)


def _json_tree(value: object) -> None:
    pending = [(value, 0)]
    count = 0
    while pending:
        node, depth = pending.pop()
        count += 1
        require(depth <= 64 and count <= 200_000, "invalid_json")
        if isinstance(node, str):
            node.encode("utf-8", errors="strict")  # Reject escaped lone surrogates too.
        elif isinstance(node, dict):
            for key, child in node.items():
                require(isinstance(key, str), "invalid_json")
                key.encode("utf-8", errors="strict")
                pending.append((child, depth + 1))
        elif isinstance(node, list):
            pending.extend((child, depth + 1) for child in node)
        elif isinstance(node, float):
            require(math.isfinite(node), "invalid_json")
        else:
            require(node is None or isinstance(node, (int, bool)), "invalid_json")


def parse_json(data: bytes, limit: int = MAX_FRAME_BYTES) -> dict:
    require(len(data) <= limit, "frame_limit")

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "invalid_json")
            result[key] = value
        return result

    def constant(_value):
        raise ExampleError("invalid_json")

    try:
        value = json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=pairs,
                           parse_constant=constant)
        _json_tree(value)
        require(isinstance(value, dict), "invalid_json")
        return value
    except (ValueError, UnicodeError, RecursionError, TypeError):
        raise ExampleError("invalid_json") from None


def encode_json(value: dict, limit: int = MAX_FRAME_BYTES) -> bytes:
    try:
        _json_tree(value)
        data = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    except (ValueError, UnicodeError, RecursionError, TypeError):
        raise ExampleError("invalid_json") from None
    require(len(data) <= limit, "frame_limit")
    return data


@dataclass(frozen=True)
class PipeLimits:
    frame_bytes: int = MAX_FRAME_BYTES
    stdout_bytes: int = 67_108_864
    stderr_bytes: int = 1_048_576
    stdin_bytes: int = 67_108_864
    cleanup_seconds: float = 4.0

    def __post_init__(self):
        require(type(self.frame_bytes) is int and 1 <= self.frame_bytes <= MAX_FRAME_BYTES, "invalid_limits")
        for value, maximum in ((self.stdout_bytes, 67_108_864), (self.stderr_bytes, 1_048_576),
                               (self.stdin_bytes, 67_108_864)):
            require(type(value) is int and 1 <= value <= maximum, "invalid_limits")
        require(isinstance(self.cleanup_seconds, (int, float)) and
                not isinstance(self.cleanup_seconds, bool) and math.isfinite(self.cleanup_seconds) and
                0 < self.cleanup_seconds <= 5, "invalid_limits")


class BoundedProcess:
    """Binary nonblocking pipes. Every write/read/exit wait shares a deadline."""

    def __init__(self, command: list[str], limits: PipeLimits | None = None):
        require(os.name == "posix", "posix_required")
        require(all(hasattr(os, name) for name in ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT")),
                "unreaped_process_guard_unavailable")
        self.limits = limits or PipeLimits()
        self.stdout_bytes = self.stderr_bytes = self.stdin_bytes = 0
        self.buffer = bytearray()
        self.stdout_eof = self.stderr_eof = False
        self.closed = False
        self.cleanup_observed_ms = None
        self.process = None
        self.selector = None
        try:
            self.selector = selectors.DefaultSelector()
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.PIPE, bufsize=0, start_new_session=True,
                                            close_fds=True)
            for name in ("stdin", "stdout", "stderr"):
                stream = getattr(self.process, name)
                os.set_blocking(stream.fileno(), False)
                if name != "stdin":
                    self.selector.register(stream, selectors.EVENT_READ, name)
        except (OSError, ValueError):
            self.close()
            raise ExampleError("process_start_failed") from None
        except BaseException:
            self.close()
            raise

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        self.close()

    @staticmethod
    def deadline(seconds: float) -> float:
        require(isinstance(seconds, (int, float)) and not isinstance(seconds, bool) and
                math.isfinite(seconds) and seconds > 0, "invalid_timeout")
        return time.monotonic() + seconds

    def _read(self, name: str) -> None:
        stream = getattr(self.process, name)
        used = self.stdout_bytes if name == "stdout" else self.stderr_bytes
        bound = self.limits.stdout_bytes if name == "stdout" else self.limits.stderr_bytes
        size = min(65_536, bound - used + 1)
        if name == "stdout":
            size = min(size, self.limits.frame_bytes - len(self.buffer) + 1)
        try:
            chunk = os.read(stream.fileno(), max(1, size))
        except BlockingIOError:
            return
        except OSError:
            raise ExampleError("transport_io_failed") from None
        if not chunk:
            setattr(self, name + "_eof", True)
            self.selector.unregister(stream)
            return
        if name == "stderr":
            self.stderr_bytes += len(chunk)
            require(self.stderr_bytes <= bound, "stderr_limit")
            return  # Never retain or publish private stderr.
        self.stdout_bytes += len(chunk)
        require(self.stdout_bytes <= bound, "stdout_limit")
        self.buffer.extend(chunk)
        newline = self.buffer.find(b"\n")
        require((len(self.buffer) if newline < 0 else newline) <= self.limits.frame_bytes, "frame_limit")

    def _pump(self, deadline: float, pending: memoryview | None = None) -> int:
        require(not self.closed and self.process is not None, "transport_closed")
        while True:
            remaining = deadline - time.monotonic()
            require(remaining > 0, "transport_timeout")
            try:
                ready = self.selector.select(remaining)
            except InterruptedError:
                continue
            except (OSError, ValueError):
                raise ExampleError("transport_io_failed") from None
            if ready:
                break
        written = 0
        for key, _mask in ready:
            if key.data != "stdin":
                self._read(key.data)
            elif pending is not None:
                try:
                    written = os.write(key.fd, pending[:65_536])
                except BlockingIOError:
                    continue
                except OSError:
                    raise ExampleError("transport_write_failed") from None
                require(written > 0, "transport_write_failed")
                self.stdin_bytes += written
        return written

    def write_frame(self, data: bytes, deadline: float) -> None:
        require(len(data) <= self.limits.frame_bytes and b"\n" not in data, "frame_limit")
        require(self.stdin_bytes + len(data) + 1 <= self.limits.stdin_bytes, "stdin_limit")
        require(not self.closed and self.process is not None and not self.process.stdin.closed,
                "transport_closed")
        require(not self.buffer and not self.stdout_eof, "unexpected_output")
        pending = memoryview(data + b"\n")
        try:
            self.selector.register(self.process.stdin, selectors.EVENT_WRITE, "stdin")
            while pending:
                written = self._pump(deadline, pending)
                pending = pending[written:]
        except (OSError, ValueError):
            raise ExampleError("transport_write_failed") from None
        finally:
            if not self.closed:
                try:
                    self.selector.unregister(self.process.stdin)
                except KeyError:
                    pass

    def read_frame(self, deadline: float) -> bytes:
        while True:
            require(time.monotonic() < deadline, "transport_timeout")
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                require(newline <= self.limits.frame_bytes, "frame_limit")
                frame = bytes(self.buffer[:newline])
                del self.buffer[:newline + 1]
                require(not self.buffer, "unexpected_output")
                return frame
            if self.stdout_eof:
                raise ExampleError("partial_eof" if self.buffer else "transport_eof")
            self._pump(deadline)

    def finish(self, deadline: float) -> int:
        """Require one-shot command EOF, no trailing output, and bounded exit."""
        exit_code = self._exit_code()
        while not (self.stdout_eof and self.stderr_eof and exit_code is not None):
            require(time.monotonic() < deadline, "transport_timeout")
            if self.selector.get_map():
                self._pump(deadline)
            else:
                time.sleep(min(0.01, max(0, deadline - time.monotonic())))
            require(not self.buffer, "unexpected_output")
            exit_code = self._exit_code()
        require(time.monotonic() < deadline, "transport_timeout")
        return exit_code

    def _exit_code(self) -> int | None:
        # Popen.poll()/wait() would release the PID before the final group
        # signal. WNOWAIT keeps our leader (even after exit) as a lifetime
        # guard against accidentally signaling a reused unrelated PGID.
        try:
            result = os.waitid(os.P_PID, self.process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except (ChildProcessError, OSError):
            raise ExampleError("process_lifetime_guard_lost") from None
        if result is None:
            return None
        require(result.si_pid == self.process.pid, "process_lifetime_guard_lost")
        return result.si_status if result.si_code == os.CLD_EXITED else -result.si_status

    def statistics(self) -> dict:
        return {"stdinBytes": self.stdin_bytes, "stdoutBytes": self.stdout_bytes, "stderrBytes": self.stderr_bytes,
                "includesNdjsonNewlines": True, "stderrRetained": False,
                "byteMeasurementScope": "transport reads/writes before cleanup; not total child emissions",
                "frameBytesLimitExcludingNewline": self.limits.frame_bytes,
                "sessionStdoutBytesLimit": self.limits.stdout_bytes,
                "sessionStderrBytesLimit": self.limits.stderr_bytes,
                "sessionStdinBytesLimit": self.limits.stdin_bytes}

    def cleanup_statistics(self) -> dict:
        require(self.closed and self.cleanup_observed_ms is not None, "cleanup_not_observed")
        return {"observedMillis": self.cleanup_observed_ms,
                "termToLastKillGraceSeconds": self.limits.cleanup_seconds,
                "forcedHelperLossGuaranteed": False, "escapedDescendantsSupported": False}

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        started = time.monotonic_ns()
        failed = False
        process = self.process
        try:
            if process is not None:
                if process.stdin is not None:
                    try:
                        process.stdin.close()
                    except OSError:
                        failed = True
                # A leader can exit while children still own pipes. Signal the owned
                # group even then; killing only the leader is insufficient.
                self._exit_code()  # Refuse group signals if an external reaper stole the guard.
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                except OSError:
                    failed = True
                deadline = time.monotonic() + self.limits.cleanup_seconds
                # An exited broker can leave its cooperative bundled helper
                # reacting to EOF/TERM. Retain the leader and allow the FULL
                # grace, never infer group emptiness or helper ACK from exit.
                while time.monotonic() < deadline:
                    self._exit_code()
                    time.sleep(min(0.01, max(0, deadline - time.monotonic())))
                # No poll()/wait() before this LAST group signal, including
                # success/EOF paths where the leader already exited.
                self._exit_code()
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except OSError:
                    failed = True
                try:
                    process.wait(timeout=self.limits.cleanup_seconds)
                except (subprocess.TimeoutExpired, OSError):
                    failed = True
        finally:
            if self.selector is not None:
                self.selector.close()
            if process is not None:
                for name in ("stdin", "stdout", "stderr"):
                    stream = getattr(process, name)
                    if stream is not None:
                        try:
                            stream.close()
                        except OSError:
                            failed = True
            self.cleanup_observed_ms = (time.monotonic_ns() - started) / 1e6
        require(not failed, "cleanup_failed")


class NdjsonClient:
    """Profile acknowledgment is per response, never a cached connection handshake."""

    def __init__(self, transport: BoundedProcess):
        self.transport = transport
        self.counter = 0

    def call(self, method: str, params: dict, *, request_id: str | None = None,
             deadline: float | None = None) -> dict:
        self.counter += 1
        identifier = str(self.counter) if request_id is None else request_id
        require(isinstance(identifier, str) and 1 <= len(identifier) <= 256, "invalid_request_id")
        try:
            require(len(identifier.encode("utf-16-le", errors="strict")) // 2 <= 256, "invalid_request_id")
        except UnicodeError:
            raise ExampleError("invalid_request_id") from None
        require(isinstance(method, str) and re.fullmatch(r"[a-z][a-zA-Z0-9_.]{0,127}", method) is not None and
                isinstance(params, dict), "invalid_request")
        deadline = deadline if deadline is not None else self.transport.deadline(RPC_TIMEOUT_SECONDS)
        data = encode_json({"id": identifier, "method": method, "params": params,
                            "findingWireProfile": FINDING_WIRE_PROFILE}, self.transport.limits.frame_bytes)
        self.transport.write_frame(data, deadline)
        response = parse_json(self.transport.read_frame(deadline), self.transport.limits.frame_bytes)
        require(response.get("contractVersion") == CONTRACT, "wrong_contract")
        require(type(response.get("id")) is str and response["id"] == identifier, "wrong_response_id")
        if "error" in response:
            raise ExampleError("rpc_rejected")  # Do not echo even the server's error code/message.
        # The peer may already have performed a mutating request. A bad success
        # acknowledgment leaves its outcome uncertain: do not expose its result,
        # infer rollback, retry, or replay the request automatically.
        profile = response.get("findingWireProfile")
        require(type(profile) is str and profile == FINDING_WIRE_PROFILE,
                "finding_wire_profile_outcome_uncertain")
        require(set(response) == {"contractVersion", "id", "result", "findingWireProfile"} and
                isinstance(response["result"], dict), "invalid_response")
        require(time.monotonic() < deadline, "transport_timeout")
        return response["result"]


def run_json_command(command: list[str], *, timeout: float = OWNER_TIMEOUT_SECONDS,
                     limits: PipeLimits | None = None, contract_required: bool = True,
                     deadline: float | None = None) -> tuple[dict, dict]:
    deadline = BoundedProcess.deadline(timeout) if deadline is None else deadline
    require(type(deadline) in (int, float) and math.isfinite(deadline) and deadline > time.monotonic(), "invalid_timeout")
    with BoundedProcess(command, limits) as transport:
        transport.process.stdin.close()
        response = parse_json(transport.read_frame(deadline), transport.limits.frame_bytes)
        require(transport.finish(deadline) == 0, "owner_command_failed")
        require("error" not in response and (not contract_required or response.get("contractVersion") == CONTRACT),
                "owner_command_rejected")
        statistics = transport.statistics()
    statistics["cleanup"] = transport.cleanup_statistics()
    return response, statistics


def _directory_fd(path: Path) -> int:
    """Open all absolute components without following links or writable ancestors."""
    require(os.name == "posix" and hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY"), "posix_required")
    require(path.is_absolute() and ".." not in path.parts, "unsafe_output_path")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open("/", flags)
    try:
        for part in path.parts[1:]:
            current = os.fstat(fd)
            require(not (current.st_mode & 0o022) or
                    bool(current.st_mode & stat.S_ISVTX) and current.st_uid in {0, os.geteuid()},
                    "unsafe_output_parent")
            next_fd = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        current = os.fstat(fd)
        require(not (current.st_mode & 0o022) or
                bool(current.st_mode & stat.S_ISVTX) and current.st_uid in {0, os.geteuid()},
                "unsafe_output_parent")
        return fd
    except BaseException:
        os.close(fd)
        raise


class PrivateDirectory:
    """New owner-private root; anchored, bounded files, no overwrite or link reads."""

    def __init__(self, path: Path):
        self.path = path.absolute()
        self.fd = None
        parent_fd = None
        try:
            require(self.path.name not in {"", ".", ".."}, "unsafe_output_path")
            parent_fd = _directory_fd(self.path.parent)
            os.mkdir(self.path.name, 0o700, dir_fd=parent_fd)
            self.fd = os.open(self.path.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                              dir_fd=parent_fd)
            self.identity = os.fstat(self.fd)
            require(self.identity.st_uid == os.geteuid() and not (self.identity.st_mode & 0o077),
                    "private_permissions")
        except OSError:
            self.close()
            raise ExampleError("new_private_output_required") from None
        except BaseException:
            self.close()
            raise
        finally:
            if parent_fd is not None:
                os.close(parent_fd)

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        self.close()

    def close(self) -> None:
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None

    def verify(self) -> None:
        require(self.fd is not None, "private_root_closed")
        fd = None
        try:
            fd = _directory_fd(self.path)
            current = os.fstat(fd)
            require((current.st_dev, current.st_ino) == (self.identity.st_dev, self.identity.st_ino) and
                    current.st_uid == os.geteuid() and not (current.st_mode & 0o077), "private_root_changed")
        except OSError:
            raise ExampleError("private_root_changed") from None
        finally:
            if fd is not None:
                os.close(fd)

    def file_path(self, name: str, *, must_be_new: bool = False) -> Path:
        require(re.fullmatch(r"[a-z][a-z0-9-]*\.json", name) is not None, "unsafe_private_filename")
        self.verify()
        if must_be_new:
            try:
                os.stat(name, dir_fd=self.fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            except OSError:
                raise ExampleError("private_file_failed") from None
            else:
                raise ExampleError("private_file_exists")
        return self.path / name

    def write_json(self, name: str, value: dict) -> None:
        self.file_path(name, must_be_new=True)
        data = encode_json(value, MAX_PRIVATE_BYTES - 1) + b"\n"
        try:
            fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                         0o600, dir_fd=self.fd)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
        except OSError:
            raise ExampleError("private_file_failed") from None

    def read_json(self, name: str) -> dict:
        self.file_path(name)
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=self.fd)
            with os.fdopen(fd, "rb") as stream:
                info = os.fstat(stream.fileno())
                require(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and info.st_nlink == 1 and
                        not (info.st_mode & 0o077), "unsafe_private_file")
                require(info.st_size <= MAX_PRIVATE_BYTES, "private_file_limit")
                data = stream.read(MAX_PRIVATE_BYTES + 1)
                require(len(data) <= MAX_PRIVATE_BYTES, "private_file_limit")
        except OSError:
            raise ExampleError("private_file_failed") from None
        return parse_json(data, MAX_PRIVATE_BYTES)


def provider_inspection(value: dict) -> dict:
    require(isinstance(value, dict) and value.get("contractVersion") == CONTRACT and
            value.get("releaseQualified") is False and value.get("doesNotEstablishTargetExecution") is True,
            "invalid_provider_inspection")
    containment = value.get("processContainment")
    require(isinstance(containment, dict) and containment.get("state") == "available" and
            containment.get("bundledBytesVerified") is True, "process_containment_unavailable")
    identity = containment.get("identity")
    target = containment.get("target")
    require(isinstance(target, str) and target in CONTAINMENT_TARGETS and
            containment.get("executionVerified") is False and containment.get("releaseQualified") is False and
            isinstance(identity, dict) and identity.get("protocolVersion") == CONTAINMENT_PROTOCOL and
            identity.get("target") == target and
            all(isinstance(identity.get(field), str) and DIGEST.fullmatch(identity[field]) is not None
                for field in ("sourceDigest", "binaryDigest")), "invalid_provider_inspection")
    compiler = value.get("compiler")
    require(isinstance(compiler, dict), "invalid_provider_inspection")
    require(compiler.get("state") == "toolchain_bound", "compiler_unavailable")
    require(compiler.get("javaHomeSelection") == "explicit_owner_selection" and
            compiler.get("runtimeRequirement") == "complete_link_free_jdk21_image" and
            compiler.get("requiresExplicitExecutionAuthorization") is True and
            isinstance(compiler.get("toolchainDigest"), str) and DIGEST.fullmatch(compiler["toolchainDigest"]) is not None,
            "invalid_provider_inspection")
    return {"state": "toolchain_bound", "toolchainDigest": compiler["toolchainDigest"],
            "javaHomeSelection": "explicit_owner_selection", "runtimeRequirement": "complete_link_free_jdk21_image",
            "processContainment": {"state": "available", "target": target, "bundledBytesVerified": True,
                                   "executionVerified": False, "releaseQualified": False,
                                   "identity": {field: identity[field] for field in
                                                ("protocolVersion", "target", "sourceDigest", "binaryDigest")}},
            "inspectionEstablishesExecution": False}


def scope_for(client_id: str) -> dict:
    return {"module": ":" + client_id, "sourceSet": "main", "target": "jvm", "compilation": "main"}


def validate_prepared(client: dict, name: str, toolchain_digest: str, *, source: str | None = None,
                      patch_id: str = "patch-1", idempotency_key: str = "request-1",
                      execution_authorized: bool = True) -> None:
    require(type(execution_authorized) is bool and isinstance(client, dict) and isinstance(client.get("prepared"), dict) and
            isinstance(client.get("registration"), dict), "invalid_prepared_binding")
    prepared, registration = client["prepared"], client["registration"]
    require(prepared.get("contractVersion") == registration.get("contractVersion") == CONTRACT,
            "invalid_prepared_binding")
    binding = registration.get("binding")
    require(isinstance(binding, dict) and prepared.get("binding") == binding and binding.get("clientId") == name and
            binding.get("workspaceId") == "example-project" and binding.get("worktreeId") == "shared-overlay" and
            binding.get("sessionId") == "session-" + name and binding.get("lineageId") == "task-" + name and
            type(binding.get("generation")) is int and binding["generation"] == 1, "invalid_prepared_binding")
    credential = registration.get("client")
    require(isinstance(credential, dict) and credential.get("clientId") == name and
            isinstance(credential.get("token"), str) and re.fullmatch(r"[A-Za-z0-9_-]{43}", credential["token"]) is not None,
            "invalid_prepared_binding")
    require(prepared.get("patchId") == patch_id and prepared.get("idempotencyKey") == idempotency_key and
            prepared.get("executionAuthorized") is execution_authorized and
            prepared.get("coverageScope") == "explicit-rule-subset-not-full-release", "invalid_prepared_binding")
    input_value = prepared.get("input")
    require(isinstance(input_value, dict), "invalid_prepared_binding")
    context, snapshot = input_value.get("context"), input_value.get("snapshot")
    require(isinstance(context, dict) and isinstance(snapshot, dict) and input_value.get("requiredRuleIds") == [RULE] and
            context.get("scope") == scope_for(name) and context.get("fullProject") is False and
            context.get("compilerVersion") == "2.4.21" and context.get("toolchainDigest") == toolchain_digest and
            context.get("compilationFiles") == [name + "/Main.kt"], "invalid_prepared_binding")
    require(isinstance(snapshot.get("snapshotId"), str) and DIGEST.fullmatch(snapshot["snapshotId"]) is not None and
            snapshot.get("ownerId") == binding.get("ownerId") and snapshot.get("workspaceId") == binding["workspaceId"] and
            snapshot.get("worktreeId") == binding["worktreeId"] and snapshot.get("captureKind") == "immutable_overlay" and
            snapshot.get("unknownInputs") == [], "invalid_prepared_binding")
    files = snapshot.get("files")
    require(isinstance(files, list), "invalid_prepared_binding")
    own_files = [row for row in files if isinstance(row, dict) and row.get("path") == name + "/Main.kt"]
    source_bytes = (SOURCES[name] if source is None else source).encode("utf-8")
    require(len(own_files) == 1 and own_files[0].get("digest") == "sha256:" + hashlib.sha256(source_bytes).hexdigest() and
            type(own_files[0].get("size")) is int and own_files[0]["size"] == len(source_bytes), "invalid_prepared_binding")


def validate_status(status_value: dict, client: dict, prior: dict | None = None, *, event: bool = False) -> None:
    prepared = client["prepared"]
    require(isinstance(status_value, dict) and status_value.get("contractVersion") == CONTRACT and
            status_value.get("clientId") == prepared["binding"]["clientId"] and
            status_value.get("patchId") == prepared["patchId"] and
            status_value.get("sourceSnapshotId") == prepared["input"]["snapshot"]["snapshotId"] and
            isinstance(status_value.get("reviewId"), str) and UUID.fullmatch(status_value["reviewId"]) is not None and
            type(status_value.get("sequence")) is int and status_value["sequence"] >= 1 and
            status_value.get("state") in TERMINAL_STATES | {"queued", "running"} | ({"accepted"} if event else set()) and
            type(status_value.get("cancelled")) is bool and isinstance(status_value.get("reasons"), list) and
            all(isinstance(reason, str) for reason in status_value["reasons"]), "invalid_status_binding")
    if prior is not None:
        require(status_value["reviewId"] == prior["reviewId"] and status_value["sequence"] == prior["sequence"],
                "invalid_status_binding")
    result_value = status_value.get("result")
    require(result_value is None or isinstance(result_value, dict), "invalid_status_result")


def matching_findings(result_value: dict | None, client: dict) -> int:
    if result_value is None:
        return 0
    findings = result_value.get("findings")
    require(isinstance(findings, list), "invalid_findings")
    name = client["prepared"]["binding"]["clientId"]
    path = name + "/Main.kt"
    expected_digest = "sha256:" + hashlib.sha256(SOURCES[name].encode("utf-8")).hexdigest()
    count = 0
    for finding in findings:
        require(isinstance(finding, dict), "invalid_findings")
        if finding.get("ruleId") != RULE:
            continue
        location = finding.get("location")
        require(finding.get("providerId") == COMPILER and finding.get("upstreamId") == UPSTREAM and
                finding.get("context") == scope_for(name) and isinstance(location, dict) and
                location.get("path") == path and location.get("fileDigest") == expected_digest, "invalid_finding_binding")
        coordinates = [location.get(field) for field in ("line", "column", "endLine", "endColumn")]
        require(all(type(value) is int and 0 <= value <= 2_147_483_647 for value in coordinates) and
                coordinates[0] >= 1 and coordinates[2] >= 1 and
                (coordinates[0], coordinates[1]) <= (coordinates[2], coordinates[3]), "invalid_finding_binding")
        lines = SOURCES[name].splitlines()
        require(coordinates[0] <= len(lines) and coordinates[2] <= len(lines) and
                coordinates[1] <= len(lines[coordinates[0] - 1].encode("utf-16-le")) // 2 and
                coordinates[3] <= len(lines[coordinates[2] - 1].encode("utf-16-le")) // 2, "invalid_finding_binding")
        count += 1
    return count


def validate_event_page(page: dict, item: dict) -> list[dict]:
    require(isinstance(page, dict) and page.get("contractVersion") == CONTRACT and
            isinstance(page.get("events"), list) and len(page["events"]) <= 50 and
            isinstance(page.get("cursor"), str) and 1 <= len(page["cursor"]) <= 8192 and
            type(page.get("hasMore")) is bool, "invalid_event_page")
    require(not page["hasMore"] or bool(page["events"]), "invalid_event_page")
    require(not page["events"] or page["cursor"] != item["cursor"], "invalid_event_cursor")
    payloads = []
    last_sequence = item["event_sequence"]
    binding = item["client"]["prepared"]["binding"]
    expected = item["status"]
    for event_value in page["events"]:
        require(isinstance(event_value, dict) and event_value.get("contractVersion") == CONTRACT and
                event_value.get("clientId") == binding["clientId"] and event_value.get("generation") == binding["generation"] and
                type(event_value.get("generation")) is int and event_value.get("reviewId") == expected["reviewId"] and
                event_value.get("patchId") == expected["patchId"] and
                event_value.get("sourceSnapshotId") == expected["sourceSnapshotId"] and
                isinstance(event_value.get("eventId"), str) and UUID.fullmatch(event_value["eventId"]) is not None and
                type(event_value.get("sequence")) is int and event_value["sequence"] > last_sequence and
                event_value.get("kind") in {"accepted", "progress", "result", "cancelled"} and
                event_value.get("deliveryRequired") is True and isinstance(event_value.get("payload"), str), "invalid_event_binding")
        payload_bytes = event_value["payload"].encode("utf-8", errors="strict")
        require(event_value.get("payloadDigest") == "sha256:" + hashlib.sha256(payload_bytes).hexdigest(), "invalid_event_digest")
        payload = parse_json(payload_bytes)
        validate_status(payload, item["client"], expected, event=True)
        matching_findings(payload.get("result"), item["client"])
        payloads.append(payload)
        last_sequence = event_value["sequence"]
    item["event_sequence"] = last_sequence
    item["cursor"] = page["cursor"]  # Fetch progress only. This is NOT an ACK/observation receipt.
    return payloads


def analysis_metrics(result_value: dict) -> dict:
    metrics = result_value.get("metrics")
    require(isinstance(metrics, dict), "invalid_analysis_metrics")
    safe = {}
    for field in ("elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"):
        value = metrics.get(field)
        require(value is None and field in {"cpuMillis", "peakRssBytes"} or
                type(value) is int and 0 <= value <= 9_223_372_036_854_775_807, "invalid_analysis_metrics")
        safe[field] = value
    return safe


def verify_selected_execution(item: dict) -> None:
    result_value = item["status"].get("result")
    require(item["status"]["state"] in TERMINAL_STATES and not item["status"]["cancelled"] and
            isinstance(result_value, dict), "terminal_analysis_missing")
    count = matching_findings(result_value, item["client"])
    name = item["status"]["clientId"]
    require(count > 0 if name == "agent-a" else not result_value["findings"], "expected_compiler_result_missing")
    coverage = result_value.get("coverage")
    require(isinstance(coverage, list), "selected_execution_missing")
    selected = [row for row in coverage if isinstance(row, dict) and row.get("ruleId") == RULE and
                row.get("context") == scope_for(name) and row.get("required") is True]
    # This deliberately incomplete compilation model cannot earn passing
    # coverage/qualification certificates. The broken source fails the actual
    # compiler task; the independent source runs successfully but its required
    # coverage remains unknown. Executed provenance is NOT passing coverage.
    require(len(selected) == 1 and selected[0].get("state") == ("failed" if count else "unknown") and
            selected[0].get("provenance") == "executed" and selected[0].get("certificateId") is None and
            selected[0].get("qualified") is False, "selected_execution_missing")
    analysis_metrics(result_value)
    require(isinstance(result_value.get("reasons"), list) and
            all(isinstance(reason, str) for reason in result_value["reasons"]), "invalid_analysis_result")


def note_observation(item: dict, status_value: dict, observed_ns: int, via: str, *, update_status: bool = False) -> None:
    validate_status(status_value, item["client"], item["status"])
    result_value = status_value.get("result")
    elapsed = (observed_ns - item["submitted_ns"]) / 1e6
    if matching_findings(result_value, item["client"]) and item["first_useful_ms"] is None:
        item["first_useful_ms"], item["first_useful_via"] = elapsed, via
    if status_value["state"] in TERMINAL_STATES and result_value is not None and item["terminal_ms"] is None:
        item["terminal_ms"], item["terminal_via"] = elapsed, via
    if update_status:
        item["status"] = status_value


def public_observation(item: dict) -> dict:
    verify_selected_execution(item)
    status_value, result_value = item["status"], item["status"]["result"]
    return {"clientId": status_value["clientId"], "reviewId": status_value["reviewId"],
            "sourceSnapshotId": status_value["sourceSnapshotId"], "state": status_value["state"],
            "requestAcknowledgmentObservedMillis": item["ack_ms"],
            "duplicateAcknowledgmentObservedMillis": item["duplicate_ack_ms"], "duplicateReusedReviewId": True,
            "firstUsefulFindingObservedMillis": item["first_useful_ms"], "firstUsefulFindingObservedVia": item["first_useful_via"],
            "terminalResultObservedMillis": item["terminal_ms"], "terminalResultObservedVia": item["terminal_via"],
            "observedLatencyOrigin": "application_monotonic_clock_from_first_submit_request",
            "noFindingExpected": status_value["clientId"] == "agent-b", "findingCount": len(result_value["findings"]),
            "selectedRuleExecutionObserved": True, "requiredAnalysisComplete": False,
            "selectedRuleCoverageState": "failed" if status_value["clientId"] == "agent-a" else "unknown",
            "selectedRulePassingCertificateObserved": False,
            "analysisReportedMetrics": analysis_metrics(result_value),
            "analysisMetricsScope": "broker analysis result; not independent whole-broker or process-tree measurements",
            "reasonCounts": {"review": len(status_value["reasons"]), "analysis": len(result_value["reasons"])}}


class Controller:
    def __init__(self, home: Path, root: PrivateDirectory, compiler_java_home: Path):
        # Deliberately light/read-only: CLI/compiler runtime owns JDK identity policy.
        compiler_java_home = compiler_java_home.absolute()
        require(compiler_java_home.is_dir() and (compiler_java_home / "bin" / "java").is_file(), "compiler_java_home_missing")
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin" / "java") if java_home else "java"
        self.command = [java, "-Xmx512m", "-cp", str(home / "lib" / "*"), "dev.ktchecker.cli.MainKt",
                        "--state", str(root.path), "--compiler-dir", str(home / "providers" / "compiler"),
                        "--compiler-java-home", str(compiler_java_home)]
        self.root = root
        self.inspection = None
        self.preparation_ms = None
        self.owner_transport = []

    def call(self, *args: str) -> dict:
        self.root.verify()
        # SnapshotCommit is an owner DTO without a contractVersion field;
        # prepare() validates its snapshot/head binding instead. RPC envelopes
        # and all other owner responses still require the preview contract.
        response, statistics = run_json_command(self.command + ["--workers", "0", *args],
                                                contract_required=args[0] != "overlay")
        self.root.verify()
        self.owner_transport.append(statistics)
        return response

    def prepare(self, *, authorize_execution: bool) -> list[dict]:
        require(type(authorize_execution) is bool, "invalid_execution_authorization")
        started = time.monotonic_ns()
        # Initialize CLI state while the NEW root is still empty. A foreign
        # pre-populated directory correctly fails the CLI's state initializer.
        self.inspection = provider_inspection(self.call("providers"))
        clients = []
        base_snapshot, base_revision = None, 0
        for name, source in SOURCES.items():
            credentials = self.root.file_path(name + "-credentials.json", must_be_new=True)
            self.call("register", "--workspace", "example-project", "--worktree", "shared-overlay",
                      "--client", name, "--session", "session-" + name, "--lineage", "task-" + name,
                      "--out", str(credentials))
            overlay = {"workspaceId": "example-project", "worktreeId": "shared-overlay", "clientId": name,
                       "sessionId": "session-" + name, "patchId": "patch-1", "baseSnapshotId": base_snapshot,
                       "expectedHeadRevision": base_revision,
                       "changes": [{"operation": "put", "path": name + "/Main.kt", "content": source}]}
            overlay_name = name + "-overlay.json"
            self.root.write_json(overlay_name, overlay)
            committed = self.call("overlay", "--request", str(self.root.file_path(overlay_name)))
            manifest = committed.get("manifest")
            require(isinstance(manifest, dict) and isinstance(manifest.get("snapshotId"), str) and
                    DIGEST.fullmatch(manifest["snapshotId"]) is not None and type(committed.get("headRevision")) is int and
                    committed["headRevision"] == base_revision + 1, "invalid_overlay_result")
            base_snapshot, base_revision = manifest["snapshotId"], committed["headRevision"]
            compilation = {"clientId": name, "snapshotId": base_snapshot, "patchId": "patch-1",
                           "idempotencyKey": "request-1", "compilationFiles": [name + "/Main.kt"],
                           "compilerVersion": "2.4.21", "requiredRuleIds": [RULE], "scope": scope_for(name)}
            spec_name, prepared_name = name + "-compilation.json", name + "-prepared.json"
            self.root.write_json(spec_name, compilation)
            prepared_path = self.root.file_path(prepared_name, must_be_new=True)
            # Preparation-only is not a deferred execution grant. Never repair or
            # upgrade a non-authorized record while reading it back.
            authorization = ["--authorize-execution"] if authorize_execution else []
            response = self.call("prepare", "--request", str(self.root.file_path(spec_name)),
                                 *authorization, "--out", str(prepared_path))
            require(provider_inspection(response.get("installedProviders")) == self.inspection,
                    "compiler_inspection_changed")
            client = {"prepared": self.root.read_json(prepared_name),
                      "registration": self.root.read_json(name + "-credentials.json")}
            validate_prepared(client, name, self.inspection["toolchainDigest"],
                              execution_authorized=authorize_execution)
            require(client["prepared"]["input"]["snapshot"]["snapshotId"] == base_snapshot, "invalid_prepared_binding")
            clients.append(client)
        self.preparation_ms = (time.monotonic_ns() - started) / 1e6
        return clients

    def review(self, clients: list[dict]) -> dict:
        require(self.inspection is not None and len(clients) == 2, "owner_preparation_required")
        for client, name in zip(clients, SOURCES):
            validate_prepared(client, name, self.inspection["toolchainDigest"], execution_authorized=True)
        self.root.verify()
        started = time.monotonic_ns()
        with BoundedProcess(self.command + ["rpc", "--workers", "2"]) as transport:
            spawned = time.monotonic_ns()
            rpc = NdjsonClient(transport)
            doctor_started = time.monotonic_ns()
            doctor = rpc.call("doctor", {})
            ready = time.monotonic_ns()
            require(doctor.get("contractVersion") == CONTRACT and doctor.get("command") == "doctor" and
                    doctor.get("installedProvidersInspected") is True and
                    provider_inspection(doctor.get("installedProviders")) == self.inspection, "broker_readiness_failed")
            accepted = []
            for client in clients:
                prepared, credential = client["prepared"], client["registration"]["client"]
                submitted = time.monotonic_ns()
                params = {"credential": credential, "patchId": prepared["patchId"],
                          "idempotencyKey": prepared["idempotencyKey"], "input": prepared["input"]}
                status_value = rpc.call("review.submit", params)
                acknowledged = time.monotonic_ns()
                validate_status(status_value, client)
                item = {"client": client, "status": status_value, "submitted_ns": submitted,
                        "ack_ms": (acknowledged - submitted) / 1e6, "duplicate_ack_ms": None,
                        "first_useful_ms": None, "first_useful_via": None, "terminal_ms": None, "terminal_via": None,
                        "cursor": None, "event_sequence": 0}
                note_observation(item, status_value, acknowledged, "review.submit")
                duplicate_started = time.monotonic_ns()
                duplicate = rpc.call("review.submit", params)
                duplicate_observed = time.monotonic_ns()
                validate_status(duplicate, client, status_value)
                item["duplicate_ack_ms"] = (duplicate_observed - duplicate_started) / 1e6
                note_observation(item, duplicate, duplicate_observed, "duplicate review.submit", update_status=True)
                accepted.append(item)
            deadline = time.monotonic() + REVIEW_TIMEOUT_SECONDS
            while any(item["status"]["state"] not in TERMINAL_STATES for item in accepted):
                require(time.monotonic() < deadline, "review_timeout")
                for item in accepted:
                    credential = item["client"]["registration"]["client"]
                    call_deadline = min(deadline, time.monotonic() + RPC_TIMEOUT_SECONDS)
                    status_value = rpc.call("review.status", {"credential": credential, "reviewId": item["status"]["reviewId"]},
                                            deadline=call_deadline)
                    note_observation(item, status_value, time.monotonic_ns(), "review.status", update_status=True)
                    params = {"credential": credential, "limit": 50}
                    if item["cursor"] is not None:
                        params["cursor"] = item["cursor"]
                    page = rpc.call("review.events", params, deadline=min(deadline, time.monotonic() + RPC_TIMEOUT_SECONDS))
                    observed = time.monotonic_ns()
                    for payload in validate_event_page(page, item):
                        # Accepted events have no useful result and are not review.status states.
                        if payload["state"] != "accepted":
                            note_observation(item, payload, observed, "review.events")
                if any(item["status"]["state"] not in TERMINAL_STATES for item in accepted):
                    time.sleep(min(0.025, max(0, deadline - time.monotonic())))
            observations = [public_observation(item) for item in accepted]
            statistics = transport.statistics()
        # Write/print only AFTER process-group cleanup succeeded, never from a partial failure.
        report = {"contractVersion": CONTRACT, "releaseQualified": False,
                  "scenario": "two immutable compilation snapshots, independent clients, one persistent broker",
                  "requiredRuleIds": [RULE], "requiredScope": "explicit one-rule partial-project preview per client",
                  "fullProjectCoverageEstablished": False, "targetQualificationEstablished": False,
                  "contextDelivery": {"state": "not_attempted", "reason": "application poller is not a model host"},
                  "compilerInspection": self.inspection,
                  "compilerWarmth": "not_measured; persistent broker is not proof of a warm compiler",
                  "ownerPreparationObservedMillis": self.preparation_ms,
                  "ownerPreparationTimingScope": "owner commands and teardown; not compiler or useful-feedback latency",
                  "brokerStartup": {"spawnObservedMillis": (spawned - started) / 1e6,
                                    "doctorRoundTripObservedMillis": (ready - doctor_started) / 1e6,
                                    "readyObservedMillisFromSpawnStart": (ready - started) / 1e6,
                                    "establishesCompilerExecution": False},
                  "observations": observations, "brokerTransport": statistics,
                  "ownerCommandTransportTotals": {field: sum(value[field] for value in self.owner_transport)
                                                  for field in ("stdinBytes", "stdoutBytes", "stderrBytes")},
                  "wholeBrokerProcessTreeResources": {"state": "not_measured", "cpuMillis": None, "peakRssBytes": None},
                  "cleanup": dict(transport.cleanup_statistics(),
                                  state="owned_group_signaled_leader_reaped_pipes_closed",
                                  ownerCommandObservedMillisTotal=sum(value["cleanup"]["observedMillis"]
                                                                     for value in self.owner_transport),
                                  scope="teardown; excluded from submit, finding and terminal observation timings")}
        self.root.write_json("observations.json", report)
        return report


def read_benchmark_scenario(path: Path, selected: str) -> benchmark.Scenario:
    # A bounded data input, never executable scenario code or a model/host receipt.
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            require(stat.S_ISREG(info.st_mode) and info.st_size <= 65_536, "benchmark_scenarios_invalid")
            data = stream.read(65_537)
        return benchmark.select_scenario(parse_json(data, 65_536), selected)
    except OSError:
        raise ExampleError("benchmark_scenarios_unavailable") from None


class BenchmarkController(Controller):
    """The same owner commands and bounded RPC transport, with retained trial accounting.

    Warm scenarios retain ONE broker across real new capture/prepare/submit rounds.
    Fresh scenarios start a NEW broker per scheduled batch, never erase state or
    cancel old accepted obligations. Neither schedule proves warm compiler state.
    """

    def __init__(self, home: Path, root: PrivateDirectory, compiler_java_home: Path, scenario: benchmark.Scenario):
        super().__init__(home, root, compiler_java_home)
        self.scenario = scenario
        self.started_ns = time.monotonic_ns()
        self.run_deadline_ns = self.started_ns + scenario.runTimeoutSeconds * 1_000_000_000
        self.ledger = benchmark.Ledger(scenario, self.started_ns, str(uuid.uuid4()), RULE, COMPILER, UPSTREAM, scope_for)
        self.active = []
        self.clients = {}
        self.base_snapshot, self.base_revision = None, 0
        self.cursors = {name: {"cursor": None, "sequence": 0, "pages": 0} for name in SOURCES}
        self.seen_event_ids = set()
        self.transport = self.rpc = None
        self.sessions = []
        self.session_startup = None
        self.failure = None

    def deadline_ns(self) -> int:
        # One original run bound plus each original completed-patch bound. No
        # polling, duplicate submission or owner subprocess resets these clocks.
        # Terminal active trials remain bounded through event draining; a newly
        # unsettled historical review retains ITS original patch deadline too.
        return min([self.run_deadline_ns] + [trial.times["patchComplete"] + self.scenario.trialTimeoutSeconds * 1_000_000_000
                                           for trial in self.ledger.trials.values()
                                           if trial in self.active or trial.review_id is not None and not trial.terminal])

    def deadline(self, cap: float) -> float:
        now = time.monotonic_ns()
        limit = min(self.deadline_ns(), now + int(cap * 1_000_000_000))
        require(now < limit, "benchmark_deadline")
        return limit / 1e9

    def call(self, *args: str) -> dict:
        self.root.verify()
        response, statistics = run_json_command(self.command + ["--workers", "0", *args],
                                                contract_required=args[0] != "overlay",
                                                deadline=self.deadline(OWNER_TIMEOUT_SECONDS))
        self.root.verify()
        self.owner_transport.append(statistics)
        require(time.monotonic_ns() < self.deadline_ns(), "benchmark_deadline")
        return response

    def setup(self) -> None:
        self.inspection = provider_inspection(self.call("providers"))
        # CLI must initialize the empty private root before any scenario file.
        self.root.write_json("benchmark-plan.json", {"schema": benchmark.SCHEMA,
                             "scenario": benchmark.asdict(self.scenario),
                             "plannedTrials": [{"trialId": patch.trial_id, "phase": patch.phase, "clientId": patch.client_id,
                                                "sourceDigest": patch.source_digest, "expected": patch.expected}
                                               for patch in self.ledger.plan],
                             "releaseQualified": False, "executionObserved": False})
        for name in SOURCES:
            credentials = self.root.file_path(name + "-credentials.json", must_be_new=True)
            self.call("register", "--workspace", "example-project", "--worktree", "shared-overlay",
                      "--client", name, "--session", "session-" + name, "--lineage", "task-" + name,
                      "--out", str(credentials))
            self.clients[name] = self.root.read_json(name + "-credentials.json")
        self.preparation_ms = (time.monotonic_ns() - self.started_ns) / 1e6

    def prepare_trial(self, trial: benchmark.Trial) -> None:
        patch = trial.patch
        previous = self.base_snapshot
        overlay = {"workspaceId": "example-project", "worktreeId": "shared-overlay", "clientId": patch.client_id,
                   "sessionId": "session-" + patch.client_id, "patchId": patch.patch_id,
                   "baseSnapshotId": previous, "expectedHeadRevision": self.base_revision,
                   "changes": [{"operation": "put", "path": patch.path, "content": patch.source}]}
        prefix = "benchmark-" + patch.trial_id
        trial.stamp("captureStarted", time.monotonic_ns())
        self.root.write_json(prefix + "-overlay.json", overlay)
        committed = self.call("overlay", "--request", str(self.root.file_path(prefix + "-overlay.json")))
        manifest = committed.get("manifest")
        require(isinstance(manifest, dict) and benchmark.is_digest(manifest.get("snapshotId")) and
                type(committed.get("headRevision")) is int and committed["headRevision"] == self.base_revision + 1,
                "invalid_overlay_result")
        self.base_snapshot, self.base_revision = manifest["snapshotId"], committed["headRevision"]
        trial.stamp("captureCommitted", time.monotonic_ns())
        compilation = {"clientId": patch.client_id, "snapshotId": self.base_snapshot, "patchId": patch.patch_id,
                       "idempotencyKey": patch.idempotency_key, "compilationFiles": [patch.path],
                       "compilerVersion": "2.4.21", "requiredRuleIds": [RULE], "scope": scope_for(patch.client_id)}
        self.root.write_json(prefix + "-compilation.json", compilation)
        prepared_name = prefix + "-prepared.json"
        response = self.call("prepare", "--request", str(self.root.file_path(prefix + "-compilation.json")),
                             "--authorize-execution", "--out", str(self.root.file_path(prepared_name, must_be_new=True)))
        require(provider_inspection(response.get("installedProviders")) == self.inspection, "compiler_inspection_changed")
        client = {"prepared": self.root.read_json(prepared_name), "registration": self.clients[patch.client_id]}
        validate_prepared(client, patch.client_id, self.inspection["toolchainDigest"], source=patch.source,
                          patch_id=patch.patch_id, idempotency_key=patch.idempotency_key, execution_authorized=True)
        value = client["prepared"]["input"]
        require(value["snapshot"]["snapshotId"] == self.base_snapshot and value.get("baselineSnapshotId") == previous and
                value["snapshot"].get("parentSnapshotId") == previous, "invalid_prepared_binding")
        trial.bind(client, time.monotonic_ns())

    def start_broker(self) -> None:
        require(self.transport is None and self.rpc is None, "benchmark_broker_already_started")
        started = time.monotonic_ns()
        self.deadline(RPC_TIMEOUT_SECONDS)
        self.transport = BoundedProcess(self.command + ["rpc", "--workers", str(self.scenario.workerCount)])
        spawned = time.monotonic_ns()
        self.rpc = NdjsonClient(self.transport)
        self.session_startup = {"session": len(self.sessions) + 1, "spawnStartedMonotonicNanos": started,
                                "spawnObservedMillis": (spawned - started) / 1e6, "readyObservedMillisFromSpawnStart": None,
                                "establishesCompilerExecutionOrWarmth": False}
        doctor = self.rpc.call("doctor", {}, deadline=self.deadline(RPC_TIMEOUT_SECONDS))
        ready = time.monotonic_ns()
        require(doctor.get("contractVersion") == CONTRACT and doctor.get("command") == "doctor" and
                doctor.get("installedProvidersInspected") is True and
                provider_inspection(doctor.get("installedProviders")) == self.inspection, "broker_readiness_failed")
        self.session_startup["readyObservedMillisFromSpawnStart"] = (ready - started) / 1e6

    def close_broker(self) -> None:
        if self.transport is None:
            return
        transport = self.transport
        self.transport = self.rpc = None
        record = {"startup": self.session_startup, "transport": transport.statistics(), "cleanup": {"state": "unavailable"}}
        self.sessions.append(record)
        try:
            transport.close()
            record["cleanup"] = dict(transport.cleanup_statistics(), state="owned_group_signaled_leader_reaped_pipes_closed")
        except BaseException:
            record["cleanup"] = {"state": "failed", "forcedHelperLossGuaranteed": False}
            raise

    def params(self, trial: benchmark.Trial) -> dict:
        prepared = trial.client["prepared"]
        return {"credential": trial.client["registration"]["client"], "patchId": prepared["patchId"],
                "idempotencyKey": prepared["idempotencyKey"], "input": prepared["input"]}

    def submit(self, trial: benchmark.Trial) -> None:
        params = self.params(trial)
        trial.broker_session = len(self.sessions) + 1
        prior = [item for item in self.ledger.trials.values() if item.review_id is not None]
        trial.prior_accepted = len(prior)
        trial.prior_unsettled = sum(not item.required_complete for item in prior)
        call_deadline = self.deadline(RPC_TIMEOUT_SECONDS)
        trial.stamp("submitStarted", time.monotonic_ns())
        status_value = self.rpc.call("review.submit", params, deadline=call_deadline)
        trial.observe(status_value, time.monotonic_ns(), "review.submit")
        # Additive, immutable observation files preserve accepted joins if a later
        # batch fails. These files are observations, NOT a crash-tested journal or
        # permission to replay an uncertain submit after this controller dies.
        self.root.write_json("benchmark-" + trial.patch.trial_id + "-accepted.json", trial.public())

    def duplicate(self, trial: benchmark.Trial) -> None:
        call_deadline = self.deadline(RPC_TIMEOUT_SECONDS)
        started = time.monotonic_ns()
        status_value = self.rpc.call("review.submit", self.params(trial), deadline=call_deadline)
        observed = time.monotonic_ns()
        trial.observe(status_value, observed, "duplicate review.submit")
        trial.duplicate_round_trip_ms = (observed - started) / 1e6

    def events(self, name: str) -> None:
        # A client cursor spans ALL its historical accepted reviews. Never route
        # every page to only its newest patch or silently discard old obligations.
        state = self.cursors[name]
        while True:
            params = {"credential": self.clients[name]["client"], "limit": 50}
            if state["cursor"] is not None:
                params["cursor"] = state["cursor"]
            page = self.rpc.call("review.events", params, deadline=self.deadline(RPC_TIMEOUT_SECONDS))
            observed = time.monotonic_ns()
            require(isinstance(page, dict) and page.get("contractVersion") == CONTRACT and
                    isinstance(page.get("events"), list) and len(page["events"]) <= 50 and
                    isinstance(page.get("cursor"), str) and 1 <= len(page["cursor"]) <= 8192 and
                    type(page.get("hasMore")) is bool and (not page["hasMore"] or bool(page["events"])) and
                    (not page["events"] or page["cursor"] != state["cursor"]), "invalid_event_page")
            last_sequence = state["sequence"]
            known = {trial.review_id: trial for trial in self.ledger.trials.values()
                     if trial.review_id is not None and trial.patch.client_id == name}
            validated = []
            page_ids = set()
            for event_value in page["events"]:
                require(isinstance(event_value, dict) and isinstance(event_value.get("reviewId"), str) and
                        event_value["reviewId"] in known, "invalid_event_binding")
                trial = known[event_value["reviewId"]]
                require(event_value.get("contractVersion") == CONTRACT and event_value.get("clientId") == name and
                        type(event_value.get("generation")) is int and event_value["generation"] == trial.client["prepared"]["binding"]["generation"] and
                        event_value.get("patchId") == trial.patch.patch_id and event_value.get("sourceSnapshotId") == trial.join["snapshotId"] and
                        isinstance(event_value.get("eventId"), str) and UUID.fullmatch(event_value["eventId"]) is not None and
                        event_value["eventId"] not in self.seen_event_ids and event_value["eventId"] not in page_ids and
                        type(event_value.get("sequence")) is int and event_value["sequence"] > last_sequence and
                        event_value.get("kind") in {"accepted", "progress", "result", "cancelled"} and
                        event_value.get("deliveryRequired") is True and isinstance(event_value.get("payload"), str), "invalid_event_binding")
                payload_bytes = event_value["payload"].encode("utf-8", errors="strict")
                require(event_value.get("payloadDigest") == "sha256:" + hashlib.sha256(payload_bytes).hexdigest(), "invalid_event_digest")
                payload = parse_json(payload_bytes)
                trial.inspect(payload, accepted_event=True)
                require((event_value["kind"] == "accepted" and payload["state"] == "accepted") or
                        (event_value["kind"] == "progress" and payload["state"] in {"running", "cancelled"}) or
                        (event_value["kind"] == "result" and payload["state"] in TERMINAL_STATES) or
                        (event_value["kind"] == "cancelled" and payload["state"] == "cancelled"), "invalid_event_binding")
                validated.append((trial, payload, event_value))
                page_ids.add(event_value["eventId"])
                last_sequence = event_value["sequence"]
            require(self.ledger.trace_count + len(validated) <= benchmark.MAX_TRACE_ENTRIES, "benchmark_trace_limit")
            # Validate the ENTIRE page before observing any item or moving its cursor.
            for trial, payload, event_value in validated:
                trial.observe(payload, observed, "review.events", event=event_value)
            self.seen_event_ids.update(page_ids)
            state["sequence"], state["cursor"] = last_sequence, page["cursor"]
            state["pages"] += 1
            if not page["hasMore"]:
                return  # Fetch progress only; NEVER review.ack/review.observe.

    def poll_current(self, trial: benchmark.Trial) -> None:
        status_value = self.rpc.call("review.status", {"credential": trial.client["registration"]["client"],
                                     "reviewId": trial.review_id}, deadline=self.deadline(RPC_TIMEOUT_SECONDS))
        trial.observe(status_value, time.monotonic_ns(), "review.status")

    def collect_batch(self) -> None:
        for trial in self.active:
            self.submit(trial)
        # Admit the concurrent batch first, then check idempotency. No extra
        # threads, hidden warm-up, cached-result substitution or automatic retry.
        for trial in self.active:
            self.duplicate(trial)
        while True:
            for trial in self.ledger.trials.values():
                if trial.review_id is not None and not trial.terminal:
                    self.poll_current(trial)
            for name in SOURCES:
                if any(trial.review_id is not None and trial.patch.client_id == name for trial in self.ledger.trials.values()):
                    self.events(name)
            # Events are validated as whole pages before any cursor advances.
            # Confirm EVERY affected accepted review after fetching, including
            # terminal reviews from older batches. A failed confirmation leaves
            # the admission and pending obligation intact for the failure report.
            for trial in self.ledger.trials.values():
                if trial.confirmation_pending:
                    self.poll_current(trial)
            if all(trial.terminal for trial in self.ledger.trials.values() if trial.review_id is not None):
                return
            time.sleep(min(0.025, max(0, (self.deadline_ns() - time.monotonic_ns()) / 1e9)))

    def fail(self, code: str) -> None:
        kind = "timeout" if code in {"preview_example_transport_timeout", "preview_example_benchmark_deadline"} else "aborted"
        if self.failure is None:
            self.failure = {"kind": kind, "code": code}
        self.ledger.abort(kind, code, time.monotonic_ns())

    def run(self) -> dict:
        try:
            self.setup()
            for batch in benchmark.batches(self.scenario, self.ledger.plan):
                self.active = [self.ledger.trials[patch.trial_id] for patch in batch]
                ready = time.monotonic_ns()
                for trial in self.active:
                    trial.stamp("patchComplete", ready)
                for trial in self.active:
                    self.prepare_trial(trial)
                if self.rpc is None:
                    self.start_broker()
                self.collect_batch()
                if self.scenario.warmthRequest == "fresh_broker_resident_dependencies":
                    self.close_broker()
                self.active = []
        except (ExampleError, benchmark.BenchmarkError) as error:
            self.fail(error.code)
        except KeyboardInterrupt:
            self.fail("preview_example_interrupted")
        except Exception:
            self.fail("preview_example_local_failure")
        finally:
            try:
                self.close_broker()
            except (ExampleError, benchmark.BenchmarkError) as error:
                self.fail(error.code)
            except BaseException:
                self.fail("preview_example_cleanup_failed")
        report = self.ledger.report(time.monotonic_ns())
        report.update(collectionState="collected" if self.failure is None else "failed", collectorFailure=self.failure,
                      compilerInspection=self.inspection, initialOwnerSetupObservedMillis=self.preparation_ms,
                      brokerSessions=self.sessions,
                      ownerCommandTransportTotals={field: sum(value[field] for value in self.owner_transport)
                                                   for field in ("stdinBytes", "stdoutBytes", "stderrBytes")},
                      ownerTransportScope="completed owner commands only; no metrics inferred for a failed command",
                      cleanupScope="owned process teardown reported separately; not successful required completion")
        # Unlike the legacy demonstration, failed/censored runs retain all planned
        # trials and uncertain admissions. Never emit success if cleanup failed.
        self.root.write_json("benchmark-observations.json", report)
        return report


def main(argv: list[str] | None = None) -> int:
    parser = SafeArgumentParser(prog="run_preview_example.py", description=__doc__)
    parser.add_argument("--distribution", type=Path, default=Path("modules/cli/build/install/ktchecker"))
    parser.add_argument("--compiler-java-home", required=True, type=Path,
                        help="explicit owner-selected compiler JDK; CLI verifies complete link-free JDK 21 identity")
    parser.add_argument("--output", required=True, type=Path, help="NEW private synthetic demo directory (POSIX)")
    parser.add_argument("--prepare-only", action="store_true",
                        help="Prepare non-authorized private owner records; does not execute a compiler")
    parser.add_argument("--benchmark-scenarios", type=Path, help="bounded scenario JSON; must also select --benchmark-scenario")
    parser.add_argument("--benchmark-scenario", help="explicit scenario ID; smoke observations, not benchmark qualification")
    args = parser.parse_args(argv)
    try:
        require(os.name == "posix", "posix_required")
        require((args.benchmark_scenarios is None) == (args.benchmark_scenario is None) and
                not (args.prepare_only and args.benchmark_scenario is not None), "benchmark_arguments_invalid")
        scenario = None if args.benchmark_scenarios is None else read_benchmark_scenario(args.benchmark_scenarios, args.benchmark_scenario)
        home = args.distribution.resolve(strict=True)
        require(home.is_dir() and (home / "lib").is_dir(), "distribution_missing")
        # Check the explicitly supplied path before creating private state. No auto-selection.
        require(args.compiler_java_home.is_dir() and (args.compiler_java_home / "bin" / "java").is_file(),
                "compiler_java_home_missing")
        with PrivateDirectory(args.output) as root:
            if scenario is not None:
                report = BenchmarkController(home, root, args.compiler_java_home, scenario).run()
            else:
                controller = Controller(home, root, args.compiler_java_home)
                clients = controller.prepare(authorize_execution=not args.prepare_only)
            if scenario is None and args.prepare_only:
                report = {"contractVersion": CONTRACT, "state": "prepared", "clientCount": len(clients),
                          "releaseQualified": False, "compilerExecutionObserved": False, "executionAuthorized": False,
                          "compilerInspection": controller.inspection,
                          "ownerPreparationObservedMillis": controller.preparation_ms,
                          "ownerPreparationTimingScope": "owner commands and teardown; not compiler or useful-feedback latency",
                          "cleanup": {"state": "owner_commands_cleaned",
                                      "ownerCommandObservedMillisTotal": sum(value["cleanup"]["observedMillis"]
                                                                             for value in controller.owner_transport),
                                      "forcedHelperLossGuaranteed": False, "escapedDescendantsSupported": False,
                                      "scope": "owner-command teardown; not compiler or useful-feedback latency"},
                          "requiredScope": "explicit one-rule partial-project preview per client",
                          "contextDelivery": {"state": "not_attempted"},
                          "warning": "owner files contain private credentials; never put credentials in model context"}
            elif scenario is None:
                report = controller.review(clients)
        print(json.dumps(report, indent=2, allow_nan=False))
        return 1 if report.get("collectionState") == "failed" else 0
    except (ExampleError, benchmark.BenchmarkError) as error:
        print(json.dumps({"state": "failed", "code": error.code, "releaseQualified": False}), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print(json.dumps({"state": "failed", "code": "preview_example_interrupted", "releaseQualified": False}), file=sys.stderr)
        return 1
    except Exception:
        # Unexpected local failures must not print arguments, owner files, or tracebacks.
        print(json.dumps({"state": "failed", "code": "preview_example_local_failure", "releaseQualified": False}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
