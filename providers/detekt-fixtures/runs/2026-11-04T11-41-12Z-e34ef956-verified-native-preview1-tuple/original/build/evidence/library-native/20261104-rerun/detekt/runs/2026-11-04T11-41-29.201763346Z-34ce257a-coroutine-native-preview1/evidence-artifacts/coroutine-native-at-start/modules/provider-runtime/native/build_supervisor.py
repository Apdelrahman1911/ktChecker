#!/usr/bin/env python3
"""Build only the current host's bundled, preview owned-child supervisor.

This is a build-time tool, not a runtime fallback. Compiler output is bounded and
never printed or retained. The separate receipt identifies the driver and inputs,
not the transitive compiler/linker/SDK closure or reproducibility of its output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import selectors
import shutil
import stat
import subprocess
import sys
import tempfile
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
    """Trusted build driver, bounded combined diagnostics, no shell or transcript."""
    process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, env=COMPILER_ENVIRONMENT)
    assert process.stdout is not None
    output = bytearray()
    deadline = time.monotonic() + timeout
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BuildProblem("native_supervisor_compiler_timeout")
            for key, _ in selector.select(min(remaining, 0.1)):
                part = os.read(key.fd, 8192)
                if not part:
                    selector.unregister(key.fd)
                else:
                    if len(output) + len(part) > MAX_COMPILER_OUTPUT:
                        raise BuildProblem("native_supervisor_compiler_output_limit")
                    output.extend(part)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BuildProblem("native_supervisor_compiler_timeout")
        try:
            code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            raise BuildProblem("native_supervisor_compiler_timeout") from None
        if code != 0:
            raise BuildProblem("native_supervisor_compiler_failed")
        return bytes(output)
    finally:
        selector.close()
        process.stdout.close()
        if process.poll() is None:
            process.kill()  # positive PID of our still-unreaped direct child only
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass


def _clear_resource_tree(root: Path) -> None:
    """The caller selected generated resources; never reuse a stale native image."""
    if root.is_symlink():
        raise BuildProblem("native_supervisor_output_invalid")
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)


def build(output: Path, cc: Path, *, host: dict[str, str] | None = None) -> dict:
    source = Path(__file__).resolve().with_name("owned_process_supervisor.c")
    builder = Path(__file__).resolve()
    resource_root = output.absolute() / PREFIX
    # Validate/clear before invoking a compiler: a failed rebuild must not leave
    # a previously generated binary/manifest available as a fresh result.
    _clear_resource_tree(resource_root)
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
        version = bounded_command([str(cc), "--version"], 10)
        first_line, version_redacted = public_version_line(version)
        with tempfile.TemporaryDirectory(prefix=".native-supervisor-build-", dir=resource_root) as temporary:
            binary = Path(temporary) / "supervisor"
            argv = [str(cc), *FLAGS, str(source), "-o", str(binary)]
            # The exact private command is hashed, never persisted or printed.
            exact_argv = json.dumps(argv, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
            bounded_command(argv, 60)
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
    (resource_root / "build-provenance.json").write_bytes(encoded_receipt)
    (resource_root / "manifest").write_bytes(manifest)
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
