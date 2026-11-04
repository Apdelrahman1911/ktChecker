#!/usr/bin/env python3
"""Check the committed PR/push range; a clean worktree diff would check nothing."""

import os
import re
import subprocess
import sys


def git_check_command(env):
    base = env.get("KT_PR_BASE") or env.get("KT_PUSH_BASE") or ""
    if base and not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", base):
        raise ValueError("GitHub base revision is not a commit hash")
    if base and set(base) != {"0"}:
        return ["git", "diff", "--check", base, "HEAD"]
    return ["git", "show", "--format=", "--check", "HEAD"]


if __name__ == "__main__":
    try:
        command = git_check_command(os.environ)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(2)
    raise SystemExit(subprocess.run(command, check=False).returncode)
