#!/usr/bin/env python3
"""Run the real pinned wrapper and retain executed-task evidence."""

import argparse
from pathlib import Path
import sys

if __package__:
    from .ci_common import REPO_ROOT, gradle_result, source_identity, write_json
else:
    from ci_common import REPO_ROOT, gradle_result, source_identity, write_json


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=1200)
    parser.add_argument("--require-task", action="append", default=[])
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.timeout < 1 or args.timeout > 2400:
        parser.error("timeout must be between 1 and 2400 seconds")
    arguments = args.arguments[1:] if args.arguments[:1] == ["--"] else args.arguments
    if not arguments:
        parser.error("provide Gradle tasks or --version after --")
    identity = source_identity()
    result, output = gradle_result(
        arguments, log_base=args.output.parent / "raw" / args.output.stem,
        timeout=args.timeout, require_tasks=tuple(args.require_task),
    )
    result.update(schema_version=1, evidence_kind="executed_gradle_command", source=identity,
                  qualification="preview; not rule qualification or host model observation")
    if "--version" in arguments:
        import re
        match = re.search(r"^Gradle ([\d.]+)\s*$", output, re.MULTILINE)
        result["executed_gradle_version"] = match[1] if match else None
        if match is None or match[1] != "9.6.1":
            result["state"] = "failed"
            result["errors"].append("Executed wrapper version is not the pinned Gradle 9.6.1")
    write_json(args.output, result)
    print(f"Gradle evidence: {result['state']} ({args.output})")
    for error in result.get("errors", []):
        print(error, file=sys.stderr)
    if result["state"] != "passed" and output:
        # Visible job diagnostics, but deliberately excluded from uploaded evidence.
        print("Last bounded Gradle output (raw transcript is not uploaded):", file=sys.stderr)
        print(output[-8000:], file=sys.stderr)
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
