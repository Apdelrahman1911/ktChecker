"""Small, dependency-free helpers for bounded, attributable CI execution."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
from datetime import datetime, timezone


REPO_ROOT = Path(__file__).resolve().parents[2]
MAX_LOG_BYTES = 20 * 1024 * 1024
TASK_LINE = re.compile(
    r"^> Task (?P<task>:\S+?)(?: (?P<outcome>UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?\s*$",
    re.MULTILINE,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def digest_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def digest_file(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return "sha256:" + hasher.hexdigest()


def canonical_digest(value: object) -> str:
    return digest_bytes(json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8"))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def read_bounded(path: Path, limit: int = MAX_LOG_BYTES) -> str:
    if path.stat().st_size > limit:
        raise ValueError(f"Input exceeds {limit} bytes: {path.name}")
    return path.read_bytes().decode("utf-8", errors="replace")


def source_identity(root: Path = REPO_ROOT) -> dict:
    """Hash actual tracked/untracked nonignored bytes, not just the commit label.

    Symlinks bind the link target text rather than silently reading outside the
    checkout. Missing tracked files and executable bits are also represented.
    Only the aggregate is emitted; source contents are never copied to evidence.
    """
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, check=True, timeout=30
    ).stdout.decode("ascii").strip()
    paths = subprocess.run(
        ["git", "ls-files", "-c", "-o", "--exclude-standard", "-z"],
        cwd=root, capture_output=True, check=True, timeout=30,
    ).stdout.split(b"\0")
    manifest = []
    for encoded in sorted(set(paths)):
        if not encoded:
            continue
        relative = os.fsdecode(encoded)
        path = root / relative
        if path.is_symlink():
            target = os.fsencode(os.readlink(path))
            entry = {"path": relative, "kind": "symlink", "size": len(target), "digest": digest_bytes(target)}
        elif path.is_file():
            entry = {"path": relative, "kind": "file", "size": path.stat().st_size,
                     "digest": digest_file(path), "executable": bool(path.stat().st_mode & 0o111)}
        elif not path.exists():
            entry = {"path": relative, "kind": "missing"}
        else:
            # Git submodules are not part of this repository's implementation tuple.
            entry = {"path": relative, "kind": "unsupported_directory"}
        manifest.append(entry)
    dirty = bool(subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=normal"],
        cwd=root, capture_output=True, check=True, timeout=30,
    ).stdout)
    return {"commit": commit, "dirty": dirty, "file_count": len(manifest),
            "working_tree_digest": canonical_digest(manifest),
            "digest_scope": "tracked + untracked nonignored actual bytes, missing files, symlinks and executable bits",
            "unsupported_directories": sum(e["kind"] == "unsupported_directory" for e in manifest)}


def parse_gradle_tasks(output: str) -> dict[str, str]:
    return {match["task"]: match["outcome"] or "EXECUTED" for match in TASK_LINE.finditer(output)}


def terminate_tree(process: subprocess.Popen) -> None:
    """Best-effort CI group termination, not production ownership/cleanup proof.

    Call only while this caller has not observed/reaped direct-child completion.
    In run_logged, poll returning None precedes this call; successful poll/wait
    ends the signal path. A wait timeout does not reap the child. Do not add a
    group signal after a successful wait: this uses a numeric PGID, not a native
    supervisor's held-leader protocol, and cannot prove descendants are absent.
    """
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=False)
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        if os.name != "nt":
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        else:
            process.kill()
        process.wait(timeout=5)


def run_logged(
    argv: list[str] | str, *, cwd: Path, log_base: Path, timeout: int = 1200,
    max_output_bytes: int = MAX_LOG_BYTES, env: dict[str, str] | None = None,
    separate_stderr: bool = False, additional_output_paths: tuple[Path, ...] = (),
) -> tuple[dict, str, str]:
    """Keep bounded raw logs locally; callers emit sanitized JSON, not transcripts.

    A string is only used for the explicitly quoted, fixed Windows batch-launcher
    smoke command. All Gradle/tool commands use argv lists, without a shell.
    This is a trusted CI harness, not a sandbox or reviewed-runtime containment.
    Timeout/output checks are polled best-effort limits; neither direct-child
    completion nor termination proves that descendants or inherited log writers
    are gone. File-backed logs have no pipe-drain loop; streaming their digests
    does not establish an immutable snapshot if an unowned writer survives.
    """
    log_base.parent.mkdir(parents=True, exist_ok=True)
    stdout_path = log_base.with_suffix(".stdout.log")
    stderr_path = log_base.with_suffix(".stderr.log")
    started = time.monotonic()
    result = {"started_at": utc_now(), "command": argv, "state": "not_started",
              "return_code": None, "elapsed_seconds": 0.0}

    def additional_bytes() -> int:
        total = 0
        for path in additional_output_paths:
            try:
                total += path.stat().st_size
            except FileNotFoundError:
                pass
        return total

    with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
        try:
            process = subprocess.Popen(
                argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=stdout,
                stderr=stderr if separate_stderr else subprocess.STDOUT,
                start_new_session=os.name != "nt",
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
            )
        except OSError as error:
            result.update(state="unavailable", reason=type(error).__name__)
        else:
            while process.poll() is None:
                if time.monotonic() - started > timeout:
                    result.update(state="timeout", reason=f"Exceeded {timeout} seconds")
                    terminate_tree(process)
                    break
                if stdout_path.stat().st_size + stderr_path.stat().st_size + additional_bytes() > max_output_bytes:
                    result.update(state="output_limit", reason=f"Exceeded {max_output_bytes} output bytes")
                    terminate_tree(process)
                    break
                time.sleep(0.2)
            # Normally this retrieves cached completion, not a pipe drain.
            # Even a missing group is not itself a completion proof, so keep
            # this last wait bounded. Never retry numeric-group signaling here:
            # an earlier successful poll/wait may already have reaped the leader.
            try:
                result["return_code"] = process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                result.update(state="cleanup_unverified", reason="Final direct-child wait exceeded 5 seconds")
                result["return_code"] = process.returncode
            if result["state"] == "not_started":
                result["state"] = "completed"
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    # A single fast child can exceed the bound before the polling interval.
    extra_bytes = additional_bytes()
    total_bytes = stdout_path.stat().st_size + stderr_path.stat().st_size + extra_bytes
    if total_bytes > max_output_bytes:
        result.update(state="output_limit", reason=f"Exceeded {max_output_bytes} output bytes")
    result["output_bytes"] = total_bytes
    if additional_output_paths:
        result["additional_output_bytes"] = extra_bytes
        result["additional_output_paths_monitored"] = True
    result["stdout_digest"] = digest_file(stdout_path)
    result["stderr_digest"] = digest_file(stderr_path)
    result["raw_logs_uploaded"] = False
    # A fast child can overrun before the polling interval. Do not allocate its
    # entire logfile merely to return the bounded preview; digests above stream.
    with stdout_path.open("rb") as stdout:
        stdout_text = stdout.read(max_output_bytes).decode("utf-8", errors="replace")
    with stderr_path.open("rb") as stderr:
        stderr_text = stderr.read(max_output_bytes).decode("utf-8", errors="replace")
    return result, stdout_text, stderr_text


def wrapper_argv(root: Path = REPO_ROOT) -> list[str]:
    wrapper = root / "gradlew"
    if not wrapper.is_file():
        raise FileNotFoundError("The checked-in repository Gradle wrapper is absent")
    if os.name == "nt":
        # Hosted Windows includes Git Bash. Avoid shell-string interpolation and
        # exercise the same checksum-pinned wrapper on every build host.
        git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
        bash = str(git_bash) if git_bash.is_file() else shutil.which("bash")
        if not bash:
            raise FileNotFoundError("Git Bash is required for the Windows Gradle build lane")
        return [bash, wrapper.as_posix()]
    return [str(wrapper)]


def gradle_result(
    arguments: list[str], *, log_base: Path, root: Path = REPO_ROOT, timeout: int = 1200,
    require_tasks: tuple[str, ...] = (), expected_failure: bool = False,
    diagnostic_pattern: str | None = None, failed_task: str | tuple[str, ...] | None = None,
    additional_output_paths: tuple[Path, ...] = (),
) -> tuple[dict, str]:
    try:
        command = wrapper_argv(root) + ["--no-daemon", "--console=plain", "--max-workers=2",
                                       "--no-parallel", "--no-configuration-cache"] + arguments
    except OSError as error:
        return {"state": "failed", "reason": str(error), "return_code": None,
                "errors": [str(error)], "tasks": {}, "expected_failure": expected_failure,
                "required_tasks": list(require_tasks)}, ""
    result, output, _ = run_logged(command, cwd=root, log_base=log_base, timeout=timeout,
                                  additional_output_paths=additional_output_paths)
    result["tasks"] = parse_gradle_tasks(output)
    failed_tasks = (failed_task,) if isinstance(failed_task, str) else failed_task or ()
    errors = []
    if result["state"] != "completed":
        errors.append(result.get("reason", "Command did not complete"))
    elif expected_failure:
        if result["return_code"] == 0:
            errors.append("Negative control unexpectedly succeeded")
        if not any(result["tasks"].get(task) == "FAILED" for task in failed_tasks):
            errors.append("Expected diagnostic-producing task did not fail")
        if not diagnostic_pattern or not re.search(diagnostic_pattern, output, re.IGNORECASE | re.MULTILINE):
            errors.append("Expected diagnostic was absent; unrelated failure is not a positive control")
    elif result["return_code"] != 0:
        errors.append(f"Gradle exited {result['return_code']}")
    for task in require_tasks:
        if result["tasks"].get(task) != "EXECUTED":
            errors.append(f"Required task did not execute: {task} ({result['tasks'].get(task, 'absent')})")
    result["expected_failure"] = expected_failure
    result["expected_failed_tasks"] = list(failed_tasks)
    result["required_tasks"] = list(require_tasks)
    result["errors"] = errors
    result["state"] = "passed" if not errors else "failed"
    return result, output
