#!/usr/bin/env python3
"""Build only the current host's bundled, preview owned-child supervisor.

This is a build-time tool, not a runtime fallback. Compiler transcripts are bounded
and never printed or persisted. Failed attempts retain their guard and any private
output residue without automatic reclamation. The separate receipt identifies the
driver and inputs, not the transitive compiler/linker/SDK closure or reproducibility
of its output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import stat
import subprocess
import sys
import time


PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
SCHEMA = "ktchecker-owned-child-supervisor-build/1"
PREFIX = Path("dev/ktchecker/providers/runtime/native")
FLAGS = ("-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pedantic")
SUPPORTED = {"linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64"}
MAX_BINARY = 1_048_576
MAX_RECEIPT = 16_384
MAX_COMPILER_OUTPUT = 65_536
PUBLIC_COMPILER_ROOTS = (
    "/bin/", "/usr/bin/", "/usr/lib/", "/usr/libexec/",
    "/Library/Developer/CommandLineTools/usr/bin/",
    "/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/",
)
COMPILER_ENVIRONMENT = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"}


class BuildProblem(Exception):
    """Public reason only; do not embed exception messages or private paths."""


def digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def file_digest(path: Path, maximum: int) -> str:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= maximum:
        raise BuildProblem("native_supervisor_build_input_invalid")
    value = hashlib.sha256()
    total = 0
    with path.open("rb") as source:
        for data in iter(lambda: source.read(65_536), b""):
            total += len(data)
            if total > maximum:
                raise BuildProblem("native_supervisor_build_input_invalid")
            value.update(data)
    if total != info.st_size:
        raise BuildProblem("native_supervisor_build_input_changed")
    return "sha256:" + value.hexdigest()


def host_identity(system: str | None = None, machine: str | None = None) -> dict[str, str]:
    system = platform.system() if system is None else system
    machine = platform.machine() if machine is None else machine
    architecture = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64", "aarch64": "aarch64"}.get(
        machine.lower(), "unsupported")
    host_os = {"linux": "linux", "darwin": "macos", "windows": "windows"}.get(system.lower(), "unsupported")
    target = f"{host_os}-{architecture}" if "unsupported" not in (host_os, architecture) else "unsupported"
    return {"os": host_os, "architecture": architecture, "target": target}


def public_compiler_path(path: Path) -> str:
    text = str(path)
    if (re.fullmatch(r"[A-Za-z0-9_./+\-]+", text) and ".." not in path.parts and
            any(text.startswith(root) for root in PUBLIC_COMPILER_ROOTS)):
        return text
    return "<private-compiler>"


def public_version_line(_output: bytes) -> tuple[str, bool]:
    # Even a recognized prefix can precede a private token ("gcc secret-token").
    # Hash the bounded exact --version bytes, but retain no raw banner text.
    return "<redacted>", True


def bounded_command(argv: list[str], timeout: float) -> bytes:
    """Trusted direct compiler driver only, not descendant containment.

    Deadlines are checked cooperatively around launch/I/O/cleanup; OS calls are
    not preemptible here. A constructor exception without a returned handle is
    launch-unknown, not evidence of no child and not authorization to retry.
    """
    if not math.isfinite(timeout) or timeout <= 0:
        raise BuildProblem("native_supervisor_compiler_timeout")
    deadline = time.monotonic() + timeout

    def remaining_time() -> float:
        remaining = deadline - time.monotonic()
        if not math.isfinite(remaining) or remaining <= 0:
            raise BuildProblem("native_supervisor_compiler_timeout")
        return remaining

    process = None
    selector = None
    cleanup_observed = False
    try:
        remaining_time()
        try:
            process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, env=COMPILER_ENVIRONMENT)
        except BaseException:
            if process is None:
                raise BuildProblem("native_supervisor_compiler_launch_unknown") from None
            raise
        remaining_time()
        if process.stdout is None:
            raise BuildProblem("native_supervisor_compiler_failed")
        output = bytearray()
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        while selector.get_map():
            remaining = remaining_time()
            for key, _ in selector.select(min(remaining, 0.1)):
                remaining_time()
                part = os.read(key.fd, 8192)
                remaining_time()
                if not part:
                    selector.unregister(key.fd)
                else:
                    if len(output) + len(part) > MAX_COMPILER_OUTPUT:
                        raise BuildProblem("native_supervisor_compiler_output_limit")
                    output.extend(part)
        remaining = remaining_time()
        try:
            code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            raise BuildProblem("native_supervisor_compiler_timeout") from None
        remaining_time()
        if code != 0:
            raise BuildProblem("native_supervisor_compiler_failed")
        result = bytes(output)
    finally:
        try:
            try:
                if process is not None:
                    try:
                        if process.poll() is None:
                            process.kill()  # owned, still-unreaped direct compiler only
                    finally:
                        cleanup_observed = process.wait(timeout=2) is not None
            finally:
                try:
                    if selector is not None:
                        selector.close()
                finally:
                    if process is not None and process.stdout is not None:
                        process.stdout.close()
        finally:
            # Neither a close failure nor interruption may hide unknown direct
            # child retirement. No returned handle remains launch-unknown.
            if process is not None and not cleanup_observed:
                raise BuildProblem("native_supervisor_compiler_cleanup_unknown") from None
    # A zero exit or successful close observed at/after the deadline is not a
    # successful bounded command, including when cleanup itself used the budget.
    remaining_time()
    return result


def _write_attempt_phase(attempt: Path, phase: str) -> None:
    """Fixed, bounded custody metadata; never paths, output, or error messages."""
    if phase not in ("preflight", "version_probe_pending", "version_probe_direct_return_observed",
                     "compile_pending", "compile_direct_return_observed"):
        raise BuildProblem("native_supervisor_build_attempt_state_invalid")
    data = f"ktchecker-native-compiler-attempt/1\n{phase}\n".encode("ascii")
    if (attempt / "phase").write_bytes(data) != len(data):
        raise BuildProblem("native_supervisor_build_attempt_state_failed")


def _clear_resource_tree(root: Path) -> Path:
    """Exclusively own this output before clearing it; never reclaim an attempt.

    The sibling guard survives clearing PREFIX, including for unsupported hosts.
    mkdir is the acquisition, not an existence check followed by destructive
    cleanup. Any retained entry (even malformed state) refuses reuse. This is
    per-output exclusivity, not protection from arbitrary ancestor-tree removal.
    """
    attempt = root.with_name(f".{root.name}.compiler-attempt")
    try:
        attempt.parent.mkdir(parents=True, exist_ok=True)
    except OSError:
        raise BuildProblem("native_supervisor_build_attempt_unavailable") from None
    try:
        attempt.mkdir(mode=0o700, exist_ok=False)
    except FileExistsError:
        raise BuildProblem("native_supervisor_build_attempt_retained") from None
    except OSError:
        raise BuildProblem("native_supervisor_build_attempt_unavailable") from None
    # The directory entry already guards custody if this write or clear fails.
    _write_attempt_phase(attempt, "preflight")
    if root.is_symlink():
        raise BuildProblem("native_supervisor_output_invalid")
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    return attempt


def build(output: Path, cc: Path, *, host: dict[str, str] | None = None) -> dict:
    source = Path(__file__).resolve().with_name("owned_process_supervisor.c")
    builder = Path(__file__).resolve()
    resource_root = output.absolute() / PREFIX
    # Atomic output ownership precedes every destructive clear or launch. Never
    # clean up on an exception: even --version can have an unknown constructor
    # outcome, and filesystem housekeeping must not mask that public reason.
    attempt = _clear_resource_tree(resource_root)
    selected_host = host_identity() if host is None else host
    source_sha = file_digest(source, MAX_BINARY)
    builder_sha = file_digest(builder, MAX_BINARY)
    receipt = {
        "schemaVersion": SCHEMA,
        "protocolVersion": PROTOCOL,
        "status": "unavailable",
        "reason": "unsupported_host",
        "host": selected_host,
        "compiler": None,
        "inputs": {
            "source": {"path": "native/owned_process_supervisor.c", "sha256": source_sha},
            "builder": {"path": "native/build_supervisor.py", "sha256": builder_sha},
        },
        "invocation": None,
        "output": None,
        "compilerDriverIdentityOnly": True,
        "transitiveCompilerToolchainVerified": False,
        "sourceToBinaryReproducibilityEstablished": False,
        "privacy": {
            "privatePathsRetained": False,
            "rawCompilerTranscriptRetained": False,
            "versionFirstLineRedacted": False,
        },
    }
    binary_sha = "unavailable"
    target = selected_host["target"]
    if target in SUPPORTED:
        if not cc.is_absolute() or not cc.is_file() or not os.access(cc, os.X_OK):
            raise BuildProblem("native_supervisor_compiler_unavailable")
        resolved_cc = cc.resolve(strict=True)
        compiler_sha = file_digest(resolved_cc, 256 * 1024 * 1024)
        _write_attempt_phase(attempt, "version_probe_pending")
        version = bounded_command([str(cc), "--version"], 10)
        _write_attempt_phase(attempt, "version_probe_direct_return_observed")
        first_line, version_redacted = public_version_line(version)
        binary = attempt / "supervisor"
        argv = [str(cc), *FLAGS, str(source), "-o", str(binary)]
        # The exact private command is hashed, never persisted or printed.
        exact_argv = json.dumps(argv, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
        _write_attempt_phase(attempt, "compile_pending")
        bounded_command(argv, 60)
        _write_attempt_phase(attempt, "compile_direct_return_observed")
        binary_sha = file_digest(binary, MAX_BINARY)
        if (file_digest(source, MAX_BINARY) != source_sha or file_digest(builder, MAX_BINARY) != builder_sha or
                file_digest(cc.resolve(strict=True), 256 * 1024 * 1024) != compiler_sha):
            raise BuildProblem("native_supervisor_build_input_changed")
        binary_size = binary.stat().st_size
        destination = resource_root / target / "supervisor"
        destination.parent.mkdir()
        binary.chmod(0o755)
        os.replace(binary, destination)
        receipt["status"] = "built"
        receipt["reason"] = None
        receipt["compiler"] = {
            "selectedPath": public_compiler_path(cc),
            "resolvedPath": public_compiler_path(resolved_cc),
            "driverSha256": compiler_sha,
            "versionFirstLine": first_line,
            "versionOutputSha256": digest(version),
            "versionOutputBytes": len(version),
        }
        receipt["invocation"] = {
            "argv": ["<compiler>", *FLAGS, "<source>", "-o", "<output>"],
            "flags": list(FLAGS),
            "exactArgvSha256": digest(exact_argv),
            "exactArgvEncoding": "utf8-json-compact-ascii",
            "privateArgvRetained": False,
        }
        receipt["output"] = {"path": (PREFIX / target / "supervisor").as_posix(), "sha256": binary_sha, "bytes": binary_size}
        receipt["privacy"]["versionFirstLineRedacted"] = version_redacted
    encoded_receipt = (json.dumps(receipt, ensure_ascii=True, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(encoded_receipt) > MAX_RECEIPT:
        raise BuildProblem("native_supervisor_receipt_limit")
    manifest = f"{PROTOCOL}\n{target}\n{source_sha}\n{binary_sha}\n".encode("utf-8")
    if (resource_root / "build-provenance.json").write_bytes(encoded_receipt) != len(encoded_receipt):
        raise BuildProblem("native_supervisor_build_publication_failed")
    if (resource_root / "manifest").write_bytes(manifest) != len(manifest):
        raise BuildProblem("native_supervisor_build_publication_failed")
    # Release only after normal, known-success completion. Empty-directory
    # removal cannot recursively discard unexpected residue. No exception-path
    # cleanup or descendant-absence claim follows from these direct returns.
    (attempt / "phase").unlink()
    attempt.rmdir()
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cc", type=Path, required=True, help="absolute owner-selected compiler driver; no PATH fallback")
    args = parser.parse_args()
    try:
        receipt = build(args.output, args.cc)
    except BuildProblem as error:
        print(str(error), file=sys.stderr)
        return 1
    except Exception:
        print("native_supervisor_build_failed", file=sys.stderr)
        return 1
    print(f"native-supervisor {receipt['status']} {receipt['host']['target']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
