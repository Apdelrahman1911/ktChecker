#!/usr/bin/env python3
"""Explicit owner/controller example, not an automatic project importer or model host.

Creates two clients and immutable Kotlin overlays in a NEW private demo directory.
All source is synthetic; no existing project source is read or modified. The poller
is application code, not a model reasoning loop. Never publishes credentials.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import time


RULE = "ktc.compiler.common.abstract_member_not_implemented"
CONTRACT = "ktchecker/preview-1"


def write_private(path: Path, value: object) -> None:
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(value, stream, ensure_ascii=False)
        stream.write("\n")


class Controller:
    def __init__(self, home: Path, root: Path):
        java_home = os.environ.get("JAVA_HOME")
        java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
        # Direct installed Java entry avoids shell evaluation of owner-selected paths.
        self.command = [java, "-Xmx512m", "-cp", str(home / "lib" / "*"), "dev.ktchecker.cli.MainKt",
                        "--state", str(root), "--compiler-dir", str(home / "providers" / "compiler")]
        self.root = root

    def call(self, *args: str) -> dict:
        result = subprocess.run(self.command + ["--workers", "0", *args], capture_output=True,
                                encoding="utf-8", errors="strict", timeout=90)
        if result.returncode != 0:
            # Avoid embedding private source paths or credential documents in a public error.
            raise RuntimeError(f"owner command {args[0]} failed with exit {result.returncode}; inspect private output locally")
        return json.loads(result.stdout)

    def prepare(self) -> list[dict]:
        clients: list[dict] = []
        base_snapshot = None
        base_revision = 0
        for index, name in enumerate(("agent-a", "agent-b")):
            credentials = self.root / f"{name}-credentials.json"
            self.call("register", "--workspace", "example-project", "--worktree", "shared-overlay",
                      "--client", name, "--session", f"session-{name}", "--lineage", f"task-{name}",
                      "--out", str(credentials))
            source = ("interface Contract { fun answer(): Int }\nclass Broken : Contract\n" if index == 0
                      else "package independent\nfun unrelated(): Int = 42\n")
            overlay = {"workspaceId": "example-project", "worktreeId": "shared-overlay", "clientId": name,
                       "sessionId": f"session-{name}", "patchId": "patch-1", "baseSnapshotId": base_snapshot,
                       "expectedHeadRevision": base_revision,
                       "changes": [{"operation": "put", "path": f"{name}/Main.kt", "content": source}]}
            overlay_path = self.root / f"{name}-overlay.json"
            write_private(overlay_path, overlay)
            committed = self.call("overlay", "--request", str(overlay_path))
            snapshot_id = committed["manifest"]["snapshotId"]
            base_snapshot, base_revision = snapshot_id, committed["headRevision"]
            compilation = {"clientId": name, "snapshotId": snapshot_id, "patchId": "patch-1",
                           "idempotencyKey": "request-1", "compilationFiles": [f"{name}/Main.kt"],
                           "requiredRuleIds": [RULE], "scope": {"module": f":{name}", "sourceSet": "main",
                           "target": "jvm", "compilation": "main"}}
            spec = self.root / f"{name}-compilation.json"
            prepared_path = self.root / f"{name}-prepared.json"
            write_private(spec, compilation)
            self.call("prepare", "--request", str(spec), "--authorize-execution", "--out", str(prepared_path))
            prepared = json.loads(prepared_path.read_text(encoding="utf-8"))
            registration = json.loads(credentials.read_text(encoding="utf-8"))
            clients.append({"prepared": prepared, "registration": registration})
        return clients

    def review(self, clients: list[dict]) -> dict:
        process = subprocess.Popen(self.command + ["rpc", "--workers", "2"], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        counter = 0

        def rpc(method: str, params: dict) -> dict:
            nonlocal counter
            counter += 1
            process.stdin.write(json.dumps({"id": str(counter), "method": method, "params": params}) + "\n")
            process.stdin.flush()
            line = process.stdout.readline(16_777_218)
            if not line or len(line.encode("utf-8")) > 16_777_217:
                raise RuntimeError("private broker transport closed or exceeded its byte bound")
            value = json.loads(line)
            if value.get("id") != str(counter) or value.get("contractVersion") != CONTRACT or "error" in value:
                raise RuntimeError(f"private broker {method} rejected; inspect owner state")
            return value["result"]

        try:
            rpc("doctor", {})  # Measures acknowledgment AFTER explicit warm broker initialization.
            accepted = []
            for client in clients:
                prepared, credential = client["prepared"], client["registration"]["client"]
                submitted_at = time.monotonic_ns()
                params = {"credential": credential, "patchId": prepared["patchId"],
                          "idempotencyKey": prepared["idempotencyKey"], "input": prepared["input"]}
                status = rpc("review.submit", params)
                acknowledged_at = time.monotonic_ns()
                duplicate = rpc("review.submit", params)
                if status["reviewId"] != duplicate["reviewId"]:
                    raise AssertionError("duplicate accepted patch created another review")
                accepted.append({"client": client, "status": status, "submitted": submitted_at,
                                 "ack_ms": (acknowledged_at - submitted_at) / 1e6, "first_ms": None})
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline and any(item["status"].get("result") is None for item in accepted):
                for item in accepted:
                    credential = item["client"]["registration"]["client"]
                    item["status"] = rpc("review.status", {"credential": credential, "reviewId": item["status"]["reviewId"]})
                    page = rpc("review.events", {"credential": credential, "limit": 50})
                    for event in page["events"]:
                        if event["clientId"] != credential["clientId"]:
                            raise AssertionError("event leaked across client capabilities")
                        payload = json.loads(event["payload"])
                        result = payload.get("result") or payload
                        if result.get("findings") and item["first_ms"] is None:
                            item["first_ms"] = (time.monotonic_ns() - item["submitted"]) / 1e6
                    if item["status"].get("result") and "terminal_ms" not in item:
                        item["terminal_ms"] = (time.monotonic_ns() - item["submitted"]) / 1e6
                time.sleep(0.025)
            if any(item["status"].get("result") is None for item in accepted):
                raise TimeoutError("required analysis did not reach a terminal preview result")
            first = accepted[0]["status"]["result"]
            if not any(f["ruleId"] == RULE for f in first["findings"]):
                raise AssertionError("real pinned compiler finding not observed; unavailability is not a passing example")
            report = {"contractVersion": CONTRACT, "releaseQualified": False,
                      "scenario": "two immutable compilation snapshots, independent clients, one persistent broker",
                      "contextDelivery": {"state": "not_attempted", "reason": "application poller is not an agent host"},
                      "warmCompiler": False, "requiredScope": "explicit one-rule partial-project preview per client",
                      "observations": [{"clientId": item["status"]["clientId"],
                                        "reviewId": item["status"]["reviewId"],
                                        "sourceSnapshotId": item["status"]["sourceSnapshotId"],
                                        "acknowledgmentMillis": item["ack_ms"],
                                        "firstUsefulObservedMillis": item["first_ms"],
                                        "terminalResultMillis": item.get("terminal_ms"),
                                        "requiredAnalysisComplete": False,
                                        "cpuAndRss": item["status"]["result"]["metrics"],
                                        "state": item["status"]["state"],
                                        "findingCount": len(item["status"]["result"]["findings"]),
                                        "reasons": item["status"]["result"]["reasons"]}
                                       for item in accepted]}
            write_private(self.root / "observations.json", report)
            return report
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            process.stdout.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, default=Path("modules/cli/build/install/ktchecker"))
    parser.add_argument("--output", required=True, type=Path, help="NEW private synthetic demo directory")
    parser.add_argument("--prepare-only", action="store_true", help="Prepare owner records for a separate host integration")
    args = parser.parse_args()
    home = args.distribution.resolve(strict=True)
    root = args.output.absolute()
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    controller = Controller(home, root)
    clients = controller.prepare()
    if args.prepare_only:
        print(json.dumps({"state": "prepared", "clientCount": len(clients), "releaseQualified": False,
                          "warning": "credential and prepared files are private; do not insert credentials into model context"}))
    else:
        print(json.dumps(controller.review(clients), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
